package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.mining.DigShaftTask;
import com.apocscode.mcai.task.mining.MineState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * AI Tool: Dig down N blocks via a walkable staircase shaft.
 * Usage: "dig down 10" → dig_down(depth=10)
 * Creates a walkable 2×1 staircase (no ladders needed) with torch placement,
 * ore scanning, lava safety, and mid-mine torch crafting.
 *
 * The companion faces their current horizontal direction and descends via
 * alternating forward/down steps, placing torches every 8 blocks.
 */
public class DigDownTool implements AiTool {

    @Override
    public String name() {
        return "dig_down";
    }

    @Override
    public String description() {
        return "Dig down from the companion's current position via a walkable staircase shaft. " +
                "Specify depth (1-64 blocks). The companion digs a 2x1 staircase with torches, " +
                "ore scanning, and lava safety. No ladders needed — the staircase is fully walkable.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject depth = new JsonObject();
        depth.addProperty("type", "integer");
        depth.addProperty("description", "How many blocks to dig down (depth in Y-levels). Default: 5, max: 64");
        props.add("depth", depth);

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

            BlockPos pos = companion.blockPosition();
            int startY = pos.getY();
            int targetY = startY - depth;

            // Safety: clamp to world minimum (don't mine into void)
            int worldMin = companion.level().getMinBuildHeight() + 1;
            if (targetY < worldMin) {
                targetY = worldMin;
                depth = startY - targetY;
                if (depth <= 0) return "Already at the bottom of the world. Can't dig further down.";
            }

            // Determine direction: use the companion's current facing direction
            Direction facing = Direction.fromYRot(companion.getYRot());
            // Ensure horizontal direction only
            if (facing == Direction.UP || facing == Direction.DOWN) {
                facing = Direction.NORTH;
            }

            // Create a lightweight MineState for the staircase shaft
            MineState mineState = new MineState(null, targetY, pos, facing);
            mineState.setShaftBottom(pos); // Will be updated by DigShaftTask

            DigShaftTask task = new DigShaftTask(companion, mineState, facing, targetY);
            companion.getTaskManager().queueTask(task);

            return "[ASYNC_TASK] Digging staircase shaft down " + depth + " blocks from Y=" +
                    startY + " to Y=" + targetY + " heading " + facing.getName() + ". " +
                    "Walkable stairs with torches, ore scanning, and lava safety. " +
                    "This task runs over time — STOP calling tools and tell the player you're on it.";
        });
    }
}
