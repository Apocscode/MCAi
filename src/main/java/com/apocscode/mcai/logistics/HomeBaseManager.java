package com.apocscode.mcai.logistics;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Manages the companion's home base infrastructure.
 *
 * When the companion is asked to craft anything, this manager checks if
 * essential crafting blocks exist at the home area and sets them up if missing:
 *   1. Crafting Table (4 planks or 1 log — any wood type)
 *   2. Furnace (8 cobblestone or cobbled deepslate)
 *   3. Cauldron (7 iron ingots)
 *   4. Double Chest (2 × 8 planks = 16 planks or 4 logs)
 *
 * Behaviour:
 *   - Re-checks on every craft request (blocks may have been moved / broken)
 *   - If near home: crafts missing blocks and places them in an organized row
 *   - If far from home: crafts blocks and carries them until returning
 *   - Pulls materials from tagged STORAGE containers when inventory is short
 *   - Chests are auto-tagged as STORAGE after placement
 *   - Best-effort: skips blocks whose materials aren't available yet
 */
public class HomeBaseManager {

    /** Radius to scan around home for existing infrastructure blocks. */
    private static final int HOME_SCAN_RADIUS = 8;
    /** Squared distance beyond which the companion is "far from home". */
    private static final int FAR_FROM_HOME_DIST_SQ = 48 * 48;

    // ========== Plank / Log definitions ==========

    private static final Set<Item> PLANK_ITEMS = Set.of(
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS,
            Items.JUNGLE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS,
            Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS, Items.BAMBOO_PLANKS,
            Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    );

    private static final Map<Item, Item> LOG_TO_PLANK = Map.ofEntries(
            Map.entry(Items.OAK_LOG, Items.OAK_PLANKS),
            Map.entry(Items.SPRUCE_LOG, Items.SPRUCE_PLANKS),
            Map.entry(Items.BIRCH_LOG, Items.BIRCH_PLANKS),
            Map.entry(Items.JUNGLE_LOG, Items.JUNGLE_PLANKS),
            Map.entry(Items.ACACIA_LOG, Items.ACACIA_PLANKS),
            Map.entry(Items.DARK_OAK_LOG, Items.DARK_OAK_PLANKS),
            Map.entry(Items.MANGROVE_LOG, Items.MANGROVE_PLANKS),
            Map.entry(Items.CHERRY_LOG, Items.CHERRY_PLANKS),
            Map.entry(Items.CRIMSON_STEM, Items.CRIMSON_PLANKS),
            Map.entry(Items.WARPED_STEM, Items.WARPED_PLANKS)
    );

    // ========== Main Entry Point ==========

