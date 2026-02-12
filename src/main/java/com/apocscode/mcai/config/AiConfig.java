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

    // ---- Groq Cloud API ----
    public static final ModConfigSpec.ConfigValue<String> GROQ_API_KEY;
    public static final ModConfigSpec.ConfigValue<String> GROQ_MODEL;
    public static final ModConfigSpec.ConfigValue<String> GROQ_URL;

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

    // ---- Companion Behavior ----
    public static final ModConfigSpec.DoubleValue FOLLOW_TELEPORT_DISTANCE;
    public static final ModConfigSpec.IntValue AUTO_EQUIP_INTERVAL;
    public static final ModConfigSpec.DoubleValue LEASH_DISTANCE;

    // ---- Logistics ----
    public static final ModConfigSpec.IntValue MAX_TAGGED_BLOCKS;
    public static final ModConfigSpec.IntValue LOGISTICS_RANGE;

    // ---- Display ----
    public static final ModConfigSpec.BooleanValue SHOW_COMPANION_HUD;
    public static final ModConfigSpec.BooleanValue SHOW_WAND_HUD;
    public static final ModConfigSpec.BooleanValue SHOW_BLOCK_LABELS;
    public static final ModConfigSpec.BooleanValue SHOW_HEALTH_BAR;

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
                .defineInRange("maxToolIterations", 10, 1, 20);

        builder.pop(); // connection

        builder.comment("Groq cloud API settings — set an API key to use Groq instead of Ollama",
                "Get a free key at https://console.groq.com",
                "When groqApiKey is set (not empty), Groq is used as the AI backend",
                "Llama 4 Scout has best free-tier limits: 30K TPM, 500K TPD").push("groq");

        GROQ_API_KEY = builder
                .comment("Groq API key (leave empty to use local Ollama instead)")
                .define("groqApiKey", "");

        GROQ_MODEL = builder
                .comment("Groq model name (recommended: meta-llama/llama-4-scout-17b-16e-instruct for best free-tier limits)")
                .define("groqModel", "meta-llama/llama-4-scout-17b-16e-instruct");

        GROQ_URL = builder
                .comment("Groq API endpoint URL")
                .define("groqUrl", "https://api.groq.com/openai/v1/chat/completions");

        builder.pop(); // groq

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
        // Companion Behavior
        // ============================================================
        builder.comment("Companion Behavior — pathfinding, teleport, and automation settings").push("companion_behavior");

        FOLLOW_TELEPORT_DISTANCE = builder
                .comment("Distance (blocks) at which the companion teleports to the owner in follow mode")
                .defineInRange("followTeleportDistance", 32.0, 8.0, 128.0);

        AUTO_EQUIP_INTERVAL = builder
                .comment("Ticks between auto-equip checks (lower = more responsive, higher = less CPU)")
                .defineInRange("autoEquipInterval", 100, 20, 400);

        LEASH_DISTANCE = builder
                .comment("Distance (blocks) for emergency leash teleport (when companion is very far)")
                .defineInRange("leashDistance", 48.0, 16.0, 256.0);

        builder.pop(); // companion_behavior

        // ============================================================
        // Logistics
        // ============================================================
        builder.comment("Logistics — wand and tagged block settings").push("logistics");

        MAX_TAGGED_BLOCKS = builder
                .comment("Maximum number of blocks that can be tagged per companion")
                .defineInRange("maxTaggedBlocks", 32, 4, 128);

        LOGISTICS_RANGE = builder
                .comment("Maximum distance (blocks) for logistics wand tagging")
                .defineInRange("logisticsRange", 32, 4, 128);

        builder.pop(); // logistics

        // ============================================================
        // Display
        // ============================================================
        builder.comment("Display — HUD and overlay toggle settings").push("display");

        SHOW_COMPANION_HUD = builder
                .comment("Show the companion status HUD overlay (top-left)")
                .define("showCompanionHud", true);

        SHOW_WAND_HUD = builder
                .comment("Show the wand mode HUD overlay when holding the logistics wand")
                .define("showWandHud", true);

        SHOW_BLOCK_LABELS = builder
                .comment("Show floating role labels above tagged blocks")
                .define("showBlockLabels", true);

        SHOW_HEALTH_BAR = builder
                .comment("Show an in-world health bar above the companion when damaged")
                .define("showHealthBar", true);

        builder.pop(); // display

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
     * Check if Groq cloud API is configured (API key is set and non-empty).
     * When true, AIService should use Groq instead of local Ollama.
     */
    public static boolean isGroqEnabled() {
        try {
            String key = GROQ_API_KEY.get();
            return key != null && !key.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

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
