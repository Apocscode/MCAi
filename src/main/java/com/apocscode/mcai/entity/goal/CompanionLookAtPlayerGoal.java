package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Look at nearby players, with priority on the owner.
 */
public class CompanionLookAtPlayerGoal extends Goal {
    private final CompanionEntity companion;
    private final float lookDistance;
    private Player target;
    private int lookTime;

    public CompanionLookAtPlayerGoal(CompanionEntity companion, float lookDistance) {
        this.companion = companion;
        this.lookDistance = lookDistance;
        this.setFlags(EnumSet.of(Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Prefer owner
        Player owner = companion.getOwner();
        if (owner != null && companion.distanceToSqr(owner) < (lookDistance * lookDistance)) {
            target = owner;
            return true;
        }

        // Look at nearest player
        target = companion.level().getNearestPlayer(companion, lookDistance);
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive()) return false;
        if (companion.distanceToSqr(target) > (lookDistance * lookDistance)) return false;
        return lookTime > 0;
    }

    @Override
    public void start() {
        lookTime = 40 + companion.getRandom().nextInt(40);
    }

    @Override
    public void tick() {
        if (target != null) {
            companion.getLookControl().setLookAt(
                    target.getX(), target.getEyeY(), target.getZ(),
                    (float) companion.getMaxHeadYRot(), (float) companion.getMaxHeadXRot());
        }
        lookTime--;
    }

    @Override
    public void stop() {
        target = null;
    }
}
