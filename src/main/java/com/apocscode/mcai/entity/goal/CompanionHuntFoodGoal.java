package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionChat;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.*;

import java.util.function.Predicate;

/**
 * Target selector goal — the companion hunts food animals when hungry.
 */
public class CompanionHuntFoodGoal extends NearestAttackableTargetGoal<Animal> {
    private final CompanionEntity companion;
    private boolean announced = false;

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
        if (companion.getOwner() == null) return false;
        if (companion.getHealth() >= companion.getMaxHealth() * 0.8f) return false;
        if (companion.hasFood()) return false;
        return super.canUse();
    }

    @Override
    public void start() {
        super.start();
        if (!announced) {
            LivingEntity target = companion.getTarget();
            String name = target != null ? target.getName().getString() : "food";
            companion.getChat().say(CompanionChat.Category.HUNTING,
                    "I'm hungry and have no food. Going to hunt a " + name + ".");
            announced = true;
        }
    }

    @Override
    public void stop() {
        announced = false;
        super.stop();
    }

    @Override
    public boolean canContinueToUse() {
        if (companion.hasFood()) return false;
        return super.canContinueToUse();
    }
}
