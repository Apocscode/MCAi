package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
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

        // Check companion has fuel
        if (!hasFuel()) {
            fail("No fuel available! I need coal, charcoal, or wood to smelt.");
            return;
        }

        furnacePos = findNearbyFurnace();
        if (furnacePos == null) {
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
        var item = stack.getItem();
        return item == Items.COAL || item == Items.CHARCOAL || item == Items.STICK
                || item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS
                || item == Items.BIRCH_PLANKS || item == Items.JUNGLE_PLANKS
                || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS
                || item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS
                || item == Items.BAMBOO_PLANKS
                || item == Items.OAK_LOG || item == Items.SPRUCE_LOG
                || item == Items.BIRCH_LOG || item == Items.JUNGLE_LOG
                || item == Items.ACACIA_LOG || item == Items.DARK_OAK_LOG
                || item == Items.MANGROVE_LOG || item == Items.CHERRY_LOG
                || item == Items.LAVA_BUCKET || item == Items.BLAZE_ROD;
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
     * Check if a position is near the companion's home (within 10 blocks).
     * Blocks placed near home stay permanently.
     */
    private boolean isNearHome(BlockPos pos) {
        if (!companion.hasHomePos()) return false;
        return companion.getHomePos().distManhattan(pos) <= 10;
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
