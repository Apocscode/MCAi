package com.apocscode.mcai.task;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Task: Build a structure by placing blocks in a pattern.
 * Supports walls, floors, platforms, and simple shelters.
 * Consumes blocks from the companion's inventory.
 */
public class BuildTask extends CompanionTask {

    public enum Shape {
        WALL, FLOOR, PLATFORM, SHELTER, COLUMN
    }

    private final Shape shape;
    private final Block block;
    private final BlockPos origin;
    private final int width;
    private final int height;
    private final int depth;
    private final Deque<BlockPos> buildQueue = new ArrayDeque<>();
    private BlockPos currentTarget;
    private int blocksPlaced = 0;
    private int totalBlocks = 0;
    private int stuckTimer = 0;

    public BuildTask(CompanionEntity companion, Shape shape, Block block,
                     BlockPos origin, int width, int height, int depth) {
        super(companion, "Build " + shape.name().toLowerCase() + " (" + width + "x" + height + "x" + depth + ")");
        this.shape = shape;
        this.block = block;
        this.origin = origin;
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    @Override
    public int getProgressPercent() {
        return totalBlocks > 0 ? (blocksPlaced * 100) / totalBlocks : -1;
    }

    @Override
    protected void start() {
        generateBuildQueue();
        totalBlocks = buildQueue.size();

        if (totalBlocks == 0) {
            say("Nothing to build — all positions are occupied.");
            complete();
            return;
        }

        int available = BlockHelper.countItem(companion,
                block.asItem());
        if (available < totalBlocks) {
            fail("Not enough " + block.getName().getString() + " — have " + available
                    + ", need " + totalBlocks + ".");
            return;
        }

        say("Building " + shape.name().toLowerCase() + " with " + totalBlocks + " blocks!");
    }

    @Override
    protected void tick() {
        if (buildQueue.isEmpty()) {
            say("Build complete! Placed " + blocksPlaced + " blocks.");
            complete();
            return;
        }

        if (currentTarget == null) {
            currentTarget = buildQueue.peek();
            stuckTimer = 0;
        }

        if (isInReach(currentTarget, 4.5)) {
            if (BlockHelper.placeBlock(companion, currentTarget, block)) {
                blocksPlaced++;
            }
            buildQueue.poll();
            currentTarget = null;
            stuckTimer = 0;
        } else {
            // Navigate close enough to place
            BlockPos standPos = findStandPos(currentTarget);
            navigateTo(standPos != null ? standPos : currentTarget);
            stuckTimer++;
            if (stuckTimer > 100) {
                // Skip this block
                buildQueue.poll();
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }

    private void generateBuildQueue() {
        switch (shape) {
            case WALL -> {
                for (int y = 0; y < height; y++)
                    for (int x = 0; x < width; x++) {
                        BlockPos pos = origin.offset(x, y, 0);
                        if (companion.level().getBlockState(pos).canBeReplaced())
                            buildQueue.add(pos);
                    }
            }
            case FLOOR, PLATFORM -> {
                for (int x = 0; x < width; x++)
                    for (int z = 0; z < depth; z++) {
                        BlockPos pos = origin.offset(x, 0, z);
                        if (companion.level().getBlockState(pos).canBeReplaced())
                            buildQueue.add(pos);
                    }
            }
            case COLUMN -> {
                for (int y = 0; y < height; y++) {
                    BlockPos pos = origin.offset(0, y, 0);
                    if (companion.level().getBlockState(pos).canBeReplaced())
                        buildQueue.add(pos);
                }
            }
            case SHELTER -> {
                // Floor
                for (int x = 0; x < width; x++)
                    for (int z = 0; z < depth; z++) {
                        BlockPos pos = origin.offset(x, 0, z);
                        if (companion.level().getBlockState(pos).canBeReplaced())
                            buildQueue.add(pos);
                    }
                // Walls (4 sides, height-1 above floor)
                for (int y = 1; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        addIfReplaceable(origin.offset(x, y, 0));
                        addIfReplaceable(origin.offset(x, y, depth - 1));
                    }
                    for (int z = 1; z < depth - 1; z++) {
                        addIfReplaceable(origin.offset(0, y, z));
                        addIfReplaceable(origin.offset(width - 1, y, z));
                    }
                }
                // Roof
                for (int x = 0; x < width; x++)
                    for (int z = 0; z < depth; z++) {
                        addIfReplaceable(origin.offset(x, height, z));
                    }
                // Leave a door (remove front-center bottom blocks from queue)
                int doorX = width / 2;
                buildQueue.remove(origin.offset(doorX, 1, 0));
                buildQueue.remove(origin.offset(doorX, 2, 0));
            }
        }
    }

    private void addIfReplaceable(BlockPos pos) {
        if (companion.level().getBlockState(pos).canBeReplaced())
            buildQueue.add(pos);
    }

    private BlockPos findStandPos(BlockPos target) {
        // Find a solid block adjacent to stand on
        BlockPos[] candidates = {
                target.north(), target.south(), target.east(), target.west(),
                target.below()
        };
        for (BlockPos c : candidates) {
            if (companion.level().getBlockState(c.below()).isSolidRender(companion.level(), c.below())
                    && companion.level().getBlockState(c).isAir()) {
                return c;
            }
        }
        return null;
    }

    @Override
    protected void cleanup() {
        buildQueue.clear();
    }
}
