package com.apocscode.mcai.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Configuration for MCAi — controls AI connection, abilities, security,
 * ranges, and diagnostic logging. Generates config/mcai-common.toml.
 */
public class AiConfig {
    public static final ModConfigSpec SPEC;

    // ---- AI Connection ----
    public static final ModConfigSpec.ConfigValue<String> OLLAMA_URL;
    public static final ModConfigSpec.ConfigValue<String> OLLAMA_MODEL;
    public static final ModConfigSpec.IntValue AI_TIMEOUT_MS;
    public static final ModConfigSpec.DoubleValue AI_TEMPERATURE;
    public static final ModConfigSpec.IntValue AI_MAX_TOKENS;
    public static final ModConfigSpec.IntValue MAX_TOOL_ITERATIONS;

    // ---- Whisper Voice ----
    public static final ModConfigSpec.ConfigValue<String> WHISPER_URL;

    // ---- Companion ----
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_COMPANION_NAME;

    // ---- Security ----
    public static final ModConfigSpec.IntValue COMMAND_PERMISSION_LEVEL;
    public static final ModConfigSpec.ConfigValue<String> BLOCKED_COMMANDS;

    // ---- Ability Toggles ----
    public static final ModConfigSpec.BooleanValue ENABLE_COMMANDS;
    public static final ModConfigSpec.BooleanValue ENABLE_BLOCK_PLACEMENT;
    public static final ModConfigSpec.BooleanValue ENABLE_CONTAINER_INTERACTION;
    public static final ModConfigSpec.BooleanValue ENABLE_CRAFTING;
    public static final ModConfigSpec.BooleanValue ENABLE_WEB_ACCESS;

    // ---- Ranges ----
    public static final ModConfigSpec.IntValue CONTAINER_SCAN_RADIUS;
    public static final ModConfigSpec.IntValue INTERACTION_DISTANCE;
    public static final ModConfigSpec.IntValue SURROUNDINGS_SCAN_RADIUS;

    // ---- Logging ----
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ModConfigSpec.BooleanValue LOG_TOOL_CALLS;
    public static final ModConfigSpec.BooleanValue LOG_AI_REQUESTS;
    public static final ModConfigSpec.BooleanValue LOG_PERFORMANCE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // ============================================================
        // AI Connection
        // ============================================================
        builder.comment("AI Connection Settings").push("ai");

        builder.comment("Ollama / LLM backend settings").push("connection");

        OLLAMA_URL = builder
                .comment("Ollama API chat endpoint URL")
                .define("ollamaUrl", "http://localhost:11434/api/chat");

        OLLAMA_MODEL = builder
                .comment("LLM model name (e.g. llama3.1, mistral, codellama)")
                .define("model", "llama3.1");

        AI_TIMEOUT_MS = builder
                .comment("HTTP request timeout in milliseconds")
                .defineInRange("timeoutMs", 60000, 5000, 300000);

        AI_TEMPERATURE = builder
                .comment("Response creativity (0.0 = deterministic, 1.0 = creative, 2.0 = wild)")
                .defineInRange("temperature", 0.7, 0.0, 2.0);

        AI_MAX_TOKENS = builder
                .comment("Maximum tokens in AI response")
                .defineInRange("maxTokens", 500, 50, 4096);

        MAX_TOOL_ITERATIONS = builder
                .comment("Maximum tool-call iterations per message (prevents runaway loops)")
                .defineInRange("maxToolIterations", 5, 1, 20);

        builder.pop(); // connection

        builder.comment("Whisper voice input settings").push("whisper");

        WHISPER_URL = builder
                .comment("Whisper-compatible transcription API endpoint")
                .define("whisperUrl", "http://localhost:8178/v1/audio/transcriptions");

        builder.pop(); // whisper

        builder.comment("Companion entity settings").push("companion");

        DEFAULT_COMPANION_NAME = builder
                .comment("Default display name for newly spawned companions")
                .define("defaultName", "MCAi");

        builder.pop(); // companion
        builder.pop(); // ai

        // ============================================================
        // Security & Permissions
        // ============================================================
        builder.comment("Security & Permission Settings").push("security");

        COMMAND_PERMISSION_LEVEL = builder
                .comment("Permission level for AI command execution",
                        "0 = normal player, 1 = moderator, 2 = game master, 3 = admin, 4 = owner")
                .defineInRange("commandPermissionLevel", 2, 0, 4);

