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
 * Client → Server: Set the companion's behavior mode (STAY / FOLLOW / AUTO).
 */
public record SetBehaviorModePacket(int entityId, int mode) implements CustomPacketPayload {
    public static final Type<SetBehaviorModePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "set_behavior_mode"));

    public static final StreamCodec<ByteBuf, SetBehaviorModePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT, SetBehaviorModePacket::entityId,
                    ByteBufCodecs.INT, SetBehaviorModePacket::mode,
                    SetBehaviorModePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetBehaviorModePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                Entity entity = serverPlayer.level().getEntity(packet.entityId());
                if (entity instanceof CompanionEntity companion) {
                    // Only the owner can change the mode
                    if (serverPlayer.getUUID().equals(companion.getOwnerUUID())) {
                        CompanionEntity.BehaviorMode[] modes = CompanionEntity.BehaviorMode.values();
                        if (packet.mode() >= 0 && packet.mode() < modes.length) {
                            companion.setBehaviorMode(modes[packet.mode()]);
                        }
                    }
                }
            }
        });
    }
}
