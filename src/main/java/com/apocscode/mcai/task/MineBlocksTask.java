package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;

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
    private boolean toolWarningGiven = false;
    private boolean foodWarningGiven = false;
    private static final int TOOL_LOW_DURABILITY = 10;

    /**
     * Mine a list of specific block positions.
     * Filters out blocks inside the home area.
     */
    public MineBlocksTask(CompanionEntity companion, List<BlockPos> blockPositions, String description) {
        super(companion);
        for (BlockPos pos : blockPositions) {
            if (!companion.isInHomeArea(pos)) {
                this.targets.add(pos);
            }
        }
        this.description = description;
    }

    /**
     * Mine a rectangular area (clear all non-air blocks).
     * Filters out blocks inside the home area.
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
                    if (!companion.level().getBlockState(pos).isAir()
                            && !companion.isInHomeArea(pos)) {
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

        // Health check — eat food if HP < 50%
        if (BlockHelper.tryEatIfLowHealth(companion, 0.5f)) {
            say("Eating some food to heal up!");
        } else if (!foodWarningGiven && companion.getHealth() / companion.getMaxHealth() < 0.3f) {
            say("I'm getting low on health and don't have any food!");
            foodWarningGiven = true;
        }

        // Tool durability check
        if (!toolWarningGiven && !BlockHelper.hasUsablePickaxe(companion, 0)) {
            // Try auto-crafting a new pickaxe before giving up
            if (BlockHelper.tryAutoCraftPickaxe(companion)) {
                say("Crafted a new pickaxe! Continuing.");
            } else {
                say("I don't have a usable pickaxe and can't craft one!");
                toolWarningGiven = true;
                complete();
                return;
            }
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
            // Safety: skip blocks that would expose lava
            if (!BlockHelper.isSafeToMine(companion.level(), currentTarget)) {
                targets.poll();
                currentTarget = null;
                stuckTimer = 0;
                return; // Skip this block silently
            }
            companion.equipBestToolForBlock(companion.level().getBlockState(currentTarget));
            BlockHelper.breakBlock(companion, currentTarget);
            // Handle falling blocks above
            handleFallingBlocks(currentTarget.above());
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

    /**
     * Handle gravity-affected blocks (gravel, sand, concrete powder) above a mined position.
     */
    private void handleFallingBlocks(BlockPos abovePos) {
        Level level = companion.level();
        int maxFalling = 10;
        BlockPos checkPos = abovePos;
        for (int i = 0; i < maxFalling; i++) {
            if (level.getBlockState(checkPos).getBlock() instanceof FallingBlock) {
                companion.equipBestToolForBlock(level.getBlockState(checkPos));
                BlockHelper.breakBlock(companion, checkPos);
                checkPos = checkPos.above();
            } else {
                break;
            }
        }
    }
}
