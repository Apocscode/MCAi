package com.apocscode.mcai.ai.planner;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.planner.RecipeResolver.DependencyNode;
import com.apocscode.mcai.ai.planner.RecipeResolver.StepType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.*;

/**
 * Converts a RecipeResolver dependency tree into an ordered list of
 * executable steps, grouped by category.
 *
 * The plan merges duplicate gather/mine/chop tasks and orders them:
 *   1. CHOP (get wood — needed for tools + crafting table)
 *   2. GATHER (stone, sand, etc.)
 *   3. MINE (ores)
 *   4. KILL_MOB / FISH / FARM
 *   5. SMELT / BLAST / SMOKE (process raw materials)
 *   6. CRAFT (bottom-up: intermediates first, then target)
 *
 * Each step can be converted to a tool call for the AI continuation system.
 */
public class CraftingPlan {

    /** A single executable step in the plan. */
    public static class Step {
        public final StepType type;
        public final Item item;
        public final int count;
        public final String itemId;
        public final String displayName;

        public Step(StepType type, Item item, int count) {
            this.type = type;
            this.item = item;
            this.count = count;
            this.itemId = BuiltInRegistries.ITEM.getKey(item).getPath();
            this.displayName = item.getDescription().getString();
        }

        @Override
        public String toString() {
            return type + " " + itemId + " x" + count;
        }

        /**
         * Convert this step to a tool call string the AI can execute.
         * @param remainingPlan Description of what comes after this step
         */
        public String toToolCall(String remainingPlan) {
            return switch (type) {
                case CHOP -> String.format(
                        "chop_trees({\"maxLogs\":%d,\"plan\":\"%s\"})", count, remainingPlan);
                case MINE -> String.format(
                        "mine_ores({\"ore\":\"%s\",\"maxOres\":%d,\"plan\":\"%s\"})",
                        itemId, count, remainingPlan);
                case GATHER -> String.format(
                        "gather_blocks({\"block\":\"%s\",\"maxBlocks\":%d,\"plan\":\"%s\"})",
                        itemId, count, remainingPlan);
                case SMELT, BLAST -> String.format(
                        "smelt_items({\"item\":\"%s\",\"count\":%d,\"plan\":\"%s\"})",
                        itemId, count, remainingPlan);
                case SMOKE, CAMPFIRE_COOK -> String.format(
                        "smelt_items({\"item\":\"%s\",\"count\":%d,\"plan\":\"%s\"})",
                        itemId, count, remainingPlan);
                case CRAFT -> String.format(
                        "craft_item({\"item\":\"%s\",\"count\":%d})", itemId, count);
                case FARM -> String.format(
                        "gather_blocks({\"block\":\"%s\",\"maxBlocks\":%d,\"plan\":\"%s\"})",
                        itemId, count, remainingPlan);
                case FISH -> String.format(
                        "go_fishing({\"maxFish\":%d,\"plan\":\"%s\"})", count, remainingPlan);
                case KILL_MOB -> {
                    // Resolve which mob to kill for this item drop
                    String mob = resolveMobForDrop(itemId);
                    yield String.format(
                            "kill_mob({\"mob\":\"%s\",\"count\":%d,\"plan\":\"%s\"})",
                            mob, count, remainingPlan);
                }
                default -> "Unknown step: " + itemId;
            };
        }

        /** Is this step an async task (companion does it over time)? */
        public boolean isAsync() {
            return switch (type) {
                case CHOP, MINE, GATHER, SMELT, BLAST, SMOKE, CAMPFIRE_COOK, FARM, FISH, KILL_MOB -> true;
                case CRAFT, STONECUT, AVAILABLE, UNKNOWN -> false;
            };
        }
    }

    // ========== Plan fields ==========

    private final List<Step> steps;
    private final Item targetItem;
    private final int targetCount;

    private CraftingPlan(List<Step> steps, Item targetItem, int targetCount) {
        this.steps = steps;
        this.targetItem = targetItem;
        this.targetCount = targetCount;
    }

    public List<Step> getSteps() { return steps; }
    public boolean isEmpty() { return steps.isEmpty(); }
    public int size() { return steps.size(); }

