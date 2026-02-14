package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Task: Companion pathfinds to a nearby furnace, loads items + fuel,
 * waits for smelting to complete, and collects the output.
 *
 * Respects game balance — requires a real furnace, real fuel, and real
 * smelting time. No instant conversion.
 */
public class SmeltItemsTask extends CompanionTask {

    private enum Phase {
        NAVIGATING, INSERTING, WAITING, COLLECTING
    }

    private final Item inputItem;
    private final int count;
    private Phase phase;
    private BlockPos furnacePos;
    /** True if we auto-placed this furnace and should pick it up when done. */
    private boolean shouldPickUpFurnace = false;
    private int stuckTimer = 0;
    private int waitTimer = 0;
    private int itemsInserted = 0;
    private int itemsCollected = 0;

    private static final int SCAN_RANGE = 32;
    private static final double INTERACT_REACH = 2.5;
    private static final int MAX_WAIT_TICKS = 20 * 60 * 3; // 3 min max wait

    public SmeltItemsTask(CompanionEntity companion, Item inputItem, int count) {
        super(companion, "Smelt " + count + "x " + inputItem.getDescription().getString());
        this.inputItem = inputItem;
        this.count = count;
    }

    @Override
    public int getProgressPercent() {
        if (count == 0) return 100;
        return (itemsCollected * 100) / count;
    }

    @Override
    protected void start() {
        // Check companion has the items
        int available = BlockHelper.countItem(companion, inputItem);
        if (available < count) {
            fail("Not enough " + inputItem.getDescription().getString() +
                    " in inventory (have " + available + ", need " + count + ")");
            return;
        }

        // Check companion has fuel — if not, try to gather some
        if (!hasFuel()) {
            if (!tryGatherFuel()) {
                fail("No fuel available and couldn't find any nearby! " +
                        "I need coal, charcoal, wood, or any burnable item to smelt.");
                return;
            }
        }

        furnacePos = findNearbyFurnace();
        if (furnacePos == null) {
            // Make inventory space: route excess items to storage before trying to craft a furnace
            if (com.apocscode.mcai.logistics.ItemRoutingHelper.hasTaggedStorage(companion)) {
                int routed = com.apocscode.mcai.logistics.ItemRoutingHelper.routeAllCompanionItems(companion);
                if (routed > 0) {
                    MCAi.LOGGER.info("SmeltItemsTask: routed {} items to storage to free inventory space", routed);
                }
            }

            // If not enough cobblestone for a furnace, try multiple strategies
            int cobble = countCobblestoneInInventory();
            if (cobble < 8) {
                // Strategy 1: Mine nearby stone blocks
                int gathered = tryGatherCobblestone(8 - cobble);
                cobble += gathered;
                MCAi.LOGGER.info("Auto-gathered {} cobblestone for furnace (now have {}, need 8)",
                        gathered, cobble);
            }
            if (cobble < 8) {
                // Strategy 2: Pull cobblestone from tagged STORAGE containers
                int pulled = tryPullCobblestoneFromStorage(8 - cobble);
                cobble += pulled;
                if (pulled > 0) {
                    MCAi.LOGGER.info("Pulled {} cobblestone from storage (now have {}, need 8)", pulled, cobble);
                }
            }

            // Try to auto-craft and place a furnace
            furnacePos = tryAutoPlaceFurnace();
            if (furnacePos == null) {
                fail("No furnace found within " + SCAN_RANGE + " blocks " +
                        "and couldn't auto-craft one (need 8 cobblestone).");
                return;
            }
            // Only pick up if NOT placed near home — near home it stays permanently
            shouldPickUpFurnace = !isNearHome(furnacePos);
            if (!shouldPickUpFurnace) {
                MCAi.LOGGER.info("Furnace placed near home — will not pick up");
            }
        }

        Block furnaceBlock = companion.level().getBlockState(furnacePos).getBlock();
        String furnaceName = furnaceBlock instanceof BlastFurnaceBlock ? "blast furnace"
                : furnaceBlock instanceof SmokerBlock ? "smoker" : "furnace";

        phase = Phase.NAVIGATING;
        say("Found a " + furnaceName + "! Heading there to smelt " + count + "x "
                + inputItem.getDescription().getString());
        navigateTo(furnacePos);
    }

