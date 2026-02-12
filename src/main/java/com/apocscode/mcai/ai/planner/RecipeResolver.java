package com.apocscode.mcai.ai.planner;

import com.apocscode.mcai.MCAi;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.*;

/**
 * Recursively resolves ANY item into a full dependency tree.
 * Works across all recipe types: crafting, smelting, blasting, smoking, stonecutting.
 * Categorizes each leaf node as a raw material action (MINE, CHOP, GATHER, KILL, FISH, FARM).
 *
 * Example: iron_pickaxe →
 *   CRAFT iron_pickaxe (needs 3x iron_ingot + 2x stick)
 *     SMELT raw_iron → iron_ingot (needs raw_iron)
 *       MINE raw_iron (ore drop)
 *     CRAFT stick (needs 2x planks)
 *       CRAFT planks (needs 1x log)
 *         CHOP log
 */
public class RecipeResolver {

    private final RecipeManager recipeManager;
    private final RegistryAccess registryAccess;

    /** Maximum recursion depth to prevent infinite loops */
    private static final int MAX_DEPTH = 10;

    public RecipeResolver(RecipeManager recipeManager, RegistryAccess registryAccess) {
        this.recipeManager = recipeManager;
        this.registryAccess = registryAccess;
    }

    // ========== Step category for each node in the tree ==========

    public enum StepType {
        /** Standard crafting table / 2x2 recipe */
        CRAFT,
        /** Furnace smelting */
        SMELT,
        /** Blast furnace */
        BLAST,
        /** Smoker */
        SMOKE,
        /** Campfire cooking */
        CAMPFIRE_COOK,
        /** Stonecutter */
        STONECUT,
        /** Mine ores / dig underground */
        MINE,
        /** Chop trees for logs */
        CHOP,
        /** Gather surface blocks (sand, gravel, clay, flowers, etc.) */
        GATHER,
        /** Kill mobs for drops (leather, string, bones, etc.) */
        KILL_MOB,
        /** Fish */
        FISH,
        /** Farm crops */
        FARM,
        /** Already available — in inventory or chests */
        AVAILABLE,
        /** Unknown — can't determine how to obtain */
        UNKNOWN
    }

    // ========== Dependency node ==========

    /**
     * A single node in the dependency tree.
     * Each node represents one step: craft X, smelt Y, mine Z, etc.
     */
    public static class DependencyNode {
        public final Item item;
        public final int count;
        public final StepType type;
        public final RecipeHolder<?> recipe;    // null for leaf nodes (MINE, CHOP, etc.)
        public final List<DependencyNode> children;  // sub-dependencies
        public final String itemId;

        public DependencyNode(Item item, int count, StepType type, RecipeHolder<?> recipe) {
            this.item = item;
            this.count = count;
            this.type = type;
            this.recipe = recipe;
            this.children = new ArrayList<>();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            this.itemId = id != null ? id.getPath() : "unknown";
        }

        @Override
        public String toString() {
            return type + " " + itemId + " x" + count;
        }

        /** Flatten the tree into an ordered list of steps (leaves first = gather first). */
        public List<DependencyNode> flatten() {
            List<DependencyNode> result = new ArrayList<>();
            flattenRecursive(result, new HashSet<>());
            return result;
        }

        private void flattenRecursive(List<DependencyNode> result, Set<String> seen) {
            // Children first (raw materials before crafting)
            for (DependencyNode child : children) {
                child.flattenRecursive(result, seen);
            }
            // Then self (only if not already added — dedup by item+type)
            String key = type + ":" + itemId;
            if (!seen.contains(key)) {
                seen.add(key);
                result.add(this);
            }
        }
    }

    // ========== Main resolution entry point ==========

    /**
     * Resolve an item into its full dependency tree.
     * @param item      The target item to craft
     * @param count     How many are needed
     * @param available Items already available (item → count). These won't be resolved further.
     * @return Root DependencyNode representing the full plan
     */
    public DependencyNode resolve(Item item, int count, Map<Item, Integer> available) {
        return resolveRecursive(item, count, available, new HashSet<>(), 0);
    }

