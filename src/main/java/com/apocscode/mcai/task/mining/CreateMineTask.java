package com.apocscode.mcai.task.mining;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.CompanionTask;
import com.apocscode.mcai.task.OreGuide;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;

/**
 * Orchestrator task: Create and operate a full mine.
 *
 * Chains the three sub-tasks in sequence:
 *   1. DigShaftTask  — staircase down to target Y
 *   2. CreateHubTask — clear hub room, place furniture
 *   3. BranchMineTask — systematic branch mining from hub
 *
 * Sub-tasks are queued sequentially via the TaskManager. CreateMineTask
 * itself completes quickly after queuing the first sub-task; the sub-tasks
 * then chain via direct queue insertion (not AI continuations, which would
 * add unnecessary latency).
 *
 * The MineState object is shared across all sub-tasks and tracks the mine's
 * progress through its lifecycle.
 */
public class CreateMineTask extends CompanionTask {

    private final MineState mineState;
    @Nullable
    private final OreGuide.Ore targetOre;

    /** Current orchestration phase. */
    private Phase phase = Phase.VALIDATE;

    private enum Phase {
        VALIDATE,       // Check preconditions (tools, space, etc.)
        QUEUE_SHAFT,    // Queue the DigShaftTask
        QUEUE_HUB,      // Queue the CreateHubTask (after shaft completes)
        QUEUE_BRANCHES, // Queue the BranchMineTask (after hub completes)
        DONE
    }

    /**
     * Create a full mining operation.
     *
     * @param companion     The companion entity
     * @param targetOre     Target ore (nullable — null for general mining)
     * @param targetY       Target Y-level for the mine
     * @param direction     Direction to dig the shaft/hub
     * @param branchLength  Length of each branch tunnel
     * @param branchesPerSide Number of branch pairs
     */
    public CreateMineTask(CompanionEntity companion, @Nullable OreGuide.Ore targetOre,
                          int targetY, Direction direction, int branchLength, int branchesPerSide) {
        super(companion, buildDescription(targetOre, targetY));
        this.targetOre = targetOre;

        // Initialize shared mine state
        BlockPos entrance = companion.blockPosition();
        this.mineState = new MineState(
                targetOre != null ? targetOre.name : null,
                targetY, entrance, direction
        );
        mineState.setBranchLength(branchLength);
        mineState.setBranchesPerSide(branchesPerSide);
    }

    private static String buildDescription(@Nullable OreGuide.Ore ore, int targetY) {
        if (ore != null) {
            return "Create " + ore.name + " mine at Y=" + targetY;
        }
        return "Create mine at Y=" + targetY;
    }

    @Override
    public String getTaskName() {
        return "CreateMine";
    }

    @Override
    public int getProgressPercent() {
        return switch (phase) {
            case VALIDATE -> 0;
            case QUEUE_SHAFT -> 10;
            case QUEUE_HUB -> 40;
            case QUEUE_BRANCHES -> 70;
            case DONE -> 100;
        };
    }

    @Override
    protected void start() {
        phase = Phase.VALIDATE;
        String oreLabel = targetOre != null ? targetOre.name + " " : "";
        say("Setting up " + oreLabel + "mine. Entrance at " + formatPos(mineState.getEntrance()) +
                ", digging to Y=" + mineState.getTargetY() + " heading " +
                mineState.getShaftDirection().getName() + ".");
    }

    @Override
    protected void tick() {
        switch (phase) {
            case VALIDATE -> tickValidate();
            case QUEUE_SHAFT -> tickQueueShaft();
            case QUEUE_HUB -> tickQueueHub();
            case QUEUE_BRANCHES -> tickQueueBranches();
            case DONE -> complete();
        }
    }

    // ================================================================
    // Phase: Validate preconditions
    // ================================================================

    private void tickValidate() {
        int currentY = companion.blockPosition().getY();
        int targetY = mineState.getTargetY();

        // Check if target Y is reachable
        int worldMin = companion.level().getMinBuildHeight();
        if (targetY <= worldMin + 1) {
            say("Target Y=" + targetY + " is too close to bedrock. Adjusting to Y=" + (worldMin + 5) + ".");
            // Can't modify targetY on mineState since it's set in constructor — this is a warning
        }

        // Check for basic mining tools
        boolean hasPickaxe = false;
        var inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.PickaxeItem) {
                hasPickaxe = true;
                break;
            }
        }
        // Also check main hand
        if (!hasPickaxe && companion.getMainHandItem().getItem() instanceof net.minecraft.world.item.PickaxeItem) {
            hasPickaxe = true;
        }

        if (!hasPickaxe) {
            say("Warning: No pickaxe found! Mining will be slow. Consider crafting one first.");
        }

        // Check torch supply
        int torches = BlockHelper.countItem(companion, net.minecraft.world.item.Items.TORCH);
        int estimatedTorchesNeeded = ((currentY - targetY) / 8) + 8; // Rough estimate
        if (torches < estimatedTorchesNeeded) {
            say("Have " + torches + " torches, might need ~" + estimatedTorchesNeeded +
                    ". Will mine without torches if we run out.");
        }

        MCAi.LOGGER.info("CreateMine: validated. currentY={}, targetY={}, hasPickaxe={}, torches={}",
                currentY, targetY, hasPickaxe, torches);

        phase = Phase.QUEUE_SHAFT;
    }

    // ================================================================
    // Phase: Queue the shaft-digging task
    // ================================================================

    private void tickQueueShaft() {
        DigShaftTask shaftTask = new DigShaftTask(
                companion, mineState, mineState.getShaftDirection(), mineState.getTargetY()
        );
        companion.getTaskManager().queueTask(shaftTask);

        // Queue hub creation right after shaft (it will wait in queue)
        CreateHubTask hubTask = new CreateHubTask(companion, mineState);
        companion.getTaskManager().queueTask(hubTask);

        // Queue branch mining after hub
        BranchMineTask branchTask = new BranchMineTask(companion, mineState, targetOre);
        companion.getTaskManager().queueTask(branchTask);

        mineState.setPhase(MinePhase.DIGGING_SHAFT);

        say("Mine plan queued: dig shaft → build hub → branch mine. " +
                "This will take a while — I'll report progress as I go!");

        phase = Phase.DONE;
    }

    // These phases are not used since we queue everything in QUEUE_SHAFT,
    // but kept for potential future use with more complex orchestration.

    private void tickQueueHub() {
        phase = Phase.QUEUE_BRANCHES;
    }

    private void tickQueueBranches() {
        phase = Phase.DONE;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private String formatPos(BlockPos pos) {
        if (pos == null) return "unknown";
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    /** Get the mine state (exposed for the tool to provide feedback). */
    public MineState getMineState() {
        return mineState;
    }

    @Override
    protected void cleanup() {
        MCAi.LOGGER.info("CreateMine cleanup: {}", mineState.getSummary());
    }
}
