package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.CompanionTask;
import com.apocscode.mcai.task.MineOresTask;
import com.apocscode.mcai.task.OreGuide;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;

/**
 * AI Tool: Mine ores nearby.
 * Supports targeted ore mining (e.g. "mine iron") and general ore mining.
 * Includes Y-level intelligence — warns if companion is at the wrong depth.
 */
public class MineOresTool implements AiTool {

    @Override
    public String name() {
        return "mine_ores";
    }

    @Override
    public String description() {
        return "Mine ore blocks near the companion. Can target a specific ore type (iron, diamond, gold, etc.) " +
                "or mine all ores. Includes Y-level awareness — warns if the companion is at the wrong depth. " +
                "The companion scans nearby, navigates to ores, mines them, and collects drops. " +
                "Specify 'ore' to target a specific type, 'radius' (default 16), and 'maxOres' (default 32). " +
                "If no ores found in scan radius, companion will report back so you can use dig_down or strip_mine " +
                "to reach the right Y-level first.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject ore = new JsonObject();
        ore.addProperty("type", "string");
        ore.addProperty("description",
                "Target ore type to mine. Examples: 'iron', 'diamond', 'gold', 'coal', 'copper', " +
                "'lapis', 'redstone', 'emerald'. If omitted, mines ALL ore types found nearby.");
        props.add("ore", ore);

        JsonObject radius = new JsonObject();
        radius.addProperty("type", "integer");
        radius.addProperty("description", "Search radius for ores. Default: 16, max: 24");
        props.add("radius", radius);

        JsonObject maxOres = new JsonObject();
        maxOres.addProperty("type", "integer");
        maxOres.addProperty("description", "Maximum number of ore blocks to mine. Default: 32.");
        props.add("maxOres", maxOres);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description",
                "Optional: describe what to do AFTER mining completes. " +
                "Example: 'smelt raw_iron then craft iron_pickaxe'. " +
                "If set, the AI will automatically continue the plan when the task finishes.");
        props.add("plan", plan);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            int radius = args.has("radius") ? args.get("radius").getAsInt() : 16;
            int maxOres = args.has("maxOres") ? args.get("maxOres").getAsInt() : 32;
            radius = Math.min(radius, 24);

            // Parse optional ore target
            String oreTarget = args.has("ore") ? args.get("ore").getAsString().trim() : null;
            OreGuide.Ore targetOre = oreTarget != null ? OreGuide.findByName(oreTarget) : null;

            // Build Y-level warning if at wrong depth
            StringBuilder warnings = new StringBuilder();
            int currentY = companion.blockPosition().getY();

