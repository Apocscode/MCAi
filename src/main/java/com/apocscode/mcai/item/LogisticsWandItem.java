package com.apocscode.mcai.item;

import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.apocscode.mcai.network.SyncHomeAreaPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.UUID;

/**
 * Logistics Wand — designate containers for companion automation and set home area.
 *
 * Usage:
 *   Shift+right-click container → tag/untag it with the current mode (INPUT/OUTPUT/STORAGE)
 *   Shift+right-click any block (HOME_AREA mode) → set corner 1, then corner 2
 *   Shift+scroll → cycle mode (INPUT/OUTPUT/STORAGE/HOME Area)
 *   Right-click container (no shift) → opens the container normally
 *
 * Tags are stored on the companion entity and persist through save/load/dismiss.
 */
public class LogisticsWandItem extends Item {

    /** Per-player current wand mode — stored in player persistent data */
    private static final String TAG_WAND_MODE = "mcai:wand_mode";
    /** Tracks which home corner the player sets next (0 = corner1, 1 = corner2) */
    private static final String TAG_HOME_CORNER_NEXT = "mcai:home_corner_next";

    public LogisticsWandItem(Properties properties) {
        super(properties);
    }

    // ================================================================
    // Right-click on block → tag/untag container, or set home corners
    // ================================================================

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();

        if (level.isClientSide || player == null) return InteractionResult.PASS;

        // Non-shift click = pass through (open container normally)
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        WandMode mode = getWandMode(player);

        // --- HOME_AREA mode: set corners ---
        if (mode == WandMode.HOME_AREA) {
            return handleHomeAreaClick(level, player, pos);
        }

        // --- CLEAR_HOME mode: clear the home area ---
        if (mode == WandMode.CLEAR_HOME) {
            return handleClearHomeArea(level, player);
        }

