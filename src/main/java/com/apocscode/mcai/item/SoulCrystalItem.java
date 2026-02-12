package com.apocscode.mcai.item;

import com.apocscode.mcai.ModRegistry;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soul Crystal — a craftable, reusable item to summon and respawn the AI companion.
 *
 * Mechanics:
 *   - Right-click to summon companion (binds to the player)
 *   - Not consumed on use (permanent item)
 *   - When companion dies, a 60-second cooldown prevents immediate resummon
 *   - Companion name persists across deaths via player persistent data
 *   - Enchantment glint indicates its magical nature
 *
 * Recipe: Diamond + Gold Ingots + Ender Pearl (shaped crafting)
 */
public class SoulCrystalItem extends Item {

    /** Respawn cooldown in ticks (60 seconds = 1200 ticks) */
    public static final int RESPAWN_COOLDOWN_TICKS = 1200;

    /** Server-side per-player death cooldown tracking (ownerUUID → gameTime of death) */
    private static final Map<UUID, Long> DEATH_COOLDOWNS = new ConcurrentHashMap<>();

    /** Server-side per-player death data (inventory + equipment saved on death) */
    private static final Map<UUID, net.minecraft.nbt.CompoundTag> DEATH_DATA = new ConcurrentHashMap<>();

    public SoulCrystalItem(Properties properties) {
        super(properties);
    }

    // ================================================================
    // Cooldown tracking (called from CompanionEntity.die())
    // ================================================================

    /**
     * Set a death cooldown for the given player. Called when their companion dies.
     */
    public static void setDeathCooldown(UUID ownerUUID, long gameTime) {
        DEATH_COOLDOWNS.put(ownerUUID, gameTime);
    }

    /**
     * Clear the death cooldown (e.g., after successful resummon).
     */
    public static void clearDeathCooldown(UUID ownerUUID) {
        DEATH_COOLDOWNS.remove(ownerUUID);
    }

    /**
     * Store death data (inventory + equipment) for recovery on respawn.
     */
    public static void setDeathData(UUID ownerUUID, net.minecraft.nbt.CompoundTag data) {
        DEATH_DATA.put(ownerUUID, data);
    }

    /**
     * Retrieve and consume death data (returns null if none).
     */
    public static net.minecraft.nbt.CompoundTag consumeDeathData(UUID ownerUUID) {
        return DEATH_DATA.remove(ownerUUID);
    }

    /**
     * Check if a respawn cooldown is active for the given player.
     */
    public static boolean isOnCooldown(UUID ownerUUID, long gameTime) {
        Long deathTime = DEATH_COOLDOWNS.get(ownerUUID);
        if (deathTime == null) return false;
        return gameTime - deathTime < RESPAWN_COOLDOWN_TICKS;
    }

    /**
     * Get remaining cooldown seconds for the given player.
     */
    public static long getRemainingSeconds(UUID ownerUUID, long gameTime) {
        Long deathTime = DEATH_COOLDOWNS.get(ownerUUID);
        if (deathTime == null) return 0;
        long remainingTicks = RESPAWN_COOLDOWN_TICKS - (gameTime - deathTime);
        return Math.max(0, remainingTicks / 20);
    }

    // ================================================================
    // Use — summon companion on right-click
    // ================================================================

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide && level instanceof ServerLevel serverLevel) {
            UUID playerUUID = player.getUUID();

            // Shift+right-click = set home position for the companion (quick single-point home)
            if (player.isShiftKeyDown()) {
                BlockPos pos = player.blockPosition();
                // Save home to player persistent data (persists even without a living companion)
                player.getPersistentData().putLong("mcai:home_pos", pos.asLong());

                // If companion is alive, update it immediately
                if (CompanionEntity.hasLivingCompanion(playerUUID)) {
                    CompanionEntity companion = CompanionEntity.getLivingCompanion(playerUUID);
                    if (companion != null) {
                        companion.setHomePos(pos);
                        // Clear any existing home area — soul crystal sets single point
                        companion.setHomeCorner1(null);
                        companion.setHomeCorner2(null);
                    }
                }

                player.sendSystemMessage(Component.literal(
                        "§b[MCAi]§r Home position set to §e" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() +
                        "§r §7(use Logistics Wand Home Area mode for bounding box)"));
                player.getCooldowns().addCooldown(this, 10);
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }

            // Check if companion is already alive
            if (CompanionEntity.hasLivingCompanion(playerUUID)) {
                player.sendSystemMessage(Component.literal(
                        "§e[MCAi]§r Your companion is already alive!"));
                return InteractionResultHolder.fail(stack);
            }

            // Check respawn cooldown
            long gameTime = serverLevel.getGameTime();
            if (isOnCooldown(playerUUID, gameTime)) {
                long remaining = getRemainingSeconds(playerUUID, gameTime);
                player.sendSystemMessage(Component.literal(
                        "§e[MCAi]§r Companion soul is recovering... " + remaining + " seconds remaining."));
                return InteractionResultHolder.fail(stack);
            }

            // Summon the companion
            CompanionEntity companion = ModRegistry.COMPANION.get().create(serverLevel);
            if (companion != null) {
                companion.moveTo(player.getX(), player.getY(), player.getZ(),
                        player.getYRot(), 0.0F);
                companion.setOwnerUUID(playerUUID);

                // Restore companion name from player persistent data
                String savedName = player.getPersistentData().getString("mcai:companion_name");
                if (!savedName.isEmpty()) {
                    companion.setCompanionName(savedName);
                }

                // Restore full state if dismissed (inventory, equipment, health)
                companion.restoreStateFromOwner(player);

                // Restore inventory + equipment from death (if died rather than dismissed)
                net.minecraft.nbt.CompoundTag deathData = consumeDeathData(playerUUID);
                if (deathData != null) {
                    companion.restoreFromDeathData(deathData);
                }

                // Restore home position from player persistent data
                if (player.getPersistentData().contains("mcai:home_pos")) {
                    companion.setHomePos(BlockPos.of(player.getPersistentData().getLong("mcai:home_pos")));
                }
                // Restore home area corners from player persistent data
                if (player.getPersistentData().contains("mcai:home_corner1")) {
                    companion.setHomeCorner1(BlockPos.of(player.getPersistentData().getLong("mcai:home_corner1")));
                }
                if (player.getPersistentData().contains("mcai:home_corner2")) {
                    companion.setHomeCorner2(BlockPos.of(player.getPersistentData().getLong("mcai:home_corner2")));
                }

                serverLevel.addFreshEntity(companion);

                // Register in living companion tracker immediately
                CompanionEntity.registerLivingCompanion(playerUUID, companion);

                // Clear death cooldown
                clearDeathCooldown(playerUUID);

                player.sendSystemMessage(Component.literal(
                        "§b[MCAi]§r " + companion.getCompanionName() + " has been summoned!"));

                // Prevent spam-clicking (2 second item cooldown)
                player.getCooldowns().addCooldown(this, 40);
            }
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    // ================================================================
    // Visual
    // ================================================================

    @Override
    public boolean isFoil(ItemStack stack) {
        // Always show enchantment glint — it's a magical soul item
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.literal("§5Right-click to summon your AI companion"));
        tooltipComponents.add(Component.literal("§5Shift+right-click to set home position"));
        tooltipComponents.add(Component.literal("§8Reusable — not consumed on use"));
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }
}
