package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.google.gson.JsonObject;

/**
 * AI Tool: Get task status or cancel tasks.
 * Usage: "what are you doing?" → task_status()
 *        "stop what you're doing" → task_status(action="cancel")
 */
public class TaskStatusTool implements AiTool {

    @Override
    public String name() {
        return "task_status";
    }

    @Override
    public String description() {
        return "Check what tasks the companion is currently doing, or cancel tasks. " +
                "Use action='status' (default) to check, action='cancel' to cancel all tasks, " +
                "action='cancel_active' to cancel only the current task.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "Action: 'status' to check tasks, 'cancel' to cancel all, 'cancel_active' to cancel current. Default: status");
        props.add("action", action);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            String action = args.has("action") ? args.get("action").getAsString() : "status";

            var taskManager = companion.getTaskManager();

            switch (action) {
                case "cancel":
                    taskManager.cancelAll();
                    return "All tasks cancelled.";
                case "cancel_active":
                    taskManager.cancelActive();
                    return "Active task cancelled. Remaining queued tasks will continue.";
                default:
                    return taskManager.getStatusSummary();
            }
        });
    }
}