    /** Get only the gathering/async steps (no CRAFTs — those are inline). */
    public List<Step> getAsyncSteps() {
        return steps.stream().filter(Step::isAsync).toList();
    }

    /** Get the sync craft steps. */
    public List<Step> getCraftSteps() {
        return steps.stream().filter(s -> s.type == StepType.CRAFT).toList();
    }

    // ========== Difficulty Analysis ==========

    /**
     * Difficulty levels for crafting plan steps.
     * Used to warn the player about challenging/impossible requirements.
     */
    public enum Difficulty {
        EASY,       // Normal overworld gathering (chop, mine coal/iron, farm wheat)
        MODERATE,   // Needs specific biomes or uncommon resources
        HARD,       // Dangerous combat or rare materials
        EXTREME,    // Requires Nether/End access or underwater monuments
        IMPOSSIBLE  // Cannot be automated (shears interaction, etc.)
    }

    /**
     * A single difficulty warning about a step in the plan.
     */
    public record DifficultyWarning(Difficulty level, String itemId, String warning) {}

    /**
     * Analyze the plan for difficulty warnings.
     * Returns a list of warnings about dangerous, rare, or impossible steps.
     * Used to inform the player in chat before Jim attempts the plan.
     */
    public List<DifficultyWarning> analyzeDifficulty() {
        List<DifficultyWarning> warnings = new ArrayList<>();

        for (Step step : steps) {
            // Check UNKNOWN steps — these are dead ends
            if (step.type == StepType.UNKNOWN) {
                String advice = getUnknownItemAdvice(step.itemId);
                warnings.add(new DifficultyWarning(Difficulty.IMPOSSIBLE, step.itemId,
                        "I don't know how to get " + step.displayName + ". " + advice));
                continue;
            }

            // Check KILL_MOB steps for difficulty
            if (step.type == StepType.KILL_MOB) {
                String mob = resolveMobForDrop(step.itemId);
                DifficultyWarning mobWarning = assessMobDifficulty(step.itemId, mob, step.count);
                if (mobWarning != null) warnings.add(mobWarning);
                continue;
            }

            // Check MINE steps for Nether/End/rare materials
            if (step.type == StepType.MINE) {
                DifficultyWarning mineWarning = assessMineDifficulty(step.itemId, step.count);
                if (mineWarning != null) warnings.add(mineWarning);
                continue;
            }

            // Check GATHER steps for dimension-locked blocks
            if (step.type == StepType.GATHER) {
                DifficultyWarning gatherWarning = assessGatherDifficulty(step.itemId, step.count);
                if (gatherWarning != null) warnings.add(gatherWarning);
                continue;
            }

            // Check FARM steps for Nether/End crops
            if (step.type == StepType.FARM) {
                DifficultyWarning farmWarning = assessFarmDifficulty(step.itemId);
                if (farmWarning != null) warnings.add(farmWarning);
            }

            // Large material counts
            if (step.count >= 20 && step.isAsync()) {
                warnings.add(new DifficultyWarning(Difficulty.MODERATE, step.itemId,
                        "Need " + step.count + "x " + step.displayName + " — this will take a while!"));
            }
        }

        return warnings;
    }

    /**
     * Get the highest difficulty level in the warnings list.
     */
    public static Difficulty getMaxDifficulty(List<DifficultyWarning> warnings) {
        Difficulty max = Difficulty.EASY;
        for (DifficultyWarning w : warnings) {
            if (w.level.ordinal() > max.ordinal()) max = w.level;
        }
        return max;
    }

