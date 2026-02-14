package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Task: Gather specific blocks nearby (flowers, mushrooms, sand, gravel, etc.)
 * Scans for the target block type and collects them.
 */
public class GatherBlocksTask extends CompanionTask {

    private static final int[] EXPAND_RADII = {32, 48}; // fallback search radii
    private static final int DIG_DOWN_MAX = 8; // max blocks to dig down to reach stone
    private final Block[] targetBlocks;
    private int radius;
    private final int maxBlocks;
    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private int blocksGathered = 0;
    private int totalBlocks = 0;
    private boolean diggingDown = false;
    private BlockPos digTarget = null;
    private Direction digDirection = null;
    private int descendProgress = 0;

    public GatherBlocksTask(CompanionEntity companion, Block targetBlock, int radius, int maxBlocks) {
        this(companion, new Block[]{targetBlock}, radius, maxBlocks);
    }

    public GatherBlocksTask(CompanionEntity companion, Block[] targetBlocks, int radius, int maxBlocks) {
        super(companion);
        this.targetBlocks = targetBlocks;
        this.radius = radius;
        this.maxBlocks = maxBlocks > 0 ? maxBlocks : 999;
    }

    @Override
    public String getTaskName() {
        return "Gather " + targetBlocks[0].getName().getString() + " (r=" + radius + ")";
    }

    @Override
    public int getProgressPercent() {
        return totalBlocks > 0 ? (blocksGathered * 100) / totalBlocks : -1;
    }

    @Override
    protected void start() {
        List<BlockPos> found = BlockHelper.scanForBlocks(companion, targetBlocks, radius, maxBlocks);
        targets.addAll(found);
        if (targets.isEmpty()) {
            // Expand search radius progressively before giving up
            for (int expandRadius : EXPAND_RADII) {
                if (expandRadius <= radius) continue;
                MCAi.LOGGER.info("GatherBlocksTask: no {} at r={}, expanding to r={}",
                        targetBlocks[0].getName().getString(), radius, expandRadius);
                radius = expandRadius;
                found = BlockHelper.scanForBlocks(companion, targetBlocks, radius, maxBlocks);
                targets.addAll(found);
                if (!targets.isEmpty()) break;
            }
        }
        if (targets.isEmpty()) {
            // For stone-type blocks, try digging down from current position
            if (isStoneType()) {
                // Don't dig down inside the home area
                if (companion.isInHomeArea(companion.blockPosition())) {
                    MCAi.LOGGER.info("GatherBlocksTask: inside home area, won't dig down");
                    fail("No " + targetBlocks[0].getName().getString() + " found outside home area");
                    return;
                }
                MCAi.LOGGER.info("GatherBlocksTask: no surface {}, will try digging down",
                        targetBlocks[0].getName().getString());
                diggingDown = true;
                digTarget = companion.blockPosition();
                digDirection = companion.getDirection(); // face direction for staircase
                descendProgress = 0;
                say("No exposed " + targetBlocks[0].getName().getString() + " nearby \u2014 digging stairs down to find some!");
                return;
            }
            MCAi.LOGGER.warn("GatherBlocksTask: no {} blocks found within r={}",
                    targetBlocks[0].getName().getString(), radius);
            say("Couldn't find any " + targetBlocks[0].getName().getString() + " nearby.");
            fail("No " + targetBlocks[0].getName().getString() + " found within radius " + radius);
            return;
        }
        totalBlocks = targets.size();
        say("Found " + totalBlocks + " " + targetBlocks[0].getName().getString() + " to gather!");
    }

