package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.google.gson.JsonObject;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * AI Tool: Transfer items between companion inventory and player inventory.
 * Enables the gather→transfer→craft workflow.
 *
 * Directions:
 *   "to_player"   — move items FROM companion → TO player
 *   "to_companion" — move items FROM player → TO companion
 *   "check"       — list the companion's inventory contents
 */
public class TransferItemsTool implements AiTool {

    @Override
    public String name() {
        return "transfer_items";
    }

    @Override
    public String description() {
        return "Transfer items between the companion's inventory and the player's inventory, " +
                "or check what the companion is carrying. " +
                "Use direction='to_player' to move items from companion to player (after mining/gathering). " +
                "Use direction='to_companion' to give items to the companion. " +
                "Use direction='check' to list the companion's inventory contents. " +
                "Optionally filter by item name and specify a count.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject direction = new JsonObject();
        direction.addProperty("type", "string");
        direction.addProperty("description",
                "Transfer direction: 'to_player' (companion→player), 'to_companion' (player→companion), or 'check' (list companion inventory)");
        props.add("direction", direction);

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description",
                "Optional item name filter (e.g. 'diamond', 'iron_ore', 'oak_log'). " +
                "If omitted, transfers ALL items (for to_player/to_companion) or lists all (for check).");
        props.add("item", item);

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description",
                "Maximum number of items to transfer. Default: all matching items. " +
                "Only used with to_player and to_companion directions.");
        props.add("count", count);

        schema.add("properties", props);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("direction");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            String direction = args.get("direction").getAsString().toLowerCase().trim();
            String itemFilter = args.has("item") ? args.get("item").getAsString().toLowerCase().trim() : null;
            int maxCount = args.has("count") ? args.get("count").getAsInt() : Integer.MAX_VALUE;

            return switch (direction) {
                case "to_player" -> transferToPlayer(companion, context, itemFilter, maxCount);
                case "to_companion" -> transferToCompanion(companion, context, itemFilter, maxCount);
                case "check" -> checkInventory(companion, itemFilter);
                default -> "Invalid direction: '" + direction + "'. Use 'to_player', 'to_companion', or 'check'.";
            };
        });
    }

    /**
     * Transfer items FROM companion inventory → TO player inventory.
     */
    private String transferToPlayer(CompanionEntity companion, ToolContext context,
                                     String itemFilter, int maxCount) {
        SimpleContainer compInv = companion.getCompanionInventory();
        Inventory playerInv = context.player().getInventory();
        int totalTransferred = 0;

        for (int i = 0; i < compInv.getContainerSize() && totalTransferred < maxCount; i++) {
            ItemStack stack = compInv.getItem(i);
            if (stack.isEmpty()) continue;
            if (itemFilter != null && !matchesFilter(stack, itemFilter)) continue;

            int toTransfer = Math.min(stack.getCount(), maxCount - totalTransferred);
            ItemStack transferStack = stack.copyWithCount(toTransfer);

            if (playerInv.add(transferStack)) {
                // Fully added
                stack.shrink(toTransfer);
                if (stack.isEmpty()) compInv.setItem(i, ItemStack.EMPTY);
                totalTransferred += toTransfer;
            } else if (transferStack.getCount() < toTransfer) {
                // Partially added
                int added = toTransfer - transferStack.getCount();
                stack.shrink(added);
                totalTransferred += added;
            }
            // else: player inventory full, skip
        }

        if (totalTransferred == 0) {
            if (itemFilter != null) {
                return "The companion has no " + itemFilter + " to transfer.";
            }
            return "The companion's inventory is empty.";
        }

        return "Transferred " + totalTransferred + " item(s) from companion to player.";
    }

    /**
     * Transfer items FROM player inventory → TO companion inventory.
     */
    private String transferToCompanion(CompanionEntity companion, ToolContext context,
                                        String itemFilter, int maxCount) {
        SimpleContainer compInv = companion.getCompanionInventory();
        Inventory playerInv = context.player().getInventory();
        int totalTransferred = 0;

        for (int i = 0; i < playerInv.getContainerSize() && totalTransferred < maxCount; i++) {
            ItemStack stack = playerInv.getItem(i);
            if (stack.isEmpty()) continue;
            if (itemFilter != null && !matchesFilter(stack, itemFilter)) continue;

            int toTransfer = Math.min(stack.getCount(), maxCount - totalTransferred);
            ItemStack transferStack = stack.copyWithCount(toTransfer);

            ItemStack remainder = compInv.addItem(transferStack);
            int added = toTransfer - remainder.getCount();
            if (added > 0) {
                stack.shrink(added);
                if (stack.isEmpty()) playerInv.setItem(i, ItemStack.EMPTY);
                totalTransferred += added;
            }

            if (!remainder.isEmpty()) break; // Companion inventory full
        }

        if (totalTransferred == 0) {
            if (itemFilter != null) {
                return "You have no " + itemFilter + " to give the companion, or companion inventory is full.";
            }
            return "Nothing to transfer — your inventory is empty or companion is full.";
        }

        return "Transferred " + totalTransferred + " item(s) from player to companion.";
    }

    /**
     * List the companion's inventory contents.
     */
    private String checkInventory(CompanionEntity companion, String itemFilter) {
        SimpleContainer compInv = companion.getCompanionInventory();
        StringBuilder sb = new StringBuilder();
        sb.append("Companion Inventory:\n");

        int totalItems = 0;
        int usedSlots = 0;

        // Aggregate by display name for cleaner output
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();

        for (int i = 0; i < compInv.getContainerSize(); i++) {
            ItemStack stack = compInv.getItem(i);
            if (stack.isEmpty()) continue;
            if (itemFilter != null && !matchesFilter(stack, itemFilter)) continue;

            String name = stack.getHoverName().getString();
            counts.merge(name, stack.getCount(), Integer::sum);
            totalItems += stack.getCount();
            usedSlots++;
        }

        if (counts.isEmpty()) {
            if (itemFilter != null) {
                return "Companion has no " + itemFilter + " in inventory.";
            }
            return "Companion inventory is empty (0/" + compInv.getContainerSize() + " slots used).";
        }

        for (var entry : counts.entrySet()) {
            sb.append("  - ").append(entry.getKey()).append(" x").append(entry.getValue()).append("\n");
        }
        sb.append("Total: ").append(totalItems).append(" item(s) in ")
                .append(usedSlots).append("/").append(compInv.getContainerSize()).append(" slots.");

        return sb.toString();
    }

    /**
     * Check if an ItemStack matches the filter string by registry path or display name.
     */
    private boolean matchesFilter(ItemStack stack, String filter) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).getPath().toLowerCase();
        String displayName = stack.getHoverName().getString().toLowerCase();
        String normalized = filter.replace(" ", "_");

        return path.equals(normalized) || path.contains(normalized)
                || displayName.toLowerCase().contains(filter);
    }
}
