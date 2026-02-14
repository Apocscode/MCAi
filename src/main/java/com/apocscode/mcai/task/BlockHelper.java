package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for companion interactions with the world.
 * Handles mining, placing, hoeing, and block scanning.
 *
 * All methods are server-side only and should be called from tasks/goals.
 */
public class BlockHelper {

    /**
     * Break a block, dropping items. The companion "mines" it.
     * Plays the break sound and spawns drops.
     *
     * @return true if block was broken
     */
    public static boolean breakBlock(CompanionEntity companion, BlockPos pos) {
        Level level = companion.level();
        if (level.isClientSide) return false;

        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK) return false;

        // === Block Protection System ===
        // 1. Never break tagged blocks (STORAGE/INPUT/OUTPUT — player-marked containers)
        if (companion.getTaggedBlockAt(pos) != null) {
            MCAi.LOGGER.warn("BlockHelper: REFUSED to break TAGGED block {} at {}",
                    state.getBlock().getName().getString(), pos);
            return false;
        }

        // 2. Inside home area: protect ALL blocks — the home area is the player's base
        if (companion.isInHomeArea(pos)) {
            MCAi.LOGGER.warn("BlockHelper: REFUSED to break {} at {} (inside home area)",
                    state.getBlock().getName().getString(), pos);
            return false;
        }