    @Override
    protected void tick() {
        if (phase == null) return;
        switch (phase) {
            case NAVIGATING -> tickNavigating();
            case INSERTING -> tickInserting();
            case WAITING -> tickWaiting();
            case COLLECTING -> tickCollecting();
        }
    }

    private void tickNavigating() {
        if (isInReach(furnacePos, INTERACT_REACH)) {
            phase = Phase.INSERTING;
            stuckTimer = 0;
            return;
        }
        stuckTimer++;
        if (stuckTimer % 20 == 0) {
            navigateTo(furnacePos);
        }
        if (stuckTimer > 200) { // 10 seconds stuck
            fail("Couldn't reach the furnace — path blocked.");
        }
    }

    private void tickInserting() {
        Level level = companion.level();
        BlockEntity be = level.getBlockEntity(furnacePos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            fail("The furnace is gone!");
            return;
        }

        SimpleContainer inv = companion.getCompanionInventory();

        // Collect any existing output first
        ItemStack existingOutput = furnace.getItem(2);
        if (!existingOutput.isEmpty()) {
            ItemStack remainder = inv.addItem(existingOutput.copy());
            if (remainder.isEmpty()) {
                furnace.setItem(2, ItemStack.EMPTY);
            } else {
                furnace.setItem(2, remainder);
            }
            furnace.setChanged();
        }

        // Insert fuel if needed
        insertFuel(furnace);

        // Insert input items
        ItemStack inputSlot = furnace.getItem(0);
        int toInsert = count - itemsInserted;

        for (int i = 0; i < inv.getContainerSize() && toInsert > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == inputItem) {
                if (inputSlot.isEmpty()) {
                    int move = Math.min(toInsert, stack.getCount());
                    move = Math.min(move, stack.getMaxStackSize());
                    furnace.setItem(0, new ItemStack(inputItem, move));
                    inputSlot = furnace.getItem(0);
                    stack.shrink(move);
                    if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                    toInsert -= move;
                    itemsInserted += move;
                } else if (ItemStack.isSameItemSameComponents(inputSlot, stack)) {
                    int space = inputSlot.getMaxStackSize() - inputSlot.getCount();
                    int move = Math.min(Math.min(toInsert, stack.getCount()), space);
                    if (move > 0) {
                        inputSlot.grow(move);
                        stack.shrink(move);
                        if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                        toInsert -= move;
                        itemsInserted += move;
                    }
                }
            }
        }

