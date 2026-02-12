package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Goal that ticks the companion's TaskManager each server tick.
 *
 * Active whenever the companion has queued tasks and is in AUTO mode.
 * Uses MOVE + LOOK flags so tasks can control pathfinding.
 */
public class CompanionTaskGoal extends Goal {
    private final CompanionEntity companion;

    public CompanionTaskGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (companion.getBehaviorMode() != CompanionEntity.BehaviorMode.AUTO) return false;
        if (companion.level().isClientSide) return false;
        return companion.getTaskManager().hasTasks();
    }

    @Override
    public boolean canContinueToUse() {
        if (companion.getBehaviorMode() != CompanionEntity.BehaviorMode.AUTO) return false;
        return companion.getTaskManager().hasTasks();
    }

    @Override
    public void tick() {
        companion.getTaskManager().tick();
    }

    @Override
    public void stop() {
        // Don't cancel tasks — just pause them while not in AUTO mode
        companion.getNavigation().stop();
    }
}
