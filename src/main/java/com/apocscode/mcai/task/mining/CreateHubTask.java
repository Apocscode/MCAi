package com.apocscode.mcai.task.mining;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.CompanionTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Task: Create a hub room at the bottom of the shaft.
 *
 * Clears a 7×5×4 room (7 long along shaft direction, 5 wide, 4 tall)
 * and furnishes it with:
 *   - 2 chests (for item deposit)
 *   - 1 crafting table
 *   - 1 furnace
 *   - 4 torches (one on each wall)
 *   - Cobblestone floor if ground is missing
 *
 * The hub serves as the staging area for branch mining operations.
 * The main corridor extends from the hub in the shaft direction,
 * and branches extend perpendicular to either side.
 *
 * Layout (top-down, shaft enters from bottom):
 *
 *   +-------+
 *   | T   T |
 *   | C F   |     C = Chest, F = Furnace, W = Crafting Table
 *   |   +   |     T = Torch, + = center
 *   | C W   |
 *   | T   T |
 *   +---v---+
 *       shaft entrance
 */
public class CreateHubTask extends CompanionTask {

    private final MineState mineState;

    /** Hub dimensions. */
    private static final int HUB_LENGTH = 7;   // Along shaft direction
    private static final int HUB_WIDTH = 5;    // Perpendicular
    private static final int HUB_HEIGHT = 4;   // Floor to ceiling

    /** Current sub-phase. */
    private Phase phase = Phase.NAVIGATE;

    /** Progress tracking for clearing. */
    private int clearX = 0, clearY = 0, clearZ = 0;

    /** Center of the hub room. */
    private BlockPos hubCenter;

    /** Corner of the hub (min x, min y, min z relative to world). */
    private BlockPos hubCorner;

    /** Total blocks cleared. */
    private int blocksCleared = 0;
    private int totalBlocksToCheck;

    private int stuckTimer = 0;
    private static final int STUCK_TIMEOUT = 80;

    private enum Phase {
        NAVIGATE,       // Move to hub position
        CLEAR_ROOM,     // Break all blocks in the room volume
        PLACE_FLOOR,    // Ensure solid floor
        PLACE_FURNITURE,// Place chests, furnace, crafting table
        PLACE_TORCHES,  // Light up the room
        DONE
    }

    public CreateHubTask(CompanionEntity companion, MineState mineState) {
        super(companion, "Create hub room at Y=" + 
              (mineState.getShaftBottom() != null ? mineState.getShaftBottom().getY() : "?"));
        this.mineState = mineState;
    }

    @Override
    public String getTaskName() {
        return "CreateHub";
    }

    @Override
    public int getProgressPercent() {
        if (totalBlocksToCheck <= 0) return 0;
        return switch (phase) {
            case NAVIGATE -> 0;
            case CLEAR_ROOM -> Math.min(50, (blocksCleared * 50) / totalBlocksToCheck);
            case PLACE_FLOOR -> 60;
            case PLACE_FURNITURE -> 80;
            case PLACE_TORCHES -> 90;
            case DONE -> 100;
        };
    }

    @Override
    protected void start() {
        BlockPos shaftBottom = mineState.getShaftBottom();
        if (shaftBottom == null) {
            fail("No shaft bottom position — shaft must be dug first.");
            return;
        }

        Direction dir = mineState.getShaftDirection();

        // Hub center: 3 blocks forward from shaft bottom along shaft direction
        hubCenter = shaftBottom.relative(dir, HUB_LENGTH / 2 + 1);

        // Calculate the corner (min corner of the room volume)
        // The room is centered on hubCenter, extending HUB_LENGTH along dir, HUB_WIDTH perpendicular
        Direction left = dir.getCounterClockWise();
        hubCorner = hubCenter
                .relative(dir.getOpposite(), HUB_LENGTH / 2)
                .relative(left, HUB_WIDTH / 2);

        totalBlocksToCheck = HUB_LENGTH * HUB_WIDTH * HUB_HEIGHT;
        clearX = 0;
        clearY = 0;
        clearZ = 0;

        // Register the level in mine state
        mineState.addLevel(hubCenter.getY(), hubCenter);

        say("Creating hub room at Y=" + hubCenter.getY() + ". Clearing " +
                HUB_LENGTH + "×" + HUB_WIDTH + "×" + HUB_HEIGHT + " area...");

        phase = Phase.NAVIGATE;
    }

