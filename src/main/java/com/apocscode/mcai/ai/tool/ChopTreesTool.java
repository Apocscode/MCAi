package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.ChopTreesTask;
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

            ChopTreesTask task = new ChopTreesTask(companion, radius, maxLogs);
            companion.getTaskManager().queueTask(task);

            return "Queued tree chopping task. Searching within " + radius + " blocks for logs.";
        });
    }
}
