package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.ConversationManager;
import com.apocscode.mcai.network.ChatMessagePacket;
import com.apocscode.mcai.network.WhistleCompanionPacket;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Handles client-side tick events for push-to-talk.
 * When the PTT key is held down outside the chat screen, records audio.
 * On release, transcribes and auto-sends to the AI companion.
 */
@EventBusSubscriber(modid = MCAi.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class ClientTickHandler {

    private static boolean wasKeyDown = false;
    private static int companionEntityId = -1;

    /**
     * Set the companion entity ID for auto-send.
     * Called when the player interacts with a companion.
     */
    public static void setCompanionEntityId(int entityId) {
        companionEntityId = entityId;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Don't process push-to-talk if the chat screen is open (it handles it internally)
        if (mc.screen instanceof CompanionChatScreen) return;

        boolean isKeyDown = ModKeybinds.PUSH_TO_TALK.isDown();

        if (isKeyDown && !wasKeyDown) {
            // Key just pressed — start recording
            if (WhisperService.isAvailable() && !WhisperService.isRecording()) {
                WhisperService.init(); // Ensure initialized
                WhisperService.startRecording();

                // Show recording indicator in action bar
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§c\u25CF §fRecording... §7(release V to send)"),
                        true
                );
            }
        } else if (!isKeyDown && wasKeyDown) {
            // Key just released — stop and transcribe
            if (WhisperService.isRecording()) {
                mc.player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§e\u23F3 §fTranscribing..."),
                        true
                );

                WhisperService.stopRecordingAndTranscribe().thenAccept(text -> {
                    if (text != null && !text.isBlank() && companionEntityId >= 0) {
                        mc.execute(() -> {
                            // Add to conversation and send
                            ConversationManager.addPlayerMessage(text);
                            PacketDistributor.sendToServer(
                                    new ChatMessagePacket(companionEntityId, text)
                            );
                            ConversationManager.addSystemMessage("Thinking...");

                            mc.player.displayClientMessage(
                                    net.minecraft.network.chat.Component.literal("§a\u2713 §fSent: §7" + text),
                                    true
                            );
                        });
                    } else if (text == null || text.isBlank()) {
                        mc.execute(() -> mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("§7No speech detected"),
                                true
                        ));
                    } else {
                        mc.execute(() -> mc.player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("§cNo companion nearby — right-click one first"),
                                true
                        ));
                    }
                });
            }
        } else if (isKeyDown && wasKeyDown) {
            // Key held — update recording indicator with elapsed time
            // (happens each tick, but actionbar messages persist)
        }

        wasKeyDown = isKeyDown;

        // --- Whistle keybind (G) ---
        if (ModKeybinds.WHISTLE.consumeClick()) {
            PacketDistributor.sendToServer(new WhistleCompanionPacket());
            mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§b\uD83D\uDD14 §fWhistling for companion..."),
                    true
            );
        }
    }
}
