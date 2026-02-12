package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;

/**
 * One-step smart item fetching: scans all containers within a large radius,
 * finds the best source(s) for the requested items, and transfers them to
 * the player's inventory. Like "The One Probe" + auto-grab.
 *
 * Supports scanning up to 32-block radius (much larger than manual interact).
 * If needed items are spread across multiple containers, pulls from each.
 */
public class FindAndFetchItemTool implements AiTool {

    private static final int MAX_RADIUS = 32;
    private static final int DEFAULT_RADIUS = 16;

    @Override
    public String name() {
        return "find_and_fetch_item";
    }

    @Override
    public String description() {
        return "Scan all containers within 32 blocks and fetch items to player inventory. " +
                "Best for 'get me X' requests. Auto-finds and pulls from multiple chests.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description",
                "Item name or ID to find and fetch. Partial match supported. " +
                        "Examples: 'iron_ingot', 'diamond', 'oak_log', 'cobblestone'");
        props.add("item", item);

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "Number of items to fetch (default: 1). Use -1 for all available.");
        props.add("count", count);

        JsonObject radius = new JsonObject();
        radius.addProperty("type", "integer");
        radius.addProperty("description", "Search radius in blocks (1-32, default 16)");
        props.add("radius", radius);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("item");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null) return "Error: no player context";

        String itemQuery = args.has("item") ? args.get("item").getAsString().trim().toLowerCase() : "";
        if (itemQuery.isEmpty()) return "Error: no item specified";

        int requestedCount = args.has("count") ? args.get("count").getAsInt() : 1;
        boolean fetchAll = requestedCount == -1;
        if (requestedCount < 1 && !fetchAll) requestedCount = 1;

        int radius = args.has("radius") ? args.get("radius").getAsInt() : DEFAULT_RADIUS;
        radius = Math.max(1, Math.min(MAX_RADIUS, radius));

        Level level = context.player().level();
        BlockPos center = context.player().blockPosition();
        List<ContainerMatch> matches = new ArrayList<>();

        // Phase 1: Check companion's tagged STORAGE locations FIRST (priority containers)
        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        if (companion != null) {
            List<TaggedBlock> storageBlocks = companion.getTaggedBlocks(TaggedBlock.Role.STORAGE);
            for (TaggedBlock tb : storageBlocks) {
                BlockPos sPos = tb.pos();
                // Storage blocks might be outside the normal radius — always check them
                BlockEntity sBe = level.getBlockEntity(sPos);
                if (sBe instanceof Container sContainer) {
                    int available = countMatchingItems(sContainer, itemQuery);
                    if (available > 0) {
                        String blockName = level.getBlockState(sPos).getBlock().getName().getString();
                        // Priority: storage locations get distance 0 so they sort first
                        matches.add(new ContainerMatch(sPos, sContainer, blockName + " [STORAGE]", available, 0));
                    }
                }
            }
        }

        // Phase 2: Scan all containers within radius

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof Container container) {
                        int available = countMatchingItems(container, itemQuery);
                        if (available > 0) {
                            double dist = Math.sqrt(center.distSqr(pos));
                            String blockName = level.getBlockState(pos).getBlock().getName().getString();
                            matches.add(new ContainerMatch(pos, container, blockName, available, dist));
                        }
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            return "No containers found with items matching '" + itemQuery +
                    "' within " + radius + " blocks. The player may need to go closer to their storage area.";
        }

        // Sort by distance (nearest first)
        matches.sort(Comparator.comparingDouble(m -> m.distance));

        // Calculate totals available
        int totalAvailable = matches.stream().mapToInt(m -> m.itemCount).sum();
        int toFetch = fetchAll ? totalAvailable : Math.min(requestedCount, totalAvailable);

        if (toFetch == 0) {
            return "Found containers matching '" + itemQuery + "' but they're empty now.";
        }

        // Phase 3: Transfer items (MUST be on server thread)
        final int finalToFetch = toFetch;
        final List<ContainerMatch> finalMatches = matches;
        final String query = itemQuery;

        return context.runOnServer(() -> {
            int remaining = finalToFetch;
            int totalMoved = 0;
            List<String> sources = new ArrayList<>();

            for (ContainerMatch match : finalMatches) {
                if (remaining <= 0) break;

                int movedFromThis = 0;
                for (int i = 0; i < match.container.getContainerSize() && remaining > 0; i++) {
                    ItemStack stack = match.container.getItem(i);
                    if (stack.isEmpty() || !matchesItem(stack, query)) continue;

                    int toTake = Math.min(stack.getCount(), remaining);
                    ItemStack toInsert = stack.copyWithCount(toTake);

                    if (context.player().getInventory().add(toInsert)) {
                        int actuallyInserted = toTake - toInsert.getCount();
                        if (toInsert.isEmpty()) actuallyInserted = toTake;

                        stack.shrink(actuallyInserted);
                        if (stack.isEmpty()) match.container.setItem(i, ItemStack.EMPTY);
                        match.container.setChanged();

                        movedFromThis += actuallyInserted;
                        remaining -= actuallyInserted;
                        totalMoved += actuallyInserted;
                    } else {
                        // Inventory full
                        if (totalMoved > 0) break;
                        return "Player inventory is full — cannot fetch items.";
                    }
                }

                if (movedFromThis > 0) {
                    sources.add(movedFromThis + " from " + match.blockName +
                            " at " + match.pos.getX() + "," + match.pos.getY() + "," + match.pos.getZ());
                }
            }

            if (totalMoved == 0) {
                return "Couldn't transfer any items. Player inventory may be full.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Fetched ").append(totalMoved).append("x items matching '").append(query).append("'");

            if (totalMoved < finalToFetch) {
                sb.append(" (wanted ").append(finalToFetch)
                        .append(", only ").append(totalMoved).append(" available)");
            }

            sb.append(":\n");
            for (String src : sources) {
                sb.append("  - ").append(src).append("\n");
            }

            if (totalAvailable > finalToFetch) {
                sb.append("(").append(totalAvailable - totalMoved)
                        .append(" more available in other containers)");
            }

            return sb.toString();
        });
    }

    private int countMatchingItems(Container container, String query) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && matchesItem(stack, query)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean matchesItem(ItemStack stack, String query) {
        String displayName = stack.getDisplayName().getString().toLowerCase();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String itemId = id.toString().toLowerCase();
        String path = id.getPath().toLowerCase();

        return displayName.contains(query) || itemId.contains(query) ||
                path.contains(query) || path.replace("_", " ").contains(query);
    }

    private static class ContainerMatch {
        final BlockPos pos;
        final Container container;
        final String blockName;
        final int itemCount;
        final double distance;

        ContainerMatch(BlockPos pos, Container container, String blockName, int itemCount, double distance) {
            this.pos = pos;
            this.container = container;
            this.blockName = blockName;
            this.itemCount = itemCount;
            this.distance = distance;
        }
    }
}
