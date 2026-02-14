package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * AI Goal: Open and close fence gates when the companion is near them.
 *
 * Unlike doors, vanilla pathfinding treats closed fence gates as FENCE PathType
 * (impassable, same as fences and walls). We can't set FENCE malus to 0 or the
 * companion would try to pathfind through solid fences/walls.
 *
 * Instead, this goal works proactively:
 * 1. When the companion is near a closed fence gate (within 2 blocks), open it
 * 2. Once open, the pathfinder sees it as walkable and routes through
 * 3. Close the gate after the companion passes
 *
 * This triggers even without active navigation — if the companion is near a
 * fence gate and moving (follow mode, task, etc.), the gate opens automatically.
 *
 * Handles all vanilla and modded fence gates.
 */
public class CompanionOpenFenceGateGoal extends Goal {

    private final CompanionEntity companion;
    private final boolean closeAfterPassing;
    private BlockPos gatePos;
    private boolean hasOpened = false;
    private int ticksSinceOpened = 0;
    private int cooldown = 0;
    private static final int CLOSE_DELAY_TICKS = 30;    // Close gate 1.5 seconds after opening
    private static final int COOLDOWN_TICKS = 10;       // Brief cooldown between activations
    private static final int MAX_TICKS = 100;            // Give up after 5 seconds

    public CompanionOpenFenceGateGoal(CompanionEntity companion, boolean closeAfterPassing) {
        this.companion = companion;
        this.closeAfterPassing = closeAfterPassing;
        // No Flag.MOVE — this goal only toggles adjacent blocks, doesn't navigate.
        // Using MOVE would conflict with combat and follow goals at the same priority.
        this.setFlags(EnumSet.noneOf(Goal.Flag.class));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }

        // Only activate when the companion has an active path (prevents opening
        // random gates in animal pens while wandering by)
        if (!companion.getNavigation().isInProgress()) return false;

        Level level = companion.level();
        BlockPos pos = companion.blockPosition();

        // Check for closed fence gates adjacent to the companion (1-block radius only)
        // Tight radius prevents opening gates Jim isn't walking toward
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(checkPos);
                    if (state.getBlock() instanceof FenceGateBlock
                            && !state.getValue(FenceGateBlock.OPEN)) {
                        gatePos = checkPos;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (gatePos == null) return false;
        if (ticksSinceOpened > MAX_TICKS) return false;
        if (!hasOpened) return true;
        // Continue until we should close the gate
        if (closeAfterPassing) {
            return ticksSinceOpened < CLOSE_DELAY_TICKS * 2;
        }
        return false;
    }

    @Override
    public void start() {
        hasOpened = false;
        ticksSinceOpened = 0;
    }

    @Override
    public void tick() {
        if (gatePos == null) return;

        Level level = companion.level();
        BlockState state = level.getBlockState(gatePos);

        if (!hasOpened) {
            // Open the gate if we're close enough (already adjacent due to 1-block scan)
            double distSq = companion.distanceToSqr(
                    gatePos.getX() + 0.5, gatePos.getY() + 0.5, gatePos.getZ() + 0.5);

            if (distSq <= 4.0) { // Within 2 blocks
                if (state.getBlock() instanceof FenceGateBlock
                        && !state.getValue(FenceGateBlock.OPEN)) {
                    toggleFenceGate(level, gatePos, true);
                    hasOpened = true;
                    ticksSinceOpened = 0;
                } else {
                    // Gate already open or gone
                    stop();
                }
            } else {
                // Too far — give up (we don't own MOVE flag, can't navigate)
                ticksSinceOpened++;
                if (ticksSinceOpened > 20) stop();
            }
        } else if (closeAfterPassing) {
            ticksSinceOpened++;
            // Close the gate after the companion has passed through
            double distSq = companion.distanceToSqr(
                    gatePos.getX() + 0.5, gatePos.getY() + 0.5, gatePos.getZ() + 0.5);
            if (distSq > 6.25 || ticksSinceOpened > CLOSE_DELAY_TICKS) {
                // Companion has moved away (>2.5 blocks) or enough time passed
                BlockState currentState = level.getBlockState(gatePos);
                if (currentState.getBlock() instanceof FenceGateBlock
                        && currentState.getValue(FenceGateBlock.OPEN)) {
                    toggleFenceGate(level, gatePos, false);
                }
                cooldown = COOLDOWN_TICKS;
                stop();
            }
        } else {
            cooldown = COOLDOWN_TICKS;
            stop();
        }
    }

    @Override
    public void stop() {
        gatePos = null;
        hasOpened = false;
        ticksSinceOpened = 0;
    }

    /**
     * Toggle a fence gate open or closed.
     * Uses setBlock to directly change the OPEN property.
     */
    private void toggleFenceGate(Level level, BlockPos pos, boolean open) {
        if (level.isClientSide) return; // Server-side only
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FenceGateBlock) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, open), 10);
            // Play gate sound effect
            if (open) {
                level.levelEvent(null, 1008, pos, 0); // Gate open sound
            } else {
                level.levelEvent(null, 1014, pos, 0); // Gate close sound
            }
            MCAi.LOGGER.debug("Companion {} fence gate at {}",
                    open ? "opened" : "closed", pos);
        }
    }
}
