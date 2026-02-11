package com.apocscode.mcai.ai;

import com.apocscode.mcai.MCAi;
import com.google.gson.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages communication with Ollama (local LLM) or compatible API.
 * All calls are async — never blocks the server tick thread.
 */
public class AIService {
    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "llama3.1"; // Change to your preferred model
    private static final int TIMEOUT_MS = 60000;
    private static final Gson GSON = new GsonBuilder().create();

    private static ExecutorService executor;

    public static void init() {
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "MCAi-AI-Worker");
            t.setDaemon(true);
            return t;
        });
        MCAi.LOGGER.info("AI Service initialized (Ollama endpoint: {})", OLLAMA_URL);
    }

    /**
     * Send a message to the AI and get a response asynchronously.
     *
     * @param userMessage The player's message
     * @param player      The server player (for context)
     * @param history     Previous conversation messages
     * @return Future containing the AI's response text
     */
    public static CompletableFuture<String> chat(String userMessage, ServerPlayer player,
                                                  List<ConversationManager.ChatMessage> history) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String context = buildPlayerContext(player);
                return callOllama(userMessage, context, history);
            } catch (Exception e) {
                MCAi.LOGGER.error("AI chat error: {}", e.getMessage());
                return "I'm having trouble connecting to my brain. Make sure Ollama is running on localhost:11434. Error: " + e.getMessage();
            }
        }, executor);
    }

    /**
     * Build context string from player's current game state.
     */
    private static String buildPlayerContext(ServerPlayer player) {
        StringBuilder ctx = new StringBuilder();

        // Position and dimension
        ctx.append("Player position: ")
                .append((int) player.getX()).append(", ")
                .append((int) player.getY()).append(", ")
                .append((int) player.getZ())
                .append(" in ").append(player.level().dimension().location())
                .append("\n");

        // Health and hunger
        ctx.append("Health: ").append((int) player.getHealth())
                .append("/").append((int) player.getMaxHealth())
                .append(", Food: ").append(player.getFoodData().getFoodLevel()).append("/20")
                .append("\n");

        // Biome
        var biome = player.level().getBiome(player.blockPosition());
        ctx.append("Biome: ").append(biome.unwrapKey().map(k -> k.location().toString()).orElse("unknown"))
                .append("\n");

        // Time and weather
        long dayTime = player.level().getDayTime() % 24000;
        String timeOfDay = dayTime < 6000 ? "Morning" : dayTime < 12000 ? "Afternoon" :
                dayTime < 18000 ? "Evening" : "Night";
        ctx.append("Time: ").append(timeOfDay);
        if (player.level().isRaining()) ctx.append(" (Raining)");
        if (player.level().isThundering()) ctx.append(" (Thunderstorm)");
        ctx.append("\n");

        // Game mode
        ctx.append("Game mode: ").append(player.gameMode.getGameModeForPlayer().getName()).append("\n");

        // Inventory summary (top items)
        Inventory inv = player.getInventory();
        List<String> items = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                items.add(stack.getDisplayName().getString() + " x" + stack.getCount());
            }
        }
        if (!items.isEmpty()) {
            ctx.append("Inventory (").append(items.size()).append(" stacks): ");
            ctx.append(String.join(", ", items.subList(0, Math.min(items.size(), 20))));
            if (items.size() > 20) ctx.append("... and ").append(items.size() - 20).append(" more");
            ctx.append("\n");
        }

        // Held item
        ItemStack mainHand = player.getMainHandItem();
        if (!mainHand.isEmpty()) {
            ctx.append("Holding: ").append(mainHand.getDisplayName().getString()).append("\n");
        }

        // XP level
        ctx.append("XP Level: ").append(player.experienceLevel).append("\n");

        return ctx.toString();
    }

    /**
     * Call Ollama's chat API with conversation history.
     */
    private static String callOllama(String userMessage, String playerContext,
                                     List<ConversationManager.ChatMessage> history) throws IOException {

        // Build messages array for Ollama
        JsonArray messages = new JsonArray();

        // System prompt
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", buildSystemPrompt(playerContext));
        messages.add(systemMsg);

        // Conversation history (last 20 messages to manage context window)
        int startIdx = Math.max(0, history.size() - 20);
        for (int i = startIdx; i < history.size(); i++) {
            ConversationManager.ChatMessage msg = history.get(i);
            if (msg.isSystem()) continue; // Skip system messages like "Thinking..."

            JsonObject histMsg = new JsonObject();
            histMsg.addProperty("role", msg.isPlayer() ? "user" : "assistant");
            histMsg.addProperty("content", msg.content());
            messages.add(histMsg);
        }

        // Current user message
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        // Build request
        JsonObject request = new JsonObject();
        request.addProperty("model", MODEL);
        request.add("messages", messages);
        request.addProperty("stream", false);

        // Options for response quality
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.7);
        options.addProperty("num_predict", 500); // Max tokens in response
        request.add("options", options);

        // Send HTTP request
        HttpURLConnection conn = (HttpURLConnection) URI.create(OLLAMA_URL).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(GSON.toJson(request).getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = readStream(conn.getErrorStream());
            throw new IOException("Ollama returned HTTP " + responseCode + ": " + error);
        }

        String responseBody = readStream(conn.getInputStream());
        conn.disconnect();

        // Parse response
        JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonObject message = response.getAsJsonObject("message");
        return message.get("content").getAsString().trim();
    }

    private static String buildSystemPrompt(String playerContext) {
        return """
                You are MCAi, an AI companion living inside Minecraft. You exist as an entity in the game world next to the player.
                
                Your personality:
                - Helpful, knowledgeable, and friendly
                - You speak concisely — this is an in-game chat, not an essay
                - Keep responses under 3-4 sentences unless the player asks for detail
                - Use Minecraft terminology naturally
                
                Your knowledge:
                - You know everything about Minecraft 1.21.1 (vanilla mechanics, crafting, mobs, biomes, redstone, commands)
                - You know about popular mods (Create, Mekanism, Applied Energistics, Thermal, etc.)
                - If you're not sure about something, say so honestly
                
                Current game state:
                """ + playerContext + """
                
                Rules:
                - Give practical, actionable advice
                - When suggesting commands, format as: /command
                - Reference specific item/block names accurately
                - If the player is low health or in danger, mention it
                - You can see the player's inventory, so reference specific items they have
                """;
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    public static void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
