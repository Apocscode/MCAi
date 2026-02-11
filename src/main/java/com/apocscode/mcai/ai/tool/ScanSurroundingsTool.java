package com.apocscode.mcai.ai.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans blocks and entities around the player.
 */
public class ScanSurroundingsTool implements AiTool {

    @Override
    public String name() {
        return "scan_surroundings";
    }

    @Override
    public String description() {
        return "Scan blocks and entities around the player within a radius. " +
                "Shows nearby blocks (ores, chests, spawners, etc.), mobs, items on ground, " +
                "and other players. Useful for situational awareness and finding resources.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject radius = new JsonObject();
        radius.addProperty("type", "integer");
        radius.addProperty("description", "Scan radius in blocks (1-16, default 8)");
        props.add("radius", radius);
        schema.add("properties", props);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null) return "Error: no player context";

        int radius = 8;
        if (args.has("radius")) {
            radius = Math.max(1, Math.min(16, args.get("radius").getAsInt()));
        }

        Level level = context.player().level();
        BlockPos center = context.player().blockPosition();
        StringBuilder sb = new StringBuilder();

        sb.append("=== Surroundings Scan (radius ").append(radius).append(") ===\n");
        sb.append("Player at: ").append(center.getX()).append(", ")
                .append(center.getY()).append(", ").append(center.getZ()).append("\n\n");

        // Scan for notable blocks
        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        Map<String, BlockPos> firstSeen = new LinkedHashMap<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    Block block = state.getBlock();

                    // Skip air and common blocks
                    if (state.isAir() || block == Blocks.STONE || block == Blocks.DIRT ||
                            block == Blocks.GRASS_BLOCK || block == Blocks.DEEPSLATE ||
                            block == Blocks.WATER || block == Blocks.LAVA ||
                            block == Blocks.BEDROCK || block == Blocks.NETHERRACK ||
                            block == Blocks.COBBLESTONE || block == Blocks.GRAVEL ||
                            block == Blocks.SAND || block == Blocks.SANDSTONE) {
                        continue;
                    }

                    String name = block.getName().getString();
                    // Filter to interesting blocks
                    String id = block.builtInRegistryHolder().key().location().toString();
                    if (isInteresting(id)) {
                        blockCounts.merge(name, 1, Integer::sum);
                        firstSeen.putIfAbsent(name, pos);
                    }
                }
            }
        }

        sb.append("Notable blocks:\n");
        if (blockCounts.isEmpty()) {
            sb.append("- Nothing notable nearby\n");
        } else {
            for (Map.Entry<String, Integer> entry : blockCounts.entrySet()) {
                BlockPos pos = firstSeen.get(entry.getKey());
                sb.append("- ").append(entry.getKey()).append(" x").append(entry.getValue());
                if (pos != null) {
                    sb.append(" (nearest: ").append(pos.getX()).append(",")
                            .append(pos.getY()).append(",").append(pos.getZ()).append(")");
                }
                sb.append("\n");
            }
        }

        // Scan for entities
        AABB box = new AABB(center).inflate(radius);
        List<Entity> entities = level.getEntities(context.player(), box, e -> !(e instanceof ItemEntity));
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, box);

        sb.append("\nNearby mobs/entities:\n");
        if (entities.isEmpty()) {
            sb.append("- None\n");
        } else {
            Map<String, Integer> mobCounts = new LinkedHashMap<>();
            for (Entity e : entities) {
                String name = e.getType().getDescription().getString();
                if (e instanceof LivingEntity le) {
                    name += " (HP: " + (int) le.getHealth() + "/" + (int) le.getMaxHealth() + ")";
                }
                mobCounts.merge(name, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : mobCounts.entrySet()) {
                sb.append("- ").append(entry.getKey());
                if (entry.getValue() > 1) sb.append(" x").append(entry.getValue());
                sb.append("\n");
            }
        }

        // Items on ground
        if (!items.isEmpty()) {
            sb.append("\nItems on ground:\n");
            Map<String, Integer> itemCounts = new LinkedHashMap<>();
            for (ItemEntity ie : items) {
                String name = ie.getItem().getDisplayName().getString();
                itemCounts.merge(name, ie.getItem().getCount(), Integer::sum);
            }
            for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(" x").append(entry.getValue()).append("\n");
            }
        }

        // Light level at feet
        int light = level.getLightEmission(center);
        int skyLight = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, center);
        sb.append("\nLight level: ").append(light).append(" (sky: ").append(skyLight).append(")\n");

        return sb.toString();
    }

    private boolean isInteresting(String id) {
        // Ores, containers, machines, spawners, special blocks
        return id.contains("ore") || id.contains("chest") || id.contains("barrel") ||
                id.contains("furnace") || id.contains("crafting") || id.contains("anvil") ||
                id.contains("enchant") || id.contains("brewing") || id.contains("spawner") ||
                id.contains("portal") || id.contains("beacon") || id.contains("hopper") ||
                id.contains("dropper") || id.contains("dispenser") || id.contains("observer") ||
                id.contains("piston") || id.contains("redstone") || id.contains("command") ||
                id.contains("shulker") || id.contains("diamond") || id.contains("emerald") ||
                id.contains("ancient_debris") || id.contains("amethyst") ||
                // Mod blocks — machines, controllers, etc.
                id.contains("machine") || id.contains("controller") || id.contains("processor") ||
                id.contains("generator") || id.contains("turbine") || id.contains("reactor") ||
                id.contains("cable") || id.contains("pipe") || id.contains("duct") ||
                id.contains("energy") || id.contains("tank") || id.contains("press") ||
                id.contains("crusher") || id.contains("smelter") || id.contains("mixer");
    }
}