    private DependencyNode resolveRecursive(Item item, int count, Map<Item, Integer> available,
                                             Set<Item> visited, int depth) {
        if (depth > MAX_DEPTH) {
            return new DependencyNode(item, count, StepType.UNKNOWN, null);
        }

        String itemId = BuiltInRegistries.ITEM.getKey(item).getPath();

        // Check if already available (inventory/chests)
        int avail = available.getOrDefault(item, 0);
        if (avail >= count) {
            available.put(item, avail - count); // "consume" from available
            return new DependencyNode(item, count, StepType.AVAILABLE, null);
        }

        // Use what's available, resolve the rest
        int remaining = count;
        if (avail > 0) {
            remaining = count - avail;
            available.put(item, 0);
        }

        // Prevent infinite recursion (item A needs B which needs A)
        if (visited.contains(item)) {
            return classifyRawMaterial(item, remaining);
        }
        visited.add(item);

        // Try crafting recipe first (most common)
        DependencyNode craftNode = tryCraftingRecipe(item, remaining, available, visited, depth);
        if (craftNode != null) {
            visited.remove(item);
            return craftNode;
        }

        // Try smelting/blasting/smoking recipes
        DependencyNode heatNode = tryHeatRecipe(item, remaining, available, visited, depth);
        if (heatNode != null) {
            visited.remove(item);
            return heatNode;
        }

        // Try stonecutting
        DependencyNode stonecutNode = tryStonecutRecipe(item, remaining, available, visited, depth);
        if (stonecutNode != null) {
            visited.remove(item);
            return stonecutNode;
        }

        visited.remove(item);

        // No recipe found — classify as raw material (mineable, choppable, etc.)
        return classifyRawMaterial(item, remaining);
    }

    // ========== Recipe type handlers ==========

