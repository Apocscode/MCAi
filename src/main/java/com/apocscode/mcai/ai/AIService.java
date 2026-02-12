package com.apocscode.mcai.ai;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.tool.AiTool;
import com.apocscode.mcai.ai.tool.ToolContext;
import com.apocscode.mcai.ai.tool.ToolRegistry;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.network.ChatResponsePacket;
import com.apocscode.mcai.task.TaskContinuation;
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

        try {
            MCAi.LOGGER.info("AI Service initialized (Ollama: {}, model: {}, tools: {})",
                    AiConfig.OLLAMA_URL.get(), AiConfig.OLLAMA_MODEL.get(),
                    ToolRegistry.getAll().keySet());
            AiLogger.log(AiLogger.Category.SYSTEM, "INFO",
                    "AIService initialized — model=" + AiConfig.OLLAMA_MODEL.get() +
                    ", tools=" + ToolRegistry.getAll().size());
        } catch (Exception e) {
            // Config may not be loaded yet during mod construction — log defaults
            MCAi.LOGGER.info("AI Service initialized (config not yet loaded, {} tools registered)",
                    ToolRegistry.getAll().size());
        }
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
                                                  List<ConversationManager.ChatMessage> history,
                                                  String companionName) {
        return CompletableFuture.supplyAsync(() -> {
            long startMs = System.currentTimeMillis();
            try {
                AiLogger.chat(player.getName().getString(), userMessage);
                String context = buildPlayerContext(player);
                ToolContext toolCtx = new ToolContext(player, player.getServer());
                String response = agentLoop(userMessage, context, history, toolCtx, companionName);
                long elapsed = System.currentTimeMillis() - startMs;
                AiLogger.aiResponse(response, elapsed);
                AiLogger.performance("Full chat cycle", elapsed);
                return response;
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startMs;
                AiLogger.error("AI chat error after " + elapsed + "ms", e);
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
                                     ToolContext toolCtx, String companionName) throws IOException {

        // Build initial messages array
        JsonArray messages = new JsonArray();

        // System prompt
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", buildSystemPrompt(playerContext, companionName));
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
        int maxIterations = AiConfig.MAX_TOOL_ITERATIONS.get();
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            JsonObject response = callOllama(messages, userMessage);
            JsonObject assistantMessage = response.getAsJsonObject("message");

            // Check if the AI wants to use tools
            if (assistantMessage.has("tool_calls") && !assistantMessage.get("tool_calls").isJsonNull()) {
                JsonArray toolCalls = assistantMessage.getAsJsonArray("tool_calls");
                if (toolCalls.size() > 0) {
                    MCAi.LOGGER.info("AI requesting {} tool call(s) (iteration {})",
                            toolCalls.size(), iteration + 1);
                    AiLogger.agentIteration(iteration, toolCalls.size());

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
                        long toolStartMs = System.currentTimeMillis();
                        String result = executeTool(toolName, toolArgs, toolCtx);
                        long toolElapsed = System.currentTimeMillis() - toolStartMs;

                        // Add tool result as a "tool" role message
                        JsonObject toolResultMsg = new JsonObject();
                        toolResultMsg.addProperty("role", "tool");
                        toolResultMsg.addProperty("content", result);
                        messages.add(toolResultMsg);

                        MCAi.LOGGER.info("Tool '{}' executed in {}ms, result length: {} chars",
                                toolName, toolElapsed, result.length());
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
        MCAi.LOGGER.warn("Agent loop hit max iterations ({})", maxIterations);
        AiLogger.log(AiLogger.Category.AI_RESPONSE, "WARN",
                "Agent loop exceeded max iterations (" + maxIterations + ")");
        return "I used several tools trying to answer your question but ran out of steps. Here's what I know so far — try asking a more specific question.";
    }

    /**
     * Execute a single tool by name.
     */
    private static String executeTool(String toolName, JsonObject args, ToolContext context) {
        AiTool tool = ToolRegistry.get(toolName);
        if (tool == null) {
            AiLogger.error("AI tried to call unknown tool: " + toolName);
            MCAi.LOGGER.warn("AI tried to call unknown tool: {}", toolName);
            return "Error: tool '" + toolName + "' does not exist. Available tools: " +
                    String.join(", ", ToolRegistry.getAll().keySet());
        }

        // Check if tool is enabled in config
        if (!AiConfig.isToolEnabled(toolName)) {
            AiLogger.toolDisabled(toolName);
            return "Error: tool '" + toolName + "' is disabled in the server configuration.";
        }

        AiLogger.toolCall(toolName, args.toString());
        long startMs = System.currentTimeMillis();

        try {
            String result = tool.execute(args, context);
            long elapsed = System.currentTimeMillis() - startMs;
            AiLogger.toolResult(toolName, result, elapsed);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            AiLogger.toolError(toolName, e.getMessage(), e);
            MCAi.LOGGER.error("Tool '{}' execution failed in {}ms: {}", toolName, elapsed, e.getMessage(), e);
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

        // Companion state
        CompanionEntity companion = CompanionEntity.getLivingCompanion(player.getUUID());
        if (companion != null) {
            ctx.append("Companion health: ").append((int) companion.getHealth())
                    .append("/").append((int) companion.getMaxHealth()).append("\n");
            ctx.append("Companion level: ").append(companion.getLevelSystem().getDisplayString()).append("\n");
            ctx.append("Companion mode: ").append(companion.getBehaviorMode().name()).append("\n");

            // Include memory context
            String memoryCtx = companion.getMemory().buildContextString();
            if (!memoryCtx.isEmpty()) {
                ctx.append(memoryCtx);
            }
        }

        return ctx.toString();
    }

    /**
     * Call Ollama's chat API with tool support.
     * Uses dynamic tool selection based on the user message to keep tool count ≤16.
     * Returns the full response JSON object.
     */
    private static JsonObject callOllama(JsonArray messages, String userMessage) throws IOException {
        // Build request
        JsonObject request = new JsonObject();
        request.addProperty("model", AiConfig.OLLAMA_MODEL.get());
        request.add("messages", messages);
        request.addProperty("stream", false);

        // Attach only relevant tools — dynamic selection keeps count manageable for small models
        JsonArray tools = ToolRegistry.toOllamaToolsArray(userMessage);
        if (tools.size() > 0) {
            request.add("tools", tools);
        }

        // Options for response quality
        JsonObject options = new JsonObject();
        options.addProperty("temperature", AiConfig.AI_TEMPERATURE.get());
        options.addProperty("num_predict", AiConfig.AI_MAX_TOKENS.get());
        request.add("options", options);

        AiLogger.aiRequest(messages.size(), tools.size(), AiConfig.OLLAMA_MODEL.get());

        // Send HTTP request
        int timeoutMs = AiConfig.AI_TIMEOUT_MS.get();
        // Force IPv4: Java may resolve "localhost" to IPv6 [::1] while Ollama binds IPv4 only
        String url = AiConfig.OLLAMA_URL.get().replace("localhost", "127.0.0.1");
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);

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

    private static String buildSystemPrompt(String playerContext, String companionName) {
        return """
                You are %s, an AI companion in Minecraft. You are an entity in the game world next to the player.
                
                Personality: Helpful, concise, friendly. Keep responses under 3 sentences. Your name is %s.
                
                CRITICAL RULE: When the player asks you to DO something (craft, mine, build, fetch, etc.), you MUST call the appropriate tool function. NEVER describe or explain tool syntax — just call the tool directly. Act, don't explain.
                
                INTENT DETECTION — read carefully:
                - "how do I make X" / "how to craft X" / "what do I need for X" / "recipe for X" → INFORMATIONAL. Call get_recipe to show the recipe. Do NOT craft it.
                - "make me X" / "can you make X" / "craft X" / "build me X" / "I need X" → ACTION. Call craft_item to actually craft it.
                - If unclear, assume ACTION (craft it).
                
                Key tool patterns:
                - "make/craft X" → call craft_item with item name (e.g. "stone_axe"). It auto-resolves intermediate steps.
                - "get/bring me X" → call find_and_fetch_item
                - "mine/gather X" → call gather_blocks or mine_ores
                - "chop trees" → call chop_trees
                - "smelt X" → call smelt_items (needs real furnace + fuel)
                - "what's around/scan" → call scan_surroundings
                - "what do I have" → call get_inventory
                - "recipe for X" → call get_recipe
                - "make it day/night" → call execute_command with "time set day"
                - "change name to X" → call rename_companion
                - "remember X" → call companion_memory with action='remember'
                - "guard here" → call guard_area
                - "build a wall/shelter" → call build_structure
                - "go fish" → call go_fishing
                - "trade with villager" → call villager_trade
                - "deliver X to Y" → call deliver_items
                - Chain tasks with 'plan' param: mine_ores({"plan":"smelt raw_iron, then craft iron_pickaxe"})
                - When [TASK_COMPLETE] arrives, execute the next plan step
                - craft_item auto-resolves logs→planks→sticks but does NOT smelt. Use smelt_items for smelting.
                
                Current game state:
                """.formatted(companionName, companionName) + playerContext + """
                
                Rules:
                - ACT first, explain after. If the player says "make an axe", call craft_item immediately.
                - Use get_inventory before crafting to check available materials.
                - After tool results, give a brief natural response — don't dump raw output.
                - Only use tools when genuinely helpful. Simple greetings don't need tools.
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
        AiLogger.shutdown();
    }

    /**
     * Continue an AI plan after a task completes.
     * Called from TaskManager on the server tick thread when a task with a
     * continuation finishes. Triggers a new AI chat turn with the task result
     * and plan context, then sends the response back to the player.
     *
     * @param continuation The continuation plan attached to the completed task
     * @param taskResult   Description of the task outcome (e.g. "Gathered 8 Cobblestone")
     * @param player       The server player to send the response to
     * @param companionName The companion's display name
     */
    public static void continueAfterTask(TaskContinuation continuation, String taskResult,
                                          ServerPlayer player, String companionName) {
        if (executor == null || executor.isShutdown()) return;

        String syntheticMessage = continuation.buildContinuationMessage(taskResult);
        MCAi.LOGGER.info("Task continuation for {}: {}", player.getName().getString(), syntheticMessage);

        // Add a system note to client-side history so the AI has context
        ConversationManager.addSystemMessage("[Task completed: " + taskResult + "]");

        chat(syntheticMessage, player, ConversationManager.getHistoryForAI(), companionName)
                .thenAccept(response -> {
                    player.getServer().execute(() -> {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                                player, new ChatResponsePacket(response));
                    });
                })
                .exceptionally(ex -> {
                    MCAi.LOGGER.error("Task continuation failed", ex);
                    player.getServer().execute(() -> {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                                player, new ChatResponsePacket(
                                        "I finished the task but had trouble continuing the plan: " + ex.getMessage()));
                    });
                    return null;
                });
    }
}
