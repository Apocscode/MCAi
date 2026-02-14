package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.EnumSet;

/**
 * AI Goal: Open and close trapdoors when pathfinding through them.
 *
 * Handles wooden trapdoors (and modded trapdoors where canOpenByHand() = true).
 * Iron trapdoors require redstone and are NOT handled by this goal.
 *
 * Behavior:
 * - Detects closed trapdoors blocking the path (top-half trapdoors blocking head,
 *   bottom-half trapdoors blocking feet)
 * - Opens the trapdoor when close enough
 * - Closes the trapdoor after passing through
 *
 * Safety:
 * - Only opens trapdoors that can be opened by hand (canOpenByHand() check)
 * - Does NOT open bottom-half trapdoors if there's a drop below (prevents falling)
 * - Closes trapdoors after passing to prevent mob intrusion
 */
public class CompanionOpenTrapdoorGoal extends Goal {

    private final CompanionEntity companion;
    private BlockPos trapdoorPos;
    private boolean hasOpened = false;
    private int ticksSinceOpened = 0;
    private static final int CLOSE_DELAY_TICKS = 20;    // Close 1 second after passing
    private static final int MAX_TICKS = 80;             // Give up after 4 seconds

    public CompanionOpenTrapdoorGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!companion.getNavigation().isInProgress()) return false;

        Level level = companion.level();
        BlockPos pos = companion.blockPosition();

        // Check for closed trapdoors near the companion
        // Check feet level (pos), head level (pos+1), and below feet (pos-1 for floor trapdoors)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(checkPos);

                    if (isOpenableTrapdoor(state) && !state.getValue(TrapDoorBlock.OPEN)) {
                        // Safety check: don't open a bottom-half trapdoor if it would
                        // create a fall hazard (no solid ground below)
                        if (state.getValue(TrapDoorBlock.HALF) == Half.BOTTOM) {
                            BlockPos below = checkPos.below();
                            BlockState belowState = level.getBlockState(below);
                            if (!belowState.isSolidRender(level, below)) {
                                continue; // Skip — opening this would create a hole
                            }
                        }

                        trapdoorPos = checkPos;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (trapdoorPos == null) return false;
        if (ticksSinceOpened > MAX_TICKS) return false;
        if (!hasOpened) return true;
        return ticksSinceOpened < CLOSE_DELAY_TICKS * 3;
    }

    @Override
    public void start() {
        hasOpened = false;
        ticksSinceOpened = 0;
    }

    @Override
    public void tick() {
        if (trapdoorPos == null) return;

        Level level = companion.level();
        BlockState state = level.getBlockState(trapdoorPos);

        if (!hasOpened) {
            if (state.getBlock() instanceof TrapDoorBlock && !state.getValue(TrapDoorBlock.OPEN)) {
                double distSq = companion.distanceToSqr(
                        trapdoorPos.getX() + 0.5, trapdoorPos.getY() + 0.5, trapdoorPos.getZ() + 0.5);
                if (distSq <= 2.25) { // Within 1.5 blocks
                    toggleTrapdoor(level, trapdoorPos, true);
                    hasOpened = true;
                    ticksSinceOpened = 0;
                }
            } else {
                // Trapdoor disappeared or is already open
                stop();
            }
        } else {
            ticksSinceOpened++;
            double distSq = companion.distanceToSqr(
                    trapdoorPos.getX() + 0.5, trapdoorPos.getY() + 0.5, trapdoorPos.getZ() + 0.5);
            if (distSq > 4.0 || ticksSinceOpened > CLOSE_DELAY_TICKS) {
                // Close the trapdoor after passing
                BlockState currentState = level.getBlockState(trapdoorPos);
                if (currentState.getBlock() instanceof TrapDoorBlock
                        && currentState.getValue(TrapDoorBlock.OPEN)) {
                    toggleTrapdoor(level, trapdoorPos, false);
                }
                stop();
            }
        }
    }

    @Override
    public void stop() {
        trapdoorPos = null;
        hasOpened = false;
        ticksSinceOpened = 0;
    }

    /**
     * Check if a trapdoor can be opened by hand (excludes iron trapdoors).
     */
    private boolean isOpenableTrapdoor(BlockState state) {
        if (!(state.getBlock() instanceof TrapDoorBlock)) return false;
        // Iron trapdoors require redstone — can't open by hand
        return state.getBlock() != net.minecraft.world.level.block.Blocks.IRON_TRAPDOOR;
    }

    /**
     * Toggle a trapdoor open or closed.
     */
    private void toggleTrapdoor(Level level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof TrapDoorBlock) {
            level.setBlock(pos, state.setValue(TrapDoorBlock.OPEN, open), 10);
            // Play trapdoor sound
            if (open) {
                level.levelEvent(null, 1007, pos, 0); // Trapdoor open sound
            } else {
                level.levelEvent(null, 1013, pos, 0); // Trapdoor close sound
            }
            MCAi.LOGGER.debug("Companion {} trapdoor at {}",
                    open ? "opened" : "closed", pos);
        }
    }
}