            if (targetOre != null) {
                // Check if companion is at the right Y-level range for this ore
                if (currentY < targetOre.minY || currentY > targetOre.maxY) {
                    warnings.append("WARNING: Companion is at Y=").append(currentY)
                            .append(" but ").append(targetOre.name).append(" ore generates between Y=")
                            .append(targetOre.minY).append(" and Y=").append(targetOre.maxY)
                            .append(". Best Y=").append(targetOre.bestY).append(". ")
                            .append("Use dig_down or strip_mine to reach the right depth first. ");
                } else if (Math.abs(currentY - targetOre.bestY) > 20) {
                    warnings.append("Note: Companion is at Y=").append(currentY)
                            .append(". ").append(targetOre.name).append(" ore is most common at Y=")
                            .append(targetOre.bestY).append(". Consider mining deeper for better results. ");
                }

                // === Auto-craft required pickaxe if companion doesn't have one ===
                if (targetOre.minTier > 0) {
                    int companionTier = getCompanionPickaxeTier(companion);
                    if (companionTier < targetOre.minTier) {
                        String neededPick = getPickaxeForTier(targetOre.minTier);
                        String planText = "mine_ores({\"ore\":\"" + targetOre.name + "\",\"radius\":" + radius +
                                ",\"maxOres\":" + maxOres + "})";
                        if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                            planText += ", then " + args.get("plan").getAsString();
                        }
                        MCAi.LOGGER.info("Auto-crafting {} before mining {} (have tier {}, need tier {})",
                                neededPick, targetOre.name, companionTier, targetOre.minTier);

                        // Call craft_item with a plan to return to mining afterward
                        JsonObject craftArgs = new JsonObject();
                        craftArgs.addProperty("item", neededPick);
                        craftArgs.addProperty("count", 1);
                        AiTool craftTool = ToolRegistry.get("craft_item");
                        if (craftTool != null) {
                            String craftResult = craftTool.execute(craftArgs, context);
                            // If craft succeeded immediately, continue to mining
                            if (craftResult.contains("Crafted") || craftResult.contains("already have")) {
                                MCAi.LOGGER.info("Auto-crafted pickaxe, proceeding to mine");
                                // Fall through to create mining task below
                            } else if (craftResult.contains("[ASYNC_TASK]")) {
                                // Crafting needs gathering first — inject mine_ores into the task continuation chain
                                // so mining actually starts after the pickaxe is auto-crafted.
                                // The craft chain ends with "Call craft_item(stone_pickaxe)..." — we append
                                // ", then Call mine_ores(...)" so the AI continues to mine after crafting.
                                String mineCall = "Call " + planText;
                                CompanionTask activeTask = companion.getTaskManager().peekActiveTask();
                                if (activeTask != null && activeTask.getContinuation() != null) {
                                    TaskContinuation existing = activeTask.getContinuation();
                                    String newNextSteps = existing.nextSteps() + ", then " + mineCall;
                                    activeTask.setContinuation(new TaskContinuation(
                                            existing.ownerUUID(),
                                            existing.planContext(),
                                            newNextSteps
                                    ));
                                    MCAi.LOGGER.info("Injected mine_ores continuation into craft chain: {}", mineCall);
                                } else if (activeTask != null) {
                                    activeTask.setContinuation(new TaskContinuation(
                                            context.player().getUUID(),
                                            "Craft " + neededPick + " then mine " + targetOre.name,
                                            mineCall
                                    ));
                                    MCAi.LOGGER.info("Set mine_ores continuation on active task: {}", mineCall);
                                }
                                return craftResult + " After crafting " + neededPick +
                                        ", I'll mine " + targetOre.name + " ore.";
                            } else {
                                // Craft failed — warn but try mining anyway
                                warnings.append("Could not auto-craft ").append(neededPick)
                                        .append(": ").append(craftResult).append(" ");
                            }
                        }
                    }
                }
            }

            // Pre-check: skip if companion already has enough of the target ore drop
            if (targetOre != null) {
                net.minecraft.world.item.Item dropItem = resolveOreDrop(targetOre.name);
                if (dropItem != null) {
                    int have = BlockHelper.countItem(companion, dropItem);
                    if (have >= maxOres) {
                        MCAi.LOGGER.info("MineOres: SKIPPING — already have {}x {} in inventory+storage (need {})",
                                have, dropItem.getDescription().getString(), maxOres);
                        return "Already have " + have + "x " + dropItem.getDescription().getString() +
                                " in inventory/storage (need " + maxOres + "). Skipping mining — proceed to next step.";
                    }
                }
            }

            // Create task — pass ore target for filtered scanning
            MineOresTask task = new MineOresTask(companion, radius, maxOres, targetOre);

            // Attach continuation plan
            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                String planText = args.get("plan").getAsString();
                String oreLabel = targetOre != null ? targetOre.name + " ore" : "ores";
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Mine " + oreLabel + " (r=" + radius + "), then: " + planText,
                        planText
                ));
            }

            companion.getTaskManager().queueTask(task);

            String oreLabel = targetOre != null ? targetOre.name + " ore" : "all ore types";
            String toolInfo = targetOre != null
                    ? " Requires " + targetOre.tierName() + " pickaxe or better."
                    : "";
            return "[ASYNC_TASK] Queued mining: " + oreLabel + " within " + radius + " blocks at Y=" + currentY + "." +
                    toolInfo + " " + warnings +
                    "This task runs over time — STOP calling tools and tell the player you're on it. " +
                    "If you used the plan parameter, the next step will auto-execute when mining finishes.";
        });
    }

    /**
     * Get the best pickaxe tier the companion currently has.
     * Returns -1=none, 0=wood/gold, 1=stone, 2=iron, 3=diamond, 4=netherite
     */
    private int getCompanionPickaxeTier(CompanionEntity companion) {
        int bestTier = -1;

        // Check main hand
        ItemStack mainHand = companion.getMainHandItem();
        if (!mainHand.isEmpty() && mainHand.getItem() instanceof PickaxeItem pick) {
            bestTier = Math.max(bestTier, getPickTier(pick));
        }

        // Check inventory
        var inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof PickaxeItem pick) {
                bestTier = Math.max(bestTier, getPickTier(pick));
            }
        }
        return bestTier;
    }

    private int getPickTier(PickaxeItem pick) {
        float speed = pick.getTier().getSpeed();
        if (speed >= 9) return 4;  // Netherite
        if (speed >= 8) return 3;  // Diamond
        if (speed >= 6) return 2;  // Iron
        if (speed >= 4) return 1;  // Stone
        return 0;                   // Wood/Gold
    }

    /**
     * Get the pickaxe item name needed for a given tier.
     */
    private String getPickaxeForTier(int tier) {
        return switch (tier) {
            case 1 -> "stone_pickaxe";
            case 2 -> "iron_pickaxe";
            case 3 -> "diamond_pickaxe";
            case 4 -> "netherite_pickaxe";
            default -> "wooden_pickaxe";
        };
    }

    /**
     * Resolve the item a player receives when mining a given ore type.
     * E.g., "iron" → raw_iron, "coal" → coal, "diamond" → diamond.
     */
    private net.minecraft.world.item.Item resolveOreDrop(String oreName) {
        String dropId = switch (oreName.toLowerCase()) {
            case "iron" -> "raw_iron";
            case "copper" -> "raw_copper";
            case "gold" -> "raw_gold";
            case "coal" -> "coal";
            case "diamond" -> "diamond";
            case "emerald" -> "emerald";
            case "lapis", "lapis_lazuli" -> "lapis_lazuli";
            case "redstone" -> "redstone";
            case "quartz", "nether_quartz" -> "quartz";
            case "ancient_debris" -> "ancient_debris";
            default -> "raw_" + oreName.toLowerCase();
        };
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(
                ResourceLocation.withDefaultNamespace(dropId));
        if (item == null || item == net.minecraft.world.item.Items.AIR) return null;
        return item;
    }
}
