package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.BuildTask;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * AI Tool: Build structures (walls, floors, shelters).
 * The companion places blocks from its inventory in patterns.
 */
public class BuildStructureTool implements AiTool {

    @Override
    public String name() { return "build_structure"; }

    @Override
    public String description() {
        return "Build a structure from companion inventory. " +
                "Shapes: wall, floor, platform, shelter, column. Needs blocks in companion inventory.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject shape = new JsonObject();
        shape.addProperty("type", "string");
        shape.addProperty("description",
                "Shape to build: 'wall', 'floor', 'platform', 'shelter', 'column'");
        props.add("shape", shape);

        JsonObject block = new JsonObject();
        block.addProperty("type", "string");
        block.addProperty("description", "Block type to use: 'cobblestone', 'oak_planks', 'stone_bricks', etc.");
        props.add("block", block);

        JsonObject width = new JsonObject();
        width.addProperty("type", "integer");
        width.addProperty("description", "Width (X axis). Default: 5. Max: 16.");
        props.add("width", width);

        JsonObject height = new JsonObject();
        height.addProperty("type", "integer");
        height.addProperty("description", "Height (Y axis). Default: 3. Max: 10.");
        props.add("height", height);

        JsonObject depth = new JsonObject();
        depth.addProperty("type", "integer");
        depth.addProperty("description", "Depth (Z axis). Default: 5. Max: 16. Used by floor/shelter.");
        props.add("depth", depth);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description", "Optional: what to do after building completes.");
        props.add("plan", plan);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("shape");
        required.add("block");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            String shapeName = args.get("shape").getAsString().trim().toUpperCase();
            BuildTask.Shape shape;
            try {
                shape = BuildTask.Shape.valueOf(shapeName);
            } catch (IllegalArgumentException e) {
                return "Unknown shape: '" + shapeName + "'. Use: wall, floor, platform, shelter, column.";
            }

            Block block = resolveBlock(args.get("block").getAsString().trim().toLowerCase());
            if (block == null) return "Unknown block type.";

            int width = args.has("width") ? Math.min(args.get("width").getAsInt(), 16) : 5;
            int height = args.has("height") ? Math.min(args.get("height").getAsInt(), 10) : 3;
            int depth = args.has("depth") ? Math.min(args.get("depth").getAsInt(), 16) : 5;

            // Build 2 blocks in front of companion
            BlockPos origin = companion.blockPosition().relative(companion.getDirection(), 2);

            BuildTask task = new BuildTask(companion, shape, block, origin, width, height, depth);

            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Build " + shapeName + ", then: " + args.get("plan").getAsString(),
                        args.get("plan").getAsString()));
            }

            companion.getTaskManager().queueTask(task);
            return "Queued " + shapeName.toLowerCase() + " build (" + width + "x" + height + "x" + depth +
                    ") using " + block.getName().getString() + " at " + origin.toShortString() + ".";
        });
    }

    private Block resolveBlock(String query) {
        String normalized = query.replace(" ", "_");
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            if (id.getPath().equals(normalized) || id.toString().equals(normalized)) return entry.getValue();
        }
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            String name = entry.getValue().getName().getString().toLowerCase();
            if (name.equals(query) || name.replace(" ", "_").equals(normalized)) return entry.getValue();
        }
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            if (entry.getKey().location().getPath().contains(normalized)) return entry.getValue();
        }
        return null;
    }
}
