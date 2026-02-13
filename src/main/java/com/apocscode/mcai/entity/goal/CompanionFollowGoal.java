package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Follow the owner by pathfinding. Never auto-teleports.
 * Teleporting is handled exclusively by the whistle (G key) via WhistleCompanionPacket.
 */
public class CompanionFollowGoal extends Goal {
    private final CompanionEntity companion;
    private final double speedModifier;
    private final float startDistance;
    private Player owner;
    private int ticksUntilPathRecalc;

    public CompanionFollowGoal(CompanionEntity companion, double speed, float startDist) {
        this.companion = companion;
        this.speedModifier = speed;
        this.startDistance = startDist;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Don't follow while owner is interacting with companion UI
        if (companion.isOwnerInteracting()) return false;
        // Only follow in FOLLOW mode
        if (companion.getBehaviorMode() != CompanionEntity.BehaviorMode.FOLLOW) return false;
        owner = companion.getOwner();
        if (owner == null) return false;
        if (owner.isSpectator()) return false;
        double dist = companion.distanceToSqr(owner);
        return dist > (startDistance * startDistance);
    }

    @Override
    public boolean canContinueToUse() {
        if (companion.isOwnerInteracting()) return false;
        if (owner == null || !owner.isAlive()) return false;
        double dist = companion.distanceToSqr(owner);
        return dist > 4.0D; // Stop when within 2 blocks
    }

    @Override
    public void start() {
        ticksUntilPathRecalc = 0;
        companion.getNavigation().moveTo(owner, speedModifier);
    }

    @Override
    public void stop() {
        owner = null;
        companion.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (owner == null) return;

        companion.getLookControl().setLookAt(owner, 10.0F, (float) companion.getMaxHeadXRot());

        double distSq = companion.distanceToSqr(owner);

        // Recalculate path periodically (5 ticks = smoother following)
        if (--ticksUntilPathRecalc <= 0) {
            ticksUntilPathRecalc = 5;
            if (!companion.isPassenger()) {
                // Speed boost when owner is moderately far
                double speed = distSq > 100.0D ? speedModifier * 1.3 : speedModifier;
                companion.getNavigation().moveTo(owner, speed);
            }
        }

        // Sprint when far from owner
        companion.setSprinting(distSq > 64.0D);
    }
}
