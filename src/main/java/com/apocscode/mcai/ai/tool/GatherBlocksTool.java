package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.GatherBlocksTask;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * AI Tool: Gather specific block types nearby.
 * The companion navigates to and mines matching blocks within a radius.
 * Usage: "gather 10 sand" → gather_blocks(block="sand", radius=16, maxBlocks=10)
 */
public class GatherBlocksTool implements AiTool {

    @Override
    public String name() {
        return "gather_blocks";
    }

    @Override
    public String description() {
        return "Send companion to mine a specific block type nearby and pick up drops. " +
                "Works for any block. Specify block name, radius (default 16), maxBlocks (default 16).";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject block = new JsonObject();
        block.addProperty("type", "string");
        block.addProperty("description",
                "Block name or ID to gather. Examples: 'sand', 'oak_log', 'cobblestone', 'dirt', 'clay'");
        props.add("block", block);

        JsonObject radius = new JsonObject();
        radius.addProperty("type", "integer");
        radius.addProperty("description", "Search radius in blocks. Default: 16, max: 32");
        props.add("radius", radius);

        JsonObject maxBlocks = new JsonObject();
        maxBlocks.addProperty("type", "integer");
        maxBlocks.addProperty("description", "Maximum number of blocks to gather. Default: 16.");
        props.add("maxBlocks", maxBlocks);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description",
                "Optional: describe what to do AFTER gathering completes. " +
                "Example: 'transfer cobblestone to player then craft stone_pickaxe'. " +
                "If set, the AI will automatically continue the plan when the task finishes.");
        props.add("plan", plan);

        schema.add("properties", props);

        // Required fields
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("block");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            String blockName = args.get("block").getAsString().toLowerCase().trim();
            int radius = args.has("radius") ? args.get("radius").getAsInt() : 16;
            int maxBlocks = args.has("maxBlocks") ? args.get("maxBlocks").getAsInt() : 16;

            radius = Math.min(radius, 32);
            maxBlocks = Math.max(1, Math.min(maxBlocks, 128));

            // Resolve block by name
            Block targetBlock = resolveBlock(blockName);
            if (targetBlock == null) {
                return "Unknown block: '" + blockName + "'. Try using the Minecraft ID like 'sand', 'oak_log', 'cobblestone'.";
            }

            GatherBlocksTask task = new GatherBlocksTask(companion, targetBlock, radius, maxBlocks);

            // Attach continuation plan if the AI specified next steps
            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                String planText = args.get("plan").getAsString();
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Gather " + maxBlocks + " " + targetBlock.getName().getString() + ", then: " + planText,
                        planText
                ));
            }

            companion.getTaskManager().queueTask(task);

            return "Queued gather task: collecting up to " + maxBlocks + " "
                    + targetBlock.getName().getString() + " within " + radius + " blocks. "
                    + "The companion will mine the blocks and pick up the drops automatically.";
        });
    }

    /**
     * Resolve a block by name, path, or display name.
     */
    private Block resolveBlock(String query) {
        // Normalize spaces to underscores
        String normalized = query.replace(" ", "_");

        // Exact match by registry path
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            if (id.getPath().equals(normalized) || id.toString().equals(normalized)) {
                return entry.getValue();
            }
        }

        // Display name match (case-insensitive)
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            String displayName = entry.getValue().getName().getString().toLowerCase();
            if (displayName.equals(query) || displayName.replace(" ", "_").equals(normalized)) {
                return entry.getValue();
            }
        }

        // Partial / contains match
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String displayName = entry.getValue().getName().getString().toLowerCase();
            if (id.getPath().contains(normalized) || displayName.contains(query)) {
                return entry.getValue();
            }
        }

        return null;
    }
}
