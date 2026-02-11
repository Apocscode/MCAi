package com.apocscode.mcai.ai.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;

/**
 * Scans for all containers (chests, barrels, hoppers, etc.) within a radius.
 * Returns container type, position, and a summary of contents for each.
 * Sorted by distance from the player.
 */
public class ScanContainersTool implements AiTool {

    @Override
    public String name() {
        return "scan_containers";
    }

    @Override
    public String description() {
        return "Scan for all containers (chests, barrels, hoppers, shulker boxes, etc.) " +
                "within a radius of the player. Returns each container's type, coordinates, " +
                "and contents summary. Use when the player asks to find items or needs to " +
                "locate a specific container.";
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

        JsonObject itemFilter = new JsonObject();
        itemFilter.addProperty("type", "string");
        itemFilter.addProperty("description",
                "Optional: only show containers that have this item (partial match). " +
                        "Example: 'iron' matches Iron Ingot, Iron Ore, etc.");
        props.add("item_filter", itemFilter);

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

        String filter = null;
        if (args.has("item_filter") && !args.get("item_filter").getAsString().isBlank()) {
            filter = args.get("item_filter").getAsString().trim().toLowerCase();
        }

        Level level = context.player().level();
        BlockPos center = context.player().blockPosition();

        // Collect all containers
        List<ContainerInfo> containers = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof Container container) {
                        ContainerInfo info = scanContainer(level, pos, container, filter);
                        if (info != null) {
                            info.distance = Math.sqrt(center.distSqr(pos));
                            containers.add(info);
                        }
                    }
                }
            }
        }

        // Sort by distance
        containers.sort(Comparator.comparingDouble(c -> c.distance));

        StringBuilder sb = new StringBuilder();
        sb.append("=== Container Scan (radius ").append(radius).append(") ===\n");

        if (filter != null) {
            sb.append("Filter: '").append(filter).append("'\n");
        }

        if (containers.isEmpty()) {
            sb.append("No containers found");
            if (filter != null) sb.append(" matching '").append(filter).append("'");
            sb.append(" within ").append(radius).append(" blocks.\n");
            return sb.toString();
        }

        sb.append("Found ").append(containers.size()).append(" container(s):\n\n");

        for (int i = 0; i < containers.size(); i++) {
            ContainerInfo info = containers.get(i);
            sb.append(i + 1).append(". ").append(info.blockName)
                    .append(" at ").append(info.pos.getX()).append(", ")
                    .append(info.pos.getY()).append(", ").append(info.pos.getZ())
                    .append(" (").append(String.format("%.1f", info.distance)).append(" blocks away)\n");

            if (info.items.isEmpty()) {
                sb.append("   Empty\n");
            } else {
                for (Map.Entry<String, Integer> item : info.items.entrySet()) {
                    sb.append("   - ").append(item.getKey())
                            .append(": ").append(item.getValue()).append("\n");
                }
            }
            sb.append("   (").append(info.emptySlots).append("/")
                    .append(info.totalSlots).append(" slots empty)\n\n");
        }

        return sb.toString();
    }

    private ContainerInfo scanContainer(Level level, BlockPos pos, Container container, String filter) {
        Block block = level.getBlockState(pos).getBlock();
        String blockName = block.getName().getString();

        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        int emptySlots = 0;
        boolean matchesFilter = (filter == null); // If no filter, always match

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                emptySlots++;
            } else {
                String name = stack.getDisplayName().getString();
                itemCounts.merge(name, stack.getCount(), Integer::sum);

                // Check filter
                if (filter != null && !matchesFilter) {
                    String id = stack.getItem().toString().toLowerCase();
                    if (name.toLowerCase().contains(filter) || id.contains(filter)) {
                        matchesFilter = true;
                    }
                }
            }
        }

        if (!matchesFilter) return null; // Doesn't match item filter

        ContainerInfo info = new ContainerInfo();
        info.pos = pos;
        info.blockName = blockName;
        info.items = itemCounts;
        info.emptySlots = emptySlots;
        info.totalSlots = container.getContainerSize();
        return info;
    }

    private static class ContainerInfo {
        BlockPos pos;
        String blockName;
        Map<String, Integer> items;
        int emptySlots;
        int totalSlots;
        double distance;
    }
}