        BLOCKED_COMMANDS = builder
                .comment("Comma-separated list of commands the AI can NEVER execute",
                        "These are checked against the root command (first word)")
                .define("blockedCommands",
                        "stop,op,deop,ban,ban-ip,pardon,pardon-ip,whitelist," +
                        "save-all,save-off,save-on,kick,publish,debug,reload," +
                        "forceload,jfr,perf,transfer");

        builder.pop(); // security

        // ============================================================
        // Ability Toggles
        // ============================================================
        builder.comment("Ability Toggles — enable or disable specific AI capabilities").push("abilities");

        ENABLE_COMMANDS = builder
                .comment("Allow AI to execute game commands (/time, /weather, /give, etc.)")
                .define("enableCommands", true);

        ENABLE_BLOCK_PLACEMENT = builder
                .comment("Allow AI to place, break, or modify blocks in the world")
                .define("enableBlockPlacement", true);

        ENABLE_CONTAINER_INTERACTION = builder
                .comment("Allow AI to interact with containers (chests, barrels, etc.)")
                .define("enableContainerInteraction", true);

        ENABLE_CRAFTING = builder
                .comment("Allow AI to craft items using player inventory materials")
                .define("enableCrafting", true);

        ENABLE_WEB_ACCESS = builder
                .comment("Allow AI to search the web and fetch web pages")
                .define("enableWebAccess", true);

        builder.pop(); // abilities

        // ============================================================
        // Range Settings
        // ============================================================
        builder.comment("Range Settings — control how far the AI can reach").push("ranges");

        CONTAINER_SCAN_RADIUS = builder
                .comment("Maximum radius (blocks) for container scanning and smart fetch")
                .defineInRange("containerScanRadius", 32, 4, 128);

        INTERACTION_DISTANCE = builder
                .comment("Maximum distance (blocks) for block/container interaction")
                .defineInRange("interactionDistance", 32, 4, 128);

        SURROUNDINGS_SCAN_RADIUS = builder
                .comment("Maximum radius (blocks) for surroundings scanning")
                .defineInRange("surroundingsScanRadius", 16, 4, 64);

        builder.pop(); // ranges

        // ============================================================
        // Diagnostic Logging
        // ============================================================
        builder.comment("Diagnostic Logging — detailed logs written to logs/mcai_debug.log").push("logging");

        DEBUG_LOGGING = builder
                .comment("Master switch for debug logging to file")
                .define("debugLogging", true);

        LOG_TOOL_CALLS = builder
                .comment("Log every tool call with arguments, results, and timing")
                .define("logToolCalls", true);

        LOG_AI_REQUESTS = builder
                .comment("Log AI request/response details (model, message count, response time)")
                .define("logAiRequests", true);

        LOG_PERFORMANCE = builder
                .comment("Log performance timing data for tools and AI responses")
                .define("logPerformance", true);

        builder.pop(); // logging

        SPEC = builder.build();
    }

    // ---- Helper Methods ----

    /**
     * Check if a specific tool is enabled based on ability config toggles.
     * Returns true for tools that don't have a specific toggle.
     */
    public static boolean isToolEnabled(String toolName) {
        try {
            return switch (toolName) {
                case "execute_command" -> ENABLE_COMMANDS.get();
                case "set_block" -> ENABLE_BLOCK_PLACEMENT.get();
                case "interact_container", "find_and_fetch_item", "scan_containers" ->
                        ENABLE_CONTAINER_INTERACTION.get();
                case "craft_item" -> ENABLE_CRAFTING.get();
                case "web_search", "web_fetch" -> ENABLE_WEB_ACCESS.get();
                default -> true;
            };
        } catch (Exception e) {
            return true; // Config not loaded yet — default to enabled
        }
    }

    /**
     * Get the blocked commands as a Set (parsed from comma-separated config string).
     */
    public static java.util.Set<String> getBlockedCommands() {
        try {
            String raw = BLOCKED_COMMANDS.get();
            if (raw == null || raw.isBlank()) return java.util.Set.of();
            String[] parts = raw.split(",");
            java.util.Set<String> set = new java.util.HashSet<>();
            for (String part : parts) {
                String trimmed = part.trim().toLowerCase();
                if (!trimmed.isEmpty()) set.add(trimmed);
            }
            return set;
        } catch (Exception e) {
            // Fallback if config not loaded
            return java.util.Set.of("stop", "op", "deop", "ban", "ban-ip", "kick");
        }
    }
}
