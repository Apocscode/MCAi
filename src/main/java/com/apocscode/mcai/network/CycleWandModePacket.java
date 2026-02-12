package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.item.LogisticsWandItem;
import com.apocscode.mcai.item.WandMode;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: cycle the Logistics Wand mode (triggered by shift+scroll).
 * forward = true scrolls to next mode, false scrolls to previous mode.
 * Now cycles through WandMode (INPUT/OUTPUT/STORAGE/HOME_AREA).
 */
public record CycleWandModePacket(boolean forward) implements CustomPacketPayload {
    public static final Type<CycleWandModePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "cycle_wand_mode"));

    public static final StreamCodec<ByteBuf, CycleWandModePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, CycleWandModePacket::forward,
                    CycleWandModePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CycleWandModePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                // Check player is holding the wand
                boolean holdingWand = player.getMainHandItem().getItem() instanceof LogisticsWandItem
                        || player.getOffhandItem().getItem() instanceof LogisticsWandItem;
                if (!holdingWand) return;

                WandMode current = LogisticsWandItem.getWandMode(player);
                WandMode next = packet.forward() ? current.next() : current.prev();
                LogisticsWandItem.setWandMode(player, next);

                String colorCode = next.getChatColor();
                player.sendSystemMessage(Component.literal(
                        "§b[MCAi]§r Logistics Wand mode: " + colorCode + next.getLabel() + "§r"));

                player.level().playSound(null, player.blockPosition(),
                        SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS,
                        0.5F, 1.2F + next.ordinal() * 0.15F);

                // Sync mode to client for HUD display
                PacketDistributor.sendToPlayer(player, new SyncWandModePacket(next.ordinal()));
            }
        });
    }
}
