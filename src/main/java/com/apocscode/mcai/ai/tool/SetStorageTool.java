package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * AI Tool: Designate a chest/container as the companion's storage.
 * The companion will check these tagged storage locations FIRST when fetching items.
 *
 * Uses the existing TaggedBlock logistics system (same as the Logistics Wand).
 * Usage: "set your chest here" or "use the chest at 100 64 200 as storage"
 */
public class SetStorageTool implements AiTool {

    @Override
    public String name() {
        return "set_storage";
    }

    @Override
    public String description() {
        return "Designate a container (chest, barrel, etc.) as the companion's storage. " +
                "The companion will check storage containers FIRST when looking for items. " +
                "Provide x,y,z coordinates, or omit to use the block the player is looking at. " +
                "Use action='list' to see current storage locations, 'remove' to undesignate, " +
                "'clear' to remove all storage.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description",
                "Action: 'set' (default), 'remove', 'list', or 'clear'. " +
                "'set' adds a container as storage, 'remove' removes one, 'list' shows all, 'clear' removes all.");
        props.add("action", action);

        JsonObject x = new JsonObject();
        x.addProperty("type", "integer");
        x.addProperty("description", "X coordinate of the container. Omit to auto-detect from player's crosshair.");
        props.add("x", x);

        JsonObject y = new JsonObject();
        y.addProperty("type", "integer");
        y.addProperty("description", "Y coordinate of the container.");
        props.add("y", y);

        JsonObject z = new JsonObject();
        z.addProperty("type", "integer");
        z.addProperty("description", "Z coordinate of the container.");
        props.add("z", z);

        schema.add("properties", props);
        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            String action = args.has("action") ? args.get("action").getAsString().toLowerCase() : "set";

            switch (action) {
                case "list":
                    return listStorage(companion);
                case "clear":
                    return clearStorage(companion);
                case "remove":
                    return removeStorage(companion, args, context);
                default: // "set"
                    return setStorage(companion, args, context);
            }
        });
    }

    private String setStorage(CompanionEntity companion, JsonObject args, ToolContext context) {
        Level level = context.player().level();
        BlockPos pos;

        if (args.has("x") && args.has("y") && args.has("z")) {
            pos = new BlockPos(
                    args.get("x").getAsInt(),
                    args.get("y").getAsInt(),
                    args.get("z").getAsInt()
            );
        } else {
            // Try to use the block the player is looking at
            net.minecraft.world.phys.HitResult hit = context.player().pick(8.0, 0.0f, false);
            if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                pos = blockHit.getBlockPos();
            } else {
                return "No coordinates provided and no block in crosshair. " +
                        "Look at a chest and try again, or specify x, y, z coordinates.";
            }
        }

        // Verify this is actually a container
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container)) {
            BlockState state = level.getBlockState(pos);
            String blockName = state.getBlock().getName().getString();
            return "Block at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() +
                    " (" + blockName + ") is not a container. " +
                    "Point at a chest, barrel, or other storage block.";
        }

        Container container = (Container) be;
        String blockName = level.getBlockState(pos).getBlock().getName().getString();
        int slots = container.getContainerSize();

        // Tag it as STORAGE
        companion.addTaggedBlock(pos, TaggedBlock.Role.STORAGE);

        int totalStorage = companion.getTaggedBlocks(TaggedBlock.Role.STORAGE).size();

        return "✓ Set " + blockName + " at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() +
                " as storage (" + slots + " slots). The companion now has " + totalStorage +
                " storage location(s) and will check them first when looking for items.";
    }

    private String removeStorage(CompanionEntity companion, JsonObject args, ToolContext context) {
        BlockPos pos;

        if (args.has("x") && args.has("y") && args.has("z")) {
            pos = new BlockPos(
                    args.get("x").getAsInt(),
                    args.get("y").getAsInt(),
                    args.get("z").getAsInt()
            );
        } else {
            net.minecraft.world.phys.HitResult hit = context.player().pick(8.0, 0.0f, false);
            if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                pos = blockHit.getBlockPos();
            } else {
                return "No coordinates provided and no block in crosshair.";
            }
        }

        boolean removed = companion.removeTaggedBlock(pos);
        if (removed) {
            return "Removed storage at " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ".";
        } else {
            return "No storage was set at that location.";
        }
    }

    private String listStorage(CompanionEntity companion) {
        var storageBlocks = companion.getTaggedBlocks(TaggedBlock.Role.STORAGE);
        if (storageBlocks.isEmpty()) {
            return "No storage locations set. Tell me to 'set storage' while looking at a chest.";
        }

        StringBuilder sb = new StringBuilder("Storage locations (" + storageBlocks.size() + "):\n");
        for (TaggedBlock tb : storageBlocks) {
            BlockPos p = tb.pos();
            sb.append("  - ").append(p.getX()).append(",").append(p.getY())
                    .append(",").append(p.getZ()).append("\n");
        }
        return sb.toString();
    }

    private String clearStorage(CompanionEntity companion) {
        var storageBlocks = companion.getTaggedBlocks(TaggedBlock.Role.STORAGE);
        int count = storageBlocks.size();
        // Remove each one
        for (TaggedBlock tb : new java.util.ArrayList<>(storageBlocks)) {
            companion.removeTaggedBlock(tb.pos());
        }
        return "Cleared " + count + " storage location(s).";
    }
}