    /**
     * Format difficulty warnings as a chat-friendly string.
     */
    public static String formatWarnings(List<DifficultyWarning> warnings) {
        if (warnings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (DifficultyWarning w : warnings) {
            String prefix = switch (w.level) {
                case IMPOSSIBLE -> "§c[IMPOSSIBLE]§r ";
                case EXTREME -> "§c[DANGEROUS]§r ";
                case HARD -> "§e[WARNING]§r ";
                case MODERATE -> "§6[NOTE]§r ";
                case EASY -> "";
            };
            sb.append("\n  ").append(prefix).append(w.warning);
        }
        return sb.toString();
    }

    // ========== Difficulty assessment helpers ==========

    private static DifficultyWarning assessMobDifficulty(String itemId, String mob, int count) {
        return switch (mob) {
            // Nether-only mobs
            case "blaze" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Blaze only spawns in Nether Fortresses — I can't get there on my own. " +
                    "Bring me blaze rods or take me to the Nether!");
            case "ghast" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Ghasts only spawn in the Nether and are very dangerous (fireballs!). " +
                    "I'd need you to take me there and help fight.");
            case "magma_cube" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Magma Cubes mainly spawn in Nether Basalt Deltas. " +
                    "Consider bringing magma cream from the Nether.");
            case "wither_skeleton" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Wither Skeletons only spawn in Nether Fortresses. Very tough fight!");

