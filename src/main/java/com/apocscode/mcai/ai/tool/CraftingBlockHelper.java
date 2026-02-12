package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Utility for auto-placing and picking up crafting station blocks.
 *
 * When the AI needs a crafting table, furnace, anvil, etc. and one isn't nearby,
 * it will:
 *   1. Check if the block already exists nearby → use it
 *   2. Check for portable crafting items (backpack upgrades etc.)
 *   3. Attempt to craft the block from available materials
 *   4. Place the block near the companion
 *   5. After use, break and recollect the block
 *
 * This avoids pathfinding to far-away stations while respecting game balance
 * (materials must be consumed to craft the station).
 */
public class CraftingBlockHelper {

    private static final int SCAN_RADIUS = 8;

    /**
     * Represents a crafting station type with its block, item, and recipe.
     */
    public enum StationType {
        CRAFTING_TABLE(Blocks.CRAFTING_TABLE, Items.CRAFTING_TABLE,
                new Item[]{Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS},
                true), // 2x2 — can craft from inventory
        FURNACE(Blocks.FURNACE, Items.FURNACE,
                new Item[]{Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
                        Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
                        Items.COBBLESTONE, Items.COBBLESTONE},
                false), // 3x3 — needs crafting table
        BLAST_FURNACE(Blocks.BLAST_FURNACE, Items.BLAST_FURNACE, null, false),
        SMOKER(Blocks.SMOKER, Items.SMOKER, null, false),
        SMITHING_TABLE(Blocks.SMITHING_TABLE, Items.SMITHING_TABLE,
                new Item[]{Items.IRON_INGOT, Items.IRON_INGOT,
                        Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS, Items.OAK_PLANKS},
                false),
        ANVIL(Blocks.ANVIL, Items.ANVIL, null, false),
        STONECUTTER(Blocks.STONECUTTER, Items.STONECUTTER, null, false),
        LOOM(Blocks.LOOM, Items.LOOM, null, false),
        CARTOGRAPHY_TABLE(Blocks.CARTOGRAPHY_TABLE, Items.CARTOGRAPHY_TABLE, null, false),
        GRINDSTONE(Blocks.GRINDSTONE, Items.GRINDSTONE, null, false);

        public final Block block;
        public final Item item;
        /** Simple recipe ingredients (null = cannot auto-craft, must be provided). */
        @Nullable public final Item[] simpleRecipe;
        /** Whether this station can itself be crafted in a 2x2 grid. */
        public final boolean craftableIn2x2;

        StationType(Block block, Item item, @Nullable Item[] simpleRecipe, boolean craftableIn2x2) {
            this.block = block;
            this.item = item;
            this.simpleRecipe = simpleRecipe;
            this.craftableIn2x2 = craftableIn2x2;
        }
    }

    /**
     * Result from ensuring a station is available.
     */
    public static class StationResult {
        public final boolean success;
        public final String message;
        /** Position of the station block (existing or newly placed). */
        @Nullable public final BlockPos pos;
        /** True if we placed this block and should pick it up after use. */
        public final boolean shouldPickUp;

        private StationResult(boolean success, String message, @Nullable BlockPos pos, boolean shouldPickUp) {
            this.success = success;
            this.message = message;
            this.pos = pos;
            this.shouldPickUp = shouldPickUp;
        }

        public static StationResult found(BlockPos pos) {
            return new StationResult(true, "", pos, false);
        }

        public static StationResult placed(BlockPos pos, String msg) {
            return new StationResult(true, msg, pos, true);
        }

        public static StationResult failed(String msg) {
            return new StationResult(false, msg, null, false);
        }
    }

    // ========== Portable crafting items (reused from CraftItemTool) ==========

    private static final Set<String> PORTABLE_CRAFTING_ITEMS = Set.of(
            "sophisticatedbackpacks:crafting_upgrade",
            "crafting_on_a_stick:crafting_table",
            "easy_villagers:auto_crafter",
            "portable_crafting:portable_crafting_table"
    );

    private static final Set<String> PORTABLE_CRAFTING_PARTIALS = Set.of(
            "portable_crafting",
            "crafting_on_a_stick",
            "crafting_upgrade"
    );

