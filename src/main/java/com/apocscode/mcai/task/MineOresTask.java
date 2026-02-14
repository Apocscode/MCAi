package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Task: Mine ores nearby.
 * Supports targeted ore type (e.g. only iron) or all ores.
 * Uses OreGuide for ore identification and tool-tier checks.
 * Expands search radius progressively (16→32→48→64) if no ores found.
 */
public class MineOresTask extends CompanionTask {

    private static final int[] EXPAND_RADII = {32, 48, 64}; // fallback search radii
    private int radius;
    private final int maxOres;
    @Nullable
    private final OreGuide.Ore targetOre; // null = mine all ores
    private final Deque<BlockPos> targets = new ArrayDeque<>();
    private BlockPos currentTarget;
    private int stuckTimer = 0;
    private int oresMined = 0;
    private int scanAttempts = 0;
    private int consecutiveSkips = 0;
    private static final int MAX_SCAN_ATTEMPTS = 3;
    private static final int STUCK_TIMEOUT_TICKS = 60; // 3 seconds per block
    private static final int MAX_CONSECUTIVE_SKIPS = 3;

    /** Constructor for mining all ore types. */
    public MineOresTask(CompanionEntity companion, int radius, int maxOres) {
        this(companion, radius, maxOres, null);
    }

    /** Constructor with optional targeted ore type. */
    public MineOresTask(CompanionEntity companion, int radius, int maxOres, @Nullable OreGuide.Ore targetOre) {
        super(companion);
        this.radius = radius;
        this.maxOres = maxOres > 0 ? maxOres : 999;
        this.targetOre = targetOre;
    }

    @Override
    public String getTaskName() {
        String oreLabel = targetOre != null ? targetOre.name + " ore" : "ores";
        return "Mine " + oreLabel + " (r=" + radius + ")";
    }

    @Override
    public int getProgressPercent() {
        return maxOres > 0 ? (oresMined * 100) / maxOres : -1;
    }

    @Override
    protected void start() {
        scanForOres();
        if (targets.isEmpty()) {
            // Expand search radius progressively before giving up
            for (int expandRadius : EXPAND_RADII) {
                if (expandRadius <= radius) continue;
                MCAi.LOGGER.info("MineOresTask: no ores at r={}, expanding to r={}", radius, expandRadius);
                radius = expandRadius;
                scanForOres();
                if (!targets.isEmpty()) break;
            }
        }
        if (targets.isEmpty()) {
            String oreLabel = targetOre != null ? targetOre.name + " ore" : "ores";
            int currentY = companion.blockPosition().getY();
            String yHint = "";
            if (targetOre != null) {
                if (currentY < targetOre.minY || currentY > targetOre.maxY) {
                    yHint = " I'm at Y=" + currentY + " but " + targetOre.name +
                            " generates between Y=" + targetOre.minY + " and Y=" + targetOre.maxY +
                            ". Best at Y=" + targetOre.bestY + ".";
                } else {
                    yHint = " I'm at Y=" + currentY + " (right range, but none visible in " + radius + " block radius).";
                }
            }
            say("No " + oreLabel + " found nearby." + yHint);
            fail("No " + oreLabel + " found within " + radius + " blocks");
            return;
        }
        String oreLabel = targetOre != null ? targetOre.name + " ore" : "ore";
        say("Found " + targets.size() + " " + oreLabel + " blocks to mine!");
    }

    @Override
    protected void tick() {
        if (oresMined >= maxOres) {
            String oreLabel = targetOre != null ? targetOre.name + " ore" : "ores";
            say("Mined " + oresMined + " " + oreLabel + "!");
            complete();
            return;
        }

        if (targets.isEmpty()) {
            scanAttempts++;
            if (scanAttempts > MAX_SCAN_ATTEMPTS) {
                String oreLabel = targetOre != null ? targetOre.name + " ore" : "ores";
                if (oresMined == 0) {
                    say("Could not find any " + oreLabel + " after " + MAX_SCAN_ATTEMPTS + " scans.");
                    fail("No " + oreLabel + " found after " + MAX_SCAN_ATTEMPTS + " scans");
                } else {
                    say("Finished mining. Got " + oresMined + " " + oreLabel + ".");
                    complete();
                }
                return;
            }
            scanForOres();
            if (targets.isEmpty()) {
                String oreLabel = targetOre != null ? targetOre.name + " ore" : "ores";
                if (oresMined == 0) {
                    say("No " + oreLabel + " found nearby.");
                    fail("No " + oreLabel + " found within " + radius + " blocks");
                } else {
                    say("No more " + oreLabel + " found. Mined " + oresMined + " total.");
                    complete();
                }
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
                return;
            }
            // Tool-tier check: skip ores the companion can't harvest
            BlockState targetState = companion.level().getBlockState(currentTarget);
            if (!companion.canHarvestBlock(targetState)) {
                targets.poll();
                currentTarget = null;
                stuckTimer = 0;
                consecutiveSkips++;
                if (consecutiveSkips >= MAX_CONSECUTIVE_SKIPS) {
                    OreGuide.Ore ore = OreGuide.identifyOre(targetState);
                    String tierHint = ore != null
                            ? " Need " + ore.tierName() + " pickaxe or better."
                            : " Need a better pickaxe.";
                    say("I don't have the right tools to mine these ores." + tierHint);
                    fail("Wrong pickaxe tier" + tierHint);
                    return;
                }
                return;
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
                    if (oresMined == 0) {
                        say("Can't reach any ores.");
                        fail("Could not reach any ore blocks");
                    } else {
                        say("Can't reach any more ores. Mined " + oresMined + ".");
                        complete();
                    }
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
        List<BlockPos> found;
        if (targetOre != null) {
            found = scanForSpecificOre(companion, targetOre, radius, maxOres - oresMined);
        } else {
            found = BlockHelper.scanForOres(companion, radius, maxOres - oresMined);
        }
        targets.addAll(found);
    }

    /**
     * Scan for a specific ore type within radius.
     */
    private static List<BlockPos> scanForSpecificOre(CompanionEntity companion, OreGuide.Ore ore,
                                                      int radius, int maxResults) {
        BlockPos center = companion.blockPosition();
        Level level = companion.level();
        List<BlockPos> results = new ArrayList<>();

        // Clamp Y to world bounds (-64 to 319 in overworld)
        int minY = Math.max(-radius, level.getMinBuildHeight() - center.getY());
        int maxY = Math.min(radius, level.getMaxBuildHeight() - 1 - center.getY());

        for (int x = -radius; x <= radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = companion.level().getBlockState(pos);
                    if (ore.matches(state)) {
                        results.add(pos);
                    }
                }
            }
        }

        // Sort by distance (mine closest first)
        results.sort((a, b) -> {
            double distA = companion.distanceToSqr(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5);
            double distB = companion.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            return Double.compare(distA, distB);
        });

        if (results.size() > maxResults) return results.subList(0, maxResults);
        return results;
    }
}
