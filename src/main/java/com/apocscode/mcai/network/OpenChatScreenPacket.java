package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.client.CompanionChatScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: Tell client to open the chat screen for a companion entity.
 */
public record OpenChatScreenPacket(int entityId) implements CustomPacketPayload {
    public static final Type<OpenChatScreenPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "open_chat"));

    public static final StreamCodec<ByteBuf, OpenChatScreenPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT, OpenChatScreenPacket::entityId,
                    OpenChatScreenPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenChatScreenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new CompanionChatScreen(packet.entityId()));
        });
    }
}
