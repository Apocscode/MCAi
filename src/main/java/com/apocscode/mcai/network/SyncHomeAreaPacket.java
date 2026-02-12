package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.client.LogisticsOutlineRenderer;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: sync the home area corners for outline rendering.
 * corner1 and corner2 are encoded as longs (BlockPos.asLong()).
 * Send 0L for both to clear the home area.
 */
public record SyncHomeAreaPacket(long corner1, long corner2) implements CustomPacketPayload {
    public static final Type<SyncHomeAreaPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "sync_home_area"));

    public static final StreamCodec<ByteBuf, SyncHomeAreaPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, SyncHomeAreaPacket::corner1,
                    ByteBufCodecs.VAR_LONG, SyncHomeAreaPacket::corner2,
                    SyncHomeAreaPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // Client-side cached corners
    private static volatile BlockPos clientCorner1 = null;
    private static volatile BlockPos clientCorner2 = null;

    public static void handle(SyncHomeAreaPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (packet.corner1() == 0L && packet.corner2() == 0L) {
                clientCorner1 = null;
                clientCorner2 = null;
            } else {
                clientCorner1 = BlockPos.of(packet.corner1());
                clientCorner2 = BlockPos.of(packet.corner2());
            }
        });
    }

    /** Get cached corner 1 on client side (may be null) */
    public static BlockPos getClientCorner1() {
        return clientCorner1;
    }

    /** Get cached corner 2 on client side (may be null) */
    public static BlockPos getClientCorner2() {
        return clientCorner2;
    }

    /** Whether the client has a complete home area to render */
    public static boolean hasClientHomeArea() {
        return clientCorner1 != null && clientCorner2 != null;
    }
}
