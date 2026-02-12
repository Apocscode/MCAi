package com.apocscode.mcai.task;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Task: Mine blocks in an area or mine specific ores.
 * The companion navigates to each target block and breaks it.
 */
public class MineBlocksTask extends CompanionTask {

    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private final String description;
    private int totalBlocks = 0;
    private int blocksMined = 0;

    /**
     * Mine a list of specific block positions.
     */
    public MineBlocksTask(CompanionEntity companion, List<BlockPos> blockPositions, String description) {
        super(companion);
        this.targets.addAll(blockPositions);
        this.description = description;
    }

    /**
     * Mine a rectangular area (clear all non-air blocks).
     */
    public MineBlocksTask(CompanionEntity companion, BlockPos from, BlockPos to) {
        super(companion);
        this.description = "Mine area";
        int minX = Math.min(from.getX(), to.getX());
        int minY = Math.min(from.getY(), to.getY());
        int minZ = Math.min(from.getZ(), to.getZ());
        int maxX = Math.max(from.getX(), to.getX());
        int maxY = Math.max(from.getY(), to.getY());
        int maxZ = Math.max(from.getZ(), to.getZ());

        // Mine top-down to avoid gravity issues
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!companion.level().getBlockState(pos).isAir()) {
                        targets.add(pos);
                    }
                }
            }
        }
    }

    @Override
    public String getTaskName() {
        return description + " (" + targets.size() + " blocks)";
    }

    @Override
    public int getProgressPercent() {
        return totalBlocks > 0 ? (blocksMined * 100) / totalBlocks : -1;
    }

    @Override
    protected void start() {
        if (targets.isEmpty()) {
            say("Nothing to mine here.");
            complete();
            return;
        }
        totalBlocks = targets.size();
        say("Starting to mine " + totalBlocks + " blocks!");
    }

    @Override
    protected void tick() {
        if (targets.isEmpty()) {
            complete();
            return;
        }

        if (currentTarget == null) {
            currentTarget = targets.peek();
            stuckTimer = 0;
        }

        // Skip if already air (another entity broke it, etc.)
        if (companion.level().getBlockState(currentTarget).isAir()) {
            targets.poll();
            currentTarget = null;
            return;
        }

        if (isInReach(currentTarget, 3.0)) {
            companion.equipBestToolForBlock(companion.level().getBlockState(currentTarget));
            BlockHelper.breakBlock(companion, currentTarget);
            blocksMined++;
            targets.poll();
            currentTarget = null;
            stuckTimer = 0;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 120) {
                // Can't reach this block, skip
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
