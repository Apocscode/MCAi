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

    private final Block targetBlock;
    private final int radius;
    private final int maxBlocks;
    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private int blocksGathered = 0;
    private int totalBlocks = 0;

    public GatherBlocksTask(CompanionEntity companion, Block targetBlock, int radius, int maxBlocks) {
        super(companion);
        this.targetBlock = targetBlock;
        this.radius = radius;
        this.maxBlocks = maxBlocks > 0 ? maxBlocks : 999;
    }

    @Override
    public String getTaskName() {
        return "Gather " + targetBlock.getName().getString() + " (r=" + radius + ")";
    }

    @Override
    public int getProgressPercent() {
        return totalBlocks > 0 ? (blocksGathered * 100) / totalBlocks : -1;
    }

    @Override
    protected void start() {
        List<BlockPos> found = BlockHelper.scanForBlocks(companion, targetBlock, radius, maxBlocks);
        targets.addAll(found);
        if (targets.isEmpty()) {
            MCAi.LOGGER.warn("GatherBlocksTask: no {} blocks found within r={}",
                    targetBlock.getName().getString(), radius);
            say("Couldn't find any " + targetBlock.getName().getString() + " nearby.");
            fail("No " + targetBlock.getName().getString() + " found within radius " + radius);
            return;
        }
        totalBlocks = targets.size();
        say("Found " + totalBlocks + " " + targetBlock.getName().getString() + " to gather!");
    }

    @Override
    protected void tick() {
        if (blocksGathered >= maxBlocks || targets.isEmpty()) {
            MCAi.LOGGER.info("GatherBlocksTask: completed — gathered {}/{} {} blocks",
                    blocksGathered, maxBlocks, targetBlock.getName().getString());
            complete();
            return;
        }

        if (currentTarget == null) {
            currentTarget = targets.peek();
            stuckTimer = 0;
        }

        if (companion.level().getBlockState(currentTarget).isAir()
                || companion.level().getBlockState(currentTarget).getBlock() != targetBlock) {
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
