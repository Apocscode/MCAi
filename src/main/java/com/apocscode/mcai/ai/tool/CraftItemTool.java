package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.planner.CraftingPlan;
import com.apocscode.mcai.ai.planner.RecipeResolver;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.ItemRoutingHelper;
import com.apocscode.mcai.task.ChopTreesTask;
import com.apocscode.mcai.task.CompanionTask;
import com.apocscode.mcai.task.GatherBlocksTask;
import com.apocscode.mcai.task.MineOresTask;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Smart crafting tool that behaves like a real player:
 *
 * 1. Check if the player already has the item
 * 2. Check nearby chests/containers for the finished item
 * 3. Check nearby chests for crafting materials and auto-pull them
 * 4. Auto-resolve intermediate crafting-table recipes (logs→planks→sticks)
 * 5. If still missing materials → return actionable gathering hints
 * 6. Craft the item
 *
 * Only uses Shaped and Shapeless recipes — smelting requires the smelt_items tool.
 * Balance: 2x2 recipes (planks, sticks, etc.) can be crafted from inventory.
 * 3x3 recipes require a crafting table within 8 blocks, OR a portable crafting
 * item (e.g. Sophisticated Backpacks crafting upgrade, Crafting on a Stick).
 * Smelting is NOT bypassed — requires a real furnace, fuel, and game time.
 */
public class CraftItemTool implements AiTool {

    private static final int MAX_RESOLVE_PASSES = 3;
    private static final int CHEST_SCAN_RADIUS = 16;
    private static final int CRAFTING_TABLE_RADIUS = 8;

    @Override
    public String name() {
        return "craft_item";
    }

