package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crafts items using materials from both the player's AND companion's inventory.
 *
 * Auto-resolves intermediate crafting-table recipes (e.g., logs→planks→sticks).
 * Only uses Shaped and Shapeless recipes — smelting requires the smelt_items tool.
 *
 * Balance: Crafting table recipes are instant (same as vanilla player behavior).
 * Smelting is NOT bypassed — requires a real furnace, fuel, and game time.
 */
public class CraftItemTool implements AiTool {

    private static final int MAX_RESOLVE_PASSES = 3;

    @Override
    public String name() {
        return "craft_item";
    }

    @Override
    public String description() {
        return "Craft an item using materials from the player's and companion's inventories. " +
                "Automatically resolves intermediate crafting steps (e.g., logs→planks→sticks). " +
                "Only works with crafting table recipes. " +
                "Smelting (raw ores → ingots, raw food → cooked) requires the smelt_items tool instead. " +
                "Use when the player says 'craft me a pickaxe' or 'make 16 torches'.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description",
                "Item name or ID to craft. Examples: 'iron_pickaxe', 'torch', " +
                        "'crafting_table', 'chest', 'piston'");
        props.add("item", item);

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "Number of items to craft (will craft in batches). Default: 1");
        props.add("count", count);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("item");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null || context.server() == null) {
            return "Error: no server context";
        }

        String itemQuery = args.has("item") ? args.get("item").getAsString().trim().toLowerCase() : "";
        if (itemQuery.isEmpty()) return "Error: no item specified";

        int requestedCount = args.has("count") ? args.get("count").getAsInt() : 1;
        if (requestedCount < 1) requestedCount = 1;

        final int finalCount = requestedCount;
        final String finalQuery = itemQuery;

        return context.runOnServer(() -> {
            RegistryAccess registryAccess = context.server().registryAccess();
            RecipeManager recipeManager = context.server().getRecipeManager();

            // --- Resolve item ---
            Item targetItem = resolveItem(finalQuery);
            if (targetItem == null) {
                return "No item found matching '" + finalQuery + "'. Check the item name.";
            }

            // --- Find crafting table recipe (Shaped/Shapeless ONLY) — NOT smelting ---
            RecipeHolder<?> craftRecipe = findCraftingTableRecipe(recipeManager, registryAccess, targetItem);

            if (craftRecipe == null) {
                // Check if a smelting recipe exists → redirect to smelt_items
                RecipeHolder<?> smeltRecipe = findSmeltingRecipe(recipeManager, registryAccess, targetItem);
                if (smeltRecipe != null) {
                    String inputName = getSmeltInputName(smeltRecipe);
                    return targetItem.getDescription().getString() + " requires smelting, not a crafting table. " +
                            "Use the smelt_items tool with '" + inputName + "'. " +
                            "The companion needs a furnace nearby and fuel in its inventory.";
                }
                return "No crafting recipe found for '" + targetItem.getDescription().getString() + "'. " +
                        "It might only be obtainable through mining, trading, or other means.";
            }

            Recipe<?> recipe = craftRecipe.value();
            ItemStack recipeResult = recipe.getResultItem(registryAccess);
            int outputPerCraft = recipeResult.getCount();
            List<Ingredient> ingredients = recipe.getIngredients();
            int craftsNeeded = (int) Math.ceil((double) finalCount / outputPerCraft);

            // --- Phase 1: Auto-resolve intermediate crafting ingredients ---
            StringBuilder craftLog = new StringBuilder();
            autoResolveIntermediates(context, recipeManager, registryAccess, ingredients, craftsNeeded, craftLog);

            // --- Phase 2: Check what we can craft now ---
            int maxCrafts = calculateMaxCrafts(context, ingredients);

            if (maxCrafts == 0) {
                return buildMissingReport(context, recipeManager, registryAccess,
                        targetItem, ingredients, craftsNeeded, craftLog);
            }

            // --- Phase 3: Execute the craft ---
            int actualCrafts = Math.min(craftsNeeded, maxCrafts);
            int totalOutput = actualCrafts * outputPerCraft;

            for (int craft = 0; craft < actualCrafts; craft++) {
                for (Ingredient ing : ingredients) {
                    if (ing.isEmpty()) continue;
                    consumeIngredient(context, ing, 1);
                }
            }

            ItemStack result = new ItemStack(targetItem, totalOutput);
            if (!context.player().getInventory().add(result)) {
                context.player().drop(result, false);
            }

            StringBuilder sb = new StringBuilder();
            if (craftLog.length() > 0) sb.append(craftLog);
            sb.append("Crafted ").append(totalOutput).append("x ")
              .append(targetItem.getDescription().getString());
            if (totalOutput < finalCount) {
                sb.append(" (wanted ").append(finalCount)
                  .append(" but only had materials for ").append(totalOutput).append(")");
            }
            sb.append(".");
            return sb.toString();
        });
    }

    // ========== Auto-resolve intermediate crafting recipes ==========

    /**
     * Iteratively auto-crafts missing intermediate ingredients.
     * Handles dependency chains like: logs → planks → sticks (up to 3 levels).
     * Only uses crafting-table recipes — smelting intermediates are skipped.
     */
    private void autoResolveIntermediates(ToolContext context, RecipeManager recipeManager,
                                          RegistryAccess registryAccess, List<Ingredient> ingredients,
                                          int craftsNeeded, StringBuilder log) {
        for (int pass = 0; pass < MAX_RESOLVE_PASSES; pass++) {
            boolean madeProgress = false;

            Map<Item, Integer> grouped = getGroupedNeeds(ingredients, craftsNeeded);

            for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
                Item neededItem = entry.getKey();
                int totalNeeded = entry.getValue();

                Ingredient matchingIng = findIngredientFor(ingredients, neededItem);
                if (matchingIng == null) continue;

                int have = countInInventory(context, matchingIng);
                if (have >= totalNeeded) continue;

                int deficit = totalNeeded - have;
                if (tryAutoCraftItem(context, recipeManager, registryAccess, matchingIng, deficit, log)) {
                    madeProgress = true;
                }
            }

            if (!madeProgress) break;
        }
    }

    /**
     * Attempts to auto-craft enough of a missing ingredient using crafting-table recipes.
     * Tries each item variant the ingredient accepts (e.g., any plank type).
     */
    private boolean tryAutoCraftItem(ToolContext context, RecipeManager recipeManager,
                                      RegistryAccess registryAccess, Ingredient ingredient,
                                      int deficit, StringBuilder log) {
        for (ItemStack variant : ingredient.getItems()) {
            Item targetItem = variant.getItem();
            RecipeHolder<?> subRecipe = findCraftingTableRecipe(recipeManager, registryAccess, targetItem);
            if (subRecipe == null) continue;

            Recipe<?> sub = subRecipe.value();
            int outputPerCraft = sub.getResultItem(registryAccess).getCount();
            List<Ingredient> subIngs = sub.getIngredients();

            int maxSub = calculateMaxCrafts(context, subIngs);
            if (maxSub <= 0) continue;

            int subCraftsNeeded = (int) Math.ceil((double) deficit / outputPerCraft);
            int actualSub = Math.min(subCraftsNeeded, maxSub);

            // Consume sub-ingredients
            for (int c = 0; c < actualSub; c++) {
                for (Ingredient si : subIngs) {
                    if (si.isEmpty()) continue;
                    consumeIngredient(context, si, 1);
                }
            }

            // Place results where countInInventory can find them
            int produced = actualSub * outputPerCraft;
            ItemStack subResult = new ItemStack(targetItem, produced);
            if (!context.player().getInventory().add(subResult)) {
                SimpleContainer companionInv = getCompanionInventory(context);
                if (companionInv != null) {
                    ItemStack rem = companionInv.addItem(subResult);
                    if (!rem.isEmpty()) context.player().drop(rem, false);
                } else {
                    context.player().drop(subResult, false);
                }
            }

            log.append("Auto-crafted ").append(produced).append("x ")
               .append(targetItem.getDescription().getString()).append(". ");
            return true;
        }
        return false;
    }

    // ========== Missing ingredients report ==========

    private String buildMissingReport(ToolContext context, RecipeManager recipeManager,
                                       RegistryAccess registryAccess, Item targetItem,
                                       List<Ingredient> ingredients, int craftsNeeded,
                                       StringBuilder craftLog) {
        StringBuilder result = new StringBuilder();
        if (craftLog.length() > 0) result.append(craftLog);

        result.append("Missing ingredients for ")
              .append(targetItem.getDescription().getString()).append(":\n");

        Map<Item, Integer> grouped = getGroupedNeeds(ingredients, craftsNeeded);

        for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
            Item ingItem = entry.getKey();
            int needed = entry.getValue();
            Ingredient ing = findIngredientFor(ingredients, ingItem);
            int have = ing != null ? countInInventory(context, ing) : 0;
            if (have >= needed) continue;

            result.append("  - ").append(ingItem.getDescription().getString())
                  .append(": have ").append(have).append(", need ").append(needed);

            // Hint: can this be smelted?
            RecipeHolder<?> smeltRecipe = findSmeltingRecipe(recipeManager, registryAccess, ingItem);
            if (smeltRecipe != null) {
                String inputName = getSmeltInputName(smeltRecipe);
                result.append(" (smelt from ").append(inputName).append(" using smelt_items)");
            }
            result.append("\n");
        }
        return result.toString();
    }

    // ========== Recipe lookup ==========

    /**
     * Find a Shaped or Shapeless recipe that produces the target item.
     * Explicitly excludes smelting/blasting/smoking/campfire recipes.
     */
    private RecipeHolder<?> findCraftingTableRecipe(RecipeManager recipeManager,
                                                     RegistryAccess registryAccess, Item targetItem) {
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> r = holder.value();
            if (!(r instanceof ShapedRecipe) && !(r instanceof ShapelessRecipe)) continue;
            if (r.getResultItem(registryAccess).getItem() == targetItem) {
                return holder;
            }
        }
        return null;
    }

    /**
     * Find a smelting, blasting, smoking, or campfire recipe that produces the target item.
     */
    private RecipeHolder<?> findSmeltingRecipe(RecipeManager recipeManager,
                                                RegistryAccess registryAccess, Item targetItem) {
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> r = holder.value();
            if (r instanceof SmeltingRecipe || r instanceof BlastingRecipe
                    || r instanceof SmokingRecipe || r instanceof CampfireCookingRecipe) {
                if (r.getResultItem(registryAccess).getItem() == targetItem) {
                    return holder;
                }
            }
        }
        return null;
    }

    /**
     * Get the registry path of the first input ingredient for a smelting recipe.
     * Returns something like "raw_iron" that the AI can use in the smelt_items tool.
     */
    private String getSmeltInputName(RecipeHolder<?> smeltRecipe) {
        List<Ingredient> ings = smeltRecipe.value().getIngredients();
        if (!ings.isEmpty()) {
            ItemStack[] items = ings.get(0).getItems();
            if (items.length > 0) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(items[0].getItem());
                if (id != null) return id.getPath();
            }
        }
        return "unknown";
    }

    // ========== Ingredient helpers ==========

    /**
     * Groups ingredient slots by representative item and sums totals.
     * e.g., iron_pickaxe → {iron_ingot: 3, stick: 2}
     */
    private Map<Item, Integer> getGroupedNeeds(List<Ingredient> ingredients, int craftsNeeded) {
        Map<Item, Integer> grouped = new LinkedHashMap<>();
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) continue;
            ItemStack[] items = ing.getItems();
            if (items.length == 0) continue;
            grouped.merge(items[0].getItem(), craftsNeeded, Integer::sum);
        }
        return grouped;
    }

    /**
     * Finds the Ingredient in the list that matches the given item.
     */
    private Ingredient findIngredientFor(List<Ingredient> ingredients, Item item) {
        ItemStack testStack = new ItemStack(item);
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) continue;
            if (ing.test(testStack)) return ing;
        }
        return null;
    }

    // ========== Inventory helpers ==========

    private SimpleContainer getCompanionInventory(ToolContext context) {
        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        return companion != null ? companion.getCompanionInventory() : null;
    }

    private int calculateMaxCrafts(ToolContext context, List<Ingredient> ingredients) {
        int maxCrafts = Integer.MAX_VALUE;
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) continue;
            int available = countInInventory(context, ing);
            maxCrafts = Math.min(maxCrafts, available);
        }
        return maxCrafts == Integer.MAX_VALUE ? 0 : maxCrafts;
    }

    /**
     * Counts matching items across both player and companion inventories.
     */
    private int countInInventory(ToolContext context, Ingredient ingredient) {
        int count = 0;

        var playerInv = context.player().getInventory();
        for (int i = 0; i < playerInv.getContainerSize(); i++) {
            ItemStack stack = playerInv.getItem(i);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                count += stack.getCount();
            }
        }

        SimpleContainer companionInv = getCompanionInventory(context);
        if (companionInv != null) {
            for (int i = 0; i < companionInv.getContainerSize(); i++) {
                ItemStack stack = companionInv.getItem(i);
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    count += stack.getCount();
                }
            }
        }

        return count;
    }

    /**
     * Consumes ingredient from player inventory first, then companion inventory.
     */
    private void consumeIngredient(ToolContext context, Ingredient ingredient, int amount) {
        int remaining = amount;

        var playerInv = context.player().getInventory();
        for (int i = 0; i < playerInv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = playerInv.getItem(i);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                int toRemove = Math.min(stack.getCount(), remaining);
                stack.shrink(toRemove);
                if (stack.isEmpty()) playerInv.setItem(i, ItemStack.EMPTY);
                remaining -= toRemove;
            }
        }

        if (remaining > 0) {
            SimpleContainer companionInv = getCompanionInventory(context);
            if (companionInv != null) {
                for (int i = 0; i < companionInv.getContainerSize() && remaining > 0; i++) {
                    ItemStack stack = companionInv.getItem(i);
                    if (!stack.isEmpty() && ingredient.test(stack)) {
                        int toRemove = Math.min(stack.getCount(), remaining);
                        stack.shrink(toRemove);
                        if (stack.isEmpty()) companionInv.setItem(i, ItemStack.EMPTY);
                        remaining -= toRemove;
                    }
                }
            }
        }
    }

    // ========== Item resolution ==========

    private Item resolveItem(String query) {
        // Exact match by ID or display name
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String name = entry.getValue().getDescription().getString().toLowerCase();
            if (id.toString().equals(query) || id.getPath().equals(query) ||
                    name.equals(query) || id.getPath().equals(query.replace(" ", "_"))) {
                return entry.getValue();
            }
        }

        // Fallback to partial match
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String name = entry.getValue().getDescription().getString().toLowerCase();
            if (id.getPath().contains(query) || name.contains(query)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
