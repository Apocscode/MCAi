package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.ai.planner.RecipeResolver;
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
        return "Smelt items at a nearby furnace. Companion walks to furnace, inserts items+fuel, waits real time, collects output. " +
                "Needs furnace within 32 blocks + fuel in companion inventory. " +
                "You can specify the raw material (e.g. 'raw_iron') OR the desired output (e.g. 'iron_ingot') and the tool will find the right recipe. " +
                "Works for raw ores, sand, cobblestone, raw food.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description",
                "Item to smelt — can be the raw material (e.g. 'raw_iron') OR the desired output (e.g. 'iron_ingot'). " +
                        "The tool automatically finds the correct smelting recipe either way. " +
                        "Examples: 'raw_iron', 'iron_ingot', 'raw_gold', 'gold_ingot', 'sand', 'cobblestone', 'beef'");
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
            Item resolvedItem = resolveItem(itemName);
            if (resolvedItem == null) {
                return "Unknown item: '" + itemName + "'. " +
                        "Try using the Minecraft ID like 'raw_iron', 'sand', 'cobblestone'.";
            }

            // Try as smelting INPUT first (e.g. 'raw_iron')
            RegistryAccess registryAccess = context.server().registryAccess();
            RecipeManager recipeManager = context.server().getRecipeManager();
            RecipeHolder<?> smeltRecipe = findSmeltRecipeForInput(recipeManager, resolvedItem);
            Item inputItem = resolvedItem;

            // If no recipe with this as input, try as OUTPUT (e.g. 'iron_ingot' → find raw_iron)
            if (smeltRecipe == null) {
                smeltRecipe = findSmeltRecipeForOutput(recipeManager, registryAccess, resolvedItem);
                if (smeltRecipe != null) {
                    // Extract the actual input item from the recipe
                    List<Ingredient> ings = smeltRecipe.value().getIngredients();
                    if (!ings.isEmpty()) {
                        ItemStack[] stacks = ings.get(0).getItems();
                        if (stacks.length > 0) {
                            inputItem = stacks[0].getItem();
                        }
                    }
                }
            }

            if (smeltRecipe == null) {
                return resolvedItem.getDescription().getString() + " cannot be smelted. " +
                        "There's no smelting recipe for this item (as input or output).";
            }

            String outputName;
            ItemStack outputStack = RecipeResolver.safeGetResult(smeltRecipe.value(), registryAccess);
            if (!outputStack.isEmpty()) {
                outputName = outputStack.getItem().getDescription().getString();
            } else {
                outputName = "smelted item";
            }

            // Check companion inventory first, then try storage/home containers
            int available = BlockHelper.countItem(companion, inputItem);
            if (available < count) {
                // Try to pull missing items from tagged STORAGE + home area containers
                int needed = count - available;
                int pulled = pullFromStorage(companion, inputItem, needed);
                available += pulled;
            }
            if (available < count) {
                return "Only have " + available + "x " +
                        inputItem.getDescription().getString() +
                        " (need " + count + ") across companion inventory and storage. " +
                        "Use transfer_items to provide materials, " +
                        "or have the companion mine/gather first.";
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

            return "[ASYNC_TASK] Queued smelting: " + count + "x " + inputItem.getDescription().getString() +
                    " → " + outputName + ". The companion will find a nearby furnace, " +
                    "load items + fuel, and wait for smelting to complete (real game time). " +
                    "STOP calling tools — tell the player you're working on it.";
        });
    }

    /**
     * Find a smelting/blasting recipe that uses the given item as INPUT.
     */
    private RecipeHolder<?> findSmeltRecipeForInput(RecipeManager recipeManager, Item inputItem) {
        ItemStack testStack = new ItemStack(inputItem);
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                if (!(r instanceof SmeltingRecipe) && !(r instanceof BlastingRecipe)
                        && !(r instanceof SmokingRecipe) && !(r instanceof CampfireCookingRecipe)) {
                    continue;
                }
                List<Ingredient> ings = r.getIngredients();
                if (ings != null && !ings.isEmpty() && ings.get(0) != null && ings.get(0).test(testStack)) {
                    return holder;
                }
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }

    /**
     * Reverse lookup: find a smelting recipe that PRODUCES the given item as OUTPUT.
     * e.g. given iron_ingot, finds the raw_iron → iron_ingot recipe.
     * Prefers recipes with raw_ inputs (actual drops) over ore block inputs.
     */
    private RecipeHolder<?> findSmeltRecipeForOutput(RecipeManager recipeManager,
                                                      RegistryAccess registryAccess, Item outputItem) {
        RecipeHolder<?> bestMatch = null;
        boolean bestIsRaw = false;

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                if (!(r instanceof SmeltingRecipe) && !(r instanceof BlastingRecipe)
                        && !(r instanceof SmokingRecipe) && !(r instanceof CampfireCookingRecipe)) {
                    continue;
                }
                ItemStack result = RecipeResolver.safeGetResult(r, registryAccess);
                if (!result.isEmpty() && result.is(outputItem)) {
                    // Check if the input is a raw_ item (preferred — actual mining drops)
                    boolean isRaw = false;
                    List<Ingredient> ings = r.getIngredients();
                    if (!ings.isEmpty()) {
                        ItemStack[] stacks = ings.get(0).getItems();
                        if (stacks.length > 0) {
                            ResourceLocation inputId = BuiltInRegistries.ITEM.getKey(stacks[0].getItem());
                            isRaw = inputId.getPath().startsWith("raw_");
                        }
                    }
                    if (bestMatch == null || (isRaw && !bestIsRaw)) {
                        bestMatch = holder;
                        bestIsRaw = isRaw;
                    }
                }
            } catch (Exception e) {
                continue;
            }
        }
        return bestMatch;
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

    /**
     * Pull items from tagged STORAGE containers and home area containers into companion inventory.
     *
     * @return number of items actually pulled
     */
    private int pullFromStorage(CompanionEntity companion, Item item, int needed) {
        int pulled = 0;
        var inv = companion.getCompanionInventory();
        java.util.Set<net.minecraft.core.BlockPos> scanned = new java.util.HashSet<>();

        // Tagged STORAGE containers
        var storageBlocks = companion.getTaggedBlocks(
                com.apocscode.mcai.logistics.TaggedBlock.Role.STORAGE);
        for (var tb : storageBlocks) {
            if (pulled >= needed) break;
            scanned.add(tb.pos());
            pulled += extractFromContainer(companion.level(), tb.pos(), inv, item, needed - pulled);
        }

        // All containers within home area
        if (pulled < needed && companion.hasHomeArea()) {
            net.minecraft.core.BlockPos c1 = companion.getHomeCorner1();
            net.minecraft.core.BlockPos c2 = companion.getHomeCorner2();
            if (c1 != null && c2 != null) {
                int minX = Math.min(c1.getX(), c2.getX());
                int minY = Math.min(c1.getY(), c2.getY());
                int minZ = Math.min(c1.getZ(), c2.getZ());
                int maxX = Math.max(c1.getX(), c2.getX());
                int maxY = Math.max(c1.getY(), c2.getY());
                int maxZ = Math.max(c1.getZ(), c2.getZ());
                for (int x = minX; x <= maxX && pulled < needed; x++) {
                    for (int y = minY; y <= maxY && pulled < needed; y++) {
                        for (int z = minZ; z <= maxZ && pulled < needed; z++) {
                            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                            if (scanned.contains(pos)) continue;
                            scanned.add(pos);
                            pulled += extractFromContainer(companion.level(), pos, inv, item, needed - pulled);
                        }
                    }
                }
            }
        }
        return pulled;
    }

    /**
     * Extract items from a container block entity into companion inventory.
     *
     * @return number of items extracted
     */
    private int extractFromContainer(net.minecraft.world.level.Level level,
                                     net.minecraft.core.BlockPos pos,
                                     net.minecraft.world.SimpleContainer inv,
                                     Item item, int maxExtract) {
        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof net.minecraft.world.Container container)) return 0;

        int extracted = 0;
        for (int i = 0; i < container.getContainerSize() && extracted < maxExtract; i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(maxExtract - extracted, stack.getCount());
                ItemStack toInsert = stack.copy();
                toInsert.setCount(take);
                ItemStack remainder = inv.addItem(toInsert);
                int actuallyInserted = take - remainder.getCount();
                if (actuallyInserted > 0) {
                    stack.shrink(actuallyInserted);
                    if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                    extracted += actuallyInserted;
                }
                if (!remainder.isEmpty()) break; // Companion inventory full
            }
        }
        if (extracted > 0) {
            container.setChanged();
        }
        return extracted;
    }
}