            // End-only mobs
            case "shulker" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Shulkers only spawn in End Cities. I can't reach The End alone.");
            case "enderman" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Endermen are dangerous — they teleport and hit hard. " +
                    "They spawn at night. I'll try but it's risky (need " + count + ").");
            case "ender_dragon" -> new DifficultyWarning(Difficulty.IMPOSSIBLE, itemId,
                    "This requires defeating the Ender Dragon — a major boss fight in The End!");

            // Boss mobs
            case "wither" -> new DifficultyWarning(Difficulty.IMPOSSIBLE, itemId,
                    "Nether Star requires defeating the Wither boss — extremely dangerous!");
            case "evoker" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Totem of Undying drops from Evokers in Woodland Mansions or raids.");

            // Underwater
            case "guardian" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Guardians only spawn at Ocean Monuments (underwater). " +
                    "I can't swim and fight effectively. Bring me prismarine shards!");
            case "drowned" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Drowned spawn underwater — trident/nautilus shell drops are rare.");

            // Explosive/dangerous overworld
            case "creeper" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Creepers explode! I'll try to fight them at range but it's risky. " +
                    "Need " + count + " gunpowder — nighttime hunting required.");
            case "phantom" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Phantoms are flying mobs that spawn when you haven't slept. " +
                    "Hard to hit — I'll do my best but no guarantees.");

            // 1.21+ mobs
            case "breeze" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Breeze mobs spawn in Trial Chambers — tricky to fight (wind attacks).");
            case "trial_chamber" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Trial chambers are underground structures with mob spawners. " +
                    "Bring good gear and help me fight!");

            // Bee interaction
            case "bee" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "This requires finding a Bee Nest/Beehive. " +
                    "Place a campfire underneath to prevent angry bees!");

            // Frog-related (froglights need frog + magma cube interaction)
            case "temperate_frog", "warm_frog", "cold_frog" ->
                    new DifficultyWarning(Difficulty.EXTREME, itemId,
                            "Froglights require a Frog to eat a Magma Cube in the Nether. " +
                            "Different frog types produce different froglight colors.");
            case "frog" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Frogs spawn in swamp biomes. Breed with slimeballs.");
            case "turtle" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Turtles spawn on beaches. Breed with seagrass — babies drop scute when growing up.");
            case "sniffer" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Sniffer Eggs are found by brushing suspicious sand in ocean ruins.");

            // Mob capture buckets — generally easy
            case "axolotl" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Axolotls spawn in Lush Caves — they can be tricky to find.");

            // Passive/easy mobs (no warning)
            default -> null;
        };
    }

    private static DifficultyWarning assessMineDifficulty(String itemId, int count) {
        return switch (itemId) {
            case "ancient_debris" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Ancient Debris only spawns deep in the Nether (Y=8-22). " +
                    "Extremely rare — needs diamond+ pickaxe. I can't reach the Nether alone.");
            case "quartz" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Nether Quartz only spawns in the Nether. Take me there or bring quartz!");
            case "glowstone_dust" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Glowstone spawns on Nether ceilings. I can't reach the Nether alone.");
            case "amethyst_shard" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Amethyst shards come from geodes underground — they're uncommon. " +
                    "Mining may take a while to find one.");
            case "amethyst_cluster", "small_amethyst_bud", "medium_amethyst_bud",
                 "large_amethyst_bud" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    itemId + " must be mined from Amethyst Geodes with Silk Touch.");
            case "diamond" -> count >= 5
                    ? new DifficultyWarning(Difficulty.HARD, itemId,
                        "Need " + count + " diamonds — that's a lot of deep mining (Y=-64 to 16)!")
                    : null;
            case "emerald" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Emerald ore only spawns in Mountain biomes. May need to travel.");
            // Sculk blocks — deep underground in deep dark biome
            case "sculk", "sculk_vein", "sculk_catalyst", "sculk_sensor", "sculk_shrieker" ->
                    new DifficultyWarning(Difficulty.EXTREME, itemId,
                            itemId + " is found in the Deep Dark (Y=-64 to -1). " +
                            "Wardens live here — extremely dangerous! Bring Silk Touch.");
            default -> null;
        };
    }

    private static DifficultyWarning assessGatherDifficulty(String itemId, int count) {
        return switch (itemId) {
            case "netherrack", "soul_sand", "soul_soil", "basalt", "blackstone",
                 "magma_block", "crying_obsidian", "gilded_blackstone" ->
                    new DifficultyWarning(Difficulty.EXTREME, itemId,
                            itemId + " is a Nether block. I can't reach the Nether alone.");
            case "end_stone" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "End Stone only exists in The End dimension.");
            case "obsidian" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Obsidian needs a diamond pickaxe and 10 seconds per block. " +
                    "Found near lava pools.");
            case "sponge", "wet_sponge" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Sponges are only found in Ocean Monuments (Elder Guardian rooms).");
            case "packed_ice" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Packed Ice is found in Ice Spikes biomes — may need to travel.");
            case "blue_ice" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Blue Ice is found in icebergs — may need to travel to an ocean.");
            case "mycelium" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Mycelium only appears in Mushroom Island biomes — very rare.");
            case "suspicious_sand", "suspicious_gravel" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Suspicious blocks are found in ruins — requires a brush to excavate.");
            // Oxidized copper variants (time-based, no recipe)
            case "exposed_copper", "weathered_copper", "oxidized_copper",
                 "exposed_copper_door", "weathered_copper_door", "oxidized_copper_door",
                 "exposed_copper_trapdoor", "weathered_copper_trapdoor", "oxidized_copper_trapdoor" ->
                    new DifficultyWarning(Difficulty.MODERATE, itemId,
                            itemId + " forms when copper oxidizes over time. Place copper and wait, " +
                            "or use an Axe to scrape wax off waxed variants.");
            // Buckets
            case "water_bucket" -> null;  // Easy — just fill from any water source
            case "lava_bucket" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Lava can be scooped from lava pools (careful, it's dangerous!).");
            case "powder_snow_bucket" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    "Place a cauldron in a snowy biome and wait for it to fill with powder snow.");
            // Misc
            case "bee_nest" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Bee Nests spawn naturally on trees in flower biomes. Silk Touch required.");
            case "dirt_path" -> null;  // Easy — shovel on grass
            case "glow_lichen" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Glow Lichen is found on cave walls — mine with shears.");
            case "shroomlight" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Shroomlight is found in Nether huge fungus trees (crimson/warped forests).");
            default -> {
                if (itemId.contains("coral")) {
                    yield new DifficultyWarning(Difficulty.HARD, itemId,
                            "Coral blocks/items must be gathered from warm ocean biomes with Silk Touch.");
                }
                if (itemId.endsWith("_concrete")) {
                    yield new DifficultyWarning(Difficulty.MODERATE, itemId,
                            "Craft concrete powder (sand + gravel + dye) and drop it in water to make concrete.");
                }
                yield null;
            }
        };
    }

    private static DifficultyWarning assessFarmDifficulty(String itemId) {
        return switch (itemId) {
            case "nether_wart" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Nether Wart only grows in the Nether (on soul sand).");
            case "chorus_fruit", "chorus_plant" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Chorus Plants only grow in The End outer islands.");
            case "wither_rose" -> new DifficultyWarning(Difficulty.EXTREME, itemId,
                    "Wither Rose only drops when the Wither boss kills a mob. Very dangerous!");
            // Nether plants
            case "crimson_fungus", "crimson_nylium", "crimson_roots",
                 "warped_fungus", "warped_nylium", "warped_roots",
                 "warped_wart_block", "nether_sprouts",
                 "twisting_vines", "weeping_vines" ->
                    new DifficultyWarning(Difficulty.EXTREME, itemId,
                            itemId + " is a Nether plant — only found in Nether forests.");
            case "cocoa_beans" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Cocoa beans grow on jungle logs — need a Jungle biome nearby.");
            case "glow_berries" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Glow Berries grow on cave vines underground — can be tricky to find.");
            case "torchflower_seeds", "pitcher_pod" -> new DifficultyWarning(Difficulty.HARD, itemId,
                    itemId + " can only be obtained from Sniffer digging — need to find a Sniffer egg first.");
            case "spore_blossom" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Spore Blossoms are found in Lush Cave ceilings — can be tricky to reach.");
            case "small_dripleaf" -> new DifficultyWarning(Difficulty.MODERATE, itemId,
                    "Small Dripleaf can be obtained from Wandering Traders using emeralds.");
            default -> null;
        };
    }

    /**
     * Give actionable advice for UNKNOWN items that can't be auto-resolved.
     * These are typically interaction-based items with no crafting recipe.
     */
    private static String getUnknownItemAdvice(String itemId) {
        return switch (itemId) {
            case "carved_pumpkin" -> "Use shears on a pumpkin to get a Carved Pumpkin. " +
                    "Give me shears and a pumpkin and I might be able to help!";
            case "suspicious_stew" -> "Craft with a mushroom stew + any flower, or find in shipwrecks.";
            case "player_head" ->
                    "Player heads are unobtainable in normal survival gameplay.";
            case "sponge" -> "Sponges are found in Ocean Monuments (Elder Guardian rooms).";
            case "heart_of_the_sea" -> "Found in Buried Treasure chests (use treasure maps from cartographer villagers).";
            case "elytra" -> "Found in End City ships — very late-game item. Defeat the Ender Dragon first!";
            case "enchanted_golden_apple" -> "Cannot be crafted — only found in dungeon/temple/mineshaft chests.";
            case "name_tag" -> "Found in dungeon chests, fishing, or villager trading (librarian).";
            case "saddle" -> "Found in dungeon chests, fishing, or villager trading (leatherworker).";
            // Decorated pot / pottery (archaeology)
            case "decorated_pot" -> "Craft with bricks or pottery sherds. Sherds come from brushing suspicious blocks.";
            // Powder snow
            case "powder_snow_bucket" -> "Place a cauldron in a snowy biome — it fills with powder snow. " +
                    "Collect with a bucket.";
            // Recovery compass (echo shards from ancient cities)
            case "echo_shard" -> "Echo Shards are found in Ancient City chests in the Deep Dark.";
            // Disc fragments
            case "disc_fragment_5" -> "Disc Fragment 5 is found in Ancient City chests.";
            // Smithing templates
            case "netherite_upgrade_smithing_template" ->
                    "Found in Bastion Remnants (Nether). Can be duplicated: 1 template + 7 diamonds + 1 netherrack → 2.";
            // Structure / loot-only items
            case "bell" -> "Bells are found in villages or can be bought from armorer villagers.";
            case "budding_amethyst" -> "Budding Amethyst is unobtainable — it breaks even with Silk Touch.";
            case "dragon_head" -> "Dragon Heads are found on End Ships in End Cities.";
            case "diamond_horse_armor" -> "Diamond Horse Armor is found in dungeon/temple/fortress chests.";
            case "golden_horse_armor" -> "Golden Horse Armor is found in dungeon/temple/fortress chests.";
            case "iron_horse_armor" -> "Iron Horse Armor is found in dungeon/temple/fortress chests.";
            case "enchanted_book" -> "Enchanted Books come from enchanting, fishing, villager trades, or loot chests.";
            case "experience_bottle" -> "Bottles o' Enchanting come from villager trades (cleric) or loot.";
            case "chipped_anvil" -> "Chipped Anvils result from using an anvil — craft a fresh one instead.";
            case "damaged_anvil" -> "Damaged Anvils result from using an anvil — craft a fresh one instead.";
            case "poisonous_potato" -> "Poisonous Potatoes are a random drop from harvesting potato crops (~2% chance).";
            default -> "This item may need special world interaction or dungeon loot. " +
                    "Try providing it manually.";
        };
    }

    /**
     * Prepend prerequisite steps to the front of the plan.
     * Used for tool prerequisites (e.g., craft stone_pickaxe before mining iron).
     * Prereqs are inserted in their existing order BEFORE the main plan steps.
     * No re-sorting — prereqs must execute first (e.g., craft pickaxe before mining).
     */
    public void prependSteps(List<Step> prereqs) {
        // Prereqs go first (already correctly ordered from fromTree),
        // then the original plan steps follow
        List<Step> merged = new ArrayList<>(prereqs);
        merged.addAll(steps);
        steps.clear();
        steps.addAll(merged);
    }

    // ========== Build plan from dependency tree ==========

    /**
     * Build an ordered execution plan from a dependency tree.
     * Merges duplicate gather tasks, orders by priority.
     */
    public static CraftingPlan fromTree(DependencyNode root) {
        // Flatten the tree (leaves first)
        List<DependencyNode> flat = root.flatten();

        // Merge duplicates: same item + same type → sum counts
        Map<String, Step> merged = new LinkedHashMap<>();
        for (DependencyNode node : flat) {
            if (node.type == StepType.AVAILABLE) continue; // Skip already-available items

            String key = node.type + ":" + node.itemId;
            if (merged.containsKey(key)) {
                Step existing = merged.get(key);
                merged.put(key, new Step(node.type, node.item, existing.count + node.count));
            } else {
                merged.put(key, new Step(node.type, node.item, node.count));
            }
        }

        // Sort by execution priority
        List<Step> sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.comparingInt(s -> typePriority(s.type)));

        return new CraftingPlan(sorted, root.item, root.count);
    }

    /**
     * Priority order — lower number = execute first.
     * Gathering raw materials before processing, processing before crafting.
     */
    private static int typePriority(StepType type) {
        return switch (type) {
            case CHOP -> 10;         // Wood first (tools, crafting table)
            case GATHER -> 20;       // Surface blocks
            case MINE -> 30;         // Ores
            case KILL_MOB -> 40;     // Mob drops
            case FISH -> 40;         // Fishing
            case FARM -> 40;         // Farming
            case SMELT -> 50;        // Smelting
            case BLAST -> 50;        // Blast furnace
            case SMOKE -> 50;        // Smoker
            case CAMPFIRE_COOK -> 50;// Campfire
            case STONECUT -> 55;     // Stonecutting
            case CRAFT -> 60;        // Crafting last
            case AVAILABLE -> 0;     // Already have
            case UNKNOWN -> 99;      // Unknown = last resort
        };
    }

    // ========== Generate continuation plan strings ==========

    /**
     * Build the continuation chain: each async step's plan parameter
     * describes the remaining steps, so they auto-chain via TaskContinuation.
     *
     * Returns the first step's tool call string (to start the chain).
     */
    public String buildContinuationChain() {
        List<Step> allSteps = new ArrayList<>(steps);
        if (allSteps.isEmpty()) return "";

        // Build from the end backwards: last step has no plan,
        // second-to-last step's plan describes the last step, etc.
        String[] toolCalls = new String[allSteps.size()];

        for (int i = allSteps.size() - 1; i >= 0; i--) {
            Step step = allSteps.get(i);
            String remaining = (i < allSteps.size() - 1) ? toolCalls[i + 1] : "";

            // For the remaining plan text, describe remaining steps simply
            StringBuilder plan = new StringBuilder();
            for (int j = i + 1; j < allSteps.size(); j++) {
                if (plan.length() > 0) plan.append(", then ");
                Step s = allSteps.get(j);
                plan.append(s.type.name().toLowerCase()).append(" ").append(s.itemId);
                if (s.count > 1) plan.append(" x").append(s.count);
            }

            toolCalls[i] = step.toToolCall(plan.toString());
        }

        return toolCalls[0];
    }

    /**
     * Generate a human-readable summary of the plan.
     */
    public String summarize() {
        if (steps.isEmpty()) return "No steps needed.";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (i > 0) sb.append(" → ");
            sb.append(s.type.name().toLowerCase());
            sb.append(" ").append(s.displayName);
            if (s.count > 1) sb.append(" x").append(s.count);
        }
        return sb.toString();
    }

    /**
     * Log the plan for debugging.
     */
    public void logPlan() {
        MCAi.LOGGER.info("=== CraftingPlan: {} x{} ===", 
                BuiltInRegistries.ITEM.getKey(targetItem), targetCount);
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            MCAi.LOGGER.info("  Step {}: {} {} x{} {}", 
                    i + 1, s.type, s.itemId, s.count, s.isAsync() ? "[ASYNC]" : "[SYNC]");
        }
    }

    /**
     * Resolve which mob to kill to obtain a specific item drop.
     * Maps item IDs to mob names for the kill_mob tool.
     */
    private static String resolveMobForDrop(String itemId) {
        return switch (itemId) {
            case "leather", "beef" -> "cow";
            case "milk_bucket" -> "cow";  // Right-click cow with bucket
            case "string", "spider_eye" -> "spider";
            case "bone", "bone_meal" -> "skeleton";
            case "gunpowder" -> "creeper";
            case "ender_pearl" -> "enderman";
            case "blaze_rod" -> "blaze";
            case "ghast_tear" -> "ghast";
            case "slime_ball" -> "slime";
            case "phantom_membrane" -> "phantom";
            case "rabbit_hide", "rabbit_foot", "rabbit" -> "rabbit";
            case "feather", "chicken", "egg" -> "chicken";
            case "ink_sac" -> "squid";
            case "glow_ink_sac" -> "glow_squid";
            case "rotten_flesh" -> "zombie";
            case "wither_skeleton_skull" -> "wither_skeleton";
            case "shulker_shell" -> "shulker";
            case "prismarine_shard", "prismarine_crystals" -> "guardian";
            case "magma_cream" -> "magma_cube";
            case "porkchop" -> "pig";
            case "mutton" -> "sheep";
            // 1.21+ mob drops (scute renamed to turtle_scute in 1.21)
            case "turtle_scute", "scute" -> "turtle";
            case "armadillo_scute" -> "armadillo";
            case "breeze_rod" -> "breeze";
            case "honeycomb", "honey_bottle" -> "bee";
            case "goat_horn" -> "goat";
            case "nether_star" -> "wither";
            case "dragon_egg", "dragon_breath" -> "ender_dragon";
            case "totem_of_undying" -> "evoker";
            case "nautilus_shell" -> "drowned";
            case "trident" -> "drowned";
            // Mob heads (charged creeper explosions)
            case "skeleton_skull" -> "skeleton";
            case "zombie_head" -> "zombie";
            case "creeper_head" -> "creeper";
            case "piglin_head" -> "piglin";
            // Trial chamber
            case "trial_key", "ominous_trial_key", "ominous_bottle", "heavy_core" -> "trial_chamber";
            // Frog-related drops
            case "frogspawn" -> "frog";
            case "turtle_egg" -> "turtle";
            case "sniffer_egg" -> "sniffer";
            case "ochre_froglight" -> "temperate_frog";
            case "pearlescent_froglight" -> "warm_frog";
            case "verdant_froglight" -> "cold_frog";
            // Mob capture buckets
            case "cod_bucket" -> "cod";
            case "salmon_bucket" -> "salmon";
            case "pufferfish_bucket" -> "pufferfish";
            case "tropical_fish_bucket" -> "tropical_fish";
            case "axolotl_bucket" -> "axolotl";
            case "tadpole_bucket" -> "tadpole";
            default -> {
                if (itemId.contains("wool")) yield "sheep";
                if (itemId.contains("meat")) yield "cow";
                yield itemId;
            }
        };
    }
}
