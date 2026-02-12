package com.apocscode.mcai.task;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Task: Chop down trees.
 * Scans for log blocks nearby and breaks them all.
 * Re-scans after each batch in case trees drop more accessible logs.
 */
public class ChopTreesTask extends CompanionTask {

    private final int radius;
    private final int maxLogs;
    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private int logsChopped = 0;
    private int scanAttempts = 0;
    private static final int MAX_SCAN_ATTEMPTS = 5;

    /**
     * @param radius   Search radius for trees
     * @param maxLogs  Maximum logs to chop before stopping (0 = unlimited/within radius)
     */
    public ChopTreesTask(CompanionEntity companion, int radius, int maxLogs) {
        super(companion);
        this.radius = radius;
        this.maxLogs = maxLogs > 0 ? maxLogs : 999;
    }

    @Override
    public String getTaskName() {
        return "Chop trees (r=" + radius + ")";
    }

    @Override
    protected void start() {
        scanForLogs();
        if (targets.isEmpty()) {
            say("No trees found nearby.");
            complete();
            return;
        }
        say("Found " + targets.size() + " logs to chop!");
    }

    @Override
    protected void tick() {
        if (logsChopped >= maxLogs) {
            say("Chopped " + logsChopped + " logs, that's enough!");
            complete();
            return;
        }

        if (targets.isEmpty()) {
            // Try rescanning — tree fall may expose more logs
            scanAttempts++;
            if (scanAttempts > MAX_SCAN_ATTEMPTS) {
                complete();
                return;
            }
            scanForLogs();
            if (targets.isEmpty()) {
                complete();
                return;
            }
        }

        if (currentTarget == null) {
            currentTarget = targets.peek();
            stuckTimer = 0;
        }

        // Skip if already gone
        if (companion.level().getBlockState(currentTarget).isAir()
                || !companion.level().getBlockState(currentTarget).is(net.minecraft.tags.BlockTags.LOGS)) {
            targets.poll();
            currentTarget = null;
            return;
        }

        if (isInReach(currentTarget, 3.5)) {
            BlockHelper.breakBlock(companion, currentTarget);
            targets.poll();
            currentTarget = null;
            stuckTimer = 0;
            logsChopped++;
        } else {
            navigateTo(currentTarget);
            stuckTimer++;
            if (stuckTimer > 120) {
                targets.poll();
                currentTarget = null;
                stuckTimer = 0;
            }
        }
    }

    @Override
    protected void cleanup() {
        targets.clear();
    }

    private void scanForLogs() {
        targets.clear();
        List<BlockPos> found = BlockHelper.scanForLogs(companion, radius, maxLogs - logsChopped);
        targets.addAll(found);
    }
}
