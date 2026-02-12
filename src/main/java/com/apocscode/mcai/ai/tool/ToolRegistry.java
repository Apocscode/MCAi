package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * Registry of all available AI tools.
 * Builds the tools array for Ollama's tool-calling API.
 * Uses dynamic tool selection to keep ≤15 tools per request for small models.
 */
public class ToolRegistry {
    private static final Map<String, AiTool> tools = new LinkedHashMap<>();

    /** Max tools to send per request — keeps small models (8B) reliable */
    private static final int MAX_TOOLS_PER_REQUEST = 16;

    /** Core tools always included regardless of message content */
    private static final Set<String> CORE_TOOLS = Set.of(
            "get_inventory", "craft_item", "execute_command", "scan_surroundings",
            "get_recipe", "find_and_fetch_item", "task_status", "transfer_items",
            "rename_companion"
    );

    /**
     * Keyword → tool mappings for dynamic selection.
     * If any keyword appears in the user message, the mapped tools are included.
     */
    private static final Map<String, List<String>> KEYWORD_TOOLS = new LinkedHashMap<>();

    static {
        // Crafting / making (covers both "how to make X" and "make me X")
        // Include gathering tools so AI can autonomously get missing materials
        kw("craft,make,build,create,axe,pickaxe,sword,shovel,hoe,armor,shield,tool,how to,how do,recipe,need,what do i need",
                "craft_item", "get_recipe", "get_inventory", "gather_blocks", "chop_trees", "mine_ores", "smelt_items");
        // Mining / gathering
        kw("mine,dig,ore,iron,gold,diamond,coal,copper,gather,collect,cobble,sand,gravel,stone",
                "mine_ores", "mine_area", "gather_blocks", "dig_down");
        // Digging down specifically
        kw("dig down,shaft,tunnel,dig straight,vertical,below",
                "dig_down", "mine_area");
        // Trees / wood
        kw("chop,tree,log,wood,plank,oak,birch,spruce",
                "chop_trees", "gather_blocks");
        // Smelting
        kw("smelt,furnace,cook,ingot,raw_iron,raw_gold,glass",
                "smelt_items");
        // Farming
        kw("farm,plant,harvest,wheat,carrot,potato,crop,seed",
                "farm_area");
        // Containers / items / storage
        kw("chest,barrel,container,storage,find,fetch,get me,bring,take,put,give",
                "find_and_fetch_item", "scan_containers", "interact_container");
        // Looking at things
        kw("this,that,look,what is,block,point",
                "get_looked_at_block");
        // Locations / bookmarks
        kw("remember,bookmark,location,where,save this,mark,home,base",
                "bookmark_location");
        // Web search
        kw("search,wiki,guide,mod,tutorial",
                "web_search", "web_fetch");
        // Commands
        kw("day,night,weather,time,teleport,tp,give,gamemode,creative,survival,command,clear",
                "execute_command");
        // Building
        kw("build,wall,floor,shelter,platform,column,structure",
                "build_structure");
        // Fishing
        kw("fish,fishing,rod,catch,cod,salmon",
                "go_fishing");
        // Guard / patrol
        kw("guard,patrol,defend,protect,watch",
                "guard_area");
        // Deliver
        kw("deliver,bring to,take to,drop off,deposit",
                "deliver_items");
        // Trade
        kw("trade,villager,merchant,buy,sell",
                "villager_trade");
        // Memory
        kw("remember,forget,recall,memory,you know,do you know",
                "companion_memory");
        // Emotes
        kw("wave,celebrate,happy,sad,angry,love,emote,dance,sneeze,think",
                "emote");
        // Blocks / set
        kw("place,break,set block,command block",
                "set_block");
        // Mods
        kw("mod,mods,modpack,installed",
                "list_installed_mods");
        // Name
        kw("name,call you,rename",
                "rename_companion");
    }

    private static void kw(String keywords, String... toolNames) {
        for (String keyword : keywords.split(",")) {
            KEYWORD_TOOLS.computeIfAbsent(keyword.trim().toLowerCase(),
                    k -> new ArrayList<>());
            for (String tool : toolNames) {
                List<String> list = KEYWORD_TOOLS.get(keyword.trim().toLowerCase());
                if (!list.contains(tool)) list.add(tool);
            }
        }
    }

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
        register(new SmeltItemsTool());
        register(new TaskStatusTool());

        // New feature tools
        register(new DeliverItemsTool());
        register(new GuardAreaTool());
        register(new BuildStructureTool());
        register(new VillagerTradeTool());
        register(new FishingTool());
        register(new MemoryTool());
        register(new EmoteTool());
        register(new DigDownTool());
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

    /**
     * Build a filtered "tools" JSON array based on the user's message.
     * Selects core tools + keyword-matched tools, capped at MAX_TOOLS_PER_REQUEST.
     * This keeps small models (8B) reliable by not overwhelming them with 30+ schemas.
     */
    public static JsonArray toOllamaToolsArray(String userMessage) {
        Set<String> selected = selectToolsForMessage(userMessage);
        JsonArray arr = new JsonArray();
        for (String toolName : selected) {
            AiTool tool = tools.get(toolName);
            if (tool == null) continue;
            if (!AiConfig.isToolEnabled(toolName)) continue;

            JsonObject func = new JsonObject();
            func.addProperty("name", tool.name());
            func.addProperty("description", tool.description());
            func.add("parameters", tool.parameterSchema());

            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", func);
            arr.add(wrapper);
        }
        MCAi.LOGGER.debug("Selected {} tools for message: {}", arr.size(), selected);
        return arr;
    }

    /**
     * Select which tools are relevant for the given user message.
     * Always includes CORE_TOOLS, then adds keyword-matched tools up to MAX_TOOLS_PER_REQUEST.
     */
    static Set<String> selectToolsForMessage(String userMessage) {
        Set<String> selected = new LinkedHashSet<>(CORE_TOOLS);
        String msg = userMessage.toLowerCase();

        // Add tools matched by keywords in the message
        for (Map.Entry<String, List<String>> entry : KEYWORD_TOOLS.entrySet()) {
            if (msg.contains(entry.getKey())) {
                selected.addAll(entry.getValue());
            }
        }

        // If very few matches beyond core, add high-value general tools
        if (selected.size() < 12) {
            selected.add("scan_surroundings");
            selected.add("web_search");
            selected.add("bookmark_location");
            selected.add("get_looked_at_block");
        }

        // Cap to MAX_TOOLS_PER_REQUEST
        if (selected.size() > MAX_TOOLS_PER_REQUEST) {
            Set<String> capped = new LinkedHashSet<>();
            int count = 0;
            for (String t : selected) {
                if (count >= MAX_TOOLS_PER_REQUEST) break;
                capped.add(t);
                count++;
            }
            return capped;
        }
        return selected;
    }

    public static Map<String, AiTool> getAll() {
        return tools;
    }
}