    @Override
    protected void tick() {
        switch (phase) {
            case NAVIGATE -> tickNavigate();
            case CLEAR_ROOM -> tickClearRoom();
            case PLACE_FLOOR -> tickPlaceFloor();
            case PLACE_FURNITURE -> tickPlaceFurniture();
            case PLACE_TORCHES -> tickPlaceTorches();
            case DONE -> {
                MineState.MineLevel level = mineState.getActiveLevel();
                if (level != null) level.setHubBuilt(true);
                mineState.addBlocksBroken(blocksCleared);
                // Update mine memory with hub center position (v2 format)
                updateMineMemoryWithHub();
                complete();
            }
        }
    }

    // ================================================================
    // Phase: Navigate to hub area
    // ================================================================

    private void tickNavigate() {
        if (hubCenter == null) {
            fail("Hub center not calculated.");
            return;
        }

        if (isInReach(hubCenter, 5.0)) {
            stuckTimer = 0;
            phase = Phase.CLEAR_ROOM;
            return;
        }

        // Navigate toward the shaft bottom (which is at the hub entrance)
        BlockPos target = mineState.getShaftBottom() != null ? mineState.getShaftBottom() : hubCenter;
        navigateTo(target);
        stuckTimer++;
        if (stuckTimer > STUCK_TIMEOUT) {
            MCAi.LOGGER.warn("CreateHub: stuck navigating, proceeding anyway");
            stuckTimer = 0;
            phase = Phase.CLEAR_ROOM;
        }
    }

    // ================================================================
    // Phase: Clear the room volume
    // ================================================================

    private void tickClearRoom() {
        Level level = companion.level();
        Direction dir = mineState.getShaftDirection();
        Direction right = dir.getClockWise();

        // Process a batch of blocks per tick (up to 4 for performance)
        int batchSize = 4;
        for (int batch = 0; batch < batchSize; batch++) {
            if (clearY >= HUB_HEIGHT) {
                // Done clearing
                say("Hub room cleared! " + blocksCleared + " blocks removed.");
                phase = Phase.PLACE_FLOOR;
                return;
            }

            // Calculate world position from local coordinates
            // Local X = along shaft dir, Local Z = along right dir, Local Y = up
            BlockPos worldPos = hubCorner
                    .relative(dir, clearX)
                    .relative(right, clearZ)
                    .above(clearY);

            BlockState state = level.getBlockState(worldPos);
            if (!state.isAir() && state.getBlock() != Blocks.BEDROCK) {
                if (BlockHelper.isSafeToMine(level, worldPos)) {
                    companion.equipBestToolForBlock(state);
                    BlockHelper.breakBlock(companion, worldPos);
                    blocksCleared++;
                }
            }

            // Advance to next position (X → Z → Y)
            clearX++;
            if (clearX >= HUB_LENGTH) {
                clearX = 0;
                clearZ++;
                if (clearZ >= HUB_WIDTH) {
                    clearZ = 0;
                    clearY++;
                }
            }
        }
    }

    // ================================================================
    // Phase: Ensure solid floor
    // ================================================================

    private void tickPlaceFloor() {
        Level level = companion.level();
        Direction dir = mineState.getShaftDirection();
        Direction right = dir.getClockWise();

        for (int x = 0; x < HUB_LENGTH; x++) {
            for (int z = 0; z < HUB_WIDTH; z++) {
                BlockPos floorPos = hubCorner
                        .relative(dir, x)
                        .relative(right, z)
                        .below(); // One below the room's Y=0

                BlockState state = level.getBlockState(floorPos);
                if (state.isAir() || state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                    BlockHelper.placeBlock(companion, floorPos, Blocks.COBBLESTONE);
                }
            }
        }
        phase = Phase.PLACE_FURNITURE;
    }

    // ================================================================
    // Phase: Place chests, furnace, crafting table
    // ================================================================

