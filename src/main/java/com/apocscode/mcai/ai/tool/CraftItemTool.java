package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafts items using the player's inventory resources.
 * Finds the recipe, checks if the player has the ingredients,
 * consumes them, and gives the result — all in one step.
 *
 * Works with shaped, shapeless, and smelting recipes from vanilla + mods.
 * For smelting recipes, gives the result directly (simulates having a furnace).
 */
public class CraftItemTool implements AiTool {

    @Override
    public String name() {
        return "craft_item";
    }

    @Override
    public String description() {
        return "Craft an item using materials from the player's inventory. " +
                "Automatically looks up the recipe, checks ingredients, " +
                "consumes materials, and gives the crafted item. " +
                "Supports crafting multiple batches. " +
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

        // Find the target item
        Item targetItem = null;
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String name = entry.getValue().getDescription().getString().toLowerCase();
            if (id.toString().equals(itemQuery) || id.getPath().equals(itemQuery) ||
                    name.equals(itemQuery) || id.getPath().equals(itemQuery.replace(" ", "_"))) {
                targetItem = entry.getValue();
                break;
            }
        }

        // Fallback to partial match
        if (targetItem == null) {
            for (var entry : BuiltInRegistries.ITEM.entrySet()) {
                ResourceLocation id = entry.getKey().location();
                String name = entry.getValue().getDescription().getString().toLowerCase();
                if (id.getPath().contains(itemQuery) || name.contains(itemQuery)) {
                    targetItem = entry.getValue();
                    break;
                }
            }
        }

        if (targetItem == null) {
            return "No item found matching '" + itemQuery + "'. Check the item name.";
        }

        // Find a recipe that produces this item
        RecipeManager recipeManager = context.server().getRecipeManager();
        RecipeHolder<?> matchedRecipe = null;

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            ItemStack result = holder.value().getResultItem(context.server().registryAccess());
            if (result.getItem() == targetItem) {
                // Prefer crafting recipes over smelting
                if (holder.value() instanceof ShapedRecipe || holder.value() instanceof ShapelessRecipe) {
                    matchedRecipe = holder;
                    break;
                }
                if (matchedRecipe == null) {
                    matchedRecipe = holder;
                }
            }
        }

        if (matchedRecipe == null) {
            String itemName = targetItem.getDescription().getString();
            return "No crafting recipe found for '" + itemName + "'. " +
                    "It might only be obtainable through mining, trading, or other means.";
        }

        // Execute crafting on server thread
        final RecipeHolder<?> recipe = matchedRecipe;
        final int finalCount = requestedCount;
        final Item finalTarget = targetItem;

        return context.runOnServer(() -> {
            Recipe<?> r = recipe.value();
            ItemStack recipeResult = r.getResultItem(context.server().registryAccess());
            int outputPerCraft = recipeResult.getCount();
            List<Ingredient> ingredients = r.getIngredients();

            // Calculate how many crafts we need
            int craftsNeeded = (int) Math.ceil((double) finalCount / outputPerCraft);

            // Check how many crafts we can actually do
            int maxCrafts = calculateMaxCrafts(context, ingredients);

            if (maxCrafts == 0) {
                StringBuilder missing = new StringBuilder();
                missing.append("Missing ingredients for ").append(finalTarget.getDescription().getString()).append(":\n");
                for (Ingredient ing : ingredients) {
                    if (ing.isEmpty()) continue;
                    ItemStack[] stacks = ing.getItems();
                    if (stacks.length > 0) {
                        String ingName = stacks[0].getDisplayName().getString();
                        int have = countInInventory(context, ing);
                        missing.append("  - ").append(ingName).append(": have ").append(have).append("\n");
                    }
                }
                return missing.toString();
            }

            int actualCrafts = Math.min(craftsNeeded, maxCrafts);
            int totalOutput = actualCrafts * outputPerCraft;

            // Consume ingredients
            for (int craft = 0; craft < actualCrafts; craft++) {
                for (Ingredient ing : ingredients) {
                    if (ing.isEmpty()) continue;
                    consumeIngredient(context, ing, 1);
                }
            }

            // Give result
            ItemStack result = new ItemStack(finalTarget, totalOutput);
            if (!context.player().getInventory().add(result)) {
                // Drop on ground if inventory full
                context.player().drop(result, false);
            }

            String itemName = finalTarget.getDescription().getString();
            StringBuilder sb = new StringBuilder();
            sb.append("Crafted ").append(totalOutput).append("x ").append(itemName);

            if (totalOutput < finalCount) {
                sb.append(" (wanted ").append(finalCount)
                        .append(" but only had materials for ").append(totalOutput).append(")");
            }

            sb.append(" using ").append(actualCrafts).append(" craft(s).");
            return sb.toString();
        });
    }

    private int calculateMaxCrafts(ToolContext context, List<Ingredient> ingredients) {
        int maxCrafts = Integer.MAX_VALUE;

        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) continue;

            int available = countInInventory(context, ing);
            // Each craft needs 1 of this ingredient
            maxCrafts = Math.min(maxCrafts, available);
        }

        return maxCrafts == Integer.MAX_VALUE ? 0 : maxCrafts;
    }

    private int countInInventory(ToolContext context, Ingredient ingredient) {
        int count = 0;
        var inv = context.player().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void consumeIngredient(ToolContext context, Ingredient ingredient, int amount) {
        int remaining = amount;
        var inv = context.player().getInventory();
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && ingredient.test(stack)) {
                int toRemove = Math.min(stack.getCount(), remaining);
                stack.shrink(toRemove);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                remaining -= toRemove;
            }
        }
    }
}