    /**
     * Ensure a station is available near the companion. Tries in order:
     *   1. Find an existing block within SCAN_RADIUS of the companion
     *   2. (For crafting tables only) Check portable crafting items
     *   3. Check if player/companion has the station item → place it
     *   4. (If simple recipe known) Try to auto-craft the station, then place it
     *   5. Fail with a helpful message
     *
     * @return StationResult with the position of the station (or failure info)
     */
    public static StationResult ensureStation(StationType type, ToolContext context) {
        Level level = context.player().level();
        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        BlockPos center = companion != null ? companion.blockPosition() : context.player().blockPosition();

        // 1. Check for existing block nearby
        BlockPos existing = findNearbyBlock(level, center, type.block, SCAN_RADIUS);
        if (existing != null) {
            MCAi.LOGGER.debug("Found existing {} at {}", type.name(), existing);
            return StationResult.found(existing);
        }

        // 2. For crafting tables, check portable items
        if (type == StationType.CRAFTING_TABLE && hasPortableCraftingItem(context)) {
            MCAi.LOGGER.debug("Has portable crafting item — no block placement needed");
            // Return a "found" without pos — caller knows portable items are used
            return StationResult.found(center);
        }

        // 3. Check if we already have the station item in inventory
        boolean hadItem = hasItem(context, type.item);
        if (hadItem) {
            return placeStation(level, center, type, context, false);
        }

        // 4. Try to auto-craft the station from materials
        if (type.simpleRecipe != null) {
            // Check if we need a crafting table for THIS recipe and don't have one
            if (!type.craftableIn2x2) {
                // Need a 3x3 grid — recursively ensure we have a crafting table first
                StationResult tableResult = ensureStation(StationType.CRAFTING_TABLE, context);
                if (!tableResult.success) {
                    return StationResult.failed("Need a crafting table to craft a " +
                            type.item.getDescription().getString() + ", but: " + tableResult.message);
                }
            }

            // Check if we have all materials
            Map<Item, Integer> needed = new LinkedHashMap<>();
            for (Item mat : type.simpleRecipe) {
                needed.merge(mat, 1, Integer::sum);
            }

            // Check available materials
            boolean canCraft = true;
            StringBuilder missing = new StringBuilder();
            for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
                int have = countItem(context, entry.getKey());
                if (have < entry.getValue()) {
                    canCraft = false;
                    missing.append(entry.getValue() - have).append("x ")
                            .append(entry.getKey().getDescription().getString()).append(", ");
                }
            }

            if (!canCraft) {
                return StationResult.failed("Cannot craft " + type.item.getDescription().getString() +
                        ". Missing: " + missing.toString().replaceAll(", $", "") + ".");
            }

            // Consume materials
            for (Map.Entry<Item, Integer> entry : needed.entrySet()) {
                consumeItem(context, entry.getKey(), entry.getValue());
            }

            // Give the station item
            giveItem(context, type.item, 1);
            MCAi.LOGGER.info("Auto-crafted {} for placement", type.item.getDescription().getString());
            return placeStation(level, center, type, context, true);
        }

