package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.OreGuide;
import com.apocscode.mcai.task.StripMineTask;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;

/**
 * AI Tool: Strip-mine a tunnel to find ores.
 *
 * When ores aren't visible at the surface, the companion needs to dig underground
 * to expose them. This tool creates a 1x2 tunnel at the optimal Y-level for the
 * target ore, mining any ores found in the walls as it goes.
 *
 * If a specific ore is requested, the companion will:
 *   1. Dig down to the optimal Y-level for that ore (if not already there)
 *   2. Tunnel horizontally, scanning walls for the target ore
 *   3. Mine any target ore blocks found in the walls/ceiling/floor
 *   4. Stop when target count is reached or tunnel length limit is hit
 */
public class StripMineTool implements AiTool {

    @Override
    public String name() {
        return "strip_mine";
    }

    @Override
    public String description() {
        return "Dig a horizontal tunnel to find and mine ores. The companion descends to the optimal Y-level " +
                "for the target ore (e.g. Y=-59 for diamonds, Y=16 for iron), then tunnels horizontally " +
                "while mining any ores exposed in the walls. Use this when mine_ores finds nothing nearby — " +
                "the companion needs to dig to expose ore veins. " +
                "Specify 'ore' for targeted mining, 'length' for tunnel length (default 48), " +
                "and 'count' for how many ores to find before stopping.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject ore = new JsonObject();
        ore.addProperty("type", "string");
        ore.addProperty("description",
                "Target ore to mine. Examples: 'iron', 'diamond', 'gold', 'coal'. " +
                "The companion will automatically go to the best Y-level for this ore. " +
                "If omitted, tunnels at current Y and mines everything found.");
        props.add("ore", ore);

        JsonObject length = new JsonObject();
        length.addProperty("type", "integer");
        length.addProperty("description", "Tunnel length in blocks. Default: 48, max: 128");
        props.add("length", length);

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description",
                "Stop after finding this many target ores. Default: 0 (tunnel full length). " +
                "Example: count=8 with ore='iron' stops after finding 8 iron ore.");
        props.add("count", count);

