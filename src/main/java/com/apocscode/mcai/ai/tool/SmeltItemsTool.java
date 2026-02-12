package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.SmeltItemsTask;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;

import java.util.List;

/**
 * AI Tool: Smelt items using a nearby furnace.
 * The companion pathfinds to the furnace, loads materials + fuel,
 * waits for smelting to complete (real game time), and collects the output.
 *
 * Respects game balance — no instant conversion.
 */
public class SmeltItemsTool implements AiTool {

    @Override
    public String name() {
        return "smelt_items";
    }

    @Override
    public String description() {
        return "Smelt items using a nearby furnace, blast furnace, or smoker. " +
                "The companion pathfinds to the nearest furnace, inserts items and fuel from its inventory, " +
                "waits for smelting to complete (real game time), and collects the output. " +
                "Requires: furnace within 32 blocks + fuel (coal, charcoal, wood) in companion inventory. " +
                "Use for: raw_iron → iron_ingot, raw_gold → gold_ingot, sand → glass, " +
                "raw_copper → copper_ingot, cobblestone → stone, raw food → cooked food. " +
                "Items must be in the companion's inventory (they pick up mined items automatically).";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description",
                "Raw material to smelt. Examples: 'raw_iron', 'raw_gold', 'sand', " +
                        "'cobblestone', 'raw_copper', 'beef', 'porkchop'");
        props.add("item", item);

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "Number of items to smelt. Default: 1. Max: 64.");
        props.add("count", count);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description",
                "Optional: describe what to do AFTER smelting completes. " +
                        "Example: 'transfer iron_ingot to player then craft iron_pickaxe'. " +
                        "If set, the AI will automatically continue the plan when smelting finishes.");
        props.add("plan", plan);

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

        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            String itemName = args.get("item").getAsString().toLowerCase().trim();
            int count = args.has("count") ? args.get("count").getAsInt() : 1;
            count = Math.max(1, Math.min(count, 64));

            // Resolve item
            Item inputItem = resolveItem(itemName);
            if (inputItem == null) {
                return "Unknown item: '" + itemName + "'. " +
                        "Try using the Minecraft ID like 'raw_iron', 'sand', 'cobblestone'.";
            }

            // Verify a smelting recipe exists for this input
            RegistryAccess registryAccess = context.server().registryAccess();
            RecipeManager recipeManager = context.server().getRecipeManager();
            RecipeHolder<?> smeltRecipe = findSmeltRecipeForInput(recipeManager, inputItem);

            if (smeltRecipe == null) {
                return inputItem.getDescription().getString() + " cannot be smelted. " +
                        "There's no smelting recipe for this item.";
            }

            String outputName = smeltRecipe.value().getResultItem(registryAccess)
                    .getItem().getDescription().getString();

            // Check companion inventory
            int available = BlockHelper.countItem(companion, inputItem);
            if (available < count) {
                return "Companion only has " + available + "x " +
                        inputItem.getDescription().getString() +
                        " (need " + count + "). " +
                        "Use transfer_items to give them the materials, " +
                        "or have them mine/gather first.";
            }

            // Create and queue the task
            SmeltItemsTask task = new SmeltItemsTask(companion, inputItem, count);

            // Attach continuation plan if provided
            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                String planText = args.get("plan").getAsString();
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Smelt " + count + "x " + inputItem.getDescription().getString() +
                                " → " + outputName + ", then: " + planText,
                        planText
                ));
            }

            companion.getTaskManager().queueTask(task);

            return "Queued smelting: " + count + "x " + inputItem.getDescription().getString() +
                    " → " + outputName + ". The companion will find a nearby furnace, " +
                    "load items + fuel, and wait for smelting to complete (real game time). " +
                    "Use task_status to check progress.";
        });
    }

    /**
     * Find a smelting/blasting recipe that uses the given item as INPUT.
     */
    private RecipeHolder<?> findSmeltRecipeForInput(RecipeManager recipeManager, Item inputItem) {
        ItemStack testStack = new ItemStack(inputItem);
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> r = holder.value();
            if (!(r instanceof SmeltingRecipe) && !(r instanceof BlastingRecipe)
                    && !(r instanceof SmokingRecipe) && !(r instanceof CampfireCookingRecipe)) {
                continue;
            }
            List<Ingredient> ings = r.getIngredients();
            if (!ings.isEmpty() && ings.get(0).test(testStack)) {
                return holder;
            }
        }
        return null;
    }

    private Item resolveItem(String query) {
        String normalized = query.replace(" ", "_");

        // Exact match
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String name = entry.getValue().getDescription().getString().toLowerCase();
            if (id.getPath().equals(normalized) || id.toString().equals(query) ||
                    name.equals(query) || name.replace(" ", "_").equals(normalized)) {
                return entry.getValue();
            }
        }

        // Partial match
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String name = entry.getValue().getDescription().getString().toLowerCase();
            if (id.getPath().contains(normalized) || name.contains(query)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
