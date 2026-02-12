package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * Cooking goal — the companion finds a furnace or campfire and cooks raw food.
 *
 * Behavior:
 *   1. Checks if it has raw food (raw beef, pork, chicken, rabbit, mutton, cod, salmon, potato)
 *   2. Scans for nearby furnace/smoker/campfire
 *   3. Pathfinds to it
 *   4. For furnace/smoker: inserts raw food + fuel, collects output if ready
 *   5. For campfire: places food on the campfire
 */
public class CompanionCookFoodGoal extends Goal {
    private final CompanionEntity companion;
    private BlockPos targetCooker;
    private int actionCooldown;
    private int pathRetryTimer;

    private static final int SCAN_RANGE = 16;
    private static final double INTERACT_REACH = 2.5;

    public CompanionCookFoodGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (companion.getBehaviorMode() == CompanionEntity.BehaviorMode.STAY) return false;
        if (companion.getBehaviorMode() == CompanionEntity.BehaviorMode.FOLLOW) return false;
        if (companion.getOwner() == null) return false;
        if (companion.level().isClientSide) return false;
        if (actionCooldown > 0) {
            actionCooldown--;
            return false;
        }

        // Need raw food to cook, OR we know a furnace might have output
        if (!companion.hasRawFood()) return false;

        targetCooker = findNearbyCooker();
        return targetCooker != null;
    }

    @Override
    public boolean canContinueToUse() {
        return targetCooker != null;
    }

    @Override
    public void start() {
        if (targetCooker != null) {
            net.minecraft.world.level.block.Block block = companion.level().getBlockState(targetCooker).getBlock();
            String cookerName = block instanceof net.minecraft.world.level.block.CampfireBlock ? "campfire" : "furnace";
            companion.getChat().say(com.apocscode.mcai.entity.CompanionChat.Category.COOKING,
                    "I have raw food. Heading to a " + cookerName + " to cook it.");
            companion.getNavigation().moveTo(
                    targetCooker.getX() + 0.5, targetCooker.getY(), targetCooker.getZ() + 0.5, 1.0);
            pathRetryTimer = 0;
        }
    }

    @Override
    public void tick() {
        if (targetCooker == null) return;

        double dist = companion.distanceToSqr(
                targetCooker.getX() + 0.5, targetCooker.getY() + 0.5, targetCooker.getZ() + 0.5);

        if (dist < INTERACT_REACH * INTERACT_REACH) {
            interactWithCooker();
            return;
        }

        pathRetryTimer++;
        if (pathRetryTimer % 20 == 0) {
            companion.getNavigation().moveTo(
                    targetCooker.getX() + 0.5, targetCooker.getY(), targetCooker.getZ() + 0.5, 1.0);
        }

        companion.getLookControl().setLookAt(
                targetCooker.getX() + 0.5, targetCooker.getY() + 0.5, targetCooker.getZ() + 0.5);
    }

    @Override
    public void stop() {
        targetCooker = null;
        companion.getNavigation().stop();
        actionCooldown = 100; // 5 second cooldown between cooking attempts
    }

    private void interactWithCooker() {
        Level level = companion.level();
        if (level.isClientSide || targetCooker == null) return;

        BlockEntity be = level.getBlockEntity(targetCooker);

        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            interactWithFurnace(furnace);
        } else if (be instanceof CampfireBlockEntity campfire) {
            interactWithCampfire(campfire);
        }

        // Done interacting this cycle
        targetCooker = null;
    }

    private void interactWithFurnace(AbstractFurnaceBlockEntity furnace) {
        SimpleContainer inv = companion.getCompanionInventory();

        // First: collect any cooked output
        ItemStack output = furnace.getItem(2);
        if (!output.isEmpty()) {
            ItemStack remainder = inv.addItem(output.copy());
            if (remainder.isEmpty()) {
                furnace.setItem(2, ItemStack.EMPTY);
            } else {
                furnace.setItem(2, remainder);
            }
            furnace.setChanged();
            companion.playSound(SoundEvents.ITEM_PICKUP, 0.5F, 1.0F);
            companion.getChat().say(com.apocscode.mcai.entity.CompanionChat.Category.COOKING,
                    "Collected cooked food from the furnace.");
        }

        // Then: load raw food into input slot if empty
        ItemStack inputSlot = furnace.getItem(0);
        if (inputSlot.isEmpty() || inputSlot.getCount() < inputSlot.getMaxStackSize()) {
            int rawSlot = findRawFoodSlot();
            if (rawSlot >= 0) {
                ItemStack rawFood = inv.getItem(rawSlot);
                if (inputSlot.isEmpty()) {
                    furnace.setItem(0, rawFood.copy());
                    inv.setItem(rawSlot, ItemStack.EMPTY);
                } else if (ItemStack.isSameItemSameComponents(inputSlot, rawFood)) {
                    int space = inputSlot.getMaxStackSize() - inputSlot.getCount();
                    int toMove = Math.min(space, rawFood.getCount());
                    inputSlot.grow(toMove);
                    rawFood.shrink(toMove);
                    if (rawFood.isEmpty()) inv.setItem(rawSlot, ItemStack.EMPTY);
                }
                furnace.setChanged();
            }
        }

        // Add fuel if needed
        ItemStack fuelSlot = furnace.getItem(1);
        if (fuelSlot.isEmpty()) {
            int fuelIdx = findFuelSlot();
            if (fuelIdx >= 0) {
                ItemStack fuel = inv.getItem(fuelIdx);
                furnace.setItem(1, fuel.copy());
                inv.setItem(fuelIdx, ItemStack.EMPTY);
                furnace.setChanged();
            } else {
                companion.getChat().warn(com.apocscode.mcai.entity.CompanionChat.Category.NO_FUEL,
                        "The furnace needs fuel but I don't have any. Give me coal or wood!");
            }
        }
    }

    private void interactWithCampfire(CampfireBlockEntity campfire) {
        SimpleContainer inv = companion.getCompanionInventory();
        int rawSlot = findRawFoodSlot();
        if (rawSlot < 0) return;

        ItemStack rawFood = inv.getItem(rawSlot);

        // Try to place the food on the campfire (campfire has 4 cooking slots)
        // Use the campfire's placeFood method
        if (campfire.placeFood(null, rawFood.copy().split(1), 600)) {
            rawFood.shrink(1);
            if (rawFood.isEmpty()) inv.setItem(rawSlot, ItemStack.EMPTY);
            companion.level().playSound(null, campfire.getBlockPos(),
                    SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    // ================================================================
    // Helper methods
    // ================================================================

    private BlockPos findNearbyCooker() {
        BlockPos center = companion.blockPosition();
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int x = -SCAN_RANGE; x <= SCAN_RANGE; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -SCAN_RANGE; z <= SCAN_RANGE; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    Block block = companion.level().getBlockState(pos).getBlock();
                    if (block instanceof FurnaceBlock || block instanceof SmokerBlock
                            || block instanceof BlastFurnaceBlock || block instanceof CampfireBlock) {
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

    private int findRawFoodSlot() {
        SimpleContainer inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isRawFood(stack)) return i;
        }
        return -1;
    }

    private int findFuelSlot() {
        SimpleContainer inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isFuel(stack)) return i;
        }
        return -1;
    }

    public static boolean isRawFood(ItemStack stack) {
        var item = stack.getItem();
        return item == Items.BEEF
                || item == Items.PORKCHOP
                || item == Items.CHICKEN
                || item == Items.MUTTON
                || item == Items.RABBIT
                || item == Items.COD
                || item == Items.SALMON
                || item == Items.POTATO
                || item == Items.KELP;
    }

    private boolean isFuel(ItemStack stack) {
        var item = stack.getItem();
        return item == Items.COAL
                || item == Items.CHARCOAL
                || item == Items.STICK
                || item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS
                || item == Items.BIRCH_PLANKS || item == Items.JUNGLE_PLANKS
                || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS
                || item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS
                || item == Items.BAMBOO_PLANKS
                || item == Items.OAK_LOG || item == Items.SPRUCE_LOG
                || item == Items.BIRCH_LOG || item == Items.JUNGLE_LOG
                || item == Items.LAVA_BUCKET
                || item == Items.BLAZE_ROD;
    }
}
