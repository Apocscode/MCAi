package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.MineOresTask;
import com.google.gson.JsonObject;

/**
 * AI Tool: Mine ores nearby.
 * Usage: "go mine some ores" → mine_ores(radius, maxOres)
 */
public class MineOresTool implements AiTool {

    @Override
    public String name() {
        return "mine_ores";
    }

    @Override
    public String description() {
        return "Mine ore blocks near the companion including iron, gold, diamond, coal, copper, " +
                "lapis, redstone, and emerald ores. " +
                "Specify radius (default 16) and max ores (default 32).";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject radius = new JsonObject();
        radius.addProperty("type", "integer");
        radius.addProperty("description", "Search radius for ores. Default: 16, max: 24");
        props.add("radius", radius);

        JsonObject maxOres = new JsonObject();
        maxOres.addProperty("type", "integer");
        maxOres.addProperty("description", "Maximum number of ore blocks to mine. Default: 32.");
        props.add("maxOres", maxOres);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            int radius = args.has("radius") ? args.get("radius").getAsInt() : 16;
            int maxOres = args.has("maxOres") ? args.get("maxOres").getAsInt() : 32;

            radius = Math.min(radius, 24);

            MineOresTask task = new MineOresTask(companion, radius, maxOres);
            companion.getTaskManager().queueTask(task);

            return "Queued ore mining task. Searching within " + radius + " blocks for ores.";
        });
    }
}
