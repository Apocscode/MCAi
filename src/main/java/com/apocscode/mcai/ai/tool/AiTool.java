package com.apocscode.mcai.ai.tool;

import com.google.gson.JsonObject;

/**
 * Base interface for all tools the AI can invoke.
 * Each tool has a name, description, parameter schema, and an execute method.
 */
public interface AiTool {
    /** Tool name as exposed to the LLM (e.g., "web_search") */
    String name();

    /** Human-readable description for the LLM */
    String description();

    /**
     * JSON Schema for the tool's parameters.
     * Ollama uses this to know what arguments to pass.
     */
    JsonObject parameterSchema();

    /**
     * Execute the tool with the given arguments.
     * Runs on a background thread — never on the server tick thread.
     *
     * @param args The arguments from the LLM's tool call
     * @param context Server-side context (player, level, etc.)
     * @return Result string to feed back to the LLM
     */
    String execute(JsonObject args, ToolContext context);
}
