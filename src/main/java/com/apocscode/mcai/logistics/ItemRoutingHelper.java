package com.apocscode.mcai.logistics;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

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

        // Ensure we have storage — auto-craft and place a chest if needed
        if (!hasTaggedStorage(companion)) {
            if (ensureStorageAvailable(companion)) {
                MCAi.LOGGER.info("Auto-created storage chest for item routing");
            }
        }

        // Try OUTPUT containers first
        int deposited = tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.OUTPUT);

        // Then STORAGE containers
        if (!stack.isEmpty()) {
            deposited += tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.STORAGE);
        }

        // If all chests are full, try to expand by crafting and placing another chest
        if (!stack.isEmpty() && deposited > 0) {
            // We had storage but it's full — try to create more
            if (ensureStorageAvailable(companion, true)) {
                MCAi.LOGGER.info("Storage full — auto-expanded with new chest");
                deposited += tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.STORAGE);
            }
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
        // Ensure we have storage — auto-craft and place a chest if needed
        if (!hasTaggedStorage(companion)) {
            if (ensureStorageAvailable(companion)) {
                MCAi.LOGGER.info("Auto-created storage chest for bulk item routing");
            }
        }
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

            // If chests are full and items remain, try to expand storage
            if (!stack.isEmpty() && deposited > 0) {
                if (ensureStorageAvailable(companion, true)) {
                    MCAi.LOGGER.info("Storage full during bulk routing — expanded with new chest");
                    deposited += tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.STORAGE);
                }
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

    // ========== Auto-craft and place chest when no storage exists ==========

    /** Any plank type counts for chest crafting. */
    private static final Set<Item> PLANK_ITEMS = Set.of(
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
            Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
            Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS,
            Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );

    /**
     * Ensure there's STORAGE container capacity available.
     * If no tagged storage exists, or if called for expansion (existing chests full),
     * tries to auto-craft a chest from 8 planks in companion inventory,
     * place it near the companion's home position (or current position), and auto-tag it as STORAGE.
     *
     * @return true if a new storage chest was created (or existing storage was already available for first call)
     */
    public static boolean ensureStorageAvailable(CompanionEntity companion) {
        return ensureStorageAvailable(companion, false);
    }

    /**
     * @param forceNew If true, always try to create a new chest (for expansion when existing ones are full)
     */
    public static boolean ensureStorageAvailable(CompanionEntity companion, boolean forceNew) {
        if (!forceNew && hasTaggedStorage(companion)) return true;

        Level level = companion.level();
        if (level.isClientSide()) return false;

        SimpleContainer inv = companion.getCompanionInventory();

        // Check if companion already has a chest in inventory
        boolean hasChest = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isChestItem(stack.getItem())) {
                hasChest = true;
                break;
            }
        }

        // If no chest, try to craft one from 8 planks (any type)
        if (!hasChest) {
            int plankCount = countPlanks(inv);
            if (plankCount < 8) {
                MCAi.LOGGER.debug("Cannot auto-craft chest: only {} planks (need 8)", plankCount);
                return false;
            }

            // Consume 8 planks (any mix of types)
            consumePlanks(inv, 8);

            // Add a chest to companion inventory
            inv.addItem(new ItemStack(Items.CHEST, 1));
            MCAi.LOGGER.info("Auto-crafted a chest from planks for storage placement");
        }

        // Determine placement location: prefer home position, fallback to current position
        BlockPos center;
        if (companion.hasHomePos()) {
            center = companion.getHomePos();
            MCAi.LOGGER.info("Placing storage chest near home position: {}", center);
        } else {
            center = companion.blockPosition();
            MCAi.LOGGER.info("No home position set — placing storage chest near companion at: {}", center);
        }

        // Find a suitable spot and place the chest
        BlockPos placePos = findPlaceableSpot(level, center);
        if (placePos == null) {
            // Try around current position if home was far/unsuitable
            if (companion.hasHomePos()) {
                placePos = findPlaceableSpot(level, companion.blockPosition());
            }
            if (placePos == null) {
                MCAi.LOGGER.warn("No suitable spot to place storage chest");
                return false;
            }
        }

        // Consume the chest from inventory and place it
        boolean consumed = consumeChestFromInventory(inv);
        if (!consumed) return false;

        level.setBlockAndUpdate(placePos, Blocks.CHEST.defaultBlockState());
        MCAi.LOGGER.info("Auto-placed storage chest at {}", placePos);

        // Auto-tag the placed chest as STORAGE
        companion.addTaggedBlock(placePos, TaggedBlock.Role.STORAGE);
        MCAi.LOGGER.info("Auto-tagged chest at {} as STORAGE", placePos);

        return true;
    }

    /**
     * Find a suitable air block near center with solid ground below.
     * Searches in expanding rings from 1 to 4 blocks out.
     */
    @Nullable
    private static BlockPos findPlaceableSpot(Level level, BlockPos center) {
        // Must be in a loaded chunk
        if (!level.isLoaded(center)) return null;

        for (int radius = 1; radius <= 4; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) continue;
                    for (int y = -1; y <= 2; y++) {
                        BlockPos candidate = center.offset(x, y, z);
                        if (!level.isLoaded(candidate)) continue;
                        BlockPos below = candidate.below();
                        if (level.getBlockState(candidate).isAir()
                                && level.getBlockState(below).isSolidRender(level, below)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        // Try directly adjacent (in case center itself was blocked)
        BlockPos above = center.above();
        if (level.isLoaded(above) && level.getBlockState(above).isAir()
                && level.getBlockState(center).isSolidRender(level, center)) {
            return above;
        }
        return null;
    }

    private static boolean isChestItem(Item item) {
        return item == Items.CHEST || item == Items.TRAPPED_CHEST;
    }

    private static int countPlanks(SimpleContainer inv) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isPlanks(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean isPlanks(Item item) {
        if (PLANK_ITEMS.contains(item)) return true;
        // Fallback: check registry path for modded planks
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id != null && id.getPath().contains("planks");
    }

    private static void consumePlanks(SimpleContainer inv, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isPlanks(stack.getItem())) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                remaining -= take;
            }
        }
    }

    private static boolean consumeChestFromInventory(SimpleContainer inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isChestItem(stack.getItem())) {
                stack.shrink(1);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                return true;
            }
        }
        return false;
    }
}
