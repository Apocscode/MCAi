package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Task: Strip-mine a tunnel at a given Y-level, mining any exposed ores along the way.
 *
 * The companion digs a 1x2 tunnel (head height) in one direction for a specified length.
 * While digging, if ores are spotted in the walls/ceiling/floor, the companion mines them too.
 * Optionally targets a specific ore type — will keep tunneling until the target count is reached
 * or the tunnel length limit is hit.
 *
 * This is the correct strategy when the player asks to "mine iron" or "find diamonds"
 * and there are no visible ores at the current location — the companion must dig to expose them.
 */
public class StripMineTask extends CompanionTask {

    private final Direction direction;
    private final int tunnelLength;
    private final int targetY;
    @Nullable
    private final OreGuide.Ore targetOre;
    private final int targetOreCount;

    private int tunnelProgress = 0;  // blocks tunneled so far
    private int oresMined = 0;
    private Phase phase = Phase.DIG_DOWN;
    private int descendTarget;
    private int descendProgress = 0;
    private int stuckTimer = 0;
    private BlockPos currentMiningTarget;
    private final Deque<BlockPos> oreQueue = new ArrayDeque<>();

    private static final int STUCK_TIMEOUT = 60; // 3 seconds
    private static final int ORE_SCAN_RADIUS = 2; // Check 2 blocks around tunnel for ores

    private enum Phase {
        DIG_DOWN,       // Dig down to target Y-level if needed
        TUNNEL,         // Dig the main tunnel
        MINE_ORE,       // Side-trip to mine a spotted ore
        DONE
    }

    /**
     * @param companion    The companion entity
     * @param direction    Horizontal direction to tunnel (NORTH/SOUTH/EAST/WEST)
     * @param tunnelLength Max tunnel length in blocks
     * @param targetY      Y-level to tunnel at (-1 = current Y)
     * @param targetOre    Specific ore to look for (null = mine all ores found)
     * @param targetOreCount How many target ores to find before stopping (0 = tunnel full length)
     */
    public StripMineTask(CompanionEntity companion, Direction direction, int tunnelLength,
                         int targetY, @Nullable OreGuide.Ore targetOre, int targetOreCount) {
        super(companion);
        this.direction = direction;
        this.tunnelLength = tunnelLength;
        this.targetY = targetY;
        this.targetOre = targetOre;
        this.targetOreCount = targetOreCount > 0 ? targetOreCount : Integer.MAX_VALUE;
    }

    @Override
    public String getTaskName() {
        String oreLabel = targetOre != null ? "for " + targetOre.name : "";
        return "Strip mine " + oreLabel + " (" + tunnelLength + " blocks " + direction.getName() + ")";
    }

    @Override
    public int getProgressPercent() {
        if (targetOreCount < Integer.MAX_VALUE && targetOreCount > 0) {
            return Math.min(100, (oresMined * 100) / targetOreCount);
        }
        return tunnelLength > 0 ? (tunnelProgress * 100) / tunnelLength : -1;
    }

    @Override
    protected void start() {
        int currentY = companion.blockPosition().getY();

        // Calculate how far to descend (if we need to reach target Y)
        if (targetY >= 0 && currentY > targetY) {
            descendTarget = currentY - targetY;
            phase = Phase.DIG_DOWN;
            say("Digging stairs down " + descendTarget + " blocks to Y=" + targetY + " before tunneling " +
                    direction.getName() + "...");
        } else {
            descendTarget = 0;
            phase = Phase.TUNNEL;
            String oreLabel = targetOre != null ? " for " + targetOre.name + " ore" : "";
            say("Starting tunnel " + direction.getName() + oreLabel + " at Y=" + currentY + "...");
        }
    }

    @Override
    protected void tick() {
        switch (phase) {
            case DIG_DOWN -> tickDigDown();
            case TUNNEL -> tickTunnel();
            case MINE_ORE -> tickMineOre();
            case DONE -> complete();
        }
    }

    // ================================================================
    // Phase: Dig down to target Y-level via staircase
    // ================================================================

