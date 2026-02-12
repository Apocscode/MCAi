package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: Tell the companion the owner is done interacting
 * (chat screen or inventory screen closed).
 * This unfreezes companion movement.
 */
public record StopInteractingPacket(int entityId) implements CustomPacketPayload {
    public static final Type<StopInteractingPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "stop_interacting"));

    public static final StreamCodec<ByteBuf, StopInteractingPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT, StopInteractingPacket::entityId,
                    StopInteractingPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StopInteractingPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                Entity entity = serverPlayer.level().getEntity(packet.entityId());
                if (entity instanceof CompanionEntity companion) {
                    if (serverPlayer.getUUID().equals(companion.getOwnerUUID())) {
                        companion.setOwnerInteracting(false);
                    }
                }
            }
        });
    }
}
