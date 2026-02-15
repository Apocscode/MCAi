package com.apocscode.mcai.task.mining;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.CompanionTask;
import com.apocscode.mcai.task.OreGuide;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Task: Systematic branch mining from a hub room.
 *
 * Creates a central corridor from the hub, then digs branch tunnels perpendicular
 * to both sides with configurable spacing. Uses the efficient 3-block gap pattern
 * so every block is visible from at least one tunnel, and adds poke holes every
 * 4th block for extra coverage.
 *
 * Branch layout (top-down view, hub at bottom):
 *
 *     B4L ─── corridor ─── B4R
 *     B3L ─── corridor ─── B3R
 *     B2L ─── corridor ─── B2R
 *     B1L ─── corridor ─── B1R
 *              hub
 *
 * Each branch is a 1×2 tunnel (feet + head height).
 * Poke holes: every 4th block, dig 1 block into each wall to see more ore.
 * Torches: placed every 8 blocks for lighting.
 *
 * Ores found in walls/ceiling/floor are mined immediately.
 * When inventory reaches 80% full, the companion returns to the hub to deposit.
 */
public class BranchMineTask extends CompanionTask {

    private final MineState mineState;
    @Nullable
    private final OreGuide.Ore targetOre;

    /** Current sub-phase. */
    private Phase phase = Phase.NAVIGATE_HUB;

    /** The branch currently being mined. */
    @Nullable
    private MineState.MineBranch activeBranch;

    /** Position along the corridor for the next branch pair. */
    private int corridorProgress = 0;

    /** Number of branches completed. */
    private int branchesCompleted = 0;

    /** Ores found during this task. */
    private int oresMined = 0;

    /** Blocks broken during this task. */
    private int blocksBroken = 0;

    /** Stuck detection. */
    private int stuckTimer = 0;

    /** Ore queue — ores spotted in walls to mine as side trips. */
    private final Deque<BlockPos> oreQueue = new ArrayDeque<>();

    /** The ore we're currently side-tripping to mine. */
    @Nullable
    private BlockPos currentOreTarget;

    /** Blocks since last torch in current tunnel. */
    private int blocksSinceLastTorch = 0;

    /** Whether we've set up branches for the active level yet. */
    private boolean branchesInitialized = false;

    private static final int STUCK_TIMEOUT = 80;
    private static final int TORCH_INTERVAL = 8;
    private static final int POKE_HOLE_INTERVAL = 4;
    private static final int ORE_SCAN_RADIUS = 2;
    private static final double INVENTORY_FULL_THRESHOLD = 0.80;
    private static final int MAX_ORE_DETOUR = 3; // Max blocks to detour for an ore

    private enum Phase {
        NAVIGATE_HUB,       // Return to hub center
        DIG_CORRIDOR,       // Extend the central corridor for the next branch pair
        SELECT_BRANCH,      // Pick the next branch to mine
        NAVIGATE_BRANCH,    // Walk to branch start
        DIG_BRANCH,         // Mine the branch tunnel
        MINE_ORE_DETOUR,    // Side trip to mine a spotted ore
        POKE_HOLE,          // Dig poke hole in wall
        DEPOSIT_ITEMS,      // Return to hub and dump inventory in chests
        DONE
    }

    public BranchMineTask(CompanionEntity companion, MineState mineState, @Nullable OreGuide.Ore targetOre) {
        super(companion, "Branch mining" + (targetOre != null ? " for " + targetOre.name : ""));
        this.mineState = mineState;
        this.targetOre = targetOre;
    }

    @Override
    public String getTaskName() {
        return "BranchMine";
    }

    @Override
    public int getProgressPercent() {
        int totalBranches = mineState.getBranchesPerSide() * 2;
        if (totalBranches <= 0) return 0;
        return Math.min(100, (branchesCompleted * 100) / totalBranches);
    }

