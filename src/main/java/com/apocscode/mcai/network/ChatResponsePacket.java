package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.apocscode.mcai.ai.ConversationManager;

/**
 * Server → Client: AI response text.
 */
public record ChatResponsePacket(String response) implements CustomPacketPayload {
    public static final Type<ChatResponsePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "chat_response"));

    public static final StreamCodec<ByteBuf, ChatResponsePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ChatResponsePacket::response,
                    ChatResponsePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChatResponsePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Client-side: add AI response to conversation
            ConversationManager.addAiMessage(packet.response());
        });
    }
}
