package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.item.LogisticsWandItem;
import com.apocscode.mcai.network.CycleWandModePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client input handler for the Logistics Wand.
 * Shift+scroll cycles the wand mode (INPUT / OUTPUT / STORAGE).
 */
@EventBusSubscriber(modid = MCAi.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class LogisticsWandInputHandler {

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        // Only when shift is held
        if (!player.isShiftKeyDown()) return;

        // Only when holding the Logistics Wand
        boolean holdingWand = player.getMainHandItem().getItem() instanceof LogisticsWandItem
                || player.getOffhandItem().getItem() instanceof LogisticsWandItem;
        if (!holdingWand) return;

        // Consume the scroll event so it doesn't change hotbar slot
        event.setCanceled(true);

        // Send packet to server: scroll up = forward, scroll down = backward
        boolean forward = event.getScrollDeltaY() > 0;
        PacketDistributor.sendToServer(new CycleWandModePacket(forward));
    }
}
