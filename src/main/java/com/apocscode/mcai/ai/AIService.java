package com.apocscode.mcai.ai;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.tool.AiTool;
import com.apocscode.mcai.ai.tool.ToolContext;
import com.apocscode.mcai.ai.tool.ToolRegistry;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.entity.CompanionChat;
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
            String primary = AiConfig.isCloudEnabled() ? "Cloud (" + AiConfig.CLOUD_MODEL.get() + ")"
                    : "Ollama (" + AiConfig.OLLAMA_MODEL.get() + ")";
            String fallback = AiConfig.isCloudFallbackEnabled()
                    ? " → Fallback: " + AiConfig.CLOUD_FALLBACK_MODEL.get()
                    : "";
            MCAi.LOGGER.info("AI Service initialized (backend: {}{}, tools: {})",
                    primary, fallback, ToolRegistry.getAll().size());
            AiLogger.log(AiLogger.Category.SYSTEM, "INFO",
                    "AIService initialized — backend=" + primary + fallback +
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
                        (AiConfig.isCloudEnabled() ? "Check your cloud API key in config." : "Make sure Ollama is running on localhost:11434.") +
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
        boolean useCloud = AiConfig.isCloudEnabled();
        JsonArray messages = new JsonArray();

        // System prompt
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", buildSystemPrompt(playerContext, companionName));
        messages.add(systemMsg);

        // Conversation history — fewer messages for cloud to stay within free-tier TPM limits
        int historyLimit = useCloud ? 8 : 20;
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
            JsonObject response;
            try {
                response = useCloud ? callCloudAI(messages, userMessage) : callOllama(messages, userMessage);
            } catch (IOException e) {
                if (useCloud && e.getMessage() != null && e.getMessage().contains("429")) {
                    // Primary cloud rate limited — try fallback cloud provider first
                    if (AiConfig.isCloudFallbackEnabled()) {
                        MCAi.LOGGER.info("Primary cloud rate limited, trying fallback cloud provider");
                        AiLogger.log(AiLogger.Category.AI_REQUEST, "WARN",
                                "Primary cloud rate limited — trying fallback provider");
                        try {
                            response = callCloudAI(messages, userMessage,
                                    AiConfig.CLOUD_FALLBACK_URL.get(),
                                    AiConfig.CLOUD_FALLBACK_API_KEY.get(),
                                    AiConfig.CLOUD_FALLBACK_MODEL.get());
                        } catch (IOException fallbackEx) {
                            // Fallback cloud also failed — try local Ollama
                            MCAi.LOGGER.warn("Fallback cloud also failed: {}", fallbackEx.getMessage());
                            try {
                                response = callOllama(messages, userMessage);
                            } catch (IOException ollamaEx) {
                                MCAi.LOGGER.warn("Ollama also failed: {}", ollamaEx.getMessage());
                                return "All AI backends are down — primary cloud hit rate limit, " +
                                        "fallback cloud failed, and Ollama isn't running. Try again shortly.";
                            }
                        }
                    } else {
                        // No fallback cloud — go straight to Ollama
                        MCAi.LOGGER.info("Cloud AI rate limited, falling back to Ollama");
                        AiLogger.log(AiLogger.Category.AI_REQUEST, "WARN",
                                "Cloud AI rate limited — falling back to Ollama");
                        try {
                            response = callOllama(messages, userMessage);
                        } catch (IOException ollamaEx) {
                            MCAi.LOGGER.warn("Ollama fallback also failed: {}", ollamaEx.getMessage());
                            return "My cloud AI hit its rate limit and Ollama isn't running. " +
                                    "Try again in about 30 seconds.";
                        }
                    }
                } else {
                    throw e;
                }
            }

            // Extract assistant message — detect format dynamically:
            // Cloud/OpenAI: response.choices[0].message
            // Ollama:      response.message
            JsonObject assistantMessage;
            if (response.has("choices")) {
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

                    // Normalize the assistant message for cross-backend compatibility:
                    // Ollama returns arguments as JsonObject, Cloud/OpenAI requires it as a String.
                    // Also ensure each tool_call has an "id" field (Ollama omits it).
                    JsonObject normalizedAssistant = normalizeToolCallMessage(assistantMessage);
                    messages.add(normalizedAssistant);

                    // Use the normalized tool calls for iteration — they have guaranteed ids
                    // and string arguments for cross-backend compatibility
                    JsonArray normalizedToolCalls = normalizedAssistant.getAsJsonArray("tool_calls");

                    // Execute each tool call and add results
                    boolean asyncTaskQueued = false;
                    for (JsonElement tcElement : normalizedToolCalls) {
                        JsonObject toolCall = tcElement.getAsJsonObject();
                        JsonObject function = toolCall.getAsJsonObject("function");
                        String toolName = function.get("name").getAsString();

                        // Always present after normalization
                        String toolCallId = toolCall.get("id").getAsString();

                        // Parse arguments (always a string after normalization)
                        JsonObject toolArgs = new JsonObject();
                        if (function.has("arguments")) {
                            try {
                                toolArgs = JsonParser.parseString(function.get("arguments").getAsString()).getAsJsonObject();
                            } catch (Exception e) {
                                MCAi.LOGGER.warn("Failed to parse normalized tool args for {}: {}",
                                        toolName, function.get("arguments").getAsString());
                            }
                        }

                        // Log parsed args for debugging (especially Ollama fallback issues)
                        MCAi.LOGGER.info("Tool '{}' args: {}", toolName, toolArgs);

                        // If args are empty but the model returned content text, try parsing args from text
                        if (toolArgs.size() == 0 && assistantMessage.has("content")
                                && !assistantMessage.get("content").isJsonNull()) {
                            String contentText = assistantMessage.get("content").getAsString();
                            if (contentText != null && !contentText.isBlank()) {
                                JsonObject textArgs = tryParseTextToolArgs(contentText);
                                if (textArgs.size() > 0) {
                                    MCAi.LOGGER.info("Recovered args from content text for '{}': {}", toolName, textArgs);
                                    toolArgs = textArgs;
                                }
                            }
                        }

                        // Execute the tool
                        long toolStartMs = System.currentTimeMillis();
                        String result = executeTool(toolName, toolArgs, toolCtx);
                        long toolElapsed = System.currentTimeMillis() - toolStartMs;

                        // Detect async task marker
                        if (result.contains("[ASYNC_TASK]")) {
                            asyncTaskQueued = true;
                        }

                        // Add tool result — Cloud requires tool_call_id, Ollama just uses "tool" role
                        JsonObject toolResultMsg = new JsonObject();
                        toolResultMsg.addProperty("role", "tool");
                        toolResultMsg.addProperty("content", result);
                        toolResultMsg.addProperty("tool_call_id", toolCallId);
                        toolResultMsg.addProperty("name", toolName);
                        messages.add(toolResultMsg);

                        MCAi.LOGGER.info("Tool '{}' executed in {}ms, result length: {} chars",
                                toolName, toolElapsed, result.length());
                    }

                    // If an async task was queued, inject a stop instruction and do ONE final
                    // iteration to get the AI's text response to the player, then break.
                    if (asyncTaskQueued) {
                        MCAi.LOGGER.info("Async task detected — forcing AI to respond to player");
                        JsonObject stopMsg = new JsonObject();
                        stopMsg.addProperty("role", "system");
                        stopMsg.addProperty("content",
                                "An async task is now running. STOP calling tools. " +
                                "Give the player a brief, friendly status update about what you're doing. " +
                                "Do NOT call any more tools.");
                        messages.add(stopMsg);

                        // One more LLM call to get the final text
                        try {
                            JsonObject finalResp = useCloud ? callCloudAI(messages, userMessage) : callOllama(messages, userMessage);
                            JsonObject finalMsg;
                            if (finalResp.has("choices")) {
                                finalMsg = finalResp.getAsJsonArray("choices")
                                        .get(0).getAsJsonObject()
                                        .getAsJsonObject("message");
                            } else {
                                finalMsg = finalResp.getAsJsonObject("message");
                            }
                            if (finalMsg.has("content") && !finalMsg.get("content").isJsonNull()) {
                                String text = finalMsg.get("content").getAsString().trim();
                                if (!text.isEmpty()) return text;
                            }
                        } catch (IOException e) {
                            MCAi.LOGGER.warn("Failed to get async stop response: {}", e.getMessage());
                        }
                        return "I've queued that task — working on it now!";
                    }

                    // Continue loop — LLM will process tool results and either
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

                // Add the original assistant message — inject a synthetic tool_calls
                // structure so Groq sees a valid assistant+tool_call → tool response pair
                String syntheticId = "fallback_" + System.nanoTime();
                JsonObject syntheticFunc = new JsonObject();
                syntheticFunc.addProperty("name", parsedToolName);
                syntheticFunc.addProperty("arguments", parsedArgs.toString());
                JsonObject syntheticToolCall = new JsonObject();
                syntheticToolCall.addProperty("id", syntheticId);
                syntheticToolCall.addProperty("type", "function");
                syntheticToolCall.add("function", syntheticFunc);
                JsonArray syntheticToolCalls = new JsonArray();
                syntheticToolCalls.add(syntheticToolCall);
                // Build a proper assistant message with tool_calls
                JsonObject assistantWithToolCall = new JsonObject();
                assistantWithToolCall.addProperty("role", "assistant");
                assistantWithToolCall.addProperty("content", "");
                assistantWithToolCall.add("tool_calls", syntheticToolCalls);
                messages.add(assistantWithToolCall);

                // Execute the tool
                long toolStartMs = System.currentTimeMillis();
                String result = executeTool(parsedToolName, parsedArgs, toolCtx);
                long toolElapsed = System.currentTimeMillis() - toolStartMs;

                // Add tool result with proper tool_call_id
                JsonObject toolResultMsg = new JsonObject();
                toolResultMsg.addProperty("role", "tool");
                toolResultMsg.addProperty("content", result);
                toolResultMsg.addProperty("tool_call_id", syntheticId);
                toolResultMsg.addProperty("name", parsedToolName);
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

    /** Pattern: tool_name( — finds the start of a text-based tool call */
    private static final Pattern TEXT_TOOL_PATTERN = Pattern.compile(
            "\\b([a-z_]+)\\s*\\(");

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
     * Extract a balanced JSON object from text starting at the given position.
     * Counts { and } while respecting quoted strings to handle nested objects.
     */
    private static String extractBalancedJson(String text, int start) {
        if (start >= text.length() || text.charAt(start) != '{') return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return text.substring(start, i + 1);
                }
            }
        }
        // Unbalanced — try adding a closing brace
        return text.substring(start) + "}";
    }

    /**
     * Try to extract tool arguments JSON from text that looks like a tool call.
     * Uses balanced brace counting so multiple tool calls in one text blob
     * (common with Ollama) are correctly separated.
     * Returns empty JsonObject if parsing fails.
     */
    private static JsonObject tryParseTextToolArgs(String text) {
        if (text == null || text.isEmpty()) return new JsonObject();
        Matcher m = TEXT_TOOL_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (ToolRegistry.get(name) != null) {
                // Find the opening { after the (
                int braceStart = text.indexOf('{', m.end());
                if (braceStart < 0) continue;
                // Extract balanced JSON using brace counting
                String argsStr = extractBalancedJson(text, braceStart);
                if (argsStr == null) continue;
                // Clean up common formatting issues from small models
                argsStr = argsStr.replace("'", "\"");
                try {
                    return JsonParser.parseString(argsStr).getAsJsonObject();
                } catch (Exception e) {
                    // Try stripping the plan value (may contain commas/quotes that break JSON)
                    try {
                        String stripped = argsStr.replaceAll(",?\\s*\"plan\"\\s*:\\s*\"[^\"]*\"", "");
                        if (!stripped.endsWith("}")) stripped += "}";
                        return JsonParser.parseString(stripped).getAsJsonObject();
                    } catch (Exception e2) {
                        MCAi.LOGGER.warn("Fallback: found tool '{}' but couldn't parse args: {}", name, argsStr);
                        return new JsonObject();
                    }
                }
            }
        }
        return new JsonObject();
    }

    /**
     * Normalize an assistant message with tool_calls for cross-backend compatibility.
     * - Ollama returns function.arguments as a JsonObject; Groq requires a JSON string.
     * - Ollama omits tool_call "id" fields; Groq requires them.
     * This ensures the conversation history works when switching backends between iterations.
     */
    private static JsonObject normalizeToolCallMessage(JsonObject assistantMessage) {
        if (!assistantMessage.has("tool_calls")) return assistantMessage;

        JsonObject normalized = new JsonObject();
        normalized.addProperty("role", "assistant");
        // Groq requires content to be present (can be empty string or null)
        if (assistantMessage.has("content") && !assistantMessage.get("content").isJsonNull()) {
            normalized.addProperty("content", assistantMessage.get("content").getAsString());
        } else {
            normalized.addProperty("content", "");
        }

        JsonArray normalizedCalls = new JsonArray();
        for (JsonElement tcEl : assistantMessage.getAsJsonArray("tool_calls")) {
            JsonObject tc = tcEl.getAsJsonObject();
            JsonObject normalizedTc = new JsonObject();

            // Ensure id exists
            if (tc.has("id")) {
                normalizedTc.addProperty("id", tc.get("id").getAsString());
            } else {
                normalizedTc.addProperty("id", "ollama_" + System.nanoTime());
            }
            normalizedTc.addProperty("type", "function");

            // Normalize function.arguments to always be a string
            JsonObject func = tc.getAsJsonObject("function");
            JsonObject normalizedFunc = new JsonObject();
            normalizedFunc.addProperty("name", func.get("name").getAsString());
            if (func.has("arguments")) {
                JsonElement argsEl = func.get("arguments");
                if (argsEl.isJsonObject()) {
                    normalizedFunc.addProperty("arguments", argsEl.toString());
                } else {
                    normalizedFunc.addProperty("arguments", argsEl.getAsString());
                }
            } else {
                normalizedFunc.addProperty("arguments", "{}");
            }
            normalizedTc.add("function", normalizedFunc);
            normalizedCalls.add(normalizedTc);
        }
        normalized.add("tool_calls", normalizedCalls);
        return normalized;
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

            // Companion equipped items
            ItemStack compMainHand = companion.getMainHandItem();
            ItemStack compOffHand = companion.getOffhandItem();
            if (!compMainHand.isEmpty()) {
                ctx.append("Companion equipped: ").append(compMainHand.getDisplayName().getString()).append("\n");
            }
            List<String> armorPieces = new ArrayList<>();
            for (ItemStack armor : companion.getArmorSlots()) {
                if (!armor.isEmpty()) armorPieces.add(armor.getDisplayName().getString());
            }
            if (!armorPieces.isEmpty()) {
                ctx.append("Companion armor: ").append(String.join(", ", armorPieces)).append("\n");
            }

            // Companion inventory summary
            var compInv = companion.getCompanionInventory();
            List<String> compItems = new ArrayList<>();
            for (int i = 0; i < compInv.getContainerSize(); i++) {
                ItemStack stack = compInv.getItem(i);
                if (!stack.isEmpty()) {
                    compItems.add(stack.getDisplayName().getString() + " x" + stack.getCount());
                }
            }
            if (!compItems.isEmpty()) {
                ctx.append("Companion inventory (" ).append(compItems.size()).append(" stacks): ");
                ctx.append(String.join(", ", compItems.subList(0, Math.min(compItems.size(), 15))));
                if (compItems.size() > 15) ctx.append("... and ").append(compItems.size() - 15).append(" more");
                ctx.append("\n");
            }

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

        // Ollama expects tool_calls.arguments as JSON objects (not strings) and doesn't use
        // tool_call_id. When messages come from the agent loop after cloud AI calls, arguments
        // are normalized to strings (OpenAI format). Sanitize them for Ollama compatibility.
        request.add("messages", sanitizeMessagesForOllama(messages));
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
     * Sanitize the messages array for Ollama compatibility.
     * Cloud/OpenAI format uses tool_calls.arguments as a JSON STRING and includes tool_call_id.
     * Ollama expects arguments as a JSON OBJECT and doesn't use tool_call_id.
     * Sending stringified arguments to Ollama causes HTTP 400: "Value looks like object,
     * but can't find closing '}' symbol".
     */
    private static JsonArray sanitizeMessagesForOllama(JsonArray messages) {
        JsonArray sanitized = new JsonArray();
        for (JsonElement el : messages) {
            JsonObject msg = el.getAsJsonObject();

            // Convert assistant messages with tool_calls
            if (msg.has("tool_calls") && !msg.get("tool_calls").isJsonNull()) {
                JsonObject cleanMsg = new JsonObject();
                cleanMsg.addProperty("role", "assistant");
                if (msg.has("content") && !msg.get("content").isJsonNull()) {
                    cleanMsg.addProperty("content", msg.get("content").getAsString());
                } else {
                    cleanMsg.addProperty("content", "");
                }

                JsonArray cleanCalls = new JsonArray();
                for (JsonElement tcEl : msg.getAsJsonArray("tool_calls")) {
                    JsonObject tc = tcEl.getAsJsonObject();
                    JsonObject cleanTc = new JsonObject();
                    cleanTc.addProperty("type", "function");

                    JsonObject func = tc.getAsJsonObject("function");
                    JsonObject cleanFunc = new JsonObject();
                    cleanFunc.addProperty("name", func.get("name").getAsString());

                    // Convert arguments from string back to JSON object
                    if (func.has("arguments")) {
                        JsonElement argsEl = func.get("arguments");
                        if (argsEl.isJsonPrimitive() && argsEl.getAsJsonPrimitive().isString()) {
                            try {
                                cleanFunc.add("arguments",
                                        JsonParser.parseString(argsEl.getAsString()).getAsJsonObject());
                            } catch (Exception e) {
                                cleanFunc.add("arguments", new JsonObject());
                            }
                        } else {
                            cleanFunc.add("arguments", argsEl);
                        }
                    } else {
                        cleanFunc.add("arguments", new JsonObject());
                    }
                    cleanTc.add("function", cleanFunc);
                    cleanCalls.add(cleanTc);
                }
                cleanMsg.add("tool_calls", cleanCalls);
                sanitized.add(cleanMsg);
            }
            // Convert tool result messages — strip tool_call_id (Ollama doesn't use it)
            else if (msg.has("role") && "tool".equals(msg.get("role").getAsString())) {
                JsonObject cleanMsg = new JsonObject();
                cleanMsg.addProperty("role", "tool");
                cleanMsg.addProperty("content",
                        msg.has("content") ? msg.get("content").getAsString() : "");
                sanitized.add(cleanMsg);
            }
            // Pass other messages through unchanged
            else {
                sanitized.add(msg);
            }
        }
        return sanitized;
    }

    /**
     * Call cloud AI API (OpenAI-compatible) with tool support.
     * Uses the primary cloud provider from config.
     */
    private static JsonObject callCloudAI(JsonArray messages, String userMessage) throws IOException {
        return callCloudAI(messages, userMessage,
                AiConfig.CLOUD_URL.get(),
                AiConfig.CLOUD_API_KEY.get(),
                AiConfig.CLOUD_MODEL.get());
    }

    /**
     * Call cloud AI API (OpenAI-compatible) with tool support.
     * Works with any OpenAI-compatible provider: Groq, OpenRouter, Together, Cerebras, etc.
     * Uses dynamic tool selection to stay within free-tier token limits.
     * Retries automatically on 429 rate-limit errors with backoff.
     * Returns the full response JSON object (OpenAI format with choices[]).
     */
    private static JsonObject callCloudAI(JsonArray messages, String userMessage,
                                          String url, String apiKey, String model) throws IOException {
        // Build request — OpenAI chat completions format
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.add("messages", messages);
        request.addProperty("temperature", AiConfig.AI_TEMPERATURE.get());
        request.addProperty("max_tokens", AiConfig.AI_MAX_TOKENS.get());
        request.addProperty("stream", false);

        // Use dynamic tool selection — keeps token usage low for free-tier limits
        JsonArray tools = ToolRegistry.toOllamaToolsArray(userMessage);
        if (tools.size() > 0) {
            request.add("tools", tools);
            request.addProperty("tool_choice", "auto");
        }

        AiLogger.aiRequest(messages.size(), tools.size(), model);

        String requestBody = GSON.toJson(request);
        MCAi.LOGGER.debug("Cloud AI request: {} chars, model: {}", requestBody.length(), model);

        // Retry loop for rate limits (429)
        int maxRetries = 3;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            int timeoutMs = AiConfig.AI_TIMEOUT_MS.get();
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            // OpenRouter requires HTTP-Referer and X-Title for free model access
            if (url.contains("openrouter.ai")) {
                conn.setRequestProperty("HTTP-Referer", "https://github.com/Apocscode/MCAi");
                conn.setRequestProperty("X-Title", "MCAi Minecraft Companion");
            }
            conn.setDoOutput(true);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 429) {
                // Rate limited — parse error and check if it's a daily limit
                String error = readStream(conn.getErrorStream());
                conn.disconnect();

                // Detect daily TPD (tokens per day) limit — retrying is pointless
                boolean isDailyLimit = error.contains("tokens per day") || error.contains("per day")
                        || error.contains("daily") || error.contains("rate_limit_exceeded");
                // OpenRouter free tier rate limits are effectively per-IP/per-model and don't clear quickly
                boolean isOpenRouterRateLimit = url.contains("openrouter.ai");
                if (isDailyLimit || isOpenRouterRateLimit) {
                    MCAi.LOGGER.warn("Cloud AI rate limit (fast-fail) for {}: {}",
                            model, error.length() > 200 ? error.substring(0, 200) : error);
                    AiLogger.log(AiLogger.Category.AI_REQUEST, "WARN",
                            "Cloud AI rate limit hit — skipping retries, falling back immediately");
                    throw new IOException("429 rate limit for " + model + " (fast-fail)");
                }

                if (attempt < maxRetries) {
                    // Per-minute/per-request rate limit — worth retrying
                    long waitMs = 15_000;
                    try {
                        // Parse "try again in Xm Y.Zs" or "try again in Y.Zs"
                        java.util.regex.Matcher retryMatcher = Pattern.compile(
                                "try again in (?:(\\d+)m)?([\\d.]+)s").matcher(error);
                        if (retryMatcher.find()) {
                            long minutes = retryMatcher.group(1) != null
                                    ? Long.parseLong(retryMatcher.group(1)) : 0;
                            double seconds = Double.parseDouble(retryMatcher.group(2));
                            waitMs = (minutes * 60_000) + (long) (seconds * 1000) + 1000;
                            // Cap at 30s — don't wait minutes for rate limits
                            waitMs = Math.min(waitMs, 30_000);
                        }
                    } catch (Exception ignored) {}

                    MCAi.LOGGER.info("Cloud AI rate limited (429), retrying in {}ms (attempt {}/{})",
                            waitMs, attempt + 1, maxRetries);
                    AiLogger.log(AiLogger.Category.AI_REQUEST, "WARN",
                            "Cloud AI rate limited, waiting " + waitMs + "ms before retry " + (attempt + 1));

                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for rate limit retry");
                    }
                    continue;
                }
                // All retries exhausted
                throw new IOException("429 rate limit exceeded after " + maxRetries + " retries for " + model);
            }

            if (responseCode == 400) {
                // Some providers return 400 tool_use_failed when the model generates
                // tool calls in XML format instead of structured format.
                // Parse the failed_generation and build a synthetic tool_calls response.
                String error = readStream(conn.getErrorStream());
                conn.disconnect();

                JsonObject syntheticResponse = tryParseGroqFailedGeneration(error);
                if (syntheticResponse != null) {
                    MCAi.LOGGER.info("Recovered tool_use_failed via failed_generation parsing");
                    AiLogger.log(AiLogger.Category.AI_REQUEST, "WARN",
                            "Cloud 400 tool_use_failed — recovered via failed_generation parsing");
                    return syntheticResponse;
                }
                throw new IOException("Cloud AI returned HTTP 400: " + error);
            }

            if (responseCode != 200) {
                String error = readStream(conn.getErrorStream());
                conn.disconnect();
                throw new IOException("Cloud AI returned HTTP " + responseCode + ": " + error);
            }

            String responseBody = readStream(conn.getInputStream());
            conn.disconnect();
            return JsonParser.parseString(responseBody).getAsJsonObject();
        }

        throw new IOException("429 Cloud AI rate limit exceeded after " + maxRetries + " retries");
    }

    /**
     * Parse Groq's failed_generation field to recover tool calls.
     * Handles two formats:
     * 1. Llama 70b XML: <function=tool_name({"key":"value"})></function>
     * 2. Llama 4 Scout JSON array: [{"name":"tool","parameters":{"key":"value"}}]
     * Also coerces string numbers to actual numbers (Scout sends "1" instead of 1).
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

            JsonArray toolCalls = new JsonArray();
            String trimmed = failedGen.trim();

            // Format 2: Scout JSON array [{"name":"tool","parameters":{...}}]
            if (trimmed.startsWith("[")) {
                JsonArray arr = JsonParser.parseString(trimmed).getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject entry = arr.get(i).getAsJsonObject();
                    String toolName = entry.get("name").getAsString();
                    if (ToolRegistry.get(toolName) == null) continue;

                    JsonObject args = entry.has("parameters")
                            ? coerceStringNumbers(entry.getAsJsonObject("parameters"))
                            : new JsonObject();

                    toolCalls.add(buildToolCallJson(toolName, args));
                    MCAi.LOGGER.info("Recovered tool call from Scout JSON: {}({})", toolName, GSON.toJson(args));
                }
            }

            // Format 1: 70b XML <function=tool_name({"key":"value"})></function>
            if (toolCalls.isEmpty()) {
                Pattern p = Pattern.compile("<function=(\\w+)\\((.+?)\\)>?</function>", Pattern.DOTALL);
                Matcher m = p.matcher(failedGen);
                if (m.find()) {
                    String toolName = m.group(1);
                    String argsStr = m.group(2).trim();
                    if (ToolRegistry.get(toolName) != null) {
                        JsonObject args;
                        try {
                            args = coerceStringNumbers(JsonParser.parseString(argsStr).getAsJsonObject());
                        } catch (Exception e) {
                            args = new JsonObject();
                        }
                        toolCalls.add(buildToolCallJson(toolName, args));
                        MCAi.LOGGER.info("Recovered tool call from XML: {}({})", toolName, argsStr);
                    }
                }
            }

            if (toolCalls.isEmpty()) return null;

            // Build synthetic OpenAI-format response
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
            return response;
        } catch (Exception e) {
            MCAi.LOGGER.warn("Failed to parse Groq failed_generation: {}", e.getMessage());
            return null;
        }
    }

    /** Build a single tool_call JSON object in OpenAI format. */
    private static JsonObject buildToolCallJson(String toolName, JsonObject args) {
        JsonObject toolCall = new JsonObject();
        toolCall.addProperty("id", "recovered_" + System.currentTimeMillis());
        toolCall.addProperty("type", "function");
        JsonObject function = new JsonObject();
        function.addProperty("name", toolName);
        function.addProperty("arguments", GSON.toJson(args));
        toolCall.add("function", function);
        return toolCall;
    }

    /** Coerce string values that look like numbers to actual numbers (Scout bug workaround). */
    private static JsonObject coerceStringNumbers(JsonObject obj) {
        JsonObject fixed = new JsonObject();
        for (var entry : obj.entrySet()) {
            JsonElement val = entry.getValue();
            if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()) {
                String s = val.getAsString();
                try {
                    long num = Long.parseLong(s);
                    fixed.addProperty(entry.getKey(), num);
                    continue;
                } catch (NumberFormatException ignored) {}
                try {
                    double num = Double.parseDouble(s);
                    fixed.addProperty(entry.getKey(), num);
                    continue;
                } catch (NumberFormatException ignored) {}
                // Check for boolean strings
                if ("true".equalsIgnoreCase(s)) { fixed.addProperty(entry.getKey(), true); continue; }
                if ("false".equalsIgnoreCase(s)) { fixed.addProperty(entry.getKey(), false); continue; }
            }
            fixed.add(entry.getKey(), val);
        }
        return fixed;
    }

    private static String buildSystemPrompt(String playerContext, String companionName) {
        return """
                You are %s, a Minecraft AI companion. Helpful, concise, friendly. Under 3 sentences.
                
                RULES:
                - You have your OWN inventory (27 slots) separate from the player's inventory. Items you gather, craft, or pick up go into YOUR inventory. You also have equipment slots (mainhand, offhand, armor) — check "Companion equipped" in the context below to see what you currently have equipped BEFORE crafting tools or armor.
                - When asked to DO something, CALL the tool immediately. Never explain syntax.
                - "how to make X" / "recipe for X" → get_recipe (info only)
                - "make X" / "craft X" / "I need X" → craft_item (action)
                - craft_item is FULLY AUTONOMOUS: it checks chests, pulls materials, auto-crafts intermediates (logs→planks→sticks), AND if raw materials are missing, it auto-queues gathering tasks (chop trees, mine ores, smelt) with a step-by-step continuation plan. Just call craft_item ONCE and it handles everything.
                - ASYNC TASKS: When a tool returns [ASYNC_TASK], STOP calling tools immediately. Tell the player what you're doing. The plan will auto-continue when each task finishes.
                - Do NOT call craft_item again in the same turn after it returns [ASYNC_TASK].
                - On [TASK_COMPLETE], follow the 'Next steps' instructions EXACTLY. Call each tool as described with the parameters shown.
                - On [TASK_FAILED], ADAPT and try an alternative approach. If mine_ores failed, use strip_mine(ore=X, plan="<remaining steps>") to dig at the optimal Y-level. ALWAYS copy the 'Remaining steps' into the 'plan' parameter so the crafting chain continues automatically.
                - NEVER tell the player "you need materials" — craft_item handles gathering automatically.
                - For direct smelting requests, use smelt_items. It auto-handles furnace placement + fuel.
                - ACT first, explain briefly after. Be fully autonomous — complete the entire task.
                
                TOOL ROUTING — read the user's intent and pick the RIGHT tool:
                  "dig down N" / "shaft" / "go underground" → dig_down(depth=N) — vertical shaft only
                  "mine area" / "clear area" / "flatten" → mine_area — rectangular volume
                  "mine iron" / "get ore" / "mine diamonds" → mine_ores(ore="iron") — scan nearby for specific ore
                  "strip mine" / "tunnel for ore" / "find diamonds underground" → strip_mine(ore="diamond") — dig tunnel at optimal Y
                  "create a mine" / "set up a mine" / "build a mine" / "permanent mine" → create_mine(ore="diamond") — full mine with shaft, hub room, branch tunnels
                  "chop trees" / "get wood" / "get logs" → chop_trees
                  "get me X from chest" / "bring X" / "fetch X" → find_and_fetch_item
                  "gather sand/dirt/cobble" → gather_blocks(block="sand")
                  "smelt X" / "cook X" → smelt_items
                  "kill X" / "attack X" / "hunt X" → kill_mob(mob="X") — works with vanilla AND modded mobs
                  "what's nearby" / "scan" / "look around" → scan_surroundings
                  NEVER use mine_area for "dig down" — mine_area is horizontal. dig_down is vertical.
                
                MINING STRATEGY (critical — follow this decision tree):
                  1. Player says "mine iron/diamond/etc" → FIRST call mine_ores(ore="iron") to scan nearby.
                  2. If mine_ores says "no ores found" or warns about wrong Y-level:
                     → Use strip_mine(ore="iron") to tunnel at the optimal Y. It auto-descends to the best depth.
                  3. Player says "create a mine" / "set up a mine" / "build a real mine" / "long-term mine":
                     → Use create_mine(ore="diamond") — builds shaft + hub room + branch mine tunnels.
                     → create_mine is for BIG operations. It takes several minutes. Use strip_mine for quick tunnels.
                  4. mine_ores and strip_mine will AUTO-CRAFT the required pickaxe if the companion doesn't have one.
                     You do NOT need to manually craft a pickaxe first — the tools handle the full chain automatically.
                  5. For "find diamonds" or similar → strip_mine(ore="diamond") is usually best since
                     diamonds are rare and deep (Y=-59). Simple scanning won't find enough.
                  6. ALWAYS specify the ore= parameter when the player asks for a specific ore type.
                
                KILL MOB — kill_mob() supports ALL mobs including modded ones:
                  - Vanilla: kill_mob(mob="zombie"), kill_mob(mob="cow", count=3)
                  - Modded: kill_mob(mob="raccoon") — auto-finds mod entity (e.g. livingthings:raccoon)
                  - Use full ID if ambiguous: kill_mob(mob="livingthings:raccoon")
                  - For crafting that needs mob drops (leather→armor, string→bow), craft_item auto-handles it.
                
                CRAFTING AUTONOMY — craft_item handles the FULL chain from NOTHING:
                  - Checks inventory/chests → auto-crafts intermediates → auto-gathers missing materials
                  - Auto-places AND picks up crafting tables, furnaces, etc. as needed
                  - Auto-crafts required tools (pickaxe for mining, etc.) before gathering
                  - Auto-hunts mobs for drops (leather, string, bones, etc.)
                  - Auto-smelts ores at a furnace (auto-crafts + auto-places furnace from cobblestone)
                  - Auto-gathers fuel (chops trees) and cobblestone if needed for smelting
                  - Example: "craft iron pickaxe" from nothing → chop trees → craft planks/sticks → craft wood pick → mine stone → craft stone pick → mine iron ore → auto-craft furnace → smelt iron → craft iron pickaxe
                  - Just call craft_item ONCE — it queues all gathering/smelting/crafting steps with continuations
                
                ORE Y-LEVELS (Minecraft 1.21 Overworld):
                  Coal:    Y=0 to 320,  best Y=96.  Any pickaxe.
                  Copper:  Y=-16 to 112, best Y=48.  Any pickaxe.
                  Iron:    Y=-64 to 320, best Y=16.  Stone pickaxe+.
                  Lapis:   Y=-64 to 64,  best Y=0.   Stone pickaxe+.
                  Gold:    Y=-64 to 32,  best Y=-16.  Iron pickaxe+.
                  Redstone:Y=-64 to 16,  best Y=-59.  Iron pickaxe+.
                  Diamond: Y=-64 to 16,  best Y=-59.  Iron pickaxe+.
                  Emerald: Y=-16 to 320, best Y=232.  Iron pickaxe+. Mountains only.
                  When companion is too high/low for the target ore, use strip_mine to auto-descend.
                
                MINECRAFT GAME KNOWLEDGE:
                
                Tool Tiers (weakest → strongest):
                  Wood(0) < Stone(1) < Iron(2) < Diamond(3) < Netherite(4)
                  Higher tier = faster + can mine harder blocks. Each tier unlocks new ores.
                
                Tool Types & Uses:
                  Pickaxe — stone, ores, metal blocks, obsidian, nether blocks. REQUIRED for ore drops.
                  Axe — logs, wood, pumpkins, melons. Faster than hand. Also a weapon.
                  Shovel — dirt, sand, gravel, clay, snow, soul sand. Needed for path blocks.
                  Hoe — farmland (right-click dirt/grass). Breaks leaves, hay, sponge faster.
                  Shears — wool (from sheep alive), leaves, cobwebs, vines, flowers. No shears = no wool drop.
                  Sword — best melee weapon. Kills mobs. Breaks cobwebs fast.
                  Bucket — carries water/lava/milk. Essential for obsidian, farms.
                  Flint & Steel — lights nether portal, fires.
                  Fishing Rod — catches fish, treasure, junk.
                
                Mining Level Requirements (minimum pickaxe tier needed):
                  Wood pick(0): cobblestone, coal ore, nether quartz, nether gold ore
                  Stone pick(1): iron ore, copper ore, lapis ore
                  Iron pick(2): gold ore, diamond ore, emerald ore, redstone ore, obsidian
                  Diamond pick(3): ancient debris (netherite), crying obsidian
                  Wrong tier = block breaks but drops NOTHING.
                
                Smelting & Fuel:
                  Furnace smelts raw ores → ingots (raw_iron→iron_ingot, raw_gold→gold_ingot).
                  Fuel: coal(8 items), charcoal(8), planks(1.5), logs(1.5), blaze_rod(12), lava_bucket(100).
                  Blast furnace = 2x faster for ores/metal. Smoker = 2x faster for food.
                  Smelting is fully autonomous: auto-crafts furnace from 8 cobblestone, auto-gathers fuel (chops trees), auto-places/retrieves furnace.
                  craft_item handles smelting steps directly in the crafting chain — no need to manually call smelt_items.
                
                Crafting Progression (early game):
                  1. Punch tree → logs → planks → crafting table + sticks
                  2. Wooden pickaxe (3 planks + 2 sticks) → mine cobblestone
                  3. Stone pickaxe (3 cobblestone + 2 sticks) → mine iron ore
                  4. Furnace (8 cobblestone) + smelt raw_iron → iron_ingot
                  5. Iron pickaxe (3 iron_ingot + 2 sticks) → mine diamond/gold/redstone
                
                Block → Tool Mapping:
                  Stone/Cobble/Brick/Ore → Pickaxe
                  Dirt/Sand/Gravel/Clay/Snow → Shovel
                  Log/Planks/Wood → Axe
                  Crops/Leaves/Hay → Hoe (or hand)
                  Wool/Vines/Cobweb → Shears (or sword for cobweb)
                  Breaking without correct tool = very slow + often no drop.
                
                Mob Drops (use kill_mob tool to hunt):
                  Zombie→rotten_flesh. Skeleton→bone+arrow. Creeper→gunpowder.
                  Spider→string+spider_eye. Enderman→ender_pearl. Blaze→blaze_rod.
                  Cow→leather+beef. Sheep→wool(shears)+mutton. Chicken→feather+chicken+egg.
                  Pig→porkchop. Iron Golem→iron_ingot. Witch→potions+redstone+glowstone.
                  Modded mobs: use kill_mob with the mob name — auto-resolves mod entity IDs.
                
                Dimensions:
                  Overworld — normal. Nether — fire/lava, needs obsidian portal (4x5 frame + flint&steel). End — endermen+dragon, needs ender_eyes in stronghold portal.
                
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
     * If the AI call fails (e.g., all backends rate-limited during game pause),
     * retries up to 3 times with 30-second delays scheduled on the server thread.
     * This ensures continuations survive game pauses and transient rate limits.
     *
     * @param continuation The continuation plan attached to the completed task
     * @param taskResult   Description of the task outcome (e.g. "Gathered 8 Cobblestone")
     * @param player       The server player to send the response to
     * @param companionName The companion's display name
     */
    public static void continueAfterTask(TaskContinuation continuation, String taskResult,
                                          ServerPlayer player, String companionName) {
        continueAfterTask(continuation, taskResult, player, companionName, 0);
    }

    private static void continueAfterTask(TaskContinuation continuation, String taskResult,
                                           ServerPlayer player, String companionName, int attempt) {
        if (executor == null || executor.isShutdown()) return;

        boolean isFailed = taskResult.startsWith("FAILED:");
        String syntheticMessage;
        if (isFailed) {
            String failPart = taskResult.substring("FAILED: ".length());
            int dashIdx = failPart.indexOf(" — ");
            String taskDesc = dashIdx >= 0 ? failPart.substring(0, dashIdx) : failPart;
            String failReason = dashIdx >= 0 ? failPart.substring(dashIdx + 3) : "unknown";
            syntheticMessage = continuation.buildFailureContinuationMessage(taskDesc, failReason);
        } else {
            syntheticMessage = continuation.buildContinuationMessage(taskResult);
        }
        MCAi.LOGGER.info("Task continuation for {} (attempt {}): {}",
                player.getName().getString(), attempt + 1, syntheticMessage);

        // Add system note only on first attempt to avoid duplicates
        if (attempt == 0) {
            String statusLabel = isFailed ? "Task failed" : "Task completed";
            ConversationManager.addSystemMessage("[" + statusLabel + ": " + taskResult + "]");
        }

        chat(syntheticMessage, player, ConversationManager.getHistoryForAI(), companionName)
                .thenAccept(response -> {
                    // Check if response indicates all backends failed — retry if so
                    if (response != null && attempt < 3 &&
                            (response.contains("All AI backends are down") ||
                             response.contains("rate limit") ||
                             response.contains("Ollama isn't running"))) {
                        MCAi.LOGGER.warn("Continuation got rate-limit response (attempt {}), scheduling retry in 30s",
                                attempt + 1);
                        // Schedule retry on server thread after 600 ticks (30 seconds)
                        // This uses server ticks, so it PAUSES when game pauses — perfect!
                        scheduleServerRetry(continuation, taskResult, player, companionName, attempt + 1);
                        return;
                    }
                    player.getServer().execute(() -> {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                                player, new ChatResponsePacket(response));
                    });
                })
                .exceptionally(ex -> {
                    MCAi.LOGGER.error("Task continuation failed (attempt {})", attempt + 1, ex);
                    if (attempt < 3) {
                        MCAi.LOGGER.info("Scheduling continuation retry in 30s (attempt {})", attempt + 2);
                        scheduleServerRetry(continuation, taskResult, player, companionName, attempt + 1);
                    } else {
                        player.getServer().execute(() -> {
                            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                                    player, new ChatResponsePacket(
                                            "I had trouble continuing the plan after multiple retries: " + ex.getMessage()));
                        });
                    }
                    return null;
                });
    }

    /**
     * Schedule a continuation retry using server ticks instead of Thread.sleep.
     * Server ticks pause when the game pauses, so the retry won't fire while paused
     * and waste rate-limit budget. Waits 600 ticks (30 seconds of game time).
     */
    private static void scheduleServerRetry(TaskContinuation continuation, String taskResult,
                                             ServerPlayer player, String companionName, int nextAttempt) {
        player.getServer().execute(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(player.getUUID());
            if (companion != null) {
                companion.getChat().say(CompanionChat.Category.TASK,
                        "AI backends busy, retrying in 30 seconds...");
                // Store pending retry on the task manager — it ticks with the server
                companion.getTaskManager().setPendingRetry(
                        continuation, taskResult, companionName, nextAttempt, 600);
            } else {
                MCAi.LOGGER.warn("Cannot schedule continuation retry — companion not found");
            }
        });
    }

    /**
     * Execute a tool deterministically (bypassing the AI agent loop).
     * Used by TaskManager for continuation chains where the next tool call is already known.
     * Runs on the AI background executor to avoid blocking the server tick thread.
     *
     * The tool result is handled automatically:
     * - If the result contains [ASYNC_TASK], a new task was queued (it will have its own continuation)
     * - If the tool returns a normal result, it's sent to the player as a chat message
     * - If the tool fails, the error is sent to the player
     */
    public static void executeToolDeterministic(String toolName, JsonObject args,
                                                 ServerPlayer player, String companionName) {
        if (executor == null || executor.isShutdown()) {
            MCAi.LOGGER.warn("Cannot execute deterministic tool — executor not running");
            return;
        }

        executor.submit(() -> {
            try {
                ToolContext toolCtx = new ToolContext(player, player.getServer());
                MCAi.LOGGER.info("Deterministic tool execution: {} with args {}", toolName, args);

                String result = executeTool(toolName, args, toolCtx);

                MCAi.LOGGER.info("Deterministic tool result: {}", result);

                // If an async task was started, the task system will handle the rest
                // (the task will fire its own continuation when it completes)
                if (result != null && result.contains("[ASYNC_TASK]")) {
                    MCAi.LOGGER.info("Deterministic continuation queued async task — task system will handle next steps");
                    player.getServer().execute(() -> {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                                player, new ChatResponsePacket(result.replaceAll("\\[ASYNC_TASK]", "").trim()));
                    });
                    return;
                }

                // For synchronous tools (like craft_item), send result and check
                // if the result itself indicates further continuation is needed
                player.getServer().execute(() -> {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            player, new ChatResponsePacket(result != null ? result : "Done."));
                });

            } catch (Exception e) {
                MCAi.LOGGER.error("Deterministic tool execution failed: {}", e.getMessage(), e);
                player.getServer().execute(() -> {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                            player, new ChatResponsePacket("Tool execution failed: " + e.getMessage()));
                });
            }
        });
    }
}
