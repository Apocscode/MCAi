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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            String backend = AiConfig.isGroqEnabled() ? "Groq (" + AiConfig.GROQ_MODEL.get() + ")"
                    : "Ollama (" + AiConfig.OLLAMA_MODEL.get() + ")";
            MCAi.LOGGER.info("AI Service initialized (backend: {}, tools: {})",
                    backend, ToolRegistry.getAll().size());
            AiLogger.log(AiLogger.Category.SYSTEM, "INFO",
                    "AIService initialized — backend=" + backend +
                    ", tools=" + ToolRegistry.getAll().size());
        } catch (Exception e) {
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
                return "I'm having trouble connecting to my brain. " +
                        (AiConfig.isGroqEnabled() ? "Check your Groq API key." : "Make sure Ollama is running on localhost:11434.") +
                        " Error: " + e.getMessage();
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
        boolean useGroq = AiConfig.isGroqEnabled();
        JsonArray messages = new JsonArray();

        // System prompt
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", buildSystemPrompt(playerContext, companionName));
        messages.add(systemMsg);

        // Conversation history — fewer messages for Groq to stay within free-tier TPM limits
        int historyLimit = useGroq ? 8 : 20;
        int startIdx = Math.max(0, history.size() - historyLimit);
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
            JsonObject response = useGroq ? callGroq(messages, userMessage) : callOllama(messages, userMessage);

            // Extract assistant message — Groq wraps in choices[0].message, Ollama uses message directly
            JsonObject assistantMessage;
            if (useGroq) {
                assistantMessage = response.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message");
            } else {
                assistantMessage = response.getAsJsonObject("message");
            }

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

                        // Groq tool calls have an id that must be echoed back
                        String toolCallId = toolCall.has("id") ? toolCall.get("id").getAsString() : null;

                        // Parse arguments — may be string (Groq/OpenAI) or object (Ollama)
                        JsonObject toolArgs = new JsonObject();
                        if (function.has("arguments")) {
                            JsonElement argsEl = function.get("arguments");
                            if (argsEl.isJsonObject()) {
                                toolArgs = argsEl.getAsJsonObject();
                            } else if (argsEl.isJsonPrimitive()) {
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

                        // Add tool result — Groq requires tool_call_id, Ollama just uses "tool" role
                        JsonObject toolResultMsg = new JsonObject();
                        toolResultMsg.addProperty("role", "tool");
                        toolResultMsg.addProperty("content", result);
                        if (toolCallId != null) {
                            toolResultMsg.addProperty("tool_call_id", toolCallId);
                            toolResultMsg.addProperty("name", toolName);
                        }
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

            // FALLBACK: Small models sometimes write tool calls as text instead of
            // using the tool_calls format. Detect and execute them.
            String parsedToolName = tryParseTextToolCall(content);
            if (parsedToolName != null) {
                JsonObject parsedArgs = tryParseTextToolArgs(content);
                MCAi.LOGGER.info("Fallback: parsed text tool call '{}' with args: {}",
                        parsedToolName, parsedArgs);
                AiLogger.log(AiLogger.Category.TOOL_CALL, "INFO",
                        "FALLBACK parsed text tool call: " + parsedToolName);

                // Add the original assistant message
                messages.add(assistantMessage);

                // Execute the tool
                long toolStartMs = System.currentTimeMillis();
                String result = executeTool(parsedToolName, parsedArgs, toolCtx);
                long toolElapsed = System.currentTimeMillis() - toolStartMs;

                // Add tool result
                JsonObject toolResultMsg = new JsonObject();
                toolResultMsg.addProperty("role", "tool");
                toolResultMsg.addProperty("content", result);
                messages.add(toolResultMsg);

                MCAi.LOGGER.info("Fallback tool '{}' executed in {}ms, result: {} chars",
                        parsedToolName, toolElapsed, result.length());
                continue; // Let the loop continue for the AI to process results
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

    // ========== Text-to-tool-call fallback parser ==========
    // Small models (8B) sometimes write tool calls as text like:
    //   gather_blocks({"block":"cobblestone", "maxBlocks":3})
    // instead of using the structured tool_calls format.
    // These helpers detect and parse such text so it can be executed.

    /** Pattern: tool_name({...}) — uses greedy match to handle nested braces in values */
    private static final Pattern TEXT_TOOL_PATTERN = Pattern.compile(
            "\\b([a-z_]+)\\s*\\(\\s*(\\{.+\\})\\s*\\)", Pattern.DOTALL);

    /**
     * Try to extract a tool name from text that looks like a tool call.
     * Returns null if no tool call pattern found or the name isn't a registered tool.
     */
    private static String tryParseTextToolCall(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = TEXT_TOOL_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (ToolRegistry.get(name) != null) {
                return name;
            }
        }
        return null;
    }

    /**
     * Try to extract tool arguments JSON from text that looks like a tool call.
     * Returns empty JsonObject if parsing fails.
     */
    private static JsonObject tryParseTextToolArgs(String text) {
        if (text == null || text.isEmpty()) return new JsonObject();
        Matcher m = TEXT_TOOL_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (ToolRegistry.get(name) != null) {
                String argsStr = m.group(2);
                try {
                    // Fix common issues: single quotes → double quotes
                    argsStr = argsStr.replace("'", "\"");
                    return JsonParser.parseString(argsStr).getAsJsonObject();
                } catch (Exception e) {
                    // If nested braces break JSON parsing, try stripping the plan value
                    try {
                        argsStr = argsStr.replaceAll(",\\s*\"plan\"\\s*:\\s*\"[^\"]*\"", "");
                        return JsonParser.parseString(argsStr).getAsJsonObject();
                    } catch (Exception e2) {
                        MCAi.LOGGER.warn("Fallback: found tool '{}' but couldn't parse args: {}", name, m.group(2));
                        return new JsonObject();
                    }
                }
            }
        }
        return new JsonObject();
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

    /**
     * Call Groq cloud API (OpenAI-compatible) with tool support.
     * Uses dynamic tool selection to stay within free-tier token limits (12K TPM).
     * Retries automatically on 429 rate-limit errors with backoff.
     * Returns the full response JSON object (OpenAI format with choices[]).
     */
    private static JsonObject callGroq(JsonArray messages, String userMessage) throws IOException {
        // Build request — OpenAI chat completions format
        JsonObject request = new JsonObject();
        request.addProperty("model", AiConfig.GROQ_MODEL.get());
        request.add("messages", messages);
        request.addProperty("temperature", AiConfig.AI_TEMPERATURE.get());
        request.addProperty("max_tokens", AiConfig.AI_MAX_TOKENS.get());
        request.addProperty("stream", false);

        // Use dynamic tool selection — keeps token usage low for free-tier TPM limits
        JsonArray tools = ToolRegistry.toOllamaToolsArray(userMessage);
        if (tools.size() > 0) {
            request.add("tools", tools);
            request.addProperty("tool_choice", "auto");
        }

        String model = AiConfig.GROQ_MODEL.get();
        AiLogger.aiRequest(messages.size(), tools.size(), model);

        String requestBody = GSON.toJson(request);
        MCAi.LOGGER.debug("Groq request: {} chars, model: {}", requestBody.length(), model);

        // Retry loop for rate limits (429)
        int maxRetries = 3;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            int timeoutMs = AiConfig.AI_TIMEOUT_MS.get();
            String url = AiConfig.GROQ_URL.get();
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + AiConfig.GROQ_API_KEY.get());
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 429 && attempt < maxRetries) {
                // Rate limited — parse retry delay from response, default 15s
                String error = readStream(conn.getErrorStream());
                conn.disconnect();

                long waitMs = 15_000;
                try {
                    // Try to extract "Please try again in X.XXs" from Groq error
                    java.util.regex.Matcher retryMatcher = Pattern.compile(
                            "try again in ([\\d.]+)s").matcher(error);
                    if (retryMatcher.find()) {
                        waitMs = (long) (Double.parseDouble(retryMatcher.group(1)) * 1000) + 1000;
                    }
                } catch (Exception ignored) {}

                MCAi.LOGGER.info("Groq rate limited (429), retrying in {}ms (attempt {}/{})",
                        waitMs, attempt + 1, maxRetries);
                AiLogger.log(AiLogger.Category.AI_REQUEST, "WARN",
                        "Groq rate limited, waiting " + waitMs + "ms before retry " + (attempt + 1));

                try { Thread.sleep(waitMs); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for Groq rate limit retry");
                }
                continue;
            }

            if (responseCode == 400) {
                // Groq sometimes returns 400 tool_use_failed when the model generates
                // tool calls in XML format (<function=name({args})>) instead of structured format.
                // Parse the failed_generation and build a synthetic tool_calls response.
                String error = readStream(conn.getErrorStream());
                conn.disconnect();

                JsonObject syntheticResponse = tryParseGroqFailedGeneration(error);
                if (syntheticResponse != null) {
                    MCAi.LOGGER.info("Recovered Groq tool_use_failed via failed_generation parsing");
                    AiLogger.log(AiLogger.Category.AI_REQUEST, "WARN",
                            "Groq 400 tool_use_failed — recovered via failed_generation parsing");
                    return syntheticResponse;
                }
                throw new IOException("Groq returned HTTP 400: " + error);
            }

            if (responseCode != 200) {
                String error = readStream(conn.getErrorStream());
                conn.disconnect();
                throw new IOException("Groq returned HTTP " + responseCode + ": " + error);
            }

            String responseBody = readStream(conn.getInputStream());
            conn.disconnect();
            return JsonParser.parseString(responseBody).getAsJsonObject();
        }

        throw new IOException("Groq rate limit exceeded after " + maxRetries + " retries");
    }

    /**
     * Parse Groq's failed_generation field to recover tool calls.
     * Groq/Llama models sometimes generate: <function=tool_name({"key":"value"})></function>
     * instead of proper tool_calls. We extract the name+args and build a synthetic response.
     */
    private static JsonObject tryParseGroqFailedGeneration(String errorBody) {
        try {
            JsonObject errorJson = JsonParser.parseString(errorBody).getAsJsonObject();
            JsonObject errorObj = errorJson.getAsJsonObject("error");
            if (errorObj == null) return null;

            String code = errorObj.has("code") ? errorObj.get("code").getAsString() : "";
            if (!"tool_use_failed".equals(code)) return null;

            String failedGen = errorObj.has("failed_generation")
                    ? errorObj.get("failed_generation").getAsString() : "";
            if (failedGen.isEmpty()) return null;

            // Parse: <function=tool_name({"key":"value"})></function>
            Pattern p = Pattern.compile("<function=(\\w+)\\((.+?)\\)>?</function>", Pattern.DOTALL);
            Matcher m = p.matcher(failedGen);
            if (!m.find()) return null;

            String toolName = m.group(1);
            String argsStr = m.group(2).trim();

            // Validate it's a real tool
            if (ToolRegistry.get(toolName) == null) return null;

            // Parse args JSON
            JsonObject args;
            try {
                args = JsonParser.parseString(argsStr).getAsJsonObject();
            } catch (Exception e) {
                args = new JsonObject();
            }

            // Build synthetic OpenAI-format response with tool_calls
            JsonObject toolCall = new JsonObject();
            toolCall.addProperty("id", "recovered_" + System.currentTimeMillis());
            toolCall.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", toolName);
            function.addProperty("arguments", GSON.toJson(args));
            toolCall.add("function", function);

            JsonArray toolCalls = new JsonArray();
            toolCalls.add(toolCall);

            JsonObject message = new JsonObject();
            message.addProperty("role", "assistant");
            message.add("tool_calls", toolCalls);

            JsonObject choice = new JsonObject();
            choice.addProperty("index", 0);
            choice.add("message", message);
            choice.addProperty("finish_reason", "tool_calls");

            JsonArray choices = new JsonArray();
            choices.add(choice);

            JsonObject response = new JsonObject();
            response.add("choices", choices);

            MCAi.LOGGER.info("Recovered tool call from failed_generation: {}({})", toolName, argsStr);
            return response;
        } catch (Exception e) {
            MCAi.LOGGER.warn("Failed to parse Groq failed_generation: {}", e.getMessage());
            return null;
        }
    }

    private static String buildSystemPrompt(String playerContext, String companionName) {
        return """
                You are %s, a Minecraft AI companion next to the player. Helpful, concise, friendly. Under 3 sentences.
                
                RULES:
                - When asked to DO something, CALL the tool. Never explain syntax.
                - "how to make X" / "recipe for X" → get_recipe (info only)
                - "make X" / "craft X" / "I need X" → craft_item (action)
                - If craft_item says missing materials: follow the ACTION NEEDED hints exactly. Gather, then re-craft. NEVER say "you need to get materials".
                - Use plan param to chain steps. On [TASK_COMPLETE], execute next step.
                - craft_item resolves logs→planks→sticks. Use smelt_items for smelting.
                - ACT first, explain briefly after. Be autonomous.
                
                Current state:
                """.formatted(companionName) + playerContext;
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