    private void tickDigDown() {
        if (descendProgress >= descendTarget) {
            phase = Phase.TUNNEL;
            String oreLabel = targetOre != null ? " for " + targetOre.name + " ore" : "";
            say("Reached Y=" + companion.blockPosition().getY() + ". Tunneling " +
                    direction.getName() + oreLabel + "...");
            return;
        }

        BlockPos pos = companion.blockPosition();
        Level level = companion.level();

        // Safety: world bottom
        if (pos.getY() <= level.getMinBuildHeight() + 2) {
            phase = Phase.TUNNEL;
            say("Near world bottom at Y=" + pos.getY() + ". Starting tunnel here.");
            return;
        }

        // Staircase pattern: dig 1 forward + 1 down each step
        // This creates walkable stairs the companion can climb back up
        BlockPos ahead = pos.relative(direction);       // one step forward
        BlockPos aheadBelow = ahead.below();             // the step-down position (new feet)
        BlockPos aheadHead = ahead;                      // head clearance at step-down = current feet level forward
        BlockPos aheadAbove = ahead.above();             // extra clearance above

        // Safety: check for lava at the step-down position
        if (!BlockHelper.isSafeToMine(level, aheadBelow)) {
            // Seal with cobblestone and tunnel here instead
            BlockHelper.placeBlock(companion, aheadBelow, Blocks.COBBLESTONE);
            phase = Phase.TUNNEL;
            say("Lava detected below stairs! Sealed and tunneling at Y=" + pos.getY() + " instead.");
            return;
        }

        // Dig: clear ahead (2 high for walking), then below-ahead for the step down
        // Clear above (3 blocks high from step-down) to ensure the companion fits while descending
        BlockState aboveState = level.getBlockState(aheadAbove);
        if (!aboveState.isAir()) {
            checkAndQueueOre(aheadAbove, aboveState);
            companion.equipBestToolForBlock(aboveState);
            BlockHelper.breakBlock(companion, aheadAbove);
        }

        BlockState headState = level.getBlockState(aheadHead);
        if (!headState.isAir()) {
            checkAndQueueOre(aheadHead, headState);
            companion.equipBestToolForBlock(headState);
            BlockHelper.breakBlock(companion, aheadHead);
        }

        BlockState belowState = level.getBlockState(aheadBelow);
        if (!belowState.isAir()) {
            checkAndQueueOre(aheadBelow, belowState);
            companion.equipBestToolForBlock(belowState);
            BlockHelper.breakBlock(companion, aheadBelow);
        }

        // Ensure solid floor under the new step (2 below current feet level)
        BlockPos floorCheck = aheadBelow.below();
        BlockState floorState = level.getBlockState(floorCheck);
        if (floorState.isAir() || floorState.getFluidState().is(FluidTags.LAVA)) {
            BlockHelper.placeBlock(companion, floorCheck, Blocks.COBBLESTONE);
        }

        // Navigate to the new lower position
        navigateTo(aheadBelow);
        descendProgress++;
    }

    // ================================================================
    // Phase: Dig the main tunnel
    // ================================================================

    private void tickTunnel() {
        // Check if we've found enough ores
        if (oresMined >= targetOreCount) {
            say("Found " + oresMined + " " + (targetOre != null ? targetOre.name + " ore" : "ores") +
                    "! Tunnel length: " + tunnelProgress + " blocks.");
            phase = Phase.DONE;
            return;
        }

        // Check if tunnel is long enough
        if (tunnelProgress >= tunnelLength) {
            say("Tunnel complete (" + tunnelProgress + " blocks). Mined " + oresMined + " ores.");
            phase = Phase.DONE;
            return;
        }

        // If we found ores in the walls, go mine them first
        if (!oreQueue.isEmpty()) {
            currentMiningTarget = oreQueue.poll();
            phase = Phase.MINE_ORE;
            return;
        }

        // Calculate next tunnel position
        BlockPos pos = companion.blockPosition();
        BlockPos nextFeet = pos.relative(direction);
        BlockPos nextHead = nextFeet.above();

        // Check if we need to navigate to the tunnel face
        double distSq = companion.distanceToSqr(nextFeet.getX() + 0.5, nextFeet.getY() + 0.5, nextFeet.getZ() + 0.5);
        if (distSq > 4.0) {
            // Too far from tunnel face — pathfind forward
            navigateTo(pos);
            stuckTimer++;
            if (stuckTimer > STUCK_TIMEOUT) {
                say("Can't progress tunnel — stuck. Mined " + oresMined + " ores in " + tunnelProgress + " blocks.");
                phase = Phase.DONE;
            }
            return;
        }
        stuckTimer = 0;

        // Safety: check for lava/void ahead
        if (!BlockHelper.isSafeToMine(companion.level(), nextFeet) ||
                !BlockHelper.isSafeToMine(companion.level(), nextHead)) {
            say("Lava detected ahead! Stopping tunnel. Mined " + oresMined + " ores in " + tunnelProgress + " blocks.");
            phase = Phase.DONE;
            return;
        }

        // Dig the two blocks ahead (feet level + head level)
        BlockState feetState = companion.level().getBlockState(nextFeet);
        BlockState headState = companion.level().getBlockState(nextHead);

        if (!feetState.isAir()) {
            checkAndQueueOre(nextFeet, feetState);
            companion.equipBestToolForBlock(feetState);
            BlockHelper.breakBlock(companion, nextFeet);
        }
        if (!headState.isAir()) {
            checkAndQueueOre(nextHead, headState);
            companion.equipBestToolForBlock(headState);
            BlockHelper.breakBlock(companion, nextHead);
        }

        // Scan walls, ceiling, floor for ores
        scanTunnelWalls(nextFeet);

        tunnelProgress++;

        // Move forward into the newly dug space
        navigateTo(nextFeet);
    }

