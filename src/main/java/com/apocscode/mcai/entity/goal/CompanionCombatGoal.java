package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionChat;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * Combat goal that equips the companion's best weapon before engaging.
 * Extends vanilla MeleeAttackGoal with weapon selection and proactive chat.
 */
public class CompanionCombatGoal extends MeleeAttackGoal {
    private final CompanionEntity companion;
    private LivingEntity lastTarget;

    public CompanionCombatGoal(CompanionEntity companion, double speedModifier,
                                boolean followingTargetEvenIfNotSeen) {
        super(companion, speedModifier, followingTargetEvenIfNotSeen);
        this.companion = companion;
    }

    @Override
    public boolean canUse() {
        // Only fight when companion has an owner (is bonded)
        if (companion.getOwner() == null) return false;
        return super.canUse();
    }

    @Override
    public void start() {
        // Equip the best weapon from inventory before engaging
        companion.equipBestWeapon();

        LivingEntity target = companion.getTarget();
        if (target != null) {
            lastTarget = target;
            String mobName = target.getName().getString();
            if (companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND).isEmpty()) {
                companion.getChat().warn(CompanionChat.Category.NO_WEAPON,
                        "Engaging " + mobName + " but I have no weapon! Give me a sword!");
            } else {
                companion.getChat().say(CompanionChat.Category.COMBAT,
                        "Engaging hostile " + mobName + "!");
            }
        }

        super.start();
    }

    @Override
    public void stop() {
        // Check if we killed the target
        if (lastTarget != null && !lastTarget.isAlive()) {
            companion.getChat().say(CompanionChat.Category.COMBAT_VICTORY,
                    "Defeated the " + lastTarget.getName().getString() + "!");
        }
        lastTarget = null;
        super.stop();
    }
}
