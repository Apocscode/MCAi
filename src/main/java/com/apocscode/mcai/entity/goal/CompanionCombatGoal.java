package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;

/**
 * Combat goal that equips the companion's best weapon before engaging.
 * Extends vanilla MeleeAttackGoal with weapon selection.
 */
public class CompanionCombatGoal extends MeleeAttackGoal {
    private final CompanionEntity companion;

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
        super.start();
    }

    @Override
    public void stop() {
        super.stop();
        // Optionally stow weapon after combat (leave equipped for now)
    }
}
