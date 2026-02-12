package com.apocscode.mcai.task;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;

/**
 * Abstract base for a queued companion task.
 *
 * Tasks represent complex multi-step activities requested via chat
 * (e.g., "plant a 10x10 wheat field", "mine 64 iron ore", "chop 20 oak logs").
 *
 * Lifecycle:
 *   1. Created by an AI tool and queued in the TaskManager
 *   2. tick() called each server tick while active
 *   3. Reports isDone() when complete or failed
 *   4. TaskManager moves to next queued task
 *
 * Tasks handle their own pathfinding, block breaking, placement, and inventory management.
 */
public abstract class CompanionTask {

    public enum Status {
        PENDING,     // Queued, not yet started
        RUNNING,     // Currently executing
        COMPLETED,   // Finished successfully
        FAILED       // Failed (unreachable, missing resources, etc.)
    }

    protected final CompanionEntity companion;
    protected final String description;
    protected Status status = Status.PENDING;
    protected String failReason;
    protected int ticksRunning = 0;
    protected static final int MAX_TICKS = 20 * 60 * 5; // 5 minute timeout

    protected CompanionTask(CompanionEntity companion, String description) {
        this.companion = companion;
        this.description = description;
    }

    protected CompanionTask(CompanionEntity companion) {
        this.companion = companion;
        this.description = null; // subclass provides via getTaskName()
    }

    /**
     * Return a human-readable task name. Override in subclasses for dynamic names.
     */
    public String getTaskName() {
        return description != null ? description : getClass().getSimpleName();
    }

    /**
     * Called once when the task becomes active.
     */
    protected abstract void start();

    /**
     * Called every server tick while the task is active.
     */
    protected abstract void tick();

    /**
     * Clean up when the task ends (success or failure).
     */
    protected abstract void cleanup();

    /**
     * Whether the task is done (completed or failed).
     */
    public boolean isDone() {
        return status == Status.COMPLETED || status == Status.FAILED;
    }

    public Status getStatus() { return status; }
    public String getDescription() { return getTaskName(); }
    public String getFailReason() { return failReason; }
    public int getTicksRunning() { return ticksRunning; }

    /**
     * Override in subclasses to report progress (0-100).
     * Returns -1 if progress is unknown.
     */
    public int getProgressPercent() {
        return -1;
    }

    protected void complete() {
        this.status = Status.COMPLETED;
    }

    protected void fail(String reason) {
        this.status = Status.FAILED;
        this.failReason = reason;
    }

    /**
     * Internal tick wrapper — handles timeout and delegation.
     */
    public final void doTick() {
        if (isDone()) return;

        if (status == Status.PENDING) {
            status = Status.RUNNING;
            start();
            if (isDone()) return;
        }

        ticksRunning++;
        if (ticksRunning >= MAX_TICKS) {
            fail("Task timed out after 5 minutes");
            return;
        }

        tick();
    }

    // ================================================================
    // Utility helpers for subclasses
    // ================================================================

    /**
     * Pathfind the companion to a position. Returns true if path started.
     */
    protected boolean navigateTo(BlockPos pos, double speed) {
        return companion.getNavigation().moveTo(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
    }

    /**
     * Pathfind at default speed (1.0).
     */
    protected boolean navigateTo(BlockPos pos) {
        return navigateTo(pos, 1.0);
    }

    /**
     * Check if companion is within reach of a block position.
     */
    protected boolean isInReach(BlockPos pos, double reach) {
        double dist = companion.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return dist < reach * reach;
    }

    /**
     * Announce to the owner via chat.
     */
    protected void say(String message) {
        companion.getChat().say(
                com.apocscode.mcai.entity.CompanionChat.Category.TASK, message);
    }
}
