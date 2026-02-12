package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.MineBlocksTask;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/**
 * AI Tool: Mine/clear a rectangular area.
 * Usage: "clear a 5x3x5 area" → mine_area(sizeX, sizeY, sizeZ)
 */
public class MineAreaTool implements AiTool {

    @Override
    public String name() {
        return "mine_area";
    }

    @Override
    public String description() {
        return "Clear/mine all blocks in a rectangular area. The area is centered on the companion. " +
                "Specify sizeX, sizeY (height), sizeZ dimensions. Max 10x10x10. " +
                "Optionally specify x, y, z for a specific corner position.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject sizeX = new JsonObject();
        sizeX.addProperty("type", "integer");
        sizeX.addProperty("description", "Width (X). Default: 3, max: 10");
        props.add("sizeX", sizeX);

        JsonObject sizeY = new JsonObject();
        sizeY.addProperty("type", "integer");
        sizeY.addProperty("description", "Height (Y). Default: 3, max: 10");
        props.add("sizeY", sizeY);

        JsonObject sizeZ = new JsonObject();
        sizeZ.addProperty("type", "integer");
        sizeZ.addProperty("description", "Depth (Z). Default: 3, max: 10");
        props.add("sizeZ", sizeZ);

        JsonObject x = new JsonObject();
        x.addProperty("type", "integer");
        x.addProperty("description", "Optional corner X. Omit to center on companion.");
        props.add("x", x);

        JsonObject y = new JsonObject();
        y.addProperty("type", "integer");
        y.addProperty("description", "Optional corner Y. Omit to use companion Y level.");
        props.add("y", y);

        JsonObject z = new JsonObject();
        z.addProperty("type", "integer");
        z.addProperty("description", "Optional corner Z. Omit to center on companion.");
        props.add("z", z);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            int sizeX = Math.min(args.has("sizeX") ? args.get("sizeX").getAsInt() : 3, 10);
            int sizeY = Math.min(args.has("sizeY") ? args.get("sizeY").getAsInt() : 3, 10);
            int sizeZ = Math.min(args.has("sizeZ") ? args.get("sizeZ").getAsInt() : 3, 10);

            BlockPos pos = companion.blockPosition();
            int startX = args.has("x") ? args.get("x").getAsInt() : pos.getX() - sizeX / 2;
            int startY = args.has("y") ? args.get("y").getAsInt() : pos.getY();
            int startZ = args.has("z") ? args.get("z").getAsInt() : pos.getZ() - sizeZ / 2;

            BlockPos from = new BlockPos(startX, startY, startZ);
            BlockPos to = new BlockPos(startX + sizeX - 1, startY + sizeY - 1, startZ + sizeZ - 1);

            MineBlocksTask task = new MineBlocksTask(companion, from, to);
            companion.getTaskManager().queueTask(task);

            return "Queued area mining task: " + sizeX + "x" + sizeY + "x" + sizeZ + " blocks.";
        });
    }
}
