package com.apocscode.mcai.task.mining;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.CompanionTask;
import com.apocscode.mcai.task.OreGuide;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/**
 * Orchestrator task: Create and operate a full mine.
 *
 * Chains the three sub-tasks in sequence:
 *   1. DigShaftTask  — staircase down to target Y
 *   2. CreateHubTask — clear hub room, place furniture
 *   3. BranchMineTask — systematic branch mining from hub
 *
 * Sub-tasks are queued sequentially via the TaskManager. CreateMineTask
 * itself completes quickly after queuing the first sub-task; the sub-tasks
 * then chain via direct queue insertion (not AI continuations, which would
 * add unnecessary latency).
 *
 * The MineState object is shared across all sub-tasks and tracks the mine's
 * progress through its lifecycle.
 */
public class CreateMineTask extends CompanionTask {

    private final MineState mineState;
    @Nullable
    private final OreGuide.Ore targetOre;

    /** Current orchestration phase. */
    private Phase phase = Phase.VALIDATE;

    private enum Phase {
        VALIDATE,       // Check preconditions (tools, space, etc.)
        QUEUE_SHAFT,    // Queue the DigShaftTask
        QUEUE_HUB,      // Queue the CreateHubTask (after shaft completes)
        QUEUE_BRANCHES, // Queue the BranchMineTask (after hub completes)
        DONE
    }

    /**
     * Create a full mining operation.
     *
     * @param companion     The companion entity
     * @param targetOre     Target ore (nullable — null for general mining)
     * @param targetY       Target Y-level for the mine
     * @param direction     Direction to dig the shaft/hub
     * @param branchLength  Length of each branch tunnel
     * @param branchesPerSide Number of branch pairs
     */
    public CreateMineTask(CompanionEntity companion, @Nullable OreGuide.Ore targetOre,
                          int targetY, Direction direction, int branchLength, int branchesPerSide) {
        super(companion, buildDescription(targetOre, targetY));
        this.targetOre = targetOre;

        // Initialize shared mine state
        BlockPos entrance = companion.blockPosition();
        this.mineState = new MineState(
                targetOre != null ? targetOre.name : null,
                targetY, entrance, direction
        );
        mineState.setBranchLength(branchLength);
        mineState.setBranchesPerSide(branchesPerSide);
    }

    private static String buildDescription(@Nullable OreGuide.Ore ore, int targetY) {
        if (ore != null) {
            return "Create " + ore.name + " mine at Y=" + targetY;
        }
        return "Create mine at Y=" + targetY;
    }

    @Override
    public String getTaskName() {
        return "CreateMine";
    }

    @Override
    public int getProgressPercent() {
        return switch (phase) {
            case VALIDATE -> 0;
            case QUEUE_SHAFT -> 10;
            case QUEUE_HUB -> 40;
            case QUEUE_BRANCHES -> 70;
            case DONE -> 100;
        };
    }

    @Override
    protected void start() {
        phase = Phase.VALIDATE;
        String oreLabel = targetOre != null ? targetOre.name + " " : "";
        say("Setting up " + oreLabel + "mine. Entrance at " + formatPos(mineState.getEntrance()) +
                ", digging to Y=" + mineState.getTargetY() + " heading " +
                mineState.getShaftDirection().getName() + ".");
    }

    @Override
    protected void tick() {
        switch (phase) {
            case VALIDATE -> tickValidate();
            case QUEUE_SHAFT -> tickQueueShaft();
            case QUEUE_HUB -> tickQueueHub();
            case QUEUE_BRANCHES -> tickQueueBranches();
            case DONE -> complete();
        }
    }

    // ================================================================
    // Phase: Validate preconditions
    // ================================================================

