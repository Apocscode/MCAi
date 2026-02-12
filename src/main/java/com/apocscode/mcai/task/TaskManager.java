package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionChat;
import com.apocscode.mcai.entity.CompanionEntity;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages a queue of CompanionTasks for a companion entity.
 *
 * The TaskManager is ticked from the CompanionTaskGoal each server tick.
 * It runs the active task, handles transitions, and reports status.
 */
public class TaskManager {

    private final CompanionEntity companion;
    private final Deque<CompanionTask> taskQueue = new ArrayDeque<>();
    private CompanionTask activeTask;
    private int progressAnnounceTicks = 0;
    private int lastAnnouncedPercent = -1;

    public TaskManager(CompanionEntity companion) {
        this.companion = companion;
    }

    /**
     * Add a task to the end of the queue.
     */
    public void queueTask(CompanionTask task) {
        taskQueue.addLast(task);
        MCAi.LOGGER.info("Task queued: {} (queue size: {})", task.getDescription(), taskQueue.size());
    }

    /**
     * Add a task to the front of the queue (high priority).
     */
    public void queueTaskFirst(CompanionTask task) {
        taskQueue.addFirst(task);
        MCAi.LOGGER.info("Priority task queued: {} (queue size: {})", task.getDescription(), taskQueue.size());
    }

    /**
     * Called every server tick by CompanionTaskGoal.
     */
    public void tick() {
        // If active task is done, clean up and announce
        if (activeTask != null && activeTask.isDone()) {
            activeTask.cleanup();
            if (activeTask.getStatus() == CompanionTask.Status.COMPLETED) {
                companion.getChat().say(CompanionChat.Category.TASK,
                        "Done: " + activeTask.getDescription());
            } else {
                String reason = activeTask.getFailReason() != null
                        ? activeTask.getFailReason() : "unknown error";
                companion.getChat().say(CompanionChat.Category.TASK,
                        "Failed: " + activeTask.getDescription() + " — " + reason);
            }
            activeTask = null;
        }

        // Start next task if idle
        if (activeTask == null && !taskQueue.isEmpty()) {
            activeTask = taskQueue.pollFirst();
            progressAnnounceTicks = 0;
            lastAnnouncedPercent = -1;
            companion.getChat().say(CompanionChat.Category.TASK,
                    "Starting: " + activeTask.getDescription());
        }

        // Tick active task
        if (activeTask != null) {
            activeTask.doTick();

            // Periodic progress announcements (every 10 seconds)
            progressAnnounceTicks++;
            if (progressAnnounceTicks >= 200) {
                progressAnnounceTicks = 0;
                int percent = activeTask.getProgressPercent();
                if (percent >= 0 && percent != lastAnnouncedPercent) {
                    lastAnnouncedPercent = percent;
                    companion.getChat().say(CompanionChat.Category.TASK,
                            activeTask.getTaskName() + ": " + percent + "% done");
                }
            }
        }
    }

    /**
     * Whether there is an active task or queued tasks.
     */
    public boolean hasTasks() {
        return activeTask != null || !taskQueue.isEmpty();
    }

    /**
     * Whether there is a currently running task.
     */
    public boolean isIdle() {
        return activeTask == null;
    }

    /**
     * Get the active task description, or null if idle.
     */
    public String getActiveTaskDescription() {
        return activeTask != null ? activeTask.getDescription() : null;
    }

    /**
     * Get number of tasks in queue (not counting active).
     */
    public int getQueueSize() {
        return taskQueue.size();
    }

    /**
     * Cancel the active task and clear the queue.
     */
    public void cancelAll() {
        if (activeTask != null) {
            activeTask.cleanup();
            activeTask = null;
        }
        taskQueue.clear();
        companion.getNavigation().stop();
    }

    /**
     * Cancel only the active task (keeps queued tasks).
     */
    public void cancelActive() {
        if (activeTask != null) {
            activeTask.cleanup();
            activeTask = null;
        }
    }

    /**
     * Get a status summary string.
     */
    public String getStatusSummary() {
        if (activeTask == null && taskQueue.isEmpty()) {
            return "Idle — no tasks queued.";
        }
        StringBuilder sb = new StringBuilder();
        if (activeTask != null) {
            sb.append("Active: ").append(activeTask.getDescription());
            int percent = activeTask.getProgressPercent();
            if (percent >= 0) {
                sb.append(" [").append(percent).append("%]");
            }
            sb.append(" (").append(activeTask.getStatus()).append(")");
        }
        if (!taskQueue.isEmpty()) {
            sb.append(" | ").append(taskQueue.size()).append(" task(s) queued");
        }
        return sb.toString();
    }
}