    @Override
    protected void start() {
        MineState.MineLevel level = mineState.getActiveLevel();
        if (level == null || !level.isHubBuilt()) {
            fail("No hub available — hub must be created first.");
            return;
        }

        // Initialize branches for this level if not done already
        if (!branchesInitialized) {
            initializeBranches(level);
            branchesInitialized = true;
        }

        String oreLabel = targetOre != null ? " for " + targetOre.name + " ore" : "";
        say("Starting branch mining" + oreLabel + ". " +
                (mineState.getBranchesPerSide() * 2) + " branches planned, " +
                mineState.getBranchLength() + " blocks each.");

        phase = Phase.NAVIGATE_HUB;
    }

    /**
     * Create branch definitions for the given mining level.
     * Branches extend perpendicular to the shaft direction from the corridor.
     */
    private void initializeBranches(MineState.MineLevel level) {
        Direction shaftDir = mineState.getShaftDirection();
        Direction left = shaftDir.getCounterClockWise();
        Direction right = shaftDir.getClockWise();
        BlockPos hub = level.getHubCenter();

        int spacing = mineState.getBranchSpacing();
        int branchLength = mineState.getBranchLength();

        for (int i = 0; i < mineState.getBranchesPerSide(); i++) {
            int corridorDist = (i + 1) * spacing;

            // Branch start positions (at the corridor wall)
            BlockPos corridorPos = hub.relative(shaftDir, corridorDist);
            BlockPos leftStart = corridorPos.relative(left, 1);
            BlockPos rightStart = corridorPos.relative(right, 1);

            // Left branch
            level.getBranches().add(new MineState.MineBranch(left, leftStart, branchLength, corridorDist));
            // Right branch
            level.getBranches().add(new MineState.MineBranch(right, rightStart, branchLength, corridorDist));
        }

        MCAi.LOGGER.info("Initialized {} branches for level Y={}",
                level.getBranches().size(), level.getDepth());
    }

    @Override
    protected void tick() {
        // Check inventory capacity before doing work
        if (phase != Phase.DEPOSIT_ITEMS && phase != Phase.NAVIGATE_HUB && phase != Phase.DONE) {
            if (BlockHelper.isInventoryNearlyFull(companion, INVENTORY_FULL_THRESHOLD)) {
                say("Inventory 80% full — heading back to deposit.");
                phase = Phase.DEPOSIT_ITEMS;
                return;
            }
        }

        switch (phase) {
            case NAVIGATE_HUB -> tickNavigateHub();
            case DIG_CORRIDOR -> tickDigCorridor();
            case SELECT_BRANCH -> tickSelectBranch();
            case NAVIGATE_BRANCH -> tickNavigateBranch();
            case DIG_BRANCH -> tickDigBranch();
            case MINE_ORE_DETOUR -> tickMineOreDetour();
            case POKE_HOLE -> tickPokeHole();
            case DEPOSIT_ITEMS -> tickDepositItems();
            case DONE -> {
                mineState.addOresMined(oresMined);
                mineState.addBlocksBroken(blocksBroken);
                say("Branch mining complete! Mined " + oresMined + " ores across " +
                        branchesCompleted + " branches.");
                complete();
            }
        }
    }

    // ================================================================
    // Phase: Navigate back to hub center
    // ================================================================

    private void tickNavigateHub() {
        MineState.MineLevel level = mineState.getActiveLevel();
        if (level == null) { fail("No active level"); return; }

        BlockPos hub = level.getHubCenter();
        if (isInReach(hub, 4.0)) {
            stuckTimer = 0;
            phase = Phase.SELECT_BRANCH;
            return;
        }

        navigateTo(hub);
        stuckTimer++;
        if (stuckTimer > STUCK_TIMEOUT) {
            MCAi.LOGGER.warn("BranchMine: stuck navigating to hub, proceeding");
            stuckTimer = 0;
            phase = Phase.SELECT_BRANCH;
        }
    }

    // ================================================================
    // Phase: Dig corridor to reach next branch pair
    // ================================================================

