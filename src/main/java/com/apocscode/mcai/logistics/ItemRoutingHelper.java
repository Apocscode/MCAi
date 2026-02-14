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
     * Pull a specific item FROM tagged storage/home area containers INTO companion inventory.
     * This is the reverse of routeToStorage — retrieves items the companion needs.
     * Checks STORAGE and INPUT tagged containers, plus all containers in the home area.
     *
     * @param companion The companion entity
     * @param item      The item to pull
     * @param maxCount  Maximum number to pull
     * @return Number of items actually transferred into companion inventory
     */
    public static int pullItemFromStorage(CompanionEntity companion, Item item, int maxCount) {
        if (maxCount <= 0) return 0;
        Level level = companion.level();
        SimpleContainer inv = companion.getCompanionInventory();
        int totalPulled = 0;
        java.util.Set<BlockPos> scanned = new java.util.HashSet<>();

        // Search tagged STORAGE containers first
        for (TaggedBlock tb : companion.getTaggedBlocks(TaggedBlock.Role.STORAGE)) {
            if (totalPulled >= maxCount) break;
            BlockPos pos = tb.pos();
            if (scanned.contains(pos) || !level.isLoaded(pos)) continue;
            scanned.add(pos);
            totalPulled += extractItemFromContainer(level, pos, inv, item, maxCount - totalPulled);
        }

        // Then INPUT containers
        for (TaggedBlock tb : companion.getTaggedBlocks(TaggedBlock.Role.INPUT)) {
            if (totalPulled >= maxCount) break;
            BlockPos pos = tb.pos();
            if (scanned.contains(pos) || !level.isLoaded(pos)) continue;
            scanned.add(pos);
            totalPulled += extractItemFromContainer(level, pos, inv, item, maxCount - totalPulled);
        }

        // Then home area containers
        if (totalPulled < maxCount && companion.hasHomeArea()) {
            BlockPos c1 = companion.getHomeCorner1();
            BlockPos c2 = companion.getHomeCorner2();
            if (c1 != null && c2 != null) {
                int minX = Math.min(c1.getX(), c2.getX());
                int minY = Math.min(c1.getY(), c2.getY());
                int minZ = Math.min(c1.getZ(), c2.getZ());
                int maxX = Math.max(c1.getX(), c2.getX());
                int maxY = Math.max(c1.getY(), c2.getY());
                int maxZ = Math.max(c1.getZ(), c2.getZ());
                for (int x = minX; x <= maxX && totalPulled < maxCount; x++) {
                    for (int y = minY; y <= maxY && totalPulled < maxCount; y++) {
                        for (int z = minZ; z <= maxZ && totalPulled < maxCount; z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (scanned.contains(pos) || !level.isLoaded(pos)) continue;
                            scanned.add(pos);
                            totalPulled += extractItemFromContainer(level, pos, inv, item, maxCount - totalPulled);
                        }
                    }
                }
            }
        }

        if (totalPulled > 0) {
            MCAi.LOGGER.info("Pulled {}x {} from storage into companion inventory",
                    totalPulled, item.getDescription().getString());
        }
        return totalPulled;
    }

    /**
     * Extract a specific item from a container at the given position into companion inventory.
     * Uses IItemHandler capability for NeoForge compatibility (works with modded containers).
     *
     * @return Number of items extracted
     */
    private static int extractItemFromContainer(Level level, BlockPos pos,
                                                 SimpleContainer inv, Item item, int maxExtract) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) return 0;

        int extracted = 0;
        for (int slot = 0; slot < handler.getSlots() && extracted < maxExtract; slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (inSlot.isEmpty() || inSlot.getItem() != item) continue;

            int toExtract = Math.min(maxExtract - extracted, inSlot.getCount());
            ItemStack pulled = handler.extractItem(slot, toExtract, false);
            if (pulled.isEmpty()) continue;

            ItemStack remainder = inv.addItem(pulled);
            int actuallyInserted = pulled.getCount() - remainder.getCount();
            extracted += actuallyInserted;

            // If inventory was full, put the remainder back
            if (!remainder.isEmpty()) {
                handler.insertItem(slot, remainder, false);
                break; // Inventory full
            }
        }
        return extracted;
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

    /**
     * Common mining byproducts that should always be deposited to storage,
     * even when a continuation plan is active. These are never needed for crafting chains.
     */
    private static final Set<Item> ALWAYS_DEPOSIT_ITEMS = Set.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT, Items.GRAVEL,
            Items.SAND, Items.RED_SAND, Items.CLAY_BALL, Items.FLINT,
            Items.DIORITE, Items.GRANITE, Items.ANDESITE, Items.TUFF,
            Items.STONE, Items.DEEPSLATE, Items.NETHERRACK, Items.BASALT,
            Items.BLACKSTONE, Items.CALCITE, Items.MUD, Items.SOUL_SAND,
            Items.SOUL_SOIL, Items.POINTED_DRIPSTONE
    );

    /**
     * Route non-essential items to storage while keeping items relevant to a continuation plan.
     * Used after mining tasks that have continuations (e.g., "mine iron" → "smelt" → "craft").
     * Always deposits common byproducts (cobblestone, dirt, gravel, etc.).
     * Keeps items whose names appear in the plan keywords.
     *
     * @param companion The companion entity
     * @param planContext The continuation plan context (e.g., "smelt raw_iron, then craft iron_pickaxe")
     * @return Total number of items deposited
     */
    public static int routeNonEssentialItems(CompanionEntity companion, String planContext) {
        if (!hasTaggedStorage(companion)) return 0;

        Level level = companion.level();
        SimpleContainer inv = companion.getCompanionInventory();
        int totalDeposited = 0;

        // Lowercase plan for matching
        String planLower = planContext != null ? planContext.toLowerCase() : "";

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            // Always deposit known byproducts
            boolean isJunk = ALWAYS_DEPOSIT_ITEMS.contains(stack.getItem());

            if (!isJunk) {
                // Check if this item's name appears in the plan — if so, keep it
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                String itemPath = itemId.getPath().toLowerCase(); // e.g. "raw_iron"
                if (planLower.contains(itemPath)) {
                    continue; // Keep this item — it's probably needed
                }

                // Also check the display name
                String displayName = stack.getItem().getDescription().getString().toLowerCase();
                // Check each word of the display name against the plan
                boolean matchesplan = false;
                for (String word : displayName.split("\\s+")) {
                    if (word.length() > 3 && planLower.contains(word)) {
                        matchesplan = true;
                        break;
                    }
                }
                if (matchesplan) {
                    continue; // Keep this item
                }
            }

            // This item is junk or not mentioned in the plan — deposit it
            int before = stack.getCount();
            int deposited = tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.OUTPUT);
            if (!stack.isEmpty()) {
                deposited += tryInsertIntoTagged(level, companion, stack, TaggedBlock.Role.STORAGE);
            }

            if (deposited > 0) {
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

    /** Log items that can be converted to planks (1 log = 4 planks). */
    private static final Set<Item> LOG_ITEMS = Set.of(
            Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG,
            Items.JUNGLE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG,
            Items.MANGROVE_LOG, Items.CHERRY_LOG,
            Items.CRIMSON_STEM, Items.WARPED_STEM
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

            // Try pulling planks from storage if we don't have enough
            if (plankCount < 8) {
                for (Item plankItem : PLANK_ITEMS) {
                    int needed = 8 - plankCount;
                    if (needed <= 0) break;
                    int pulled = pullItemFromStorage(companion, plankItem, needed);
                    plankCount += pulled;
                }
            }

            // Still not enough planks? Try pulling logs and converting to planks
            if (plankCount < 8) {
                int planksNeeded = 8 - plankCount;
                int logsNeeded = (planksNeeded + 3) / 4; // Each log makes 4 planks
                for (Item logItem : LOG_ITEMS) {
                    if (logsNeeded <= 0) break;
                    int pulled = pullItemFromStorage(companion, logItem, logsNeeded);
                    if (pulled > 0) {
                        // Convert logs to planks in inventory
                        consumeLogs(inv, logItem, pulled);
                        Item plankType = logToPlanks(logItem);
                        inv.addItem(new ItemStack(plankType, pulled * 4));
                        MCAi.LOGGER.info("Converted {}x {} to {}x planks for chest crafting",
                                pulled, BuiltInRegistries.ITEM.getKey(logItem), pulled * 4);
                        plankCount += pulled * 4;
                        logsNeeded -= pulled;
                    }
                }
            }

            if (plankCount < 8) {
                MCAi.LOGGER.debug("Cannot auto-craft chest: only {} planks (need 8), no logs available", plankCount);
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

    /** Consume a specific log type from inventory. */
    private static void consumeLogs(SimpleContainer inv, Item logItem, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == logItem) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                remaining -= take;
            }
        }
    }

    /** Map a log item to its corresponding plank item. */
    private static Item logToPlanks(Item log) {
        if (log == Items.OAK_LOG) return Items.OAK_PLANKS;
        if (log == Items.SPRUCE_LOG) return Items.SPRUCE_PLANKS;
        if (log == Items.BIRCH_LOG) return Items.BIRCH_PLANKS;
        if (log == Items.JUNGLE_LOG) return Items.JUNGLE_PLANKS;
        if (log == Items.ACACIA_LOG) return Items.ACACIA_PLANKS;
        if (log == Items.DARK_OAK_LOG) return Items.DARK_OAK_PLANKS;
        if (log == Items.MANGROVE_LOG) return Items.MANGROVE_PLANKS;
        if (log == Items.CHERRY_LOG) return Items.CHERRY_PLANKS;
        if (log == Items.CRIMSON_STEM) return Items.CRIMSON_PLANKS;
        if (log == Items.WARPED_STEM) return Items.WARPED_PLANKS;
        return Items.OAK_PLANKS; // fallback
    }
}
