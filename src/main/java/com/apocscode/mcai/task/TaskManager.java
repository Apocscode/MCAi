package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.CompanionChunkLoader;
import com.apocscode.mcai.ai.AIService;
import com.apocscode.mcai.entity.CompanionChat;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.ItemRoutingHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

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

    // Pending continuation retry — fires after ticksRemaining reaches 0
    private TaskContinuation pendingRetryContinuation;
    private String pendingRetryTaskResult;
    private String pendingRetryCompanionName;
    private int pendingRetryAttempt;
    private int pendingRetryTicksRemaining;

    // Chunk loader — keeps companion's chunk active during tasks
    private final CompanionChunkLoader chunkLoader = new CompanionChunkLoader();
    private int chunkIdleTimer = 0;

    public TaskManager(CompanionEntity companion) {
        this.companion = companion;
    }

    /**
     * Add a task to the end of the queue.
     */
    public void queueTask(CompanionTask task) {
        taskQueue.addLast(task);
        // Clear interaction freeze — player gave a command, so let the companion move
        companion.setOwnerInteracting(false);
        MCAi.LOGGER.info("Task queued: {} (queue size: {})", task.getDescription(), taskQueue.size());
    }

    /**
     * Add a task to the front of the queue (high priority).
     */
    public void queueTaskFirst(CompanionTask task) {
        taskQueue.addFirst(task);
        // Clear interaction freeze — player gave a command, so let the companion move
        companion.setOwnerInteracting(false);
        MCAi.LOGGER.info("Priority task queued: {} (queue size: {})", task.getDescription(), taskQueue.size());
    }

    /**
     * Called every server tick by CompanionTaskGoal.
     */
    public void tick() {
        // If active task is done, clean up and announce
        if (activeTask != null && activeTask.isDone()) {
            activeTask.cleanup();
            String taskDescription = activeTask.getDescription();
            CompanionTask.Status taskStatus = activeTask.getStatus();
            TaskContinuation continuation = activeTask.getContinuation();
            MCAi.LOGGER.info("Task finished: {} — status={}, ticks={}, hasContinuation={}",
                    taskDescription, taskStatus, activeTask.getTicksRunning(),
                    continuation != null);

            if (taskStatus == CompanionTask.Status.COMPLETED) {
                companion.getChat().say(CompanionChat.Category.TASK,
                        "Done: " + taskDescription);
                // Award XP for completing a task
                companion.awardXp(com.apocscode.mcai.entity.CompanionLevelSystem.TASK_COMPLETE_XP);

                // Auto-deposit items ONLY if no continuation is set.
                // When a continuation exists, items are needed for the next step
                // (e.g., mined ore needed for smelting, chopped logs for crafting).
                if (continuation == null && ItemRoutingHelper.hasTaggedStorage(companion)) {
                    int deposited = ItemRoutingHelper.routeAllCompanionItems(companion);
                    if (deposited > 0) {
                        MCAi.LOGGER.info("Auto-deposited {} item(s) to tagged storage after task: {}",
                                deposited, taskDescription);
                        companion.getChat().say(CompanionChat.Category.TASK,
                                "Deposited " + deposited + " item(s) to storage.");
                    }
                }
            } else {
                String reason = activeTask.getFailReason() != null
                        ? activeTask.getFailReason() : "unknown error";
                companion.getChat().say(CompanionChat.Category.TASK,
                        "Failed: " + taskDescription + " — " + reason);
                MCAi.LOGGER.warn("Task FAILED: {} — reason: {}", taskDescription, reason);
            }

            // Fire continuation if one was registered (for multi-step plans)
            if (continuation != null) {
                if (taskStatus == CompanionTask.Status.COMPLETED) {
                    fireContinuation(continuation, taskDescription);
                } else {
                    // Fire failure continuation so the AI can adapt and try alternatives
                    String failReason = activeTask.getFailReason() != null
                            ? activeTask.getFailReason() : "unknown error";
                    fireFailureContinuation(continuation, taskDescription, failReason);
                }
            }

            activeTask = null;

            // Stop chunk loading if no more tasks queued AND no continuation pending.
            // If a continuation was fired, keep chunks loaded — the AI will queue a new task.
            if (taskQueue.isEmpty() && continuation == null && chunkLoader.isLoading()) {
                chunkLoader.stopLoading();
            }
        }

        // Start next task if idle
        if (activeTask == null && !taskQueue.isEmpty()) {
            activeTask = taskQueue.pollFirst();
            progressAnnounceTicks = 0;
            lastAnnouncedPercent = -1;
            MCAi.LOGGER.info("Task starting: {} (remaining in queue: {})",
                    activeTask.getDescription(), taskQueue.size());
            companion.getChat().say(CompanionChat.Category.TASK,
                    "Starting: " + activeTask.getDescription());

            // Start chunk loading so companion stays active if player walks away
            if (!chunkLoader.isLoading()) {
                chunkLoader.startLoading(companion);
            }
        }

        // Tick active task
        if (activeTask != null) {
            activeTask.doTick();

            // Update chunk loading position if companion moved to a new chunk
            if (chunkLoader.isLoading()) {
                chunkLoader.updatePosition(companion);
            }

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

        // Tick pending continuation retry countdown (server-tick based, pauses with game)
        if (pendingRetryContinuation != null) {
            pendingRetryTicksRemaining--;
            if (pendingRetryTicksRemaining <= 0) {
                MCAi.LOGGER.info("Pending continuation retry firing (attempt {})", pendingRetryAttempt + 1);
                TaskContinuation cont = pendingRetryContinuation;
                String result = pendingRetryTaskResult;
                String name = pendingRetryCompanionName;
                int attempt = pendingRetryAttempt;
                // Clear before firing to prevent double-fire
                pendingRetryContinuation = null;
                pendingRetryTaskResult = null;
                pendingRetryCompanionName = null;

                Player owner = companion.getOwner();
                if (owner instanceof ServerPlayer serverPlayer) {
                    AIService.continueAfterTask(cont, result, serverPlayer, name);
                } else {
                    MCAi.LOGGER.warn("Cannot fire pending retry — owner not online");
                }
            }
        }

        // Safety: release chunk loading if idle with no tasks or continuations pending
        // This catches cases where a continuation fires but the AI fails to queue a new task.
        if (chunkLoader.isLoading() && activeTask == null && taskQueue.isEmpty()
                && pendingRetryContinuation == null) {
            chunkIdleTimer++;
            if (chunkIdleTimer >= 1200) { // 60 seconds idle safety timeout
                MCAi.LOGGER.warn("Chunk loading safety timeout — idle for 60s with no tasks, releasing");
                chunkLoader.stopLoading();
                chunkIdleTimer = 0;
            }
        } else {
            chunkIdleTimer = 0;
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
     * Get the active task (for continuation injection). Returns null if idle.
     */
    public CompanionTask peekActiveTask() {
        return activeTask;
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
        if (chunkLoader.isLoading()) {
            chunkLoader.stopLoading();
        }
    }

    /**
     * Cancel only the active task (keeps queued tasks).
     */
    public void cancelActive() {
        if (activeTask != null) {
            activeTask.cleanup();
            activeTask = null;
        }
        // Stop chunk loading if nothing else is queued
        if (taskQueue.isEmpty() && chunkLoader.isLoading()) {
            chunkLoader.stopLoading();
        }
    }

    /**
     * Schedule a pending continuation retry. The retry fires after the given
     * number of server ticks (which pause when the game pauses).
     * This is the mechanism that survives game pauses and rate-limit cooldowns.
     *
     * @param continuation  The continuation to retry
     * @param taskResult    The original task result string
     * @param companionName The companion's display name
     * @param attempt       The retry attempt number (0-based)
     * @param delayTicks    Number of server ticks to wait before retrying
     */
    public void setPendingRetry(TaskContinuation continuation, String taskResult,
                                 String companionName, int attempt, int delayTicks) {
        this.pendingRetryContinuation = continuation;
        this.pendingRetryTaskResult = taskResult;
        this.pendingRetryCompanionName = companionName;
        this.pendingRetryAttempt = attempt;
        this.pendingRetryTicksRemaining = delayTicks;
        MCAi.LOGGER.info("Pending continuation retry scheduled: attempt={}, delay={}t ({}s)",
                attempt + 1, delayTicks, delayTicks / 20);
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

    /**
     * Fire a task continuation — either deterministically (direct tool call) or via AI.
     *
     * For SUCCESS continuations: parse the nextSteps string for structured tool calls
     * like "Call tool_name({...})". If parseable, execute the tool directly without
     * involving the AI — this is faster and immune to rate limits or LLM mistakes.
     * Falls back to AI if the format is unrecognized.
     *
     * For FAILURE continuations: always use AI — it needs to reason about alternatives.
     */
    private void fireContinuation(TaskContinuation continuation, String taskDescription) {
        Player owner = companion.getOwner();
        if (!(owner instanceof ServerPlayer serverPlayer)) {
            MCAi.LOGGER.warn("Cannot fire task continuation — owner not online");
            return;
        }

        String companionName = companion.getCompanionName();
        MCAi.LOGGER.info("Firing task continuation for '{}': plan='{}'",
                taskDescription, continuation.planContext());

        // Try deterministic execution first — parse "Call tool_name({...})" from nextSteps
        String nextSteps = continuation.nextSteps();
        if (nextSteps != null && tryDeterministicContinuation(nextSteps, serverPlayer, companionName, taskDescription)) {
            return; // Successfully executed without AI
        }

        // Fall back to AI
        MCAi.LOGGER.info("Deterministic continuation not possible, falling back to AI");
        companion.getChat().say(CompanionChat.Category.TASK,
                "Continuing the plan...");

        AIService.continueAfterTask(continuation, "Completed: " + taskDescription,
                serverPlayer, companionName);
    }



    /**
     * Try to execute a continuation deterministically by parsing the tool call from nextSteps.
     * Returns true if successful (tool was called), false if the format couldn't be parsed.
     */
    private boolean tryDeterministicContinuation(String nextSteps, ServerPlayer player,
                                                   String companionName, String taskDescription) {
        // nextSteps format: "Call tool_name({\"arg\":\"val\",...})"
        // or for final crafts: "Call craft_item({\"item\":\"iron_pickaxe\",\"count\":1}) — all materials should be gathered now."
        String trimmed = nextSteps.trim();

        // Strip trailing commentary after the closing paren
        // e.g., "Call craft_item({...}) — all materials should be gathered now."
        int callStart = trimmed.indexOf("Call ");
        if (callStart < 0) return false;
        trimmed = trimmed.substring(callStart);

        if (!trimmed.startsWith("Call ")) return false;

        int parenOpen = trimmed.indexOf('(');
        if (parenOpen < 0) return false;

        String toolName = trimmed.substring(5, parenOpen).trim(); // "Call " = 5 chars
        if (toolName.isEmpty()) return false;

        // Extract the JSON args — find the matching closing paren
        // The args are JSON inside parens: tool_name({...json...})
        String argsStr = null;
        int braceDepth = 0;
        int jsonStart = -1;
        for (int i = parenOpen + 1; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '{' && jsonStart < 0) jsonStart = i;
            if (c == '{') braceDepth++;
            if (c == '}') braceDepth--;
            if (braceDepth == 0 && jsonStart >= 0) {
                argsStr = trimmed.substring(jsonStart, i + 1);
                break;
            }
        }

        if (argsStr == null) return false;

        MCAi.LOGGER.info("Deterministic continuation: tool='{}', args='{}'", toolName, argsStr);

        // Parse args JSON
        com.google.gson.JsonObject args;
        try {
            args = com.google.gson.JsonParser.parseString(argsStr).getAsJsonObject();
        } catch (Exception e) {
            MCAi.LOGGER.warn("Failed to parse tool args JSON: {}", e.getMessage());
            return false;
        }

        // Verify tool exists
        com.apocscode.mcai.ai.tool.AiTool tool = com.apocscode.mcai.ai.tool.ToolRegistry.get(toolName);
        if (tool == null) {
            MCAi.LOGGER.warn("Deterministic continuation: unknown tool '{}'", toolName);
            return false;
        }

        companion.getChat().say(CompanionChat.Category.TASK,
                "Continuing: " + toolName.replace('_', ' ') + "...");

        // Add system note to conversation history
        com.apocscode.mcai.ai.ConversationManager.addSystemMessage(
                "[Task completed: " + taskDescription + " → auto-continuing with " + toolName + "]");

        // Execute tool on background thread (tools use ToolContext.runOnServer() internally)
        final com.google.gson.JsonObject finalArgs = args;
        AIService.executeToolDeterministic(toolName, finalArgs, player, companionName);

        return true;
    }

    /**
     * Fire a failure continuation — triggers an AI follow-up chat so it can adapt the plan.
     * Called when a task with a continuation FAILS (e.g., mine_ores can't reach underground ore).
     */
    private void fireFailureContinuation(TaskContinuation continuation, String taskDescription, String failReason) {
        Player owner = companion.getOwner();
        if (!(owner instanceof ServerPlayer serverPlayer)) {
            MCAi.LOGGER.warn("Cannot fire failure continuation — owner not online");
            return;
        }

        String companionName = companion.getCompanionName();
        MCAi.LOGGER.info("Firing FAILURE continuation for '{}': reason='{}', plan='{}'",
                taskDescription, failReason, continuation.planContext());

        companion.getChat().say(CompanionChat.Category.TASK,
                "That didn't work, trying another approach...");

        String syntheticMessage = continuation.buildFailureContinuationMessage(taskDescription, failReason);
        MCAi.LOGGER.info("Failure continuation message: {}", syntheticMessage);

        AIService.continueAfterTask(continuation, "FAILED: " + taskDescription + " — " + failReason,
                serverPlayer, companionName);
    }
}