    private void tickDigCorridor() {
        if (activeBranch == null) {
            phase = Phase.SELECT_BRANCH;
            return;
        }

        MineState.MineLevel level = mineState.getActiveLevel();
        if (level == null) { fail("No active level"); return; }

        Direction shaftDir = mineState.getShaftDirection();
        BlockPos hub = level.getHubCenter();
        int targetCorridorDist = activeBranch.getCorridorOffset();

        // Dig corridor blocks from hub forward to the branch position
        for (int d = 1; d <= targetCorridorDist; d++) {
            BlockPos corridorFeet = hub.relative(shaftDir, d);
            BlockPos corridorHead = corridorFeet.above();

            breakIfSolid(corridorFeet);
            breakIfSolid(corridorHead);
        }

        // Navigate to the corridor position at the branch
        BlockPos corridorTarget = hub.relative(shaftDir, targetCorridorDist);
        navigateTo(corridorTarget);

        phase = Phase.NAVIGATE_BRANCH;
    }

    // ================================================================
    // Phase: Select next branch to mine
    // ================================================================

    private void tickSelectBranch() {
        MineState.MineLevel level = mineState.getActiveLevel();
        if (level == null) { fail("No active level"); return; }

        MineState.MineBranch next = level.getNextIncompleteBranch();
        if (next == null) {
            // All branches done
            phase = Phase.DONE;
            return;
        }

        activeBranch = next;
        activeBranch.setStatus(MineState.BranchStatus.IN_PROGRESS);
        blocksSinceLastTorch = 0;

        // If corridor needs to be dug to reach this branch
        phase = Phase.DIG_CORRIDOR;
    }

    // ================================================================
    // Phase: Navigate to branch start
    // ================================================================

    private void tickNavigateBranch() {
        if (activeBranch == null) { phase = Phase.SELECT_BRANCH; return; }

        BlockPos start = activeBranch.getStartPos();
        if (isInReach(start, 3.0)) {
            stuckTimer = 0;
            phase = Phase.DIG_BRANCH;
            return;
        }

        navigateTo(start);
        stuckTimer++;
        if (stuckTimer > STUCK_TIMEOUT) {
            MCAi.LOGGER.warn("BranchMine: stuck reaching branch start, skipping");
            activeBranch.setStatus(MineState.BranchStatus.BLOCKED);
            branchesCompleted++;
            stuckTimer = 0;
            phase = Phase.SELECT_BRANCH;
        }
    }

    // ================================================================
    // Phase: Dig the branch tunnel
    // ================================================================

