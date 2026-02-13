package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionChat;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.EnumSet;

/**
 * Survival goal — the companion fetches food from nearby containers when hungry.
 *
 * Priority order:
 *   1. Tagged STORAGE containers (always checked)
 *   2. Any container within the home area
 *   3. Any container within 16 blocks
 *
 * Triggers when health < 80% and companion has no food.
 * Pathfinds to the container, takes food, then lets CompanionEatFoodGoal handle eating.
 */
public class CompanionFetchFoodGoal extends Goal {
    private final CompanionEntity companion;
    private BlockPos targetContainer;
    private int pathRetryTimer;
    private int actionCooldown;

    private static final int SCAN_RANGE = 16;
    private static final double INTERACT_REACH = 2.5;

    public CompanionFetchFoodGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (companion.getBehaviorMode() == CompanionEntity.BehaviorMode.STAY) return false;
        if (companion.getOwner() == null) return false;
        if (companion.level().isClientSide) return false;
        if (actionCooldown > 0) {
            actionCooldown--;
            return false;
        }

        // Only fetch food when hurt and doesn't have any
        if (companion.getHealth() >= companion.getMaxHealth() * 0.8f) return false;
        if (companion.hasFood()) return false;

        targetContainer = findContainerWithFood();
        return targetContainer != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (companion.hasFood()) return false; // Got food, done
        return targetContainer != null;
    }

    @Override
    public void start() {
        if (targetContainer != null) {
            companion.getChat().say(CompanionChat.Category.HUNGRY,
                    "I'm hungry — heading to a chest to grab some food.");
            companion.getNavigation().moveTo(
                    targetContainer.getX() + 0.5, targetContainer.getY(), targetContainer.getZ() + 0.5, 1.0);
            pathRetryTimer = 0;
        }
    }

    @Override
    public void tick() {
        if (targetContainer == null) return;

        double dist = companion.distanceToSqr(
                targetContainer.getX() + 0.5, targetContainer.getY() + 0.5, targetContainer.getZ() + 0.5);

        if (dist < INTERACT_REACH * INTERACT_REACH) {
            takeFoodFromContainer();
            return;
        }

        pathRetryTimer++;
        if (pathRetryTimer % 20 == 0) {
            companion.getNavigation().moveTo(
                    targetContainer.getX() + 0.5, targetContainer.getY(), targetContainer.getZ() + 0.5, 1.0);
        }
        // Give up after 5 seconds of pathing
        if (pathRetryTimer > 100) {
            targetContainer = null;
        }

        companion.getLookControl().setLookAt(
                targetContainer.getX() + 0.5, targetContainer.getY() + 0.5, targetContainer.getZ() + 0.5);
    }

    @Override
    public void stop() {
        targetContainer = null;
        companion.getNavigation().stop();
        actionCooldown = 200; // 10 second cooldown between fetch attempts
    }

    /**
     * Take food items from the target container into companion inventory.
     */
    private void takeFoodFromContainer() {
        Level level = companion.level();
        BlockEntity be = level.getBlockEntity(targetContainer);
        if (!(be instanceof Container container)) {
            targetContainer = null;
            return;
        }

        var inv = companion.getCompanionInventory();
        int taken = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && isFood(stack)) {
                // Take up to a stack
                int toTake = Math.min(stack.getCount(), 16);
                ItemStack toInsert = stack.copyWithCount(toTake);
                ItemStack remainder = inv.addItem(toInsert);
                int actuallyTaken = toTake - remainder.getCount();
                if (actuallyTaken > 0) {
                    stack.shrink(actuallyTaken);
                    if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                    container.setChanged();
                    taken += actuallyTaken;
                    break; // One food type is enough
                }
            }
        }

        if (taken > 0) {
            companion.getChat().say(CompanionChat.Category.EATING,
                    "Grabbed food from a chest. Time to eat!");
        }
        targetContainer = null; // Done with this container
    }

    /**
     * Find the nearest container that has food.
     * Checks tagged STORAGE first, then home area, then nearby containers.
     */
    private BlockPos findContainerWithFood() {
        Level level = companion.level();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        // 1. Tagged STORAGE containers
        for (TaggedBlock tb : companion.getTaggedBlocks(TaggedBlock.Role.STORAGE)) {
            BlockPos pos = tb.pos();
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container && hasAnyFood(container)) {
                double d = companion.blockPosition().distSqr(pos);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos;
                }
            }
        }
        if (best != null) return best;

        // 2. Home area containers
        if (companion.hasHomeArea()) {
            BlockPos c1 = companion.getHomeCorner1();
            BlockPos c2 = companion.getHomeCorner2();
            if (c1 != null && c2 != null) {
                int minX = Math.min(c1.getX(), c2.getX());
                int minY = Math.min(c1.getY(), c2.getY());
                int minZ = Math.min(c1.getZ(), c2.getZ());
                int maxX = Math.max(c1.getX(), c2.getX());
                int maxY = Math.max(c1.getY(), c2.getY());
                int maxZ = Math.max(c1.getZ(), c2.getZ());
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            BlockEntity be = level.getBlockEntity(pos);
                            if (be instanceof Container container && hasAnyFood(container)) {
                                double d = companion.blockPosition().distSqr(pos);
                                if (d < bestDist) {
                                    bestDist = d;
                                    best = pos;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (best != null) return best;

        // 3. Nearby containers within scan range
        BlockPos center = companion.blockPosition();
        for (int x = -SCAN_RANGE; x <= SCAN_RANGE; x++) {
            for (int y = -SCAN_RANGE / 2; y <= SCAN_RANGE / 2; y++) {
                for (int z = -SCAN_RANGE; z <= SCAN_RANGE; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof Container container && hasAnyFood(container)) {
                        double d = center.distSqr(pos);
                        if (d < bestDist) {
                            bestDist = d;
                            best = pos;
                        }
                    }
                }
            }
        }

        return best;
    }

    private boolean hasAnyFood(Container container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && isFood(stack)) return true;
        }
        return false;
    }

    private boolean isFood(ItemStack stack) {
        return stack.get(net.minecraft.core.component.DataComponents.FOOD) != null;
    }
}
