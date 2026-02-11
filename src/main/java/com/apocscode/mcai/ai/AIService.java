package com.apocscode.mcai.ai;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.tool.AiTool;
import com.apocscode.mcai.ai.tool.ToolContext;
import com.apocscode.mcai.ai.tool.ToolRegistry;
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
 * Supports tool-calling agent loop: the AI can invoke tools (web search,
 * inventory check, recipe lookup, etc.) and receive results before
 * generating a final response.
 *
 * All calls are async — never blocks the server tick thread.
 */
public class AIService {
    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL = "llama3.1"; // Change to your preferred model
    private static final int TIMEOUT_MS = 60000;
    private static final int MAX_TOOL_ITERATIONS = 5; // Prevent runaway tool loops
    private static final Gson GSON = new GsonBuilder().create();

    private static ExecutorService executor;

    public static void init() {
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "MCAi-AI-Worker");
            t.setDaemon(true);
            return t;
        });

        // Initialize tool registry
        ToolRegistry.init();

        MCAi.LOGGER.info("AI Service initialized (Ollama endpoint: {}, tools: {})",
                OLLAMA_URL, ToolRegistry.getAll().keySet());
    }

    /**
     * Send a message to the AI and get a response asynchronously.
     * Uses the agent loop: AI can call tools multiple times before responding.
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
                ToolContext toolCtx = new ToolContext(player, player.getServer());
                return agentLoop(userMessage, context, history, toolCtx);
            } catch (Exception e) {
                MCAi.LOGGER.error("AI chat error: {}", e.getMessage(), e);
                return "I'm having trouble connecting to my brain. Make sure Ollama is running on localhost:11434. Error: " + e.getMessage();
            }
        }, executor);
    }

    /**
     * The core agent loop. Sends messages to Ollama, checks if the AI wants
     * to call tools, executes them, feeds results back, and repeats until
     * the AI returns a text response or we hit the iteration limit.
     */
    private static String agentLoop(String userMessage, String playerContext,
                                     List<ConversationManager.ChatMessage> history,
                                     ToolContext toolCtx) throws IOException {

        // Build initial messages array
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

        // Agent loop — keep going until AI gives a text response or limit reached
        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            JsonObject response = callOllama(messages);
            JsonObject assistantMessage = response.getAsJsonObject("message");

            // Check if the AI wants to use tools
            if (assistantMessage.has("tool_calls") && !assistantMessage.get("tool_calls").isJsonNull()) {
                JsonArray toolCalls = assistantMessage.getAsJsonArray("tool_calls");
                if (toolCalls.size() > 0) {
                    MCAi.LOGGER.info("AI requesting {} tool call(s) (iteration {})",
                            toolCalls.size(), iteration + 1);

                    // Add the assistant's tool-calling message to the conversation
                    messages.add(assistantMessage);

                    // Execute each tool call and add results
                    for (JsonElement tcElement : toolCalls) {
                        JsonObject toolCall = tcElement.getAsJsonObject();
                        JsonObject function = toolCall.getAsJsonObject("function");
                        String toolName = function.get("name").getAsString();

                        // Parse arguments — Ollama may return as string or object
                        JsonObject toolArgs = new JsonObject();
                        if (function.has("arguments")) {
                            JsonElement argsEl = function.get("arguments");
                            if (argsEl.isJsonObject()) {
                                toolArgs = argsEl.getAsJsonObject();
                            } else if (argsEl.isJsonPrimitive()) {
                                // Sometimes Ollama returns args as a JSON string
                                try {
                                    toolArgs = JsonParser.parseString(argsEl.getAsString()).getAsJsonObject();
                                } catch (Exception e) {
                                    MCAi.LOGGER.warn("Failed to parse tool args string for {}: {}",
                                            toolName, argsEl.getAsString());
                                }
                            }
                        }

                        // Execute the tool
                        String result = executeTool(toolName, toolArgs, toolCtx);

                        // Add tool result as a "tool" role message
                        JsonObject toolResultMsg = new JsonObject();
                        toolResultMsg.addProperty("role", "tool");
                        toolResultMsg.addProperty("content", result);
                        messages.add(toolResultMsg);

                        MCAi.LOGGER.info("Tool '{}' executed, result length: {} chars",
                                toolName, result.length());
                    }

                    // Continue loop — Ollama will process tool results and either
                    // call more tools or generate a final response
                    continue;
                }
            }

            // No tool calls — extract the text response
            String content = "";
            if (assistantMessage.has("content") && !assistantMessage.get("content").isJsonNull()) {
                content = assistantMessage.get("content").getAsString().trim();
            }

            if (content.isEmpty()) {
                content = "I processed your request but don't have anything specific to say.";
            }

            return content;
        }

        // Exceeded iteration limit
        MCAi.LOGGER.warn("Agent loop hit max iterations ({})", MAX_TOOL_ITERATIONS);
        return "I used several tools trying to answer your question but ran out of steps. Here's what I know so far — try asking a more specific question.";
    }

    /**
     * Execute a single tool by name.
     */
    private static String executeTool(String toolName, JsonObject args, ToolContext context) {
        AiTool tool = ToolRegistry.get(toolName);
        if (tool == null) {
            MCAi.LOGGER.warn("AI tried to call unknown tool: {}", toolName);
            return "Error: tool '" + toolName + "' does not exist. Available tools: " +
                    String.join(", ", ToolRegistry.getAll().keySet());
        }

        try {
            return tool.execute(args, context);
        } catch (Exception e) {
            MCAi.LOGGER.error("Tool '{}' execution failed: {}", toolName, e.getMessage(), e);
            return "Error executing " + toolName + ": " + e.getMessage();
        }
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
     * Call Ollama's chat API with tool support.
     * Returns the full response JSON object.
     */
    private static JsonObject callOllama(JsonArray messages) throws IOException {
        // Build request
        JsonObject request = new JsonObject();
        request.addProperty("model", MODEL);
        request.add("messages", messages);
        request.addProperty("stream", false);

        // Attach tools definition so Ollama knows what's available
        JsonArray tools = ToolRegistry.toOllamaToolsArray();
        if (tools.size() > 0) {
            request.add("tools", tools);
        }

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

        String requestBody = GSON.toJson(request);
        MCAi.LOGGER.debug("Ollama request: {} chars", requestBody.length());

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String error = readStream(conn.getErrorStream());
            conn.disconnect();
            throw new IOException("Ollama returned HTTP " + responseCode + ": " + error);
        }

        String responseBody = readStream(conn.getInputStream());
        conn.disconnect();

        return JsonParser.parseString(responseBody).getAsJsonObject();
    }

    private static String buildSystemPrompt(String playerContext) {
        // Build tool descriptions for the system prompt
        StringBuilder toolDesc = new StringBuilder();
        for (AiTool tool : ToolRegistry.getAll().values()) {
            toolDesc.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }

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
                - If you're not sure about something, USE YOUR TOOLS to look it up
                
                Available tools:
                """ + toolDesc + """
                
                Tool usage guidelines:
                - Use web_search when the player asks about something you're not confident about
                - Use web_fetch to read a specific webpage after finding it via web_search
                - Use get_inventory to check what the player has before giving crafting advice
                - Use scan_surroundings to describe what's around the player
                - Use get_recipe to look up exact crafting recipes
                - Use get_looked_at_block when the player says 'this', 'that block', or 'this chest' — it tells you exactly what they're pointing at, including container contents
                - Use scan_containers to find containers with specific items nearby, or to figure out which chest to interact with
                - Use interact_container to take items from or put items into a specific container by coordinates — chain with scan_containers or get_looked_at_block to get the coordinates first
                - Use bookmark_location to save/recall named places — when the player says 'remember this as X' or 'where is X'
                - You can chain tools: get_looked_at_block → interact_container, or scan_containers → interact_container
                - Only use tools when they'd genuinely help. Don't use tools for simple greetings or basic Minecraft facts you already know.
                
                Current game state:
                """ + playerContext + """
                
                Rules:
                - Give practical, actionable advice
                - When suggesting commands, format as: /command
                - Reference specific item/block names accurately
                - If the player is low health or in danger, mention it
                - You can see the player's inventory, so reference specific items they have
                - After using tools, synthesize the results into a natural response — don't just dump raw tool output
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
