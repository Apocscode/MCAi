package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.AIService;
import com.apocscode.mcai.ai.AiLogger;
import com.apocscode.mcai.ai.ConversationManager;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.entity.CompanionChat;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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

        // === Quick commands (bypass AI) ===
        if (message.startsWith("!")) {
            Entity entity = serverPlayer.level().getEntity(packet.entityId());
            if (entity instanceof CompanionEntity companion) {
                String response = handleQuickCommand(message.substring(1).trim(), companion, serverPlayer);
                PacketDistributor.sendToPlayer(serverPlayer, new ChatResponsePacket(response));
            } else {
                PacketDistributor.sendToPlayer(serverPlayer, new ChatResponsePacket("No companion found."));
            }
            return;
        }

        // Look up companion entity to get its name
        String companionName = AiConfig.DEFAULT_COMPANION_NAME.get();
        Entity entity = serverPlayer.level().getEntity(packet.entityId());
        if (entity instanceof CompanionEntity companion) {
            companionName = companion.getCompanionName();
        }

        // Call AI asynchronously — NEVER block the server thread
        sendToAI(message, serverPlayer, companionName, false);
    }

    /**
     * Handle a message from the vanilla game chat (T key) prefixed with "!".
     * Called by ServerEventHandler when a player types !command in normal chat.
     * Responses are sent as system messages (not via the companion chat GUI packet).
     */
    public static void handleFromGameChat(String message, ServerPlayer player, CompanionEntity companion) {
        if (message == null || message.isBlank()) return;

        if (message.length() > 500) {
            message = message.substring(0, 500);
        }

        MCAi.LOGGER.info("Game chat from {}: {}", player.getName().getString(), message);

        // Quick commands (prefixed with !)
        if (message.startsWith("!")) {
            String response = handleQuickCommand(message.substring(1).trim(), companion, player);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§b[" + companion.getCompanionName() + "]§r " + response));
            return;
        }

        // Natural language — send to AI, respond in game chat
        sendToAI(message, player, companion.getCompanionName(), true);
    }

    /**
     * Send a message to the AI service and deliver the response.
     * @param useGameChat If true, respond via sendSystemMessage; if false, via ChatResponsePacket.
     */
    private static void sendToAI(String message, ServerPlayer player, String companionName, boolean useGameChat) {
        AIService.chat(message, player, ConversationManager.getHistoryForAI(), companionName)
                .thenAccept(response -> {
                    player.getServer().execute(() -> {
                        if (useGameChat) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "§b[" + companionName + "]§r " + response));
                        } else {
                            PacketDistributor.sendToPlayer(player, new ChatResponsePacket(response));
                        }
                    });
                })
                .exceptionally(ex -> {
                    MCAi.LOGGER.error("AI response failed", ex);
                    player.getServer().execute(() -> {
                        String errMsg = "Sorry, I had an error: " + ex.getMessage();
                        if (useGameChat) {
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    "§c[" + companionName + "]§r " + errMsg));
                        } else {
                            PacketDistributor.sendToPlayer(player, new ChatResponsePacket(errMsg));
                        }
                    });
                    return null;
                });
    }

    /**
     * Handle quick commands (prefixed with !) without going through Ollama.
     * Supported commands:
     *   !follow, !stay, !auto      — change behavior mode
     *   !come, !here               — teleport/pathfind to player
     *   !status                    — current state summary
     *   !cancel                    — cancel all tasks
     *   !equip                     — auto-equip best gear
     *   !heal                      — eat food if available
     *   !help                      — list commands
     */
    private static String handleQuickCommand(String cmd, CompanionEntity companion, ServerPlayer player) {
        String lower = cmd.toLowerCase();

        return switch (lower) {
            case "follow" -> {
                companion.setBehaviorMode(CompanionEntity.BehaviorMode.FOLLOW);
                yield "Following you!";
            }
            case "stay" -> {
                companion.setBehaviorMode(CompanionEntity.BehaviorMode.STAY);
                yield "Staying here.";
            }
            case "auto" -> {
                companion.setBehaviorMode(CompanionEntity.BehaviorMode.AUTO);
                yield "Autonomous mode active!";
            }
            case "come", "here" -> {
                double dist = companion.distanceTo(player);
                if (dist > 64.0) {
                    companion.teleportTo(player.getX(), player.getY(), player.getZ());
                    companion.getNavigation().stop();
                    yield "Teleporting to you!";
                } else if (dist > 4.0) {
                    companion.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), 1.4);
                    yield "Coming to you!";
                } else {
                    yield "I'm already right here.";
                }
            }
            case "status" -> {
                float hp = companion.getHealth();
                float maxHp = companion.getMaxHealth();
                String mode = companion.getBehaviorMode().name();
                String tasks = companion.getTaskManager().getStatusSummary();
                yield String.format("HP: %.0f/%.0f | Mode: %s | %s", hp, maxHp, mode, tasks);
            }
            case "cancel", "stop" -> {
                companion.getTaskManager().cancelAll();
                companion.getNavigation().stop();
                yield "All tasks cancelled.";
            }
            case "equip" -> {
                companion.autoEquipBestGear();
                yield "Equipped best available gear!";
            }
            case "hp", "health" -> {
                float hp = companion.getHealth();
                float maxHp = companion.getMaxHealth();
                float pct = (hp / maxHp) * 100f;
                yield String.format("Health: %.0f/%.0f (%.0f%%)", hp, maxHp, pct);
            }
            case "help", "?" -> {
                yield "Quick commands: !follow !stay !auto !come !status !cancel !equip !health !help";
            }
            default -> "Unknown command: !" + cmd + ". Type !help for a list.";
        };
    }
}