        // Collect drops directly into companion inventory (avoids ground-drop race condition)
        if (level instanceof ServerLevel serverLevel) {
            List<ItemStack> drops = Block.getDrops(state, serverLevel, pos,
                    level.getBlockEntity(pos), companion, companion.getMainHandItem());

            // Break WITHOUT spawning item entities
            level.destroyBlock(pos, false, companion);

            // Insert each drop into companion inventory
            var inv = companion.getCompanionInventory();
            for (ItemStack drop : drops) {
                ItemStack remainder = inv.addItem(drop);
                if (!remainder.isEmpty()) {
                    // Inventory full — try routing to tagged storage first
                    if (com.apocscode.mcai.logistics.ItemRoutingHelper.hasTaggedStorage(companion)) {
                        int routed = com.apocscode.mcai.logistics.ItemRoutingHelper.routeAllCompanionItems(companion);
                        if (routed > 0) {
                            // Made space — try inserting again
                            remainder = inv.addItem(remainder);
                        }
                    }
                    if (!remainder.isEmpty()) {
                        // Still no space — drop on ground as last resort
                        Block.popResource(level, pos, remainder);
                    }
                }
            }
        } else {
            level.destroyBlock(pos, true, companion);
        }
        return true;
    }

    /**
     * Blocks that should never be broken inside the home area.
     * These are player-placed functional blocks (workstations, utilities).
     * Containers are already protected by the Container check above.
     */
    private static boolean isProtectedFunctionalBlock(Block block) {
        return block == Blocks.CRAFTING_TABLE
                || block == Blocks.FURNACE
                || block == Blocks.BLAST_FURNACE
                || block == Blocks.SMOKER
                || block == Blocks.ANVIL
                || block == Blocks.CHIPPED_ANVIL
                || block == Blocks.DAMAGED_ANVIL
                || block == Blocks.ENCHANTING_TABLE
                || block == Blocks.BREWING_STAND
                || block == Blocks.SMITHING_TABLE
                || block == Blocks.CARTOGRAPHY_TABLE
                || block == Blocks.FLETCHING_TABLE
                || block == Blocks.GRINDSTONE
                || block == Blocks.LOOM
                || block == Blocks.STONECUTTER
                || block == Blocks.COMPOSTER
                || block == Blocks.LECTERN
                || block == Blocks.CAULDRON
                || block == Blocks.WATER_CAULDRON
                || block == Blocks.LAVA_CAULDRON
                || block == Blocks.BELL
                || block == Blocks.BEACON
                || block == Blocks.CONDUIT
                || block == Blocks.RESPAWN_ANCHOR
                || block == Blocks.LODESTONE
                || block == Blocks.BEE_NEST
                || block == Blocks.BEEHIVE;
    }

    /**
     * Check if a block is safe to mine (no lava/water source directly behind it).
     * Returns true if safe, false if mining would expose a fluid hazard.
     * Checks all 6 adjacent faces for lava/water source blocks.
     */
    public static boolean isSafeToMine(Level level, BlockPos pos) {
        net.minecraft.core.Direction[] dirs = net.minecraft.core.Direction.values();
        for (net.minecraft.core.Direction dir : dirs) {
            BlockState adjacent = level.getBlockState(pos.relative(dir));
            // Block lava exposure entirely
            if (adjacent.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Place a block from the companion's inventory.
     *
     * @param blockToPlace The block to place (must be in inventory as item form)
     * @return true if block was placed
     */
    public static boolean placeBlock(CompanionEntity companion, BlockPos pos, Block blockToPlace) {
        Level level = companion.level();
        if (level.isClientSide) return false;

        BlockState current = level.getBlockState(pos);
        if (!current.canBeReplaced()) return false;

        // Check inventory for the block item
        var inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && Block.byItem(stack.getItem()) == blockToPlace) {
                level.setBlock(pos, blockToPlace.defaultBlockState(), 3);
                stack.shrink(1);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);

                // Play place sound
                var sound = blockToPlace.defaultBlockState().getSoundType();
                level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                        (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
                return true;
            }
        }
        return false;
    }

    /**
     * Hoe a dirt/grass block into farmland.
     *
     * @return true if block was hoed
     */
    public static boolean hoeBlock(CompanionEntity companion, BlockPos pos) {
        Level level = companion.level();
        if (level.isClientSide) return false;

        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();

        // Can hoe grass, dirt, dirt path, coarse dirt, rooted dirt
        if (block == Blocks.GRASS_BLOCK || block == Blocks.DIRT
                || block == Blocks.DIRT_PATH || block == Blocks.COARSE_DIRT
                || block == Blocks.ROOTED_DIRT) {
            level.setBlock(pos, Blocks.FARMLAND.defaultBlockState(), 3);
            level.playSound(null, pos, net.minecraft.sounds.SoundEvents.HOE_TILL,
                    SoundSource.BLOCKS, 1.0F, 1.0F);
            return true;
        }
        return false;
    }

    /**
     * Plant a crop on farmland.
     *
     * @param seedItem The seed/crop item to look for in inventory
     * @param cropBlock The crop block to place
     * @return true if planted
     */
    public static boolean plantCrop(CompanionEntity companion, BlockPos farmlandPos,
                                     net.minecraft.world.item.Item seedItem, Block cropBlock) {
        Level level = companion.level();
        if (level.isClientSide) return false;

        BlockPos cropPos = farmlandPos.above();
        BlockState above = level.getBlockState(cropPos);
        if (!above.isAir()) return false;

        BlockState below = level.getBlockState(farmlandPos);
        if (!(below.getBlock() instanceof FarmBlock)) return false;

        // Check inventory for seeds
        var inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == seedItem) {
                level.setBlock(cropPos, cropBlock.defaultBlockState(), 3);
                stack.shrink(1);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                level.playSound(null, cropPos, net.minecraft.sounds.SoundEvents.CROP_PLANTED,
                        SoundSource.BLOCKS, 1.0F, 1.0F);
                return true;
            }
        }
        return false;
    }

    /**
     * Count how many of a specific item the companion has.
     */
    public static int countItem(CompanionEntity companion, net.minecraft.world.item.Item item) {
        var inv = companion.getCompanionInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        // Also count equipped items (mainhand, offhand, armor)
        ItemStack mainHand = companion.getMainHandItem();
        if (!mainHand.isEmpty() && mainHand.getItem() == item) {
            count += mainHand.getCount();
        }
        ItemStack offHand = companion.getOffhandItem();
        if (!offHand.isEmpty() && offHand.getItem() == item) {
            count += offHand.getCount();
        }
        for (ItemStack armor : companion.getArmorSlots()) {
            if (!armor.isEmpty() && armor.getItem() == item) {
                count += armor.getCount();
            }
        }
        // Also count items in tagged STORAGE chests
        count += countInTaggedStorage(companion, stack -> stack.getItem() == item);
        return count;
    }

    /**
     * Count how many of a specific item are in the companion's INVENTORY ONLY (not storage).
     * Use this when you need items physically in the companion's hands,
     * not just logically available via storage.
     */
    public static int countItemInInventory(CompanionEntity companion, net.minecraft.world.item.Item item) {
        var inv = companion.getCompanionInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        // Also count equipped items (mainhand, offhand, armor)
        ItemStack mainHand = companion.getMainHandItem();
        if (!mainHand.isEmpty() && mainHand.getItem() == item) {
            count += mainHand.getCount();
        }
        ItemStack offHand = companion.getOffhandItem();
        if (!offHand.isEmpty() && offHand.getItem() == item) {
            count += offHand.getCount();
        }
        for (ItemStack armor : companion.getArmorSlots()) {
            if (!armor.isEmpty() && armor.getItem() == item) {
                count += armor.getCount();
            }
        }
        return count;
    }

    /**
     * Count items in the companion's inventory matching a predicate.
     * Useful for tag-based checks (e.g., count all log-type items).
     *
     * @param companion The companion entity
     * @param predicate Returns true for items to count
     * @return Total count of matching items
     */
    public static int countItemMatching(CompanionEntity companion,
                                         java.util.function.Predicate<net.minecraft.world.item.Item> predicate) {
        var inv = companion.getCompanionInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && predicate.test(stack.getItem())) {
                count += stack.getCount();
            }
        }
        ItemStack mainHand = companion.getMainHandItem();
        if (!mainHand.isEmpty() && predicate.test(mainHand.getItem())) count += mainHand.getCount();
        ItemStack offHand = companion.getOffhandItem();
        if (!offHand.isEmpty() && predicate.test(offHand.getItem())) count += offHand.getCount();
        // Also count items in tagged STORAGE chests
        count += countInTaggedStorage(companion, stack2 -> predicate.test(stack2.getItem()));
        return count;
    }

    /**
     * Count items matching a predicate in all tagged STORAGE containers.
     */
    public static int countInTaggedStorage(CompanionEntity companion,
                                            java.util.function.Predicate<ItemStack> predicate) {
        int count = 0;
        var storageBlocks = companion.getTaggedBlocks(TaggedBlock.Role.STORAGE);
        for (var tb : storageBlocks) {
            var be = companion.level().getBlockEntity(tb.pos());
            if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty() && predicate.test(stack)) {
                        count += stack.getCount();
                    }
                }
            }
        }
        return count;
    }

    /**
     * Remove a specific number of items from the companion's inventory.
     *
     * @return number actually removed
     */
    public static int removeItem(CompanionEntity companion, net.minecraft.world.item.Item item, int count) {
        var inv = companion.getCompanionInventory();
        int remaining = count;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                remaining -= take;
            }
        }
        return count - remaining;
    }

    /**
     * Scan for blocks of a specific type within a radius.
     *
     * @return List of matching block positions, sorted by distance
     */
    public static List<BlockPos> scanForBlocks(CompanionEntity companion, Block targetBlock,
                                                int radius, int maxResults) {
        return scanForBlocks(companion, new Block[]{targetBlock}, radius, maxResults);
    }

    /**
     * Scan for any of the given block types within a radius.
     * Useful when an item can come from multiple blocks (e.g., cobblestone from stone OR cobblestone).
     *
     * @return List of matching block positions, sorted by distance
     */
    public static List<BlockPos> scanForBlocks(CompanionEntity companion, Block[] targetBlocks,
                                                int radius, int maxResults) {
        BlockPos center = companion.blockPosition();
        Level level = companion.level();
        List<BlockPos> results = new ArrayList<>();

        // Clamp Y to world bounds (-64 to 319 in overworld)
        int minY = Math.max(-radius / 2, level.getMinBuildHeight() - center.getY());
        int maxY = Math.min(radius / 2, level.getMaxBuildHeight() - 1 - center.getY());

        for (int x = -radius; x <= radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    // Skip blocks inside the home area (player structures)
                    if (companion.isInHomeArea(pos)) continue;
                    Block block = level.getBlockState(pos).getBlock();
                    for (Block target : targetBlocks) {
                        if (block == target) {
                            results.add(pos);
                            break;
                        }
                    }
                }
            }
        }

        // Sort by distance
        results.sort((a, b) -> {
            double distA = companion.distanceToSqr(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5);
            double distB = companion.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            return Double.compare(distA, distB);
        });

        if (results.size() > maxResults) {
            return results.subList(0, maxResults);
        }
        return results;
    }

    /**
     * Scan for logs (any wood type) within radius.
     */
    public static List<BlockPos> scanForLogs(CompanionEntity companion, int radius, int maxResults) {
        BlockPos center = companion.blockPosition();
        Level level = companion.level();
        List<BlockPos> results = new ArrayList<>();

        // Clamp Y to world bounds
        int minY = Math.max(-2, level.getMinBuildHeight() - center.getY());
        int maxY = Math.min(radius, level.getMaxBuildHeight() - 1 - center.getY());

        for (int x = -radius; x <= radius; x++) {
            for (int y = minY; y <= maxY; y++) { // Search up for tall trees
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = companion.level().getBlockState(pos);
                    if (state.is(net.minecraft.tags.BlockTags.LOGS)) {
                        results.add(pos);
                    }
                }
            }
        }

        results.sort((a, b) -> {
            double distA = companion.distanceToSqr(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5);
            double distB = companion.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            return Double.compare(distA, distB);
        });

        if (results.size() > maxResults) return results.subList(0, maxResults);
        return results;
    }

    /**
     * Scan for ores within radius.
     */
    public static List<BlockPos> scanForOres(CompanionEntity companion, int radius, int maxResults) {
        BlockPos center = companion.blockPosition();
        Level level = companion.level();
        List<BlockPos> results = new ArrayList<>();

        // Clamp Y to world bounds (-64 to 319 in overworld)
        int minY = Math.max(-radius, level.getMinBuildHeight() - center.getY());
        int maxY = Math.min(radius, level.getMaxBuildHeight() - 1 - center.getY());

        for (int x = -radius; x <= radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    // Skip blocks inside the home area
                    if (companion.isInHomeArea(pos)) continue;
                    BlockState state = companion.level().getBlockState(pos);
                    // Check ore tags
                    if (state.is(net.minecraft.tags.BlockTags.IRON_ORES)
                            || state.is(net.minecraft.tags.BlockTags.GOLD_ORES)
                            || state.is(net.minecraft.tags.BlockTags.DIAMOND_ORES)
                            || state.is(net.minecraft.tags.BlockTags.COAL_ORES)
                            || state.is(net.minecraft.tags.BlockTags.COPPER_ORES)
                            || state.is(net.minecraft.tags.BlockTags.LAPIS_ORES)
                            || state.is(net.minecraft.tags.BlockTags.REDSTONE_ORES)
                            || state.is(net.minecraft.tags.BlockTags.EMERALD_ORES)) {
                        results.add(pos);
                    }
                }
            }
        }

        results.sort((a, b) -> {
            double distA = companion.distanceToSqr(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5);
            double distB = companion.distanceToSqr(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
            return Double.compare(distA, distB);
        });

        if (results.size() > maxResults) return results.subList(0, maxResults);
        return results;
    }

    // ================================================================
    // Torch placement (for mining system)
    // ================================================================

    /**
     * Place a torch at the given position. Tries floor torch first,
     * then wall torch on an adjacent solid face if the floor isn't solid.
     * Consumes a torch from the companion's inventory.
     *
     * @return true if a torch was placed
     */
    public static boolean placeTorch(CompanionEntity companion, BlockPos pos) {
        Level level = companion.level();
        if (level.isClientSide) return false;

        // Must have torches in inventory
        if (countItem(companion, Items.TORCH) <= 0) return false;

        // Try standing torch on solid ground
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        BlockState currentState = level.getBlockState(pos);
        if (!currentState.canBeReplaced()) return false;

        if (belowState.isFaceSturdy(level, below, Direction.UP)) {
            // Place standing torch
            level.setBlock(pos, Blocks.TORCH.defaultBlockState(), 3);
            removeItem(companion, Items.TORCH, 1);
            playTorchSound(level, pos);
            return true;
        }

        // Try wall torch — check each horizontal direction for a solid wall
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos wallPos = pos.relative(dir);
            BlockState wallState = level.getBlockState(wallPos);
            if (wallState.isFaceSturdy(level, wallPos, dir.getOpposite())) {
                // Wall torch faces AWAY from the wall it's attached to
                BlockState torchState = Blocks.WALL_TORCH.defaultBlockState()
                        .setValue(WallTorchBlock.FACING, dir.getOpposite());
                level.setBlock(pos, torchState, 3);
                removeItem(companion, Items.TORCH, 1);
                playTorchSound(level, pos);
                return true;
            }
        }
        return false;
    }

    private static void playTorchSound(Level level, BlockPos pos) {
        var sound = Blocks.TORCH.defaultBlockState().getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
    }

    // ================================================================
    // Inventory capacity helpers (for mining system)
    // ================================================================

    /**
     * Check if companion inventory is nearly full (>= threshold % of slots used).
     *
     * @param thresholdPercent 0.0 to 1.0 — e.g. 0.8 for 80% full
     * @return true if inventory usage is at or above the threshold
     */
    public static boolean isInventoryNearlyFull(CompanionEntity companion, double thresholdPercent) {
        var inv = companion.getCompanionInventory();
        int totalSlots = inv.getContainerSize();
        int usedSlots = 0;
        for (int i = 0; i < totalSlots; i++) {
            if (!inv.getItem(i).isEmpty()) usedSlots++;
        }
        return (double) usedSlots / totalSlots >= thresholdPercent;
    }

    /**
     * Check if a position is safe to stand on (solid below, not in lava/fire).
     */
    public static boolean isSafeToStand(Level level, BlockPos feetPos) {
        BlockState feet = level.getBlockState(feetPos);
        BlockState head = level.getBlockState(feetPos.above());
        BlockState below = level.getBlockState(feetPos.below());

        // Feet and head must be passable (air, torch, etc.)
        boolean feetClear = feet.isAir() || !feet.blocksMotion();
        boolean headClear = head.isAir() || !head.blocksMotion();

        // Ground below must be solid
        boolean solidGround = below.isFaceSturdy(level, feetPos.below(), Direction.UP);

        // No lava at feet level
        boolean noLava = !feet.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)
                && !head.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);

        return feetClear && headClear && solidGround && noLava;
    }

    /**
     * Check if a block is a falling block (sand, gravel, concrete powder).
     */
    public static boolean isFallingBlock(Level level, BlockPos pos) {
        Block block = level.getBlockState(pos).getBlock();
        return block instanceof net.minecraft.world.level.block.FallingBlock;
    }
}
