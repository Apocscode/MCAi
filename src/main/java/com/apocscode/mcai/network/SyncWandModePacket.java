package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: sync the current Logistics Wand mode.
 * Sent after mode cycling and on player login.
 */
public record SyncWandModePacket(int mode) implements CustomPacketPayload {
    public static final Type<SyncWandModePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "sync_wand_mode"));

    public static final StreamCodec<ByteBuf, SyncWandModePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, SyncWandModePacket::mode,
                    SyncWandModePacket::new);

    // Client-side cached mode ordinal (updated by packet handler)
    private static volatile int clientMode = 0;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncWandModePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            clientMode = packet.mode();
        });
    }

    /** Get the cached wand mode on client side (ordinal of TaggedBlock.Role) */
    public static int getClientMode() {
        return clientMode;
    }
}