    /**
     * Ensure essential crafting infrastructure exists at the companion's home area.
     * Called at the start of every craft request.
     *
     * @return status message for the craft log (empty if everything is already in place)
     */
    public static String ensureHomeInfrastructure(CompanionEntity companion) {
        if (!companion.hasHomePos()) {
            MCAi.LOGGER.debug("HomeBase: no home position set, skipping infrastructure check");
            return "";
        }

        Level level = companion.level();
        if (level.isClientSide()) return "";

        BlockPos home = companion.getHomePos();
        if (!level.isLoaded(home)) {
            MCAi.LOGGER.debug("HomeBase: home area chunks not loaded, skipping check");
            return "";
        }

        // === Scan home area for existing blocks ===
        boolean hasCraftingTable = findBlock(level, home, Blocks.CRAFTING_TABLE, HOME_SCAN_RADIUS) != null;
        boolean hasFurnace       = findBlock(level, home, Blocks.FURNACE, HOME_SCAN_RADIUS) != null;
        boolean hasCauldron      = findBlock(level, home, Blocks.CAULDRON, HOME_SCAN_RADIUS) != null;
        int chestCount           = countBlocks(level, home, Blocks.CHEST, HOME_SCAN_RADIUS);

        if (hasCraftingTable && hasFurnace && hasCauldron && chestCount >= 2) {
            MCAi.LOGGER.debug("HomeBase: all infrastructure present at home");
            return "";
        }

        boolean farFromHome = companion.blockPosition().distSqr(home) > FAR_FROM_HOME_DIST_SQ;
        SimpleContainer inv = companion.getCompanionInventory();
        StringBuilder log = new StringBuilder();

        MCAi.LOGGER.info("HomeBase: infrastructure check at home={} — table={}, furnace={}, cauldron={}, chests={}/2, far={}",
                home, hasCraftingTable, hasFurnace, hasCauldron, chestCount, farFromHome);

        // === Phase 1: Craft missing items into companion inventory ===

        // 1. Crafting Table (2×2, no prerequisite, 4 planks or 1 log)
        if (!hasCraftingTable && !hasItem(inv, Items.CRAFTING_TABLE)) {
            if (ensureCraftCraftingTable(companion, inv)) {
                log.append("Crafted Crafting Table. ");
            }
        }

        // 2. Furnace (8 cobblestone / cobbled deepslate)
        if (!hasFurnace && !hasItem(inv, Items.FURNACE)) {
            if (ensureCraftFurnace(companion, inv)) {
                log.append("Crafted Furnace. ");
            }
        }

        // 3. Cauldron (7 iron ingots — may not be available early game)
        if (!hasCauldron && !hasItem(inv, Items.CAULDRON)) {
            if (ensureCraftCauldron(companion, inv)) {
                log.append("Crafted Cauldron. ");
            }
        }

        // 4. Chests (8 planks each — need up to 2 for double chest)
        int chestsNeeded = Math.max(0, 2 - chestCount);
        int chestsInInv = countItem(inv, Items.CHEST);
        int chestsToCraft = Math.max(0, chestsNeeded - chestsInInv);
        for (int i = 0; i < chestsToCraft; i++) {
            if (craftOneChest(companion, inv)) {
                log.append("Crafted Chest. ");
            } else {
                break; // can't get more materials
            }
        }

        // === Phase 2: If far from home, carry blocks for later ===
        if (farFromHome) {
            if (log.length() > 0) {
                log.append("Far from home — will place blocks when returning. ");
                MCAi.LOGGER.info("HomeBase: crafted infrastructure, carrying until near home");
            }
            return log.toString();
        }

        // === Phase 3: Place crafted blocks at home ===
        List<BlockAndItem> toPlace = new ArrayList<>();

        if (!hasCraftingTable && hasItem(inv, Items.CRAFTING_TABLE)) {
            toPlace.add(new BlockAndItem(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE, false));
        }
        if (!hasFurnace && hasItem(inv, Items.FURNACE)) {
            toPlace.add(new BlockAndItem(Blocks.FURNACE, Items.FURNACE, false));
        }
        if (!hasCauldron && hasItem(inv, Items.CAULDRON)) {
            toPlace.add(new BlockAndItem(Blocks.CAULDRON, Items.CAULDRON, false));
        }

        // Chests go last so they're adjacent → double chest
        int chestsAvailable = countItem(inv, Items.CHEST);
        int chestsToPlace = Math.min(chestsNeeded, chestsAvailable);
        for (int i = 0; i < chestsToPlace; i++) {
            toPlace.add(new BlockAndItem(Blocks.CHEST, Items.CHEST, true));
        }

        if (toPlace.isEmpty()) return log.toString();

        // Special handling: if only 1 chest needed and 1 already exists,
        // try to place new chest adjacent to existing one
        if (chestsToPlace == 1 && chestCount == 1) {
            BlockPos existingChest = findBlock(level, home, Blocks.CHEST, HOME_SCAN_RADIUS);
            if (existingChest != null) {
                BlockPos adjPos = findAdjacentPlaceable(level, existingChest);
                if (adjPos != null) {
                    // Place non-chest blocks normally, then the chest adjacent to existing
                    List<BlockAndItem> nonChest = new ArrayList<>();
                    for (BlockAndItem bai : toPlace) {
                        if (!bai.tagAsStorage) nonChest.add(bai);
                    }
                    List<BlockPos> positions = findLayoutPositions(level, home, nonChest.size());
                    int placed = placeBlocks(companion, level, nonChest, positions, log);

                    // Place the chest adjacent to the existing one
                    consumeItem(inv, Items.CHEST, 1);
                    level.setBlockAndUpdate(adjPos, Blocks.CHEST.defaultBlockState());
                    companion.addTaggedBlock(adjPos, TaggedBlock.Role.STORAGE);
                    log.append("Placed Chest adjacent to existing one (double chest). ");
                    placed++;

                    if (placed > 0) {
                        MCAi.LOGGER.info("HomeBase: placed {} infrastructure blocks at home", placed);
                    }
                    return log.toString();
                }
            }
        }

        // Normal placement: find a row of spots and place everything
        List<BlockPos> positions = findLayoutPositions(level, home, toPlace.size());
        int placed = placeBlocks(companion, level, toPlace, positions, log);

        if (placed > 0) {
            MCAi.LOGGER.info("HomeBase: placed {} infrastructure blocks at home", placed);
        }

        return log.toString();
    }

