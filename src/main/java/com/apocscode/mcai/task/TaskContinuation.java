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
                "\n\nFollow the next steps above. Call each tool with the parameters shown. " +
                "Each tool automatically skips if you already have enough materials. " +
                "craft_item will auto-pull materials from your inventory and nearby chests.";
    }

    /**
     * Build the synthetic message sent to the AI when a task FAILS but has a continuation.
     * Gives the AI context to try an alternative approach.
     * Uses strong, repeated instructions so even smaller local models follow them.
     */
    public String buildFailureContinuationMessage(String taskDescription, String failReason) {
        return "[TASK_FAILED] " + taskDescription + " — Reason: " + failReason +
                "\n\nOriginal plan: " + planContext +
                "\nRemaining steps: " + nextSteps +
                "\n\n=== MANDATORY INSTRUCTIONS ===" +
                "\nThe previous step FAILED. You MUST adapt and try an alternative approach." +
                "\nYou MUST call a tool. Do NOT just respond with text." +
                "\n" +
                "\n*** CRITICAL: Do NOT call craft_item again! That would restart the entire plan and create an infinite loop. ***" +
                "\n*** Instead, fix the specific step that failed using the appropriate tool directly. ***" +
                "\n" +
                "\nFallback strategies:" +
                "\n- If mine_ores failed (could not reach ores / no ores found): use strip_mine(ore=X, plan=\"<remaining steps>\") instead — it digs a tunnel at the optimal Y-level." +
                "\n- If smelt_items failed (no furnace/no fuel/no cobblestone): use gather_blocks({\"block\":\"cobblestone\",\"count\":8}) to get furnace materials, or chop_trees for fuel." +
                "\n- If gather_blocks failed: try a larger radius or different location." +
                "\n- If the task timed out or got stuck: retry with adjusted parameters." +
                "\n" +
                "\n*** IMPORTANT: Look at 'Remaining steps' above. You MUST pass those EXACT remaining steps as the 'plan' parameter in your tool call. ***" +
                "\n*** DO NOT call craft_item — use the specific tool for the failed step instead. ***" +
                "\nExample: strip_mine({\"ore\":\"iron\",\"plan\":\"smelt iron_ingot, then craft bucket\"})" +
                "\nThis ensures the crafting chain continues automatically after the fallback task completes." +
                "\nDo NOT give up — find an alternative way to get the materials needed.";
    }
}
