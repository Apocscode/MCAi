package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.OreGuide;
import com.apocscode.mcai.task.mining.BranchMineTask;
import com.apocscode.mcai.task.mining.CreateMineTask;
import com.apocscode.mcai.task.mining.MineState;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;

/**
 * AI Tool: Create a permanent mine with shaft, hub room, and branch mining.
 *
 * This is the "big" mining tool — it sets up a full mine operation rather than
 * a simple tunnel. The companion will:
 *   1. Dig a walkable staircase shaft to the target ore's optimal Y-level
 *   2. Create a hub room with chests, furnace, crafting table, and torches
 *   3. Systematically branch mine from the hub with efficient spacing
 *
 * MEMORY: When a mine is created, its entrance position is saved to companion memory.
 * On subsequent "create mine" requests for the same ore, the companion will navigate
 * back to the existing mine and resume branch mining instead of digging a new shaft.
 * Use new_mine=true to force creating a fresh mine.
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
                "optimal Y-level for the target ore/resource, creates a furnished hub room as a staging area, then " +
                "mines branches with 3-block spacing and poke holes for maximum ore exposure. " +
                "Supports all vanilla ores AND modded ores from ATM10 (Mekanism, Thermal, Create, AE2, etc.). " +
                "Say 'create a [resource] mine' — e.g. 'create a redstone mine', 'create an osmium mine'. " +
                "The Y-level is automatically chosen based on the resource's best spawn depth. " +
                "The mine is remembered — if the companion already has a mine for the requested resource, " +
                "it will return to the existing mine and continue mining. " +
                "Set new_mine=true only if the player explicitly asks to build a NEW mine. " +
                "Use list_mines to show where existing mines are located.";
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
                "Target ore/resource to mine. Examples: 'iron', 'diamond', 'redstone', 'osmium', 'tin', 'certus quartz'. " +
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

        // New mine (force fresh mine)
        JsonObject newMine = new JsonObject();
        newMine.addProperty("type", "boolean");
        newMine.addProperty("description",
                "Set to true to create a brand new mine even if one already exists for this ore. " +
                "Default: false (returns to existing mine and resumes mining). " +
                "Only set true when the player explicitly says 'new mine' or 'build a new one'.");
        props.add("new_mine", newMine);

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
                        "Try: " + OreGuide.allOreNames() + ".";
            }

            boolean forceNew = args.has("new_mine") && args.get("new_mine").getAsBoolean();

            // ================================================================
            // Check companion memory for an existing mine
            // ================================================================
            if (!forceNew) {
                String oreKey = targetOre != null ? targetOre.name.toLowerCase() : "general";
                String memoryKey = "mine_" + oreKey;
                String mineData = companion.getMemory().getFact(memoryKey);

                if (mineData != null) {
                    String[] parsed = CreateMineTask.parseMineMemory(mineData);
                    if (parsed != null) {
                        return resumeExistingMine(companion, targetOre, parsed, oreKey);
                    }
                }
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

            // Auto-craft torches if companion doesn't have enough
            String torchWarning = "";
            int torchCount = BlockHelper.countItem(companion, Items.TORCH);
            int estimatedTorchesNeeded = ((currentY - targetY) / 8) + 8;
            if (torchCount < estimatedTorchesNeeded) {
                int needed = Math.max(32, estimatedTorchesNeeded) - torchCount;
                MCAi.LOGGER.info("CreateMine: Low torches ({}/{}), trying to craft {}",
                        torchCount, estimatedTorchesNeeded, needed);

                AiTool craftTool = ToolRegistry.get("craft_item");
                if (craftTool != null) {
                    JsonObject craftArgs = new JsonObject();
                    craftArgs.addProperty("item", "torch");
                    craftArgs.addProperty("count", needed);
                    String craftResult = craftTool.execute(craftArgs, context);

                    if (craftResult.contains("[ASYNC_TASK]")) {
                        // Need async crafting (gather materials) — defer the mine
                        return craftResult + " After crafting torches, " +
                                "I'll create the mine. Say 'create mine' again once torches are ready.";
                    } else if (craftResult.contains("Crafted")) {
                        int newCount = BlockHelper.countItem(companion, Items.TORCH);
                        MCAi.LOGGER.info("CreateMine: Torches crafted, now have {}", newCount);
                    } else {
                        torchWarning = " Note: Could not craft torches — mine will be unlit.";
                        MCAi.LOGGER.info("CreateMine: Could not craft torches: {}", craftResult);
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
            resp.append(torchWarning);

            return resp.toString();
        });
    }

    // ================================================================
    // Resume existing mine from memory
    // ================================================================

    /**
     * Resume mining at an existing mine that was saved to memory.
     * Navigates to the mine entrance and starts branch mining from the hub.
     */
    private String resumeExistingMine(CompanionEntity companion, OreGuide.Ore targetOre,
                                       String[] parsed, String oreKey) {
        try {
            int entranceX = Integer.parseInt(parsed[0]);
            int entranceY = Integer.parseInt(parsed[1]);
            int entranceZ = Integer.parseInt(parsed[2]);
            int targetY = Integer.parseInt(parsed[3]);
            String dirName = parsed[4];
            int branchLength = Integer.parseInt(parsed[5]);
            int branchesPerSide = Integer.parseInt(parsed[6]);

            BlockPos entrance = new BlockPos(entranceX, entranceY, entranceZ);
            Direction direction = Direction.byName(dirName);
            if (direction == null) direction = Direction.NORTH;

            // Create a MineState that reflects the existing mine
            MineState mineState = new MineState(
                    targetOre != null ? targetOre.name : null,
                    targetY, entrance, direction
            );
            mineState.setBranchLength(branchLength);
            mineState.setBranchesPerSide(branchesPerSide);

            // Calculate the shaft bottom position
            // The staircase shaft moves 2 blocks forward per 1 Y-level descended
            // (1 in DIG_FORWARD phase + 1 in DIG_DOWN phase)
            int depth = entranceY - targetY;
            int horizontalOffset = depth * 2;
            BlockPos shaftBottom = entrance.relative(direction, horizontalOffset).atY(targetY);
            mineState.setShaftBottom(shaftBottom);

            // Use saved hub center if available (v2 format), otherwise calculate it
            BlockPos hubCenter;
            if (parsed.length >= 10) {
                // v2 format: hub center was saved explicitly
                int hubX = Integer.parseInt(parsed[7]);
                int hubY = Integer.parseInt(parsed[8]);
                int hubZ = Integer.parseInt(parsed[9]);
                hubCenter = new BlockPos(hubX, hubY, hubZ);
                MCAi.LOGGER.info("Resumemine: using saved hubCenter={}", hubCenter);
            } else {
                // v1 format: calculate hub center from shaft bottom
                // HUB_LENGTH = 7, offset = HUB_LENGTH/2 + 1 = 4
                hubCenter = shaftBottom.relative(direction, 4);
                MCAi.LOGGER.info("Resumemine: calculated hubCenter={} (no saved hub)", hubCenter);
            }

            MCAi.LOGGER.info("Resumemine: entrance={}, depth={}, horizOffset={}, shaftBottom={}",
                    entrance, depth, horizontalOffset, shaftBottom);

            mineState.addLevel(hubCenter.getY(), hubCenter);
            var level = mineState.getActiveLevel();
            if (level != null) level.setHubBuilt(true);

            MCAi.LOGGER.info("Resumemine: hubCenter={}, branches={}x{}, branchLen={}",
                    hubCenter, branchesPerSide, 2, branchLength);

            // Queue branch mining directly (skip shaft + hub since they already exist)
            BranchMineTask branchTask = new BranchMineTask(companion, mineState, targetOre);
            companion.getTaskManager().queueTask(branchTask);

            companion.getMemory().addEvent("Resumed " + oreKey + " mine at (" +
                    entranceX + ", " + entranceY + ", " + entranceZ + ")");

            String oreLabel = targetOre != null ? targetOre.name + " " : "";
            return "[ASYNC_TASK] Found existing " + oreLabel + "mine at (" +
                    entranceX + ", " + entranceY + ", " + entranceZ + ") → Y=" + targetY + ". " +
                    "Returning to resume branch mining from the hub. " +
                    "The shaft and hub are already built — heading straight to mining. " +
                    "Say 'create new mine' if you want a completely new mine instead. " +
                    "This task runs over time — STOP calling tools and tell the player you're on it.";

        } catch (NumberFormatException e) {
            MCAi.LOGGER.warn("CreateMine: invalid mine memory format, creating new mine");
            // Fall through — invalid data, will create new mine
            return null; // This won't happen due to the flow, but let's be safe
        }
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
