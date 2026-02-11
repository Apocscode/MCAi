package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: Player sends a chat message to the companion.
 */
public record ChatMessagePacket(int entityId, String message) implements CustomPacketPayload {
    public static final Type<ChatMessagePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "chat_message"));

    public static final StreamCodec<ByteBuf, ChatMessagePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT, ChatMessagePacket::entityId,
                    ByteBufCodecs.STRING_UTF8, ChatMessagePacket::message,
                    ChatMessagePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChatMessagePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // This runs on the server — process the chat message
            ChatMessageHandler.handleOnServer(packet, context);
        });
    }
}