        furnace.setChanged();
        phase = Phase.WAITING;
        waitTimer = 0;
        say("Loaded " + itemsInserted + " items into the furnace. Waiting for smelting...");
    }

    private void tickWaiting() {
        waitTimer++;

        // Check every second
        if (waitTimer % 20 != 0) return;

        Level level = companion.level();
        BlockEntity be = level.getBlockEntity(furnacePos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            fail("The furnace was destroyed while smelting!");
            return;
        }

        // Stay near the furnace
        if (!isInReach(furnacePos, 4.0)) {
            navigateTo(furnacePos);
        }

        // Top up fuel if running out
        ItemStack fuelSlot = furnace.getItem(1);
        ItemStack input = furnace.getItem(0);
        if (fuelSlot.isEmpty() && !input.isEmpty()) {
            insertFuel(furnace);
        }

        // Check if smelting is done (input slot empty = all items processed)
        ItemStack output = furnace.getItem(2);
        if (input.isEmpty()) {
            phase = Phase.COLLECTING;
            return;
        }

        // Collect if output is getting full (prevent overflow loss)
        if (!output.isEmpty() && output.getCount() >= output.getMaxStackSize() - 4) {
            phase = Phase.COLLECTING;
            return;
        }

        if (waitTimer > MAX_WAIT_TICKS) {
            say("Smelting is taking too long. Collecting what's done so far.");
            phase = Phase.COLLECTING;
        }
    }

    private void tickCollecting() {
        Level level = companion.level();
        BlockEntity be = level.getBlockEntity(furnacePos);
        if (!(be instanceof AbstractFurnaceBlockEntity furnace)) {
            if (itemsCollected > 0) {
                complete();
            } else {
                fail("Furnace was destroyed before collecting output.");
            }
            return;
        }

        SimpleContainer inv = companion.getCompanionInventory();

        // Collect output
        ItemStack output = furnace.getItem(2);
        if (!output.isEmpty()) {
            int outputCount = output.getCount();
            ItemStack remainder = inv.addItem(output.copy());
            int collected = outputCount - (remainder.isEmpty() ? 0 : remainder.getCount());
            itemsCollected += collected;

            if (remainder.isEmpty()) {
                furnace.setItem(2, ItemStack.EMPTY);
            } else {
                furnace.setItem(2, remainder);
            }
            furnace.setChanged();
        }

        // Check if more items still being processed
        ItemStack input = furnace.getItem(0);
        if (!input.isEmpty()) {
            // Still smelting, go back to waiting
            phase = Phase.WAITING;
            return;
        }

        companion.playSound(SoundEvents.ITEM_PICKUP, 0.5F, 1.0F);

        // Pick up the furnace if we placed it
        if (shouldPickUpFurnace && furnacePos != null) {
            pickUpAutoPlacedFurnace();
        }

        say("Smelting complete! Collected " + itemsCollected + " smelted items.");
        complete();
    }

    // ========== Furnace finding ==========

    private BlockPos findNearbyFurnace() {
        BlockPos center = companion.blockPosition();
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int x = -SCAN_RANGE; x <= SCAN_RANGE; x++) {
            for (int y = -4; y <= 8; y++) {
                for (int z = -SCAN_RANGE; z <= SCAN_RANGE; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    Block block = companion.level().getBlockState(pos).getBlock();
                    if (block instanceof FurnaceBlock || block instanceof BlastFurnaceBlock
                            || block instanceof SmokerBlock) {
                        double dist = companion.distanceToSqr(
                                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                        if (dist < closestDist) {
                            closestDist = dist;
                            closest = pos;
                        }
                    }
                }
            }
        }
        return closest;
    }

    // ========== Fuel management ==========

    private boolean hasFuel() {
        SimpleContainer inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isFuel(stack)) return true;
        }
        return false;
    }

    private void insertFuel(AbstractFurnaceBlockEntity furnace) {
        SimpleContainer inv = companion.getCompanionInventory();
        ItemStack fuelSlot = furnace.getItem(1);

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && isFuel(stack)) {
                if (fuelSlot.isEmpty()) {
                    furnace.setItem(1, stack.copy());
                    inv.setItem(i, ItemStack.EMPTY);
                    furnace.setChanged();
                    return;
                } else if (ItemStack.isSameItemSameComponents(fuelSlot, stack)) {
                    int space = fuelSlot.getMaxStackSize() - fuelSlot.getCount();
                    int move = Math.min(space, stack.getCount());
                    if (move > 0) {
                        fuelSlot.grow(move);
                        stack.shrink(move);
                        if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                        furnace.setChanged();
                        return;
                    }
                }
            }
        }
    }

    private static boolean isFuel(ItemStack stack) {
        // Use Minecraft's built-in fuel registry — covers ALL valid fuels
        // including modded items, dried kelp, bamboo, wooden tools, wool, etc.
        return AbstractFurnaceBlockEntity.isFuel(stack);
    }

    /**
     * Try to gather fuel from multiple sources (in priority order):
     * 1. Mine nearby coal ore (common underground, each drops 1 coal = 8 smelt operations)
     * 2. Break nearby logs (common on surface, each burns 1.5 items)
     * 3. Pull fuel from tagged storage containers and home area chests
     *
     * @return true if fuel was found/gathered
     */
    private boolean tryGatherFuel() {
        Level level = companion.level();
        BlockPos center = companion.blockPosition();
        int gathered = 0;
        int needed = Math.max(2, (count + 1) / 2); // Rough fuel estimate

        // Strategy 1: Mine nearby coal ore (best underground fuel source)
        // Coal ore is abundant at all Y-levels, and each drops 1+ coal (8 smelts each!)
        for (int radius = 1; radius <= 16 && gathered < needed; radius++) {
            for (int x = -radius; x <= radius && gathered < needed; x++) {
                for (int y = -4; y <= 8 && gathered < needed; y++) {
                    for (int z = -radius; z <= radius && gathered < needed; z++) {
                        if (Math.abs(x) != radius && Math.abs(z) != radius) continue;
                        BlockPos pos = center.offset(x, y, z);
                        if (companion.isInHomeArea(pos)) continue;
                        BlockState state = level.getBlockState(pos);
                        if (state.is(BlockTags.COAL_ORES)) {
                            if (companion.canHarvestBlock(state)
                                    && BlockHelper.isSafeToMine(level, pos)) {
                                companion.equipBestToolForBlock(state);
                                if (BlockHelper.breakBlock(companion, pos)) {
                                    gathered++;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (hasFuel()) {
            if (gathered > 0) {
                MCAi.LOGGER.info("SmeltItemsTask: mined {} coal ore for fuel", gathered);
                say("Mined " + gathered + " coal ore for fuel.");
            }
            return true;
        }

        // Strategy 2: Break nearby logs (surface fuel source)
        for (int radius = 1; radius <= 16 && gathered < needed; radius++) {
            for (int x = -radius; x <= radius && gathered < needed; x++) {
                for (int y = -2; y <= 8 && gathered < needed; y++) {
                    for (int z = -radius; z <= radius && gathered < needed; z++) {
                        if (Math.abs(x) != radius && Math.abs(z) != radius) continue;
                        BlockPos pos = center.offset(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (state.is(BlockTags.LOGS)) {
                            companion.equipBestToolForBlock(state);
                            if (BlockHelper.breakBlock(companion, pos)) {
                                gathered++;
                            }
                        }
                    }
                }
            }
        }

        if (hasFuel()) {
            if (gathered > 0) {
                MCAi.LOGGER.info("SmeltItemsTask: auto-gathered {} logs for fuel", gathered);
                say("Gathered " + gathered + " logs for fuel.");
            }
            return true;
        }

        // Strategy 3: Pull fuel from tagged storage containers and home area chests
        int pulled = tryPullFuelFromStorage();
        if (pulled > 0) {
            MCAi.LOGGER.info("SmeltItemsTask: pulled {} fuel items from storage", pulled);
            say("Grabbed fuel from storage.");
        }

        if (gathered > 0 && !hasFuel()) {
            MCAi.LOGGER.info("SmeltItemsTask: gathered {} blocks but still no usable fuel", gathered);
        }
        return hasFuel();
    }

    /**
     * Try to pull fuel items from nearby storage containers (tagged STORAGE + home area).
     * Looks for coal, charcoal, logs, planks, or any burnable item.
     */
    private int tryPullFuelFromStorage() {
        int totalPulled = 0;
        SimpleContainer inv = companion.getCompanionInventory();
        java.util.Set<BlockPos> scanned = new java.util.HashSet<>();

        // Preferred fuel items to look for (in priority order)
        Item[] fuelItems = {
                Items.COAL, Items.CHARCOAL,
                Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG,
                Items.DARK_OAK_LOG, Items.JUNGLE_LOG, Items.ACACIA_LOG,
                Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS
        };

        // Search tagged STORAGE containers
        var storageBlocks = companion.getTaggedBlocks(
                com.apocscode.mcai.logistics.TaggedBlock.Role.STORAGE);
        for (var tb : storageBlocks) {
            if (hasFuel()) return totalPulled;
            scanned.add(tb.pos());
            totalPulled += extractFuelFromContainer(companion.level(), tb.pos(), inv, fuelItems, 8);
        }

        // Search home area containers
        if (!hasFuel() && companion.hasHomeArea()) {
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
                            if (hasFuel()) return totalPulled;
                            BlockPos pos = new BlockPos(x, y, z);
                            if (scanned.contains(pos)) continue;
                            scanned.add(pos);
                            totalPulled += extractFuelFromContainer(
                                    companion.level(), pos, inv, fuelItems, 8);
                        }
                    }
                }
            }
        }
        return totalPulled;
    }

    /**
     * Extract fuel items from a container into companion inventory.
     */
    private int extractFuelFromContainer(Level level, BlockPos pos,
                                          SimpleContainer inv, Item[] fuelItems, int maxExtract) {
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof net.minecraft.world.Container container)) return 0;

        int extracted = 0;
        for (int i = 0; i < container.getContainerSize() && extracted < maxExtract; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            // Check if this item is one of our preferred fuels
            boolean isFuelItem = false;
            for (Item fuel : fuelItems) {
                if (stack.getItem() == fuel) { isFuelItem = true; break; }
            }
            // Also accept any item MC considers fuel
            if (!isFuelItem && AbstractFurnaceBlockEntity.isFuel(stack)) {
                isFuelItem = true;
            }
            if (isFuelItem) {
                int take = Math.min(maxExtract - extracted, stack.getCount());
                ItemStack toInsert = stack.copy();
                toInsert.setCount(take);
                ItemStack remainder = inv.addItem(toInsert);
                int actuallyInserted = take - remainder.getCount();
                if (actuallyInserted > 0) {
                    stack.shrink(actuallyInserted);
                    if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                    extracted += actuallyInserted;
                }
                if (!remainder.isEmpty()) break; // Inventory full
            }
        }
        if (extracted > 0) {
            container.setChanged();
        }
        return extracted;
    }

    /**
     * Try to gather cobblestone by breaking nearby stone blocks.
     * Stone drops cobblestone when mined with a pickaxe.
     * Searches up to 16 blocks away (was 8, but that's too tight in tunnels).
     *
     * @param needed number of cobblestone blocks needed
     * @return number actually gathered
     */
    private int tryGatherCobblestone(int needed) {
        Level level = companion.level();
        BlockPos center = companion.blockPosition();
        int gathered = 0;

        for (int radius = 1; radius <= 16 && gathered < needed; radius++) {
            for (int x = -radius; x <= radius && gathered < needed; x++) {
                for (int y = -3; y <= 2 && gathered < needed; y++) {
                    for (int z = -radius; z <= radius && gathered < needed; z++) {
                        BlockPos pos = center.offset(x, y, z);
                        Block block = level.getBlockState(pos).getBlock();
                        if (block == Blocks.STONE || block == Blocks.COBBLESTONE
                                || block == Blocks.ANDESITE || block == Blocks.DIORITE
                                || block == Blocks.GRANITE) {
                            // Only mine stone if companion can harvest it
                            BlockState state = level.getBlockState(pos);
                            if (companion.canHarvestBlock(state)
                                    && BlockHelper.isSafeToMine(level, pos)) {
                                companion.equipBestToolForBlock(state);
                                if (BlockHelper.breakBlock(companion, pos)) {
                                    gathered++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return gathered;
    }

    /**
     * Count cobblestone only in companion's inventory (not storage).
     */
    private int countCobblestoneInInventory() {
        SimpleContainer inv = companion.getCompanionInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.COBBLESTONE) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * Pull cobblestone from tagged STORAGE containers and home area chests
     * into companion inventory. Used when companion needs cobblestone for
     * furnace auto-craft but has already routed it all to storage during mining.
     *
     * @param needed maximum cobblestone to pull
     * @return number actually pulled
     */
    private int tryPullCobblestoneFromStorage(int needed) {
        int totalPulled = 0;
        SimpleContainer inv = companion.getCompanionInventory();
        java.util.Set<BlockPos> scanned = new java.util.HashSet<>();

        // Items to look for (cobblestone + variants that can substitute)
        Item[] cobbleItems = { Items.COBBLESTONE, Items.COBBLED_DEEPSLATE };

        // Search tagged STORAGE containers
        var storageBlocks = companion.getTaggedBlocks(
                com.apocscode.mcai.logistics.TaggedBlock.Role.STORAGE);
        for (var tb : storageBlocks) {
            if (totalPulled >= needed) return totalPulled;
            scanned.add(tb.pos());
            totalPulled += extractItemsFromContainer(
                    companion.level(), tb.pos(), inv, cobbleItems, needed - totalPulled);
        }

        // Search home area containers
        if (totalPulled < needed && companion.hasHomeArea()) {
            BlockPos c1 = companion.getHomeCorner1();
            BlockPos c2 = companion.getHomeCorner2();
            if (c1 != null && c2 != null) {
                int minX = Math.min(c1.getX(), c2.getX());
                int minY = Math.min(c1.getY(), c2.getY());
                int minZ = Math.min(c1.getZ(), c2.getZ());
                int maxX = Math.max(c1.getX(), c2.getX());
                int maxY = Math.max(c1.getY(), c2.getY());
                int maxZ = Math.max(c1.getZ(), c2.getZ());
                for (int x = minX; x <= maxX && totalPulled < needed; x++) {
                    for (int y = minY; y <= maxY && totalPulled < needed; y++) {
                        for (int z = minZ; z <= maxZ && totalPulled < needed; z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (scanned.contains(pos)) continue;
                            scanned.add(pos);
                            totalPulled += extractItemsFromContainer(
                                    companion.level(), pos, inv, cobbleItems, needed - totalPulled);
                        }
                    }
                }
            }
        }

        if (totalPulled > 0) {
            MCAi.LOGGER.info("SmeltItemsTask: pulled {} cobblestone from storage containers", totalPulled);
        }
        return totalPulled;
    }

    /**
     * Extract specific items from a container into companion inventory.
     */
    private int extractItemsFromContainer(Level level, BlockPos pos,
                                           SimpleContainer inv, Item[] targetItems, int maxExtract) {
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof net.minecraft.world.Container container)) return 0;

        int extracted = 0;
        for (int i = 0; i < container.getContainerSize() && extracted < maxExtract; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;
            boolean isTarget = false;
            for (Item target : targetItems) {
                if (stack.getItem() == target) { isTarget = true; break; }
            }
            if (isTarget) {
                int take = Math.min(maxExtract - extracted, stack.getCount());
                ItemStack toInsert = stack.copy();
                toInsert.setCount(take);
                ItemStack remainder = inv.addItem(toInsert);
                int actuallyInserted = take - remainder.getCount();
                if (actuallyInserted > 0) {
                    stack.shrink(actuallyInserted);
                    if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                    extracted += actuallyInserted;
                }
                if (!remainder.isEmpty()) break; // Inventory full
            }
        }
        if (extracted > 0 && be instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
            blockEntity.setChanged();
        }
        return extracted;
    }

    // ========== Auto-place furnace ==========

    /**
     * Try to auto-craft a furnace from 8 cobblestone in companion inventory,
     * then place it near the companion.
     * @return the BlockPos of the placed furnace, or null if failed
     */
    private BlockPos tryAutoPlaceFurnace() {
        SimpleContainer inv = companion.getCompanionInventory();

        // Count cobblestone
        int cobbleCount = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.COBBLESTONE) {
                cobbleCount += stack.getCount();
            }
        }

        if (cobbleCount < 8) {
            MCAi.LOGGER.debug("Cannot auto-craft furnace: only {} cobblestone (need 8)", cobbleCount);
            return null;
        }

        // Consume 8 cobblestone
        int toConsume = 8;
        for (int i = 0; i < inv.getContainerSize() && toConsume > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.COBBLESTONE) {
                int take = Math.min(toConsume, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                toConsume -= take;
            }
        }

        // Give furnace item and place it
        inv.addItem(new ItemStack(Items.FURNACE, 1));
        BlockPos placePos = findPlaceableSpot();
        if (placePos == null) {
            // Can't place — return cobblestone
            inv.addItem(new ItemStack(Items.COBBLESTONE, 8));
            // Remove the furnace item we just gave
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == Items.FURNACE) {
                    stack.shrink(1);
                    if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                    break;
                }
            }
            MCAi.LOGGER.warn("Cannot auto-place furnace: no suitable spot nearby");
            return null;
        }

        if (BlockHelper.placeBlock(companion, placePos, Blocks.FURNACE)) {
            MCAi.LOGGER.info("Auto-crafted and placed furnace at {}", placePos);
            say("Crafted and placed a furnace!");
            return placePos;
        }

        // Failed to place — return materials
        inv.addItem(new ItemStack(Items.COBBLESTONE, 8));
        return null;
    }

    /**
     * Pick up an auto-placed furnace: break block and recollect the item.
     */
    private void pickUpAutoPlacedFurnace() {
        Level level = companion.level();
        BlockState state = level.getBlockState(furnacePos);

        if (state.isAir()) return;

        // Make sure it's actually a furnace
        Block block = state.getBlock();
        if (!(block instanceof FurnaceBlock || block instanceof BlastFurnaceBlock
                || block instanceof SmokerBlock)) return;

        // Clear any remaining items in the furnace first
        BlockEntity be = level.getBlockEntity(furnacePos);
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            SimpleContainer inv = companion.getCompanionInventory();
            for (int slot = 0; slot < 3; slot++) {
                ItemStack furnaceStack = furnace.getItem(slot);
                if (!furnaceStack.isEmpty()) {
                    ItemStack remainder = inv.addItem(furnaceStack.copy());
                    furnace.setItem(slot, remainder);
                }
            }
        }

        // Remove the block and return it to inventory
        level.setBlockAndUpdate(furnacePos, Blocks.AIR.defaultBlockState());
        companion.getCompanionInventory().addItem(new ItemStack(block.asItem(), 1));
        MCAi.LOGGER.info("Picked up auto-placed furnace at {}", furnacePos);
        say("Picked up the furnace.");
    }

    /**
     * Find a suitable air block near the companion (or its home) with solid ground below.
     * Prefers home position for permanent placement.
     */
    private BlockPos findPlaceableSpot() {
        Level level = companion.level();

        // Prefer home position for placement
        BlockPos center;
        if (companion.hasHomePos()) {
            center = companion.getHomePos();
        } else {
            center = companion.blockPosition();
        }

        for (int radius = 1; radius <= 3; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) continue;
                    for (int y = -1; y <= 1; y++) {
                        BlockPos candidate = center.offset(x, y, z);
                        BlockPos below = candidate.below();
                        if (level.getBlockState(candidate).isAir()
                                && level.getBlockState(below).isSolidRender(level, below)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Check if a position is within the companion's home area.
     * Blocks placed near home stay permanently.
     */
    private boolean isNearHome(BlockPos pos) {
        if (!companion.hasHomePos()) return false;
        return companion.isInHomeArea(pos);
    }

    @Override
    protected void cleanup() {
        // If we auto-placed a furnace and the task is cancelled/failed, try to pick it up
        // (unless it's near home — those stay)
        if (shouldPickUpFurnace && furnacePos != null) {
            pickUpAutoPlacedFurnace();
        }
    }
}