        return StationResult.failed("No " + type.item.getDescription().getString() +
                " found nearby and cannot auto-craft one. Craft or place one within " +
                SCAN_RADIUS + " blocks.");
    }

    /**
     * Pick up a station block that was placed by the AI.
     * Removes the block and returns the item to the companion or player inventory.
     */
    public static void pickUpStation(StationResult result, ToolContext context) {
        if (!result.shouldPickUp || result.pos == null) return;

        Level level = context.player().level();
        BlockState state = level.getBlockState(result.pos);

        if (state.isAir()) return; // Already gone

        // Remove the block
        level.setBlockAndUpdate(result.pos, Blocks.AIR.defaultBlockState());

        // Return the item to companion inventory (prefer) or player inventory
        Item blockItem = state.getBlock().asItem();
        if (blockItem == Items.AIR) return;

        ItemStack returnStack = new ItemStack(blockItem, 1);

        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        if (companion != null) {
            ItemStack remainder = companion.getCompanionInventory().addItem(returnStack);
            if (!remainder.isEmpty()) {
                context.player().getInventory().add(remainder);
            }
        } else {
            context.player().getInventory().add(returnStack);
        }

        MCAi.LOGGER.info("Picked up auto-placed {} at {}", blockItem.getDescription().getString(), result.pos);
    }

    // ========== Block placement ==========

    /**
     * Place a station block at a suitable location near the center.
     * Finds an air block with a solid block below it.
     */
    private static StationResult placeStation(Level level, BlockPos center, StationType type,
                                               ToolContext context, boolean wasCrafted) {
        BlockPos placePos = findPlaceableSpot(level, center);
        if (placePos == null) {
            // Can't find a spot — give the item back if we crafted it
            return StationResult.failed("No suitable spot to place " +
                    type.item.getDescription().getString() + " nearby.");
        }

        // Remove the station item from inventory
        consumeItem(context, type.item, 1);

        // Place the block
        level.setBlockAndUpdate(placePos, type.block.defaultBlockState());
        MCAi.LOGGER.info("Auto-placed {} at {} (crafted={})", type.name(), placePos, wasCrafted);

        String msg = wasCrafted
                ? "Crafted and placed a " + type.item.getDescription().getString() + "."
                : "Placed a " + type.item.getDescription().getString() + " from inventory.";

        return StationResult.placed(placePos, msg);
    }

    /**
     * Find a suitable air block near the center with solid ground below.
     * Prefers blocks directly adjacent to the center (within 3 blocks).
     */
    @Nullable
    private static BlockPos findPlaceableSpot(Level level, BlockPos center) {
        // Try immediate neighbors first (same Y level), then expand
        for (int radius = 1; radius <= 3; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) continue; // Only check the outer ring
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
        // Fallback: try directly above
        BlockPos above = center.above();
        if (level.getBlockState(above).isAir() && level.getBlockState(above.below()).isSolidRender(level, above.below())) {
            return above;
        }
        return null;
    }

    // ========== Block scanning ==========

    @Nullable
    private static BlockPos findNearbyBlock(Level level, BlockPos center, Block targetBlock, int radius) {
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (level.getBlockState(pos).is(targetBlock)) {
                        double dist = center.distSqr(pos);
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

    /**
     * Find any furnace-type block nearby (furnace, blast furnace, or smoker).
     */
    @Nullable
    public static BlockPos findNearbyFurnace(Level level, BlockPos center, int radius) {
        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -4; y <= 8; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    Block block = level.getBlockState(pos).getBlock();
                    if (block instanceof FurnaceBlock || block instanceof BlastFurnaceBlock
                            || block instanceof SmokerBlock) {
                        double dist = center.distSqr(pos);
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

    // ========== Portable crafting check ==========

    public static boolean hasPortableCraftingItem(ToolContext context) {
        // Check player inventory
        var playerInv = context.player().getInventory();
        for (int i = 0; i < playerInv.getContainerSize(); i++) {
            ItemStack stack = playerInv.getItem(i);
            if (!stack.isEmpty() && isPortableCraftingItem(stack)) return true;
        }

        // Check companion inventory
        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        if (companion != null) {
            SimpleContainer companionInv = companion.getCompanionInventory();
            for (int i = 0; i < companionInv.getContainerSize(); i++) {
                ItemStack stack = companionInv.getItem(i);
                if (!stack.isEmpty() && isPortableCraftingItem(stack)) return true;
            }
        }
        return false;
    }

    private static boolean isPortableCraftingItem(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) return false;
        String fullId = id.toString();
        String path = id.getPath();
        if (PORTABLE_CRAFTING_ITEMS.contains(fullId)) return true;
        for (String partial : PORTABLE_CRAFTING_PARTIALS) {
            if (path.contains(partial)) return true;
        }
        return false;
    }

    // ========== Inventory helpers ==========

    private static boolean hasItem(ToolContext context, Item item) {
        return countItem(context, item) > 0;
    }

    private static int countItem(ToolContext context, Item item) {
        int count = 0;

        // Player inventory
        var playerInv = context.player().getInventory();
        for (int i = 0; i < playerInv.getContainerSize(); i++) {
            ItemStack stack = playerInv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }

        // Companion inventory
        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        if (companion != null) {
            SimpleContainer inv = companion.getCompanionInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    count += stack.getCount();
                }
            }
        }

        return count;
    }

    private static void consumeItem(ToolContext context, Item item, int amount) {
        int remaining = amount;

        // Consume from player first
        var playerInv = context.player().getInventory();
        for (int i = 0; i < playerInv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = playerInv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) playerInv.setItem(i, ItemStack.EMPTY);
                remaining -= take;
            }
        }

        // Then companion inventory
        if (remaining > 0) {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion != null) {
                SimpleContainer inv = companion.getCompanionInventory();
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
        }
    }

    private static void giveItem(ToolContext context, Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        // Prefer companion inventory
        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        if (companion != null) {
            ItemStack remainder = companion.getCompanionInventory().addItem(stack);
            if (!remainder.isEmpty()) {
                context.player().getInventory().add(remainder);
            }
        } else {
            context.player().getInventory().add(stack);
        }
    }
}
