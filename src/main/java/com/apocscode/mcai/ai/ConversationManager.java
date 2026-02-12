package com.apocscode.mcai.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side conversation history manager.
 * Stores chat messages for display in the CompanionChatScreen.
 */
public class ConversationManager {
    private static final List<ChatMessage> messages = new ArrayList<>();
    private static final int MAX_HISTORY = 100;

    public record ChatMessage(String content, MessageType type, long timestamp) {
        public boolean isPlayer() { return type == MessageType.PLAYER; }
        public boolean isAi() { return type == MessageType.AI; }
        public boolean isSystem() { return type == MessageType.SYSTEM; }
    }

    public enum MessageType {
        PLAYER, AI, SYSTEM
    }

    public static void addPlayerMessage(String content) {
        add(new ChatMessage(content, MessageType.PLAYER, System.currentTimeMillis()));
    }

    public static void addAiMessage(String content) {
        // Remove "Thinking..." message if present
        messages.removeIf(m -> m.isSystem() && m.content().equals("Thinking..."));
        add(new ChatMessage(content, MessageType.AI, System.currentTimeMillis()));
    }

    public static void addSystemMessage(String content) {
        add(new ChatMessage(content, MessageType.SYSTEM, System.currentTimeMillis()));
    }

    private static void add(ChatMessage msg) {
        messages.add(msg);
        // Trim old messages
        while (messages.size() > MAX_HISTORY) {
            messages.remove(0);
        }
    }

    public static List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * Get messages suitable for sending to the AI (player + AI + task system messages).
     * Includes system messages about task completions for continuation context.
     */
    public static List<ChatMessage> getHistoryForAI() {
        return messages.stream()
                .filter(m -> !m.isSystem() || m.content().startsWith("[Task"))
                .toList();
    }

    public static void clear() {
        messages.clear();
    }
}
