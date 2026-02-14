package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Task: Gather specific blocks nearby (flowers, mushrooms, sand, gravel, etc.)
 * Scans for the target block type and collects them.
 */
public class GatherBlocksTask extends CompanionTask {

    private static final int[] EXPAND_RADII = {32, 48}; // fallback search radii
    private final Block[] targetBlocks;
    private int radius;
    private final int maxBlocks;
    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private int blocksGathered = 0;
    private int totalBlocks = 0;

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

    @Override
    protected void cleanup() {
        targets.clear();
    }
}
