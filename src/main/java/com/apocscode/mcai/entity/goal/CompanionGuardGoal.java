package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * Goal: Patrol around a guard point, engaging hostile mobs within the patrol radius.
 * Active only when companion is in GUARD mode.
 */
public class CompanionGuardGoal extends Goal {

    private final CompanionEntity companion;
    private BlockPos guardCenter;
    private int patrolCooldown = 0;
    private BlockPos patrolTarget;
    private int scanCooldown = 0;
    private static final int PATROL_RADIUS = 16;
    private static final double PATROL_SPEED = 0.8D;

    public CompanionGuardGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return companion.getBehaviorMode() == CompanionEntity.BehaviorMode.GUARD;
    }

    @Override
    public boolean canContinueToUse() {
        return companion.getBehaviorMode() == CompanionEntity.BehaviorMode.GUARD;
    }

    @Override
    public void start() {
        guardCenter = companion.getGuardPos() != null ? companion.getGuardPos() : companion.blockPosition();
        companion.getChat().say(com.apocscode.mcai.entity.CompanionChat.Category.TASK,
                "Guarding this area. I'll patrol and engage any hostiles.");
    }

    @Override
    public void tick() {
        if (guardCenter == null) guardCenter = companion.blockPosition();

        // Scan for hostile mobs every second
        scanCooldown--;
        if (scanCooldown <= 0) {
            scanCooldown = 20;
            scanForHostiles();
        }

        // If no target, patrol around guard center
        if (companion.getTarget() == null) {
            patrolCooldown--;
            if (patrolCooldown <= 0) {
                patrolCooldown = 60 + companion.getRandom().nextInt(60); // 3-6 sec
                pickPatrolPoint();
            }

            if (patrolTarget != null) {
                double dist = companion.distanceToSqr(
                        patrolTarget.getX() + 0.5, patrolTarget.getY() + 0.5, patrolTarget.getZ() + 0.5);
                if (dist < 4.0) {
                    patrolTarget = null;
                } else {
                    companion.getNavigation().moveTo(
                            patrolTarget.getX() + 0.5, patrolTarget.getY(), patrolTarget.getZ() + 0.5,
                            PATROL_SPEED);
                }
            }
        }

        // Don't wander too far from guard center
        double distFromCenter = companion.distanceToSqr(
                guardCenter.getX() + 0.5, guardCenter.getY() + 0.5, guardCenter.getZ() + 0.5);
        if (distFromCenter > (PATROL_RADIUS + 8) * (PATROL_RADIUS + 8)) {
            companion.getNavigation().moveTo(
                    guardCenter.getX() + 0.5, guardCenter.getY(), guardCenter.getZ() + 0.5, 1.2D);
        }
    }

    @Override
    public void stop() {
        patrolTarget = null;
        companion.getNavigation().stop();
    }

    private void scanForHostiles() {
        AABB area = new AABB(guardCenter).inflate(PATROL_RADIUS);
        List<Monster> monsters = companion.level().getEntitiesOfClass(Monster.class, area,
                m -> m.isAlive() && !m.isNoAi());

        if (!monsters.isEmpty()) {
            // Target the closest one
            Monster closest = null;
            double closestDist = Double.MAX_VALUE;
            for (Monster m : monsters) {
                double d = companion.distanceToSqr(m);
                if (d < closestDist) {
                    closestDist = d;
                    closest = m;
                }
            }
            if (closest != null && companion.getTarget() == null) {
                companion.setTarget(closest);
            }
        }
    }

    private void pickPatrolPoint() {
        int dx = companion.getRandom().nextIntBetweenInclusive(-PATROL_RADIUS, PATROL_RADIUS);
        int dz = companion.getRandom().nextIntBetweenInclusive(-PATROL_RADIUS, PATROL_RADIUS);
        BlockPos candidate = guardCenter.offset(dx, 0, dz);

        // Find solid ground
        for (int y = 3; y >= -3; y--) {
            BlockPos check = candidate.offset(0, y, 0);
            if (companion.level().getBlockState(check.below()).isSolidRender(companion.level(), check.below())
                    && companion.level().getBlockState(check).isAir()) {
                patrolTarget = check;
                return;
            }
        }
        patrolTarget = guardCenter;
    }
}