    private void tickDigBranch() {
        if (activeBranch == null) { phase = Phase.SELECT_BRANCH; return; }

        int progress = activeBranch.getCurrentLength();
        int maxLen = activeBranch.getMaxLength();

        // Branch complete?
        if (progress >= maxLen) {
            activeBranch.setStatus(MineState.BranchStatus.COMPLETED);
            branchesCompleted++;
            MCAi.LOGGER.info("Branch completed: {} direction, {} blocks",
                    activeBranch.getDirection().getName(), progress);
            activeBranch = null;
            phase = Phase.NAVIGATE_HUB; // Return to hub for next branch
            return;
        }

        // Check ore queue first — mine nearby ores before continuing
        if (!oreQueue.isEmpty()) {
            currentOreTarget = oreQueue.poll();
            phase = Phase.MINE_ORE_DETOUR;
            return;
        }

        Direction branchDir = activeBranch.getDirection();
        BlockPos currentEnd = activeBranch.getCurrentEnd();
        BlockPos nextFeet = currentEnd.relative(branchDir);
        BlockPos nextHead = nextFeet.above();
        Level level = companion.level();

        // Safety: check for lava/void
        if (!BlockHelper.isSafeToMine(level, nextFeet) || !BlockHelper.isSafeToMine(level, nextHead)) {
            say("Lava in branch! Stopping this branch at " + progress + " blocks.");
            activeBranch.setStatus(MineState.BranchStatus.BLOCKED);
            branchesCompleted++;
            activeBranch = null;
            phase = Phase.NAVIGATE_HUB;
            return;
        }

        // Check if we need to walk to the tunnel face
        double distSq = companion.distanceToSqr(
                nextFeet.getX() + 0.5, nextFeet.getY() + 0.5, nextFeet.getZ() + 0.5);
        if (distSq > 4.0) {
            navigateTo(currentEnd);
            stuckTimer++;
            if (stuckTimer > STUCK_TIMEOUT) {
                say("Stuck in branch tunnel. Ending this branch.");
                activeBranch.setStatus(MineState.BranchStatus.BLOCKED);
                branchesCompleted++;
                activeBranch = null;
                stuckTimer = 0;
                phase = Phase.NAVIGATE_HUB;
            }
            return;
        }
        stuckTimer = 0;

        // Dig the two blocks ahead
        breakIfSolid(nextFeet);
        breakIfSolid(nextHead);

        // Handle falling blocks
        handleFallingBlocks(nextHead.above());

        // Ensure solid floor
        BlockPos floorPos = nextFeet.below();
        BlockState floorState = level.getBlockState(floorPos);
        if (floorState.isAir() || floorState.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            BlockHelper.placeBlock(companion, floorPos, Blocks.COBBLESTONE);
        }

        // Scan walls for ores
        scanTunnelWalls(nextFeet, branchDir);

        // Mark progress
        activeBranch.setCurrentLength(progress + 1);

        // Torch placement
        blocksSinceLastTorch++;
        if (blocksSinceLastTorch >= TORCH_INTERVAL) {
            // Try crafting torches if we have none but have materials
            if (BlockHelper.countItem(companion, Items.TORCH) <= 0) {
                tryMidMineTorchCraft();
            }
            BlockPos torchPos = currentEnd.above(); // Head height at previous position
            if (BlockHelper.placeTorch(companion, torchPos)) {
                blocksSinceLastTorch = 0;
                mineState.addTorchesPlaced(1);
            }
        }

        // Poke hole every POKE_HOLE_INTERVAL blocks
        if (progress > 0 && progress % POKE_HOLE_INTERVAL == 0) {
            phase = Phase.POKE_HOLE;
            return;
        }

        // Move forward
        navigateTo(nextFeet);
    }

    // ================================================================
    // Phase: Poke holes in branch walls
    // ================================================================

    private void tickPokeHole() {
        if (activeBranch == null) { phase = Phase.DIG_BRANCH; return; }

        Direction branchDir = activeBranch.getDirection();
        BlockPos currentEnd = activeBranch.getCurrentEnd();
        Level level = companion.level();

        // Dig 1 block into each wall (left and right of the branch)
        Direction leftWall = branchDir.getCounterClockWise();
        Direction rightWall = branchDir.getClockWise();

        BlockPos leftHole = currentEnd.relative(leftWall);
        BlockPos rightHole = currentEnd.relative(rightWall);

        // Left poke hole
        if (BlockHelper.isSafeToMine(level, leftHole)) {
            BlockState lState = level.getBlockState(leftHole);
            if (!lState.isAir()) {
                if (isTargetOre(lState)) oresMined++;
                breakIfSolid(leftHole);
            }
        }

        // Right poke hole
        if (BlockHelper.isSafeToMine(level, rightHole)) {
            BlockState rState = level.getBlockState(rightHole);
            if (!rState.isAir()) {
                if (isTargetOre(rState)) oresMined++;
                breakIfSolid(rightHole);
            }
        }

        phase = Phase.DIG_BRANCH;
    }

    // ================================================================
    // Phase: Side trip to mine a spotted ore
    // ================================================================

