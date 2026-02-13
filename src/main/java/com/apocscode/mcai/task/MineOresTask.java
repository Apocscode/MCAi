package com.apocscode.mcai.task;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Task: Mine ores nearby.
 * Scans for ore blocks and mines them.
 */
public class MineOresTask extends CompanionTask {

    private final int radius;
    private final int maxOres;
    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private int oresMined = 0;
    private int scanAttempts = 0;
    private int consecutiveSkips = 0;
    private static final int MAX_SCAN_ATTEMPTS = 3;
    private static final int STUCK_TIMEOUT_TICKS = 60; // 3 seconds per block
    private static final int MAX_CONSECUTIVE_SKIPS = 3; // give up after 3 unreachable ores

    public MineOresTask(CompanionEntity companion, int radius, int maxOres) {
        super(companion);
        this.radius = radius;
        this.maxOres = maxOres > 0 ? maxOres : 999;
    }

    @Override
    public String getTaskName() {
        return "Mine ores (r=" + radius + ")";
    }

    @Override
    public int getProgressPercent() {
        return maxOres > 0 ? (oresMined * 100) / maxOres : -1;
    }

    @Override
    protected void start() {
        scanForOres();
        if (targets.isEmpty()) {
            say("No ores found nearby.");
            complete();
            return;
        }
        say("Found " + targets.size() + " ore blocks to mine!");
    }

    @Override
    protected void tick() {
        if (oresMined >= maxOres) {
            say("Mined " + oresMined + " ores!");
            complete();
            return;
        }

        if (targets.isEmpty()) {
            scanAttempts++;
            if (scanAttempts > MAX_SCAN_ATTEMPTS) {
                complete();
                return;
            }
            scanForOres();
            if (targets.isEmpty()) {
                complete();
                return;
            }
        }

        if (currentTarget == null) {
            currentTarget = targets.peek();
            stuckTimer = 0;
        }

        if (companion.level().getBlockState(currentTarget).isAir()) {
            targets.poll();
            currentTarget = null;
            return;
        }

        if (isInReach(currentTarget, 3.5)) {
            // Safety: skip ores that would expose lava
            if (!BlockHelper.isSafeToMine(companion.level(), currentTarget)) {
                targets.poll();
                currentTarget = null;
                stuckTimer = 0;
                return; // Skip this ore
            }
            // Tool-tier check: skip ores we can't properly harvest (e.g. iron ore needs stone pickaxe+)
            BlockState targetState = companion.level().getBlockState(currentTarget);
            if (!companion.canHarvestBlock(targetState)) {
                targets.poll();
                currentTarget = null;
                stuckTimer = 0;
                consecutiveSkips++;
                if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
                    say("I don't have the right tools to mine these ores. Need a better pickaxe.");
                    complete();
                    return;
                }
                return; // Skip — wrong tool tier
            }
            companion.equipBestToolForBlock(targetState);
            BlockHelper.breakBlock(companion, currentTarget);
            targets.poll();
            currentTarget = null;
            stuckTimer = 0;
            consecutiveSkips = 0;
            oresMined++;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > STUCK_TIMEOUT_TICKS) {
                targets.poll();
                currentTarget = null;
                stuckTimer = 0;
                consecutiveSkips++;
                if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
                    say("Can't reach any more ores. Mined " + oresMined + " ores.");
                    complete();
                    return;
                }
            }
        }
    }

    @Override
    protected void cleanup() {
        targets.clear();
    }

    private void scanForOres() {
        targets.clear();
        List<BlockPos> found = BlockHelper.scanForOres(companion, radius, maxOres - oresMined);
        targets.addAll(found);
    }
}
