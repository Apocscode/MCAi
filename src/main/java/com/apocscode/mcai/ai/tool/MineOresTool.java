package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.MineOresTask;
import com.apocscode.mcai.task.OreGuide;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * AI Tool: Mine ores nearby.
 * Supports targeted ore mining (e.g. "mine iron") and general ore mining.
 * Includes Y-level intelligence — warns if companion is at the wrong depth.
 */
public class MineOresTool implements AiTool {

    @Override
    public String name() {
        return "mine_ores";
    }

    @Override
    public String description() {
        return "Mine ore blocks near the companion. Can target a specific ore type (iron, diamond, gold, etc.) " +
                "or mine all ores. Includes Y-level awareness — warns if the companion is at the wrong depth. " +
                "The companion scans nearby, navigates to ores, mines them, and collects drops. " +
                "Specify 'ore' to target a specific type, 'radius' (default 16), and 'maxOres' (default 32). " +
                "If no ores found in scan radius, companion will report back so you can use dig_down or strip_mine " +
                "to reach the right Y-level first.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject ore = new JsonObject();
        ore.addProperty("type", "string");
        ore.addProperty("description",
                "Target ore type to mine. Examples: 'iron', 'diamond', 'gold', 'coal', 'copper', " +
                "'lapis', 'redstone', 'emerald'. If omitted, mines ALL ore types found nearby.");
        props.add("ore", ore);

        JsonObject radius = new JsonObject();
        radius.addProperty("type", "integer");
        radius.addProperty("description", "Search radius for ores. Default: 16, max: 24");
        props.add("radius", radius);

        JsonObject maxOres = new JsonObject();
        maxOres.addProperty("type", "integer");
        maxOres.addProperty("description", "Maximum number of ore blocks to mine. Default: 32.");
        props.add("maxOres", maxOres);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description",
                "Optional: describe what to do AFTER mining completes. " +
                "Example: 'smelt raw_iron then craft iron_pickaxe'. " +
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
            int maxOres = args.has("maxOres") ? args.get("maxOres").getAsInt() : 32;
            radius = Math.min(radius, 24);

            // Parse optional ore target
            String oreTarget = args.has("ore") ? args.get("ore").getAsString().trim() : null;
            OreGuide.Ore targetOre = oreTarget != null ? OreGuide.findByName(oreTarget) : null;

            // Build Y-level warning if at wrong depth
            StringBuilder warnings = new StringBuilder();
            int currentY = companion.blockPosition().getY();

            if (targetOre != null) {
                // Check if companion is at the right Y-level range for this ore
                if (currentY < targetOre.minY || currentY > targetOre.maxY) {
                    warnings.append("WARNING: Companion is at Y=").append(currentY)
                            .append(" but ").append(targetOre.name).append(" ore generates between Y=")
                            .append(targetOre.minY).append(" and Y=").append(targetOre.maxY)
                            .append(". Best Y=").append(targetOre.bestY).append(". ")
                            .append("Use dig_down or strip_mine to reach the right depth first. ");
                } else if (Math.abs(currentY - targetOre.bestY) > 20) {
                    warnings.append("Note: Companion is at Y=").append(currentY)
                            .append(". ").append(targetOre.name).append(" ore is most common at Y=")
                            .append(targetOre.bestY).append(". Consider mining deeper for better results. ");
                }
            }

            // Create task — pass ore target for filtered scanning
            MineOresTask task = new MineOresTask(companion, radius, maxOres, targetOre);

            // Attach continuation plan
            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                String planText = args.get("plan").getAsString();
                String oreLabel = targetOre != null ? targetOre.name + " ore" : "ores";
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Mine " + oreLabel + " (r=" + radius + "), then: " + planText,
                        planText
                ));
            }

            companion.getTaskManager().queueTask(task);

            String oreLabel = targetOre != null ? targetOre.name + " ore" : "all ore types";
            String toolInfo = targetOre != null
                    ? " Requires " + targetOre.tierName() + " pickaxe or better."
                    : "";
            return "[ASYNC_TASK] Queued mining: " + oreLabel + " within " + radius + " blocks at Y=" + currentY + "." +
                    toolInfo + " " + warnings +
                    "This task runs over time — STOP calling tools and tell the player you're on it. " +
                    "If you used the plan parameter, the next step will auto-execute when mining finishes.";
        });
    }
}
