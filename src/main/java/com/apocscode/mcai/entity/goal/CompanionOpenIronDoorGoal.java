package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * AI Goal: Open iron doors by pressing nearby buttons or pulling levers.
 *
 * When the companion's path is blocked by an iron door (or any door that
 * can't be opened by hand), this goal scans for adjacent buttons/levers
 * and activates them.
 *
 * Handles:
 * - Vanilla iron doors
 * - Modded doors with canOpenByHand() = false
 * - Buttons (stone, wooden, polished_blackstone, any ButtonBlock subclass)
 * - Levers
 * - Pressure plates are handled automatically by walking on them
 */
public class CompanionOpenIronDoorGoal extends Goal {

    private final CompanionEntity companion;
    private BlockPos doorPos;
    private BlockPos activatorPos; // Button or lever position
    private int cooldown = 0;
    private int actionTimer = 0;
    private static final int COOLDOWN_TICKS = 40; // Don't spam buttons
    private static final int MAX_ACTION_TICKS = 60; // Give up after 3 seconds

    public CompanionOpenIronDoorGoal(CompanionEntity companion) {
        this.companion = companion;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }

        // Check if we're near a closed iron door that blocks our path
        Level level = companion.level();
        BlockPos pos = companion.blockPosition();

        // Scan around companion for a closed iron-type door within 2 blocks
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(checkPos);
                    if (isIronTypeDoor(state) && !isDoorOpen(state)) {
                        doorPos = checkPos;
                        activatorPos = findActivator(level, checkPos);
                        if (activatorPos != null) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (actionTimer > MAX_ACTION_TICKS) return false;
        if (doorPos == null) return false;

        BlockState doorState = companion.level().getBlockState(doorPos);
        // Stop if door is now open or gone
        if (!isIronTypeDoor(doorState) || isDoorOpen(doorState)) return false;

        return true;
    }

    @Override
    public void start() {
        actionTimer = 0;
    }

    @Override
    public void tick() {
        actionTimer++;

        if (activatorPos == null) {
            stop();
            return;
        }

        // Look at the activator
        companion.getLookControl().setLookAt(
                activatorPos.getX() + 0.5, activatorPos.getY() + 0.5, activatorPos.getZ() + 0.5);

        // Check if we're close enough to press the button/lever
        double distSq = companion.distanceToSqr(
                activatorPos.getX() + 0.5, activatorPos.getY() + 0.5, activatorPos.getZ() + 0.5);

        if (distSq <= 4.0) { // Within 2 blocks
            pressActivator();
            cooldown = COOLDOWN_TICKS;
            stop();
        } else {
            // Walk toward the activator
            companion.getNavigation().moveTo(
                    activatorPos.getX() + 0.5, activatorPos.getY(), activatorPos.getZ() + 0.5, 1.0);
        }
    }

    @Override
    public void stop() {
        doorPos = null;
        activatorPos = null;
        actionTimer = 0;
    }

    /**
     * Check if a block is an iron-type door (any door that can't be opened by hand).
     * This covers vanilla iron doors and modded doors with canOpenByHand() = false.
     */
    private boolean isIronTypeDoor(BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock doorBlock)) return false;
        // In 1.21.1, DoorBlock stores its BlockSetType — doors with canOpenByHand()=false are "iron-type"
        try {
            BlockSetType type = doorBlock.type();
            return !type.canOpenByHand();
        } catch (Exception e) {
            // Fallback: check if it's literally an iron door
            return state.getBlock() == Blocks.IRON_DOOR;
        }
    }

    /**
     * Check if a door is open.
     */
    private boolean isDoorOpen(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return state.getValue(DoorBlock.OPEN);
        }
        return false;
    }

    /**
     * Find a button or lever adjacent to the door (within 2 blocks).
     * Searches around the door position and the block above it (doors are 2 blocks tall).
     */
    @Nullable
    private BlockPos findActivator(Level level, BlockPos doorPos) {
        // Search around both the lower and upper door block
        BlockPos[] doorBlocks = { doorPos, doorPos.above() };

        for (BlockPos dp : doorBlocks) {
            // Check all 6 directions + diagonals
            for (Direction dir : Direction.values()) {
                BlockPos checkPos = dp.relative(dir);
                BlockState state = level.getBlockState(checkPos);
                if (isActivator(state)) {
                    return checkPos;
                }
            }
            // Also check 2-block range for wall-mounted buttons
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos checkPos = dp.relative(dir, 2);
                BlockState state = level.getBlockState(checkPos);
                if (isActivator(state)) {
                    return checkPos;
                }
                // Check one block up/down at distance 2
                state = level.getBlockState(checkPos.above());
                if (isActivator(state)) {
                    return checkPos.above();
                }
                state = level.getBlockState(checkPos.below());
                if (isActivator(state)) {
                    return checkPos.below();
                }
            }
        }
        return null;
    }

    /**
     * Check if a block is a button or lever (any type — vanilla or modded).
     */
    private boolean isActivator(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ButtonBlock || block instanceof LeverBlock;
    }

    /**
     * Press the button or pull the lever.
     */
    private void pressActivator() {
        Level level = companion.level();
        BlockState state = level.getBlockState(activatorPos);

        if (state.getBlock() instanceof ButtonBlock || state.getBlock() instanceof LeverBlock) {
            // Need a Player for useWithoutItem — use the companion's owner
            net.minecraft.world.entity.player.Player owner = companion.getOwner();
            if (owner == null) {
                MCAi.LOGGER.warn("Companion has no owner — can't press button at {}", activatorPos);
                return;
            }
            // Safety: owner must be nearby (same dimension checked by getOwner(), but also distance)
            if (owner.distanceToSqr(companion) > 256) { // 16 blocks max
                MCAi.LOGGER.debug("Owner too far to proxy button press at {}", activatorPos);
                return;
            }

            // Simulate a right-click interaction on the block
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(activatorPos),
                    Direction.NORTH,
                    activatorPos,
                    false);
            state.useWithoutItem(level, owner, hit);

            MCAi.LOGGER.info("Companion pressed {} at {} to open iron door at {}",
                    state.getBlock().getName().getString(), activatorPos, doorPos);
        }
    }
}
