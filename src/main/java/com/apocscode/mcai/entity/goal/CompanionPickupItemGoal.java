package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * Active item pickup goal — the companion navigates toward nearby dropped items
 * on the ground and picks them up, rather than only picking up items it walks over.
 * Scans within SEARCH_RANGE blocks and paths to the nearest valid item.
 */
public class CompanionPickupItemGoal extends Goal {
    private final CompanionEntity companion;
    private ItemEntity targetItem;
    private int cooldown;

    private static final double SEARCH_RANGE = 16.0;
    private static final double PICKUP_RANGE = 1.5;
    private static final int SEARCH_COOLDOWN = 10; // ticks between scans (faster during tasks)
    private static final int MAX_PATH_TICKS = 200; // give up after 10 seconds
    private int pathTicks;

    public CompanionPickupItemGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // STAY mode: don't seek items
        if (companion.getBehaviorMode() == CompanionEntity.BehaviorMode.STAY) return false;
        if (--cooldown > 0) return false;
        cooldown = SEARCH_COOLDOWN;

        // Don't pick up items if companion inventory is full
        if (!companion.hasInventorySpace()) return false;

        // Find nearest valid item on the ground
        targetItem = findNearestItem();
        return targetItem != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (targetItem == null || !targetItem.isAlive() || targetItem.isRemoved()) return false;
        if (pathTicks > MAX_PATH_TICKS) return false;
        if (!companion.hasInventorySpace()) return false;
        return companion.distanceToSqr(targetItem) > PICKUP_RANGE * PICKUP_RANGE;
    }

    @Override
    public void start() {
        pathTicks = 0;
        navigateToItem();
    }

    @Override
    public void tick() {
        pathTicks++;
        if (targetItem == null || !targetItem.isAlive()) return;

        // Re-navigate every 10 ticks in case item moved
        if (pathTicks % 10 == 0) {
            navigateToItem();
        }

        // If close enough, trigger pickup
        if (companion.distanceToSqr(targetItem) <= PICKUP_RANGE * PICKUP_RANGE) {
            companion.pickUpNearbyItem(targetItem);
        }
    }

    @Override
    public void stop() {
        targetItem = null;
        companion.getNavigation().stop();
    }

    private void navigateToItem() {
        if (targetItem != null && targetItem.isAlive()) {
            companion.getNavigation().moveTo(
                    targetItem.getX(), targetItem.getY(), targetItem.getZ(), 1.0D);
        }
    }

    private ItemEntity findNearestItem() {
        AABB searchBox = companion.getBoundingBox().inflate(SEARCH_RANGE);
        List<ItemEntity> items = companion.level().getEntitiesOfClass(ItemEntity.class, searchBox,
                item -> item.isAlive() && !item.isRemoved() && !item.hasPickUpDelay());

        return items.stream()
                .min(Comparator.comparingDouble(companion::distanceToSqr))
                .orElse(null);
    }
}
