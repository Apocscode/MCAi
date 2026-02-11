package com.apocscode.mcai.ai.tool;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns the player's full inventory contents.
 */
public class GetInventoryTool implements AiTool {

    @Override
    public String name() {
        return "get_inventory";
    }

    @Override
    public String description() {
        return "Get the player's complete inventory contents including armor and offhand. " +
                "Use this when you need to know exactly what items the player has, " +
                "check if they have specific materials, or help plan crafting.";
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

        StringBuilder sb = new StringBuilder();
        Inventory inv = context.player().getInventory();

        // Aggregate items by type
        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                String id = stack.getItem().toString();
                String name = stack.getDisplayName().getString();
                itemCounts.merge(id, stack.getCount(), Integer::sum);
                displayNames.putIfAbsent(id, name);
            }
        }

        sb.append("=== Player Inventory ===\n\n");

        // Held items
        ItemStack mainHand = context.player().getMainHandItem();
        ItemStack offHand = context.player().getOffhandItem();
        if (!mainHand.isEmpty()) {
            sb.append("Main hand: ").append(mainHand.getDisplayName().getString())
                    .append(" x").append(mainHand.getCount()).append("\n");
        }
        if (!offHand.isEmpty()) {
            sb.append("Off hand: ").append(offHand.getDisplayName().getString())
                    .append(" x").append(offHand.getCount()).append("\n");
        }

        // Armor
        sb.append("\nArmor:\n");
        for (ItemStack armor : context.player().getArmorSlots()) {
            if (!armor.isEmpty()) {
                sb.append("- ").append(armor.getDisplayName().getString()).append("\n");
            }
        }

        // All items aggregated
        sb.append("\nAll items (").append(itemCounts.size()).append(" types):\n");
        for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            String displayName = displayNames.get(entry.getKey());
            sb.append("- ").append(displayName).append(": ").append(entry.getValue()).append("\n");
        }

        // Empty slots
        int emptySlots = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.getItem(i).isEmpty()) emptySlots++;
        }
        sb.append("\nEmpty slots: ").append(emptySlots).append("/36\n");

        return sb.toString();
    }
}
