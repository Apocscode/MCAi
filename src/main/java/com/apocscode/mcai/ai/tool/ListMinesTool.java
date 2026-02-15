package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.mining.CreateMineTask;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * AI Tool: List all remembered mines.
 *
 * Reads the companion's memory for saved mine locations and returns
 * a formatted summary. Each mine is stored as a memory fact with key
 * "mine_{ore}" (e.g. "mine_diamond", "mine_iron", "mine_general").
 *
 * Use this when the player asks "where are my mines?", "do I have a mine?",
 * "show mines", or similar queries about existing mine locations.
 */
public class ListMinesTool implements AiTool {

    @Override
    public String name() {
        return "list_mines";
    }

    @Override
    public String description() {
        return "List all remembered mine locations. Shows entrance coordinates, target Y-level, " +
                "direction, and ore type for each mine the companion has built. " +
                "Use when the player asks about existing mines, mine locations, or " +
                "whether a mine for a specific ore already exists.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            Map<String, String> facts = companion.getMemory().getAllFacts();

            StringBuilder sb = new StringBuilder();
            int count = 0;

            for (Map.Entry<String, String> entry : facts.entrySet()) {
                if (!entry.getKey().startsWith("mine_")) continue;

                String[] parsed = CreateMineTask.parseMineMemory(entry.getValue());
                if (parsed == null) continue;

                count++;
                String oreType = entry.getKey().substring(5); // Remove "mine_" prefix
                String oreName = oreType.substring(0, 1).toUpperCase() + oreType.substring(1);

                sb.append(count).append(". **").append(oreName).append(" mine**: ");
                sb.append("Entrance at (").append(parsed[0]).append(", ").append(parsed[1])
                        .append(", ").append(parsed[2]).append("), ");
                sb.append("mining at Y=").append(parsed[3]).append(", ");
                sb.append("heading ").append(parsed[4]).append(", ");
                sb.append(parsed[5]).append("-block branches × ").append(parsed[6]).append(" pairs\n");
            }

            if (count == 0) {
                return "No mines remembered yet. Use create_mine to build one — I'll remember where it is!";
            }

            return count + " mine(s) found:\n" + sb.toString().trim();
        });
    }
}
