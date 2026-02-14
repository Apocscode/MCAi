package com.apocscode.mcai.ai;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.tool.AiTool;
import com.apocscode.mcai.ai.tool.ToolContext;
import com.apocscode.mcai.ai.tool.ToolRegistry;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.network.ChatResponsePacket;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local command parser that handles common player requests WITHOUT needing any AI.
 *
 * Pattern-matches natural language requests like "mine iron", "craft a bucket",
 * "chop some trees" and directly invokes the appropriate tool. This provides:
 *   - Zero-latency responses (no network call)
 *   - Works offline (no internet needed)
 *   - Immune to rate limiting
 *   - More reliable than small local LLMs for common tasks
 *
 * Falls back to AI for anything it can't parse.
 *
 * Usage: Call {@link #tryParse(String, ServerPlayer, CompanionEntity)} before sending to AI.
 * Returns true if the command was handled locally, false to fall through to AI.
 */
public class CommandParser {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MCAi-CommandParser");
        t.setDaemon(true);
        return t;
    });

    // ==================== Ore name normalization ====================

    private static final Map<String, String> ORE_ALIASES = Map.ofEntries(
            Map.entry("iron", "iron"),
            Map.entry("coal", "coal"),
            Map.entry("copper", "copper"),
            Map.entry("gold", "gold"),
            Map.entry("diamond", "diamond"),
            Map.entry("diamonds", "diamond"),
            Map.entry("emerald", "emerald"),
            Map.entry("emeralds", "emerald"),
            Map.entry("lapis", "lapis"),
            Map.entry("redstone", "redstone"),
            Map.entry("quartz", "quartz"),
            Map.entry("nether quartz", "quartz"),
            Map.entry("ancient debris", "ancient_debris"),
            Map.entry("netherite", "ancient_debris")
    );

    // ==================== Patterns ====================

    // "mine iron", "mine some iron", "go mine iron ore", "mine 10 iron"
    private static final Pattern MINE_PATTERN = Pattern.compile(
            "(?:go\\s+)?(?:mine|dig|get|find|collect)\\s+(?:some\\s+|more\\s+)?(?:(\\d+)\\s+)?(.+?)(?:\\s+ore(?:s)?)?$",
            Pattern.CASE_INSENSITIVE);

    // "strip mine for iron", "strip mine iron", "go strip mining for diamonds"
    private static final Pattern STRIP_MINE_PATTERN = Pattern.compile(
            "(?:go\\s+)?strip\\s*min(?:e|ing)\\s+(?:for\\s+)?(?:some\\s+)?(?:(\\d+)\\s+)?(.+?)(?:\\s+ore(?:s)?)?$",
            Pattern.CASE_INSENSITIVE);

    // "craft a bucket", "craft iron pickaxe", "make me a furnace", "craft 4 torches"
    private static final Pattern CRAFT_PATTERN = Pattern.compile(
            "(?:craft|make|build|create)\\s+(?:me\\s+)?(?:an?\\s+)?(?:(\\d+)\\s+)?(.+?)$",
            Pattern.CASE_INSENSITIVE);

    // "chop trees", "chop some wood", "go chop trees", "get 20 logs"
    private static final Pattern CHOP_PATTERN = Pattern.compile(
            "(?:go\\s+)?(?:chop|cut|fell|harvest)\\s+(?:some\\s+|down\\s+)?(?:(\\d+)\\s+)?(?:trees?|logs?|wood)$",
            Pattern.CASE_INSENSITIVE);

    // "get wood" / "get logs" / "get some wood"
    private static final Pattern GET_WOOD_PATTERN = Pattern.compile(
            "(?:go\\s+)?get\\s+(?:some\\s+|more\\s+)?(?:(\\d+)\\s+)?(?:wood|logs?)$",
            Pattern.CASE_INSENSITIVE);

    // "smelt iron", "smelt 3 raw iron", "smelt raw_iron", "smelt the iron ore"
    private static final Pattern SMELT_PATTERN = Pattern.compile(
            "(?:smelt|cook|burn)\\s+(?:the\\s+)?(?:my\\s+)?(?:(\\d+)\\s+)?(.+?)$",
            Pattern.CASE_INSENSITIVE);

    // "check inventory", "what do you have", "show inventory"
    private static final Pattern INVENTORY_PATTERN = Pattern.compile(
            "(?:check|show|what(?:'s| is| do you have)?(?: in)?(?: your)?|list|whats in your)\\s*(?:inventory|items|stuff|bag)|" +
            "what(?:'s| is| do you have) (?:in your )?(?:inventory|bag)|" +
            "what do you have|" +
            "show me (?:your )?(?:inventory|items)|" +
            "inventory",
            Pattern.CASE_INSENSITIVE);

    // "come here", "come to me", "teleport to me"
    private static final Pattern COME_PATTERN = Pattern.compile(
            "(?:come\\s+(?:here|to me|back)|(?:teleport|tp)\\s+(?:to me|here)|get over here)",
            Pattern.CASE_INSENSITIVE);

    // "follow me", "follow"
    private static final Pattern FOLLOW_PATTERN = Pattern.compile(
            "follow(?:\\s+me)?$",
            Pattern.CASE_INSENSITIVE);

    // "stay", "stay here", "wait", "wait here", "stop"
    private static final Pattern STAY_PATTERN = Pattern.compile(
            "(?:stay|wait|stop)(?:\\s+here)?$",
            Pattern.CASE_INSENSITIVE);

    // "cancel", "cancel task", "stop task", "stop what you're doing", "nevermind"
    private static final Pattern CANCEL_PATTERN = Pattern.compile(
            "(?:cancel|abort|stop|nevermind|never mind)(?:\\s+(?:all\\s+)?(?:task|tasks|that|everything|what you.re doing))?$",
            Pattern.CASE_INSENSITIVE);

    // "go fishing", "fish", "catch some fish"
    private static final Pattern FISH_PATTERN = Pattern.compile(
            "(?:go\\s+)?(?:fish|fishing)|catch\\s+(?:some\\s+)?fish",
            Pattern.CASE_INSENSITIVE);

    // "farm", "farm area", "plant crops"
    private static final Pattern FARM_PATTERN = Pattern.compile(
            "(?:go\\s+)?(?:farm|plant|harvest)(?:\\s+(?:the\\s+)?(?:area|crops?|field))?$",
            Pattern.CASE_INSENSITIVE);

    // "what's your status", "task status", "what are you doing"
    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "(?:what(?:'s| is| are you)\\s+(?:your\\s+)?(?:status|doing|working on|up to))|" +
            "(?:task\\s+)?status|" +
            "how(?:'s| is) it going",
            Pattern.CASE_INSENSITIVE);

    // "scan area", "look around", "scan surroundings", "what's around"
    private static final Pattern SCAN_PATTERN = Pattern.compile(
            "(?:scan|look|check)\\s+(?:around|surroundings|area|the area)|" +
            "what(?:'s| is) around(?:\\s+(?:me|here|us))?",
            Pattern.CASE_INSENSITIVE);

    // "deposit items", "store items", "put items away", "empty inventory"
    private static final Pattern DEPOSIT_PATTERN = Pattern.compile(
            "(?:deposit|store|put away|stash|empty)\\s+(?:your\\s+|all\\s+)?(?:items?|inventory|stuff|everything)|" +
            "empty (?:your )?inventory",
            Pattern.CASE_INSENSITIVE);

    // "guard", "guard here", "protect this area"
    private static final Pattern GUARD_PATTERN = Pattern.compile(
            "(?:guard|protect|defend|watch)(?:\\s+(?:here|this area|this place|the area))?$",
            Pattern.CASE_INSENSITIVE);

    // ==================== Main entry point ====================

    /**
     * Try to parse a player message as a direct command.
     * If matched, executes the tool on a background thread and sends the result.
     *
     * @return true if the command was handled (caller should NOT send to AI),
     *         false if unrecognized (caller should fall through to AI).
     */
    public static boolean tryParse(String message, ServerPlayer player, @Nullable CompanionEntity companion) {
        if (message == null || message.isBlank()) return false;

        String msg = message.trim();

        // ---- Behavioral commands (no tool call needed, execute on server thread) ----
        if (companion != null) {
            if (FOLLOW_PATTERN.matcher(msg).matches()) {
                companion.setBehaviorMode(CompanionEntity.BehaviorMode.FOLLOW);
                respond(player, "Following you!");
                return true;
            }
            if (STAY_PATTERN.matcher(msg).matches()) {
                companion.setBehaviorMode(CompanionEntity.BehaviorMode.STAY);
                respond(player, "Staying here.");
                return true;
            }
            if (CANCEL_PATTERN.matcher(msg).matches()) {
                companion.getTaskManager().cancelAll();
                companion.getNavigation().stop();
                respond(player, "All tasks cancelled.");
                return true;
            }
            Matcher comeMatcher = COME_PATTERN.matcher(msg);
            if (comeMatcher.find()) {
                double dist = companion.distanceTo(player);
                if (dist > 64.0) {
                    companion.teleportTo(player.getX(), player.getY(), player.getZ());
                    companion.getNavigation().stop();
                    respond(player, "Teleporting to you!");
                } else if (dist > 4.0) {
                    companion.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), 1.4);
                    respond(player, "Coming to you!");
                } else {
                    respond(player, "I'm already here!");
                }
                return true;
            }
        }

        // ---- Tool-based commands (must run on background thread) ----

        // Strip mine (check BEFORE generic mine)
        Matcher stripMatcher = STRIP_MINE_PATTERN.matcher(msg);
        if (stripMatcher.matches()) {
            String countStr = stripMatcher.group(1);
            String oreStr = stripMatcher.group(2).trim();
            String ore = resolveOre(oreStr);
            if (ore == null) ore = oreStr.toLowerCase().replace(" ", "_");

            JsonObject args = new JsonObject();
            args.addProperty("ore", ore);
            if (countStr != null) args.addProperty("count", Integer.parseInt(countStr));

            executeToolAsync("strip_mine", args, player, companion,
                    "Strip mining for " + ore + "...");
            return true;
        }

        // Mine ores
        Matcher mineMatcher = MINE_PATTERN.matcher(msg);
        if (mineMatcher.matches()) {
            String countStr = mineMatcher.group(1);
            String oreStr = mineMatcher.group(2).trim();
            String ore = resolveOre(oreStr);

            if (ore != null) {
                JsonObject args = new JsonObject();
                args.addProperty("ore", ore);
                if (countStr != null) args.addProperty("maxOres", Integer.parseInt(countStr));

                executeToolAsync("mine_ores", args, player, companion,
                        "Mining " + ore + "...");
                return true;
            }
            // Not a recognized ore — fall through to AI
            return false;
        }

        // Craft
        Matcher craftMatcher = CRAFT_PATTERN.matcher(msg);
        if (craftMatcher.matches()) {
            String countStr = craftMatcher.group(1);
            String itemStr = craftMatcher.group(2).trim();
            String item = normalizeItemName(itemStr);

            JsonObject args = new JsonObject();
            args.addProperty("item", item);
            if (countStr != null) args.addProperty("count", Integer.parseInt(countStr));

            executeToolAsync("craft_item", args, player, companion,
                    "Crafting " + itemStr + "...");
            return true;
        }

        // Chop trees
        Matcher chopMatcher = CHOP_PATTERN.matcher(msg);
        if (chopMatcher.matches()) {
            String countStr = chopMatcher.group(1);
            JsonObject args = new JsonObject();
            if (countStr != null) args.addProperty("maxLogs", Integer.parseInt(countStr));

            executeToolAsync("chop_trees", args, player, companion,
                    "Chopping trees...");
            return true;
        }

        // Get wood (alias for chop trees)
        Matcher getWoodMatcher = GET_WOOD_PATTERN.matcher(msg);
        if (getWoodMatcher.matches()) {
            String countStr = getWoodMatcher.group(1);
            JsonObject args = new JsonObject();
            if (countStr != null) args.addProperty("maxLogs", Integer.parseInt(countStr));

            executeToolAsync("chop_trees", args, player, companion,
                    "Getting wood...");
            return true;
        }

        // Smelt
        Matcher smeltMatcher = SMELT_PATTERN.matcher(msg);
        if (smeltMatcher.matches()) {
            String countStr = smeltMatcher.group(1);
            String itemStr = smeltMatcher.group(2).trim();
            String item = normalizeItemName(itemStr);

            JsonObject args = new JsonObject();
            args.addProperty("item", item);
            if (countStr != null) args.addProperty("count", Integer.parseInt(countStr));

            executeToolAsync("smelt_items", args, player, companion,
                    "Smelting " + itemStr + "...");
            return true;
        }

        // Inventory check
        if (INVENTORY_PATTERN.matcher(msg).find()) {
            executeToolAsync("get_inventory", new JsonObject(), player, companion, null);
            return true;
        }

        // Scan surroundings
        if (SCAN_PATTERN.matcher(msg).find()) {
            executeToolAsync("scan_surroundings", new JsonObject(), player, companion,
                    "Looking around...");
            return true;
        }

        // Go fishing
        if (FISH_PATTERN.matcher(msg).find()) {
            executeToolAsync("go_fishing", new JsonObject(), player, companion,
                    "Going fishing...");
            return true;
        }

        // Farm
        if (FARM_PATTERN.matcher(msg).matches()) {
            executeToolAsync("farm_area", new JsonObject(), player, companion,
                    "Farming the area...");
            return true;
        }

        // Task status
        if (STATUS_PATTERN.matcher(msg).find()) {
            executeToolAsync("task_status", new JsonObject(), player, companion, null);
            return true;
        }

        // Deposit items
        if (DEPOSIT_PATTERN.matcher(msg).find()) {
            if (companion != null) {
                int deposited = com.apocscode.mcai.logistics.ItemRoutingHelper.routeAllCompanionItems(companion);
                if (deposited > 0) {
                    respond(player, "Deposited " + deposited + " item(s) to storage.");
                } else {
                    respond(player, "Nothing to deposit, or no tagged storage found.");
                }
            } else {
                respond(player, "No companion found.");
            }
            return true;
        }

        // Guard
        if (GUARD_PATTERN.matcher(msg).matches()) {
            JsonObject args = new JsonObject();
            args.addProperty("radius", 16);
            executeToolAsync("guard_area", args, player, companion,
                    "Guarding this area...");
            return true;
        }

        // No match — fall through to AI
        return false;
    }

    // ==================== Helpers ====================

    /**
     * Resolve an ore name from player input to the canonical name used by tools.
     */
    @Nullable
    private static String resolveOre(String input) {
        String lower = input.toLowerCase().trim();
        // Direct match
        if (ORE_ALIASES.containsKey(lower)) return ORE_ALIASES.get(lower);
        // Try stripping "ore" / "ores" suffix
        String stripped = lower.replaceAll("\\s*ores?$", "").trim();
        if (ORE_ALIASES.containsKey(stripped)) return ORE_ALIASES.get(stripped);
        return null;
    }

    /**
     * Normalize an item name from player input to minecraft resource path format.
     * "iron pickaxe" → "iron_pickaxe", "Stone Bricks" → "stone_bricks"
     */
    private static String normalizeItemName(String input) {
        return input.toLowerCase()
                .trim()
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-z0-9_]", "");
    }

    /**
     * Execute a tool asynchronously on the background thread.
     * Sends a "working on it" message immediately, then sends the tool result when done.
     */
    private static void executeToolAsync(String toolName, JsonObject args, ServerPlayer player,
                                          @Nullable CompanionEntity companion, @Nullable String workingMessage) {
        // Check tool is enabled
        if (!AiConfig.isToolEnabled(toolName)) {
            respond(player, "That action is disabled in the server config.");
            return;
        }

        AiTool tool = ToolRegistry.get(toolName);
        if (tool == null) {
            respond(player, "Unknown tool: " + toolName);
            return;
        }

        // Cancel existing tasks if this is a new action command
        if (companion != null && companion.getTaskManager().hasTasks()) {
            companion.getTaskManager().cancelAll();
        }

        // Send immediate feedback
        if (workingMessage != null && companion != null) {
            companion.getChat().say(com.apocscode.mcai.entity.CompanionChat.Category.TASK, workingMessage);
        }

        // Add to conversation history so AI has context of what happened
        ConversationManager.addPlayerMessage(player.getName().getString() + ": " +
                args.toString());
        ConversationManager.addSystemMessage("[Command parsed locally → " + toolName + "(" + args + ")]");

        MCAi.LOGGER.info("CommandParser: executing {} with args {} (bypassing AI)", toolName, args);
        AiLogger.toolCall(toolName, args.toString());

        // Run on background thread (tools use runOnServer() internally which would deadlock on server thread)
        executor.submit(() -> {
            try {
                ToolContext ctx = new ToolContext(player, player.getServer());
                long startMs = System.currentTimeMillis();
                String result = tool.execute(args, ctx);
                long elapsed = System.currentTimeMillis() - startMs;

                AiLogger.toolResult(toolName, result, elapsed);
                MCAi.LOGGER.info("CommandParser: {} completed in {}ms: {}",
                        toolName, elapsed, result != null ? result.substring(0, Math.min(200, result.length())) : "null");

                // Add result to conversation history
                ConversationManager.addSystemMessage("[Tool result: " + (result != null ? result : "done") + "]");

                // Send result to player (skip [ASYNC_TASK] markers)
                if (result != null && !result.contains("[ASYNC_TASK]")) {
                    String cleanResult = result.trim();
                    if (!cleanResult.isEmpty()) {
                        player.getServer().execute(() ->
                                PacketDistributor.sendToPlayer(player, new ChatResponsePacket(cleanResult)));
                    }
                } else if (result != null) {
                    // Async task was queued — strip the marker and send any remaining message
                    String cleanResult = result.replaceAll("\\[ASYNC_TASK]", "").trim();
                    if (!cleanResult.isEmpty()) {
                        player.getServer().execute(() ->
                                PacketDistributor.sendToPlayer(player, new ChatResponsePacket(cleanResult)));
                    }
                }
            } catch (Exception e) {
                MCAi.LOGGER.error("CommandParser: {} failed: {}", toolName, e.getMessage(), e);
                player.getServer().execute(() ->
                        PacketDistributor.sendToPlayer(player, new ChatResponsePacket(
                                "Failed: " + e.getMessage())));
            }
        });
    }

    /**
     * Send a response to the player via ChatResponsePacket.
     */
    private static void respond(ServerPlayer player, String message) {
        player.getServer().execute(() ->
                PacketDistributor.sendToPlayer(player, new ChatResponsePacket(message)));
    }
}
