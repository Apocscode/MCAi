package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.MineBlocksTask;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/**
 * AI Tool: Dig straight down N blocks (vertical shaft).
 * Usage: "dig down 5" → dig_down(depth=5)
 * Creates a 1×depth×1 vertical shaft from the companion's current position.
 */
public class DigDownTool implements AiTool {

    @Override
    public String name() {
        return "dig_down";
    }

    @Override
    public String description() {
        return "Dig straight down (vertical shaft) from the companion's current position. " +
                "Specify depth (1-64 blocks). The companion mines a 1x1 column directly below.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject depth = new JsonObject();
        depth.addProperty("type", "integer");
        depth.addProperty("description", "How many blocks to dig down. Default: 5, max: 64");
        props.add("depth", depth);

        JsonObject width = new JsonObject();
        width.addProperty("type", "integer");
        width.addProperty("description", "Width of the shaft (1=1x1, 2=2x2, 3=3x3). Default: 1, max: 3");
        props.add("width", width);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("depth");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            int depth = args.has("depth") ? args.get("depth").getAsInt() : 5;
            depth = Math.max(1, Math.min(depth, 64));

            int width = args.has("width") ? args.get("width").getAsInt() : 1;
            width = Math.max(1, Math.min(width, 3));

            BlockPos pos = companion.blockPosition();

            // Shaft starts 1 block below the companion and goes down 'depth' blocks
            int startY = pos.getY() - 1;
            int endY = startY - depth + 1;

            // Safety: clamp to world minimum (don't mine into void)
            int worldMin = companion.level().getMinBuildHeight();
            if (endY < worldMin) {
                endY = worldMin;
                depth = startY - endY + 1;
                if (depth <= 0) return "Already at the bottom of the world. Can't dig further down.";
            }

            BlockPos from, to;
            if (width == 1) {
                from = new BlockPos(pos.getX(), endY, pos.getZ());
                to = new BlockPos(pos.getX(), startY, pos.getZ());
            } else {
                // Center the wider shaft on the companion
                int offset = width / 2;
                from = new BlockPos(pos.getX() - offset, endY, pos.getZ() - offset);
                to = new BlockPos(pos.getX() - offset + width - 1, startY, pos.getZ() - offset + width - 1);
            }

            MineBlocksTask task = new MineBlocksTask(companion, from, to);
            companion.getTaskManager().queueTask(task);

            String shaftDesc = width == 1 ? "1x1" : width + "x" + width;
            return "[ASYNC_TASK] Digging " + shaftDesc + " shaft straight down " + depth + " blocks from Y=" +
                    startY + " to Y=" + endY + ". " +
                    "This task runs over time — STOP calling tools and tell the player you're on it.";
        });
    }
}
