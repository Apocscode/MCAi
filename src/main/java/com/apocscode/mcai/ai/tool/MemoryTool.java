package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.ai.CompanionMemory;
import com.apocscode.mcai.entity.CompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * AI Tool: Manage the companion's persistent memory.
 * Can remember facts, recall them, and log notable events.
 */
public class MemoryTool implements AiTool {

    @Override
    public String name() { return "companion_memory"; }

    @Override
    public String description() {
        return "Manage the companion's persistent memory. " +
                "Use this to remember facts about the player (preferences, base location, favorite items), " +
                "recall saved facts, log notable events, or list all memories. " +
                "Memory persists across game sessions. " +
                "Actions: 'remember' (save a fact), 'recall' (get a fact), 'forget' (remove a fact), " +
                "'log_event' (record something that happened), 'list' (show all facts and recent events).";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "Action: 'remember', 'recall', 'forget', 'log_event', or 'list'");
        JsonArray actionEnum = new JsonArray();
        actionEnum.add("remember");
        actionEnum.add("recall");
        actionEnum.add("forget");
        actionEnum.add("log_event");
        actionEnum.add("list");
        action.add("enum", actionEnum);
        props.add("action", action);

        JsonObject key = new JsonObject();
        key.addProperty("type", "string");
        key.addProperty("description", "Fact key for remember/recall/forget (e.g. 'base_location', 'favorite_food', 'player_name')");
        props.add("key", key);

        JsonObject value = new JsonObject();
        value.addProperty("type", "string");
        value.addProperty("description", "Value for 'remember' (the fact to store) or 'log_event' (the event description)");
        props.add("value", value);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("action");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            CompanionMemory memory = companion.getMemory();
            String action = args.has("action") ? args.get("action").getAsString() : "list";
            String key = args.has("key") ? args.get("key").getAsString() : "";
            String value = args.has("value") ? args.get("value").getAsString() : "";

            switch (action) {
                case "remember" -> {
                    if (key.isBlank() || value.isBlank()) return "Need both 'key' and 'value' to remember a fact.";
                    memory.setFact(key, value);
                    return "Remembered: " + key + " = " + value;
                }
                case "recall" -> {
                    if (key.isBlank()) return "Need 'key' to recall a fact.";
                    String fact = memory.getFact(key);
                    return fact != null ? key + ": " + fact : "I don't have any memory of '" + key + "'.";
                }
                case "forget" -> {
                    if (key.isBlank()) return "Need 'key' to forget a fact.";
                    memory.removeFact(key);
                    return "Forgot: " + key;
                }
                case "log_event" -> {
                    if (value.isBlank()) return "Need 'value' containing the event description.";
                    memory.addEvent(value);
                    return "Event logged: " + value;
                }
                case "list" -> {
                    StringBuilder sb = new StringBuilder();
                    Map<String, String> facts = memory.getAllFacts();
                    if (facts.isEmpty()) {
                        sb.append("No facts stored.\n");
                    } else {
                        sb.append("Facts (").append(facts.size()).append("):\n");
                        for (Map.Entry<String, String> e : facts.entrySet()) {
                            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
                        }
                    }
                    List<String> events = memory.getRecentEvents(10);
                    if (events.isEmpty()) {
                        sb.append("No events logged.");
                    } else {
                        sb.append("Recent events (last ").append(events.size()).append("):\n");
                        for (String ev : events) {
                            sb.append("  - ").append(ev).append("\n");
                        }
                    }
                    return sb.toString().trim();
                }
                default -> { return "Unknown action: " + action; }
            }
        });
    }
}