    private DependencyNode tryCraftingRecipe(Item item, int count, Map<Item, Integer> available,
                                              Set<Item> visited, int depth) {
        RecipeHolder<?> best = null;

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                if (!(r instanceof ShapedRecipe) && !(r instanceof ShapelessRecipe)) continue;
                ItemStack resultStack = r.getResultItem(registryAccess);
                if (resultStack == null || resultStack.isEmpty()) continue;
                if (resultStack.getItem() != item) continue;
                best = holder;
                break;
            } catch (Exception e) {
                // Modded recipe threw during inspection — skip it
                continue;
            }
        }

        if (best == null) return null;

        try {
            Recipe<?> recipe = best.value();
            ItemStack resultStack = recipe.getResultItem(registryAccess);
            if (resultStack == null || resultStack.isEmpty()) return null;
            int outputPerCraft = resultStack.getCount();
            if (outputPerCraft <= 0) outputPerCraft = 1;
            int craftsNeeded = (int) Math.ceil((double) count / outputPerCraft);

            DependencyNode node = new DependencyNode(item, count, StepType.CRAFT, best);

            // Resolve each ingredient recursively
            Map<Item, Integer> grouped = groupIngredients(recipe.getIngredients(), craftsNeeded);
            for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
                DependencyNode child = resolveRecursive(entry.getKey(), entry.getValue(),
                        available, new HashSet<>(visited), depth + 1);
                node.children.add(child);
            }

            return node;
        } catch (Exception e) {
            MCAi.LOGGER.warn("RecipeResolver: crafting recipe for {} threw: {}",
                    BuiltInRegistries.ITEM.getKey(item), e.getMessage());
            return null;
        }
    }

    private DependencyNode tryHeatRecipe(Item item, int count, Map<Item, Integer> available,
                                          Set<Item> visited, int depth) {
        RecipeHolder<?> best = null;
        StepType stepType = null;

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                // Skip non-heat recipe types early (performance: 602 mods = thousands of recipes)
                if (!(r instanceof SmeltingRecipe) && !(r instanceof BlastingRecipe)
                        && !(r instanceof SmokingRecipe) && !(r instanceof CampfireCookingRecipe)) continue;
                ItemStack resultStack = r.getResultItem(registryAccess);
                if (resultStack == null || resultStack.isEmpty()) continue;
                if (resultStack.getItem() != item) continue;

                if (r instanceof SmeltingRecipe) {
                    best = holder;
                    stepType = StepType.SMELT;
                    break; // Prefer smelting
                } else if (r instanceof BlastingRecipe && best == null) {
                    best = holder;
                    stepType = StepType.BLAST;
                } else if (r instanceof SmokingRecipe && best == null) {
                    best = holder;
                    stepType = StepType.SMOKE;
                } else if (r instanceof CampfireCookingRecipe && best == null) {
                    best = holder;
                    stepType = StepType.CAMPFIRE_COOK;
                }
            } catch (Exception e) {
                continue;
            }
        }

        if (best == null || stepType == null) return null;

        try {
            Recipe<?> recipe = best.value();
            DependencyNode node = new DependencyNode(item, count, stepType, best);

            // Smelting recipes have exactly 1 ingredient
            List<Ingredient> ings = recipe.getIngredients();
            if (ings != null && !ings.isEmpty()) {
                Ingredient firstIng = ings.get(0);
                if (firstIng != null && !firstIng.isEmpty()) {
                    ItemStack[] variants = firstIng.getItems();
                    if (variants != null && variants.length > 0 && variants[0] != null) {
                        Item inputItem = variants[0].getItem();
                        DependencyNode child = resolveRecursive(inputItem, count,
                                available, new HashSet<>(visited), depth + 1);
                        node.children.add(child);
                    }
                }
            }

            return node;
        } catch (Exception e) {
            MCAi.LOGGER.warn("RecipeResolver: heat recipe for {} threw: {}",
                    BuiltInRegistries.ITEM.getKey(item), e.getMessage());
            return null;
        }
    }

    private DependencyNode tryStonecutRecipe(Item item, int count, Map<Item, Integer> available,
                                              Set<Item> visited, int depth) {
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                if (!(r instanceof StonecutterRecipe)) continue;
                ItemStack resultStack = r.getResultItem(registryAccess);
                if (resultStack == null || resultStack.isEmpty()) continue;
                if (resultStack.getItem() != item) continue;

                int outputPerCraft = resultStack.getCount();
                if (outputPerCraft <= 0) outputPerCraft = 1;
                int craftsNeeded = (int) Math.ceil((double) count / outputPerCraft);

                DependencyNode node = new DependencyNode(item, count, StepType.STONECUT, holder);

                List<Ingredient> ings = r.getIngredients();
                if (ings != null && !ings.isEmpty()) {
                    Ingredient firstIng = ings.get(0);
                    if (firstIng != null && !firstIng.isEmpty()) {
                        ItemStack[] variants = firstIng.getItems();
                        if (variants != null && variants.length > 0 && variants[0] != null) {
                            Item inputItem = variants[0].getItem();
                            DependencyNode child = resolveRecursive(inputItem, craftsNeeded,
                                    available, new HashSet<>(visited), depth + 1);
                            node.children.add(child);
                        }
                    }
                }

                return node;
            } catch (Exception e) {
                MCAi.LOGGER.warn("RecipeResolver: stonecut recipe threw: {}", e.getMessage());
                continue;
            }
        }
        return null;
    }

    // ========== Raw material classification ==========

    /**
     * Classify an item that has no recipe — how does a player obtain it in the world?
     */
    public static DependencyNode classifyRawMaterial(Item item, int count) {
        String id = BuiltInRegistries.ITEM.getKey(item).getPath();

        // Ores and raw metals → MINE
        if (id.contains("raw_") || id.contains("_ore") || id.equals("diamond")
                || id.equals("emerald") || id.equals("coal") || id.equals("lapis_lazuli")
                || id.equals("redstone") || id.equals("quartz") || id.equals("amethyst_shard")
                || id.equals("ancient_debris") || id.equals("glowstone_dust")
                || id.contains("deepslate")) {
            return new DependencyNode(item, count, StepType.MINE, null);
        }

        // Wood / logs → CHOP
        if (id.contains("log") || id.contains("wood") || id.contains("stem")
                || id.contains("hyphae")) {
            return new DependencyNode(item, count, StepType.CHOP, null);
        }

        // Stone, cobblestone, dirt, sand, gravel, clay → GATHER
        if (id.equals("cobblestone") || id.equals("cobbled_deepslate")
                || id.contains("sand") || id.equals("gravel") || id.equals("clay_ball")
                || id.equals("clay") || id.equals("dirt") || id.equals("flint")
                || id.equals("obsidian") || id.equals("ice") || id.equals("snow_block")
                || id.equals("snowball") || id.equals("netherrack") || id.equals("soul_sand")
                || id.equals("soul_soil") || id.equals("basalt") || id.equals("blackstone")
                || id.equals("end_stone") || id.equals("moss_block") || id.equals("mud")
                || id.equals("packed_mud") || id.equals("dripstone_block")
                || id.equals("pointed_dripstone") || id.equals("calcite")
                || id.equals("tuff") || id.equals("stone")) {
            return new DependencyNode(item, count, StepType.GATHER, null);
        }

        // Mob drops → KILL_MOB
        if (id.equals("leather") || id.equals("string") || id.equals("bone")
                || id.equals("spider_eye") || id.equals("gunpowder") || id.equals("ender_pearl")
                || id.equals("blaze_rod") || id.equals("ghast_tear") || id.equals("slime_ball")
                || id.equals("phantom_membrane") || id.equals("rabbit_hide")
                || id.equals("rabbit_foot") || id.equals("feather") || id.equals("ink_sac")
                || id.equals("glow_ink_sac") || id.equals("rotten_flesh")
                || id.equals("bone_meal") || id.equals("wither_skeleton_skull")
                || id.equals("shulker_shell") || id.equals("prismarine_shard")
                || id.equals("prismarine_crystals") || id.equals("magma_cream")
                || id.contains("_wool") || id.contains("_meat") || id.equals("porkchop")
                || id.equals("beef") || id.equals("chicken") || id.equals("mutton")
                || id.equals("rabbit") || id.equals("egg")) {
            return new DependencyNode(item, count, StepType.KILL_MOB, null);
        }

        // Fish → FISH
        if (id.equals("cod") || id.equals("salmon") || id.equals("tropical_fish")
                || id.equals("pufferfish")) {
            return new DependencyNode(item, count, StepType.FISH, null);
        }

        // Crops / farmable → FARM
        if (id.equals("wheat") || id.equals("wheat_seeds") || id.equals("carrot")
                || id.equals("potato") || id.equals("beetroot") || id.equals("beetroot_seeds")
                || id.equals("melon_slice") || id.equals("pumpkin") || id.equals("sugar_cane")
                || id.equals("sugar") || id.equals("bamboo") || id.equals("cactus")
                || id.equals("kelp") || id.equals("cocoa_beans") || id.equals("sweet_berries")
                || id.equals("glow_berries") || id.equals("nether_wart")
                || id.equals("chorus_fruit") || id.equals("apple")
                || id.contains("mushroom") || id.contains("flower") || id.contains("tulip")
                || id.equals("dandelion") || id.equals("poppy") || id.equals("blue_orchid")
                || id.equals("allium") || id.equals("azure_bluet") || id.equals("cornflower")
                || id.equals("lily_of_the_valley") || id.equals("lily_pad")
                || id.equals("vine") || id.equals("tall_grass") || id.equals("fern")
                || id.equals("seagrass") || id.equals("dried_kelp")) {
            return new DependencyNode(item, count, StepType.FARM, null);
        }

        // Default: unknown — AI will need to figure out creative solutions
        MCAi.LOGGER.debug("RecipeResolver: unknown raw material '{}' — classified as UNKNOWN", id);
        return new DependencyNode(item, count, StepType.UNKNOWN, null);
    }

    // ========== Helpers ==========

    /**
     * Group recipe ingredients by item and sum counts.
     * e.g., ShapedRecipe slots [iron, iron, iron, _, stick, _, stick, _, _]
     * → {iron_ingot: 3*crafts, stick: 2*crafts}
     */
    private Map<Item, Integer> groupIngredients(List<Ingredient> ingredients, int craftsNeeded) {
        Map<Item, Integer> grouped = new LinkedHashMap<>();
        if (ingredients == null) return grouped;
        for (Ingredient ing : ingredients) {
            try {
                if (ing == null || ing.isEmpty()) continue;
                ItemStack[] items = ing.getItems();
                if (items == null || items.length == 0) continue;
                // Use first variant (vanilla preferred)
                Item representative = pickBestVariant(items);
                if (representative == null) continue;
                grouped.merge(representative, craftsNeeded, Integer::sum);
            } catch (Exception e) {
                // Modded ingredient threw during getItems() — skip it
                MCAi.LOGGER.warn("RecipeResolver: ingredient threw: {}", e.getMessage());
                continue;
            }
        }
        return grouped;
    }

    /**
     * Pick the best variant from an ingredient's options.
     * Prefers vanilla (minecraft:) items over modded to avoid modded-only recipes.
     */
    private Item pickBestVariant(ItemStack[] variants) {
        if (variants == null || variants.length == 0) return null;
        Item vanillaMatch = null;
        Item firstValid = null;
        for (ItemStack v : variants) {
            if (v == null || v.isEmpty()) continue;
            if (firstValid == null) firstValid = v.getItem();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(v.getItem());
            if (id != null && id.getNamespace().equals("minecraft")) {
                vanillaMatch = v.getItem();
                break;
            }
        }
        return vanillaMatch != null ? vanillaMatch : firstValid;
    }

    // ========== Debug / logging ==========

    /**
     * Print the dependency tree as a string for logging.
     */
    public static String printTree(DependencyNode root) {
        StringBuilder sb = new StringBuilder();
        printTreeRecursive(root, sb, "", true);
        return sb.toString();
    }

    private static void printTreeRecursive(DependencyNode node, StringBuilder sb,
                                            String prefix, boolean isLast) {
        sb.append(prefix);
        sb.append(isLast ? "└── " : "├── ");
        sb.append(node.type).append(" ").append(node.itemId).append(" x").append(node.count);
        sb.append("\n");

        for (int i = 0; i < node.children.size(); i++) {
            printTreeRecursive(node.children.get(i), sb,
                    prefix + (isLast ? "    " : "│   "),
                    i == node.children.size() - 1);
        }
    }
}