    @Override
    public String description() {
        return "Smart crafting: checks player inventory, nearby chests, auto-pulls materials, " +
                "resolves intermediates (logs→planks→sticks), then crafts. Like a real player would. " +
                "2x2 recipes (planks, sticks, torches) can be crafted from inventory. " +
                "3x3 recipes (pickaxes, armor, etc.) REQUIRE a crafting table within 8 blocks " +
                "or a portable crafting item in inventory. For smelting, use smelt_items.";
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
            StringBuilder craftLog = new StringBuilder();

            // --- Resolve item ---
            Item targetItem = resolveItem(finalQuery);
            if (targetItem == null) {
                return "No item found matching '" + finalQuery + "'. Check the item name.";
            }

            String targetName = targetItem.getDescription().getString();

            // === PHASE 0: Check if player already has the item ===
            int alreadyHave = countItemInInventory(context, targetItem);
            if (alreadyHave >= finalCount) {
                return "You already have " + alreadyHave + "x " + targetName + " in your inventory!";
            }

            // === PHASE 1: Check nearby chests for the finished item ===
            int fetched = fetchFromNearbyContainers(context, targetItem, finalCount - alreadyHave);
            if (fetched > 0) {
                int totalNow = alreadyHave + fetched;
                craftLog.append("Found ").append(fetched).append("x ").append(targetName)
                        .append(" in nearby chest. ");
                if (totalNow >= finalCount) {
                    return craftLog.append("You now have ").append(totalNow).append("x ")
                            .append(targetName).append("!").toString();
                }
                craftLog.append("Still need ").append(finalCount - totalNow).append(" more. ");
            }

            // === PHASE 2: Find recipe (prefers recipes player has materials for) ===
            RecipeHolder<?> craftRecipe = findCraftingTableRecipe(recipeManager, registryAccess, targetItem, context);

            if (craftRecipe == null) {
                // Check if a smelting recipe exists → redirect to smelt_items
                RecipeHolder<?> smeltRecipe = findSmeltingRecipe(recipeManager, registryAccess, targetItem);
                if (smeltRecipe != null) {
                    String inputName = getSmeltInputName(smeltRecipe);
                    return craftLog.toString() + targetName + " requires smelting, not a crafting table. " +
                            "Use the smelt_items tool with '" + inputName + "'. " +
                            "The companion needs a furnace nearby and fuel in its inventory.";
                }
                return craftLog.toString() + "No crafting recipe found for '" + targetName + "'. " +
                        "It might only be obtainable through mining, trading, or other means.";
            }

            Recipe<?> recipe = craftRecipe.value();
            ItemStack recipeResult = recipe.getResultItem(registryAccess);
            int outputPerCraft = recipeResult.getCount();
            List<Ingredient> ingredients = recipe.getIngredients();
            int stillNeed = finalCount - (alreadyHave + fetched);
            int craftsNeeded = (int) Math.ceil((double) stillNeed / outputPerCraft);

            // === PHASE 3: Auto-fetch materials from nearby chests ===
            int materialsFetched = fetchMaterialsFromContainers(context, ingredients, craftsNeeded, craftLog);
            if (materialsFetched > 0) {
                MCAi.LOGGER.info("Auto-fetched {} material stacks from nearby containers", materialsFetched);
            }

            // === PHASE 4: Auto-resolve intermediate crafting ingredients ===
            autoResolveIntermediates(context, recipeManager, registryAccess, ingredients, craftsNeeded, craftLog);

            // === PHASE 5: Check what we can craft now ===
            int maxCrafts = calculateMaxCrafts(context, ingredients);

            if (maxCrafts == 0) {
                return autoCraftPlan(context, recipeManager, registryAccess,
                        targetItem, finalCount, ingredients, craftsNeeded, craftLog);
            }

            // === PHASE 5.5: Ensure crafting station if 3x3 recipe ===
            boolean needs3x3 = !recipe.canCraftInDimensions(2, 2);
            CraftingBlockHelper.StationResult stationResult = null;
            if (needs3x3) {
                stationResult = CraftingBlockHelper.ensureStation(
                        CraftingBlockHelper.StationType.CRAFTING_TABLE, context);
                if (!stationResult.success) {
                    return craftLog.toString() + targetName + " requires a 3x3 crafting grid. " +
                            stationResult.message + " " +
                            "2x2 recipes like planks and sticks can still be crafted from inventory.";
                }
                if (!stationResult.message.isEmpty()) {
                    craftLog.append(stationResult.message).append(" ");
                }
            }

            // === PHASE 6: Execute the craft ===
            int actualCrafts = Math.min(craftsNeeded, maxCrafts);
            int totalOutput = actualCrafts * outputPerCraft;

            for (int craft = 0; craft < actualCrafts; craft++) {
                for (Ingredient ing : ingredients) {
                    if (ing.isEmpty()) continue;
                    consumeIngredient(context, ing, 1);
                }
            }

            ItemStack result = new ItemStack(targetItem, totalOutput);

            // Give the requested amount to the player
            int forPlayer = Math.min(totalOutput, stillNeed);
            ItemStack playerShare = new ItemStack(targetItem, forPlayer);
            if (!context.player().getInventory().add(playerShare)) {
                context.player().drop(playerShare, false);
            }

            // Route any excess to tagged storage (OUTPUT > STORAGE > companion inventory)
            int excess = totalOutput - forPlayer;
            String depositMsg = "";
            if (excess > 0) {
                CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
                if (companion != null && ItemRoutingHelper.hasTaggedStorage(companion)) {
                    ItemStack excessStack = new ItemStack(targetItem, excess);
                    depositMsg = " " + ItemRoutingHelper.routeToStorage(companion, excessStack);
                    // If any couldn't be routed, give to player
                    if (!excessStack.isEmpty()) {
                        if (!context.player().getInventory().add(excessStack)) {
                            context.player().drop(excessStack, false);
                        }
                    }
                } else {
                    // No tagged storage — give everything to player
                    ItemStack excessStack = new ItemStack(targetItem, excess);
                    if (!context.player().getInventory().add(excessStack)) {
                        context.player().drop(excessStack, false);
                    }
                }
            }

            // Pick up auto-placed crafting station if we placed one
            if (stationResult != null && stationResult.shouldPickUp) {
                CraftingBlockHelper.pickUpStation(stationResult, context);
                craftLog.append("Picked up crafting table. ");
            }

            StringBuilder sb = new StringBuilder();
            if (craftLog.length() > 0) sb.append(craftLog);
            sb.append("Crafted ").append(totalOutput).append("x ").append(targetName);
            if (forPlayer < totalOutput) {
                sb.append(" (").append(forPlayer).append(" to player");
                if (excess > 0) sb.append(", ").append(excess).append(" to storage");
                sb.append(")");
            }
            sb.append(".");
            return sb.toString();
        });
    }

    // ========== Container scanning (auto-fetch from chests) ==========

    /**
     * Scans nearby containers for the finished item and pulls it into the player's inventory.
     * Returns the number of items fetched.
     */
    private int fetchFromNearbyContainers(ToolContext context, Item targetItem, int maxToFetch) {
        Level level = context.player().level();
        BlockPos center = context.player().blockPosition();
        int fetched = 0;

        for (int x = -CHEST_SCAN_RADIUS; x <= CHEST_SCAN_RADIUS && fetched < maxToFetch; x++) {
            for (int y = -CHEST_SCAN_RADIUS; y <= CHEST_SCAN_RADIUS && fetched < maxToFetch; y++) {
                for (int z = -CHEST_SCAN_RADIUS; z <= CHEST_SCAN_RADIUS && fetched < maxToFetch; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockEntity be = level.getBlockEntity(pos);
                    if (!(be instanceof Container container)) continue;

                    for (int i = 0; i < container.getContainerSize() && fetched < maxToFetch; i++) {
                        ItemStack stack = container.getItem(i);
                        if (stack.isEmpty() || stack.getItem() != targetItem) continue;

                        int toTake = Math.min(stack.getCount(), maxToFetch - fetched);
                        ItemStack toInsert = stack.copyWithCount(toTake);

                        if (context.player().getInventory().add(toInsert)) {
                            int inserted = toTake - toInsert.getCount();
                            if (toInsert.isEmpty()) inserted = toTake;
                            stack.shrink(inserted);
                            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                            container.setChanged();
                            fetched += inserted;
                        }
                    }
                }
            }
        }
        return fetched;
    }

    /**
     * Scans nearby containers for missing crafting materials and pulls them into the player's inventory.
     * For each ingredient type, checks how many we need vs have, then fetches the deficit from chests.
     * If a direct ingredient isn't in any chest, recursively checks for sub-recipe materials
     * (e.g., sticks not found → look for planks → look for logs).
     * Returns the total number of material types successfully fetched.
     */
    private int fetchMaterialsFromContainers(ToolContext context, List<Ingredient> ingredients,
                                              int craftsNeeded, StringBuilder log) {
        RecipeManager recipeManager = context.server().getRecipeManager();
        RegistryAccess registryAccess = context.server().registryAccess();

        Map<Item, Integer> grouped = getGroupedNeeds(ingredients, craftsNeeded);
        int typesFetched = 0;

        for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
            Item neededItem = entry.getKey();
            int totalNeeded = entry.getValue();

            Ingredient matchingIng = findIngredientFor(ingredients, neededItem);
            if (matchingIng == null) continue;

            int have = countInInventory(context, matchingIng);
            if (have >= totalNeeded) continue;

            int deficit = totalNeeded - have;

            // Try to fetch this ingredient directly from nearby containers
            int totalFetchedForIng = 0;
            for (ItemStack variant : matchingIng.getItems()) {
                if (totalFetchedForIng >= deficit) break;
                int got = fetchFromNearbyContainers(context, variant.getItem(), deficit - totalFetchedForIng);
                totalFetchedForIng += got;
            }

            if (totalFetchedForIng > 0) {
                String itemName = neededItem.getDescription().getString();
                log.append("Pulled ").append(totalFetchedForIng).append("x ").append(itemName)
                   .append(" from nearby chest. ");
                typesFetched++;
            }

            // If we still don't have enough, try to fetch SUB-recipe materials from chests
            // e.g., sticks not in chest → fetch logs (which auto-resolve will convert to planks → sticks)
            have = countInInventory(context, matchingIng);
            if (have < totalNeeded) {
                int subFetched = fetchSubRecipeMaterials(context, recipeManager, registryAccess,
                        matchingIng, totalNeeded - have, log, 0);
                if (subFetched > 0) typesFetched++;
            }
        }
        return typesFetched;
    }

    /**
     * Recursively fetches sub-recipe raw materials from nearby chests.
     * e.g., need sticks → recipe needs planks → recipe needs logs → fetch logs from chest.
     * Returns number of base materials fetched. Max depth 3 to prevent infinite loops.
     */
    private int fetchSubRecipeMaterials(ToolContext context, RecipeManager recipeManager,
                                         RegistryAccess registryAccess, Ingredient ingredient,
                                         int deficit, StringBuilder log, int depth) {
        if (depth > MAX_RESOLVE_PASSES) return 0;
        int totalFetched = 0;

        for (ItemStack variant : ingredient.getItems()) {
            Item targetItem = variant.getItem();
            RecipeHolder<?> subRecipe = findCraftingTableRecipe(recipeManager, registryAccess, targetItem, context);
            if (subRecipe == null) continue;

            Recipe<?> sub = subRecipe.value();

            // Skip 3x3 sub-recipes if no crafting table access (can't auto-place during fetch phase)
            if (!sub.canCraftInDimensions(2, 2) && !hasCraftingAccess(context)) continue;

            int outputPerCraft = sub.getResultItem(registryAccess).getCount();
            List<Ingredient> subIngs = sub.getIngredients();
            int subCraftsNeeded = (int) Math.ceil((double) deficit / outputPerCraft);

            // For each sub-ingredient, try to fetch from chests
            for (Ingredient subIng : subIngs) {
                if (subIng.isEmpty()) continue;

                int subHave = countInInventory(context, subIng);
                int subNeed = subCraftsNeeded;
                if (subHave >= subNeed) continue;

                int subDeficit = subNeed - subHave;

                // Try direct fetch from containers
                int fetched = 0;
                for (ItemStack subVariant : subIng.getItems()) {
                    if (fetched >= subDeficit) break;
                    int got = fetchFromNearbyContainers(context, subVariant.getItem(), subDeficit - fetched);
                    fetched += got;
                }

                if (fetched > 0) {
                    String itemName = subIng.getItems()[0].getItem().getDescription().getString();
                    log.append("Pulled ").append(fetched).append("x ").append(itemName)
                       .append(" from nearby chest (for sub-recipe). ");
                    totalFetched += fetched;
                }

                // If still short, recurse deeper (logs for planks for sticks)
                subHave = countInInventory(context, subIng);
                if (subHave < subNeed) {
                    totalFetched += fetchSubRecipeMaterials(context, recipeManager, registryAccess,
                            subIng, subNeed - subHave, log, depth + 1);
                }
            }

            if (totalFetched > 0) return totalFetched; // Found materials for at least one variant
        }
        return totalFetched;
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
                if (tryAutoCraftItem(context, recipeManager, registryAccess, matchingIng, deficit, log, 0)) {
                    madeProgress = true;
                }
            }

            if (!madeProgress) break;
        }
    }

    /**
     * Attempts to auto-craft enough of a missing ingredient using crafting-table recipes.
     * Recursively resolves sub-ingredients (e.g., logs → planks → sticks) up to 3 levels deep.
     * Tries each item variant the ingredient accepts (e.g., any plank type).
     */
    private boolean tryAutoCraftItem(ToolContext context, RecipeManager recipeManager,
                                      RegistryAccess registryAccess, Ingredient ingredient,
                                      int deficit, StringBuilder log, int depth) {
        if (depth > MAX_RESOLVE_PASSES) return false;

        for (ItemStack variant : ingredient.getItems()) {
            Item targetItem = variant.getItem();
            RecipeHolder<?> subRecipe = findCraftingTableRecipe(recipeManager, registryAccess, targetItem, context);
            if (subRecipe == null) continue;

            Recipe<?> sub = subRecipe.value();

            // Skip 3x3 sub-recipes if no crafting table access (can't auto-place during auto-craft phase)
            if (!sub.canCraftInDimensions(2, 2) && !hasCraftingAccess(context)) continue;

            int outputPerCraft = sub.getResultItem(registryAccess).getCount();
            List<Ingredient> subIngs = sub.getIngredients();
            int subCraftsNeeded = (int) Math.ceil((double) deficit / outputPerCraft);

            // Recursively auto-craft any missing sub-ingredients (e.g., planks from logs for sticks)
            for (Ingredient subIng : subIngs) {
                if (subIng.isEmpty()) continue;
                int subHave = countInInventory(context, subIng);
                if (subHave < subCraftsNeeded) {
                    tryAutoCraftItem(context, recipeManager, registryAccess, subIng,
                            subCraftsNeeded - subHave, log, depth + 1);
                }
            }

            int maxSub = calculateMaxCrafts(context, subIngs);
            if (maxSub <= 0) continue;

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

    // ========== Autonomous crafting planner ==========

    /**
     * Autonomously plan and execute a multi-step crafting chain when materials are missing.
     * Uses RecipeResolver to build a full recursive dependency tree for ANY item,
     * across ALL recipe types (crafting, smelting, blasting, smoking, stonecutting).
     *
     * The resolver handles arbitrary depth: iron_pickaxe → iron_ingot (smelt) → raw_iron (mine)
     *                                                    → stick (craft) → planks (craft) → logs (chop)
     *
     * Steps:
     * 1. Build full dependency tree via RecipeResolver
     * 2. Flatten into ordered CraftingPlan (gather → process → craft)
     * 3. Queue first async task with continuation chain
     * 4. Return [ASYNC_TASK] so the agent loop stops
     */
    private String autoCraftPlan(ToolContext context, RecipeManager recipeManager,
                                 RegistryAccess registryAccess, Item targetItem,
                                 int requestedCount, List<Ingredient> ingredients,
                                 int craftsNeeded, StringBuilder craftLog) {
        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        if (companion == null) {
            return buildMissingReport(context, recipeManager, registryAccess,
                    targetItem, ingredients, craftsNeeded, craftLog);
        }

        String targetName = targetItem.getDescription().getString();
        String targetId = BuiltInRegistries.ITEM.getKey(targetItem).getPath();

        // === Build available inventory map ===
        Map<Item, Integer> available = buildAvailableMap(context);

        // === Resolve full dependency tree ===
        RecipeResolver.DependencyNode tree;
        try {
            RecipeResolver resolver = new RecipeResolver(recipeManager, registryAccess);
            tree = resolver.resolve(targetItem, requestedCount, available);
        } catch (Exception e) {
            MCAi.LOGGER.error("RecipeResolver failed for {}: {}", targetName, e.getMessage(), e);
            return buildMissingReport(context, recipeManager, registryAccess,
                    targetItem, ingredients, craftsNeeded, craftLog);
        }

        MCAi.LOGGER.info("Dependency tree for {}:\n{}", targetName, RecipeResolver.printTree(tree));

        // === Convert to ordered plan ===
        CraftingPlan plan = CraftingPlan.fromTree(tree);
        plan.logPlan();

        List<CraftingPlan.Step> asyncSteps = plan.getAsyncSteps();
        List<CraftingPlan.Step> craftSteps = plan.getCraftSteps();

        // If no async steps needed, all items should be obtainable through crafting alone
        // This shouldn't normally happen (we'd have crafted it already), but handle gracefully
        if (asyncSteps.isEmpty()) {
            return buildMissingReport(context, recipeManager, registryAccess,
                    targetItem, ingredients, craftsNeeded, craftLog);
        }

        // === Find the first async step that can be directly tasked ===
        // Some async steps (SMELT, KILL_MOB, etc.) can't create a CompanionTask directly —
        // they're handled via AI continuation calling the tool. So find the first one we CAN task.
        int firstTaskableIndex = -1;
        CompanionTask firstTask = null;
        for (int i = 0; i < asyncSteps.size(); i++) {
            firstTask = createTaskForStep(asyncSteps.get(i), companion);
            if (firstTask != null) {
                firstTaskableIndex = i;
                break;
            }
        }

        if (firstTask == null) {
            // No async step can be directly tasked (e.g. only SMELT steps remain)
            // Fall back: have the AI handle it via tool calls in the continuation
            return buildMissingReport(context, recipeManager, registryAccess,
                    targetItem, ingredients, craftsNeeded, craftLog);
        }

        // === Queue first async task with continuation chain ===
        // Build continuation plan: describes ALL remaining steps after the first taskable one
        CraftingPlan.Step firstStep = asyncSteps.get(firstTaskableIndex);

        StringBuilder nextSteps = new StringBuilder();

        // Remaining async steps after the first taskable one
        for (int i = firstTaskableIndex + 1; i < asyncSteps.size(); i++) {
            CraftingPlan.Step s = asyncSteps.get(i);
            if (nextSteps.length() > 0) nextSteps.append(", then ");
            nextSteps.append(stepToInstruction(s));
        }

        // Then craft steps (sync — these happen instantly during continuation)
        for (CraftingPlan.Step s : craftSteps) {
            if (nextSteps.length() > 0) nextSteps.append(", then ");
            nextSteps.append("craft_item({\"item\":\"").append(s.itemId)
                     .append("\",\"count\":").append(s.count).append("})");
        }

        // If there are more async steps, the first async step's continuation 
        // describes the NEXT step (with its own plan for the rest).
        // This creates a chain: step1 → continuation → step2 → continuation → ... → craft
        String continuationNext;
        if (firstTaskableIndex + 1 < asyncSteps.size()) {
            // Build nested chain: next step's plan contains remaining steps, etc.
            continuationNext = buildNestedContinuation(asyncSteps, firstTaskableIndex + 1,
                    craftSteps, targetId, requestedCount);
        } else {
            // Only sync craft steps remain
            continuationNext = "Call craft_item({\"item\":\"" + targetId + 
                    "\",\"count\":" + requestedCount + "}) — all materials should be gathered now.";
        }

        firstTask.setContinuation(new TaskContinuation(
                context.player().getUUID(),
                "Crafting " + targetName + ": " + firstStep,
                continuationNext
        ));
        companion.getTaskManager().queueTask(firstTask);

        MCAi.LOGGER.info("Auto-craft plan for {}: first={}, continuation={}",
                targetName, firstStep, continuationNext);

        // === Build player-friendly summary ===
        StringBuilder summary = new StringBuilder();
        summary.append("[ASYNC_TASK] Started crafting ").append(targetName).append("! Plan: ");
        summary.append(plan.summarize());
        summary.append(". I'll handle each step automatically!");

        return summary.toString();
    }

    /**
     * Build nested continuation chain for async steps.
     * Each step's continuation text tells the AI to call the NEXT async tool with a plan,
     * or to call craft_item at the end.
     */
    private String buildNestedContinuation(List<CraftingPlan.Step> asyncSteps, int fromIndex,
                                            List<CraftingPlan.Step> craftSteps,
                                            String finalItemId, int finalCount) {
        if (fromIndex >= asyncSteps.size()) {
            // No more async steps → craft the final item
            return "Call craft_item({\"item\":\"" + finalItemId +
                    "\",\"count\":" + finalCount + "}) — all materials should be gathered now.";
        }

        CraftingPlan.Step current = asyncSteps.get(fromIndex);

        // Build the plan parameter for this step (what comes next)
        String nestedPlan;
        if (fromIndex + 1 < asyncSteps.size()) {
            // More async steps after this one
            StringBuilder planDesc = new StringBuilder();
            for (int i = fromIndex + 1; i < asyncSteps.size(); i++) {
                if (planDesc.length() > 0) planDesc.append(", then ");
                planDesc.append(asyncSteps.get(i).type.name().toLowerCase())
                        .append(" ").append(asyncSteps.get(i).itemId);
            }
            planDesc.append(", then craft ").append(finalItemId);
            nestedPlan = planDesc.toString();
        } else {
            // Last async step → plan goes straight to crafting
            nestedPlan = "craft " + finalItemId;
        }

        // Tell the AI to call this async tool with the plan parameter
        return "Call " + current.toToolCall(nestedPlan);
    }

    /**
     * Convert a plan step to a human-readable instruction for the continuation message.
     */
    private String stepToInstruction(CraftingPlan.Step step) {
        return switch (step.type) {
            case CHOP -> "chop_trees for " + step.count + " logs";
            case MINE -> "mine_ores for " + step.count + " ores";
            case GATHER -> "gather " + step.count + "x " + step.itemId;
            case SMELT, BLAST -> "smelt " + step.count + "x " + step.itemId;
            case SMOKE, CAMPFIRE_COOK -> "cook " + step.count + "x " + step.itemId;
            case FARM -> "farm " + step.itemId;
            case FISH -> "fish for " + step.count + "x " + step.itemId;
            case KILL_MOB -> "gather " + step.count + "x " + step.itemId + " (mob drop)";
            case CRAFT -> "craft " + step.count + "x " + step.itemId;
            default -> step.toString();
        };
    }

    /**
     * Create a CompanionTask for a plan step.
     */
    private CompanionTask createTaskForStep(CraftingPlan.Step step, CompanionEntity companion) {
        return switch (step.type) {
            case CHOP -> new ChopTreesTask(companion, 16, Math.max(step.count, 4));
            case MINE -> new MineOresTask(companion, 16, Math.max(step.count, 3));
            case GATHER -> {
                Block block = resolveGatherBlock(step.itemId);
                yield block != null
                        ? new GatherBlocksTask(companion, block, 16, step.count)
                        : null;
            }
            case SMELT, BLAST, SMOKE, CAMPFIRE_COOK -> {
                // Smelting tasks are handled via AI continuation calling smelt_items tool
                // (SmeltItemsTask needs the furnace interaction which the tool handles)
                yield null;
            }
            default -> null;
        };
    }

    /**
     * Resolve an item ID to a Block for gathering. Maps items to their block forms.
     */
    private Block resolveGatherBlock(String itemId) {
        // Common mappings where the gatherable block differs from the item
        return switch (itemId) {
            case "cobblestone" -> Blocks.STONE;  // Breaking stone gives cobblestone
            case "cobbled_deepslate" -> Blocks.DEEPSLATE;
            case "flint" -> Blocks.GRAVEL;  // Gravel drops flint
            case "clay_ball" -> Blocks.CLAY;
            case "snowball" -> Blocks.SNOW_BLOCK;
            default -> {
                // Try to find block by registry name
                for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
                    if (entry.getKey().location().getPath().equals(itemId)) {
                        yield entry.getValue();
                    }
                }
                yield Blocks.STONE; // fallback
            }
        };
    }

    /**
     * Build a map of all items available in player + companion inventories.
     */
    private Map<Item, Integer> buildAvailableMap(ToolContext context) {
        Map<Item, Integer> available = new HashMap<>();

        var playerInv = context.player().getInventory();
        for (int i = 0; i < playerInv.getContainerSize(); i++) {
            ItemStack stack = playerInv.getItem(i);
            if (!stack.isEmpty()) {
                available.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }

        SimpleContainer companionInv = getCompanionInventory(context);
        if (companionInv != null) {
            for (int i = 0; i < companionInv.getContainerSize(); i++) {
                ItemStack stack = companionInv.getItem(i);
                if (!stack.isEmpty()) {
                    available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }

        return available;
    }

    // ========== Missing ingredients report (fallback) ==========

    private String buildMissingReport(ToolContext context, RecipeManager recipeManager,
                                       RegistryAccess registryAccess, Item targetItem,
                                       List<Ingredient> ingredients, int craftsNeeded,
                                       StringBuilder craftLog) {
        StringBuilder result = new StringBuilder();
        if (craftLog.length() > 0) result.append(craftLog);

        result.append("Missing ingredients for ")
              .append(targetItem.getDescription().getString()).append(":\n");

        Map<Item, Integer> grouped = getGroupedNeeds(ingredients, craftsNeeded);
        String targetName = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(targetItem).getPath();

        // Build actionable hints for each missing item
        StringBuilder actions = new StringBuilder();
        actions.append("\nACTION NEEDED — try nearby chests FIRST, then gather:\n");
        int actionNum = 0;

        // Step 0: Always suggest checking nearby chests/containers first
        // Collect all missing item names for a single find_and_fetch_item hint
        StringBuilder missingItemsList = new StringBuilder();

        for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
            Item ingItem = entry.getKey();
            int needed = entry.getValue();
            Ingredient ing = findIngredientFor(ingredients, ingItem);
            int have = ing != null ? countInInventory(context, ing) : 0;
            if (have >= needed) continue;

            int shortage = needed - have;
            String ingName = ingItem.getDescription().getString();
            String ingId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(ingItem).getPath();

            result.append("  - ").append(ingName)
                  .append(": have ").append(have).append(", need ").append(needed).append("\n");

            // Collect for chest-check hint
            if (missingItemsList.length() > 0) missingItemsList.append(", ");
            missingItemsList.append(ingId).append(" x").append(shortage);
        }

        // First action: check nearby chests for ANY of the missing materials
        if (missingItemsList.length() > 0) {
            actionNum++;
            // Pick the first missing ingredient for find_and_fetch_item
            Item firstMissing = null;
            int firstShortage = 0;
            for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
                Ingredient ing = findIngredientFor(ingredients, entry.getKey());
                int have = ing != null ? countInInventory(context, ing) : 0;
                if (have < entry.getValue()) {
                    firstMissing = entry.getKey();
                    firstShortage = entry.getValue() - have;
                    break;
                }
            }
            if (firstMissing != null) {
                String firstId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(firstMissing).getPath();
                actions.append("  ").append(actionNum)
                       .append(". FIRST try find_and_fetch_item({\"item\":\"").append(firstId)
                       .append("\", \"count\":").append(firstShortage)
                       .append("}) — checks all nearby chests/barrels\n");
            }
        }

        // Then add gathering fallbacks per item
        for (Map.Entry<Item, Integer> entry : grouped.entrySet()) {
            Item ingItem = entry.getKey();
            int needed = entry.getValue();
            Ingredient ing = findIngredientFor(ingredients, ingItem);
            int have = ing != null ? countInInventory(context, ing) : 0;
            if (have >= needed) continue;

            int shortage = needed - have;
            String ingId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(ingItem).getPath();

            // Determine the best gathering action
            boolean needsSmelt = false;
            RecipeHolder<?> smeltRecipe = findSmeltingRecipe(recipeManager, registryAccess, ingItem);
            if (smeltRecipe != null && isRealSmeltingNeed(ingId)) {
                needsSmelt = true;
            }

            actionNum++;
            if (ingId.contains("log") || ingId.contains("wood")) {
                actions.append("  ").append(actionNum).append(". IF chest empty: chop_trees({\"maxLogs\":").append(shortage)
                       .append(", \"plan\":\"craft ").append(targetName).append("\"})\n");
            } else if (ingId.contains("stick") || ingId.contains("plank")) {
                actions.append("  ").append(actionNum).append(". IF chest empty: chop_trees({\"maxLogs\":2")
                       .append(", \"plan\":\"craft ").append(targetName).append("\"})\n");
            } else if (needsSmelt) {
                actions.append("  ").append(actionNum).append(". IF chest empty: mine_ores({\"count\":").append(shortage)
                       .append(", \"plan\":\"smelt_items to get ").append(ingId)
                       .append(", then craft ").append(targetName).append("\"})\n");
            } else {
                actions.append("  ").append(actionNum).append(". IF chest empty: gather_blocks({\"block\":\"")
                       .append(ingId).append("\", \"maxBlocks\":").append(shortage)
                       .append(", \"plan\":\"craft ").append(targetName).append("\"})\n");
            }
        }

        if (actionNum > 0) {
            actions.append("Use the 'plan' parameter on gathering tools to auto-continue after task completes.\n");
            actions.append("Example plan: \"smelt_items to get iron_ingot, then craft ").append(targetName).append("\"\n");
            actions.append("After calling an async gathering tool with a plan, STOP and tell the player you're working on it.\n");
            actions.append("Do NOT call craft_item again immediately — the plan will auto-continue when gathering finishes.\n");
            result.append(actions);
        }
        return result.toString();
    }

    /**
     * Determine if an item truly needs smelting (like iron_ingot from raw_iron)
     * vs items that happen to have obscure modpack smelting recipes (cobblestone from frozen_cobblestone).
     * We only suggest smelting for actual ingots and processed items.
     */
    private boolean isRealSmeltingNeed(String itemId) {
        // These are items where smelting is the primary/only way to obtain them
        return itemId.contains("ingot") || itemId.contains("glass")
                || itemId.contains("brick") || itemId.contains("charcoal")
                || itemId.contains("sponge") || itemId.contains("smooth_")
                || itemId.contains("terracotta") || itemId.contains("_dye")
                || itemId.equals("stone") || itemId.equals("cracked_stone_bricks");
    }

    // ========== Recipe lookup ==========

    /**
     * Find a Shaped or Shapeless recipe that produces the target item.
     * Explicitly excludes smelting/blasting/smoking/campfire recipes.
     */
    private RecipeHolder<?> findCraftingTableRecipe(RecipeManager recipeManager,
                                                     RegistryAccess registryAccess, Item targetItem) {
        return findCraftingTableRecipe(recipeManager, registryAccess, targetItem, null);
    }

    /**
     * Find a crafting table recipe that produces the target item.
     * If context is provided, prefers recipes the player has ingredients for.
     * This prevents matching "Green Bioshroom Planks" when the player has oak logs.
     */
    private RecipeHolder<?> findCraftingTableRecipe(RecipeManager recipeManager,
                                                     RegistryAccess registryAccess, Item targetItem,
                                                     @Nullable ToolContext context) {
        RecipeHolder<?> firstMatch = null;

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            Recipe<?> r = holder.value();
            if (!(r instanceof ShapedRecipe) && !(r instanceof ShapelessRecipe)) continue;
            if (r.getResultItem(registryAccess).getItem() != targetItem) continue;

            if (firstMatch == null) firstMatch = holder;

            // If context available, prefer recipes the player can actually craft
            if (context != null) {
                boolean canCraft = true;
                for (Ingredient ing : r.getIngredients()) {
                    if (ing.isEmpty()) continue;
                    if (countInInventory(context, ing) <= 0) {
                        canCraft = false;
                        break;
                    }
                }
                if (canCraft) return holder; // Player has all ingredients — use this recipe
            }
        }
        return firstMatch; // Fallback to first match if no craftable recipe found
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

    // ========== Crafting station requirement ==========

    /**
     * Checks if the player/companion has access to a 3x3 crafting grid:
     * 1. Crafting table block within CRAFTING_TABLE_RADIUS blocks of the player
     * 2. OR a known portable crafting item in player/companion inventory
     * Used for sub-recipe checks where we don't want to auto-place.
     */
    private boolean hasCraftingAccess(ToolContext context) {
        // Check for nearby crafting table
        Level level = context.player().level();
        BlockPos center = context.player().blockPosition();
        int r = CRAFTING_TABLE_RADIUS;
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                        return true;
                    }
                }
            }
        }
        // Check for portable crafting items
        return CraftingBlockHelper.hasPortableCraftingItem(context);
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
     * Counts a specific item across player and companion inventories (by Item, not Ingredient).
     */
    private int countItemInInventory(ToolContext context, Item item) {
        int count = 0;

        var playerInv = context.player().getInventory();
        for (int i = 0; i < playerInv.getContainerSize(); i++) {
            ItemStack stack = playerInv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }

        SimpleContainer companionInv = getCompanionInventory(context);
        if (companionInv != null) {
            for (int i = 0; i < companionInv.getContainerSize(); i++) {
                ItemStack stack = companionInv.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == item) {
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

        // Fallback to partial match — prefer vanilla (minecraft:) items over modded
        Item vanillaMatch = null;
        Item moddedMatch = null;
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String name = entry.getValue().getDescription().getString().toLowerCase();
            if (id.getPath().contains(query) || name.contains(query)) {
                if (id.getNamespace().equals("minecraft")) {
                    if (vanillaMatch == null) vanillaMatch = entry.getValue();
                } else {
                    if (moddedMatch == null) moddedMatch = entry.getValue();
                }
            }
        }
        return vanillaMatch != null ? vanillaMatch : moddedMatch;
    }
}
