package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of all available AI tools.
 * Builds the tools array for Ollama's tool-calling API.
 */
public class ToolRegistry {
    private static final Map<String, AiTool> tools = new LinkedHashMap<>();

    public static void init() {
        register(new WebSearchTool());
        register(new WebFetchTool());
        register(new GetInventoryTool());
        register(new ScanSurroundingsTool());
        register(new GetRecipeTool());
        register(new GetLookedAtBlockTool());
        register(new ScanContainersTool());
        register(new InteractContainerTool());
        register(new BookmarkLocationTool());
        register(new ExecuteCommandTool());
        register(new FindAndFetchItemTool());
        register(new SetBlockTool());
        register(new CraftItemTool());
        register(new RenameCompanionTool());
        register(new ListInstalledModsTool());

        // Automation task tools
        register(new FarmAreaTool());
        register(new ChopTreesTool());
        register(new MineOresTool());
        register(new MineAreaTool());
        register(new GatherBlocksTool());
        register(new TransferItemsTool());
        register(new TaskStatusTool());
        MCAi.LOGGER.info("Registered {} AI tools: {}", tools.size(), tools.keySet());
    }

    public static void register(AiTool tool) {
        tools.put(tool.name(), tool);
    }

    public static AiTool get(String name) {
        return tools.get(name);
    }

    /**
     * Build the "tools" JSON array for Ollama's /api/chat request.
     * Only includes tools that are currently enabled in config.
     * Format: [{type: "function", function: {name, description, parameters}}]
     */
    public static JsonArray toOllamaToolsArray() {
        JsonArray arr = new JsonArray();
        for (AiTool tool : tools.values()) {
            // Skip disabled tools so the AI doesn't even try to call them
            if (!AiConfig.isToolEnabled(tool.name())) continue;

            JsonObject func = new JsonObject();
            func.addProperty("name", tool.name());
            func.addProperty("description", tool.description());
            func.add("parameters", tool.parameterSchema());

            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", func);
            arr.add(wrapper);
        }
        return arr;
    }

    public static Map<String, AiTool> getAll() {
        return tools;
    }
}
