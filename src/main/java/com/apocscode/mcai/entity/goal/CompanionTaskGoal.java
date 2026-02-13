package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Goal that ticks the companion's TaskManager each server tick.
 *
 * Active whenever the companion has queued tasks (any behavior mode except STAY).
 * Uses MOVE + LOOK flags so tasks can control pathfinding without interference
 * from follow/wander goals.
 *
 * Priority should be set high (2) so only combat and swimming can preempt tasks
 * that the player explicitly requested via AI chat.
 */
public class CompanionTaskGoal extends Goal {
    private final CompanionEntity companion;

    public CompanionTaskGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Tasks run in any mode except STAY — the player explicitly asked the AI to do something
        if (companion.getBehaviorMode() == CompanionEntity.BehaviorMode.STAY) return false;
        if (companion.level().isClientSide) return false;
        return companion.getTaskManager().hasTasks();
    }

    @Override
    public boolean canContinueToUse() {
        if (companion.getBehaviorMode() == CompanionEntity.BehaviorMode.STAY) return false;
        return companion.getTaskManager().hasTasks();
    }

    @Override
    public void tick() {
        companion.getTaskManager().tick();
    }

    @Override
    public void stop() {
        // Don't cancel tasks — just pause them while mode changes
        companion.getNavigation().stop();
    }
}
