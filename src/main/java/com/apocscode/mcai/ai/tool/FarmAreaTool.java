package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.FarmAreaTask;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/**
 * AI Tool: Farm an area with a specific crop.
 * Usage: "plant a 10x10 wheat field" → farm_area(x, z, sizeX, sizeZ, crop)
 * The area is centered on the companion's position if coordinates not given.
 */
public class FarmAreaTool implements AiTool {

    @Override
    public String name() {
        return "farm_area";
    }

    @Override
    public String description() {
        return "Farm an area: hoe the ground, plant seeds, and harvest mature crops. " +
                "Specify the area size and crop type. If x/z are omitted, uses companion's current position. " +
                "Supported crops: wheat, carrot, potato, beetroot. " +
                "The companion needs seeds in their inventory to plant.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject sizeX = new JsonObject();
        sizeX.addProperty("type", "integer");
        sizeX.addProperty("description", "Width of the farm area (X axis). Default: 5");
        props.add("sizeX", sizeX);

        JsonObject sizeZ = new JsonObject();
        sizeZ.addProperty("type", "integer");
        sizeZ.addProperty("description", "Length of the farm area (Z axis). Default: 5");
        props.add("sizeZ", sizeZ);

        JsonObject crop = new JsonObject();
        crop.addProperty("type", "string");
        crop.addProperty("description", "Crop type: wheat, carrot, potato, or beetroot. Default: wheat");
        props.add("crop", crop);

        JsonObject x = new JsonObject();
        x.addProperty("type", "integer");
        x.addProperty("description", "Optional X coordinate for farm corner. Omit to use companion position.");
        props.add("x", x);

        JsonObject z = new JsonObject();
        z.addProperty("type", "integer");
        z.addProperty("description", "Optional Z coordinate for farm corner. Omit to use companion position.");
        props.add("z", z);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            int sizeX = args.has("sizeX") ? args.get("sizeX").getAsInt() : 5;
            int sizeZ = args.has("sizeZ") ? args.get("sizeZ").getAsInt() : 5;
            String cropName = args.has("crop") ? args.get("crop").getAsString() : "wheat";
            FarmAreaTask.CropType cropType = FarmAreaTask.CropType.fromString(cropName);

            // Clamp size to prevent performance issues
            sizeX = Math.min(sizeX, 20);
            sizeZ = Math.min(sizeZ, 20);

            BlockPos companionPos = companion.blockPosition();
            int startX = args.has("x") ? args.get("x").getAsInt() : companionPos.getX() - sizeX / 2;
            int startZ = args.has("z") ? args.get("z").getAsInt() : companionPos.getZ() - sizeZ / 2;
            int y = companionPos.getY() - 1; // Ground level below feet

            BlockPos corner1 = new BlockPos(startX, y, startZ);
            BlockPos corner2 = new BlockPos(startX + sizeX - 1, y, startZ + sizeZ - 1);

            FarmAreaTask task = new FarmAreaTask(companion, corner1, corner2, cropType);
            companion.getTaskManager().queueTask(task);

            int seedCount = com.apocscode.mcai.task.BlockHelper.countItem(companion, cropType.seedItem);
            return "Queued farming task: " + sizeX + "x" + sizeZ + " " + cropName + " farm. " +
                    "Seeds available: " + seedCount + ". " +
                    "The companion will hoe, harvest, and plant in this area.";
        });
    }
}