    // ========== Crafting Methods ==========

    /**
     * Ensure a Crafting Table exists in companion inventory.
     * Accepts any plank type (4) or any log type (1 → converted to table directly).
     */
    private static boolean ensureCraftCraftingTable(CompanionEntity companion, SimpleContainer inv) {
        // Check planks in inventory
        if (countAnyPlank(inv) >= 4) {
            consumeAnyPlank(inv, 4);
            inv.addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
            MCAi.LOGGER.info("HomeBase: crafted Crafting Table from planks in inventory");
            return true;
        }

        // Pull planks from storage
        pullAnyPlankFromStorage(companion, 4 - countAnyPlank(inv));
        if (countAnyPlank(inv) >= 4) {
            consumeAnyPlank(inv, 4);
            inv.addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
            MCAi.LOGGER.info("HomeBase: crafted Crafting Table from storage planks");
            return true;
        }

        // Try logs in inventory (1 log = crafting table directly)
        if (countAnyLog(inv) >= 1) {
            consumeAnyLog(inv, 1);
            inv.addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
            MCAi.LOGGER.info("HomeBase: crafted Crafting Table from log in inventory");
            return true;
        }

        // Pull logs from storage
        pullAnyLogFromStorage(companion, 1);
        if (countAnyLog(inv) >= 1) {
            consumeAnyLog(inv, 1);
            inv.addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
            MCAi.LOGGER.info("HomeBase: crafted Crafting Table from storage log");
            return true;
        }

        MCAi.LOGGER.debug("HomeBase: cannot craft Crafting Table — no planks or logs available");
        return false;
    }

    /**
     * Ensure a Furnace exists in companion inventory.
     * Needs 8 cobblestone or cobbled deepslate (or mix).
     * Full chain: check inventory → pull from storage → use available stone.
     */
    private static boolean ensureCraftFurnace(CompanionEntity companion, SimpleContainer inv) {
        int needed = 8;
        int have = countItem(inv, Items.COBBLESTONE) + countItem(inv, Items.COBBLED_DEEPSLATE);

        // Step 1: Pull cobblestone from storage
        if (have < needed) {
            ItemRoutingHelper.pullItemFromStorage(companion, Items.COBBLESTONE, needed - have);
            have = countItem(inv, Items.COBBLESTONE) + countItem(inv, Items.COBBLED_DEEPSLATE);
        }

        // Step 2: Pull cobbled deepslate from storage
        if (have < needed) {
            ItemRoutingHelper.pullItemFromStorage(companion, Items.COBBLED_DEEPSLATE, needed - have);
            have = countItem(inv, Items.COBBLESTONE) + countItem(inv, Items.COBBLED_DEEPSLATE);
        }

        // Step 3: Try pulling regular stone and converting (stone can substitute)
        if (have < needed) {
            int stoneNeeded = needed - have;
            ItemRoutingHelper.pullItemFromStorage(companion, Items.STONE, stoneNeeded);
            int stoneHave = countItem(inv, Items.STONE);
            // Stone works as cobblestone substitute in vanilla furnace crafting context
            // but to be safe, count it toward our total
            have += stoneHave;
        }

        if (have < needed) {
            MCAi.LOGGER.info("HomeBase: cannot craft Furnace yet — only {}/{} cobblestone/stone (will retry later)", have, needed);
            return false;
        }

        // Consume 8 stone materials (prefer cobblestone first, then deepslate, then stone)
        int remaining = needed;
        remaining -= consumeItemUpTo(inv, Items.COBBLESTONE, remaining);
        remaining -= consumeItemUpTo(inv, Items.COBBLED_DEEPSLATE, remaining);
        remaining -= consumeItemUpTo(inv, Items.STONE, remaining);

        inv.addItem(new ItemStack(Items.FURNACE, 1));
        MCAi.LOGGER.info("HomeBase: crafted Furnace from cobblestone/deepslate/stone");
        return true;
    }

