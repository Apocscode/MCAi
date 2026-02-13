package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.OreGuide;
import com.apocscode.mcai.task.mining.CreateMineTask;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;

/**
 * AI Tool: Create a permanent mine with shaft, hub room, and branch mining.
 *
 * This is the "big" mining tool — it sets up a full mine operation rather than
 * a simple tunnel. The companion will:
 *   1. Dig a walkable staircase shaft to the target ore's optimal Y-level
 *   2. Create a hub room with chests, furnace, crafting table, and torches
 *   3. Systematically branch mine from the hub with efficient spacing
 *
 * Use this when the player wants a long-term mining operation, a "real mine",
 * or needs large quantities of a specific ore. For quick surface mining or
 * short tunnels, use mine_ores or strip_mine instead.
 */
public class CreateMineTool implements AiTool {

    @Override
    public String name() {
        return "create_mine";
    }

    @Override
    public String description() {
        return "Create a permanent mine with staircase shaft, hub room (chests, furnace, crafting table), " +
                "and systematic branch mining. The companion builds a full mine operation: digs down to the " +
                "optimal Y-level for the target ore, creates a furnished hub room as a staging area, then " +
                "mines branches with 3-block spacing and poke holes for maximum ore exposure. " +
                "Use for long-term mining or when large quantities of ore are needed. " +
                "The mine persists and can be resumed later.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        // Target ore
        JsonObject ore = new JsonObject();
        ore.addProperty("type", "string");
        ore.addProperty("description",
                "Target ore to mine. Examples: 'iron', 'diamond', 'gold', 'coal'. " +
                "Determines the Y-level for the mine. If omitted, mines at current Y.");
        props.add("ore", ore);

        // Branch length
        JsonObject branchLen = new JsonObject();
        branchLen.addProperty("type", "integer");
        branchLen.addProperty("description",
                "Length of each branch tunnel in blocks. Default: 20, max: 40. " +
                "Longer branches find more ore but take longer.");
        props.add("branch_length", branchLen);

        // Branches per side
        JsonObject branchCount = new JsonObject();
        branchCount.addProperty("type", "integer");
        branchCount.addProperty("description",
                "Number of branch pairs (one left + one right). Default: 4, max: 8. " +
                "More branches = more area covered.");
        props.add("branches_per_side", branchCount);

        // Direction
        JsonObject directionParam = new JsonObject();
        directionParam.addProperty("type", "string");
        directionParam.addProperty("description",
                "Direction for the shaft and main corridor: 'north', 'south', 'east', 'west'. " +
                "Default: companion's current facing direction.");
        props.add("direction", directionParam);

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
                return "Unknown ore type: '" + oreName + "'. " +
                        "Try: coal, copper, iron, lapis, gold, redstone, diamond, emerald.";
            }

            // Determine target Y-level
            int targetY;
            int currentY = companion.blockPosition().getY();
            if (targetOre != null) {
                targetY = targetOre.bestY;
                int worldMin = companion.level().getMinBuildHeight();
                targetY = Math.max(targetY, worldMin + 5); // Keep above bedrock zone
            } else {
                // No specific ore — mine ~20 blocks below current Y, min Y=-50
                targetY = Math.max(currentY - 20, companion.level().getMinBuildHeight() + 5);
            }

            // Parse branch length
            int branchLength = args.has("branch_length") ? args.get("branch_length").getAsInt() : 20;
            branchLength = Math.max(8, Math.min(branchLength, 40));

            // Parse branches per side
            int branchesPerSide = args.has("branches_per_side") ? args.get("branches_per_side").getAsInt() : 4;
            branchesPerSide = Math.max(1, Math.min(branchesPerSide, 8));

            // Parse direction
            Direction direction = getDirection(args, companion);

