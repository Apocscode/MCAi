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
                "\n\nContinue executing the plan. Use transfer_items and craft_item as needed. " +
                "Do NOT queue another gathering task — the resources should already be collected.";
    }
}
