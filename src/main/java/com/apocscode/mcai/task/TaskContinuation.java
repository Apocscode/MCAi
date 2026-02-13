package com.apocscode.mcai.task;

import java.util.UUID;

/**
 * Represents a continuation plan that should execute after a task completes.
 * When attached to a CompanionTask, the TaskManager will trigger an AI follow-up
 * chat when the task finishes, allowing the AI to continue multi-step plans
 * (e.g., gather → transfer → craft).
 *
 * @param ownerUUID   The UUID of the player who initiated the plan
 * @param planContext A description of what the AI was trying to accomplish
 *                    (e.g. "Craft a diamond pickaxe for the player")
 * @param nextSteps   What the AI should do next after the task completes
 *                    (e.g. "transfer the mined items to the player, then craft the item")
 */
public record TaskContinuation(
        UUID ownerUUID,
        String planContext,
        String nextSteps
) {
    /**
     * Build the synthetic message sent to the AI when continuing.
     * This message replaces the normal user message in the agent loop.
     */
    public String buildContinuationMessage(String taskResult) {
        return "[TASK_COMPLETE] " + taskResult +
                "\n\nOriginal plan: " + planContext +
                "\nNext steps: " + nextSteps +
                "\n\nFollow the next steps EXACTLY as described above. " +
                "Call each tool with the parameters shown. " +
                "craft_item will auto-pull materials from your inventory and nearby chests.";
    }

    /**
     * Build the synthetic message sent to the AI when a task FAILS but has a continuation.
     * Gives the AI context to try an alternative approach.
     */
    public String buildFailureContinuationMessage(String taskDescription, String failReason) {
        return "[TASK_FAILED] " + taskDescription + " — Reason: " + failReason +
                "\n\nOriginal plan: " + planContext +
                "\nRemaining steps: " + nextSteps +
                "\n\nThe previous step FAILED. You must adapt and try an alternative approach:" +
                "\n- If mine_ores failed (could not reach ores / no ores found): use strip_mine instead — it digs a tunnel at the optimal Y-level." +
                "\n- If gather_blocks failed: try a larger radius or different location." +
                "\n- If the task timed out or got stuck: retry with adjusted parameters." +
                "\nThen continue with the remaining steps of the original plan." +
                "\nDo NOT give up — find an alternative way to get the materials needed.";
    }
}
