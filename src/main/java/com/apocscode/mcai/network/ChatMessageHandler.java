package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.AIService;
import com.apocscode.mcai.ai.ConversationManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server-side handler for incoming chat messages.
 * Calls the AI service asynchronously and sends the response back to the client.
 */
public class ChatMessageHandler {

    public static void handleOnServer(ChatMessagePacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer serverPlayer)) return;

        String message = packet.message();
        if (message == null || message.isBlank()) return;

        // Clamp message length
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }

        MCAi.LOGGER.info("Chat from {}: {}", serverPlayer.getName().getString(), message);

        // Call AI asynchronously — NEVER block the server thread
        AIService.chat(message, serverPlayer, ConversationManager.getHistoryForAI())
                .thenAccept(response -> {
                    // Send response back to client
                    serverPlayer.getServer().execute(() -> {
                        PacketDistributor.sendToPlayer(serverPlayer, new ChatResponsePacket(response));
                    });
                })
                .exceptionally(ex -> {
                    MCAi.LOGGER.error("AI response failed", ex);
                    serverPlayer.getServer().execute(() -> {
                        PacketDistributor.sendToPlayer(serverPlayer,
                                new ChatResponsePacket("Sorry, I had an error: " + ex.getMessage()));
                    });
                    return null;
                });
    }
}
