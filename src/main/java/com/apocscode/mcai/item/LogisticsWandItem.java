package com.apocscode.mcai.item;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

import java.util.List;
import java.util.UUID;

/**
 * Logistics Wand — designate containers for companion automation.
 *
 * Usage:
 *   Shift+right-click container → tag/untag it with the current mode
 *   Shift+scroll → cycle mode (INPUT/OUTPUT/STORAGE)
 *   Right-click container (no shift) → opens the container normally
 *
 * Tags are stored on the companion entity and persist through save/load/dismiss.
 */
public class LogisticsWandItem extends Item {

    /** Per-player current wand mode — stored in player persistent data */
    private static final String TAG_WAND_MODE = "mcai:wand_mode";

    public LogisticsWandItem(Properties properties) {
        super(properties);
    }

    // ================================================================
    // Right-click on block → tag or untag container
    // ================================================================

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos pos = context.getClickedPos();

        if (level.isClientSide || player == null) return InteractionResult.PASS;

        // Check if the clicked block is a container (has item handler capability)
        boolean isContainer = false;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            isContainer = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null) != null;
        }

        // Non-shift click = pass through (open container normally)
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
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
        TaggedBlock.Role mode = getWandMode(player);
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

        // Check distance from home (if set) — max 32 blocks
        if (companion.hasHomePos()) {
            double dist = Math.sqrt(companion.getHomePos().distSqr(pos));
            if (dist > 32.0) {
                player.sendSystemMessage(Component.literal(
                        "§e[MCAi]§r Too far from home position! (§c" +
                                String.format("%.0f", dist) + "§r/32 blocks)"));
                return InteractionResult.FAIL;
            }
        }

        companion.addTaggedBlock(pos, mode);

        String colorCode = switch (mode) {
            case INPUT -> "§9";   // Blue
            case OUTPUT -> "§6";  // Gold/Orange
            case STORAGE -> "§a"; // Green
        };

        player.sendSystemMessage(Component.literal(
                "§b[MCAi]§r Tagged as " + colorCode + mode.getLabel() + "§r at §e" +
                        pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "§r " +
                        "(" + companion.getTaggedBlockCount() + " total)"));

        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8F, 1.0F);

        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // ================================================================
    // Wand mode persistence (per-player)
    // ================================================================

    public static TaggedBlock.Role getWandMode(Player player) {
        int modeOrd = player.getPersistentData().getInt(TAG_WAND_MODE);
        TaggedBlock.Role[] roles = TaggedBlock.Role.values();
        return (modeOrd >= 0 && modeOrd < roles.length) ? roles[modeOrd] : TaggedBlock.Role.INPUT;
    }

    public static void setWandMode(Player player, TaggedBlock.Role role) {
        player.getPersistentData().putInt(TAG_WAND_MODE, role.ordinal());
    }

    // ================================================================
    // Tooltip
    // ================================================================

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.literal("§5Shift+right-click container to tag/untag"));
        tooltipComponents.add(Component.literal("§5Shift+scroll to cycle mode"));
        tooltipComponents.add(Component.literal("§8Modes: §9Input §8| §6Output §8| §aStorage"));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }
}
