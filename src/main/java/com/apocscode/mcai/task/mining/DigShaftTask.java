package com.apocscode.mcai.task.mining;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.CompanionTask;
import com.apocscode.mcai.task.OreGuide;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Task: Dig a 2×1 staircase shaft down to a target Y-level.
 *
 * Creates a walkable staircase (no ladders needed) by alternating:
 *   Step N:   dig 2 blocks forward at current Y (feet + head space)
 *   Step N+1: dig 1 block down + 1 block forward-and-down (descend one level)
 *
 * Pattern per step (side view, going right):
 *   ##    ##    ##
 *   .C -> ..C -> ...
 *   ##    #.#   #..C  <- companion walks down the staircase
 *
 * The companion:
 *   - Places cobblestone floors over any gaps/lava for safety
 *   - Places torches every 8 blocks for light
 *   - Checks for lava before mining each block
 *   - Scans walls for ores and mines them opportunistically
 *   - Handles falling blocks (gravel/sand) by re-mining until clear
 *
 * On completion, updates the MineState with the shaft bottom position.
 */
public class DigShaftTask extends CompanionTask {

    private final MineState mineState;
    private final Direction direction;
    private final int targetY;

    /** Current sub-phase within shaft digging. */
    private Phase phase = Phase.NAVIGATE_START;

    /** How many steps we've descended. */
    private int stepsDescended = 0;

    /** Total steps needed to reach targetY. */
    private int totalSteps;

    /** Ticks we've been stuck trying to navigate. */
    private int stuckTimer = 0;

    /** Blocks since last torch was placed. */
    private int blocksSinceLastTorch = 0;

    /** Ores mined during shaft digging (bonus). */
    private int oresMined = 0;

    /** Blocks broken total. */
    private int blocksBroken = 0;

    private static final int STUCK_TIMEOUT = 80;  // 4 seconds
    private static final int TORCH_INTERVAL = 8;  // Place torch every 8 blocks
    private static final int ORE_SCAN_RADIUS = 1;  // Check adjacent blocks for ores

    private enum Phase {
        NAVIGATE_START,   // Move to shaft entrance
        DIG_FORWARD,      // Dig 2 blocks forward at current level
        DIG_DOWN,         // Dig down one step (staircase pattern)
        SCAN_ORES,        // Quick scan for wall ores
        DONE
    }

    /**
     * Create a shaft-digging task.
     *
     * @param companion  The companion entity
     * @param mineState  Shared mine state to update on completion
     * @param direction  Horizontal direction to dig the staircase
     * @param targetY    Y-level to reach
     */
    public DigShaftTask(CompanionEntity companion, MineState mineState, Direction direction, int targetY) {
        super(companion, "Dig shaft to Y=" + targetY + " heading " + direction.getName());
        this.mineState = mineState;
        this.direction = direction;
        this.targetY = targetY;

        int currentY = companion.blockPosition().getY();
        this.totalSteps = Math.max(0, currentY - targetY);
    }

    @Override
    public String getTaskName() {
        return "DigShaft";
    }

    @Override
    public int getProgressPercent() {
        if (totalSteps <= 0) return 100;
        return Math.min(100, (stepsDescended * 100) / totalSteps);
    }

    @Override
    protected void start() {
        phase = Phase.NAVIGATE_START;
        say("Digging staircase shaft to Y=" + targetY + " heading " + direction.getName() +
                ". That's " + totalSteps + " blocks down.");

        // If already at or below target, skip
        if (companion.blockPosition().getY() <= targetY) {
            say("Already at Y=" + companion.blockPosition().getY() + ". No shaft needed.");
            mineState.setShaftBottom(companion.blockPosition());
            complete();
        }
    }

    @Override
    protected void tick() {
        switch (phase) {
            case NAVIGATE_START -> tickNavigateStart();
            case DIG_FORWARD -> tickDigForward();
            case DIG_DOWN -> tickDigDown();
            case SCAN_ORES -> tickScanOres();
            case DONE -> {
                mineState.setShaftBottom(companion.blockPosition());
                mineState.addBlocksBroken(blocksBroken);
                mineState.addOresMined(oresMined);
                complete();
            }
        }
    }

    // ================================================================
    // Phase: Navigate to the shaft entrance
    // ================================================================

    private void tickNavigateStart() {
        BlockPos entrance = mineState.getEntrance();
        if (isInReach(entrance, 3.0)) {
            stuckTimer = 0;
            phase = Phase.DIG_FORWARD;
            return;
        }

        navigateTo(entrance);
        stuckTimer++;
        if (stuckTimer > STUCK_TIMEOUT) {
            // Can't reach entrance — just start digging from current position
            MCAi.LOGGER.warn("DigShaft: couldn't reach entrance, starting from current pos");
            mineState.setShaftBottom(companion.blockPosition()); // Update entrance
            stuckTimer = 0;
            phase = Phase.DIG_FORWARD;
        }
    }

    // ================================================================
    // Phase: Dig 2 blocks forward (staircase landing)
    // ================================================================

