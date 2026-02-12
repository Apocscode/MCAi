package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Survival goal — the companion eats food from its inventory when health drops.
 * Triggers when health is below 80% of max, with a cooldown between meals.
 */
public class CompanionEatFoodGoal extends Goal {
    private final CompanionEntity companion;

    public CompanionEatFoodGoal(CompanionEntity companion) {
        this.companion = companion;
        // No movement or look flags — eating doesn't block other goals
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        // Eat when health is below 80%
        return companion.getHealth() < companion.getMaxHealth() * 0.8f;
    }

    @Override
    public void start() {
        companion.tryEatFood();
    }

    @Override
    public boolean canContinueToUse() {
        // One-shot: eat once per activation
        return false;
    }
}