        JsonObject directionParam = new JsonObject();
        directionParam.addProperty("type", "string");
        directionParam.addProperty("description",
                "Tunnel direction: 'north', 'south', 'east', or 'west'. " +
                "Default: the direction the companion is currently facing.");
        props.add("direction", directionParam);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description",
                "Optional: what to do AFTER strip mining completes. " +
                "Example: 'smelt raw_iron then craft iron_pickaxe'.");
        props.add("plan", plan);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            // Parse ore target
            String oreName = args.has("ore") ? args.get("ore").getAsString().trim() : null;
            OreGuide.Ore targetOre = oreName != null ? OreGuide.findByName(oreName) : null;
            if (oreName != null && targetOre == null) {
                return "Unknown ore type: '" + oreName + "'. Try: " + OreGuide.allOreNames() + ".";
            }

            // Parse tunnel length
            int length = args.has("length") ? args.get("length").getAsInt() : 48;
            length = Math.max(4, Math.min(length, 128));

            // Parse target ore count
            int count = args.has("count") ? args.get("count").getAsInt() : 0;

            // Parse direction
            Direction direction = getDirection(args, companion);

            // Determine target Y-level
            int targetY;
            int currentY = companion.blockPosition().getY();
            if (targetOre != null) {
                targetY = targetOre.bestY;
                // Don't go below world min
                int worldMin = companion.level().getMinBuildHeight();
                targetY = Math.max(targetY, worldMin + 1);
            } else {
                targetY = currentY; // Stay at current level
            }

            // Check tool tier — auto-craft required pickaxe if missing
            String tierWarning = "";
            if (targetOre != null && targetOre.minTier > 0) {
                int companionTier = getCompanionPickaxeTier(companion);
                if (companionTier < targetOre.minTier) {
                    String neededPick = getPickaxeForTier(targetOre.minTier);
                    MCAi.LOGGER.info("Auto-crafting {} before strip mining {} (have tier {}, need tier {})",
                            neededPick, targetOre.name, companionTier, targetOre.minTier);

                    // Try to auto-craft the pickaxe
                    AiTool craftTool = ToolRegistry.get("craft_item");
                    if (craftTool != null) {
                        JsonObject craftArgs = new JsonObject();
                        craftArgs.addProperty("item", neededPick);
                        craftArgs.addProperty("count", 1);
                        String craftResult = craftTool.execute(craftArgs, context);

                        if (craftResult.contains("Crafted") || craftResult.contains("already have")) {
                            MCAi.LOGGER.info("Auto-crafted pickaxe, proceeding to strip mine");
                        } else if (craftResult.contains("[ASYNC_TASK]")) {
                            // Crafting needs gathering first — return async with plan to mine after
                            return craftResult + " After crafting " + neededPick +
                                    ", I'll strip mine for " + targetOre.name + " ore.";
                        } else {
                            tierWarning = " WARNING: Could not auto-craft " + neededPick +
                                    ". Companion may not harvest " + targetOre.name + " ore.";
                        }
                    }
                }
            }

            // Create the task
            StripMineTask task = new StripMineTask(companion, direction, length, targetY, targetOre, count);

            // Continuation plan
            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                String planText = args.get("plan").getAsString();
                String oreLabel = targetOre != null ? targetOre.name + " ore" : "ores";
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Strip mine " + oreLabel + " (" + length + " blocks " + direction.getName() + "), then: " + planText,
                        planText
                ));
            }

            companion.getTaskManager().queueTask(task);

            // Build response
            StringBuilder resp = new StringBuilder();
            resp.append("[ASYNC_TASK] Queued strip mining: ");
            if (targetOre != null) {
                resp.append("hunting ").append(targetOre.name).append(" ore, ");
                if (currentY != targetY) {
                    resp.append("digging from Y=").append(currentY).append(" down to Y=").append(targetY).append(", ");
                } else {
                    resp.append("at Y=").append(currentY).append(", ");
                }
            } else {
                resp.append("mining all ores, at Y=").append(currentY).append(", ");
            }
            resp.append(length).append(" block tunnel heading ").append(direction.getName()).append(". ");
            if (count > 0) {
                resp.append("Will stop after finding ").append(count).append(" ores. ");
            }
            if (targetOre != null) {
                resp.append(targetOre.tip).append(" ");
            }
            resp.append("This task runs over time — STOP calling tools and tell the player you're on it.");
            resp.append(tierWarning);

            return resp.toString();
        });
    }

    /**
     * Parse direction from args, or use companion's facing direction.
     */
    private Direction getDirection(JsonObject args, CompanionEntity companion) {
        if (args.has("direction")) {
            String dir = args.get("direction").getAsString().toLowerCase().trim();
            return switch (dir) {
                case "north" -> Direction.NORTH;
                case "south" -> Direction.SOUTH;
                case "east" -> Direction.EAST;
                case "west" -> Direction.WEST;
                default -> getCompanionFacing(companion);
            };
        }
        return getCompanionFacing(companion);
    }

    /**
     * Get the horizontal direction the companion is facing.
     */
    private Direction getCompanionFacing(CompanionEntity companion) {
        float yaw = companion.getYRot();
        // Normalize to 0-360
        yaw = ((yaw % 360) + 360) % 360;
        if (yaw >= 315 || yaw < 45) return Direction.SOUTH;
        if (yaw >= 45 && yaw < 135) return Direction.WEST;
        if (yaw >= 135 && yaw < 225) return Direction.NORTH;
        return Direction.EAST;
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
            bestTier = Math.max(bestTier, getToolTier(pick));
        }

        // Check inventory
        var inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof PickaxeItem pick) {
                bestTier = Math.max(bestTier, getToolTier(pick));
            }
        }
        return bestTier;
    }

    /**
     * Estimate tool tier from a PickaxeItem.
     */
    private int getToolTier(PickaxeItem pickaxe) {
        float speed = pickaxe.getTier().getSpeed();
        if (speed >= 9.0f) return 4; // netherite
        if (speed >= 8.0f) return 3; // diamond
        if (speed >= 6.0f) return 2; // iron
        if (speed >= 4.0f) return 1; // stone
        return 0; // wood
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
}