        // --- Container tagging modes (INPUT/OUTPUT/STORAGE) ---
        return handleContainerTagging(level, player, pos, mode);
    }

    /**
     * Handle HOME_AREA mode: first click = corner 1, second click = corner 2.
     */
    private InteractionResult handleHomeAreaClick(Level level, Player player, BlockPos pos) {
        UUID playerUUID = player.getUUID();
        CompanionEntity companion = CompanionEntity.getLivingCompanion(playerUUID);

        int nextCorner = player.getPersistentData().getInt(TAG_HOME_CORNER_NEXT); // 0 or 1

        if (nextCorner == 0) {
            // Set corner 1
            player.getPersistentData().putLong("mcai:home_corner1", pos.asLong());
            player.getPersistentData().putInt(TAG_HOME_CORNER_NEXT, 1);

            if (companion != null) {
                companion.setHomeCorner1(pos);
            }

            player.sendSystemMessage(Component.literal(
                    "§2[MCAi]§r Home Area §acorner 1§r set to §e" +
                            pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
                            "§r — now click §acorner 2§r"));

            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.2F);
            return InteractionResult.sidedSuccess(level.isClientSide);
        } else {
            // Set corner 2
            player.getPersistentData().putLong("mcai:home_corner2", pos.asLong());
            player.getPersistentData().putInt(TAG_HOME_CORNER_NEXT, 0); // reset for next time

            if (companion != null) {
                companion.setHomeCorner2(pos);

                // Also set legacy homePos to center for backward compat
                BlockPos center = companion.getHomePos();
                if (center != null) {
                    companion.setHomePos(center);
                    player.getPersistentData().putLong("mcai:home_pos", center.asLong());
                }

                // Sync home area to client for rendering
                if (player instanceof ServerPlayer serverPlayer) {
                    BlockPos c1 = companion.getHomeCorner1();
                    BlockPos c2 = companion.getHomeCorner2();
                    if (c1 != null && c2 != null) {
                        PacketDistributor.sendToPlayer(serverPlayer,
                                new SyncHomeAreaPacket(c1.asLong(), c2.asLong()));
                    }
                }
            }

            // Calculate area size
            BlockPos c1 = BlockPos.of(player.getPersistentData().getLong("mcai:home_corner1"));
            int sizeX = Math.abs(pos.getX() - c1.getX()) + 1;
            int sizeY = Math.abs(pos.getY() - c1.getY()) + 1;
            int sizeZ = Math.abs(pos.getZ() - c1.getZ()) + 1;

            player.sendSystemMessage(Component.literal(
                    "§2[MCAi]§r Home Area §acorner 2§r set to §e" +
                            pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
                            "§r — area: §a" + sizeX + "x" + sizeY + "x" + sizeZ + "§r blocks"));

            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 0.8F);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
    }

    /**
     * Handle CLEAR_HOME mode: clear the home area bounding box.
     */
    private InteractionResult handleClearHomeArea(Level level, Player player) {
        UUID playerUUID = player.getUUID();
        CompanionEntity companion = CompanionEntity.getLivingCompanion(playerUUID);

        // Clear player persistent data
        player.getPersistentData().remove("mcai:home_corner1");
        player.getPersistentData().remove("mcai:home_corner2");
        player.getPersistentData().putInt(TAG_HOME_CORNER_NEXT, 0);

        // Clear companion data
        if (companion != null) {
            companion.setHomeCorner1(null);
            companion.setHomeCorner2(null);
        }

        // Sync clear to client (0L, 0L clears rendering)
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new SyncHomeAreaPacket(0L, 0L));
        }

        player.sendSystemMessage(Component.literal(
                "§c[MCAi]§r Home Area §ccleared§r!"));
        level.playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 0.8F);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Handle container tagging for INPUT/OUTPUT/STORAGE modes.
     */
    private InteractionResult handleContainerTagging(Level level, Player player, BlockPos pos, WandMode mode) {
        // Check if the clicked block is a container (has item handler capability)
        boolean isContainer = false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            isContainer = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null;
        }

        if (!isContainer) {
            player.sendSystemMessage(Component.literal(
                    "§e[MCAi]§r That block is not a container!"));
            return InteractionResult.FAIL;
        }

        UUID playerUUID = player.getUUID();

        // Get the companion
        CompanionEntity companion = CompanionEntity.getLivingCompanion(playerUUID);

        if (companion == null) {
            player.sendSystemMessage(Component.literal(
                    "§e[MCAi]§r You need a living companion to tag containers!"));
            return InteractionResult.FAIL;
        }

        // Shift+click on tagged container = remove tag; on untagged = tag it
        TaggedBlock.Role role = mode.toRole();
        TaggedBlock existing = companion.getTaggedBlockAt(pos);

        if (existing != null) {
            // Already tagged — remove it
            companion.removeTaggedBlock(pos);
            player.sendSystemMessage(Component.literal(
                    "§b[MCAi]§r Removed tag from container at §e" +
                            pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "§r"));
            level.playSound(null, pos, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6F, 0.8F);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        // Not tagged — tag it with current mode

        // Check distance from home (if set)
        int logisticsRange;
        try { logisticsRange = AiConfig.LOGISTICS_RANGE.get(); } catch (Exception e) { logisticsRange = 32; }
        if (companion.hasHomePos()) {
            BlockPos homeCenter = companion.getHomePos();
            if (homeCenter != null) {
                double dist = Math.sqrt(homeCenter.distSqr(pos));
                if (dist > logisticsRange) {
                    player.sendSystemMessage(Component.literal(
                            "§e[MCAi]§r Too far from home position! (§c" +
                                    String.format("%.0f", dist) + "§r/" + logisticsRange + " blocks)"));
                    return InteractionResult.FAIL;
                }
            }
        }

        companion.addTaggedBlock(pos, role);

        String colorCode = mode.getChatColor();

        player.sendSystemMessage(Component.literal(
                "§b[MCAi]§r Tagged as " + colorCode + role.getLabel() + "§r at §e" +
                        pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "§r " +
                        "(" + companion.getTaggedBlockCount() + " total)"));

        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.0F);

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // ================================================================
    // Wand mode persistence (per-player) — now uses WandMode enum
    // ================================================================

    public static WandMode getWandMode(Player player) {
        int modeOrd = player.getPersistentData().getInt(TAG_WAND_MODE);
        WandMode[] modes = WandMode.values();
        return (modeOrd >= 0 && modeOrd < modes.length) ? modes[modeOrd] : WandMode.INPUT;
    }

    public static void setWandMode(Player player, WandMode mode) {
        player.getPersistentData().putInt(TAG_WAND_MODE, mode.ordinal());
    }

    // ================================================================
    // Tooltip
    // ================================================================

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.literal("§5Shift+right-click container to tag/untag"));
        tooltipComponents.add(Component.literal("§5Shift+scroll to cycle mode"));
        tooltipComponents.add(Component.literal("§8Modes: §9Input §8| §6Output §8| §aStorage §8| §2Home Area §8| §cClear Home"));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }
}
