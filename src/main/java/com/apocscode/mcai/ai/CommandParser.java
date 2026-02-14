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
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local command parser that handles player requests WITHOUT needing any AI.
 *
 * Features:
 *   - Extensive natural language pattern matching (100+ phrasings)
 *   - Fuzzy matching for typos and misspellings (Levenshtein distance)
 *   - Case insensitive throughout
 *   - Jim personality responses for unknown/failed requests
 *   - Works completely offline — no internet, no LLM
 *   - Falls back to AI only for truly complex/conversational requests
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

    private static final Map<String, String> ORE_ALIASES = new HashMap<>();
    static {
        // Primary names
        ORE_ALIASES.put("iron", "iron");
        ORE_ALIASES.put("coal", "coal");
        ORE_ALIASES.put("copper", "copper");
        ORE_ALIASES.put("gold", "gold");
        ORE_ALIASES.put("diamond", "diamond");
        ORE_ALIASES.put("emerald", "emerald");
        ORE_ALIASES.put("lapis", "lapis");
        ORE_ALIASES.put("redstone", "redstone");
        ORE_ALIASES.put("quartz", "quartz");
        ORE_ALIASES.put("ancient debris", "ancient_debris");
        // Plurals
        ORE_ALIASES.put("diamonds", "diamond");
        ORE_ALIASES.put("emeralds", "emerald");
        ORE_ALIASES.put("coals", "coal");
        ORE_ALIASES.put("coppers", "copper");
        ORE_ALIASES.put("irons", "iron");
        ORE_ALIASES.put("golds", "gold");
        // Alternate names
        ORE_ALIASES.put("nether quartz", "quartz");
        ORE_ALIASES.put("netherite", "ancient_debris");
        ORE_ALIASES.put("nether gold", "gold");
        ORE_ALIASES.put("lapis lazuli", "lapis");
        ORE_ALIASES.put("deepslate iron", "iron");
        ORE_ALIASES.put("deepslate coal", "coal");
        ORE_ALIASES.put("deepslate copper", "copper");
        ORE_ALIASES.put("deepslate gold", "gold");
        ORE_ALIASES.put("deepslate diamond", "diamond");
        ORE_ALIASES.put("deepslate emerald", "emerald");
        ORE_ALIASES.put("deepslate lapis", "lapis");
        ORE_ALIASES.put("deepslate redstone", "redstone");
        // Common misspellings
        ORE_ALIASES.put("dimond", "diamond");
        ORE_ALIASES.put("dimonds", "diamond");
        ORE_ALIASES.put("diamnod", "diamond");
        ORE_ALIASES.put("diamnd", "diamond");
        ORE_ALIASES.put("iorn", "iron");
        ORE_ALIASES.put("irn", "iron");
        ORE_ALIASES.put("iren", "iron");
        ORE_ALIASES.put("goald", "gold");
        ORE_ALIASES.put("gld", "gold");
        ORE_ALIASES.put("emrald", "emerald");
        ORE_ALIASES.put("emrld", "emerald");
        ORE_ALIASES.put("laips", "lapis");
        ORE_ALIASES.put("lapiz", "lapis");
        ORE_ALIASES.put("restone", "redstone");
        ORE_ALIASES.put("redston", "redstone");
        ORE_ALIASES.put("coppe", "copper");
        ORE_ALIASES.put("copr", "copper");
        ORE_ALIASES.put("coper", "copper");
        ORE_ALIASES.put("quartx", "quartz");
        ORE_ALIASES.put("quatrz", "quartz");
    }

    // ==================== Common item aliases ====================

    private static final Map<String, String> ITEM_ALIASES = new HashMap<>();
    static {
        // Pickaxes
        ITEM_ALIASES.put("pickaxe", "wooden_pickaxe");
        ITEM_ALIASES.put("pick", "wooden_pickaxe");
        ITEM_ALIASES.put("pikaxe", "wooden_pickaxe");
        ITEM_ALIASES.put("picaxe", "wooden_pickaxe");
        ITEM_ALIASES.put("wood pickaxe", "wooden_pickaxe");
        ITEM_ALIASES.put("wood pick", "wooden_pickaxe");
        ITEM_ALIASES.put("wooden pick", "wooden_pickaxe");
        ITEM_ALIASES.put("stone pickaxe", "stone_pickaxe");
        ITEM_ALIASES.put("stone pick", "stone_pickaxe");
        ITEM_ALIASES.put("iron pickaxe", "iron_pickaxe");
        ITEM_ALIASES.put("iron pick", "iron_pickaxe");
        ITEM_ALIASES.put("diamond pickaxe", "diamond_pickaxe");
        ITEM_ALIASES.put("diamond pick", "diamond_pickaxe");
        ITEM_ALIASES.put("netherite pickaxe", "netherite_pickaxe");
        ITEM_ALIASES.put("netherite pick", "netherite_pickaxe");
        // Swords
        ITEM_ALIASES.put("sword", "wooden_sword");
        ITEM_ALIASES.put("wood sword", "wooden_sword");
        ITEM_ALIASES.put("wooden sword", "wooden_sword");
        ITEM_ALIASES.put("stone sword", "stone_sword");
        ITEM_ALIASES.put("iron sword", "iron_sword");
        ITEM_ALIASES.put("diamond sword", "diamond_sword");
        ITEM_ALIASES.put("netherite sword", "netherite_sword");
        // Axes
        ITEM_ALIASES.put("axe", "wooden_axe");
        ITEM_ALIASES.put("wood axe", "wooden_axe");
        ITEM_ALIASES.put("wooden axe", "wooden_axe");
        ITEM_ALIASES.put("stone axe", "stone_axe");
        ITEM_ALIASES.put("iron axe", "iron_axe");
        ITEM_ALIASES.put("diamond axe", "diamond_axe");
        ITEM_ALIASES.put("netherite axe", "netherite_axe");
        // Shovels
        ITEM_ALIASES.put("shovel", "wooden_shovel");
        ITEM_ALIASES.put("spade", "wooden_shovel");
        ITEM_ALIASES.put("wood shovel", "wooden_shovel");
        ITEM_ALIASES.put("wooden shovel", "wooden_shovel");
        ITEM_ALIASES.put("stone shovel", "stone_shovel");
        ITEM_ALIASES.put("iron shovel", "iron_shovel");
        ITEM_ALIASES.put("diamond shovel", "diamond_shovel");
        ITEM_ALIASES.put("netherite shovel", "netherite_shovel");
        // Hoes
        ITEM_ALIASES.put("hoe", "wooden_hoe");
        ITEM_ALIASES.put("wood hoe", "wooden_hoe");
        ITEM_ALIASES.put("wooden hoe", "wooden_hoe");
        ITEM_ALIASES.put("stone hoe", "stone_hoe");
        ITEM_ALIASES.put("iron hoe", "iron_hoe");
        ITEM_ALIASES.put("diamond hoe", "diamond_hoe");
        // Crafting table
        ITEM_ALIASES.put("crafting table", "crafting_table");
        ITEM_ALIASES.put("table", "crafting_table");
        ITEM_ALIASES.put("workbench", "crafting_table");
        ITEM_ALIASES.put("bench", "crafting_table");
        ITEM_ALIASES.put("work bench", "crafting_table");
        ITEM_ALIASES.put("crafting bench", "crafting_table");
        // Furnace
        ITEM_ALIASES.put("furnace", "furnace");
        ITEM_ALIASES.put("oven", "furnace");
        ITEM_ALIASES.put("smelter", "furnace");
        ITEM_ALIASES.put("blast furnace", "blast_furnace");
        ITEM_ALIASES.put("smoker", "smoker");
        // Storage
        ITEM_ALIASES.put("chest", "chest");
        ITEM_ALIASES.put("storage", "chest");
        ITEM_ALIASES.put("barrel", "barrel");
        ITEM_ALIASES.put("ender chest", "ender_chest");
        ITEM_ALIASES.put("shulker box", "shulker_box");
        // Torch
        ITEM_ALIASES.put("torch", "torch");
        ITEM_ALIASES.put("torches", "torch");
        ITEM_ALIASES.put("light", "torch");
        // Basics
        ITEM_ALIASES.put("bucket", "bucket");
        ITEM_ALIASES.put("pail", "bucket");
        ITEM_ALIASES.put("water bucket", "water_bucket");
        ITEM_ALIASES.put("lava bucket", "lava_bucket");
        ITEM_ALIASES.put("sticks", "stick");
        ITEM_ALIASES.put("stick", "stick");
        ITEM_ALIASES.put("planks", "oak_planks");
        ITEM_ALIASES.put("plank", "oak_planks");
        ITEM_ALIASES.put("wood planks", "oak_planks");
        ITEM_ALIASES.put("wooden planks", "oak_planks");
        ITEM_ALIASES.put("oak planks", "oak_planks");
        ITEM_ALIASES.put("birch planks", "birch_planks");
        ITEM_ALIASES.put("spruce planks", "spruce_planks");
        // Movement
        ITEM_ALIASES.put("boat", "oak_boat");
        ITEM_ALIASES.put("minecart", "minecart");
        ITEM_ALIASES.put("cart", "minecart");
        ITEM_ALIASES.put("rail", "rail");
        ITEM_ALIASES.put("rails", "rail");
        ITEM_ALIASES.put("powered rail", "powered_rail");
        // Bed
        ITEM_ALIASES.put("bed", "white_bed");
        ITEM_ALIASES.put("white bed", "white_bed");
        ITEM_ALIASES.put("red bed", "red_bed");
        // Doors / access
        ITEM_ALIASES.put("door", "oak_door");
        ITEM_ALIASES.put("wooden door", "oak_door");
        ITEM_ALIASES.put("iron door", "iron_door");
        ITEM_ALIASES.put("trapdoor", "oak_trapdoor");
        ITEM_ALIASES.put("ladder", "ladder");
        ITEM_ALIASES.put("ladders", "ladder");
        ITEM_ALIASES.put("fence", "oak_fence");
        ITEM_ALIASES.put("gate", "oak_fence_gate");
        ITEM_ALIASES.put("fence gate", "oak_fence_gate");
        ITEM_ALIASES.put("sign", "oak_sign");
        // Weapons / combat
        ITEM_ALIASES.put("bow", "bow");
        ITEM_ALIASES.put("crossbow", "crossbow");
        ITEM_ALIASES.put("arrows", "arrow");
        ITEM_ALIASES.put("arrow", "arrow");
        ITEM_ALIASES.put("shield", "shield");
        ITEM_ALIASES.put("trident", "trident");
        // Armor
        ITEM_ALIASES.put("armor", "iron_chestplate");
        ITEM_ALIASES.put("armour", "iron_chestplate");
        ITEM_ALIASES.put("helmet", "iron_helmet");
        ITEM_ALIASES.put("chestplate", "iron_chestplate");
        ITEM_ALIASES.put("leggings", "iron_leggings");
        ITEM_ALIASES.put("boots", "iron_boots");
        ITEM_ALIASES.put("iron armor", "iron_chestplate");
        ITEM_ALIASES.put("iron armour", "iron_chestplate");
        ITEM_ALIASES.put("iron helmet", "iron_helmet");
        ITEM_ALIASES.put("iron chestplate", "iron_chestplate");
        ITEM_ALIASES.put("iron leggings", "iron_leggings");
        ITEM_ALIASES.put("iron boots", "iron_boots");
        ITEM_ALIASES.put("diamond armor", "diamond_chestplate");
        ITEM_ALIASES.put("diamond armour", "diamond_chestplate");
        ITEM_ALIASES.put("diamond helmet", "diamond_helmet");
        ITEM_ALIASES.put("diamond chestplate", "diamond_chestplate");
        ITEM_ALIASES.put("diamond leggings", "diamond_leggings");
        ITEM_ALIASES.put("diamond boots", "diamond_boots");
        ITEM_ALIASES.put("leather armor", "leather_chestplate");
        ITEM_ALIASES.put("leather armour", "leather_chestplate");
        ITEM_ALIASES.put("chainmail", "chainmail_chestplate");
        // Glass / decorative
        ITEM_ALIASES.put("glass", "glass");
        ITEM_ALIASES.put("glass pane", "glass_pane");
        ITEM_ALIASES.put("glass block", "glass");
        // Paper / books
        ITEM_ALIASES.put("paper", "paper");
        ITEM_ALIASES.put("book", "book");
        ITEM_ALIASES.put("bookshelf", "bookshelf");
        ITEM_ALIASES.put("enchanting table", "enchanting_table");
        ITEM_ALIASES.put("enchantment table", "enchanting_table");
        // Tools / utility
        ITEM_ALIASES.put("shears", "shears");
        ITEM_ALIASES.put("scissors", "shears");
        ITEM_ALIASES.put("compass", "compass");
        ITEM_ALIASES.put("clock", "clock");
        ITEM_ALIASES.put("watch", "clock");
        ITEM_ALIASES.put("map", "map");
        ITEM_ALIASES.put("flint and steel", "flint_and_steel");
        ITEM_ALIASES.put("lighter", "flint_and_steel");
        ITEM_ALIASES.put("fishing rod", "fishing_rod");
        ITEM_ALIASES.put("rod", "fishing_rod");
        ITEM_ALIASES.put("lead", "lead");
        ITEM_ALIASES.put("leash", "lead");
        ITEM_ALIASES.put("name tag", "name_tag");
        // Redstone
        ITEM_ALIASES.put("piston", "piston");
        ITEM_ALIASES.put("sticky piston", "sticky_piston");
        ITEM_ALIASES.put("hopper", "hopper");
        ITEM_ALIASES.put("dropper", "dropper");
        ITEM_ALIASES.put("dispenser", "dispenser");
        ITEM_ALIASES.put("repeater", "repeater");
        ITEM_ALIASES.put("comparator", "comparator");
        ITEM_ALIASES.put("observer", "observer");
        ITEM_ALIASES.put("lever", "lever");
        ITEM_ALIASES.put("button", "stone_button");
        ITEM_ALIASES.put("pressure plate", "stone_pressure_plate");
        ITEM_ALIASES.put("daylight sensor", "daylight_detector");
        ITEM_ALIASES.put("tripwire hook", "tripwire_hook");
        // Building
        ITEM_ALIASES.put("anvil", "anvil");
        ITEM_ALIASES.put("campfire", "campfire");
        ITEM_ALIASES.put("lantern", "lantern");
        ITEM_ALIASES.put("slab", "oak_slab");
        ITEM_ALIASES.put("stairs", "oak_stairs");
        ITEM_ALIASES.put("stone bricks", "stone_bricks");
        ITEM_ALIASES.put("bricks", "bricks");
        ITEM_ALIASES.put("cobblestone wall", "cobblestone_wall");
        ITEM_ALIASES.put("stone wall", "cobblestone_wall");
        // Explosives
        ITEM_ALIASES.put("tnt", "tnt");
        ITEM_ALIASES.put("dynamite", "tnt");
        // Food
        ITEM_ALIASES.put("bread", "bread");
        ITEM_ALIASES.put("cake", "cake");
        ITEM_ALIASES.put("cookie", "cookie");
        ITEM_ALIASES.put("cookies", "cookie");
        ITEM_ALIASES.put("pie", "pumpkin_pie");
        ITEM_ALIASES.put("pumpkin pie", "pumpkin_pie");
        ITEM_ALIASES.put("steak", "cooked_beef");
        ITEM_ALIASES.put("cooked beef", "cooked_beef");
        ITEM_ALIASES.put("golden apple", "golden_apple");
        // Common misspellings
        ITEM_ALIASES.put("pikaxe", "wooden_pickaxe");
        ITEM_ALIASES.put("picaxe", "wooden_pickaxe");
        ITEM_ALIASES.put("soard", "wooden_sword");
        ITEM_ALIASES.put("swrod", "wooden_sword");
        ITEM_ALIASES.put("furnase", "furnace");
        ITEM_ALIASES.put("furnce", "furnace");
        ITEM_ALIASES.put("tabel", "crafting_table");
        ITEM_ALIASES.put("chets", "chest");
        ITEM_ALIASES.put("tourch", "torch");
        ITEM_ALIASES.put("torche", "torch");
        ITEM_ALIASES.put("bukket", "bucket");
        ITEM_ALIASES.put("buckt", "bucket");
        ITEM_ALIASES.put("shielf", "shield");
        ITEM_ALIASES.put("sheild", "shield");
        ITEM_ALIASES.put("armer", "iron_chestplate");
        ITEM_ALIASES.put("armr", "iron_chestplate");
    }

    // ==================== Mob name normalization ====================

    private static final Map<String, String> MOB_ALIASES = new HashMap<>();
    static {
        MOB_ALIASES.put("zombie", "zombie");
        MOB_ALIASES.put("zombies", "zombie");
        MOB_ALIASES.put("zombi", "zombie");
        MOB_ALIASES.put("skeleton", "skeleton");
        MOB_ALIASES.put("skeletons", "skeleton");
        MOB_ALIASES.put("skelly", "skeleton");
        MOB_ALIASES.put("skelleton", "skeleton");
        MOB_ALIASES.put("creeper", "creeper");
        MOB_ALIASES.put("creepers", "creeper");
        MOB_ALIASES.put("creper", "creeper");
        MOB_ALIASES.put("spider", "spider");
        MOB_ALIASES.put("spiders", "spider");
        MOB_ALIASES.put("spyder", "spider");
        MOB_ALIASES.put("enderman", "enderman");
        MOB_ALIASES.put("endermen", "enderman");
        MOB_ALIASES.put("ender man", "enderman");
        MOB_ALIASES.put("witch", "witch");
        MOB_ALIASES.put("witches", "witch");
        MOB_ALIASES.put("slime", "slime");
        MOB_ALIASES.put("slimes", "slime");
        MOB_ALIASES.put("drowned", "drowned");
        MOB_ALIASES.put("phantom", "phantom");
        MOB_ALIASES.put("phantoms", "phantom");
        MOB_ALIASES.put("pillager", "pillager");
        MOB_ALIASES.put("pillagers", "pillager");
        MOB_ALIASES.put("vindicator", "vindicator");
        MOB_ALIASES.put("blaze", "blaze");
        MOB_ALIASES.put("blazes", "blaze");
        MOB_ALIASES.put("ghast", "ghast");
        MOB_ALIASES.put("ghasts", "ghast");
        MOB_ALIASES.put("wither skeleton", "wither_skeleton");
        MOB_ALIASES.put("wither", "wither_skeleton");
        MOB_ALIASES.put("piglin", "piglin");
        MOB_ALIASES.put("piglins", "piglin");
        MOB_ALIASES.put("hoglin", "hoglin");
        MOB_ALIASES.put("hoglins", "hoglin");
        MOB_ALIASES.put("cave spider", "cave_spider");
        MOB_ALIASES.put("silverfish", "silverfish");
        MOB_ALIASES.put("guardian", "guardian");
        MOB_ALIASES.put("guardians", "guardian");
        MOB_ALIASES.put("elder guardian", "elder_guardian");
        MOB_ALIASES.put("ravager", "ravager");
        MOB_ALIASES.put("evoker", "evoker");
        MOB_ALIASES.put("vex", "vex");
        MOB_ALIASES.put("husk", "husk");
        MOB_ALIASES.put("husks", "husk");
        MOB_ALIASES.put("stray", "stray");
        MOB_ALIASES.put("strays", "stray");
        MOB_ALIASES.put("magma cube", "magma_cube");
        MOB_ALIASES.put("magma", "magma_cube");
        MOB_ALIASES.put("shulker", "shulker");
        MOB_ALIASES.put("warden", "warden");
        // Animals
        MOB_ALIASES.put("pig", "pig");
        MOB_ALIASES.put("pigs", "pig");
        MOB_ALIASES.put("cow", "cow");
        MOB_ALIASES.put("cows", "cow");
        MOB_ALIASES.put("sheep", "sheep");
        MOB_ALIASES.put("chicken", "chicken");
        MOB_ALIASES.put("chickens", "chicken");
        MOB_ALIASES.put("horse", "horse");
        MOB_ALIASES.put("horses", "horse");
        MOB_ALIASES.put("wolf", "wolf");
        MOB_ALIASES.put("wolves", "wolf");
        MOB_ALIASES.put("rabbit", "rabbit");
        MOB_ALIASES.put("rabbits", "rabbit");
        MOB_ALIASES.put("bunny", "rabbit");
        MOB_ALIASES.put("fox", "fox");
        MOB_ALIASES.put("foxes", "fox");
        MOB_ALIASES.put("cat", "cat");
        MOB_ALIASES.put("cats", "cat");
        MOB_ALIASES.put("bee", "bee");
        MOB_ALIASES.put("bees", "bee");
        // Generic
        MOB_ALIASES.put("hostile", "hostile");
        MOB_ALIASES.put("hostiles", "hostile");
        MOB_ALIASES.put("monsters", "hostile");
        MOB_ALIASES.put("mobs", "hostile");
        MOB_ALIASES.put("enemies", "hostile");
        MOB_ALIASES.put("everything", "hostile");
        MOB_ALIASES.put("anything nearby", "hostile");
    }

    // ==================== Jim's personality responses ====================

    private static final String[] CONFUSED_RESPONSES = {
            "Hmm, I'm not sure what you mean. Try something like 'mine iron' or 'craft a pickaxe'!",
            "I didn't quite catch that. I can mine, craft, chop trees, smelt, build, guard, and more — just tell me what!",
            "Sorry, that went over my head. Want me to mine something? Craft an item? Chop some trees?",
            "I'm scratching my blocky head here... Can you rephrase that? I understand things like 'get me some diamonds' or 'make a sword'.",
            "Not sure what to do with that. Try 'mine iron', 'craft bucket', 'chop trees', or 'follow me'!",
            "Huh? I'm just a simple companion — tell me to mine, craft, build, or fight and I'll get right on it!",
            "I don't understand that one. Some things I can do: mine ores, craft items, chop wood, smelt, fish, farm, guard...",
            "That's a new one for me! I work best with clear tasks like 'mine diamonds' or 'make me a pickaxe'.",
            "Eh? Say that again but simpler maybe? Like 'chop trees' or 'smelt iron'.",
            "I'm confused! Try saying: 'help' to see everything I can do.",
    };

    private static final String[] CANT_DO_RESPONSES = {
            "I'd love to help with that, but it's beyond what I can do right now.",
            "That's a bit outside my skill set. I'm great at mining, crafting, and chopping though!",
            "I wish I could do that! Maybe ask me to mine, craft, or build something instead?",
            "I can't do that one, sorry. But I can mine, craft, smelt, chop, farm, fish, guard, and build!",
            "That tool is disabled right now. Maybe try something else?",
    };

    private static final String[] BUSY_RESPONSES = {
            "I'm already working on something! Say 'cancel' first if you want me to switch tasks.",
            "Hang on, I'm in the middle of a task. Tell me to 'stop' if you want me to do something else.",
            "Still working on my current job! Use 'cancel' if you need me for something new.",
            "One thing at a time, boss! Cancel the current task first.",
    };

    // ==================== Patterns (EXTENSIVE) ====================
    // All patterns use CASE_INSENSITIVE flag and handle many natural phrasings.
    // The order of checking in tryParse() matters: more specific matches first.

    // --- STRIP MINING ---
    // "strip mine for iron", "go strip mining", "strip mine diamonds",
    // "start strip mining for gold", "can you strip mine for diamonds"
    private static final Pattern STRIP_MINE_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:start\\s+)?strip\\s*min(?:e|ing)\\s*" +
            "(?:for\\s+)?(?:some\\s+)?(?:(\\d+)\\s+)?(.+?)(?:\\s+ore(?:s)?)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // "dig a tunnel", "tunnel for iron", "dig a mine"
    private static final Pattern TUNNEL_PATTERN = Pattern.compile(
            "(?:(?:can|could) you\\s+)?(?:please\\s+)?(?:dig|make|create|start)\\s+(?:a\\s+)?(?:tunnel|shaft)\\s*" +
            "(?:for\\s+(?:some\\s+)?(.+?)(?:\\s+ore(?:s)?)?)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- MINING ---
    // "mine iron", "dig some gold", "go mine 10 diamonds", "find me coal",
    // "get me some iron ore", "can you mine iron", "grab some copper",
    // "collect emeralds", "excavate gold", "i want to mine iron"
    private static final Pattern MINE_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:mine|dig|find|collect|grab|fetch|excavate|gather|obtain|acquire|harvest)\\s+" +
            "(?:me\\s+)?(?:some\\s+|more\\s+)?(?:(\\d+)\\s+)?(.+?)(?:\\s+ore(?:s)?)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // "i need iron", "we need diamonds", "i want iron"
    private static final Pattern NEED_ORE_PATTERN = Pattern.compile(
            "(?:i|we)\\s+(?:need|want|could use|require|gotta get|gotta have)\\s+" +
            "(?:some\\s+|more\\s+)?(?:(\\d+)\\s+)?(.+?)(?:\\s+ore(?:s)?)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // "get me some iron", "go get iron", "get iron for me"
    private static final Pattern GET_ORE_PATTERN = Pattern.compile(
            "(?:go\\s+)?get\\s+(?:me\\s+)?(?:some\\s+|more\\s+)?(?:(\\d+)\\s+)?(.+?)(?:\\s+ore(?:s)?)?(?:\\s+for me)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- CRAFTING ---
    // "craft a bucket", "make me an iron pickaxe", "build a furnace", "create 4 torches",
    // "forge an iron sword", "can you make a sword", "please make me a crafting table",
    // "put together a chest", "whip up a bucket", "i want to make a pickaxe"
    private static final Pattern CRAFT_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:craft|make|build|create|construct|assemble|forge|put together|" +
            "whip up|produce|fabricate|prepare|fashion)\\s+" +
            "(?:me\\s+)?(?:an?\\s+)?(?:(\\d+)\\s+)?(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // "i need a pickaxe", "i want a bucket", "we need a crafting table"
    private static final Pattern NEED_ITEM_PATTERN = Pattern.compile(
            "(?:i|we)\\s+(?:need|want|could use|require|gotta have)\\s+(?:an?\\s+)?(?:(\\d+)\\s+)?(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // "i want to make ...", "i wanna craft ..."
    private static final Pattern WANT_TO_MAKE_PATTERN = Pattern.compile(
            "i\\s+(?:want|wanna|would like|need)\\s+(?:to\\s+)?(?:make|craft|build|create)\\s+" +
            "(?:an?\\s+)?(?:(\\d+)\\s+)?(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- CHOPPING ---
    // "chop trees", "cut wood", "fell some trees", "go chop trees", "get 20 logs",
    // "chop down trees", "harvest timber", "gather wood", "get some lumber",
    // "can you chop some trees", "go cut down some trees"
    private static final Pattern CHOP_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:chop|cut|fell|harvest|lumber|gather|collect)\\s+" +
            "(?:down\\s+)?(?:some\\s+)?(?:(\\d+)\\s+)?(?:trees?|logs?|wood|timber|lumber)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // "get wood", "get logs", "get some wood", "go get wood", "bring me wood"
    private static final Pattern GET_WOOD_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:get|grab|fetch|bring|find)\\s+" +
            "(?:me\\s+)?(?:some\\s+|more\\s+)?(?:(\\d+)\\s+)?(?:wood|logs?|timber|lumber)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // "i need wood/logs"
    private static final Pattern NEED_WOOD_PATTERN = Pattern.compile(
            "(?:i|we)\\s+(?:need|want|could use|require)\\s+(?:some\\s+|more\\s+)?(?:(\\d+)\\s+)?(?:wood|logs?|timber)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- SMELTING ---
    // "smelt iron", "cook 3 raw iron", "smelt raw_iron", "smelt the iron ore",
    // "melt some gold", "can you smelt iron", "burn some raw copper", "process raw iron"
    private static final Pattern SMELT_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:smelt|cook|burn|melt|process|refine|heat|bake|roast)\\s+" +
            "(?:the\\s+)?(?:my\\s+)?(?:some\\s+)?(?:(\\d+)\\s+)?(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- INVENTORY ---
    // "check inventory", "what do you have", "show inventory", "whats in your bag",
    // "show me your items", "what are you carrying", "do you have anything"
    private static final Pattern INVENTORY_PATTERN = Pattern.compile(
            "(?:check|show|display|open|list|view|see|inspect)\\s+(?:me\\s+)?(?:your\\s+)?(?:inventory|items|stuff|bag|belongings|things)|" +
            "what(?:'s|s| is| do you have| are you carrying)\\s*(?:in (?:your )?)?(?:inventory|bag|items|on you)?|" +
            "what do you have|what are you holding|what are you carrying|" +
            "(?:show|tell) me (?:what you have|your (?:inventory|items|stuff))|" +
            "do you have (?:anything|something|items)|" +
            "(?:your )?inventory|" +
            "what(?:'s|s) on you",
            Pattern.CASE_INSENSITIVE);

    // --- COME HERE ---
    // "come here", "come to me", "teleport to me", "get over here", "come back",
    // "tp here", "warp to me"
    private static final Pattern COME_PATTERN = Pattern.compile(
            "(?:come\\s+(?:here|to me|back|over here|on over)|" +
            "(?:teleport|tp|warp)\\s+(?:to me|here|over)|" +
            "get (?:over )?here|" +
            "(?:over|right) here|" +
            "(?:come|get) (?:back )?(?:to me|here)|" +
            "here boy|here jim)",
            Pattern.CASE_INSENSITIVE);

    // --- FOLLOW ---
    // "follow me", "follow", "come with me", "tag along", "walk with me", "stay close"
    private static final Pattern FOLLOW_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:follow(?:\\s+me)?|" +
            "come with me|tag along|walk with me|stay (?:close|near)(?: to me)?|" +
            "stick with me|keep up|come along|let's go|lets go|with me)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- STAY ---
    // "stay", "stay here", "wait", "wait here", "stop", "don't move",
    // "hold position", "stay put", "remain", "sit", "hang tight"
    private static final Pattern STAY_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:stay(?:\\s+(?:here|put|still))?|" +
            "wait(?:\\s+here)?|(?:don't|do not|dont) move|" +
            "hold (?:position|still|your position)|remain(?:\\s+here)?|park it|sit(?:\\s+here)?|" +
            "stand(?:\\s+here)?|hang tight|stand still|freeze|hold on|chill|" +
            "don't follow|stop following)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- CANCEL ---
    // "cancel", "stop", "abort", "nevermind", "forget it", "scratch that", "nvm"
    private static final Pattern CANCEL_PATTERN = Pattern.compile(
            "(?:cancel|abort|nevermind|never ?mind|nvm|forget (?:it|that|about it)|scratch that|" +
            "knock it off|cut it out|enough|quit|drop it|leave it|disregard|scrap (?:it|that))(?:\\s+(?:all\\s+)?(?:task|tasks|that|it|everything))?\\s*$|" +
            "stop(?:\\s+(?:all\\s+)?(?:task|tasks|that|it|everything|what you.re doing|doing that|working|mining|crafting|building|chopping))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- FISHING ---
    // "go fishing", "fish", "catch some fish", "let's fish", "do some fishing"
    private static final Pattern FISH_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:(?:go|do some|let's|lets|start)\\s+)?(?:fish|fishing)|" +
            "catch\\s+(?:some\\s+|me some\\s+)?fish|" +
            "(?:cast|drop)\\s+(?:a\\s+)?line|go fish",
            Pattern.CASE_INSENSITIVE);

    // --- FARMING ---
    // "farm", "farm area", "plant crops", "harvest crops", "tend the farm",
    // "go farm", "work the fields", "do some farming", "plant seeds", "till the soil"
    private static final Pattern FARM_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:farm|plant|harvest|tend|till|hoe|cultivate|grow|sow)\\s*" +
            "(?:the\\s+)?(?:area|crops?|fields?|farm|seeds?|soil|ground|wheat|potatoes?|carrots?|beetroot)?\\s*$|" +
            "(?:do some|go|start|begin)\\s+farming|work the (?:fields?|farm|land)|" +
            "tend (?:the |to the )?(?:farm|crops|garden)",
            Pattern.CASE_INSENSITIVE);

    // --- STATUS ---
    // "status", "what are you doing", "what's going on", "task status",
    // "how's it going", "progress", "report"
    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "(?:what(?:'s|s| is| are you)\\s+(?:your\\s+)?(?:status|doing|up to|working on|going on))|" +
            "(?:task\\s+)?(?:status|progress|report|sitrep|update)|" +
            "how(?:'s|s| is| are) (?:it|things|you)\\s*(?:going|doing)?|" +
            "are you (?:done|busy|finished|working|idle|free)|" +
            "you (?:busy|done|idle|free)\\??|" +
            "give me (?:a\\s+)?(?:status|update|report)|" +
            "what(?:'s|s) (?:the )?(?:progress|status|update)",
            Pattern.CASE_INSENSITIVE);

    // --- SCAN SURROUNDINGS ---
    // "scan area", "look around", "what's around", "check the area", "what's nearby"
    private static final Pattern SCAN_PATTERN = Pattern.compile(
            "(?:(?:can|could) you\\s+)?(?:scan|look|check|inspect|survey|scout|explore|examine|observe)\\s+" +
            "(?:the\\s+)?(?:around|surroundings?|area|vicinity|environment|nearby|here)|" +
            "what(?:'s|s| is|'re) (?:around|nearby|here|near (?:me|us))|" +
            "look around|what(?:'s|s) (?:out )?there|" +
            "what can you see|describe (?:the )?(?:area|surroundings?)|" +
            "scout (?:the )?area|survey (?:the )?(?:area|land)|" +
            "scan|scan here|check nearby|check around",
            Pattern.CASE_INSENSITIVE);

    // --- DEPOSIT ITEMS ---
    // "deposit items", "store items", "put items away", "empty inventory",
    // "dump your stuff", "stash everything"
    private static final Pattern DEPOSIT_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?" +
            "(?:deposit|store|stash|dump|unload|offload|put away|drop off|empty|clean out)\\s*" +
            "(?:your\\s+|all\\s+|the\\s+)?(?:items?|inventory|stuff|everything|things|loot|goods)?\\s*" +
            "(?:in(?:to)?\\s+(?:the\\s+)?(?:storage|chest|containers?))?\\s*$|" +
            "(?:put|place|move)\\s+(?:everything|items?|your stuff|all)\\s+(?:in(?:to)?\\s+)?(?:the\\s+)?(?:storage|chest|away)|" +
            "empty (?:your |the )?(?:inventory|bag|pockets)|" +
            "clean (?:out )?(?:your )?(?:inventory|bag)",
            Pattern.CASE_INSENSITIVE);

    // --- GUARD ---
    // "guard", "guard here", "protect this area", "defend", "watch over", "keep watch"
    private static final Pattern GUARD_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?" +
            "(?:guard|protect|defend|watch|patrol|sentry|keep watch|stand guard)\\s*" +
            "(?:here|this (?:area|place|spot)|(?:over )?(?:me|us)|my back|the (?:area|base|house|home))?\\s*$|" +
            "keep (?:us|me|the area) safe|watch (?:my|our) back|" +
            "be on (?:guard|lookout|watch)|keep (?:a )?lookout",
            Pattern.CASE_INSENSITIVE);

    // --- KILL / ATTACK ---
    // "kill the zombie", "attack that skeleton", "fight the creeper",
    // "kill mobs", "slay that monster", "go kill monsters"
    private static final Pattern KILL_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:kill|attack|fight|slay|destroy|murder|eliminate|smash|" +
            "take out|take down|deal with|handle|dispose of|hunt|get rid of|punch|hit|whack|smack)\\s+" +
            "(?:that\\s+|the\\s+|those\\s+|all\\s+(?:the\\s+)?|any\\s+|some\\s+|every\\s+)?(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- FETCH / FIND ITEM ---
    // "find me a diamond", "fetch a bucket", "bring me some cobblestone"
    private static final Pattern FETCH_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:find|fetch|bring|retrieve|locate|search for|look for|hunt for)\\s+" +
            "(?:me\\s+)?(?:an?\\s+)?(?:some\\s+)?(?:(\\d+)\\s+)?(.+?)(?:\\s+(?:from|in|out of) .+)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- EQUIP ---
    // "equip", "equip best gear", "put on armor", "gear up", "suit up"
    private static final Pattern EQUIP_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?" +
            "(?:equip|gear up|suit up|arm yourself|put on|wear)\\s*" +
            "(?:your\\s+)?(?:best\\s+)?(?:gear|armor|armour|equipment|stuff|weapons?)?\\s*$|" +
            "equip(?:\\s+(?:best|gear|up))?|gear up|suit up|arm up|tool up",
            Pattern.CASE_INSENSITIVE);

    // --- RECIPE ---
    // "how do i craft a bucket", "recipe for iron pickaxe", "what's the recipe for"
    private static final Pattern RECIPE_PATTERN = Pattern.compile(
            "(?:how (?:do (?:i|you|we)|to|can (?:i|you|we))\\s+(?:craft|make|build|create)\\s+(?:an?\\s+)?(.+?))|" +
            "(?:recipe|ingredients?|materials?)\\s+(?:for|of|to (?:make|craft))\\s+(?:an?\\s+)?(.+?)|" +
            "(?:show|get|display|what(?:'s|s| is))\\s+(?:the\\s+)?(?:recipe|crafting recipe)\\s+(?:for|of)\\s+(?:an?\\s+)?(.+?)|" +
            "what(?:'s|s| is| do (?:i|you|we))\\s+need(?:ed)?\\s+(?:for|to (?:make|craft))\\s+(?:an?\\s+)?(.+?)|" +
            "how (?:is|are)\\s+(?:an?\\s+)?(.+?)\\s+(?:made|crafted|built)",
            Pattern.CASE_INSENSITIVE);

    // --- SCAN CONTAINERS ---
    // "scan chests", "check containers", "what's in the chests"
    private static final Pattern SCAN_CONTAINERS_PATTERN = Pattern.compile(
            "(?:(?:can|could) you\\s+)?(?:scan|check|inspect|look (?:in|at|through)|search|browse)\\s+" +
            "(?:the\\s+)?(?:nearby\\s+)?(?:chests?|containers?|storage|barrels?|boxes?)\\s*$|" +
            "what(?:'s|s| is) in (?:the )?(?:nearby )?(?:chests?|containers?|storage)|" +
            "check (?:the )?(?:nearby )?(?:chests?|containers?|storage)",
            Pattern.CASE_INSENSITIVE);

    // --- BOOKMARK ---
    // "bookmark this", "remember this location", "save this spot"
    private static final Pattern BOOKMARK_PATTERN = Pattern.compile(
            "(?:bookmark|save|remember|mark|waypoint|pin)\\s+(?:this\\s+)?(?:location|spot|place|position|point|area|here|coords?|coordinates?)|" +
            "remember (?:where (?:we|i) (?:are|am)|this (?:place|spot))|" +
            "(?:set|add|create|make|drop)\\s+(?:a\\s+)?(?:bookmark|waypoint|marker|pin)\\s*(?:here)?|" +
            "save (?:this )?(?:location|spot|place|coords?|position)|" +
            "remember here|bookmark here",
            Pattern.CASE_INSENSITIVE);

    // --- RENAME COMPANION ---
    // "rename yourself to Bob", "call yourself Jim", "change your name to Max"
    private static final Pattern RENAME_PATTERN = Pattern.compile(
            "(?:rename yourself|change your name|your name is|call yourself|i'll call you|" +
            "you are now|(?:your )?new name is|be called|go by|i'm (?:gonna|going to) call you)\\s+" +
            "(?:to\\s+|now\\s+)?(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- TRADE ---
    // "trade with villager", "villager trade", "go trade", "barter"
    private static final Pattern TRADE_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:trade|barter|exchange|deal|haggle|negotiate)\\s*" +
            "(?:with\\s+)?(?:(?:a|the|that|this)\\s+)?(?:villager|trader|merchant|wandering trader)?\\s*$|" +
            "(?:talk|speak) to (?:the |a )?(?:villager|trader|merchant)|" +
            "(?:find|go to) (?:a |the )?(?:villager|trader|merchant)|" +
            "villager trade",
            Pattern.CASE_INSENSITIVE);

    // --- BUILD ---
    // "build a house", "build a wall", "build a shelter"
    private static final Pattern BUILD_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:build|construct|erect|raise|set up|put up)\\s+" +
            "(?:me\\s+)?(?:a\\s+)?(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- DIG DOWN ---
    // "dig down", "dig straight down", "go underground", "dig a hole"
    private static final Pattern DIG_DOWN_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:dig|mine|go)\\s+(?:straight\\s+)?(?:down|deeper|underground)|" +
            "dig (?:a )?hole|burrow|go (?:below|under)|mine downward|dig downward|" +
            "dig (?:straight )?down",
            Pattern.CASE_INSENSITIVE);

    // --- CREATE MINE ---
    // "create a mine", "set up a mine", "establish a mine"
    private static final Pattern CREATE_MINE_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:create|set up|establish|make|build|dig|start)\\s+" +
            "(?:a\\s+|me a\\s+)?(?:mine|mine shaft|mining shaft|mining area|quarry|mining operation)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- EMOTE ---
    // "dance", "wave", "jump", "celebrate", "spin"
    private static final Pattern EMOTE_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:do an?\\s+)?" +
            "(?:dance|wave|jump|celebrate|spin|flip|cheer|bow|salute|clap|nod|shake|dab|" +
            "thumbs up|fist bump|high five|emote)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- DELIVER ---
    // "give me the items", "hand over", "deliver items", "bring me the stuff"
    private static final Pattern DELIVER_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?" +
            "(?:give|hand|deliver|bring|pass|toss|throw)\\s+" +
            "(?:me\\s+)?(?:the\\s+|your\\s+|all\\s+(?:the\\s+)?)?(?:items?|stuff|things?|everything|loot|goods|materials?|(?:what you (?:have|got)))\\s*$|" +
            "hand (?:over|it over|them over)|give (?:it|them) (?:to me|here)|" +
            "(?:give|hand|toss|pass) (?:me )?(?:everything|it all|all of it)",
            Pattern.CASE_INSENSITIVE);

    // --- TRANSFER ---
    // "put items in chest", "transfer to storage", "move items to container"
    private static final Pattern TRANSFER_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?" +
            "(?:put|place|transfer|move|drop)\\s+" +
            "(?:the\\s+|your\\s+|all\\s+)?(?:items?|stuff|things|everything|it|them)\\s+" +
            "(?:in(?:to)?|to|in the|into the)\\s+(?:the\\s+)?(?:chest|storage|container|barrel|box)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- GATHER BLOCKS ---
    // "gather cobblestone", "collect sand", "get dirt", "pick up some gravel"
    private static final Pattern GATHER_PATTERN = Pattern.compile(
            "(?:(?:can|could|would) you\\s+)?(?:please\\s+)?(?:go\\s+)?(?:and\\s+)?" +
            "(?:gather|collect|pick up|get|grab|break|clear)\\s+" +
            "(?:me\\s+)?(?:some\\s+|more\\s+)?(?:(\\d+)\\s+)?(?:blocks? of )?(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- GREETINGS / CHITCHAT ---
    private static final Pattern GREETING_PATTERN = Pattern.compile(
            "^(?:hi|hey|hello|yo|sup|heya|hiya|howdy|greetings|what's up|whats up|g'day|morning|" +
            "good morning|good afternoon|good evening|oi|ahoy|salutations|" +
            "what's good|whaddup|wassup|whassup)\\s*(?:jim|buddy|pal|mate|friend|there|dude|bro|man)?[!.]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- THANKS ---
    private static final Pattern THANKS_PATTERN = Pattern.compile(
            "^(?:thanks?|thank you|thx|ty|cheers|good job|well done|nice work|great job|" +
            "good work|awesome|perfect|nice one|brilliant|excellent|you're the best|you rock|" +
            "preciate it|appreciate it|tysm|much appreciated|that's great|wonderful|fantastic)[!.]*\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- HELP ---
    private static final Pattern HELP_PATTERN = Pattern.compile(
            "^(?:help|what can you do|commands?|list commands|show commands|" +
            "what do you do|what are you capable of|abilities|skills|" +
            "what tasks can you do|what(?:'s|s) available|options|menu|" +
            "how do you work|what commands|give me help|" +
            "what are your abilities|what can i ask you)\\s*\\??\\s*$",
            Pattern.CASE_INSENSITIVE);

    // --- HEALTH ---
    private static final Pattern HEALTH_PATTERN = Pattern.compile(
            "(?:how(?:'s|s| is) your health|are you (?:ok|okay|hurt|injured|fine|alright|dying|damaged|alive)|" +
            "how much (?:health|hp|hearts?) do you have|check (?:your )?(?:health|hp)|" +
            "your (?:health|hp)|you (?:ok|okay|hurt|injured|dying|good|alive)\\??|" +
            "health check|hp check|hearts?)",
            Pattern.CASE_INSENSITIVE);

    // --- MODS ---
    private static final Pattern MODS_PATTERN = Pattern.compile(
            "(?:list|show|what|which)\\s+(?:are the\\s+)?(?:installed\\s+)?mods|installed mods|" +
            "what mods (?:are|do we have)|mod list|modpack|which mods|" +
            "what(?:'s|s) installed",
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

        // Normalize: trim, collapse whitespace, strip trailing punctuation
        String msg = message.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[.!?]+$", "")
                .trim();

        if (msg.isEmpty()) return false;

        // Strip common filler prefixes for matching (but keep original for display)
        String cleaned = stripFillerPrefixes(msg);

        // ---- Greetings / chitchat (respond immediately, no tool) ----
        if (GREETING_PATTERN.matcher(msg).matches()) {
            respondRandom(player, new String[]{
                    "Hey there! What can I do for you?",
                    "Hello! Need me to mine, craft, or build something?",
                    "Hey! Ready to work. What do you need?",
                    "Howdy! Just say the word and I'll get to it!",
                    "Hi! I'm here and ready. What's the plan?",
                    "Yo! What are we doing today?",
                    "Hey boss! What's on the agenda?",
            });
            return true;
        }

        if (THANKS_PATTERN.matcher(msg).matches()) {
            respondRandom(player, new String[]{
                    "No problem! Happy to help.",
                    "You're welcome! Need anything else?",
                    "Anytime! That's what I'm here for.",
                    "Glad I could help! Just let me know if you need more.",
                    "All in a day's work!",
                    "Happy to help, boss!",
                    "You got it! What's next?",
            });
            return true;
        }

        if (HELP_PATTERN.matcher(msg).matches()) {
            respond(player, "Here's what I can do:\n" +
                    "§e⛏ Mining:§r mine [ore], strip mine [ore], dig down, create mine\n" +
                    "§e🔨 Crafting:§r craft/make [item], smelt [item], recipe for [item]\n" +
                    "§e🪓 Gathering:§r chop trees, get wood, gather [block]\n" +
                    "§e🌾 Farming:§r farm, plant crops, harvest\n" +
                    "§e🎣 Fishing:§r go fishing, fish\n" +
                    "§e⚔ Combat:§r kill [mob], guard area, attack [mob]\n" +
                    "§e🏗 Building:§r build [structure]\n" +
                    "§e📦 Logistics:§r deposit items, check inventory, deliver items, find [item]\n" +
                    "§e🔍 Info:§r scan area, scan chests, task status, health, mods\n" +
                    "§e🐕 Behavior:§r follow me, stay, come here, cancel\n" +
                    "§e📌 Utility:§r bookmark location, rename, equip gear, emote\n" +
                    "§7Tip: Just talk naturally! 'Can you mine some iron?' or 'I need a pickaxe' work too.");
            return true;
        }

        // ---- Behavioral commands (no tool call needed, execute on server thread) ----
        if (companion != null) {
            if (FOLLOW_PATTERN.matcher(msg).matches()) {
                companion.setBehaviorMode(CompanionEntity.BehaviorMode.FOLLOW);
                respondRandom(player, new String[]{
                        "Following you! Lead the way.",
                        "Right behind you, boss!",
                        "On your six! Let's go.",
                        "Following! Where are we headed?",
                        "Sticking close! Let's move.",
                });
                return true;
            }
            if (STAY_PATTERN.matcher(msg).matches()) {
                companion.setBehaviorMode(CompanionEntity.BehaviorMode.STAY);
                respondRandom(player, new String[]{
                        "Staying right here.",
                        "Not moving an inch!",
                        "Holding position. I'll be here.",
                        "Staying put! Call me when you need me.",
                        "Rooted to the spot!",
                });
                return true;
            }
            if (CANCEL_PATTERN.matcher(msg).matches()) {
                boolean hadTasks = companion.getTaskManager().hasTasks();
                companion.getTaskManager().cancelAll();
                companion.getNavigation().stop();
                if (hadTasks) {
                    respondRandom(player, new String[]{
                            "All tasks cancelled. What's next?",
                            "Done! Dropping everything. What now?",
                            "Cancelled! I'm all yours. What do you need?",
                            "Alright, scrapping that. New orders?",
                            "Roger, task cancelled! What else?",
                    });
                } else {
                    respondRandom(player, new String[]{
                            "I wasn't doing anything, but I'm ready!",
                            "Nothing to cancel — I'm idle. What do you need?",
                            "Already free! What should I do?",
                    });
                }
                return true;
            }
            Matcher comeMatcher = COME_PATTERN.matcher(msg);
            if (comeMatcher.find()) {
                double dist = companion.distanceTo(player);
                if (dist > 64.0) {
                    companion.teleportTo(player.getX(), player.getY(), player.getZ());
                    companion.getNavigation().stop();
                    respondRandom(player, new String[]{
                            "Teleporting to you! That was far.",
                            "Warping over! I was way out there.",
                            "Teleported! Don't leave me that far again!",
                    });
                } else if (dist > 4.0) {
                    companion.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), 1.4);
                    respondRandom(player, new String[]{
                            "Coming to you!",
                            "On my way!",
                            "Running over!",
                            "Be right there!",
                    });
                } else {
                    respondRandom(player, new String[]{
                            "I'm already right here!",
                            "I'm standing next to you...",
                            "I couldn't be any closer, boss!",
                            "Right here! Did you need something?",
                    });
                }
                return true;
            }

            // Health check
            if (HEALTH_PATTERN.matcher(msg).find()) {
                float hp = companion.getHealth();
                float maxHp = companion.getMaxHealth();
                float pct = (hp / maxHp) * 100f;
                String status;
                if (pct >= 90) status = "I'm doing great! Feeling strong.";
                else if (pct >= 60) status = "I'm a bit scuffed but fine. Nothing I can't handle.";
                else if (pct >= 30) status = "I've been better... could use some rest or food.";
                else status = "I'm in rough shape! Please help!";
                respond(player, String.format("Health: %.0f/%.0f (%.0f%%) — %s", hp, maxHp, pct, status));
                return true;
            }

            // Equip
            if (EQUIP_PATTERN.matcher(msg).matches()) {
                companion.autoEquipBestGear();
                respondRandom(player, new String[]{
                        "Equipped my best gear! Ready for action.",
                        "Geared up! Looking good, right?",
                        "Suited up with the best I've got!",
                        "Armed and ready!",
                        "All geared up, let's go!",
                });
                return true;
            }
        }

        // ---- Tool-based commands (run on background thread) ----
        // Order matters: more specific patterns first, then generic ones.

        // Strip mine (check BEFORE generic mine)
        Matcher stripMatcher = STRIP_MINE_PATTERN.matcher(msg);
        if (stripMatcher.matches()) {
            return handleStripMine(stripMatcher, player, companion);
        }

        // Tunnel (alias for strip mine)
        Matcher tunnelMatcher = TUNNEL_PATTERN.matcher(msg);
        if (tunnelMatcher.matches()) {
            String oreStr = tunnelMatcher.group(1);
            JsonObject args = new JsonObject();
            if (oreStr != null) {
                String ore = resolveOre(oreStr.trim());
                if (ore != null) args.addProperty("ore", ore);
            }
            executeToolAsync("strip_mine", args, player, companion, "Digging a tunnel...");
            return true;
        }

        // Create mine
        if (CREATE_MINE_PATTERN.matcher(msg).matches()) {
            executeToolAsync("create_mine", new JsonObject(), player, companion, "Setting up a mine...");
            return true;
        }

        // Dig down
        if (DIG_DOWN_PATTERN.matcher(msg).find()) {
            executeToolAsync("dig_down", new JsonObject(), player, companion, "Digging down...");
            return true;
        }

        // Recipe lookup (check before craft so "how do i make a pickaxe" doesn't trigger craft)
        Matcher recipeMatcher = RECIPE_PATTERN.matcher(msg);
        if (recipeMatcher.find()) {
            String itemStr = null;
            for (int g = 1; g <= recipeMatcher.groupCount(); g++) {
                if (recipeMatcher.group(g) != null) { itemStr = recipeMatcher.group(g).trim(); break; }
            }
            if (itemStr != null) {
                // Strip trailing punctuation that may have survived
                itemStr = itemStr.replaceAll("[?.!]+$", "").trim();
                String item = resolveItem(itemStr);
                if (item == null) item = normalizeItemName(itemStr);
                JsonObject args = new JsonObject();
                args.addProperty("item", item);
                executeToolAsync("get_recipe", args, player, companion, null);
                return true;
            }
        }

        // "i want to make/craft ..."
        Matcher wantToMakeMatcher = WANT_TO_MAKE_PATTERN.matcher(msg);
        if (wantToMakeMatcher.matches()) {
            String itemStr = wantToMakeMatcher.group(2).trim();
            String item = resolveItem(itemStr);
            if (item == null) item = normalizeItemName(itemStr);
            return handleCraftDirectly(item, wantToMakeMatcher.group(1), player, companion);
        }

        // Mine ores (check against ore list to avoid false positives)
        Matcher mineMatcher = MINE_PATTERN.matcher(msg);
        if (mineMatcher.matches()) {
            if (handleMineOre(mineMatcher, player, companion)) return true;
        }

        // "i need [ore]"
        Matcher needOreMatcher = NEED_ORE_PATTERN.matcher(msg);
        if (needOreMatcher.matches()) {
            String oreStr = needOreMatcher.group(2).trim();
            String ore = resolveOre(oreStr);
            if (ore != null) {
                return handleMineOreDirectly(ore, needOreMatcher.group(1), player, companion);
            }
            // Could be "i need a pickaxe" — try craft
            String item = resolveItem(oreStr);
            if (item != null) {
                return handleCraftDirectly(item, needOreMatcher.group(1), player, companion);
            }
        }

        // "get me some [ore]"
        Matcher getOreMatcher = GET_ORE_PATTERN.matcher(msg);
        if (getOreMatcher.matches()) {
            String oreStr = getOreMatcher.group(2).trim();
            String ore = resolveOre(oreStr);
            if (ore != null) {
                return handleMineOreDirectly(ore, getOreMatcher.group(1), player, companion);
            }
        }

        // Craft
        Matcher craftMatcher = CRAFT_PATTERN.matcher(msg);
        if (craftMatcher.matches()) {
            String itemStr = craftMatcher.group(2).trim();
            // Don't trigger craft for things that aren't items (e.g., "build a house")
            String item = resolveItem(itemStr);
            if (item == null) item = normalizeItemName(itemStr);
            return handleCraftDirectly(item, craftMatcher.group(1), player, companion);
        }

        // "i need [item]" — if not an ore, try crafting
        Matcher needItemMatcher = NEED_ITEM_PATTERN.matcher(msg);
        if (needItemMatcher.matches()) {
            String itemStr = needItemMatcher.group(2).trim();
            String ore = resolveOre(itemStr);
            if (ore != null) {
                return handleMineOreDirectly(ore, needItemMatcher.group(1), player, companion);
            }
            String item = resolveItem(itemStr);
            if (item == null) item = normalizeItemName(itemStr);
            return handleCraftDirectly(item, needItemMatcher.group(1), player, companion);
        }

        // Chop trees
        Matcher chopMatcher = CHOP_PATTERN.matcher(msg);
        if (chopMatcher.matches()) {
            return handleChop(chopMatcher.group(1), player, companion);
        }

        // Get wood (alias)
        Matcher getWoodMatcher = GET_WOOD_PATTERN.matcher(msg);
        if (getWoodMatcher.matches()) {
            return handleChop(getWoodMatcher.group(1), player, companion);
        }

        // Need wood
        Matcher needWoodMatcher = NEED_WOOD_PATTERN.matcher(msg);
        if (needWoodMatcher.matches()) {
            return handleChop(needWoodMatcher.group(1), player, companion);
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
            executeToolAsync("smelt_items", args, player, companion, "Smelting " + itemStr + "...");
            return true;
        }

        // Kill / Attack
        Matcher killMatcher = KILL_PATTERN.matcher(msg);
        if (killMatcher.matches()) {
            String mobStr = killMatcher.group(1).trim();
            String mob = resolveMob(mobStr);
            if (mob != null) {
                JsonObject args = new JsonObject();
                args.addProperty("mob", mob);
                executeToolAsync("kill_mob", args, player, companion,
                        "Going after the " + mobStr + "!");
                return true;
            }
        }

        // Scan containers
        if (SCAN_CONTAINERS_PATTERN.matcher(msg).find()) {
            executeToolAsync("scan_containers", new JsonObject(), player, companion,
                    "Checking nearby containers...");
            return true;
        }

        // Bookmark location
        if (BOOKMARK_PATTERN.matcher(msg).find()) {
            JsonObject args = new JsonObject();
            args.addProperty("name", "Bookmark");
            executeToolAsync("bookmark_location", args, player, companion,
                    "Bookmarking this location!");
            return true;
        }

        // Rename
        Matcher renameMatcher = RENAME_PATTERN.matcher(msg);
        if (renameMatcher.matches()) {
            String newName = renameMatcher.group(1).trim();
            if (!newName.isEmpty() && newName.length() <= 32) {
                JsonObject args = new JsonObject();
                args.addProperty("name", newName);
                executeToolAsync("rename_companion", args, player, companion,
                        "Changing my name...");
                return true;
            }
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

        // Fishing
        if (FISH_PATTERN.matcher(msg).find()) {
            executeToolAsync("go_fishing", new JsonObject(), player, companion,
                    "Heading to the water!");
            return true;
        }

        // Farm
        if (FARM_PATTERN.matcher(msg).find()) {
            executeToolAsync("farm_area", new JsonObject(), player, companion,
                    "Working the fields...");
            return true;
        }

        // Status
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
                    respond(player, "Nothing to deposit, or no tagged storage found. Use the Logistics Wand to mark a chest!");
                }
            } else {
                respond(player, "I need to be summoned first!");
            }
            return true;
        }

        // Transfer to container
        if (TRANSFER_PATTERN.matcher(msg).find()) {
            if (companion != null) {
                int deposited = com.apocscode.mcai.logistics.ItemRoutingHelper.routeAllCompanionItems(companion);
                if (deposited > 0) {
                    respond(player, "Transferred " + deposited + " item(s) to storage.");
                } else {
                    respond(player, "Nothing to transfer, or no tagged storage containers found.");
                }
            }
            return true;
        }

        // Deliver items (give to player)
        if (DELIVER_PATTERN.matcher(msg).find()) {
            JsonObject args = new JsonObject();
            args.addProperty("target", "player");
            executeToolAsync("deliver_items", args, player, companion,
                    "Bringing everything to you!");
            return true;
        }

        // Guard
        if (GUARD_PATTERN.matcher(msg).find()) {
            JsonObject args = new JsonObject();
            args.addProperty("radius", 16);
            executeToolAsync("guard_area", args, player, companion,
                    "Guarding this area! Nothing gets past me.");
            return true;
        }

        // Emote
        Matcher emoteMatcher = EMOTE_PATTERN.matcher(msg);
        if (emoteMatcher.matches()) {
            JsonObject args = new JsonObject();
            String emoteStr = msg.replaceAll("(?i)(?:can|could|would) you\\s+", "")
                    .replaceAll("(?i)please\\s+", "")
                    .replaceAll("(?i)do an?\\s+", "").trim();
            args.addProperty("emote", emoteStr.toLowerCase());
            executeToolAsync("emote", args, player, companion, null);
            return true;
        }

        // Build structure
        Matcher buildMatcher = BUILD_PATTERN.matcher(msg);
        if (buildMatcher.matches()) {
            String structure = buildMatcher.group(1).trim();
            JsonObject args = new JsonObject();
            args.addProperty("structure", normalizeItemName(structure));
            executeToolAsync("build_structure", args, player, companion,
                    "Building " + structure + "...");
            return true;
        }

        // Trade with villager
        if (TRADE_PATTERN.matcher(msg).find()) {
            executeToolAsync("villager_trade", new JsonObject(), player, companion,
                    "Looking for a villager to trade with...");
            return true;
        }

        // Gather blocks (catch-all for "gather cobblestone", "collect sand", etc.)
        Matcher gatherMatcher = GATHER_PATTERN.matcher(msg);
        if (gatherMatcher.matches()) {
            String countStr = gatherMatcher.group(1);
            String blockStr = gatherMatcher.group(2).trim();
            // Check if this is actually an ore
            String ore = resolveOre(blockStr);
            if (ore != null) {
                return handleMineOreDirectly(ore, countStr, player, companion);
            }
            JsonObject args = new JsonObject();
            args.addProperty("block", normalizeItemName(blockStr));
            if (countStr != null) args.addProperty("count", Integer.parseInt(countStr));
            executeToolAsync("gather_blocks", args, player, companion,
                    "Gathering " + blockStr + "...");
            return true;
        }

        // Fetch item (find and bring an item from containers) — generic, check last
        Matcher fetchMatcher = FETCH_PATTERN.matcher(msg);
        if (fetchMatcher.matches()) {
            String itemStr = fetchMatcher.group(2).trim();
            // Don't match if it's clearly a behavioral thing
            if (!itemStr.matches("(?i)here|there|back|over|me")) {
                String item = resolveItem(itemStr);
                if (item == null) item = normalizeItemName(itemStr);
                JsonObject args = new JsonObject();
                args.addProperty("item", item);
                if (fetchMatcher.group(1) != null) args.addProperty("count", Integer.parseInt(fetchMatcher.group(1)));
                executeToolAsync("find_and_fetch_item", args, player, companion,
                        "Looking for " + itemStr + "...");
                return true;
            }
        }

        // Mods list
        if (MODS_PATTERN.matcher(msg).find()) {
            executeToolAsync("list_installed_mods", new JsonObject(), player, companion, null);
            return true;
        }

        // ---- Fuzzy matching fallback ----
        // If no exact pattern matched, try fuzzy-matching common command words
        String fuzzyResult = tryFuzzyMatch(msg);
        if (fuzzyResult != null) {
            MCAi.LOGGER.info("CommandParser: fuzzy matched '{}' → '{}'", msg, fuzzyResult);
            // Prevent infinite recursion: only recurse once
            return tryParseDirect(fuzzyResult, player, companion);
        }

        // No match — fall through to AI
        return false;
    }

    /**
     * tryParse without fuzzy fallback (prevents recursion).
     */
    private static boolean tryParseDirect(String message, ServerPlayer player, @Nullable CompanionEntity companion) {
        // Temporarily save and call tryParse but mark as fuzzy attempt
        // Instead, we just inline the critical parts - run the message through patterns only
        // Simple approach: set a flag on thread local to prevent recursive fuzzy
        fuzzyRecursionGuard.set(true);
        try {
            return tryParse(message, player, companion);
        } finally {
            fuzzyRecursionGuard.set(false);
        }
    }

    private static final ThreadLocal<Boolean> fuzzyRecursionGuard = ThreadLocal.withInitial(() -> false);

    // ==================== Command handlers ====================

    private static boolean handleStripMine(Matcher matcher, ServerPlayer player,
                                            @Nullable CompanionEntity companion) {
        String countStr = matcher.group(1);
        String oreStr = matcher.group(2).trim();
        String ore = resolveOre(oreStr);
        if (ore == null) ore = oreStr.toLowerCase().replace(" ", "_");

        JsonObject args = new JsonObject();
        args.addProperty("ore", ore);
        if (countStr != null) args.addProperty("count", Integer.parseInt(countStr));

        executeToolAsync("strip_mine", args, player, companion,
                "Strip mining for " + ore.replace('_', ' ') + "... Let's find some!");
        return true;
    }

    private static boolean handleMineOre(Matcher matcher, ServerPlayer player,
                                          @Nullable CompanionEntity companion) {
        String countStr = matcher.group(1);
        String oreStr = matcher.group(2).trim();
        String ore = resolveOre(oreStr);

        if (ore != null) {
            return handleMineOreDirectly(ore, countStr, player, companion);
        }
        return false;
    }

    private static boolean handleMineOreDirectly(String ore, @Nullable String countStr,
                                                  ServerPlayer player, @Nullable CompanionEntity companion) {
        JsonObject args = new JsonObject();
        args.addProperty("ore", ore);
        if (countStr != null) args.addProperty("maxOres", Integer.parseInt(countStr));
        executeToolAsync("mine_ores", args, player, companion,
                "Mining " + ore.replace('_', ' ') + "...");
        return true;
    }

    private static boolean handleCraftDirectly(String item, @Nullable String countStr,
                                                ServerPlayer player, @Nullable CompanionEntity companion) {
        JsonObject args = new JsonObject();
        args.addProperty("item", item);
        if (countStr != null) args.addProperty("count", Integer.parseInt(countStr));
        executeToolAsync("craft_item", args, player, companion,
                "Crafting " + item.replace('_', ' ') + "...");
        return true;
    }

    private static boolean handleChop(@Nullable String countStr, ServerPlayer player,
                                       @Nullable CompanionEntity companion) {
        JsonObject args = new JsonObject();
        if (countStr != null) args.addProperty("maxLogs", Integer.parseInt(countStr));
        executeToolAsync("chop_trees", args, player, companion,
                "Chopping trees!");
        return true;
    }

    // ==================== Fuzzy matching ====================

    /** Command keywords and their canonical forms for fuzzy match */
    private static final Map<String, String> FUZZY_KEYWORDS = new LinkedHashMap<>();
    static {
        // Mining
        FUZZY_KEYWORDS.put("mine", "mine");
        FUZZY_KEYWORDS.put("mining", "mine");
        FUZZY_KEYWORDS.put("excavate", "mine");
        FUZZY_KEYWORDS.put("dig", "dig down");

        // Crafting
        FUZZY_KEYWORDS.put("craft", "craft");
        FUZZY_KEYWORDS.put("crafting", "craft");
        FUZZY_KEYWORDS.put("make", "craft");
        FUZZY_KEYWORDS.put("making", "craft");
        FUZZY_KEYWORDS.put("create", "craft");
        FUZZY_KEYWORDS.put("forge", "craft");
        FUZZY_KEYWORDS.put("build", "build");
        FUZZY_KEYWORDS.put("construct", "build");

        // Wood
        FUZZY_KEYWORDS.put("chop", "chop trees");
        FUZZY_KEYWORDS.put("chopping", "chop trees");
        FUZZY_KEYWORDS.put("lumber", "chop trees");

        // Smelting
        FUZZY_KEYWORDS.put("smelt", "smelt");
        FUZZY_KEYWORDS.put("smelting", "smelt");
        FUZZY_KEYWORDS.put("cook", "smelt");
        FUZZY_KEYWORDS.put("melt", "smelt");
        FUZZY_KEYWORDS.put("refine", "smelt");

        // Combat
        FUZZY_KEYWORDS.put("kill", "kill");
        FUZZY_KEYWORDS.put("attack", "kill");
        FUZZY_KEYWORDS.put("fight", "kill");
        FUZZY_KEYWORDS.put("slay", "kill");

        // Behavior
        FUZZY_KEYWORDS.put("follow", "follow me");
        FUZZY_KEYWORDS.put("stay", "stay");
        FUZZY_KEYWORDS.put("wait", "stay");
        FUZZY_KEYWORDS.put("cancel", "cancel");
        FUZZY_KEYWORDS.put("stop", "cancel");
        FUZZY_KEYWORDS.put("abort", "cancel");
        FUZZY_KEYWORDS.put("nevermind", "cancel");

        // Activities
        FUZZY_KEYWORDS.put("fish", "go fishing");
        FUZZY_KEYWORDS.put("fishing", "go fishing");
        FUZZY_KEYWORDS.put("farm", "farm");
        FUZZY_KEYWORDS.put("farming", "farm");
        FUZZY_KEYWORDS.put("guard", "guard");
        FUZZY_KEYWORDS.put("protect", "guard");
        FUZZY_KEYWORDS.put("patrol", "guard");

        // Logistics
        FUZZY_KEYWORDS.put("deposit", "deposit items");
        FUZZY_KEYWORDS.put("store", "deposit items");
        FUZZY_KEYWORDS.put("stash", "deposit items");
        FUZZY_KEYWORDS.put("inventory", "check inventory");

        // Info
        FUZZY_KEYWORDS.put("status", "status");
        FUZZY_KEYWORDS.put("help", "help");
        FUZZY_KEYWORDS.put("recipe", "recipe");
        FUZZY_KEYWORDS.put("scan", "scan area");

        // Other
        FUZZY_KEYWORDS.put("bookmark", "bookmark this location");
        FUZZY_KEYWORDS.put("equip", "equip");
        FUZZY_KEYWORDS.put("dance", "dance");
        FUZZY_KEYWORDS.put("wave", "wave");
        FUZZY_KEYWORDS.put("trade", "trade with villager");
    }

    /**
     * Try to fuzzy-match a message by finding words that are close (Levenshtein distance <= 2)
     * to known command keywords. Returns a corrected message, or null.
     */
    @Nullable
    private static String tryFuzzyMatch(String msg) {
        // Don't recurse
        if (fuzzyRecursionGuard.get()) return null;

        String[] words = msg.toLowerCase().split("\\s+");

        // For single-word messages, check against keywords directly
        if (words.length == 1) {
            String best = fuzzyFindKeyword(words[0]);
            if (best != null && !best.equals(words[0])) {
                return FUZZY_KEYWORDS.get(best);
            }
            return null;
        }

        // For multi-word messages, try correcting individual words
        boolean anyFixed = false;
        String[] corrected = new String[words.length];
        for (int i = 0; i < words.length; i++) {
            corrected[i] = words[i];

            // Try keyword correction
            String fix = fuzzyFindKeyword(words[i]);
            if (fix != null && !fix.equals(words[i])) {
                corrected[i] = fix;
                anyFixed = true;
                continue;
            }

            // Try ore name correction
            String oreFix = fuzzyFindInMap(words[i], ORE_ALIASES);
            if (oreFix != null && !oreFix.equals(words[i])) {
                corrected[i] = oreFix;
                anyFixed = true;
                continue;
            }

            // Try item name correction (single-word items only)
            String itemFix = fuzzyFindInMap(words[i], ITEM_ALIASES);
            if (itemFix != null && !itemFix.equals(words[i])) {
                corrected[i] = itemFix;
                anyFixed = true;
            }
        }

        if (anyFixed) {
            return String.join(" ", corrected);
        }
        return null;
    }

    /**
     * Find the closest keyword within Levenshtein distance 2.
     */
    @Nullable
    private static String fuzzyFindKeyword(String word) {
        if (word.length() < 3) return null;
        if (FUZZY_KEYWORDS.containsKey(word)) return word;

        int bestDist = 3; // threshold
        String bestMatch = null;
        for (String keyword : FUZZY_KEYWORDS.keySet()) {
            int dist = levenshtein(word, keyword);
            if (dist < bestDist) {
                bestDist = dist;
                bestMatch = keyword;
            }
        }
        return bestMatch;
    }

    /**
     * Find the closest key in a map within Levenshtein distance 2.
     */
    @Nullable
    private static String fuzzyFindInMap(String word, Map<String, String> map) {
        if (word.length() < 3) return null;
        if (map.containsKey(word)) return word;

        int bestDist = 3;
        String bestMatch = null;
        for (String key : map.keySet()) {
            if (key.contains(" ")) continue; // Skip multi-word keys for per-word fuzzy
            int dist = levenshtein(word, key);
            if (dist < bestDist) {
                bestDist = dist;
                bestMatch = key;
            }
        }
        return bestMatch;
    }

    /**
     * Levenshtein edit distance between two strings.
     */
    private static int levenshtein(String a, String b) {
        int lenA = a.length(), lenB = b.length();
        // Quick reject: if length difference > 2, distance must be > 2
        if (Math.abs(lenA - lenB) > 2) return 3;

        int[][] dp = new int[lenA + 1][lenB + 1];
        for (int i = 0; i <= lenA; i++) dp[i][0] = i;
        for (int j = 0; j <= lenB; j++) dp[0][j] = j;
        for (int i = 1; i <= lenA; i++) {
            for (int j = 1; j <= lenB; j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[lenA][lenB];
    }

    // ==================== Text cleaning helpers ====================

    /**
     * Strip common filler/prefix words that don't affect command meaning.
     * "can you please go and mine some iron" → "mine some iron"
     * "i want you to craft me a pickaxe" → "craft me a pickaxe"
     */
    private static String stripFillerPrefixes(String msg) {
        return msg.replaceAll("(?i)^(?:(?:can|could|would|will) you\\s+)?", "")
                  .replaceAll("(?i)^(?:please\\s+)?", "")
                  .replaceAll("(?i)^(?:i (?:want|need|would like) (?:you )?to\\s+)?", "")
                  .replaceAll("(?i)^(?:go (?:and )?)?", "")
                  .trim();
    }

    // ==================== Name resolution helpers ====================

    /**
     * Resolve an ore name from player input to the canonical name used by tools.
     * Handles direct matches, stripped suffixes, and fuzzy matching.
     */
    @Nullable
    private static String resolveOre(String input) {
        String lower = input.toLowerCase().trim();
        // Direct match
        if (ORE_ALIASES.containsKey(lower)) return ORE_ALIASES.get(lower);
        // Strip "ore" / "ores" suffix
        String stripped = lower.replaceAll("\\s*ores?$", "").trim();
        if (ORE_ALIASES.containsKey(stripped)) return ORE_ALIASES.get(stripped);
        // Fuzzy match
        String fuzzy = fuzzyFindInMap(lower, ORE_ALIASES);
        if (fuzzy != null) return ORE_ALIASES.get(fuzzy);
        return null;
    }

    /**
     * Resolve an item name from player input using the alias table.
     * Returns minecraft path (e.g., "iron_pickaxe") or null if no alias found.
     */
    @Nullable
    private static String resolveItem(String input) {
        String lower = input.toLowerCase().trim();
        if (ITEM_ALIASES.containsKey(lower)) return ITEM_ALIASES.get(lower);
        // Strip leading "a", "an", "the"
        String stripped = lower.replaceAll("^(?:an?|the|some)\\s+", "");
        if (ITEM_ALIASES.containsKey(stripped)) return ITEM_ALIASES.get(stripped);
        // Fuzzy match
        String fuzzy = fuzzyFindInMap(stripped, ITEM_ALIASES);
        if (fuzzy != null) return ITEM_ALIASES.get(fuzzy);
        return null;
    }

    /**
     * Resolve a mob name from player input.
     */
    @Nullable
    private static String resolveMob(String input) {
        String lower = input.toLowerCase().trim();
        if (MOB_ALIASES.containsKey(lower)) return MOB_ALIASES.get(lower);
        // Strip leading "the", "that", "a"
        String stripped = lower.replaceAll("^(?:the|that|those|an?|every|all(?: the)?)\\s+", "");
        if (MOB_ALIASES.containsKey(stripped)) return MOB_ALIASES.get(stripped);
        // Fuzzy match
        String fuzzy = fuzzyFindInMap(stripped, MOB_ALIASES);
        if (fuzzy != null) return MOB_ALIASES.get(fuzzy);
        return null;
    }

    /**
     * Normalize an item name from player input to minecraft resource path format.
     * "iron pickaxe" → "iron_pickaxe", "Stone Bricks" → "stone_bricks"
     */
    private static String normalizeItemName(String input) {
        return input.toLowerCase()
                .trim()
                .replaceAll("^(?:an?|the|some)\\s+", "")
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-z0-9_]", "");
    }

    // ==================== Tool execution ====================

    /**
     * Execute a tool asynchronously on the background thread.
     * Sends a "working on it" message immediately, then sends the tool result when done.
     */
    private static void executeToolAsync(String toolName, JsonObject args, ServerPlayer player,
                                          @Nullable CompanionEntity companion, @Nullable String workingMessage) {
        // Check tool is enabled
        if (!AiConfig.isToolEnabled(toolName)) {
            respondRandom(player, CANT_DO_RESPONSES);
            return;
        }

        AiTool tool = ToolRegistry.get(toolName);
        if (tool == null) {
            MCAi.LOGGER.warn("CommandParser: tool '{}' not found in registry", toolName);
            respondRandom(player, CANT_DO_RESPONSES);
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

        // Run on background thread
        executor.submit(() -> {
            try {
                ToolContext ctx = new ToolContext(player, player.getServer());
                long startMs = System.currentTimeMillis();
                String result = tool.execute(args, ctx);
                long elapsed = System.currentTimeMillis() - startMs;

                AiLogger.toolResult(toolName, result, elapsed);
                MCAi.LOGGER.info("CommandParser: {} completed in {}ms", toolName, elapsed);

                // Add result to conversation history
                ConversationManager.addSystemMessage("[Tool result: " + (result != null ? result : "done") + "]");

                // Send result to player
                if (result != null && !result.contains("[ASYNC_TASK]")) {
                    String cleanResult = result.trim();
                    if (!cleanResult.isEmpty()) {
                        player.getServer().execute(() ->
                                PacketDistributor.sendToPlayer(player, new ChatResponsePacket(cleanResult)));
                    }
                } else if (result != null) {
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
                                "Hmm, that didn't work: " + e.getMessage())));
            }
        });
    }

    // ==================== Response helpers ====================

    /**
     * Send a response to the player via ChatResponsePacket.
     */
    private static void respond(ServerPlayer player, String message) {
        player.getServer().execute(() ->
                PacketDistributor.sendToPlayer(player, new ChatResponsePacket(message)));
    }

    /**
     * Send a random personality response from a pool.
     */
    private static void respondRandom(ServerPlayer player, String[] responses) {
        String msg = responses[ThreadLocalRandom.current().nextInt(responses.length)];
        respond(player, msg);
    }
}
