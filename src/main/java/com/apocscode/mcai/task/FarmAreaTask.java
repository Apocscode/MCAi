package com.apocscode.mcai.task;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Task: Farm an area.
 * Steps: Navigate to each block, hoe dirt→farmland, plant seeds.
 * Also harvests any fully-grown crops in the area.
 */
public class FarmAreaTask extends CompanionTask {

    private final BlockPos corner1;
    private final BlockPos corner2;
    private final CropType cropType;

    private final Deque<BlockPos> workQueue = new ArrayDeque<>();
    private Phase phase = Phase.HOE;
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private int totalWork = 0;
    private int workDone = 0;

    public enum Phase { HOE, PLANT, HARVEST, DONE }

    public enum CropType {
        WHEAT(Items.WHEAT_SEEDS, Blocks.WHEAT),
        CARROT(Items.CARROT, Blocks.CARROTS),
        POTATO(Items.POTATO, Blocks.POTATOES),
        BEETROOT(Items.BEETROOT_SEEDS, Blocks.BEETROOTS);

        public final Item seedItem;
        public final Block cropBlock;

        CropType(Item seedItem, Block cropBlock) {
            this.seedItem = seedItem;
            this.cropBlock = cropBlock;
        }

        public static CropType fromString(String name) {
            try {
                return valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                return WHEAT; // default
            }
        }
    }

    public FarmAreaTask(CompanionEntity companion, BlockPos corner1, BlockPos corner2, CropType cropType) {
        super(companion);
        // Normalize corners (min/max)
        this.corner1 = new BlockPos(
                Math.min(corner1.getX(), corner2.getX()),
                Math.min(corner1.getY(), corner2.getY()),
                Math.min(corner1.getZ(), corner2.getZ())
        );
        this.corner2 = new BlockPos(
                Math.max(corner1.getX(), corner2.getX()),
                Math.max(corner1.getY(), corner2.getY()),
                Math.max(corner1.getZ(), corner2.getZ())
        );
        this.cropType = cropType;
    }

    @Override
    public String getTaskName() {
        int sizeX = corner2.getX() - corner1.getX() + 1;
        int sizeZ = corner2.getZ() - corner1.getZ() + 1;
        return "Farm " + sizeX + "x" + sizeZ + " " + cropType.name().toLowerCase();
    }

    @Override
    protected void start() {
        // Calculate total area for progress tracking
        int sizeX = corner2.getX() - corner1.getX() + 1;
        int sizeZ = corner2.getZ() - corner1.getZ() + 1;
        totalWork = sizeX * sizeZ * 2; // hoe + plant each block (rough estimate)
        workDone = 0;

        // First: harvest any mature crops in the area
        phase = Phase.HARVEST;
        buildHarvestQueue();
        if (workQueue.isEmpty()) {
            phase = Phase.HOE;
            buildHoeQueue();
        }
        say("Starting to farm! " + getTaskName());
    }

    @Override
    public int getProgressPercent() {
        if (totalWork <= 0) return -1;
        return Math.min(100, (workDone * 100) / totalWork);
    }

    @Override
    protected void tick() {
        switch (phase) {
            case HARVEST -> tickHarvest();
            case HOE -> tickHoe();
            case PLANT -> tickPlant();
            case DONE -> complete();
        }
    }

    @Override
    protected void cleanup() {
        workQueue.clear();
    }

    // --- HARVEST phase: break mature crops ---

    private void buildHarvestQueue() {
        workQueue.clear();
        for (int x = corner1.getX(); x <= corner2.getX(); x++) {
            for (int z = corner1.getZ(); z <= corner2.getZ(); z++) {
                BlockPos cropPos = new BlockPos(x, corner1.getY() + 1, z);
                BlockState state = companion.level().getBlockState(cropPos);
                if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                    workQueue.add(cropPos);
                }
            }
        }
    }

    private void tickHarvest() {
        if (workQueue.isEmpty()) {
            phase = Phase.HOE;
            buildHoeQueue();
            if (workQueue.isEmpty()) {
                phase = Phase.PLANT;
                buildPlantQueue();
                if (workQueue.isEmpty()) {
                    phase = Phase.DONE;
                }
            }
            return;
        }

        if (currentTarget == null) {
            currentTarget = workQueue.peek();
            stuckTimer = 0;
        }

        if (isInReach(currentTarget, 2.5)) {
            companion.equipBestToolForBlock(companion.level().getBlockState(currentTarget));
            BlockHelper.breakBlock(companion, currentTarget);
            workQueue.poll();
            currentTarget = null;
            stuckTimer = 0;
            workDone++;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 100) {
                // Skip this one
                workQueue.poll();
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }

    // --- HOE phase: convert dirt/grass to farmland ---

    private void buildHoeQueue() {
        workQueue.clear();
        for (int x = corner1.getX(); x <= corner2.getX(); x++) {
            for (int z = corner1.getZ(); z <= corner2.getZ(); z++) {
                BlockPos pos = new BlockPos(x, corner1.getY(), z);
                BlockState state = companion.level().getBlockState(pos);
                Block block = state.getBlock();
                // Need to hoe if it's grass/dirt but NOT farmland
                if (block == Blocks.GRASS_BLOCK || block == Blocks.DIRT
                        || block == Blocks.DIRT_PATH || block == Blocks.COARSE_DIRT) {
                    workQueue.add(pos);
                }
            }
        }
    }

    private void tickHoe() {
        if (workQueue.isEmpty()) {
            phase = Phase.PLANT;
            buildPlantQueue();
            if (workQueue.isEmpty()) {
                phase = Phase.DONE;
            }
            return;
        }

        if (currentTarget == null) {
            currentTarget = workQueue.peek();
            stuckTimer = 0;
        }

        if (isInReach(currentTarget, 2.5)) {
            BlockHelper.hoeBlock(companion, currentTarget);
            workQueue.poll();
            currentTarget = null;
            stuckTimer = 0;
            workDone++;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 100) {
                workQueue.poll();
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }

    // --- PLANT phase: place seeds on farmland ---

    private void buildPlantQueue() {
        workQueue.clear();
        for (int x = corner1.getX(); x <= corner2.getX(); x++) {
            for (int z = corner1.getZ(); z <= corner2.getZ(); z++) {
                BlockPos farmPos = new BlockPos(x, corner1.getY(), z);
                BlockPos abovePos = farmPos.above();
                BlockState below = companion.level().getBlockState(farmPos);
                BlockState above = companion.level().getBlockState(abovePos);
                if (below.getBlock() instanceof net.minecraft.world.level.block.FarmBlock
                        && above.isAir()) {
                    workQueue.add(farmPos);
                }
            }
        }
    }

    private void tickPlant() {
        if (workQueue.isEmpty()) {
            phase = Phase.DONE;
            return;
        }

        // Check if we have seeds
        int seedCount = BlockHelper.countItem(companion, cropType.seedItem);
        if (seedCount <= 0) {
            say("Out of " + cropType.seedItem.getDescription().getString() + "!");
            phase = Phase.DONE;
            return;
        }

        if (currentTarget == null) {
            currentTarget = workQueue.peek();
            stuckTimer = 0;
        }

        if (isInReach(currentTarget, 2.5)) {
            boolean planted = BlockHelper.plantCrop(companion, currentTarget,
                    cropType.seedItem, cropType.cropBlock);
            if (!planted) {
                // Farmland might have reverted, skip
            }
            workDone++;
            workQueue.poll();
            currentTarget = null;
            stuckTimer = 0;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 100) {
                workQueue.poll();
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }
}