    @Override
    protected void tick() {
        // === Dig-down mode: break soft blocks until we hit stone ===
        if (diggingDown) {
            tickDigDown();
            return;
        }

        if (blocksGathered >= maxBlocks || targets.isEmpty()) {
            MCAi.LOGGER.info("GatherBlocksTask: finished — gathered {}/{} {} blocks",
                    blocksGathered, maxBlocks, targetBlocks[0].getName().getString());
            if (blocksGathered == 0) {
                fail("Found " + targetBlocks[0].getName().getString() + " blocks but couldn't reach any (0 gathered)");
            } else {
                complete();
            }
            return;
        }

        if (currentTarget == null) {
            currentTarget = targets.peek();
            stuckTimer = 0;
        }

        // Check if block is still a valid target (not already mined or changed)
        Block currentBlock = companion.level().getBlockState(currentTarget).getBlock();
        boolean isTarget = false;
        for (Block t : targetBlocks) {
            if (currentBlock == t) { isTarget = true; break; }
        }

        if (companion.level().getBlockState(currentTarget).isAir() || !isTarget) {
            targets.poll();
            currentTarget = null;
            return;
        }

        if (isInReach(currentTarget, 3.0)) {
            companion.equipBestToolForBlock(companion.level().getBlockState(currentTarget));
            BlockHelper.breakBlock(companion, currentTarget);
            targets.poll();
            currentTarget = null;
            stuckTimer = 0;
            blocksGathered++;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 120) {
                targets.poll();
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }

    /**
     * Dig down via staircase pattern to reach stone-type blocks.
     * Uses the same safe approach as StripMineTask: 1 forward + 1 down per step,
     * creating walkable stairs. Checks for lava, places floor if needed.
     */
    private void tickDigDown() {
        if (digDirection == null) {
            fail("Dig direction lost");
            return;
        }

        // Check depth limit
        if (descendProgress >= DIG_DOWN_MAX) {
            MCAi.LOGGER.warn("GatherBlocksTask: dug down {} steps without finding target",
                    DIG_DOWN_MAX);
            fail("Dug down " + DIG_DOWN_MAX + " steps but couldn't find "
                    + targetBlocks[0].getName().getString());
            return;
        }

        BlockPos pos = companion.blockPosition();
        Level level = companion.level();

        // Safety: near world bottom
        if (pos.getY() <= level.getMinBuildHeight() + 2) {
            MCAi.LOGGER.warn("GatherBlocksTask: near world bottom at Y={}", pos.getY());
            fail("Reached world bottom without finding "
                    + targetBlocks[0].getName().getString());
            return;
        }

        // Staircase pattern: dig 1 forward + 1 down each step
        // This creates walkable stairs the companion can climb back up
        BlockPos ahead = pos.relative(digDirection);    // one step forward
        BlockPos aheadBelow = ahead.below();              // the step-down position (new feet)
        BlockPos aheadHead = ahead;                       // head clearance at step-down
        BlockPos aheadAbove = ahead.above();              // extra clearance above

        // Safety: check for lava at the step-down position
        if (!BlockHelper.isSafeToMine(level, aheadBelow)) {
            MCAi.LOGGER.warn("GatherBlocksTask: lava detected at stairs, aborting dig-down");
            fail("Lava detected while digging stairs — aborting for safety");
            return;
        }

        // Check if any of the 3 blocks we're about to clear are target blocks
        for (BlockPos checkPos : new BlockPos[]{aheadAbove, aheadHead, aheadBelow}) {
            BlockState checkState = level.getBlockState(checkPos);
            for (Block t : targetBlocks) {
                if (checkState.getBlock() == t) {
                    MCAi.LOGGER.info("GatherBlocksTask: found {} at stair step {}, switching to gather mode",
                            t.getName().getString(), descendProgress + 1);
                    diggingDown = false;
                    // Fresh scan around exposed stone
                    List<BlockPos> found = BlockHelper.scanForBlocks(companion, targetBlocks, 6, maxBlocks);
                    if (!found.isEmpty()) {
                        targets.addAll(found);
                        totalBlocks = targets.size();
                        say("Found " + totalBlocks + " " + targetBlocks[0].getName().getString() + " underground!");
                    } else {
                        targets.add(checkPos);
                        totalBlocks = 1;
                    }
                    return;
                }
            }
        }

        // Clear 3 blocks for the step: above, head, and step-down
        BlockState aboveState = level.getBlockState(aheadAbove);
        if (!aboveState.isAir()) {
            companion.equipBestToolForBlock(aboveState);
            BlockHelper.breakBlock(companion, aheadAbove);
        }

        BlockState headState = level.getBlockState(aheadHead);
        if (!headState.isAir()) {
            companion.equipBestToolForBlock(headState);
            BlockHelper.breakBlock(companion, aheadHead);
        }

        BlockState belowState = level.getBlockState(aheadBelow);
        if (!belowState.isAir()) {
            companion.equipBestToolForBlock(belowState);
            BlockHelper.breakBlock(companion, aheadBelow);
        }

        // Ensure solid floor under the new step (prevent falling into caves)
        BlockPos floorCheck = aheadBelow.below();
        BlockState floorState = level.getBlockState(floorCheck);
        if (floorState.isAir() || floorState.getFluidState().is(FluidTags.LAVA)) {
            BlockHelper.placeBlock(companion, floorCheck, Blocks.COBBLESTONE);
        }

        // Navigate to the new lower position
        navigateTo(aheadBelow);
        descendProgress++;
    }

    /**
     * Check if the target blocks are stone-type (underground blocks).
     * These blocks might require digging down to reach from the surface.
     */
    private boolean isStoneType() {
        for (Block b : targetBlocks) {
            if (b == Blocks.STONE || b == Blocks.COBBLESTONE
                    || b == Blocks.DEEPSLATE || b == Blocks.COBBLED_DEEPSLATE
                    || b == Blocks.CALCITE || b == Blocks.TUFF
                    || b == Blocks.DRIPSTONE_BLOCK || b == Blocks.BASALT
                    || b == Blocks.BLACKSTONE || b == Blocks.NETHERRACK) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void cleanup() {
        targets.clear();
    }
}
