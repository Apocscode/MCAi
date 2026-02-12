package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.FishingTask;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * AI Tool: Send the companion to fish near water.
 */
public class FishingTool implements AiTool {

    @Override
    public String name() { return "go_fishing"; }

    @Override
    public String description() {
        return "Send the companion to fish at a nearby water source. " +
                "The companion finds water within 20 blocks, stands at the edge, and catches fish. " +
                "Average 8 seconds per catch. Produces fish, junk items, and occasional treasures. " +
                "Good for getting food (cod, salmon) or passive loot.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "Number of items to catch before stopping. Default: 5. Max: 20.");
        props.add("count", count);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description", "Optional: what to do after fishing completes.");
        props.add("plan", plan);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            int count = args.has("count") ? args.get("count").getAsInt() : 5;
            count = Math.max(1, Math.min(count, 20));

            FishingTask task = new FishingTask(companion, count);

            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                String planText = args.get("plan").getAsString();
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Fish " + count + " items, then: " + planText,
                        planText));
            }

            companion.getTaskManager().queueTask(task);
            return "Queued fishing: will catch " + count + " items from nearby water.";
        });
    }
}
