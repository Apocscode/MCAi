package com.apocscode.mcai;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.item.LogisticsWandItem;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.apocscode.mcai.network.ChatMessageHandler;
import com.apocscode.mcai.network.SyncWandModePacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side game event handlers.
 */
@EventBusSubscriber(modid = MCAi.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class ServerEventHandler {

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            // Sync wand mode to client on login
            TaggedBlock.Role mode = LogisticsWandItem.getWandMode(sp);
            PacketDistributor.sendToPlayer(sp, new SyncWandModePacket(mode.ordinal()));
        }
    }

    /**
     * Intercept game chat messages starting with "!" to send commands
     * or messages to the AI companion without opening the GUI.
     *
     * Usage from normal T chat:
     *   !follow    — quick command (same as in companion GUI)
     *   !come      — teleport companion to you
     *   !help      — list quick commands
     *   !say hello — send "hello" to the AI for a conversational reply
     */
    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        String raw = event.getRawText();
        if (raw == null || !raw.startsWith("!")) return;

        ServerPlayer player = event.getPlayer();
        CompanionEntity companion = CompanionEntity.getLivingCompanion(player.getUUID());
        if (companion == null) return; // No companion — let the message through as normal chat

        // Cancel the chat message so it doesn't appear in public chat
        event.setCanceled(true);

        // Route through the chat handler — responses appear as system messages in game chat
        ChatMessageHandler.handleFromGameChat(raw, player, companion);
    }
}
