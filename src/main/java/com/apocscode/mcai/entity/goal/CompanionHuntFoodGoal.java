package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.*;

import java.util.function.Predicate;

/**
 * Target selector goal — the companion hunts food animals when hungry.
 *
 * Activates when:
 *   - Health is below 80% of max
 *   - No cooked/ready food in inventory
 *
 * Targets: Cow, Pig, Chicken, Sheep, Rabbit (only adults)
 */
public class CompanionHuntFoodGoal extends NearestAttackableTargetGoal<Animal> {
    private final CompanionEntity companion;

    // Only target adult food animals
    private static final Predicate<LivingEntity> FOOD_ANIMALS = entity -> {
        if (entity instanceof Animal animal && !animal.isBaby()) {
            return entity instanceof Cow
                    || entity instanceof Pig
                    || entity instanceof Chicken
                    || entity instanceof Sheep
                    || entity instanceof Rabbit;
        }
        return false;
    };

    public CompanionHuntFoodGoal(CompanionEntity companion) {
        super(companion, Animal.class, 10, true, false, FOOD_ANIMALS);
        this.companion = companion;
    }

    @Override
    public boolean canUse() {
        // Only hunt when hungry and has no food
        if (companion.getOwner() == null) return false;
        if (companion.getHealth() >= companion.getMaxHealth() * 0.8f) return false;
        if (companion.hasFood()) return false;
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        // Stop hunting once we have food
        if (companion.hasFood()) return false;
        return super.canContinueToUse();
    }
}
