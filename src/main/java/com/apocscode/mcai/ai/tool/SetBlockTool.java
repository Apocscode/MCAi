package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CommandBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places or modifies blocks in the world. Supports:
 * - Placing any block at coordinates
 * - Setting command blocks with commands
 * - Breaking blocks (setting to air)
 * - Reading block state at a position
 *
 * Limited to a reasonable range from the player (32 blocks).
 */
public class SetBlockTool implements AiTool {

    private static final double MAX_DISTANCE = 32.0;

    @Override
    public String name() {
        return "set_block";
    }

    @Override
    public String description() {
        return "Place, modify, or break a block at specific coordinates. Can set command blocks with commands. " +
                "Use coordinates or relative position (~). " +
                "Examples: set stone at 100,64,200 / set air to break a block / " +
                "set command_block with command 'say Hello'. " +
                "Maximum range: 32 blocks from player.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject block = new JsonObject();
        block.addProperty("type", "string");
        block.addProperty("description",
                "Block ID to place. Examples: 'stone', 'minecraft:diamond_block', " +
                        "'command_block', 'air' (to break), 'repeating_command_block', " +
                        "'chain_command_block', 'redstone_block'");
        props.add("block", block);

        JsonObject x = new JsonObject();
        x.addProperty("type", "integer");
        x.addProperty("description", "X coordinate");
        props.add("x", x);

        JsonObject y = new JsonObject();
        y.addProperty("type", "integer");
        y.addProperty("description", "Y coordinate");
        props.add("y", y);

        JsonObject z = new JsonObject();
        z.addProperty("type", "integer");
        z.addProperty("description", "Z coordinate");
        props.add("z", z);

        JsonObject command = new JsonObject();
        command.addProperty("type", "string");
        command.addProperty("description",
                "Optional: command to set in a command block. Only used when block is " +
                        "'command_block', 'repeating_command_block', or 'chain_command_block'.");
        props.add("command", command);

        JsonObject alwaysActive = new JsonObject();
        alwaysActive.addProperty("type", "boolean");
        alwaysActive.addProperty("description",
                "Optional: if true, command block is set to 'Always Active' (no redstone needed). Default: true.");
        props.add("always_active", alwaysActive);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("block");
        required.add("x");
        required.add("y");
        required.add("z");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null || context.server() == null) {
            return "Error: no server context";
        }

        String blockId = args.has("block") ? args.get("block").getAsString().trim().toLowerCase() : "";
        if (blockId.isEmpty()) return "Error: no block specified";

        if (!args.has("x") || !args.has("y") || !args.has("z")) {
            return "Error: coordinates (x, y, z) are required";
        }

        BlockPos pos = new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt()
        );

        // Distance check
        double dist = Math.sqrt(context.player().blockPosition().distSqr(pos));
        if (dist > MAX_DISTANCE) {
            return "Error: position " + pos.getX() + "," + pos.getY() + "," + pos.getZ() +
                    " is " + String.format("%.1f", dist) + " blocks away (max " + MAX_DISTANCE + ").";
        }

        // Resolve block ID
        if (!blockId.contains(":")) blockId = "minecraft:" + blockId;
        ResourceLocation blockRL = ResourceLocation.parse(blockId);
        Block block = BuiltInRegistries.BLOCK.get(blockRL);
        if (block == Blocks.AIR && !blockId.equals("minecraft:air")) {
            return "Error: unknown block '" + blockId + "'. Check the block ID.";
        }

        String cmdBlockCommand = args.has("command") ? args.get("command").getAsString() : null;
        boolean alwaysActive = !args.has("always_active") || args.get("always_active").getAsBoolean();

        final Block finalBlock = block;
        final String finalBlockId = blockId;

        return context.runOnServer(() -> {
            Level level = context.player().level();
            BlockState oldState = level.getBlockState(pos);
            String oldBlockName = oldState.getBlock().getName().getString();

            // Set the block
            BlockState newState = finalBlock.defaultBlockState();
            level.setBlockAndUpdate(pos, newState);

            StringBuilder sb = new StringBuilder();
            sb.append("Block set: ").append(finalBlockId)
                    .append(" at ").append(pos.getX()).append(",")
                    .append(pos.getY()).append(",").append(pos.getZ());

            if (!oldState.isAir()) {
                sb.append(" (replaced ").append(oldBlockName).append(")");
            }

            // Configure command block if applicable
            if (cmdBlockCommand != null && finalBlock instanceof CommandBlock) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof CommandBlockEntity cmdEntity) {
                    cmdEntity.getCommandBlock().setCommand(cmdBlockCommand);

                    if (alwaysActive) {
                        // Set to always active — auto mode
                        cmdEntity.setAutomatic(true);
                    }

                    cmdEntity.setChanged();
                    // Force block entity update to clients
                    level.sendBlockUpdated(pos, newState, newState, 3);

                    sb.append("\nCommand block configured:")
                            .append("\n  Command: ").append(cmdBlockCommand)
                            .append("\n  Mode: ").append(alwaysActive ? "Always Active" : "Needs Redstone");
                }
            }

            return sb.toString();
        });
    }
}