    private void tickDigForward() {
        // Check if we've reached target depth
        if (companion.blockPosition().getY() <= targetY) {
            say("Reached Y=" + companion.blockPosition().getY() + "! Shaft complete. " +
                    "Mined " + oresMined + " ores along the way.");
            phase = Phase.DONE;
            return;
        }

        BlockPos pos = companion.blockPosition();
        BlockPos ahead = pos.relative(direction);
        BlockPos aheadHead = ahead.above();
        Level level = companion.level();

        // Safety: check for lava ahead
        if (!BlockHelper.isSafeToMine(level, ahead) || !BlockHelper.isSafeToMine(level, aheadHead)) {
            say("Lava detected ahead! Stopping shaft at Y=" + pos.getY() + ".");
            phase = Phase.DONE;
            return;
        }

        // Safety: check for world bottom
        if (ahead.getY() <= level.getMinBuildHeight() + 1) {
            say("Near bedrock! Stopping shaft at Y=" + pos.getY() + ".");
            phase = Phase.DONE;
            return;
        }

        // Dig the two blocks ahead (feet + head level)
        if (breakIfSolid(ahead)) blocksBroken++;
        if (breakIfSolid(aheadHead)) blocksBroken++;

        // Handle falling blocks (gravel/sand) above head position
        handleFallingBlocks(aheadHead.above());

        // Move forward
        navigateTo(ahead);

        // Torch placement
        blocksSinceLastTorch++;
        if (blocksSinceLastTorch >= TORCH_INTERVAL) {
            // Place torch on the wall behind us
            BlockPos torchPos = pos.above(); // Head height at previous position
            if (BlockHelper.placeTorch(companion, torchPos)) {
                blocksSinceLastTorch = 0;
                mineState.addTorchesPlaced(1);
            }
        }

        // Now dig down for the staircase
        phase = Phase.DIG_DOWN;
    }

    // ================================================================
    // Phase: Dig down one block (staircase step)
    // ================================================================

    private void tickDigDown() {
        BlockPos pos = companion.blockPosition();
        // The block we need to dig below the forward position
        BlockPos ahead = pos.relative(direction);
        BlockPos belowAhead = ahead.below();
        Level level = companion.level();

        // Safety: check for lava below
        if (!BlockHelper.isSafeToMine(level, belowAhead)) {
            // Place cobblestone floor to seal the danger
            BlockHelper.placeBlock(companion, belowAhead, Blocks.COBBLESTONE);
            say("Sealed lava below with cobblestone. Continuing...");
            // Still descend — the floor ahead is now safe
        }

        // Dig below-ahead to create the step down
        BlockState belowAheadState = level.getBlockState(belowAhead);
        if (!belowAheadState.isAir() && belowAheadState.getBlock() != Blocks.BEDROCK) {
            // Check if it's an ore — mine it and count
            if (OreGuide.isOre(belowAheadState)) {
                oresMined++;
            }
            companion.equipBestToolForBlock(belowAheadState);
            BlockHelper.breakBlock(companion, belowAhead);
            blocksBroken++;
        }

        // Also clear the block at ahead (feet level for the lower step)
        // and ahead+above to ensure 2-high clearance at the lower position
        BlockPos lowerFeet = belowAhead;
        BlockPos lowerHead = belowAhead.above(); // = ahead

        if (breakIfSolid(lowerFeet)) blocksBroken++;
        // lowerHead = ahead, should already be cleared from DIG_FORWARD step

        // Ensure solid floor under where we'll walk
        BlockPos floorCheck = lowerFeet.below();
        BlockState floorState = level.getBlockState(floorCheck);
        if (floorState.isAir() || floorState.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
            // Place cobblestone floor
            BlockHelper.placeBlock(companion, floorCheck, Blocks.COBBLESTONE);
        }

        // Handle falling blocks above
        handleFallingBlocks(lowerHead.above());

        // Navigate to the lower position
        navigateTo(lowerFeet);

        stepsDescended++;

        // Quick ore scan before continuing
        phase = Phase.SCAN_ORES;
    }

    // ================================================================
    // Phase: Quick wall ore scan
    // ================================================================

    private void tickScanOres() {
        BlockPos pos = companion.blockPosition();
        Level level = companion.level();

        // Scan the 4 walls + ceiling around current position for exposed ores
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue; // Skip floor (we need it)
            for (int offset = 0; offset <= ORE_SCAN_RADIUS; offset++) {
                BlockPos checkPos = pos.relative(dir, offset + 1);
                BlockState state = level.getBlockState(checkPos);

                if (OreGuide.isOre(state) && BlockHelper.isSafeToMine(level, checkPos)) {
                    // Mine it if we can reach it
                    if (isInReach(checkPos, 4.5)) {
                        companion.equipBestToolForBlock(state);
                        BlockHelper.breakBlock(companion, checkPos);
                        oresMined++;
                        blocksBroken++;
                        OreGuide.Ore ore = OreGuide.identifyOre(state);
                        MCAi.LOGGER.debug("DigShaft: bonus ore {} at {}", 
                                ore != null ? ore.name : "unknown", checkPos);
                    }
                }
            }
        }

        // Check inventory capacity — warn if getting full
        if (BlockHelper.isInventoryNearlyFull(companion, 0.9)) {
            say("Inventory getting full! May need to deposit soon.");
        }

        // Continue digging
        phase = Phase.DIG_FORWARD;
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Break a block if it's not air/bedrock. Equips best tool automatically.
     * @return true if a block was actually broken
     */
    private boolean breakIfSolid(BlockPos pos) {
        BlockState state = companion.level().getBlockState(pos);
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK) return false;

        // Count ores broken in the shaft
        if (OreGuide.isOre(state)) {
            oresMined++;
        }

        companion.equipBestToolForBlock(state);
        BlockHelper.breakBlock(companion, pos);
        return true;
    }

    /**
     * Handle falling blocks (gravel, sand) by waiting for them to settle
     * and then mining them. Prevents the companion from being buried.
     */
    private void handleFallingBlocks(BlockPos abovePos) {
        Level level = companion.level();
        int maxFalling = 10; // Don't mine more than 10 falling blocks in a column
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
        // Update mine state with final shaft bottom
        mineState.setShaftBottom(companion.blockPosition());
        MCAi.LOGGER.info("DigShaft cleanup: descended {} steps, {} ores found, {} blocks broken",
                stepsDescended, oresMined, blocksBroken);
    }
}