    // ================================================================
    // Phase: Mine a spotted ore (side tunnel)
    // ================================================================

    private void tickMineOre() {
        if (currentMiningTarget == null) {
            phase = Phase.TUNNEL;
            return;
        }

        BlockState state = companion.level().getBlockState(currentMiningTarget);
        if (state.isAir() || !OreGuide.isOre(state)) {
            // Already mined or not an ore anymore
            currentMiningTarget = null;
            phase = Phase.TUNNEL;
            return;
        }

        if (isInReach(currentMiningTarget, 4.5)) {
            if (!BlockHelper.isSafeToMine(companion.level(), currentMiningTarget)) {
                currentMiningTarget = null;
                phase = Phase.TUNNEL;
                return;
            }
            if (!companion.canHarvestBlock(state)) {
                currentMiningTarget = null;
                phase = Phase.TUNNEL;
                return;
            }
            companion.equipBestToolForBlock(state);
            BlockHelper.breakBlock(companion, currentMiningTarget);
            oresMined++;
            OreGuide.Ore minedOre = OreGuide.identifyOre(state);
            MCAi.LOGGER.debug("Strip-mine: mined {} at {}", minedOre != null ? minedOre.name : "ore", currentMiningTarget);
            currentMiningTarget = null;
            phase = Phase.TUNNEL;
        } else {
            navigateTo(currentMiningTarget);
            stuckTimer++;
            if (stuckTimer > STUCK_TIMEOUT) {
                // Can't reach this ore — skip it
                currentMiningTarget = null;
                stuckTimer = 0;
                phase = Phase.TUNNEL;
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * If the given block is an ore we care about, add it to the ore queue
     * and count it as mined (since breakBlock already collects drops).
     */
    private void checkAndQueueOre(BlockPos pos, BlockState state) {
        if (targetOre != null) {
            if (targetOre.matches(state)) {
                oresMined++; // Counted here since it's being broken in the tunnel
            }
        } else if (OreGuide.isOre(state)) {
            oresMined++;
        }
    }

    /**
     * Scan the walls, ceiling, and floor around a tunnel position for ores.
     * Ores found within ORE_SCAN_RADIUS are added to the queue for mining.
     */
    private void scanTunnelWalls(BlockPos tunnelPos) {
        for (int dx = -ORE_SCAN_RADIUS; dx <= ORE_SCAN_RADIUS; dx++) {
            for (int dy = -1; dy <= 2; dy++) { // floor to just above head
                for (int dz = -ORE_SCAN_RADIUS; dz <= ORE_SCAN_RADIUS; dz++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue; // Skip tunnel itself
                    BlockPos checkPos = tunnelPos.offset(dx, dy, dz);
                    BlockState state = companion.level().getBlockState(checkPos);

                    boolean isTarget;
                    if (targetOre != null) {
                        isTarget = targetOre.matches(state);
                    } else {
                        isTarget = OreGuide.isOre(state);
                    }

                    if (isTarget && !oreQueue.contains(checkPos)) {
                        oreQueue.add(checkPos);
                    }
                }
            }
        }
    }

    @Override
    protected void cleanup() {
        oreQueue.clear();
    }
}