    private void tickMineOreDetour() {
        if (currentOreTarget == null) {
            phase = Phase.DIG_BRANCH;
            return;
        }

        Level level = companion.level();
        BlockState state = level.getBlockState(currentOreTarget);

        // Already mined or no longer ore
        if (state.isAir() || !isTargetOre(state)) {
            currentOreTarget = null;
            phase = Phase.DIG_BRANCH;
            return;
        }

        if (isInReach(currentOreTarget, 4.5)) {
            if (!BlockHelper.isSafeToMine(level, currentOreTarget)) {
                currentOreTarget = null;
                phase = Phase.DIG_BRANCH;
                return;
            }
            companion.equipBestToolForBlock(state);
            BlockHelper.breakBlock(companion, currentOreTarget);
            oresMined++;
            blocksBroken++;
            OreGuide.Ore ore = OreGuide.identifyOre(state);
            MCAi.LOGGER.debug("BranchMine: mined {} at {}", ore != null ? ore.name : "ore", currentOreTarget);
            currentOreTarget = null;
            phase = Phase.DIG_BRANCH;
        } else {
            navigateTo(currentOreTarget);
            stuckTimer++;
            if (stuckTimer > STUCK_TIMEOUT / 2) { // Shorter timeout for detour
                currentOreTarget = null;
                stuckTimer = 0;
                phase = Phase.DIG_BRANCH;
            }
        }
    }

    // ================================================================
    // Phase: Deposit items at hub chests
    // ================================================================

    private void tickDepositItems() {
        MineState.MineLevel level = mineState.getActiveLevel();
        if (level == null) { phase = Phase.DIG_BRANCH; return; }

        BlockPos hub = level.getHubCenter();

        if (!isInReach(hub, 4.0)) {
            navigateTo(hub);
            stuckTimer++;
            if (stuckTimer > STUCK_TIMEOUT * 2) { // Generous timeout for return trip
                MCAi.LOGGER.warn("BranchMine: stuck returning to hub for deposit");
                stuckTimer = 0;
                phase = Phase.DIG_BRANCH; // Give up on depositing
            }
            return;
        }
        stuckTimer = 0;

        // Deposit items into chests at the hub
        int deposited = depositToNearbyChests();
        if (deposited > 0) {
            say("Deposited " + deposited + " items at hub. Continuing mining...");
        }

        // Return to branch mining
        if (activeBranch != null) {
            phase = Phase.NAVIGATE_BRANCH;
        } else {
            phase = Phase.SELECT_BRANCH;
        }
    }

    /**
     * Try to deposit companion inventory into nearby chests.
     * @return number of items deposited
     */
    private int depositToNearbyChests() {
        // Use the item routing system if available, otherwise manual deposit
        if (com.apocscode.mcai.logistics.ItemRoutingHelper.hasTaggedStorage(companion)) {
            return com.apocscode.mcai.logistics.ItemRoutingHelper.routeAllCompanionItems(companion);
        }

        // Manual fallback: find chest blocks near hub and interact
        // For now, just clear tool items we don't need to keep
        return 0; // Will be enhanced when container interaction is wired up
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Check if a block is an ore we care about.
     */
    private boolean isTargetOre(BlockState state) {
        if (targetOre != null) {
            return targetOre.matches(state);
        }
        return OreGuide.isOre(state);
    }

    /**
     * Break a block if it's not air/bedrock. Counts blocks broken and ores.
     */
    private boolean breakIfSolid(BlockPos pos) {
        BlockState state = companion.level().getBlockState(pos);
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK) return false;

        if (isTargetOre(state)) oresMined++;

        companion.equipBestToolForBlock(state);
        BlockHelper.breakBlock(companion, pos);
        blocksBroken++;
        return true;
    }

