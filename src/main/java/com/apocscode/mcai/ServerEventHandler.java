package com.apocscode.mcai;

import com.apocscode.mcai.item.LogisticsWandItem;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.apocscode.mcai.network.SyncWandModePacket;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
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
}