    private void tickValidate() {
        int currentY = companion.blockPosition().getY();
        int targetY = mineState.getTargetY();

        // Check if target Y is reachable
        int worldMin = companion.level().getMinBuildHeight();
        if (targetY <= worldMin + 1) {
            say("Target Y=" + targetY + " is too close to bedrock. Adjusting to Y=" + (worldMin + 5) + ".");
        }

        // Check for basic mining tools
        boolean hasPickaxe = false;
        var inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.PickaxeItem) {
                hasPickaxe = true;
                break;
            }
        }
        if (!hasPickaxe && companion.getMainHandItem().getItem() instanceof net.minecraft.world.item.PickaxeItem) {
            hasPickaxe = true;
        }
        if (!hasPickaxe) {
            say("Warning: No pickaxe found! Mining will be slow. Consider crafting one first.");
        }

        // === Torch supply: pull from storage, then craft if needed ===
        int torches = BlockHelper.countItem(companion, Items.TORCH);
        int estimatedTorchesNeeded = ((currentY - targetY) / 8) + 8;

        // Step 1: Pull existing torches from tagged STORAGE and home area chests
        if (torches < estimatedTorchesNeeded) {
            int pulled = pullFromStorage(Items.TORCH, estimatedTorchesNeeded - torches);
            if (pulled > 0) {
                torches += pulled;
                MCAi.LOGGER.info("CreateMine: pulled {} torches from storage, now have {}", pulled, torches);
            }
        }

        // Step 2: If still low, pull coal/charcoal + sticks/planks and craft torches
        if (torches < estimatedTorchesNeeded) {
            torches = tryAutoCraftTorches(torches, estimatedTorchesNeeded);
        }

        if (torches < estimatedTorchesNeeded) {
            say("Have " + torches + " torches, might need ~" + estimatedTorchesNeeded +
                    ". I'll craft more from coal I find while mining.");
        }

        MCAi.LOGGER.info("CreateMine: validated. currentY={}, targetY={}, hasPickaxe={}, torches={}",
                currentY, targetY, hasPickaxe, torches);

        phase = Phase.QUEUE_SHAFT;
    }

    /**
     * Pull items from tagged STORAGE containers and home area chests into companion inventory.
     */
    private int pullFromStorage(Item item, int needed) {
        int pulled = 0;
        SimpleContainer inv = companion.getCompanionInventory();
        Level level = companion.level();
        java.util.Set<BlockPos> scanned = new java.util.HashSet<>();

        // Tagged STORAGE containers
        for (TaggedBlock tb : companion.getTaggedBlocks(TaggedBlock.Role.STORAGE)) {
            if (pulled >= needed) break;
            scanned.add(tb.pos());
            pulled += extractFromContainer(level, tb.pos(), inv, item, needed - pulled);
        }

        // Home area containers
        if (pulled < needed && companion.hasHomeArea()) {
            BlockPos c1 = companion.getHomeCorner1();
            BlockPos c2 = companion.getHomeCorner2();
            if (c1 != null && c2 != null) {
                int minX = Math.min(c1.getX(), c2.getX());
                int minY = Math.min(c1.getY(), c2.getY());
                int minZ = Math.min(c1.getZ(), c2.getZ());
                int maxX = Math.max(c1.getX(), c2.getX());
                int maxY = Math.max(c1.getY(), c2.getY());
                int maxZ = Math.max(c1.getZ(), c2.getZ());
                for (int x = minX; x <= maxX && pulled < needed; x++) {
                    for (int y = minY; y <= maxY && pulled < needed; y++) {
                        for (int z = minZ; z <= maxZ && pulled < needed; z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (scanned.contains(pos)) continue;
                            scanned.add(pos);
                            pulled += extractFromContainer(level, pos, inv, item, needed - pulled);
                        }
                    }
                }
            }
        }
        return pulled;
    }

    /**
     * Extract items from a container block entity into companion inventory.
     */
    private int extractFromContainer(Level level, BlockPos pos, SimpleContainer inv, Item item, int maxExtract) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container container)) return 0;

        int extracted = 0;
        for (int i = 0; i < container.getContainerSize() && extracted < maxExtract; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(maxExtract - extracted, stack.getCount());
                ItemStack toInsert = stack.copy();
                toInsert.setCount(take);
                ItemStack remainder = inv.addItem(toInsert);
                int inserted = take - remainder.getCount();
                if (inserted > 0) {
                    stack.shrink(inserted);
                    if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                    extracted += inserted;
                }
                if (!remainder.isEmpty()) break;
            }
        }
        if (extracted > 0) container.setChanged();
        return extracted;
    }

    /**
     * Try to craft torches by pulling coal/charcoal + sticks/planks from storage.
     * Returns the new total torch count.
     */
    private int tryAutoCraftTorches(int currentTorches, int target) {
        int needed = target - currentTorches;
        int batchesNeeded = (needed + 3) / 4; // 1 coal + 1 stick = 4 torches

        // Pull coal/charcoal from storage
        int coal = BlockHelper.countItem(companion, Items.COAL)
                 + BlockHelper.countItem(companion, Items.CHARCOAL);
        if (coal < batchesNeeded) {
            int pulledCoal = pullFromStorage(Items.COAL, batchesNeeded - coal);
            coal += pulledCoal;
            if (coal < batchesNeeded) {
                int pulledCharcoal = pullFromStorage(Items.CHARCOAL, batchesNeeded - coal);
                coal += pulledCharcoal;
            }
        }

        // Pull sticks from storage
        int sticks = BlockHelper.countItem(companion, Items.STICK);
        if (sticks < batchesNeeded) {
            int pulledSticks = pullFromStorage(Items.STICK, batchesNeeded - sticks);
            sticks += pulledSticks;
        }

        // If still no sticks, pull planks and craft sticks
        if (sticks < batchesNeeded) {
            // Try pulling planks — look for any plank type
            Item[] plankTypes = {
                Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
                Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
                Items.CHERRY_PLANKS, Items.MANGROVE_PLANKS, Items.BAMBOO_PLANKS,
                Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
            };
            int planksNeeded = (batchesNeeded - sticks) * 2; // 2 planks -> 4 sticks
            for (Item plank : plankTypes) {
                int have = BlockHelper.countItem(companion, plank);
                if (have < planksNeeded) {
                    int pulled = pullFromStorage(plank, planksNeeded - have);
                    have += pulled;
                }
                if (have >= 2) {
                    int planksToUse = Math.min(have, planksNeeded) & ~1; // Round down to even
                    if (planksToUse > 0) {
                        BlockHelper.removeItem(companion, plank, planksToUse);
                        int sticksProduced = (planksToUse / 2) * 4;
                        companion.getCompanionInventory().addItem(new ItemStack(Items.STICK, sticksProduced));
                        sticks += sticksProduced;
                        MCAi.LOGGER.info("CreateMine: crafted {} sticks from {} planks", sticksProduced, planksToUse);
                        planksNeeded -= planksToUse;
                        if (sticks >= batchesNeeded) break;
                    }
                }
            }
        }

        // If still no sticks or coal, pull logs and craft planks → sticks
        if (sticks < batchesNeeded || coal < 1) {
            // Try pulling logs
            Item[] logTypes = {
                Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
                Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG,
                Items.CHERRY_LOG, Items.MANGROVE_LOG
            };
            Item[] plankResults = {
                Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
                Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
                Items.CHERRY_PLANKS, Items.MANGROVE_PLANKS
            };
            for (int li = 0; li < logTypes.length; li++) {
                int have = BlockHelper.countItem(companion, logTypes[li]);
                if (have == 0) {
                    int pulled = pullFromStorage(logTypes[li], 8);
                    have += pulled;
                }
                if (have > 0) {
                    // Craft logs → planks: 1 log → 4 planks
                    int logsToUse = Math.min(have, 4);
                    BlockHelper.removeItem(companion, logTypes[li], logsToUse);
                    int planksProduced = logsToUse * 4;
                    companion.getCompanionInventory().addItem(new ItemStack(plankResults[li], planksProduced));
                    MCAi.LOGGER.info("CreateMine: crafted {} planks from {} logs", planksProduced, logsToUse);

                    // Craft planks → sticks if needed
                    if (sticks < batchesNeeded) {
                        int plankCount = BlockHelper.countItem(companion, plankResults[li]);
                        int planksToUse = Math.min(plankCount, (batchesNeeded - sticks) * 2) & ~1;
                        if (planksToUse >= 2) {
                            BlockHelper.removeItem(companion, plankResults[li], planksToUse);
                            int sticksProduced = (planksToUse / 2) * 4;
                            companion.getCompanionInventory().addItem(new ItemStack(Items.STICK, sticksProduced));
                            sticks += sticksProduced;
                            MCAi.LOGGER.info("CreateMine: crafted {} sticks from planks", sticksProduced);
                        }
                    }
                    if (sticks >= batchesNeeded) break;
                }
            }
        }

        // Now craft torches
        if (coal > 0 && sticks > 0) {
            int batches = Math.min(Math.min(coal, sticks), batchesNeeded);

            // Consume coal (prefer coal over charcoal)
            int coalCount = BlockHelper.countItem(companion, Items.COAL);
            int fromCoal = Math.min(batches, coalCount);
            if (fromCoal > 0) BlockHelper.removeItem(companion, Items.COAL, fromCoal);
            int fromCharcoal = batches - fromCoal;
            if (fromCharcoal > 0) BlockHelper.removeItem(companion, Items.CHARCOAL, fromCharcoal);
            BlockHelper.removeItem(companion, Items.STICK, batches);

            int torchesProduced = batches * 4;
            companion.getCompanionInventory().addItem(new ItemStack(Items.TORCH, torchesProduced));
            currentTorches += torchesProduced;

            MCAi.LOGGER.info("CreateMine: auto-crafted {} torches from storage materials", torchesProduced);
            say("Crafted " + torchesProduced + " torches for the mine.");
        }

        return currentTorches;
    }

    // ================================================================
    // Phase: Queue the shaft-digging task
    // ================================================================

    private void tickQueueShaft() {
        DigShaftTask shaftTask = new DigShaftTask(
                companion, mineState, mineState.getShaftDirection(), mineState.getTargetY()
        );
        companion.getTaskManager().queueTask(shaftTask);

        // Queue hub creation right after shaft (it will wait in queue)
        CreateHubTask hubTask = new CreateHubTask(companion, mineState);
        companion.getTaskManager().queueTask(hubTask);

        // Queue branch mining after hub
        BranchMineTask branchTask = new BranchMineTask(companion, mineState, targetOre);
        companion.getTaskManager().queueTask(branchTask);

        mineState.setPhase(MinePhase.DIGGING_SHAFT);

        // Save mine to companion memory for later resumption
        saveMineToMemory();

        say("Mine plan queued: dig shaft → build hub → branch mine. " +
                "This will take a while — I'll report progress as I go!");

        phase = Phase.DONE;
    }

    // These phases are not used since we queue everything in QUEUE_SHAFT,
    // but kept for potential future use with more complex orchestration.

    private void tickQueueHub() {
        phase = Phase.QUEUE_BRANCHES;
    }

    private void tickQueueBranches() {
        phase = Phase.DONE;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private String formatPos(BlockPos pos) {
        if (pos == null) return "unknown";
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    /** Get the mine state (exposed for the tool to provide feedback). */
    public MineState getMineState() {
        return mineState;
    }

    /**
     * Save this mine's location and configuration to companion memory.
     * Uses the memory key "mine_{ore}" (e.g. "mine_diamond", "mine_general").
     * Value format: "x,y,z|targetY|direction|branchLength|branchesPerSide"
     */
    private void saveMineToMemory() {
        String oreKey = targetOre != null ? targetOre.name.toLowerCase() : "general";
        String memoryKey = "mine_" + oreKey;

        BlockPos entrance = mineState.getEntrance();
        String value = entrance.getX() + "," + entrance.getY() + "," + entrance.getZ()
                + "|" + mineState.getTargetY()
                + "|" + mineState.getShaftDirection().getName()
                + "|" + mineState.getBranchLength()
                + "|" + mineState.getBranchesPerSide();

        companion.getMemory().setFact(memoryKey, value);
        companion.getMemory().addEvent("Created " + oreKey + " mine at " + formatPos(entrance)
                + " → Y=" + mineState.getTargetY());

        MCAi.LOGGER.info("CreateMine: saved mine to memory as '{}' = '{}'", memoryKey, value);
    }

    /**
     * Parse a mine memory value string back into its components.
     * @return [entranceX, entranceY, entranceZ, targetY, directionName, branchLength, branchesPerSide]
     *         or null if the format is invalid.
     */
    public static String[] parseMineMemory(String value) {
        if (value == null || value.isEmpty()) return null;
        String[] parts = value.split("\\|");
        if (parts.length < 5) return null;

        String[] coords = parts[0].split(",");
        if (coords.length != 3) return null;

        return new String[]{
                coords[0], coords[1], coords[2],  // x, y, z
                parts[1],                          // targetY
                parts[2],                          // direction
                parts[3],                          // branchLength
                parts[4]                           // branchesPerSide
        };
    }

    @Override
    protected void cleanup() {
        MCAi.LOGGER.info("CreateMine cleanup: {}", mineState.getSummary());
    }
}
