package com.apocscode.mcai.logistics;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;

/**
 * Utility for routing items to tagged logistics containers.
 *
 * Priority order:
 *   1. OUTPUT containers (sorted destinations)
 *   2. STORAGE containers (general storage)
 *   3. Companion inventory (fallback)
 *
 * Uses NeoForge IItemHandler capability for container interaction.
 * Items are inserted directly if the container is loaded; no pathfinding needed
 * since we interact with the block entity remotely (within the loaded chunk).
 */
public class ItemRoutingHelper {

    /**
     * Route items to the best available tagged container.
     * Tries OUTPUT first, then STORAGE, then companion inventory as fallback.
     *
     * @param companion The companion entity (owns the tagged blocks list)
     * @param stack     The items to route (will be MODIFIED — shrunk as items are deposited)
     * @return Description of what happened (e.g. "Deposited 9x Stone Pickaxe into storage")
     */
    public static String routeToStorage(CompanionEntity companion, ItemStack stack) {
        if (stack.isEmpty()) return "";

        Level level = companion.level();
        String itemName = stack.getItem().getDescription().getString();
        int originalCount = stack.getCount();

        // Try OUTPUT containers first
        int deposited = tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.OUTPUT);

        // Then STORAGE containers
        if (!stack.isEmpty()) {
            deposited += tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.STORAGE);
        }

        // Fallback: companion inventory
        if (!stack.isEmpty()) {
            SimpleContainer inv = companion.getCompanionInventory();
            ItemStack remainder = inv.addItem(stack.copy());
            int toInv = stack.getCount() - remainder.getCount();
            stack.setCount(remainder.getCount());

            if (toInv > 0 && deposited == 0) {
                return "Stored " + toInv + "x " + itemName + " in companion inventory (no tagged containers found).";
            }
        }

        if (deposited > 0) {
            String target = deposited == originalCount ? "storage" : "storage (partial)";
            return "Deposited " + deposited + "x " + itemName + " into tagged " + target + ".";
        }

        return "";
    }

    /**
     * Try to insert the stack into all tagged containers of the given role.
     * Modifies the input stack (shrinks it as items are inserted).
     *
     * @return Number of items successfully inserted
     */
    public static int tryInsertIntoTagged(Level level, CompanionEntity companion,
                                           ItemStack stack, TaggedBlock.Role role) {
        List<TaggedBlock> containers = companion.getTaggedBlocks(role);
        int totalInserted = 0;

        for (TaggedBlock tb : containers) {
            if (stack.isEmpty()) break;

            BlockPos pos = tb.pos();

            // Only interact with loaded chunks
            if (!level.isLoaded(pos)) continue;

            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler == null) continue;

            // Try to insert into each slot
            ItemStack remaining = stack.copy();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                remaining = handler.insertItem(slot, remaining, false);
                if (remaining.isEmpty()) break;
            }

            int inserted = stack.getCount() - remaining.getCount();
            if (inserted > 0) {
                stack.shrink(inserted);
                totalInserted += inserted;
                MCAi.LOGGER.debug("Routed {}x {} to {} container at {}",
                        inserted, stack.getItem().getDescription().getString(),
                        role.getLabel(), pos.toShortString());
            }
        }

        return totalInserted;
    }

    /**
     * Check if any OUTPUT or STORAGE containers are tagged and available.
     */
    public static boolean hasTaggedStorage(CompanionEntity companion) {
        return !companion.getTaggedBlocks(TaggedBlock.Role.OUTPUT).isEmpty()
                || !companion.getTaggedBlocks(TaggedBlock.Role.STORAGE).isEmpty();
    }

    /**
     * Route all items in the companion's inventory to tagged storage.
     * Useful after task completion (e.g. chopping trees, mining, farming).
     *
     * @return Total number of items deposited
     */
    public static int routeAllCompanionItems(CompanionEntity companion) {
        if (!hasTaggedStorage(companion)) return 0;

        Level level = companion.level();
        SimpleContainer inv = companion.getCompanionInventory();
        int totalDeposited = 0;

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            int before = stack.getCount();
            int deposited = tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.OUTPUT);
            if (!stack.isEmpty()) {
                deposited += tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.STORAGE);
            }

            if (deposited > 0) {
                // Update the inventory slot
                if (stack.isEmpty()) {
                    inv.setItem(i, ItemStack.EMPTY);
                } else {
                    inv.getItem(i).setCount(stack.getCount());
                }
                totalDeposited += deposited;
            }
        }

        return totalDeposited;
    }
}
