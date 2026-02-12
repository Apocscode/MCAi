package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionChat;
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
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        return companion.getHealth() < companion.getMaxHealth() * 0.8f;
    }

    @Override
    public void start() {
        boolean ate = companion.tryEatFood();
        if (ate) {
            companion.getChat().say(CompanionChat.Category.EATING,
                    "Eating some food to heal up. (" + String.format("%.0f", companion.getHealth()) +
                    "/" + String.format("%.0f", companion.getMaxHealth()) + " HP)");
        } else if (!companion.hasFood()) {
            companion.getChat().warn(CompanionChat.Category.NO_FOOD,
                    "I'm hurt but have no food! Can you give me something to eat?");
        }
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }
}
