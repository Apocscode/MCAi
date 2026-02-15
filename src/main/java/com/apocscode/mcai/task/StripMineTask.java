package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;

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
    private BlockPos tunnelFacePos = null; // Where the companion should be standing to mine next
    private BlockPos descendTargetPos = null; // Where the companion should walk to during stair descent

    private BlockPos walkOutTarget = null; // Where to walk to exit home area

    private static final int STUCK_TIMEOUT = 60; // 3 seconds
    private static final int ORE_SCAN_RADIUS = 2; // Check 2 blocks around tunnel for ores
    private static final int TORCH_INTERVAL = 8;  // Place a torch every N blocks
    private static final int TOOL_LOW_DURABILITY = 10; // Warn at 10 uses remaining
    private static final double INVENTORY_FULL_THRESHOLD = 0.85; // 85% full = stop and warn
    private boolean toolWarningGiven = false;
    private boolean inventoryWarningGiven = false;
    private boolean torchWarningGiven = false;
    private boolean foodWarningGiven = false;
    private int emergencyDigAttempts = 0;

    private enum Phase {
        WALK_OUT,       // Walk outside the home area before mining
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

        // Walk out of home area before mining
        if (companion.isInHomeArea(companion.blockPosition())) {
            // Walk incrementally in the mining direction to exit the home area
            // Use 8-block steps instead of blind 25-block offset to avoid
            // targeting impassable terrain (cliffs, oceans, mountains)
            walkOutTarget = companion.blockPosition().relative(direction, 8);
            phase = Phase.WALK_OUT;
            navigateTo(walkOutTarget);
            say("I'm inside the home area — walking out " + direction.getName() + " before I start mining.");
            return;
        }

        // Calculate how far to descend (if we need to reach target Y)
        if (targetY >= 0 && currentY > targetY) {
            descendTarget = currentY - targetY;
            phase = Phase.DIG_DOWN;
            say("Digging stairs down " + descendTarget + " blocks to Y=" + targetY + " before tunneling " +
                    direction.getName() + "...");
        } else {
            descendTarget = 0;
            phase = Phase.TUNNEL;
            tunnelFacePos = companion.blockPosition(); // Start mining from current position
            String oreLabel = targetOre != null ? " for " + targetOre.name + " ore" : "";
            say("Starting tunnel " + direction.getName() + oreLabel + " at Y=" + currentY + "...");
        }
    }

    @Override
    protected void tick() {
        switch (phase) {
            case WALK_OUT -> tickWalkOut();
            case DIG_DOWN -> tickDigDown();
            case TUNNEL -> tickTunnel();
            case MINE_ORE -> tickMineOre();
            case DONE -> complete();
        }
    }

    // ================================================================
    // Phase: Walk outside the home area before mining
    // ================================================================

    private void tickWalkOut() {
        if (walkOutTarget == null) {
            // Shouldn't happen, but recover
            phase = Phase.TUNNEL;
            tunnelFacePos = companion.blockPosition();
            return;
        }

        // Check if we've left the home area
        if (!companion.isInHomeArea(companion.blockPosition())) {
            say("Outside the home area now. Starting the mine!");
            stuckTimer = 0;
            // Re-run start logic from current position (outside home area)
            int currentY = companion.blockPosition().getY();
            if (targetY >= 0 && currentY > targetY) {
                descendTarget = currentY - targetY;
                phase = Phase.DIG_DOWN;
                say("Digging stairs down " + descendTarget + " blocks to Y=" + targetY + "...");
            } else {
                phase = Phase.TUNNEL;
                tunnelFacePos = companion.blockPosition();
                String oreLabel = targetOre != null ? " for " + targetOre.name + " ore" : "";
                say("Starting tunnel " + direction.getName() + oreLabel + " at Y=" + currentY + "...");
            }
            return;
        }

        // Still inside — keep walking
        // Re-target every 3 seconds (60 ticks) if still in home area — incremental approach
        if (stuckTimer > 0 && stuckTimer % 60 == 0) {
            walkOutTarget = companion.blockPosition().relative(direction, 8);
            MCAi.LOGGER.debug("WALK_OUT: re-targeting to {} (still in home area)", walkOutTarget);
        }
        navigateTo(walkOutTarget);
        stuckTimer++;
        if (stuckTimer > STUCK_TIMEOUT * 3) { // 9 seconds to walk out — generous timeout
            say("Can't get out of the home area! Try moving me outside manually.");
            fail("Stuck trying to leave home area after " + stuckTimer + " ticks");
        }
    }

    // ================================================================
    // Phase: Dig down to target Y-level via staircase
    // ================================================================

    private void tickDigDown() {
        if (descendProgress >= descendTarget) {
            phase = Phase.TUNNEL;
            tunnelFacePos = companion.blockPosition(); // Start tunnel from where we landed
            String oreLabel = targetOre != null ? " for " + targetOre.name + " ore" : "";
            say("Reached Y=" + companion.blockPosition().getY() + ". Tunneling " +
                    direction.getName() + oreLabel + "...");
            return;
        }

        // === Wait for companion to arrive at the last stair step before digging next ===
        if (descendTargetPos != null && !isInReach(descendTargetPos, 2.5)) {
            navigateTo(descendTargetPos);
            stuckTimer++;
            if (stuckTimer > STUCK_TIMEOUT) {
                // Try emergency dig-out before giving up
                if (emergencyDigAttempts < 3) {
                    boolean dug = BlockHelper.emergencyDigOut(companion);
                    if (dug) {
                        emergencyDigAttempts++;
                        stuckTimer = 0;
                        return;
                    }
                }
                // Can't reach stair step — start tunneling here instead
                phase = Phase.TUNNEL;
                tunnelFacePos = companion.blockPosition();
                say("Stuck on stairs at Y=" + companion.blockPosition().getY() + ". Tunneling here.");
            }
            return;
        }
        stuckTimer = 0;

        BlockPos pos = companion.blockPosition();
        Level level = companion.level();

        // Safety: world bottom
        if (pos.getY() <= level.getMinBuildHeight() + 2) {
            phase = Phase.TUNNEL;
            tunnelFacePos = companion.blockPosition();
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
            tunnelFacePos = companion.blockPosition();
            say("Lava detected below stairs! Sealed and tunneling at Y=" + pos.getY() + " instead.");
            return;
        }

        // Don't dig stairs into the home area
        if (companion.isInHomeArea(aheadBelow) || companion.isInHomeArea(ahead)) {
            phase = Phase.TUNNEL;
            tunnelFacePos = companion.blockPosition();
            say("Stairs reached home area boundary! Tunneling at Y=" + pos.getY() + " instead.");
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

        // Handle falling blocks above staircase opening
        handleFallingBlocks(aheadAbove.above());

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

        // Ensure safe floor under the new step (seal hazards too)
        BlockPos floorCheck = aheadBelow.below();
        BlockHelper.sealHazardousFloor(companion, floorCheck);

        // Navigate to the new lower position
        descendTargetPos = aheadBelow;
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

        // Initialize tunnel face on first tick
        if (tunnelFacePos == null) {
            tunnelFacePos = companion.blockPosition();
        }

        // === KEY FIX: Wait for companion to ARRIVE at tunnel face before mining ===
        // The companion must physically walk to each position before we dig the next block.
        // Without this, tunnelProgress increments every tick while the companion stands still.
        if (!isInReach(tunnelFacePos, 2.5)) {
            navigateTo(tunnelFacePos);
            stuckTimer++;
            if (stuckTimer > STUCK_TIMEOUT) {                // Try emergency dig-out before giving up
                if (emergencyDigAttempts < 3) {
                    boolean dug = BlockHelper.emergencyDigOut(companion);
                    if (dug) {
                        emergencyDigAttempts++;
                        MCAi.LOGGER.info("Strip-mine: emergency dig-out attempt {} at {}", emergencyDigAttempts, companion.blockPosition());
                        stuckTimer = 0;
                        return;
                    }
                }                say("Can't reach tunnel face — stuck at " + companion.blockPosition() +
                        ". Mined " + oresMined + " ores in " + tunnelProgress + " blocks.");
                phase = Phase.DONE;
            }
            return;
        }
        stuckTimer = 0;
        emergencyDigAttempts = 0; // Reset since we reached the face

        // === Health check — eat food if HP < 50% ===
        if (BlockHelper.tryEatIfLowHealth(companion, 0.5f)) {
            say("Eating some food to heal up!");
        } else if (!foodWarningGiven && companion.getHealth() / companion.getMaxHealth() < 0.3f) {
            say("I'm getting low on health and don't have any food!");
            foodWarningGiven = true;
        }

        // === Tool durability check ===
        if (!toolWarningGiven && !BlockHelper.hasUsablePickaxe(companion, 0)) {
            // Try auto-crafting a new pickaxe before giving up
            if (BlockHelper.tryAutoCraftPickaxe(companion)) {
                say("Crafted a new pickaxe! Continuing mining.");
                companion.equipBestToolForBlock(companion.level().getBlockState(tunnelFacePos.relative(direction)));
            } else {
                say("I don't have a usable pickaxe and can't craft one! Need materials to continue.");
                toolWarningGiven = true;
                phase = Phase.DONE;
                return;
            }
        }
        if (BlockHelper.isToolLowDurability(companion, TOOL_LOW_DURABILITY)) {
            // Try to swap to a better tool from inventory
            companion.equipBestToolForBlock(companion.level().getBlockState(tunnelFacePos.relative(direction)));
        }

        // === Inventory full check ===
        if (BlockHelper.isInventoryNearlyFull(companion, INVENTORY_FULL_THRESHOLD)) {
            // Try routing items to tagged storage first
            if (com.apocscode.mcai.logistics.ItemRoutingHelper.hasTaggedStorage(companion)) {
                int routed = com.apocscode.mcai.logistics.ItemRoutingHelper.routeAllCompanionItems(companion);
                if (routed > 0) {
                    say("Deposited " + routed + " items to storage.");
                }
            }
            // If still full after routing, stop
            if (BlockHelper.isInventoryNearlyFull(companion, INVENTORY_FULL_THRESHOLD)) {
                if (!inventoryWarningGiven) {
                    say("Inventory is almost full! Stopping tunnel at " + tunnelProgress + " blocks. Mined " + oresMined + " ores.");
                    inventoryWarningGiven = true;
                }
                phase = Phase.DONE;
                return;
            }
        }

        // Calculate next tunnel position (one block ahead of where we stand)
        BlockPos nextFeet = tunnelFacePos.relative(direction);
        BlockPos nextHead = nextFeet.above();

        // Safety: check for lava/water/void ahead
        if (!BlockHelper.isSafeToMine(companion.level(), nextFeet) ||
                !BlockHelper.isSafeToMine(companion.level(), nextHead)) {
            // Attempt to seal water/lava with cobblestone and continue
            boolean sealed = tryToSealFluid(nextFeet, nextHead);
            if (!sealed) {
                say("Fluid hazard ahead! Stopping tunnel. Mined " + oresMined + " ores in " + tunnelProgress + " blocks.");
                phase = Phase.DONE;
                return;
            }
            say("Sealed fluid ahead with cobblestone. Continuing...");
        }

        // Don't tunnel into the home area
        if (companion.isInHomeArea(nextFeet) || companion.isInHomeArea(nextHead)) {
            say("Reached home area boundary! Stopping tunnel. Mined " + oresMined + " ores in " + tunnelProgress + " blocks.");
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

        // Handle falling blocks (gravel/sand) above the head after breaking
        handleFallingBlocks(nextHead.above());

        // Clear hazardous blocks in tunnel space (cobwebs, fire, berry bushes, wither roses)
        BlockHelper.clearTunnelHazards(companion, nextFeet);

        // Ensure safe floor under feet (prevent falling + seal magma/fire/hazards)
        BlockPos floorPos = nextFeet.below();
        BlockHelper.sealHazardousFloor(companion, floorPos);

        // Scan walls, ceiling, floor for ores
        scanTunnelWalls(nextFeet);

        tunnelProgress++;

        // Log progress periodically
        if (tunnelProgress % 8 == 0) {
            MCAi.LOGGER.info("Strip-mine progress: {}/{} blocks, {} ores mined, companion at {}",
                    tunnelProgress, tunnelLength, oresMined, companion.blockPosition());
        }        // Place torches periodically for lighting
        if (tunnelProgress % TORCH_INTERVAL == 0) {
            tryPlaceTorch(nextFeet);
        }

        // Advance tunnel face and navigate to it
        tunnelFacePos = nextFeet;
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
            // Only count toward completion if it's the target ore (or no target specified)
            OreGuide.Ore minedOre = OreGuide.identifyOre(state);
            if (targetOre == null || (minedOre != null && minedOre == targetOre)) {
                oresMined++;
            }
            MCAi.LOGGER.debug("Strip-mine: mined {} at {} (target: {}, counted: {})",
                    minedOre != null ? minedOre.name : "ore", currentMiningTarget,
                    targetOre != null ? targetOre.name : "all",
                    targetOre == null || (minedOre != null && minedOre == targetOre));
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
    /**
     * Scan the walls, ceiling, and floor around a tunnel position for ores.
     * Always mines ALL ores found (not just the target) — this ensures Jim picks up
     * coal, copper, etc. as free bonus resources (especially coal for fuel!).
     * Only the target ore counts toward the completion condition.
     */
    private void scanTunnelWalls(BlockPos tunnelPos) {
        for (int dx = -ORE_SCAN_RADIUS; dx <= ORE_SCAN_RADIUS; dx++) {
            for (int dy = -1; dy <= 2; dy++) { // floor to just above head
                for (int dz = -ORE_SCAN_RADIUS; dz <= ORE_SCAN_RADIUS; dz++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue; // Skip tunnel itself
                    BlockPos checkPos = tunnelPos.offset(dx, dy, dz);
                    // Skip blocks inside the home area
                    if (companion.isInHomeArea(checkPos)) continue;
                    BlockState state = companion.level().getBlockState(checkPos);

                    // Mine ALL ores found in the walls, not just the target.
                    // This ensures Jim picks up coal (fuel), copper, etc. as bonus.
                    if (OreGuide.isOre(state) && !oreQueue.contains(checkPos)) {
                        oreQueue.add(checkPos);
                    }
                }
            }
        }
    }

    /**
     * Handle gravity-affected blocks (gravel, sand, concrete powder) above a mined position.
     * After breaking a block at head level, falling blocks above can cascade down and fill
     * the tunnel, trapping the companion. This mines them preemptively before they fall.
     */
    private void handleFallingBlocks(BlockPos abovePos) {
        Level level = companion.level();
        int maxFalling = 10; // Safety cap — don't mine an entire gravel column to the sky
        BlockPos checkPos = abovePos;
        for (int i = 0; i < maxFalling; i++) {
            if (level.getBlockState(checkPos).getBlock() instanceof FallingBlock) {
                companion.equipBestToolForBlock(level.getBlockState(checkPos));
                BlockHelper.breakBlock(companion, checkPos);
                checkPos = checkPos.above();
            } else {
                break;
            }
        }
    }

    /**
     * Try to seal fluid (lava/water) adjacent to tunnel blocks with cobblestone.
     * Places cobblestone on the exposed face to block the fluid from flowing in.
     *
     * @return true if fluid was sealed, false if we should stop mining
     */
    private boolean tryToSealFluid(BlockPos feetPos, BlockPos headPos) {
        Level level = companion.level();
        boolean sealed = false;

        for (BlockPos pos : new BlockPos[]{feetPos, headPos}) {
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos adjacent = pos.relative(dir);
                BlockState adjState = level.getBlockState(adjacent);

                // Seal lava unconditionally
                if (adjState.getFluidState().is(FluidTags.LAVA)) {
                    // Place cobblestone at the tunnel position to block the lava
                    if (level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced()) {
                        BlockHelper.placeBlock(companion, pos, Blocks.COBBLESTONE);
                        sealed = true;
                    }
                }
                // Seal water source blocks
                if (adjState.getFluidState().is(FluidTags.WATER) && adjState.getFluidState().isSource()) {
                    if (level.getBlockState(pos).isAir() || level.getBlockState(pos).canBeReplaced()) {
                        BlockHelper.placeBlock(companion, pos, Blocks.COBBLESTONE);
                        sealed = true;
                    }
                }
            }
        }
        return sealed;
    }

    // ================================================================
    // Torch Crafting & Placement
    // ================================================================

    /**
     * Try to place a torch at the given tunnel position.
     * If no torches in inventory, try to craft some from coal + sticks.
     * Falls back to crafting sticks from planks, planks from logs if needed.
     */
    private void tryPlaceTorch(BlockPos tunnelPos) {
        // Check if we have torches — craft with shared method if not
        if (BlockHelper.countItem(companion, Items.TORCH) == 0) {
            int crafted = BlockHelper.tryAutoCraftTorches(companion, 8);
            if (crafted > 0) {
                say("Crafted " + crafted + " torches.");
                torchWarningGiven = false; // Reset warning since we got more
            } else if (!torchWarningGiven) {
                say("I'm out of torches and don't have materials to craft more!");
                torchWarningGiven = true;
            }
        }

        if (BlockHelper.countItem(companion, Items.TORCH) > 0) {
            boolean placed = BlockHelper.placeTorch(companion, tunnelPos);
            if (placed) {
                MCAi.LOGGER.debug("Strip-mine: placed torch at {}", tunnelPos);
            }
        }
    }

    @Override
    protected void cleanup() {
        oreQueue.clear();
    }
}
