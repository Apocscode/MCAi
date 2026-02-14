package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Task: Chop down trees using tree felling — break the base log and the entire tree falls.
 *
 * Tree Felling Mechanic:
 *   1. Scan for tree bases (bottom-most log blocks sitting on dirt/grass)
 *   2. Navigate to each tree base (ground level — always reachable)
 *   3. Break the base log → flood-fill upward to instantly break ALL connected logs
 *   4. All drops go directly into companion inventory (no ground chasing)
 *
 * This eliminates pathfinding issues with floating upper logs and is much faster
 * than breaking logs one-by-one. Similar to TreeCapitator/FallingTree mod mechanics.
 *
 * Post-felling:
 *   - Breaks nearby orphaned leaves to force sapling/apple drops
 *   - Sweeps ground items (from leaf decay)
 *   - Replants a matching sapling at each tree base
 */
public class ChopTreesTask extends CompanionTask {

    private static final int[] EXPAND_RADII = {32, 48}; // fallback search radii
    private int radius;
    private final int maxLogs;
    private final Deque<BlockPos> treeBaseTargets = new ArrayDeque<>();
    private final Deque<BlockPos> leafTargets = new ArrayDeque<>();
    private final List<BlockPos> felledBases = new ArrayList<>();
    private final Set<BlockPos> replanted = new HashSet<>();
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private int logsChopped = 0;
    private int treesFelled = 0;
    private int leavesCleared = 0;
    private int saplingsPlanted = 0;
    private int itemSweepTimer = 0;
    private static final int MAX_LEAVES_PER_TREE = 40;
    private static final int ITEM_SWEEP_INTERVAL = 40; // every 2 seconds
    private static final int MAX_LOGS_PER_TREE = 128; // safety cap for giant modded trees

    private enum Phase {
        FELLING_TREES,   // Navigate to base, break base → instant full tree break
        CLEARING_LEAVES, // Break orphaned leaves to force drops
        SWEEPING_ITEMS,  // Collect dropped items
        REPLANTING       // Plant saplings at felled tree bases
    }
    private Phase phase = Phase.FELLING_TREES;

    /**
     * @param radius   Search radius for trees
     * @param maxLogs  Maximum logs to chop before stopping (0 = unlimited/within radius)
     */
    public ChopTreesTask(CompanionEntity companion, int radius, int maxLogs) {
        super(companion);
        this.radius = radius;
        this.maxLogs = maxLogs > 0 ? maxLogs : 999;
    }

    @Override
    public String getTaskName() {
        return "Chop trees (r=" + radius + ")";
    }

    @Override
    public int getProgressPercent() {
        return maxLogs > 0 ? Math.min(100, (logsChopped * 100) / maxLogs) : -1;
    }

    @Override
    protected void start() {
        scanForTreeBases();
        if (treeBaseTargets.isEmpty()) {
            // Expand search radius progressively before giving up
            for (int expandRadius : EXPAND_RADII) {
                if (expandRadius <= radius) continue;
                MCAi.LOGGER.info("ChopTreesTask: no trees at r={}, expanding to r={}", radius, expandRadius);
                radius = expandRadius;
                scanForTreeBases();
                if (!treeBaseTargets.isEmpty()) break;
            }
        }
        if (treeBaseTargets.isEmpty()) {
            say("No trees found within " + radius + " blocks.");
            fail("No trees found within radius " + radius);
            return;
        }
        say("Found " + treeBaseTargets.size() + " trees to fell!");
    }

    @Override
    protected void tick() {
        // Periodic item sweep during any phase
        itemSweepTimer++;
        if (itemSweepTimer >= ITEM_SWEEP_INTERVAL) {
            itemSweepTimer = 0;
            sweepNearbyItems();
        }

        switch (phase) {
            case FELLING_TREES -> tickFellTrees();
            case CLEARING_LEAVES -> tickClearLeaves();
            case SWEEPING_ITEMS -> tickSweepItems();
            case REPLANTING -> tickReplant();
        }
    }

    // ========== PHASE 1: Tree Felling ==========

