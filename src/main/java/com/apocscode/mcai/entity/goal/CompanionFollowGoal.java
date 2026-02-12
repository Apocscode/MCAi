package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionChat;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/**
 * Follow the owner. Teleports if too far away (like a tamed wolf).
 */
public class CompanionFollowGoal extends Goal {
    private final CompanionEntity companion;
    private final double speedModifier;
    private final float startDistance;
    private final float teleportDistance;
    private Player owner;
    private int ticksUntilPathRecalc;

    public CompanionFollowGoal(CompanionEntity companion, double speed, float startDist, float teleportDist) {
        this.companion = companion;
        this.speedModifier = speed;
        this.startDistance = startDist;
        this.teleportDistance = teleportDist;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
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

        // Teleport if too far
        if (distSq > (teleportDistance * teleportDistance)) {
            teleportToOwner();
            return;
        }

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

    private void teleportToOwner() {
        if (owner == null) return;

        companion.getChat().say(CompanionChat.Category.TELEPORT,
                "You're too far away! Teleporting to you.");

        // Try positions around the owner
        for (int i = 0; i < 10; i++) {
            double dx = companion.getRandom().nextGaussian() * 2;
            double dz = companion.getRandom().nextGaussian() * 2;
            double x = owner.getX() + dx;
            double y = owner.getY();
            double z = owner.getZ() + dz;

            if (companion.level().noCollision(companion, companion.getBoundingBox().move(
                    x - companion.getX(), y - companion.getY(), z - companion.getZ()))) {
                companion.moveTo(x, y, z, companion.getYRot(), companion.getXRot());
                companion.getNavigation().stop();
                return;
            }
        }

        // Fallback: teleport directly to owner
        companion.moveTo(owner.getX(), owner.getY(), owner.getZ(),
                companion.getYRot(), companion.getXRot());
        companion.getNavigation().stop();
    }
}