    private void tickPlaceFurniture() {
        Direction dir = mineState.getShaftDirection();
        Direction left = dir.getCounterClockWise();
        Direction right = dir.getClockWise();
        MineState.MineLevel mineLevel = mineState.getActiveLevel();

        // Place 2 chests on the left wall
        BlockPos chest1Pos = hubCenter.relative(left, HUB_WIDTH / 2 - 1).relative(dir.getOpposite(), 1);
        BlockPos chest2Pos = hubCenter.relative(left, HUB_WIDTH / 2 - 1).relative(dir, 1);
        placeFurniture(chest1Pos, Blocks.CHEST, mineLevel);
        placeFurniture(chest2Pos, Blocks.CHEST, mineLevel);

        // Furnace on the left wall between chests
        BlockPos furnacePos = hubCenter.relative(left, HUB_WIDTH / 2 - 1);
        placeFurniture(furnacePos, Blocks.FURNACE, mineLevel);

        // Crafting table on the right wall
        BlockPos craftPos = hubCenter.relative(right, HUB_WIDTH / 2 - 1);
        placeFurniture(craftPos, Blocks.CRAFTING_TABLE, mineLevel);

        say("Placed hub furniture (chests, furnace, crafting table).");
        phase = Phase.PLACE_TORCHES;
    }

    private void placeFurniture(BlockPos pos, net.minecraft.world.level.block.Block block,
                                 MineState.MineLevel mineLevel) {
        if (BlockHelper.placeBlock(companion, pos, block)) {
            if (mineLevel != null) {
                mineLevel.getFurniturePositions().add(pos);
            }
        }
    }

    // ================================================================
    // Phase: Place torches
    // ================================================================

    private void tickPlaceTorches() {
        // Ensure we have torches — craft from coal/sticks if needed
        if (BlockHelper.countItem(companion, Items.TORCH) < 4) {
            int crafted = BlockHelper.tryAutoCraftTorches(companion, 4);
            if (crafted > 0) {
                say("Crafted " + crafted + " torches for the hub.");
            }
        }

        Direction dir = mineState.getShaftDirection();
        Direction left = dir.getCounterClockWise();
        Direction right = dir.getClockWise();

        // 4 torches — one near each corner at head height
        BlockPos[] torchPositions = {
                hubCenter.relative(dir, HUB_LENGTH / 2 - 1).relative(left, HUB_WIDTH / 2 - 1).above(),
                hubCenter.relative(dir, HUB_LENGTH / 2 - 1).relative(right, HUB_WIDTH / 2 - 1).above(),
                hubCenter.relative(dir.getOpposite(), HUB_LENGTH / 2 - 1).relative(left, HUB_WIDTH / 2 - 1).above(),
                hubCenter.relative(dir.getOpposite(), HUB_LENGTH / 2 - 1).relative(right, HUB_WIDTH / 2 - 1).above()
        };

        int placed = 0;
        for (BlockPos tp : torchPositions) {
            if (BlockHelper.placeTorch(companion, tp)) {
                placed++;
            }
        }
        mineState.addTorchesPlaced(placed);

        say("Hub complete! " + placed + " torches placed.");
        phase = Phase.DONE;
    }

    @Override
    protected void cleanup() {
        MCAi.LOGGER.info("CreateHub cleanup: cleared {} blocks, hub at {}",
                blocksCleared, hubCenter);
    }

    // ================================================================
    // Torch Crafting
    // ================================================================

    /**
     * Update the mine memory with the hub center position so resume can use it.
     * Reads the existing memory, appends hub center coords (v2 format).
     */
    private void updateMineMemoryWithHub() {
        if (hubCenter == null) return;
        String oreName = mineState.getTargetOre();
        String oreKey = oreName != null ? oreName.toLowerCase() : "general";
        String memoryKey = "mine_" + oreKey;

        String existing = companion.getMemory().getFact(memoryKey);
        if (existing == null || existing.isEmpty()) return;

        // Check if hub center is already saved (v2 format has 6+ pipe-separated parts)
        String[] parts = existing.split("\\|");
        if (parts.length >= 6) {
            MCAi.LOGGER.info("CreateHub: mine memory already has hub data, not overwriting");
            return;
        }

        // Append hub center to existing memory
        String updated = existing + "|" + hubCenter.getX() + "," + hubCenter.getY() + "," + hubCenter.getZ();
        companion.getMemory().setFact(memoryKey, updated);
        MCAi.LOGGER.info("CreateHub: updated mine memory with hubCenter={}", hubCenter);
    }
}
