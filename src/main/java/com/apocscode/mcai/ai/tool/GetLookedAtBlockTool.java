package com.apocscode.mcai.ai.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raycasts from the player's eyes to find what block they're looking at.
 * If it's a container (chest, barrel, hopper, etc.), also lists contents.
 * Range: ~6 blocks (normal interaction distance).
 */
public class GetLookedAtBlockTool implements AiTool {

    private static final double REACH = 6.0;

    @Override
    public String name() {
        return "get_looked_at_block";
    }

    @Override
    public String description() {
        return "Get information about the block the player is currently looking at. " +
                "Returns block type, coordinates, and if it's a container (chest, barrel, etc.) " +
                "also lists the contents. Use when the player says 'this chest', 'that block', etc.";
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
        if (context.player() == null) return "Error: no player context";

        // Raycast from eye position in look direction
        Vec3 eyePos = context.player().getEyePosition(1.0F);
        Vec3 lookVec = context.player().getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(REACH));

        Level level = context.player().level();
        BlockHitResult hitResult = level.clip(new ClipContext(
                eyePos, endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                context.player()
        ));

        if (hitResult.getType() == HitResult.Type.MISS) {
            return "The player is not looking at any block (looking at air/sky).";
        }

        BlockPos pos = hitResult.getBlockPos();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        String blockName = block.getName().getString();
        String blockId = block.builtInRegistryHolder().key().location().toString();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Looked-At Block ===\n");
        sb.append("Block: ").append(blockName).append(" (").append(blockId).append(")\n");
        sb.append("Position: ").append(pos.getX()).append(", ")
                .append(pos.getY()).append(", ").append(pos.getZ()).append("\n");
        sb.append("Face hit: ").append(hitResult.getDirection().getName()).append("\n");

        // Check if it's a container
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof Container container) {
            sb.append("\n--- Container Contents (").append(container.getContainerSize()).append(" slots) ---\n");

            Map<String, Integer> itemCounts = new LinkedHashMap<>();
            Map<String, String> displayNames = new LinkedHashMap<>();
            int emptySlots = 0;

            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) {
                    emptySlots++;
                } else {
                    String id = stack.getItem().toString();
                    String name = stack.getDisplayName().getString();
                    itemCounts.merge(id, stack.getCount(), Integer::sum);
                    displayNames.putIfAbsent(id, name);
                }
            }

            if (itemCounts.isEmpty()) {
                sb.append("Empty container\n");
            } else {
                for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                    sb.append("- ").append(displayNames.get(entry.getKey()))
                            .append(": ").append(entry.getValue()).append("\n");
                }
            }
            sb.append("Empty slots: ").append(emptySlots).append("/")
                    .append(container.getContainerSize()).append("\n");
        } else {
            // Check for notable block properties
            if (!state.getValues().isEmpty()) {
                sb.append("Properties: ");
                state.getValues().forEach((prop, val) -> {
                    sb.append(prop.getName()).append("=").append(val.toString()).append(" ");
                });
                sb.append("\n");
            }
        }

        // Distance from player
        double dist = Math.sqrt(context.player().blockPosition().distSqr(pos));
        sb.append("Distance: ").append(String.format("%.1f", dist)).append(" blocks\n");

        return sb.toString();
    }
}
