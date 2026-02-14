package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
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
    private BlockPos tunnelFacePos = null; // Where the companion should be standing to mine next
    private BlockPos descendTargetPos = null; // Where the companion should walk to during stair descent

    private BlockPos walkOutTarget = null; // Where to walk to exit home area

    private static final int STUCK_TIMEOUT = 60; // 3 seconds
    private static final int ORE_SCAN_RADIUS = 2; // Check 2 blocks around tunnel for ores
    private static final int TORCH_INTERVAL = 8;  // Place a torch every N blocks

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
            // Walk 25 blocks in the mining direction to clear the home area
            walkOutTarget = companion.blockPosition().relative(direction, 25);
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
            if (stuckTimer > STUCK_TIMEOUT) {
                say("Can't reach tunnel face — stuck at " + companion.blockPosition() +
                        ". Mined " + oresMined + " ores in " + tunnelProgress + " blocks.");
                phase = Phase.DONE;
            }
            return;
        }
        stuckTimer = 0;

        // Calculate next tunnel position (one block ahead of where we stand)
        BlockPos nextFeet = tunnelFacePos.relative(direction);
        BlockPos nextHead = nextFeet.above();

        // Safety: check for lava/void ahead
        if (!BlockHelper.isSafeToMine(companion.level(), nextFeet) ||
                !BlockHelper.isSafeToMine(companion.level(), nextHead)) {
            say("Lava detected ahead! Stopping tunnel. Mined " + oresMined + " ores in " + tunnelProgress + " blocks.");
            phase = Phase.DONE;
            return;
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

        // Ensure solid floor under feet (prevent falling into caves/voids)
        BlockPos floorPos = nextFeet.below();
        BlockState floorState = companion.level().getBlockState(floorPos);
        if (floorState.isAir() || floorState.getFluidState().is(FluidTags.LAVA)) {
            BlockHelper.placeBlock(companion, floorPos, Blocks.COBBLESTONE);
        }

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

    // ================================================================
    // Torch Crafting & Placement
    // ================================================================

    /**
     * Try to place a torch at the given tunnel position.
     * If no torches in inventory, try to craft some from coal + sticks.
     * Falls back to crafting sticks from planks, planks from logs if needed.
     */
    private void tryPlaceTorch(BlockPos tunnelPos) {
        SimpleContainer inv = companion.getCompanionInventory();

        // Check if we have torches already
        if (countItem(inv, Items.TORCH) == 0) {
            // Try to craft torches: 1 coal/charcoal + 1 stick = 4 torches
            craftTorches(inv);
        }

        if (countItem(inv, Items.TORCH) > 0) {
            // Place torch at feet level — the tunnel floor should be solid below
            boolean placed = BlockHelper.placeBlock(companion, tunnelPos, Blocks.TORCH);
            if (placed) {
                MCAi.LOGGER.debug("Strip-mine: placed torch at {}", tunnelPos);
            }
        }
    }

    /**
     * Craft torches from available materials.
     * Chain: logs → planks → sticks → torches (with coal/charcoal).
     */
    private void craftTorches(SimpleContainer inv) {
        // Need coal or charcoal
        boolean hasCoal = countItem(inv, Items.COAL) > 0;
        boolean hasCharcoal = countItem(inv, Items.CHARCOAL) > 0;
        if (!hasCoal && !hasCharcoal) return; // Can't craft torches without fuel

        // Ensure we have sticks (2 planks → 4 sticks)
        if (countItem(inv, Items.STICK) == 0) {
            craftSticks(inv);
        }
        if (countItem(inv, Items.STICK) == 0) return; // No sticks available

        // Craft: 1 coal/charcoal + 1 stick = 4 torches
        if (hasCoal) {
            consumeItem(inv, Items.COAL, 1);
        } else {
            consumeItem(inv, Items.CHARCOAL, 1);
        }
        consumeItem(inv, Items.STICK, 1);
        inv.addItem(new ItemStack(Items.TORCH, 4));
        MCAi.LOGGER.info("Strip-mine: crafted 4 torches");
    }

    /**
     * Craft sticks from planks. If no planks, convert logs to planks first.
     * 2 planks → 4 sticks.
     */
    private void craftSticks(SimpleContainer inv) {
        // Try to get planks from logs first if needed
        if (countAnyPlank(inv) < 2) {
            convertLogsToPlanks(inv);
        }

        // Find any plank type and craft sticks
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.PLANKS) && stack.getCount() >= 2) {
                stack.shrink(2);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                inv.addItem(new ItemStack(Items.STICK, 4));
                MCAi.LOGGER.debug("Strip-mine: crafted 4 sticks from planks");
                return;
            }
        }
    }

    /**
     * Convert any logs in inventory to planks (1 log → 4 planks).
     */
    private void convertLogsToPlanks(SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.LOGS)) {
                // Determine plank type (oak by default for simplicity)
                stack.shrink(1);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                inv.addItem(new ItemStack(Items.OAK_PLANKS, 4));
                MCAi.LOGGER.debug("Strip-mine: converted 1 log → 4 oak planks");
                return;
            }
        }
    }

    // ================================================================
    // Inventory Helpers
    // ================================================================

    private static int countItem(SimpleContainer inv, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private static int countAnyPlank(SimpleContainer inv) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.PLANKS)) count += stack.getCount();
        }
        return count;
    }

    private static void consumeItem(SimpleContainer inv, net.minecraft.world.item.Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                remaining -= take;
            }
        }
    }

    @Override
    protected void cleanup() {
        oreQueue.clear();
    }
}
