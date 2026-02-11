package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Looks up crafting recipes from the game's recipe manager.
 * Works with vanilla and all loaded mod recipes.
 */
public class GetRecipeTool implements AiTool {

    @Override
    public String name() {
        return "get_recipe";
    }

    @Override
    public String description() {
        return "Look up the crafting recipe for an item. Searches all loaded recipes " +
                "including vanilla and mod recipes (Create, Mekanism, etc.). " +
                "Provide the item name or registry ID.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();
        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description",
                "Item name or registry ID to look up. Examples: 'iron_pickaxe', 'minecraft:piston', 'mekanism:digital_miner'");
        props.add("item", item);
        schema.add("properties", props);

        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        required.add("item");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null || context.server() == null) {
            return "Error: no server context";
        }

        String query = args.has("item") ? args.get("item").getAsString().trim().toLowerCase() : "";
        if (query.isBlank()) return "Error: no item specified";

        // Find matching items
        List<Item> matches = new ArrayList<>();
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String name = entry.getValue().getDescription().getString().toLowerCase();

            if (id.toString().equals(query) || id.getPath().equals(query) ||
                    name.equals(query) || id.getPath().contains(query) ||
                    name.contains(query)) {
                matches.add(entry.getValue());
                if (matches.size() >= 5) break; // Limit matches
            }
        }

        if (matches.isEmpty()) {
            return "No items found matching '" + query + "'. Try a different name or check spelling.";
        }

        StringBuilder sb = new StringBuilder();
        RecipeManager recipeManager = context.server().getRecipeManager();

        for (Item item : matches) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            String displayName = item.getDescription().getString();
            sb.append("=== ").append(displayName).append(" (").append(itemId).append(") ===\n");

            // Search all recipes for this item output
            boolean foundRecipe = false;

            for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
                Recipe<?> recipe = holder.value();
                ItemStack result = recipe.getResultItem(context.server().registryAccess());

                if (result.getItem() == item) {
                    foundRecipe = true;
                    sb.append("\nRecipe type: ").append(holder.id()).append("\n");

                    if (recipe instanceof ShapedRecipe shaped) {
                        sb.append("Shaped crafting (")
                                .append(shaped.getWidth()).append("x").append(shaped.getHeight()).append("):\n");
                        appendIngredients(sb, shaped.getIngredients());
                        sb.append("Output: ").append(result.getCount()).append("x ").append(displayName).append("\n");
                    } else if (recipe instanceof ShapelessRecipe shapeless) {
                        sb.append("Shapeless crafting:\n");
                        appendIngredients(sb, shapeless.getIngredients());
                        sb.append("Output: ").append(result.getCount()).append("x ").append(displayName).append("\n");
                    } else if (recipe instanceof SmeltingRecipe || recipe instanceof BlastingRecipe ||
                            recipe instanceof SmokingRecipe || recipe instanceof CampfireCookingRecipe) {
                        sb.append("Smelting/Cooking:\n");
                        appendIngredients(sb, recipe.getIngredients());
                        sb.append("Output: ").append(result.getCount()).append("x ").append(displayName).append("\n");
                    } else {
                        // Mod recipe — show what we can
                        sb.append("Type: ").append(recipe.getClass().getSimpleName()).append("\n");
                        List<Ingredient> ingredients = recipe.getIngredients();
                        if (!ingredients.isEmpty()) {
                            appendIngredients(sb, ingredients);
                        }
                        sb.append("Output: ").append(result.getCount()).append("x ").append(displayName).append("\n");
                    }
                    sb.append("\n");

                    // Limit to 3 recipes per item to avoid huge output
                    break;
                }
            }

            if (!foundRecipe) {
                sb.append("No crafting recipe found (may be obtained through mining, trading, or other means).\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private void appendIngredients(StringBuilder sb, List<Ingredient> ingredients) {
        sb.append("Ingredients:\n");
        int slot = 1;
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) {
                slot++;
                continue;
            }
            ItemStack[] stacks = ing.getItems();
            if (stacks.length > 0) {
                sb.append("  ").append(slot).append(". ");
                if (stacks.length == 1) {
                    sb.append(stacks[0].getDisplayName().getString());
                } else {
                    // Tag-based ingredient — show options
                    sb.append("Any of: ");
                    for (int i = 0; i < Math.min(stacks.length, 4); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(stacks[i].getDisplayName().getString());
                    }
                    if (stacks.length > 4) sb.append(" (+" + (stacks.length - 4) + " more)");
                }
                sb.append("\n");
            }
            slot++;
        }
    }
}
