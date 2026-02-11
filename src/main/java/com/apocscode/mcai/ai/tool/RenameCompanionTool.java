package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Allows the AI to rename itself when the player asks.
 * Changes the display name above the companion's head and in chat.
 * Persists across saves (stored in entity NBT).
 */
public class RenameCompanionTool implements AiTool {

    @Override
    public String name() {
        return "rename_companion";
    }

    @Override
    public String description() {
        return "Change the companion's display name. Use when the player says " +
                "'your name is X', 'I'll call you X', 'rename yourself to X', " +
                "'call yourself X', etc. The new name appears above your head and in chat.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject nameParam = new JsonObject();
        nameParam.addProperty("type", "string");
        nameParam.addProperty("description",
                "The new name for the companion. Max 32 characters.");
        props.add("name", nameParam);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("name");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null) return "Error: no player context";

        String newName = args.has("name") ? args.get("name").getAsString().trim() : "";
        if (newName.isEmpty()) return "Error: no name provided";
        if (newName.length() > 32) newName = newName.substring(0, 32);

        final String finalName = newName;

        return context.runOnServer(() -> {
            // Find the nearest companion entity belonging to this player
            AABB searchBox = context.player().getBoundingBox().inflate(32);
            List<CompanionEntity> companions = context.player().level()
                    .getEntitiesOfClass(CompanionEntity.class, searchBox,
                            c -> context.player().getUUID().equals(c.getOwnerUUID()));

            if (companions.isEmpty()) {
                return "Error: no companion found nearby";
            }

            String oldName = companions.get(0).getCompanionName();

            // Rename all matching companions (usually just one)
            for (CompanionEntity companion : companions) {
                companion.setCompanionName(finalName);
            }

            return "Renamed from '" + oldName + "' to '" + finalName +
                    "'. The new name is displayed above the companion's head " +
                    "and will be used in the chat window.";
        });
    }
}
