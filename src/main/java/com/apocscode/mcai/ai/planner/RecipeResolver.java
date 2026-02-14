package com.apocscode.mcai.ai.planner;

import com.apocscode.mcai.MCAi;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Recursively resolves ANY item into a full dependency tree.
 * Works across all recipe types: crafting, smelting, blasting, smoking, stonecutting.
 * Categorizes each leaf node as a raw material action (MINE, CHOP, GATHER, KILL, FISH, FARM).
 *
 * IMPORTANT: With 600+ mods, recipe selection is critical.
 * We score and prefer vanilla (minecraft:) recipes over modded alternatives
 * to avoid bizarre modded conversion chains (e.g., "Cultural Log → Porkchop → Iron Ingot").
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

    /** Pre-indexed recipes by output item — built once, reused for all lookups */
    private final Map<Item, List<RecipeHolder<?>>> craftingByOutput = new HashMap<>();
    private final Map<Item, List<RecipeHolder<?>>> heatByOutput = new HashMap<>();
    private final Map<Item, List<RecipeHolder<?>>> stonecutByOutput = new HashMap<>();

    public RecipeResolver(RecipeManager recipeManager, RegistryAccess registryAccess) {
        this.recipeManager = recipeManager;
        this.registryAccess = registryAccess;
        buildRecipeIndex();
    }

    // ========== Recipe index (like EMI does client-side) ==========

    /**
     * Build output→recipe index for O(1) lookups instead of scanning all recipes every time.
     * Only indexes recipes we can actually use (crafting, smelting, blasting, smoking, campfire, stonecutting).
     * Skips recipes that throw exceptions or return null results (common with 600+ mods).
     */
    private void buildRecipeIndex() {
        int indexed = 0, skipped = 0, vanillaSkipped = 0;
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) { skipped++; continue; }

                // Get recipe output — try safeResult first, then fall back for known types
                ItemStack result = safeResult(r);
                if (result.isEmpty()) {
                    // For ShapedRecipe/ShapelessRecipe, try accessing result directly
                    // Some mods' mixins break getResultItem() for vanilla recipes
                    result = tryDirectResult(r);
                }
                if (result.isEmpty()) {
                    // Log if this is a vanilla recipe being skipped — critical for diagnosis
                    ResourceLocation recipeId = holder.id();
                    if (recipeId != null && recipeId.getNamespace().equals("minecraft")) {
                        MCAi.LOGGER.warn("RecipeResolver: SKIPPING vanilla recipe {} (getResultItem returned null)",
                                recipeId);
                        vanillaSkipped++;
                    }
                    skipped++;
                    continue;
                }
                Item outputItem = result.getItem();

                if (r instanceof ShapedRecipe || r instanceof ShapelessRecipe) {
                    craftingByOutput.computeIfAbsent(outputItem, k -> new ArrayList<>()).add(holder);
                    indexed++;
                } else if (r instanceof SmeltingRecipe || r instanceof BlastingRecipe
                        || r instanceof SmokingRecipe || r instanceof CampfireCookingRecipe) {
                    heatByOutput.computeIfAbsent(outputItem, k -> new ArrayList<>()).add(holder);
                    indexed++;
                } else if (r instanceof StonecutterRecipe) {
                    stonecutByOutput.computeIfAbsent(outputItem, k -> new ArrayList<>()).add(holder);
                    indexed++;
                }
                // Skip all other recipe types (Create processing, Mekanism machines, etc.)
                // — these require special machines the companion can't use
            } catch (Exception e) {
                skipped++;
            }
        }
        MCAi.LOGGER.info("RecipeResolver: indexed {} recipes ({} skipped, {} vanilla skipped), "
                + "crafting={}, heat={}, stonecut={}",
                indexed, skipped, vanillaSkipped,
                craftingByOutput.size(), heatByOutput.size(), stonecutByOutput.size());

        // Verify critical vanilla items are indexed
        verifyKeyItemsIndexed();

        // Sort each recipe list by score (best first)
        for (List<RecipeHolder<?>> list : craftingByOutput.values()) {
            list.sort((a, b) -> scoreRecipe(b) - scoreRecipe(a));
        }
        for (List<RecipeHolder<?>> list : heatByOutput.values()) {
            list.sort((a, b) -> scoreRecipe(b) - scoreRecipe(a));
        }
        for (List<RecipeHolder<?>> list : stonecutByOutput.values()) {
            list.sort((a, b) -> scoreRecipe(b) - scoreRecipe(a));
        }
    }

    // ========== Null-safe result helper ==========

    /**
     * Safely get result ItemStack from any recipe.
     * Many modded recipes (Create ProcessingRecipe, Mystical Agriculture essence, etc.)
     * return null from getResultItem(). This ALWAYS returns non-null.
     */
    private ItemStack safeResult(Recipe<?> r) {
        try {
            ItemStack result = r.getResultItem(registryAccess);
            return result != null ? result : ItemStack.EMPTY;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * Fallback: try to get the result directly from known recipe types.
     * Some mods' mixins break getResultItem() even for vanilla recipes.
     * For ShapedRecipe/ShapelessRecipe, we can try accessing the result field via reflection.
     */
    private ItemStack tryDirectResult(Recipe<?> r) {
        try {
            // Both ShapedRecipe and ShapelessRecipe store result as a field
            // Try reflection as a last resort
            java.lang.reflect.Field resultField = null;
            Class<?> clazz = r.getClass();
            // Walk up the class hierarchy looking for a 'result' field
            while (clazz != null && clazz != Object.class) {
                try {
                    resultField = clazz.getDeclaredField("result");
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (resultField != null) {
                resultField.setAccessible(true);
                Object value = resultField.get(r);
                if (value instanceof ItemStack stack && !stack.isEmpty()) {
                    return stack.copy();
                }
            }
        } catch (Exception e) {
            // Reflection failed — nothing we can do
        }
        return ItemStack.EMPTY;
    }

    /**
     * Verify that critical vanilla items are present in the recipe index.
     * Logs warnings for any missing items — helps diagnose modded environment issues.
     */
    private void verifyKeyItemsIndexed() {
        String[] criticalItems = {
            "furnace", "crafting_table", "iron_pickaxe", "iron_ingot", "stone_pickaxe",
            "wooden_pickaxe", "chest", "torch", "stick", "oak_planks"
        };
        for (String name : criticalItems) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(name));
            if (item == null || item == net.minecraft.world.item.Items.AIR) continue;
            boolean hasCrafting = craftingByOutput.containsKey(item);
            boolean hasHeat = heatByOutput.containsKey(item);
            boolean hasStonecut = stonecutByOutput.containsKey(item);
            if (!hasCrafting && !hasHeat && !hasStonecut) {
                MCAi.LOGGER.error("RecipeResolver: CRITICAL item '{}' has NO recipes indexed! " +
                        "This will break crafting plans.", name);
            }
        }
    }

    /**
     * Static version for use outside the resolver (CraftItemTool, GetRecipeTool).
     */
    public static ItemStack safeGetResult(Recipe<?> r, RegistryAccess registryAccess) {
        try {
            ItemStack result = r.getResultItem(registryAccess);
            return result != null ? result : ItemStack.EMPTY;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ========== Recipe scoring (vanilla preference) ==========

    /**
     * Score a recipe for preference ranking. Higher = better.
     * Strongly prefers vanilla (minecraft:) recipes over modded alternatives.
     * This prevents the resolver from picking bizarre modded conversion chains
     * like "Cultural Log → Raw Porkchop → Iron Ingot" when the vanilla recipe
     * "3x Iron Ingot + 2x Stick → Iron Pickaxe" is available.
     */
    private int scoreRecipe(RecipeHolder<?> holder) {
        int score = 0;
        try {
            ResourceLocation recipeId = holder.id();
            String namespace = recipeId.getNamespace();

            // ---- Namespace scoring ----
            // Vanilla recipes are almost always correct and simple
            if (namespace.equals("minecraft")) {
                score += 1000;
            }
            // Penalize mob/essence/mystical agriculture conversion recipes
            // These mods add recipes that convert mob essences → vanilla items
            if (namespace.contains("mystical") || namespace.contains("apotheosis")
                    || namespace.contains("productive") || namespace.contains("mob")
                    || namespace.contains("essence") || namespace.contains("occultism")
                    || namespace.contains("ars_") || namespace.contains("botania")
                    || namespace.contains("bloodmagic") || namespace.contains("forbidden")
                    || namespace.contains("pneumaticcraft") || namespace.contains("integrated")) {
                score -= 500;
            }

            // ---- Ingredient scoring ----
            Recipe<?> r = holder.value();
            if (r != null) {
                List<Ingredient> ings = r.getIngredients();
                if (ings != null) {
                    for (Ingredient ing : ings) {
                        if (ing == null || ing.isEmpty()) continue;
                        try {
                            ItemStack[] items = ing.getItems();
                            if (items != null && items.length > 0 && items[0] != null) {
                                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(items[0].getItem());
                                if (itemId != null && itemId.getNamespace().equals("minecraft")) {
                                    score += 20; // Vanilla ingredient = good
                                } else {
                                    score -= 10; // Modded ingredient = slightly bad
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            score = -9999; // Can't even inspect this recipe — rank it last
        }
        return score;
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
        String itemId = BuiltInRegistries.ITEM.getKey(item).getPath();
        String indent = "  ".repeat(depth);

        if (depth > MAX_DEPTH) {
            MCAi.LOGGER.warn("{}resolveRecursive: MAX_DEPTH exceeded for '{}' x{}", indent, itemId, count);
            return new DependencyNode(item, count, StepType.UNKNOWN, null);
        }

        MCAi.LOGGER.info("{}resolveRecursive: '{}' x{} (depth={})", indent, itemId, count, depth);

        // Check if already available (inventory/chests)
        int avail = available.getOrDefault(item, 0);
        if (avail >= count) {
            available.put(item, avail - count); // "consume" from available
            MCAi.LOGGER.info("{}  -> AVAILABLE (have {} >= need {})", indent, avail, count);
            return new DependencyNode(item, count, StepType.AVAILABLE, null);
        }

        // Use what's available, resolve the rest
        int remaining = count;
        if (avail > 0) {
            remaining = count - avail;
            available.put(item, 0);
            MCAi.LOGGER.info("{}  -> partially available: have {}, still need {}", indent, avail, remaining);
        }

        // Prevent infinite recursion (item A needs B which needs A)
        if (visited.contains(item)) {
            MCAi.LOGGER.info("{}  -> CYCLE detected for '{}', classifying as raw", indent, itemId);
            return classifyRawMaterial(item, remaining);
        }

        // === Early exit: known raw materials skip recipe lookup ===
        // Items like raw_iron, coal, oak_log are directly obtainable (mine, chop, etc.)
        // Without this, raw_iron resolves via crafting (uncraft raw_iron_block → 9 raw_iron)
        // which is absurd — raw_iron drops from mining iron_ore.
        DependencyNode rawCheck = classifyRawMaterial(item, remaining);
        if (rawCheck.type != StepType.UNKNOWN) {
            MCAi.LOGGER.info("{}  -> early-exit raw material: '{}' classified as {}", indent, itemId, rawCheck.type);
            return rawCheck;
        }

        visited.add(item);

        boolean hasHeatRecipe = heatByOutput.containsKey(item);
        MCAi.LOGGER.info("{}  -> not raw, hasHeatRecipe={}, trying recipe phases...", indent, hasHeatRecipe);

        // === Phase 1: Try heat (smelting) FIRST when a heat recipe exists ===
        if (hasHeatRecipe) {
            DependencyNode heatNode = tryHeatRecipe(item, remaining, available, visited, depth);
            if (heatNode != null && !hasDeepUnknown(heatNode)) {
                MCAi.LOGGER.info("{}  -> Phase 1 HEAT success for '{}'", indent, itemId);
                visited.remove(item);
                return heatNode;
            }
            MCAi.LOGGER.info("{}  -> Phase 1 HEAT failed/had UNKNOWN for '{}'", indent, itemId);
        }

        // === Phase 2: Try crafting recipe ===
        DependencyNode craftNode = tryCraftingRecipe(item, remaining, available, visited, depth);
        if (craftNode != null) {
            if (!hasDeepUnknown(craftNode)) {
                MCAi.LOGGER.info("{}  -> Phase 2 CRAFT success for '{}'", indent, itemId);
                visited.remove(item);
                return craftNode;
            }
            MCAi.LOGGER.info("{}  -> Phase 2 CRAFT had deep UNKNOWN for '{}', trying heat fallback", indent, itemId);
            if (hasHeatRecipe) {
                DependencyNode heatFallback = tryHeatRecipe(item, remaining, available, visited, depth);
                if (heatFallback != null) {
                    MCAi.LOGGER.info("{}  -> heat fallback success for '{}'", indent, itemId);
                    visited.remove(item);
                    return heatFallback;
                }
            }
            MCAi.LOGGER.info("{}  -> accepting CRAFT despite deep UNKNOWN for '{}'", indent, itemId);
            visited.remove(item);
            return craftNode;
        } else {
            MCAi.LOGGER.info("{}  -> Phase 2 no CRAFT recipe found for '{}'", indent, itemId);
        }

        // === Phase 3: Try heat recipe (for items without pre-indexed heat recipes) ===
        if (!hasHeatRecipe) {
            DependencyNode heatNode = tryHeatRecipe(item, remaining, available, visited, depth);
            if (heatNode != null) {
                MCAi.LOGGER.info("{}  -> Phase 3 late HEAT success for '{}'", indent, itemId);
                visited.remove(item);
                return heatNode;
            }
        }

        // === Phase 4: Try stonecutting ===
        DependencyNode stonecutNode = tryStonecutRecipe(item, remaining, available, visited, depth);
        if (stonecutNode != null) {
            MCAi.LOGGER.info("{}  -> Phase 4 STONECUT success for '{}'", indent, itemId);
            visited.remove(item);
            return stonecutNode;
        }

        visited.remove(item);

        // No recipe found — classify as raw material (mineable, choppable, etc.)
        MCAi.LOGGER.warn("{}  -> NO RECIPE found for '{}' x{}, falling back to raw classification", indent, itemId, remaining);
        return classifyRawMaterial(item, remaining);
    }

    // ========== Tree analysis helpers ==========

    /**
     * Recursively checks if any descendant node in the tree is UNKNOWN.
     * Used to detect deeply nested circular dependencies that a shallow check would miss.
     * Example: iron_ingot → CRAFT(9× iron_nugget → SMELT(iron_pickaxe → UNKNOWN))
     */
    private boolean hasDeepUnknown(DependencyNode node) {
        if (node == null) return false;
        if (node.type == StepType.UNKNOWN) return true;
        for (DependencyNode child : node.children) {
            if (hasDeepUnknown(child)) return true;
        }
        return false;
    }

    // ========== Recipe type handlers ==========

    private DependencyNode tryCraftingRecipe(Item item, int count, Map<Item, Integer> available,
                                              Set<Item> visited, int depth) {
        // Use pre-indexed recipes — already sorted by score (vanilla first)
        List<RecipeHolder<?>> candidates = craftingByOutput.get(item);
        if (candidates == null || candidates.isEmpty()) return null;

        String itemId = BuiltInRegistries.ITEM.getKey(item).getPath();
        MCAi.LOGGER.info("RecipeResolver: tryCraftingRecipe '{}' x{}, {} candidates", itemId, count, candidates.size());

        // Try each candidate in score order (best/vanilla first)
        for (RecipeHolder<?> best : candidates) {
            try {
                Recipe<?> recipe = best.value();
                if (recipe == null) continue;
                ItemStack resultStack = safeResult(recipe);
                if (resultStack.isEmpty()) continue;
                int outputPerCraft = resultStack.getCount();
                if (outputPerCraft <= 0) outputPerCraft = 1;
                int craftsNeeded = (int) Math.ceil((double) count / outputPerCraft);

                DependencyNode node = new DependencyNode(item, count, StepType.CRAFT, best);

                // Resolve each ingredient recursively
                Map<Item, Integer> grouped = groupIngredients(recipe.getIngredients(), craftsNeeded, available);
                MCAi.LOGGER.info("  recipe {} -> output={}/craft, craftsNeeded={}, ingredients: {}",
                        best.id(), outputPerCraft, craftsNeeded, grouped.entrySet().stream()
                                .map(e -> BuiltInRegistries.ITEM.getKey(e.getKey()).getPath() + "=" + e.getValue())
                                .collect(java.util.stream.Collectors.joining(", ")));
                boolean hasUnresolvable = false;
                for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
                    DependencyNode child = resolveRecursive(entry.getKey(), entry.getValue(),
                            available, new HashSet<>(visited), depth + 1);
                    node.children.add(child);
                    if (child.type == StepType.UNKNOWN) hasUnresolvable = true;
                }

                // If all ingredients resolved OR this is a vanilla recipe, accept it
                ResourceLocation recipeId = best.id();
                if (!hasUnresolvable || recipeId.getNamespace().equals("minecraft")) {
                    return node;
                }
                // Modded recipe with unresolvable ingredients — try next candidate
                MCAi.LOGGER.debug("RecipeResolver: skipping {} (has UNKNOWN ingredients), trying next",
                        recipeId);
            } catch (Exception e) {
                MCAi.LOGGER.warn("RecipeResolver: crafting recipe for {} threw: {}",
                        BuiltInRegistries.ITEM.getKey(item), e.getMessage());
            }
        }

        // All candidates had issues — return null to let heat/stonecut recipes be tried.
        // If we returned a node with UNKNOWN children, it would block tryHeatRecipe
        // (e.g., iron_ingot: modded crafting recipe with essence vs. smelting raw_iron)
        MCAi.LOGGER.debug("RecipeResolver: all {} crafting candidates for {} had UNKNOWN ingredients, falling through to heat/stonecut",
                candidates.size(), BuiltInRegistries.ITEM.getKey(item));
        return null;
    }

    private DependencyNode tryHeatRecipe(Item item, int count, Map<Item, Integer> available,
                                          Set<Item> visited, int depth) {
        // Use pre-indexed heat recipes — already sorted by score (vanilla first)
        List<RecipeHolder<?>> candidates = heatByOutput.get(item);
        if (candidates == null || candidates.isEmpty()) return null;

        // Pick best candidate, preferring SmeltingRecipe > BlastingRecipe > others
        RecipeHolder<?> best = null;
        StepType stepType = null;

        for (RecipeHolder<?> holder : candidates) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                if (r instanceof SmeltingRecipe) {
                    best = holder;
                    stepType = StepType.SMELT;
                    break; // SmeltingRecipe is always preferred
                } else if (best == null) {
                    best = holder;
                    if (r instanceof BlastingRecipe) stepType = StepType.BLAST;
                    else if (r instanceof SmokingRecipe) stepType = StepType.SMOKE;
                    else if (r instanceof CampfireCookingRecipe) stepType = StepType.CAMPFIRE_COOK;
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
                        Item inputItem = pickBestVariant(variants, available);
                        if (inputItem != null) {
                            DependencyNode child = resolveRecursive(inputItem, count,
                                    available, new HashSet<>(visited), depth + 1);
                            node.children.add(child);
                        }
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
        // Use pre-indexed stonecut recipes
        List<RecipeHolder<?>> candidates = stonecutByOutput.get(item);
        if (candidates == null || candidates.isEmpty()) return null;

        for (RecipeHolder<?> holder : candidates) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                ItemStack resultStack = safeResult(r);
                if (resultStack.isEmpty()) continue;

                int outputPerCraft = Math.max(1, resultStack.getCount());
                int craftsNeeded = (int) Math.ceil((double) count / outputPerCraft);

                DependencyNode node = new DependencyNode(item, count, StepType.STONECUT, holder);

                List<Ingredient> ings = r.getIngredients();
                if (ings != null && !ings.isEmpty()) {
                    Ingredient firstIng = ings.get(0);
                    if (firstIng != null && !firstIng.isEmpty()) {
                        ItemStack[] variants = firstIng.getItems();
                        if (variants != null && variants.length > 0 && variants[0] != null) {
                            Item inputItem = pickBestVariant(variants, available);
                            if (inputItem != null) {
                                DependencyNode child = resolveRecursive(inputItem, craftsNeeded,
                                        available, new HashSet<>(visited), depth + 1);
                                node.children.add(child);
                            }
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
        // Use endsWith for "deepslate" to avoid matching craftable items like
        // "deepslate_bricks", "deepslate_tiles", "chiseled_deepslate"
        if (id.startsWith("raw_") || id.endsWith("_ore") || id.equals("diamond")
                || id.equals("emerald") || id.equals("coal") || id.equals("lapis_lazuli")
                || id.equals("redstone") || id.equals("quartz") || id.equals("amethyst_shard")
                || id.equals("ancient_debris") || id.equals("glowstone_dust")
                || id.equals("deepslate")) {
            return new DependencyNode(item, count, StepType.MINE, null);
        }

        // Wood / logs → CHOP
        // Use endsWith/startsWith to avoid false matches:
        //   "wooden_pickaxe".contains("wood") was incorrectly classified as CHOP!
        //   "wooden_sword", "wooden_hoe", etc. are craftable items, NOT raw wood.
        if (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem")
                || id.endsWith("_hyphae")
                || id.startsWith("stripped_")
                || id.equals("bamboo_block")) {
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
        MCAi.LOGGER.info("RecipeResolver: classifyRawMaterial '{}' — UNKNOWN (not a recognized raw material)", id);
        return new DependencyNode(item, count, StepType.UNKNOWN, null);
    }

    // ========== Helpers ==========

    /**
     * Group recipe ingredients by item and sum counts.
     * e.g., ShapedRecipe slots [iron, iron, iron, _, stick, _, stick, _, _]
     * → {iron_ingot: 3*crafts, stick: 2*crafts}
     *
     * When a recipe ingredient is a tag (e.g., #minecraft:logs), prefers variants
     * that are already in the available inventory to avoid unnecessary gathering.
     */
    private Map<Item, Integer> groupIngredients(List<Ingredient> ingredients, int craftsNeeded,
                                                 Map<Item, Integer> available) {
        Map<Item, Integer> grouped = new LinkedHashMap<>();
        if (ingredients == null) return grouped;
        for (Ingredient ing : ingredients) {
            try {
                if (ing == null || ing.isEmpty()) continue;
                ItemStack[] items = ing.getItems();
                if (items == null || items.length == 0) continue;
                // Prefer variants the player already has in inventory
                Item representative = pickBestVariant(items, available);
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
     * Priority: 1) items already in available inventory, 2) vanilla items, 3) first valid.
     * This prevents generating CHOP steps when the companion already has birch_log
     * but the recipe says "any #logs" and we'd otherwise pick oak_log.
     */
    private Item pickBestVariant(ItemStack[] variants, Map<Item, Integer> available) {
        if (variants == null || variants.length == 0) return null;
        Item availableMatch = null;
        Item vanillaMatch = null;
        Item firstValid = null;
        for (ItemStack v : variants) {
            if (v == null || v.isEmpty()) continue;
            Item item = v.getItem();
            if (firstValid == null) firstValid = item;
            // Highest priority: variant already in inventory
            if (availableMatch == null && available != null && available.getOrDefault(item, 0) > 0) {
                availableMatch = item;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (vanillaMatch == null && id != null && id.getNamespace().equals("minecraft")) {
                vanillaMatch = item;
            }
        }
        if (availableMatch != null) return availableMatch;
        return vanillaMatch != null ? vanillaMatch : firstValid;
    }

    /**
     * Legacy overload without inventory context — used by scoring and other non-resolution paths.
     */
    private Item pickBestVariant(ItemStack[] variants) {
        return pickBestVariant(variants, null);
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
