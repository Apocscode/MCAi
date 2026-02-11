package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows the player to bookmark locations with custom names.
 * "Mark this as iron-storage" → later "get iron from iron-storage"
 * Bookmarks are per-player and stored in memory (persistent across session via saved data later).
 */
public class BookmarkLocationTool implements AiTool {

    // Per-player bookmarks: playerUUID -> (name -> position)
    private static final Map<UUID, Map<String, BlockPos>> bookmarks = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "bookmark_location";
    }

    @Override
    public String description() {
        return "Save, list, get, or delete named location bookmarks for the player. " +
                "Use 'save' when the player says 'remember this place as X' or 'mark this as X'. " +
                "Use 'get' to retrieve coordinates of a named location. " +
                "Use 'list' to show all saved bookmarks. " +
                "Use 'delete' to remove a bookmark.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "Action: 'save', 'get', 'list', or 'delete'");
        JsonArray actionEnum = new JsonArray();
        actionEnum.add("save");
        actionEnum.add("get");
        actionEnum.add("list");
        actionEnum.add("delete");
        action.add("enum", actionEnum);
        props.add("action", action);

        JsonObject locationName = new JsonObject();
        locationName.addProperty("type", "string");
        locationName.addProperty("description",
                "Name for the bookmark. Examples: 'iron-storage', 'base', 'nether-portal', 'mob-farm'");
        props.add("name", locationName);

        JsonObject x = new JsonObject();
        x.addProperty("type", "integer");
        x.addProperty("description", "X coordinate (optional for 'save' — defaults to player position or looked-at block)");
        props.add("x", x);

        JsonObject y = new JsonObject();
        y.addProperty("type", "integer");
        y.addProperty("description", "Y coordinate");
        props.add("y", y);

        JsonObject z = new JsonObject();
        z.addProperty("type", "integer");
        z.addProperty("description", "Z coordinate");
        props.add("z", z);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("action");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null) return "Error: no player context";

        String action = args.has("action") ? args.get("action").getAsString().toLowerCase() : "";
        String locationName = args.has("name") ? args.get("name").getAsString().trim().toLowerCase() : "";
        UUID playerId = context.player().getUUID();

        switch (action) {
            case "save":
                return saveBookmark(args, context, playerId, locationName);
            case "get":
                return getBookmark(playerId, locationName);
            case "list":
                return listBookmarks(playerId);
            case "delete":
                return deleteBookmark(playerId, locationName);
            default:
                return "Error: action must be 'save', 'get', 'list', or 'delete'. Got: '" + action + "'";
        }
    }

    private String saveBookmark(JsonObject args, ToolContext context, UUID playerId, String name) {
        if (name.isEmpty()) {
            return "Error: a name is required to save a bookmark. Example: 'iron-storage'";
        }

        BlockPos pos;
        if (args.has("x") && args.has("y") && args.has("z")) {
            pos = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
        } else {
            // Default to player's current position
            pos = context.player().blockPosition();
        }

        bookmarks.computeIfAbsent(playerId, k -> new LinkedHashMap<>()).put(name, pos);

        MCAi.LOGGER.info("Bookmark saved: '{}' at {} for player {}", name, pos, playerId);
        return "Bookmark '" + name + "' saved at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".";
    }

    private String getBookmark(UUID playerId, String name) {
        if (name.isEmpty()) {
            return "Error: specify the bookmark name to retrieve.";
        }

        Map<String, BlockPos> playerBookmarks = bookmarks.get(playerId);
        if (playerBookmarks == null || !playerBookmarks.containsKey(name)) {
            // Fuzzy search
            if (playerBookmarks != null) {
                for (Map.Entry<String, BlockPos> entry : playerBookmarks.entrySet()) {
                    if (entry.getKey().contains(name) || name.contains(entry.getKey())) {
                        BlockPos pos = entry.getValue();
                        return "Bookmark '" + entry.getKey() + "': " +
                                pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
                                " (fuzzy match for '" + name + "')";
                    }
                }
            }
            return "No bookmark found with name '" + name + "'. Use action 'list' to see all bookmarks.";
        }

        BlockPos pos = playerBookmarks.get(name);
        return "Bookmark '" + name + "': " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private String listBookmarks(UUID playerId) {
        Map<String, BlockPos> playerBookmarks = bookmarks.get(playerId);
        if (playerBookmarks == null || playerBookmarks.isEmpty()) {
            return "No bookmarks saved yet. Tell the player to say 'remember this as [name]' to save a location.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Saved Bookmarks (").append(playerBookmarks.size()).append(") ===\n");
        for (Map.Entry<String, BlockPos> entry : playerBookmarks.entrySet()) {
            BlockPos pos = entry.getValue();
            sb.append("- ").append(entry.getKey()).append(": ")
                    .append(pos.getX()).append(", ")
                    .append(pos.getY()).append(", ")
                    .append(pos.getZ()).append("\n");
        }
        return sb.toString();
    }

    private String deleteBookmark(UUID playerId, String name) {
        if (name.isEmpty()) {
            return "Error: specify the bookmark name to delete.";
        }

        Map<String, BlockPos> playerBookmarks = bookmarks.get(playerId);
        if (playerBookmarks == null || !playerBookmarks.containsKey(name)) {
            return "No bookmark found with name '" + name + "'.";
        }

        playerBookmarks.remove(name);
        return "Bookmark '" + name + "' deleted.";
    }

    /**
     * Get all bookmarks for a player (used by other tools to resolve location names).
     */
    public static Map<String, BlockPos> getPlayerBookmarks(UUID playerId) {
        return bookmarks.getOrDefault(playerId, Collections.emptyMap());
    }

    /**
     * Resolve a bookmark name to coordinates for a player.
     * Returns null if not found.
     */
    public static BlockPos resolveBookmark(UUID playerId, String name) {
        Map<String, BlockPos> playerBookmarks = bookmarks.get(playerId);
        if (playerBookmarks == null) return null;
        BlockPos exact = playerBookmarks.get(name.toLowerCase());
        if (exact != null) return exact;

        // Fuzzy match
        for (Map.Entry<String, BlockPos> entry : playerBookmarks.entrySet()) {
            if (entry.getKey().contains(name.toLowerCase())) return entry.getValue();
        }
        return null;
    }
}