    /**
     * Ensure a Cauldron exists in companion inventory.
     * Needs 7 iron ingots. Full chain: pull ingots from storage → pull raw iron
     * from storage → virtual smelt raw iron → craft cauldron.
     */
    private static boolean ensureCraftCauldron(CompanionEntity companion, SimpleContainer inv) {
        int needed = 7;
        int have = countItem(inv, Items.IRON_INGOT);

        // Step 1: Pull iron ingots from storage
        if (have < needed) {
            ItemRoutingHelper.pullItemFromStorage(companion, Items.IRON_INGOT, needed - have);
            have = countItem(inv, Items.IRON_INGOT);
        }

        // Step 2: Pull raw iron from storage and virtual-smelt it (1 raw → 1 ingot)
        if (have < needed) {
            int rawNeeded = needed - have;
            ItemRoutingHelper.pullItemFromStorage(companion, Items.RAW_IRON, rawNeeded);
            int rawHave = countItem(inv, Items.RAW_IRON);
            if (rawHave > 0) {
                int toSmelt = Math.min(rawHave, rawNeeded);
                consumeItem(inv, Items.RAW_IRON, toSmelt);
                inv.addItem(new ItemStack(Items.IRON_INGOT, toSmelt));
                MCAi.LOGGER.info("HomeBase: virtual-smelted {} raw iron → {} iron ingots", toSmelt, toSmelt);
                have = countItem(inv, Items.IRON_INGOT);
            }
        }

        if (have < needed) {
            MCAi.LOGGER.info("HomeBase: cannot craft Cauldron yet — only {}/{} iron ingots (will retry later)", have, needed);
            return false;
        }

        consumeItem(inv, Items.IRON_INGOT, needed);
        inv.addItem(new ItemStack(Items.CAULDRON, 1));
        MCAi.LOGGER.info("HomeBase: crafted Cauldron from {} iron ingots", needed);
        return true;
    }

    /**
     * Craft one Chest into companion inventory.
     * Called multiple times when more than one chest is needed.
     * Needs 8 planks (any type) — will convert logs if needed.
     */
    private static boolean craftOneChest(CompanionEntity companion, SimpleContainer inv) {
        int needed = 8;

        // Check planks in inventory
        if (countAnyPlank(inv) >= needed) {
            consumeAnyPlank(inv, needed);
            inv.addItem(new ItemStack(Items.CHEST, 1));
            MCAi.LOGGER.info("HomeBase: crafted Chest from planks");
            return true;
        }

        // Pull planks from storage
        pullAnyPlankFromStorage(companion, needed - countAnyPlank(inv));
        if (countAnyPlank(inv) >= needed) {
            consumeAnyPlank(inv, needed);
            inv.addItem(new ItemStack(Items.CHEST, 1));
            MCAi.LOGGER.info("HomeBase: crafted Chest from storage planks");
            return true;
        }

        // Try converting logs to planks (need ceil((8 - havePlanks) / 4) logs)
        int planksShort = needed - countAnyPlank(inv);
        int logsNeeded = (int) Math.ceil(planksShort / 4.0);
        if (logsNeeded > 0) {
            // Pull logs from storage if needed
            if (countAnyLog(inv) < logsNeeded) {
                pullAnyLogFromStorage(companion, logsNeeded - countAnyLog(inv));
            }
            convertLogsToPlanks(inv, logsNeeded);
        }

        if (countAnyPlank(inv) >= needed) {
            consumeAnyPlank(inv, needed);
            inv.addItem(new ItemStack(Items.CHEST, 1));
            MCAi.LOGGER.info("HomeBase: crafted Chest from logs → planks");
            return true;
        }

        MCAi.LOGGER.debug("HomeBase: cannot craft Chest — not enough planks or logs");
        return false;
    }