    /**
     * Scan tunnel walls, ceiling, and floor for ores to queue.
     */
    private void scanTunnelWalls(BlockPos tunnelPos, Direction tunnelDir) {
        Level level = companion.level();
        for (int dx = -ORE_SCAN_RADIUS; dx <= ORE_SCAN_RADIUS; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -ORE_SCAN_RADIUS; dz <= ORE_SCAN_RADIUS; dz++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) continue; // Skip tunnel itself
                    BlockPos checkPos = tunnelPos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(checkPos);

                    if (isTargetOre(state) && !oreQueue.contains(checkPos)) {
                        // Only queue if close enough to detour
                        double dist = checkPos.distSqr(tunnelPos);
                        if (dist <= MAX_ORE_DETOUR * MAX_ORE_DETOUR) {
                            oreQueue.add(checkPos);
                        }
                    }
                }
            }
        }
    }

    /**
     * Handle falling blocks above a position.
     */
    private void handleFallingBlocks(BlockPos abovePos) {
        Level level = companion.level();
        int maxFalling = 10;
        BlockPos checkPos = abovePos;
        for (int i = 0; i < maxFalling; i++) {
            if (BlockHelper.isFallingBlock(level, checkPos)) {
                companion.equipBestToolForBlock(level.getBlockState(checkPos));
                BlockHelper.breakBlock(companion, checkPos);
                blocksBroken++;
                checkPos = checkPos.above();
            } else {
                break;
            }
        }
    }

    @Override
    protected void cleanup() {
        oreQueue.clear();
        MCAi.LOGGER.info("BranchMine cleanup: {} branches, {} ores, {} blocks broken",
                branchesCompleted, oresMined, blocksBroken);
    }

    // ================================================================
    // Torch Crafting (mid-mine)
    // ================================================================

    /**
     * Attempt to craft torches mid-mining from coal/charcoal + sticks found while digging.
     * If the companion has coal but no sticks, tries to craft sticks from planks.
     * If no planks either, skips silently.
     */
    private void tryMidMineTorchCraft() {
        var inv = companion.getCompanionInventory();

        // Count fuel: coal or charcoal
        int coal = BlockHelper.countItem(companion, Items.COAL)
                 + BlockHelper.countItem(companion, Items.CHARCOAL);
        if (coal <= 0) return;

        // Count sticks
        int sticks = BlockHelper.countItem(companion, Items.STICK);

        // If no sticks, try to craft from planks (any plank type)
        if (sticks <= 0) {
            int planks = 0;
            net.minecraft.world.item.Item plankItem = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(stack.getItem()).getPath();
                    if (id.endsWith("_planks")) {
                        planks += stack.getCount();
                        if (plankItem == null) plankItem = stack.getItem();
                    }
                }
            }
            if (planks >= 2 && plankItem != null) {
                // Craft sticks: 2 planks -> 4 sticks
                BlockHelper.removeItem(companion, plankItem, 2);
                inv.addItem(new net.minecraft.world.item.ItemStack(Items.STICK, 4));
                sticks = 4;
                MCAi.LOGGER.debug("BranchMine: mid-mine crafted 4 sticks from planks");
            }
        }

        if (sticks <= 0) return;

        // Craft torches: 1 coal + 1 stick -> 4 torches
        int batches = Math.min(coal, sticks);
        batches = Math.min(batches, 8); // Cap at 32 torches per craft

        // Remove materials — prefer coal over charcoal
        int coalCount = BlockHelper.countItem(companion, Items.COAL);
        int fromCoal = Math.min(batches, coalCount);
        if (fromCoal > 0) {
            BlockHelper.removeItem(companion, Items.COAL, fromCoal);
        }
        int fromCharcoal = batches - fromCoal;
        if (fromCharcoal > 0) {
            BlockHelper.removeItem(companion, Items.CHARCOAL, fromCharcoal);
        }
        BlockHelper.removeItem(companion, Items.STICK, batches);

        // Add torches
        int torchesProduced = batches * 4;
        inv.addItem(new net.minecraft.world.item.ItemStack(Items.TORCH, torchesProduced));

        MCAi.LOGGER.info("BranchMine: mid-mine crafted {} torches ({} coal + {} sticks)",
                torchesProduced, fromCoal + fromCharcoal, batches);
    }
}
