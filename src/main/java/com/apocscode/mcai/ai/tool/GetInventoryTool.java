package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns the player's full inventory contents + companion inventory + storage summary.
 */
public class GetInventoryTool implements AiTool {

    @Override
    public String name() {
        return "get_inventory";
    }

    @Override
    public String description() {
        return "Get the player's and companion's complete inventory contents including equipped items (mainhand, offhand, armor). " +
                "Shows player inventory, companion inventory with equipped gear, and nearby storage containers. " +
                "Use this to check what items are available before crafting or to see what tools/armor are equipped.";
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

        // === Companion Inventory ===
        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        if (companion != null) {
            var compInv = companion.getCompanionInventory();
            Map<String, Integer> compCounts = new LinkedHashMap<>();
            Map<String, String> compNames = new LinkedHashMap<>();
            for (int i = 0; i < compInv.getContainerSize(); i++) {
                ItemStack stack = compInv.getItem(i);
                if (!stack.isEmpty()) {
                    String id = stack.getItem().toString();
                    String name = stack.getDisplayName().getString();
                    compCounts.merge(id, stack.getCount(), Integer::sum);
                    compNames.putIfAbsent(id, name);
                }
            }
            sb.append("\n=== Companion Inventory ===\n");

            // Equipped items (mainhand, offhand, armor)
            ItemStack compMainHand = companion.getMainHandItem();
            ItemStack compOffHand = companion.getOffhandItem();
            if (!compMainHand.isEmpty()) {
                sb.append("Equipped main hand: ").append(compMainHand.getDisplayName().getString())
                        .append(" x").append(compMainHand.getCount()).append("\n");
            }
            if (!compOffHand.isEmpty()) {
                sb.append("Equipped off hand: ").append(compOffHand.getDisplayName().getString())
                        .append(" x").append(compOffHand.getCount()).append("\n");
            }
            sb.append("Armor:\n");
            boolean hasArmor = false;
            for (ItemStack armor : companion.getArmorSlots()) {
                if (!armor.isEmpty()) {
                    sb.append("- ").append(armor.getDisplayName().getString()).append("\n");
                    hasArmor = true;
                }
            }
            if (!hasArmor) sb.append("- (none)\n");

            // Inventory items
            sb.append("\nInventory items:\n");
            if (compCounts.isEmpty()) {
                sb.append("(empty)\n");
            } else {
                for (Map.Entry<String, Integer> entry : compCounts.entrySet()) {
                    sb.append("- ").append(compNames.get(entry.getKey()))
                            .append(": ").append(entry.getValue()).append("\n");
                }
            }

            // === Storage containers (tagged STORAGE + home area) ===
            Map<String, Integer> storageCounts = new LinkedHashMap<>();
            Map<String, String> storageNames = new LinkedHashMap<>();
            java.util.Set<BlockPos> scanned = new java.util.HashSet<>();
            Level level = context.player().level();

            // Tagged STORAGE
            for (TaggedBlock tb : companion.getTaggedBlocks(TaggedBlock.Role.STORAGE)) {
                scanned.add(tb.pos());
                BlockEntity be = level.getBlockEntity(tb.pos());
                if (be instanceof Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack stack = container.getItem(i);
                        if (!stack.isEmpty()) {
                            String id = stack.getItem().toString();
                            String name = stack.getDisplayName().getString();
                            storageCounts.merge(id, stack.getCount(), Integer::sum);
                            storageNames.putIfAbsent(id, name);
                        }
                    }
                }
            }

            // Home area containers
            if (companion.hasHomeArea()) {
                BlockPos c1 = companion.getHomeCorner1();
                BlockPos c2 = companion.getHomeCorner2();
                if (c1 != null && c2 != null) {
                    int minX = Math.min(c1.getX(), c2.getX());
                    int minY = Math.min(c1.getY(), c2.getY());
                    int minZ = Math.min(c1.getZ(), c2.getZ());
                    int maxX = Math.max(c1.getX(), c2.getX());
                    int maxY = Math.max(c1.getY(), c2.getY());
                    int maxZ = Math.max(c1.getZ(), c2.getZ());
                    for (int x = minX; x <= maxX; x++) {
                        for (int y = minY; y <= maxY; y++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                BlockPos pos = new BlockPos(x, y, z);
                                if (scanned.contains(pos)) continue;
                                scanned.add(pos);
                                BlockEntity be = level.getBlockEntity(pos);
                                if (be instanceof Container container) {
                                    for (int i = 0; i < container.getContainerSize(); i++) {
                                        ItemStack stack = container.getItem(i);
                                        if (!stack.isEmpty()) {
                                            String id = stack.getItem().toString();
                                            String name = stack.getDisplayName().getString();
                                            storageCounts.merge(id, stack.getCount(), Integer::sum);
                                            storageNames.putIfAbsent(id, name);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!storageCounts.isEmpty()) {
                sb.append("\n=== Storage/Home Containers ===\n");
                for (Map.Entry<String, Integer> entry : storageCounts.entrySet()) {
                    sb.append("- ").append(storageNames.get(entry.getKey()))
                            .append(": ").append(entry.getValue()).append("\n");
                }
            }
        }

        return sb.toString();
    }
}
