package com.apocscode.mcai.task;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
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
                    // Inventory full — spawn remainder on ground as fallback
                    Block.popResource(level, pos, remainder);
                }
            }
        } else {
            level.destroyBlock(pos, true, companion);
        }
        return true;
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
        BlockPos center = companion.blockPosition();
        List<BlockPos> results = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius / 2; y <= radius / 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (companion.level().getBlockState(pos).getBlock() == targetBlock) {
                        results.add(pos);
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
        List<BlockPos> results = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= radius; y++) { // Search up for tall trees
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
        List<BlockPos> results = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
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
}