    // ========== Block Scanning ==========

    /**
     * Find the first occurrence of a block type near the home center.
     * Only searches within the companion's home area bounds if defined,
     * otherwise uses the scan radius.
     */
    @Nullable
    private static BlockPos findBlock(Level level, BlockPos center, Block target, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() == target) {
                        MCAi.LOGGER.info("HomeBase: found {} at {}",
                                target.getName().getString(), pos);
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /** Count how many blocks of a type exist near center. */
    private static int countBlocks(Level level, BlockPos center, Block target, int radius) {
        int count = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() == target) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // ========== Layout Placement ==========

    /**
     * Place blocks at the given positions, consuming items from companion inventory.
     * @return number of blocks actually placed
     */
    private static int placeBlocks(CompanionEntity companion, Level level,
                                    List<BlockAndItem> toPlace, List<BlockPos> positions,
                                    StringBuilder log) {
        SimpleContainer inv = companion.getCompanionInventory();
        int placed = 0;
        for (int i = 0; i < Math.min(toPlace.size(), positions.size()); i++) {
            BlockAndItem bai = toPlace.get(i);
            BlockPos pos = positions.get(i);

            consumeItem(inv, bai.item, 1);
            level.setBlockAndUpdate(pos, bai.block.defaultBlockState());

            if (bai.tagAsStorage) {
                companion.addTaggedBlock(pos, TaggedBlock.Role.STORAGE);
                log.append("Placed & tagged Chest as STORAGE at home. ");
            } else {
                log.append("Placed ").append(bai.item.getDescription().getString()).append(" at home. ");
            }
            placed++;
        }
        return placed;
    }

    /**
     * Find a row of consecutive placeable spots near center.
     * ALL blocks are placed on the SAME Y level, side by side.
     * Tries X axis first, then Z axis, at the home center Y level.
     * Falls back to individual same-Y spots if no contiguous row is found.
     */
    private static List<BlockPos> findLayoutPositions(Level level, BlockPos center, int count) {
        if (count <= 0) return List.of();

        // Use the home center Y level — all blocks on the same Y
        int homeY = center.getY();

        // Try to find a contiguous row at the exact home Y level
        BlockPos rowCenter = new BlockPos(center.getX(), homeY, center.getZ());

        // Try X axis first
        List<BlockPos> row = findRow(level, rowCenter, count, true);
        if (row != null) {
            MCAi.LOGGER.info("HomeBase: found row of {} spots along X at Y={}", count, homeY);
            return row;
        }

        // Try Z axis
        row = findRow(level, rowCenter, count, false);
        if (row != null) {
            MCAi.LOGGER.info("HomeBase: found row of {} spots along Z at Y={}", count, homeY);
            return row;
        }

        // Fallback: find individual spots at the SAME Y level only
        MCAi.LOGGER.info("HomeBase: no contiguous row found, using individual spots at Y={}", homeY);
        List<BlockPos> spots = new ArrayList<>();
        Set<BlockPos> used = new HashSet<>();
        for (int radius = 1; radius <= 6 && spots.size() < count; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) continue;
                    BlockPos pos = new BlockPos(center.getX() + x, homeY, center.getZ() + z);
                    if (!used.contains(pos) && isPlaceable(level, pos)) {
                        spots.add(pos);
                        used.add(pos);
                        if (spots.size() >= count) return spots;
                    }
                }
            }
        }
        return spots;
    }

    /**
     * Find the first contiguous run of {@code count} placeable spots along one axis.
     */
    @Nullable
    private static List<BlockPos> findRow(Level level, BlockPos center, int count, boolean alongX) {
        int dx = alongX ? 1 : 0;
        int dz = alongX ? 0 : 1;

        List<BlockPos> consecutive = new ArrayList<>();
        for (int i = -6; i <= 6; i++) {
            BlockPos pos = center.offset(i * dx, 0, i * dz);
            if (isPlaceable(level, pos)) {
                consecutive.add(pos);
                if (consecutive.size() >= count) {
                    return new ArrayList<>(consecutive.subList(consecutive.size() - count, consecutive.size()));
                }
            } else {
                consecutive.clear();
            }
        }
        return null;
    }

    /** Find an air block adjacent (N/S/E/W) to a position, on the SAME Y level, on solid ground. */
    @Nullable
    private static BlockPos findAdjacentPlaceable(Level level, BlockPos pos) {
        // Only check same-Y neighbors (N/S/E/W, not above/below)
        for (BlockPos adj : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west()}) {
            if (adj.getY() == pos.getY() && isPlaceable(level, adj)) return adj;
        }
        return null;
    }

    /** Check if a position is suitable for block placement (air with solid below). */
    private static boolean isPlaceable(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        if (!level.getBlockState(pos).isAir()) return false;
        BlockPos below = pos.below();
        return level.getBlockState(below).isSolidRender(level, below);
    }

    // ========== Storage Pull Helpers ==========

    /** Pull any plank type from storage into companion inventory. */
    private static int pullAnyPlankFromStorage(CompanionEntity companion, int needed) {
        int pulled = 0;
        for (Item plank : PLANK_ITEMS) {
            if (pulled >= needed) break;
            pulled += ItemRoutingHelper.pullItemFromStorage(companion, plank, needed - pulled);
        }
        return pulled;
    }

    /** Pull any log type from storage into companion inventory. */
    private static int pullAnyLogFromStorage(CompanionEntity companion, int needed) {
        int pulled = 0;
        for (Item log : LOG_TO_PLANK.keySet()) {
            if (pulled >= needed) break;
            pulled += ItemRoutingHelper.pullItemFromStorage(companion, log, needed - pulled);
        }
        return pulled;
    }

    // ========== Inventory Helpers ==========

    private static boolean hasItem(SimpleContainer inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) return true;
        }
        return false;
    }

    private static int countItem(SimpleContainer inv, Item item) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private static int countAnyPlank(SimpleContainer inv) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && PLANK_ITEMS.contains(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    private static int countAnyLog(SimpleContainer inv) {
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && LOG_TO_PLANK.containsKey(stack.getItem())) count += stack.getCount();
        }
        return count;
    }

    private static void consumeItem(SimpleContainer inv, Item item, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                remaining -= take;
            }
        }
    }

    /** Consume up to {@code max} of an item. Returns the amount actually consumed. */
    private static int consumeItemUpTo(SimpleContainer inv, Item item, int max) {
        int consumed = 0;
        for (int i = 0; i < inv.getContainerSize() && consumed < max; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(max - consumed, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                consumed += take;
            }
        }
        return consumed;
    }

    private static void consumeAnyPlank(SimpleContainer inv, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && PLANK_ITEMS.contains(stack.getItem())) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                remaining -= take;
            }
        }
    }

    private static void consumeAnyLog(SimpleContainer inv, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && LOG_TO_PLANK.containsKey(stack.getItem())) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                remaining -= take;
            }
        }
    }

    /**
     * Convert logs in inventory to planks (1 log → 4 planks).
     * Converts up to {@code maxLogs} log items.
     */
    private static void convertLogsToPlanks(SimpleContainer inv, int maxLogs) {
        int converted = 0;
        for (int i = 0; i < inv.getContainerSize() && converted < maxLogs; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && LOG_TO_PLANK.containsKey(stack.getItem())) {
                Item plank = LOG_TO_PLANK.get(stack.getItem());
                int logs = Math.min(maxLogs - converted, stack.getCount());
                stack.shrink(logs);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                inv.addItem(new ItemStack(plank, logs * 4));
                converted += logs;
            }
        }
        if (converted > 0) {
            MCAi.LOGGER.info("HomeBase: converted {} logs to {} planks", converted, converted * 4);
        }
    }

    // ========== Inner Types ==========

    /** A block to place at home with its corresponding item and whether to tag as STORAGE. */
    private record BlockAndItem(Block block, Item item, boolean tagAsStorage) {}
}
