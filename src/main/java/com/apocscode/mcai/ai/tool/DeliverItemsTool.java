package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.DeliverItemsTask;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.Map;

/**
 * AI Tool: Deliver items from companion inventory to a bookmarked location or coordinates.
 */
public class DeliverItemsTool implements AiTool {

    @Override
    public String name() { return "deliver_items"; }

    @Override
    public String description() {
        return "Send companion to deliver items to a bookmarked location or coordinates. " +
                "Deposits into container if present, otherwise drops on ground.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject dest = new JsonObject();
        dest.addProperty("type", "string");
        dest.addProperty("description",
                "Destination: bookmark name (e.g., 'home', 'base') or coordinates as 'x,y,z'");
        props.add("destination", dest);

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description",
                "Optional: specific item to deliver (e.g., 'iron_ingot'). If omitted, delivers all items.");
        props.add("item", item);

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "Optional: number of items to deliver. Default: all matching items.");
        props.add("count", count);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description",
                "Optional: describe what to do AFTER delivery completes.");
        props.add("plan", plan);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("destination");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            if (!args.has("destination") || args.get("destination").isJsonNull()) {
                return "Error: 'destination' parameter is required. Use a bookmark name or coordinates (x,y,z).";
            }
            String destStr = args.get("destination").getAsString().trim();

            // Resolve destination — try bookmark first, then coords
            BlockPos destination = resolveDestination(destStr, context);
            if (destination == null) {
                return "Unknown destination '" + destStr + "'. Use a bookmark name or coordinates (x,y,z).";
            }

            // Resolve item filter
            Item itemFilter = null;
            if (args.has("item") && !args.get("item").getAsString().isBlank()) {
                itemFilter = resolveItem(args.get("item").getAsString().toLowerCase().trim());
                if (itemFilter == null) {
                    return "Unknown item: '" + args.get("item").getAsString() + "'.";
                }
            }

            int count = args.has("count") ? args.get("count").getAsInt() : -1;

            DeliverItemsTask task = new DeliverItemsTask(companion, destination,
                    itemFilter, count, destStr);

            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                String planText = args.get("plan").getAsString();
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Deliver items to " + destStr + ", then: " + planText,
                        planText));
            }

            companion.getTaskManager().queueTask(task);
            return "Queued delivery to '" + destStr + "' at " + destination.toShortString() + ".";
        });
    }

    private BlockPos resolveDestination(String dest, ToolContext context) {
        // Try bookmark
        BlockPos bookmark = BookmarkLocationTool.resolveBookmark(context.player().getUUID(), dest);
        if (bookmark != null) return bookmark;

        // Try coordinates x,y,z
        try {
            String[] parts = dest.replace(" ", "").split(",");
            if (parts.length == 3) {
                return new BlockPos(
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
            }
        } catch (NumberFormatException ignored) {}

        return null;
    }

    private Item resolveItem(String query) {
        String normalized = query.replace(" ", "_");
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String name = entry.getValue().getDescription().getString().toLowerCase();
            if (id.getPath().equals(normalized) || name.equals(query)) return entry.getValue();
        }
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String name = entry.getValue().getDescription().getString().toLowerCase();
            if (id.getPath().contains(normalized) || name.contains(query)) return entry.getValue();
        }
        return null;
    }
}