            // Check for pickaxe tier (similar to StripMineTool)
            String tierWarning = "";
            if (targetOre != null && targetOre.minTier > 0) {
                int companionTier = getCompanionPickaxeTier(companion);
                if (companionTier < targetOre.minTier) {
                    String neededPick = getPickaxeForTier(targetOre.minTier);
                    MCAi.LOGGER.info("CreateMine: Companion needs {} for {} (have tier {}, need {})",
                            neededPick, targetOre.name, companionTier, targetOre.minTier);

                    // Try auto-craft
                    AiTool craftTool = ToolRegistry.get("craft_item");
                    if (craftTool != null) {
                        JsonObject craftArgs = new JsonObject();
                        craftArgs.addProperty("item", neededPick);
                        craftArgs.addProperty("count", 1);
                        String craftResult = craftTool.execute(craftArgs, context);

                        if (craftResult.contains("[ASYNC_TASK]")) {
                            return craftResult + " After crafting " + neededPick +
                                    ", I'll create the " + targetOre.name + " mine.";
                        } else if (!craftResult.contains("Crafted") && !craftResult.contains("already have")) {
                            tierWarning = " WARNING: Could not auto-craft " + neededPick +
                                    ". Companion may not harvest " + targetOre.name + " ore.";
                        }
                    }
                }
            }

            // Create the orchestrator task
            CreateMineTask mineTask = new CreateMineTask(
                    companion, targetOre, targetY, direction, branchLength, branchesPerSide
            );
            companion.getTaskManager().queueTask(mineTask);

            // Build response
            StringBuilder resp = new StringBuilder();
            resp.append("[ASYNC_TASK] Creating ");
            if (targetOre != null) {
                resp.append(targetOre.name).append(" ");
            }
            resp.append("mine: ");
            resp.append("staircase shaft from Y=").append(currentY).append(" to Y=").append(targetY);
            resp.append(" heading ").append(direction.getName()).append(", ");
            resp.append("hub room with chests/furnace, then ");
            resp.append(branchesPerSide * 2).append(" branch tunnels (").append(branchLength).append(" blocks each). ");
            if (targetOre != null) {
                resp.append(targetOre.tip).append(" ");
            }
            resp.append("This is a big operation — it will take several minutes. ");
            resp.append("STOP calling tools and tell the player the mine is being set up.");
            resp.append(tierWarning);

            return resp.toString();
        });
    }

    // ================================================================
    // Helpers (same pattern as StripMineTool)
    // ================================================================

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

    private Direction getCompanionFacing(CompanionEntity companion) {
        float yaw = companion.getYRot();
        yaw = ((yaw % 360) + 360) % 360;
        if (yaw >= 315 || yaw < 45) return Direction.SOUTH;
        if (yaw >= 45 && yaw < 135) return Direction.WEST;
        if (yaw >= 135 && yaw < 225) return Direction.NORTH;
        return Direction.EAST;
    }

    private int getCompanionPickaxeTier(CompanionEntity companion) {
        int bestTier = -1;
        var mainHand = companion.getMainHandItem();
        if (!mainHand.isEmpty() && mainHand.getItem() instanceof net.minecraft.world.item.PickaxeItem pick) {
            bestTier = Math.max(bestTier, getToolTier(pick));
        }
        var inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.PickaxeItem pick) {
                bestTier = Math.max(bestTier, getToolTier(pick));
            }
        }
        return bestTier;
    }

    private int getToolTier(net.minecraft.world.item.PickaxeItem pick) {
        var tier = pick.getTier();
        // NeoForge 1.21.1 tier comparison
        if (tier == net.minecraft.world.item.Tiers.WOOD || tier == net.minecraft.world.item.Tiers.GOLD) return 0;
        if (tier == net.minecraft.world.item.Tiers.STONE) return 1;
        if (tier == net.minecraft.world.item.Tiers.IRON) return 2;
        if (tier == net.minecraft.world.item.Tiers.DIAMOND) return 3;
        if (tier == net.minecraft.world.item.Tiers.NETHERITE) return 4;
        return 0; // Unknown → assume wood tier
    }

    private String getPickaxeForTier(int tier) {
        return switch (tier) {
            case 1 -> "stone_pickaxe";
            case 2 -> "iron_pickaxe";
            case 3 -> "diamond_pickaxe";
            default -> "wooden_pickaxe";
        };
    }
}
