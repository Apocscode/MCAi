package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/**
 * AI Tool: Set companion to guard mode at current position or a bookmark.
 */
public class GuardAreaTool implements AiTool {

    @Override
    public String name() { return "guard_area"; }

    @Override
    public String description() {
        return "Set the companion to guard an area. The companion will patrol within a 16-block radius, " +
                "engaging any hostile mobs that enter. Use a bookmark name or 'here' for current position. " +
                "Use 'stop' to cancel guard mode and return to following.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject location = new JsonObject();
        location.addProperty("type", "string");
        location.addProperty("description",
                "Where to guard: 'here' (companion's current pos), 'stop' (cancel guard), " +
                        "or a bookmark name (e.g., 'base')");
        props.add("location", location);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("location");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            if (!args.has("location") || args.get("location").isJsonNull()) {
                return "Error: 'location' parameter is required. Use: 'here', 'home', 'stop', or coordinates (x,y,z).";
            }
            String location = args.get("location").getAsString().trim().toLowerCase();

            if (location.equals("stop") || location.equals("cancel")) {
                companion.setBehaviorMode(CompanionEntity.BehaviorMode.FOLLOW);
                companion.setGuardPos(null);
                return "Guard mode cancelled. Companion is now following you.";
            }

            BlockPos guardPos;
            if (location.equals("here") || location.equals("current")) {
                guardPos = companion.blockPosition();
            } else {
                BlockPos bookmark = BookmarkLocationTool.resolveBookmark(
                        context.player().getUUID(), location);
                if (bookmark != null) {
                    guardPos = bookmark;
                } else {
                    return "Unknown location '" + location + "'. Use 'here' or a bookmark name.";
                }
            }

            companion.setGuardPos(guardPos);
            companion.setBehaviorMode(CompanionEntity.BehaviorMode.GUARD);

            return "Companion is now guarding at " + guardPos.toShortString() +
                    ". It will patrol a 16-block radius and attack hostile mobs.";
        });
    }
}