    private void tickFellTrees() {
        if (logsChopped >= maxLogs) {
            MCAi.LOGGER.info("ChopTreesTask: reached maxLogs={}, transitioning to leaves", maxLogs);
            transitionToLeaves();
            return;
        }

        if (treeBaseTargets.isEmpty()) {
            transitionToLeaves();
            return;
        }

        if (currentTarget == null) {
            currentTarget = treeBaseTargets.peek();
            stuckTimer = 0;
        }

        // Check if base is still a log (might have been broken by another means)
        BlockState state = companion.level().getBlockState(currentTarget);
        if (state.isAir() || !state.is(BlockTags.LOGS)) {
            treeBaseTargets.poll();
            currentTarget = null;
            return;
        }

        if (isInReach(currentTarget, 3.5)) {
            // === TREE FELLING: break base → flood-fill break all connected logs ===
            BlockPos basePos = currentTarget;
            companion.equipBestToolForBlock(state);
            int felled = fellTree(basePos);
            logsChopped += felled;
            treesFelled++;
            felledBases.add(basePos);
            treeBaseTargets.poll();
            currentTarget = null;
            stuckTimer = 0;

            MCAi.LOGGER.info("ChopTreesTask: felled tree #{} at {} — {} logs (total: {}/{})",
                    treesFelled, basePos, felled, logsChopped, maxLogs);
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 160) {
                MCAi.LOGGER.debug("ChopTreesTask: can't reach tree base at {}, skipping", currentTarget);
                treeBaseTargets.poll();
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }

    /**
     * Fell an entire tree starting from the base log position.
     * Uses flood-fill to find ALL connected log blocks (up, and diagonally for branch trees)
     * and breaks them all instantly, collecting drops into companion inventory.
     *
     * @param base The bottom-most log of the tree
     * @return Number of logs broken
     */
    private int fellTree(BlockPos base) {
        Level level = companion.level();
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> logsToBreak = new ArrayList<>();

        queue.add(base);
        visited.add(base);

        while (!queue.isEmpty() && logsToBreak.size() < MAX_LOGS_PER_TREE) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);

            if (!state.is(BlockTags.LOGS)) continue;
            logsToBreak.add(pos);

            // Check all 26 neighbors (3x3x3 cube minus center) to handle branching trees
            // but only expand upward and sideways — don't go below the base
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) { // Only up and same level (not down)
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos neighbor = pos.offset(dx, dy, dz);
                        if (!visited.contains(neighbor)) {
                            visited.add(neighbor);
                            BlockState nState = level.getBlockState(neighbor);
                            if (nState.is(BlockTags.LOGS)) {
                                queue.add(neighbor);
                            }
                        }
                    }
                }
            }
        }

        // Break all logs — bottom-up so the visual makes sense
        logsToBreak.sort(Comparator.comparingInt(BlockPos::getY));
        int broken = 0;
        for (BlockPos log : logsToBreak) {
            if (BlockHelper.breakBlock(companion, log)) {
                broken++;
            }
        }

        MCAi.LOGGER.debug("fellTree: flood-filled {} logs from base {}", broken, base);
        return broken;
    }

    // ========== PHASE 2: Clear nearby leaves ==========

    private void transitionToLeaves() {
        phase = Phase.CLEARING_LEAVES;
        currentTarget = null;
        stuckTimer = 0;
        scanForLeaves();
        if (leafTargets.isEmpty()) {
            transitionToSweep();
        } else {
            MCAi.LOGGER.info("ChopTreesTask: clearing {} orphaned leaves", leafTargets.size());
        }
    }

    private void tickClearLeaves() {
        if (leafTargets.isEmpty() || leavesCleared >= MAX_LEAVES_PER_TREE * Math.max(treesFelled, 1)) {
            transitionToSweep();
            return;
        }

        if (currentTarget == null) {
            currentTarget = leafTargets.peek();
            stuckTimer = 0;
        }

        BlockState state = companion.level().getBlockState(currentTarget);
        if (state.isAir() || !(state.getBlock() instanceof LeavesBlock)) {
            leafTargets.poll();
            currentTarget = null;
            return;
        }

        if (isInReach(currentTarget, 4.0)) {
            BlockHelper.breakBlock(companion, currentTarget);
            leafTargets.poll();
            currentTarget = null;
            stuckTimer = 0;
            leavesCleared++;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 80) {
                leafTargets.poll();
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }

    // ========== PHASE 3: Final item sweep ==========

    private void transitionToSweep() {
        phase = Phase.SWEEPING_ITEMS;
        stuckTimer = 0;
    }

    private void tickSweepItems() {
        stuckTimer++;
        if (stuckTimer > 40) {
            sweepNearbyItems();
            transitionToReplant();
        }
    }

    // ========== PHASE 4: Replant saplings ==========

    private void transitionToReplant() {
        phase = Phase.REPLANTING;
        currentTarget = null;
        stuckTimer = 0;
    }

    private void tickReplant() {
        if (currentTarget == null) {
            BlockPos nextBase = null;
            for (BlockPos base : felledBases) {
                if (!replanted.contains(base)) {
                    nextBase = base;
                    break;
                }
            }
            if (nextBase == null) {
                finishTask();
                return;
            }
            currentTarget = nextBase;
            stuckTimer = 0;
        }

        if (isInReach(currentTarget, 3.5)) {
            tryReplantSapling(currentTarget);
            replanted.add(currentTarget);
            currentTarget = null;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 100) {
                replanted.add(currentTarget);
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }

    // ========== Completion ==========

    private void finishTask() {
        MCAi.LOGGER.info("ChopTreesTask complete: {} trees felled, {} logs total, {} leaves cleared, {} saplings planted",
                treesFelled, logsChopped, leavesCleared, saplingsPlanted);

        // If trees were found but none could be reached/felled, report failure
        if (logsChopped == 0) {
            fail("Found trees but couldn't reach or fell any (0 logs collected)");
            return;
        }

        StringBuilder msg = new StringBuilder();
        msg.append("Done! Felled ").append(treesFelled).append(" tree")
           .append(treesFelled != 1 ? "s" : "")
           .append(" (").append(logsChopped).append(" logs)");
        if (leavesCleared > 0) {
            msg.append(", cleared ").append(leavesCleared).append(" leaves");
        }
        if (saplingsPlanted > 0) {
            msg.append(", replanted ").append(saplingsPlanted).append(" sapling")
               .append(saplingsPlanted > 1 ? "s" : "");
        }
        msg.append(".");
        say(msg.toString());
        complete();
    }

    @Override
    protected void cleanup() {
        treeBaseTargets.clear();
        leafTargets.clear();
        felledBases.clear();
        replanted.clear();
    }

    // ========== Scanning ==========

    /**
     * Scan for tree base positions — the bottom-most log of each tree.
     * A tree base is a log block with dirt/grass below it (natural tree).
     * This ensures the companion only needs to pathfind to ground-level positions
     * (always reachable) and then fells the whole tree from there.
     */
    private void scanForTreeBases() {
        treeBaseTargets.clear();
        Level level = companion.level();
        BlockPos center = companion.blockPosition();
        Set<BlockPos> foundBases = new LinkedHashSet<>();

        // Clamp Y to world bounds (-64 to 319 in overworld)
        int minY = Math.max(-4, level.getMinBuildHeight() - center.getY());
        int maxY = Math.min(radius, level.getMaxBuildHeight() - 1 - center.getY());

        for (int x = -radius; x <= radius; x++) {
            for (int y = minY; y <= maxY; y++) { // Full vertical range to catch trees on hills/valleys
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(BlockTags.LOGS)) continue;

                    // Walk down to the actual base
                    BlockPos base = pos;
                    while (level.getBlockState(base.below()).is(BlockTags.LOGS)) {
                        base = base.below();
                    }

                    // Must be standing on dirt/grass (natural tree, not a build)
                    BlockState ground = level.getBlockState(base.below());
                    if (ground.is(BlockTags.DIRT)) {
                        foundBases.add(base);
                    }
                }
            }
        }

        // Sort by distance (nearest first)
        List<BlockPos> sorted = new ArrayList<>(foundBases);
        sorted.sort((a, b) -> {
            double distA = companion.distanceToSqr(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5);
            double distB = companion.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            return Double.compare(distA, distB);
        });

        treeBaseTargets.addAll(sorted);
        MCAi.LOGGER.info("ChopTreesTask: found {} tree bases within radius {}", sorted.size(), radius);
    }

    /**
     * Scan for orphaned leaves near felled trees.
     * Only targets non-persistent leaves (natural, not player-placed).
     */
    private void scanForLeaves() {
        leafTargets.clear();
        Level level = companion.level();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> found = new ArrayList<>();

        for (BlockPos base : felledBases) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = 0; dy <= 24; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        BlockPos pos = base.offset(dx, dy, dz);
                        if (!seen.add(pos)) continue;

                        BlockState state = level.getBlockState(pos);
                        if (state.getBlock() instanceof LeavesBlock
                                && !state.getValue(LeavesBlock.PERSISTENT)) {
                            found.add(pos);
                        }
                    }
                }
            }
        }

        // Sort by distance
        found.sort((a, b) -> {
            double distA = companion.distanceToSqr(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5);
            double distB = companion.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            return Double.compare(distA, distB);
        });
        leafTargets.addAll(found);
    }

    // ========== Sapling replanting ==========

    /**
     * Attempt to replant a sapling at a tree base position.
     * Matches the sapling type to whatever sapling is in the companion's inventory.
     * Prefers the sapling that matches the original tree type, but will use any available.
     */
    private void tryReplantSapling(BlockPos base) {
        Level level = companion.level();

        // Base position should now be air (the log was chopped)
        BlockState baseState = level.getBlockState(base);
        if (!baseState.isAir() && !baseState.canBeReplaced()) return;

        // Ground below must be dirt/grass/podzol
        BlockState ground = level.getBlockState(base.below());
        if (!ground.is(BlockTags.DIRT)) return;

        // Find any sapling in companion inventory
        var inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            Block saplingBlock = getSaplingBlock(stack.getItem());
            if (saplingBlock != null) {
                // Place the sapling
                level.setBlock(base, saplingBlock.defaultBlockState(), 3);
                stack.shrink(1);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                saplingsPlanted++;
                MCAi.LOGGER.info("Replanted {} at {}", saplingBlock.getName().getString(), base);
                return;
            }
        }

        MCAi.LOGGER.debug("No saplings available to replant at {}", base);
    }

    /**
     * Map sapling items to their block form for planting.
     * Returns null if the item is not a sapling.
     */
    private static Block getSaplingBlock(Item item) {
        if (item == Items.OAK_SAPLING) return Blocks.OAK_SAPLING;
        if (item == Items.SPRUCE_SAPLING) return Blocks.SPRUCE_SAPLING;
        if (item == Items.BIRCH_SAPLING) return Blocks.BIRCH_SAPLING;
        if (item == Items.JUNGLE_SAPLING) return Blocks.JUNGLE_SAPLING;
        if (item == Items.ACACIA_SAPLING) return Blocks.ACACIA_SAPLING;
        if (item == Items.DARK_OAK_SAPLING) return Blocks.DARK_OAK_SAPLING;
        if (item == Items.CHERRY_SAPLING) return Blocks.CHERRY_SAPLING;
        if (item == Items.MANGROVE_PROPAGULE) return Blocks.MANGROVE_PROPAGULE;

        // Modded saplings: try the generic block-from-item approach
        Block block = Block.byItem(item);
        if (block != Blocks.AIR) {
            BlockState state = block.defaultBlockState();
            if (state.is(BlockTags.SAPLINGS)) {
                return block;
            }
        }
        return null;
    }

    // ========== Item collection ==========

    /**
     * Actively collect dropped items within 6 blocks of the companion.
     * Picks up saplings, apples, sticks, and any other drops from leaves/logs.
     * This supplements the CompanionPickupItemGoal which may be interrupted by task navigation.
     */
    private void sweepNearbyItems() {
        Level level = companion.level();
        AABB box = companion.getBoundingBox().inflate(6.0);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && !e.isRemoved() && !e.hasPickUpDelay());

        for (ItemEntity itemEntity : items) {
            if (companion.distanceToSqr(itemEntity) <= 2.0 * 2.0) {
                // Close enough — pick up directly
                ItemStack stack = itemEntity.getItem();
                ItemStack remainder = companion.getCompanionInventory().addItem(stack.copy());
                if (remainder.isEmpty()) {
                    companion.take(itemEntity, stack.getCount());
                    itemEntity.discard();
                } else if (remainder.getCount() < stack.getCount()) {
                    int picked = stack.getCount() - remainder.getCount();
                    companion.take(itemEntity, picked);
                    itemEntity.getItem().setCount(remainder.getCount());
                }
            }
        }
    }
}
