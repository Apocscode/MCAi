package com.apocscode.mcai.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * Proactive chat system for the companion entity.
 * Sends status messages to the owner with per-category cooldowns to prevent spam.
 *
 * Messages appear in the player's chat as colored "[CompanionName] message" lines.
 * Each category has its own cooldown so important messages aren't blocked by routine ones.
 */
public class CompanionChat {

    /** Message categories — each has its own cooldown timer. */
    public enum Category {
        GREETING(6000),          // 5 min between greetings
        COMBAT(200),             // 10 sec between combat messages
        COMBAT_VICTORY(100),     // 5 sec — just killed something
        LOW_HEALTH(400),         // 20 sec between low-health warnings
        CRITICAL_HEALTH(200),    // 10 sec — very low HP
        HUNGRY(600),             // 30 sec between hunger messages
        HUNTING(400),            // 20 sec
        FARMING(600),            // 30 sec
        COOKING(600),            // 30 sec
        EATING(200),             // 10 sec
        STUCK(400),              // 20 sec between stuck notices
        INVENTORY_FULL(600),     // 30 sec
        TELEPORT(100),           // 5 sec — just teleported
        ITEM_PICKUP(400),        // 20 sec for notable pickups
        NO_WEAPON(400),          // 20 sec
        NO_FOOD(600),            // 30 sec
        NO_FUEL(600),            // 30 sec
        TASK(100),               // 5 sec for task status updates
        GENERAL(200);            // 10 sec for misc

        public final int cooldownTicks;
        Category(int cooldownTicks) {
            this.cooldownTicks = cooldownTicks;
        }
    }

    private final CompanionEntity companion;
    private final Map<Category, Integer> cooldowns = new HashMap<>();

    public CompanionChat(CompanionEntity companion) {
        this.companion = companion;
    }

    /**
     * Send a proactive chat message to the owner if the category isn't on cooldown.
     *
     * @param category  Message category for cooldown tracking
     * @param message   The plain text message (will be prefixed with companion name)
     * @return true if the message was sent, false if on cooldown or no owner
     */
    public boolean say(Category category, String message) {
        if (companion.level().isClientSide) return false;

        Player owner = companion.getOwner();
        if (owner == null || !(owner instanceof ServerPlayer)) return false;

        // Check cooldown
        int remaining = cooldowns.getOrDefault(category, 0);
        if (remaining > 0) return false;

        // Set cooldown
        cooldowns.put(category, category.cooldownTicks);

        // Format and send
        String name = companion.getCompanionName();
        String formatted = "§b[" + name + "]§r " + message;
        owner.sendSystemMessage(Component.literal(formatted));

        return true;
    }

    /**
     * Send a warning-level message (yellow prefix).
     */
    public boolean warn(Category category, String message) {
        if (companion.level().isClientSide) return false;

        Player owner = companion.getOwner();
        if (owner == null || !(owner instanceof ServerPlayer)) return false;

        int remaining = cooldowns.getOrDefault(category, 0);
        if (remaining > 0) return false;

        cooldowns.put(category, category.cooldownTicks);

        String name = companion.getCompanionName();
        String formatted = "§e[" + name + "]§r " + message;
        owner.sendSystemMessage(Component.literal(formatted));
        return true;
    }

    /**
     * Send an urgent/danger message (red prefix). Ignores cooldown.
     */
    public void urgent(String message) {
        if (companion.level().isClientSide) return;

        Player owner = companion.getOwner();
        if (owner == null || !(owner instanceof ServerPlayer)) return;

        String name = companion.getCompanionName();
        String formatted = "§c[" + name + "]§r " + message;
        owner.sendSystemMessage(Component.literal(formatted));
    }

    /**
     * Tick all cooldowns down. Call from CompanionEntity.tick().
     */
    public void tick() {
        cooldowns.entrySet().removeIf(e -> {
            e.setValue(e.getValue() - 1);
            return e.getValue() <= 0;
        });
    }
}
