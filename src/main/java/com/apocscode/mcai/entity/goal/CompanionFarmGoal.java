package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * Farming goal — the companion harvests mature crops and replants when hungry.
 *
 * Behavior:
 *   1. Scans for mature crops within 12 blocks
 *   2. Pathfinds to the nearest one
 *   3. Breaks it (drops items that get auto-picked up)
 *   4. Replants if it has seeds
 *
 * Works with: Wheat, Carrots, Potatoes, Beetroot
 */
public class CompanionFarmGoal extends Goal {
    private final CompanionEntity companion;
    private BlockPos targetCrop;
    private int harvestCooldown;
    private int pathRetryTimer;

    private static final int SCAN_RANGE = 12;
    private static final double HARVEST_REACH = 2.5;

    public CompanionFarmGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (companion.getBehaviorMode() == CompanionEntity.BehaviorMode.STAY) return false;
        if (companion.getBehaviorMode() == CompanionEntity.BehaviorMode.FOLLOW) return false;
        if (companion.getOwner() == null) return false;
        if (companion.level().isClientSide) return false;
        if (harvestCooldown > 0) {
            harvestCooldown--;
            return false;
        }
        // Only farm when hungry or has no food
        if (companion.getHealth() >= companion.getMaxHealth() * 0.8f && companion.hasFood()) return false;

        targetCrop = findMatureCrop();
        return targetCrop != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (targetCrop == null) return false;
        BlockState state = companion.level().getBlockState(targetCrop);
        // Stop if crop was already harvested
        return isMatureCrop(state);
    }

    @Override
    public void start() {
        if (targetCrop != null) {
            companion.getChat().say(com.apocscode.mcai.entity.CompanionChat.Category.FARMING,
                    "Found a mature crop nearby. Going to harvest it.");
            companion.getNavigation().moveTo(
                    targetCrop.getX() + 0.5, targetCrop.getY(), targetCrop.getZ() + 0.5, 1.0);
            pathRetryTimer = 0;
        }
    }

    @Override
    public void tick() {
        if (targetCrop == null) return;

        double dist = companion.distanceToSqr(
                targetCrop.getX() + 0.5, targetCrop.getY() + 0.5, targetCrop.getZ() + 0.5);

        // Close enough to harvest
        if (dist < HARVEST_REACH * HARVEST_REACH) {
            harvestCrop();
            return;
        }

        // Re-path periodically
        pathRetryTimer++;
        if (pathRetryTimer % 20 == 0) {
            companion.getNavigation().moveTo(
                    targetCrop.getX() + 0.5, targetCrop.getY(), targetCrop.getZ() + 0.5, 1.0);
        }

        // Look at the crop
        companion.getLookControl().setLookAt(
                targetCrop.getX() + 0.5, targetCrop.getY() + 0.5, targetCrop.getZ() + 0.5);
    }

    @Override
    public void stop() {
        targetCrop = null;
        companion.getNavigation().stop();
        harvestCooldown = 40; // 2 second cooldown between harvests
    }

    private void harvestCrop() {
        Level level = companion.level();
        if (level.isClientSide || targetCrop == null) return;

        BlockState state = level.getBlockState(targetCrop);
        Block block = state.getBlock();

        // Break the crop — drops items that companion picks up
        level.destroyBlock(targetCrop, true, companion);
        level.playSound(null, targetCrop, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);

        // Try to replant from inventory
        tryReplant(block, targetCrop);
        targetCrop = null;
    }

    private void tryReplant(Block harvestedBlock, BlockPos pos) {
        Level level = companion.level();
        BlockState farmland = level.getBlockState(pos.below());

        // Only replant if farmland is still there
        if (!(farmland.getBlock() instanceof FarmBlock)) return;

        // Determine which seed to plant
        Item seedItem = getSeedForCrop(harvestedBlock);
        if (seedItem == null) return;

        // Check companion inventory for seeds
        var inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == seedItem) {
                // Place the crop block at age 0
                Block cropBlock = getCropBlockForSeed(seedItem);
                if (cropBlock != null) {
                    level.setBlock(pos, cropBlock.defaultBlockState(), 3);
                    stack.shrink(1);
                    if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                }
                return;
            }
        }
    }

    private BlockPos findMatureCrop() {
        BlockPos center = companion.blockPosition();
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int x = -SCAN_RANGE; x <= SCAN_RANGE; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -SCAN_RANGE; z <= SCAN_RANGE; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = companion.level().getBlockState(pos);
                    if (isMatureCrop(state)) {
                        double dist = companion.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                        if (dist < closestDist) {
                            closestDist = dist;
                            closest = pos;
                        }
                    }
                }
            }
        }
        return closest;
    }

    private boolean isMatureCrop(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        return false;
    }

    // Mapping: crop block → seed item needed to replant
    private static net.minecraft.world.item.Item getSeedForCrop(Block block) {
        if (block instanceof CropBlock) {
            if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
            if (block == Blocks.CARROTS) return Items.CARROT;
            if (block == Blocks.POTATOES) return Items.POTATO;
            if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        }
        return null;
    }

    // Mapping: seed item → crop block to place
    private static Block getCropBlockForSeed(net.minecraft.world.item.Item item) {
        if (item == Items.WHEAT_SEEDS) return Blocks.WHEAT;
        if (item == Items.CARROT) return Blocks.CARROTS;
        if (item == Items.POTATO) return Blocks.POTATOES;
        if (item == Items.BEETROOT_SEEDS) return Blocks.BEETROOTS;
        return null;
    }
}
