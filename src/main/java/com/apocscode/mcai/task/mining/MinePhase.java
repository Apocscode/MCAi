package com.apocscode.mcai.task.mining;

/**
 * Phases of the Create Mine operation.
 *
 * The mining system moves through these phases sequentially:
 *   INITIALIZING → DIGGING_SHAFT → CREATING_HUB → BRANCH_MINING → DEPOSITING → DEEPENING → COMPLETED
 *
 * PAUSED can be entered from any active phase (e.g., inventory full, hazard encountered).
 */
public enum MinePhase {
    /** Setting up — validating parameters, checking tools, determining target Y-level. */
    INITIALIZING,
    /** Digging a 2×1 staircase shaft down to the target Y-level. */
    DIGGING_SHAFT,
    /** Clearing and furnishing the hub room at the bottom of the shaft. */
    CREATING_HUB,
    /** Systematic branch mining from the hub — the main ore-finding phase. */
    BRANCH_MINING,
    /** Returning to hub to deposit inventory into chests. */
    DEPOSITING,
    /** Digging the shaft deeper to reach the next mining level. */
    DEEPENING,
    /** Temporarily paused (inventory full, needs repair, hazard). */
    PAUSED,
    /** All mining operations complete. */
    COMPLETED;

    /** Whether this phase represents active work (not terminal or paused). */
    public boolean isActive() {
        return this != COMPLETED && this != PAUSED;
    }

    /** Whether this is a terminal state. */
    public boolean isTerminal() {
        return this == COMPLETED;
    }
}
