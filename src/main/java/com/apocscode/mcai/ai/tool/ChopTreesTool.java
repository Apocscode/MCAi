package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.ChopTreesTask;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonObject;

/**
 * AI Tool: Chop trees nearby.
 * Usage: "go chop some wood" → chop_trees(radius, maxLogs)
 */
public class ChopTreesTool implements AiTool {

    @Override
    public String name() {
        return "chop_trees";
    }

    @Override
    public String description() {
        return "Chop down trees near the companion. The companion will find logs within the " +
                "search radius and break them. Specify radius (default 16) and max logs to chop (default 64).";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject radius = new JsonObject();
        radius.addProperty("type", "integer");
        radius.addProperty("description", "Search radius for trees. Default: 16, max: 32");
        props.add("radius", radius);

        JsonObject maxLogs = new JsonObject();
        maxLogs.addProperty("type", "integer");
        maxLogs.addProperty("description", "Maximum number of logs to chop. Default: 64. Use 0 for no limit.");
        props.add("maxLogs", maxLogs);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description",
                "Optional: describe what to do AFTER chopping completes. " +
                "Example: 'transfer logs to player then craft planks and sticks'. " +
                "If set, the AI will automatically continue the plan when the task finishes.");
        props.add("plan", plan);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            int radius = args.has("radius") ? args.get("radius").getAsInt() : 16;
            int maxLogs = args.has("maxLogs") ? args.get("maxLogs").getAsInt() : 64;

            radius = Math.min(radius, 32);

            // Pre-check: skip if companion already has enough log-type items
            // Logs include any item whose ID contains "log", "wood", "stem", or "hyphae"
            int logsHave = BlockHelper.countItemMatching(companion, item -> {
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
                return id.contains("log") || id.contains("wood") || id.contains("stem") || id.contains("hyphae");
            });
            if (logsHave >= maxLogs) {
                MCAi.LOGGER.info("ChopTrees: SKIPPING — already have {} logs (need {})", logsHave, maxLogs);
                return "Already have " + logsHave + " logs in inventory (need " + maxLogs +
                        "). Skipping chop — proceed to next step.";
            }

            ChopTreesTask task = new ChopTreesTask(companion, radius, maxLogs);

            // Attach continuation plan if the AI specified next steps
            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                String planText = args.get("plan").getAsString();
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Chop trees (r=" + radius + "), then: " + planText,
                        planText
                ));
            }

            companion.getTaskManager().queueTask(task);

            return "[ASYNC_TASK] Queued tree chopping task. Searching within " + radius + " blocks for logs. " +
                    "This task runs over time — STOP calling tools and tell the player you're on it.";
        });
    }
}
