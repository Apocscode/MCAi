package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Transfers items between the player's inventory and a container at specific coordinates.
 * Supports taking items from a container or putting items into a container.
 * Validates distance (must be within 6 blocks).
 */
public class InteractContainerTool implements AiTool {

    private static final double MAX_DISTANCE = 6.0;

    @Override
    public String name() {
        return "interact_container";
    }

    @Override
    public String description() {
        return "Take items from or put items into a container (chest, barrel, etc.) at specific coordinates. " +
                "The player must be within 6 blocks. " +
                "Actions: 'take' moves items from container to player inventory, " +
                "'put' moves items from player inventory to container.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject action = new JsonObject();
        action.addProperty("type", "string");
        action.addProperty("description", "Action: 'take' to take from container, 'put' to put into container");
        JsonArray actionEnum = new JsonArray();
        actionEnum.add("take");
        actionEnum.add("put");
        action.add("enum", actionEnum);
        props.add("action", action);

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description",
                "Item name or ID to transfer. Partial match supported. " +
                        "Examples: 'iron_ingot', 'diamond', 'oak_log'");
        props.add("item", item);

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "Number of items to transfer (default: all available)");
        props.add("count", count);

        JsonObject x = new JsonObject();
        x.addProperty("type", "integer");
        x.addProperty("description", "X coordinate of the container");
        props.add("x", x);

        JsonObject y = new JsonObject();
        y.addProperty("type", "integer");
        y.addProperty("description", "Y coordinate of the container");
        props.add("y", y);

        JsonObject z = new JsonObject();
        z.addProperty("type", "integer");
        z.addProperty("description", "Z coordinate of the container");
        props.add("z", z);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("action");
        required.add("item");
        required.add("x");
        required.add("y");
        required.add("z");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null) return "Error: no player context";

        // Parse arguments
        String action = args.has("action") ? args.get("action").getAsString().toLowerCase() : "";
        String itemQuery = args.has("item") ? args.get("item").getAsString().toLowerCase().trim() : "";
        int requestedCount = args.has("count") ? args.get("count").getAsInt() : Integer.MAX_VALUE;

        if (!action.equals("take") && !action.equals("put")) {
            return "Error: action must be 'take' or 'put', got '" + action + "'";
        }
        if (itemQuery.isEmpty()) {
            return "Error: no item specified";
        }

        // Parse coordinates
        if (!args.has("x") || !args.has("y") || !args.has("z")) {
            return "Error: coordinates (x, y, z) are required";
        }
        BlockPos targetPos = new BlockPos(
                args.get("x").getAsInt(),
                args.get("y").getAsInt(),
                args.get("z").getAsInt()
        );

        // Check distance
        double dist = Math.sqrt(context.player().blockPosition().distSqr(targetPos));
        if (dist > MAX_DISTANCE) {
            return "Error: container at " + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() +
                    " is " + String.format("%.1f", dist) + " blocks away (max " + MAX_DISTANCE + "). " +
                    "The player needs to be closer.";
        }

        // Get container
        Level level = context.player().level();
        BlockEntity be = level.getBlockEntity(targetPos);
        if (!(be instanceof Container container)) {
            String blockName = level.getBlockState(targetPos).getBlock().getName().getString();
            return "Error: block at " + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() +
                    " is '" + blockName + "', not a container.";
        }

        if (action.equals("take")) {
            return takeFromContainer(context, container, itemQuery, requestedCount, targetPos);
        } else {
            return putInContainer(context, container, itemQuery, requestedCount, targetPos);
        }
    }

    private String takeFromContainer(ToolContext context, Container container,
                                      String itemQuery, int maxCount, BlockPos pos) {
        int totalMoved = 0;
        int remaining = maxCount;

        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;

            if (matchesItem(stack, itemQuery)) {
                int toTake = Math.min(stack.getCount(), remaining);
                ItemStack toInsert = stack.copy();
                toInsert.setCount(toTake);

                // Try to add to player inventory
                if (context.player().getInventory().add(toInsert)) {
                    // Successfully added — remove from container
                    int inserted = toTake - toInsert.getCount();
                    if (inserted > 0) {
                        stack.shrink(inserted);
                        container.setItem(i, stack.isEmpty() ? ItemStack.EMPTY : stack);
                        totalMoved += inserted;
                        remaining -= inserted;
                    }

                    // add() modifies the stack count if partially inserted
                    if (toInsert.isEmpty()) {
                        stack.shrink(toTake);
                        if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                        totalMoved += toTake;
                        remaining -= toTake;
                    }
                } else {
                    // Player inventory full
                    if (totalMoved > 0) break;
                    return "Error: player inventory is full, cannot take items.";
                }
            }
        }

        container.setChanged();

        if (totalMoved == 0) {
            return "No items matching '" + itemQuery + "' found in container at " +
                    pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".";
        }

        return "Took " + totalMoved + " item(s) matching '" + itemQuery + "' from container at " +
                pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".";
    }

    private String putInContainer(ToolContext context, Container container,
                                   String itemQuery, int maxCount, BlockPos pos) {
        int totalMoved = 0;
        int remaining = maxCount;
        var inventory = context.player().getInventory();

        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;

            if (matchesItem(stack, itemQuery)) {
                int toPut = Math.min(stack.getCount(), remaining);

                // Try to insert into container
                for (int j = 0; j < container.getContainerSize() && toPut > 0; j++) {
                    ItemStack containerSlot = container.getItem(j);

                    if (containerSlot.isEmpty()) {
                        // Empty slot — place items
                        int toPlace = Math.min(toPut, stack.getMaxStackSize());
                        ItemStack placed = stack.copy();
                        placed.setCount(toPlace);
                        container.setItem(j, placed);
                        stack.shrink(toPlace);
                        totalMoved += toPlace;
                        remaining -= toPlace;
                        toPut -= toPlace;
                    } else if (ItemStack.isSameItemSameComponents(containerSlot, stack)) {
                        // Same item — stack
                        int space = containerSlot.getMaxStackSize() - containerSlot.getCount();
                        if (space > 0) {
                            int toAdd = Math.min(Math.min(toPut, space), stack.getCount());
                            containerSlot.grow(toAdd);
                            stack.shrink(toAdd);
                            totalMoved += toAdd;
                            remaining -= toAdd;
                            toPut -= toAdd;
                        }
                    }
                }

                if (stack.isEmpty()) {
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
        }

        container.setChanged();

        if (totalMoved == 0) {
            return "No items matching '" + itemQuery + "' found in player inventory, " +
                    "or the container at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " is full.";
        }

        return "Put " + totalMoved + " item(s) matching '" + itemQuery + "' into container at " +
                pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ".";
    }

    private boolean matchesItem(ItemStack stack, String query) {
        String displayName = stack.getDisplayName().getString().toLowerCase();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String itemId = id.toString().toLowerCase();
        String path = id.getPath().toLowerCase();

        return displayName.contains(query) || itemId.contains(query) ||
                path.contains(query) || path.replace("_", " ").contains(query);
    }
}
