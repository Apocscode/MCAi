package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.planner.CraftingPlan;
import com.apocscode.mcai.ai.planner.RecipeResolver;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.ItemRoutingHelper;
import com.apocscode.mcai.task.BlockHelper;
import com.apocscode.mcai.task.ChopTreesTask;
import com.apocscode.mcai.task.CompanionTask;
import com.apocscode.mcai.task.FishingTask;
import com.apocscode.mcai.task.GatherBlocksTask;
import com.apocscode.mcai.task.KillMobTask;
import com.apocscode.mcai.task.MineOresTask;
import com.apocscode.mcai.task.OreGuide;
import com.apocscode.mcai.task.SmeltItemsTask;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.SmokerBlock;

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
            ItemStack recipeResult = RecipeResolver.safeGetResult(recipe, registryAccess);
            if (recipeResult.isEmpty()) {
                return craftLog.toString() + "Found recipe but cannot determine output for '" + targetName + "'.";
            }
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

            // Give the requested amount to the companion inventory (items belong to the AI companion)
            int forCompanion = Math.min(totalOutput, stillNeed);
            ItemStack companionShare = new ItemStack(targetItem, forCompanion);
            SimpleContainer companionInv = getCompanionInventory(context);
            if (companionInv != null) {
                ItemStack rem = companionInv.addItem(companionShare);
                // If companion inventory full, fallback to player
                if (!rem.isEmpty()) {
                    if (!context.player().getInventory().add(rem)) {
                        context.player().drop(rem, false);
                    }
                }
            } else {
                // No companion — give to player as fallback
                if (!context.player().getInventory().add(companionShare)) {
                    context.player().drop(companionShare, false);
                }
            }

            // Route any excess to tagged storage (OUTPUT > STORAGE > companion inventory)
            int excess = totalOutput - forCompanion;
            String depositMsg = "";
            if (excess > 0) {
                CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
                if (companion != null && ItemRoutingHelper.hasTaggedStorage(companion)) {
                    ItemStack excessStack = new ItemStack(targetItem, excess);
                    depositMsg = " " + ItemRoutingHelper.routeToStorage(companion, excessStack);
                    // If any couldn't be routed, give to companion then player
                    if (!excessStack.isEmpty()) {
                        if (companionInv != null) {
                            ItemStack rem = companionInv.addItem(excessStack);
                            if (!rem.isEmpty()) {
                                if (!context.player().getInventory().add(rem)) {
                                    context.player().drop(rem, false);
                                }
                            }
                        } else if (!context.player().getInventory().add(excessStack)) {
                            context.player().drop(excessStack, false);
                        }
                    }
                } else {
                    // No tagged storage — give to companion inventory
                    ItemStack excessStack = new ItemStack(targetItem, excess);
                    if (companionInv != null) {
                        ItemStack rem = companionInv.addItem(excessStack);
                        if (!rem.isEmpty()) {
                            if (!context.player().getInventory().add(rem)) {
                                context.player().drop(rem, false);
                            }
                        }
                    } else if (!context.player().getInventory().add(excessStack)) {
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
            if (forCompanion < totalOutput) {
                sb.append(" (").append(forCompanion).append(" to companion");
                if (excess > 0) sb.append(", ").append(excess).append(" to storage");
                sb.append(")");
            }
            sb.append(".");
            return sb.toString();
        });
    }

    // ========== Container scanning (auto-fetch from chests) ==========

    /**
     * Scans nearby containers for the finished item and pulls it into the companion's inventory.
     * Returns the number of items fetched.
     */
    private int fetchFromNearbyContainers(ToolContext context, Item targetItem, int maxToFetch) {
        Level level = context.player().level();
        BlockPos center = context.player().blockPosition();
        SimpleContainer companionInv = getCompanionInventory(context);
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

                        // Route to companion inventory first, player as fallback
                        boolean inserted = false;
                        int actualInserted = 0;
                        if (companionInv != null) {
                            ItemStack rem = companionInv.addItem(toInsert);
                            actualInserted = toTake - rem.getCount();
                            if (!rem.isEmpty()) {
                                // Companion full — try player for remainder
                                if (context.player().getInventory().add(rem)) {
                                    actualInserted = toTake;
                                }
                            } else {
                                actualInserted = toTake;
                            }
                            inserted = actualInserted > 0;
                        } else if (context.player().getInventory().add(toInsert)) {
                            actualInserted = toTake - toInsert.getCount();
                            if (toInsert.isEmpty()) actualInserted = toTake;
                            inserted = actualInserted > 0;
                        }

                        if (inserted) {
                            stack.shrink(actualInserted);
                            if (stack.isEmpty()) container.setItem(i, ItemStack.EMPTY);
                            container.setChanged();
                            fetched += actualInserted;
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

            int outputPerCraft = RecipeResolver.safeGetResult(sub, registryAccess).getCount();
            if (outputPerCraft <= 0) outputPerCraft = 1;
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

            int outputPerCraft = RecipeResolver.safeGetResult(sub, registryAccess).getCount();
            if (outputPerCraft <= 0) outputPerCraft = 1;
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

            // Place results in companion inventory (where tasks consume from)
            int produced = actualSub * outputPerCraft;
            ItemStack subResult = new ItemStack(targetItem, produced);
            SimpleContainer companionInv = getCompanionInventory(context);
            if (companionInv != null) {
                ItemStack rem = companionInv.addItem(subResult);
                if (!rem.isEmpty()) {
                    if (!context.player().getInventory().add(rem)) {
                        context.player().drop(rem, false);
                    }
                }
            } else if (!context.player().getInventory().add(subResult)) {
                context.player().drop(subResult, false);
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
            MCAi.LOGGER.warn("autoCraftPlan: companion is null — falling back to manual instructions");
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
            MCAi.LOGGER.error("autoCraftPlan: RecipeResolver failed for {}: {}", targetName, e.getMessage(), e);
            return buildMissingReport(context, recipeManager, registryAccess,
                    targetItem, ingredients, craftsNeeded, craftLog);
        }

        MCAi.LOGGER.info("Dependency tree for {}:\n{}", targetName, RecipeResolver.printTree(tree));

        // === Convert to ordered plan ===
        CraftingPlan plan = CraftingPlan.fromTree(tree);

        // === Check tool prerequisites for MINE steps ===
        // If plan includes mining iron ore but companion has no stone+ pickaxe, we need to craft one first.
        // The plan already handles the chain (chop→craft planks→craft sticks→craft pickaxe→mine),
        // but only if we detect the need and recursively resolve the pickaxe too.
        ensureToolPrerequisites(plan, available, companion, recipeManager, registryAccess);

        // Ensure smelting prerequisites: fuel and furnace materials
        ensureSmeltPrerequisites(plan, available, companion);

        plan.logPlan();

        List<CraftingPlan.Step> asyncSteps = plan.getAsyncSteps();
        List<CraftingPlan.Step> craftSteps = plan.getCraftSteps();

        // If no async steps needed, all items should be obtainable through crafting alone
        // This shouldn't normally happen (we'd have crafted it already), but handle gracefully
        if (asyncSteps.isEmpty()) {
            MCAi.LOGGER.warn("autoCraftPlan: no async steps in plan for {} — falling back", targetName);
            return buildMissingReport(context, recipeManager, registryAccess,
                    targetItem, ingredients, craftsNeeded, craftLog);
        }

        // === Find the first async step that can be directly tasked ===
        // Most async steps now create CompanionTasks directly (CHOP, MINE, GATHER,
        // SMELT, KILL_MOB). The rest are handled via AI continuation tool calls.
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
            MCAi.LOGGER.warn("autoCraftPlan: no taskable async steps for {} (asyncSteps={})",
                    targetName, asyncSteps.stream().map(s -> s.type.name()).toList());
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

    // ========== Tool Prerequisite System ==========

    /**
     * Check if the plan includes MINE steps that require a pickaxe tier the companion doesn't have.
     * If so, prepend the necessary tool crafting steps to the plan.
     *
     * Mining tiers:
     *   Tier 0 (Wood/Gold pick): coal ore, nether quartz, nether gold ore
     *   Tier 1 (Stone pick): iron ore, copper ore, lapis ore
     *   Tier 2 (Iron pick): gold ore, diamond ore, emerald ore, redstone ore
     *   Tier 3 (Diamond pick): ancient debris, obsidian
     */
    private void ensureToolPrerequisites(CraftingPlan plan, Map<Item, Integer> available,
                                          CompanionEntity companion,
                                          RecipeManager recipeManager, RegistryAccess registryAccess) {
        // Determine the highest mining tier required by MINE steps in the plan
        int requiredTier = 0;
        for (CraftingPlan.Step step : plan.getSteps()) {
            if (step.type == RecipeResolver.StepType.MINE) {
                int tier = getRequiredMiningTier(step.itemId);
                if (tier > requiredTier) requiredTier = tier;
            }
        }

        if (requiredTier == 0) return; // Wood pick or bare hands suffice

        // Check what pickaxe the companion already has
        int companionTier = getCompanionPickaxeTier(companion, available);
        if (companionTier >= requiredTier) return; // Already has sufficient pickaxe

        MCAi.LOGGER.info("Tool prerequisite: need tier {} pick, have tier {}. Adding prerequisite steps.",
                requiredTier, companionTier);

        // Determine which pickaxe to craft (just need the minimum required tier)
        // But we might need to bootstrap: to get stone pick we need wood pick + cobble,
        // to get iron pick we need stone pick + iron ingots, etc.
        // Add steps bottom-up from current tier to required tier.
        try {
            RecipeResolver resolver = new RecipeResolver(recipeManager, registryAccess);
            List<CraftingPlan.Step> prereqSteps = new ArrayList<>();

            if (companionTier < 1 && requiredTier >= 1) {
                // Need at least stone pickaxe — requires wooden pickaxe first to mine cobblestone
                addToolChainSteps(prereqSteps, Items.WOODEN_PICKAXE, available, resolver);
                addToolChainSteps(prereqSteps, Items.STONE_PICKAXE, available, resolver);
            }
            if (companionTier < 2 && requiredTier >= 2) {
                // Need iron pickaxe — stone pick should exist from above or already owned
                if (companionTier < 1) {
                    // Already handled stone pick above
                } else {
                    // Have stone but need iron
                }
                // Iron pickaxe needs iron ingots (from smelting) — the resolver handles this
                addToolChainSteps(prereqSteps, Items.IRON_PICKAXE, available, resolver);
            }
            if (companionTier < 3 && requiredTier >= 3) {
                // Need diamond pickaxe — iron pick should exist
                addToolChainSteps(prereqSteps, Items.DIAMOND_PICKAXE, available, resolver);
            }

            // Prepend prerequisite steps to the plan
            if (!prereqSteps.isEmpty()) {
                plan.prependSteps(prereqSteps);
                MCAi.LOGGER.info("Added {} tool prerequisite steps to plan", prereqSteps.size());
            }
        } catch (Exception e) {
            MCAi.LOGGER.warn("Failed to resolve tool prerequisites: {}", e.getMessage());
        }
    }

    // ========== Smelting Prerequisite System ==========

    /**
     * Ensure the plan has prerequisites for smelting steps:
     * 1. Enough fuel (add CHOP step if no fuel available and plan doesn't already chop)
     * 2. Enough cobblestone for a furnace (add GATHER step if no furnace nearby and not enough cobble)
     *
     * Without this, the smelting task would fail when the companion has no furnace,
     * no fuel, or no cobblestone to auto-craft a furnace.
     */
    private void ensureSmeltPrerequisites(CraftingPlan plan, Map<Item, Integer> available,
                                           CompanionEntity companion) {
        boolean hasSmelt = plan.getSteps().stream()
                .anyMatch(s -> s.type == RecipeResolver.StepType.SMELT
                        || s.type == RecipeResolver.StepType.BLAST
                        || s.type == RecipeResolver.StepType.SMOKE
                        || s.type == RecipeResolver.StepType.CAMPFIRE_COOK);
        if (!hasSmelt) return;

        List<CraftingPlan.Step> prereqs = new ArrayList<>();

        // === Furnace check ===
        // If no furnace nearby, companion needs 8 cobblestone to auto-craft one
        boolean hasFurnace = hasFurnaceNearby(companion);
        if (!hasFurnace) {
            int cobble = available.getOrDefault(Items.COBBLESTONE, 0);
            cobble += BlockHelper.countItem(companion, Items.COBBLESTONE);
            if (cobble < 8) {
                // Check if plan already gathers cobblestone
                int planCobble = plan.getSteps().stream()
                        .filter(s -> s.type == RecipeResolver.StepType.GATHER
                                && s.itemId.equals("cobblestone"))
                        .mapToInt(s -> s.count).sum();
                int totalCobble = cobble + planCobble;
                if (totalCobble < 8) {
                    int needed = 8 - totalCobble;
                    prereqs.add(new CraftingPlan.Step(RecipeResolver.StepType.GATHER,
                            Items.COBBLESTONE, needed));
                    MCAi.LOGGER.info("Smelting prereq: added GATHER cobblestone x{} for furnace auto-craft",
                            needed);
                }
            }
        }

        // === Fuel check ===
        // If no fuel in inventory and plan doesn't already chop trees, add a CHOP step
        boolean hasFuel = false;
        SimpleContainer inv = companion.getCompanionInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(stack)) {
                hasFuel = true;
                break;
            }
        }
        // Also check if the available map has any common fuels
        if (!hasFuel) {
            for (Map.Entry<Item, Integer> entry : available.entrySet()) {
                if (entry.getValue() > 0 && AbstractFurnaceBlockEntity.isFuel(new ItemStack(entry.getKey()))) {
                    hasFuel = true;
                    break;
                }
            }
        }
        // Check if plan already has a CHOP step (logs are excellent fuel)
        boolean planChops = plan.getSteps().stream()
                .anyMatch(s -> s.type == RecipeResolver.StepType.CHOP);
        if (!hasFuel && !planChops) {
            prereqs.add(new CraftingPlan.Step(RecipeResolver.StepType.CHOP,
                    Items.OAK_LOG, 4));
            MCAi.LOGGER.info("Smelting prereq: added CHOP oak_log x4 for fuel");
        }

        if (!prereqs.isEmpty()) {
            plan.prependSteps(prereqs);
        }
    }

    /**
     * Check if there's a furnace (or blast furnace or smoker) within range of the companion.
     */
    private static boolean hasFurnaceNearby(CompanionEntity companion) {
        BlockPos center = companion.blockPosition();
        Level level = companion.level();
        int range = 32;
        for (int x = -range; x <= range; x++) {
            for (int y = -4; y <= 8; y++) {
                for (int z = -range; z <= range; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    Block block = level.getBlockState(pos).getBlock();
                    if (block instanceof FurnaceBlock || block instanceof BlastFurnaceBlock
                            || block instanceof SmokerBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Resolve the crafting chain for a tool and add its steps to the list.
     * Uses the same RecipeResolver tree approach.
     */
    private void addToolChainSteps(List<CraftingPlan.Step> steps, Item tool,
                                    Map<Item, Integer> available, RecipeResolver resolver) {
        // Check if already available
        if (available.getOrDefault(tool, 0) > 0) return;

        RecipeResolver.DependencyNode tree = resolver.resolve(tool, 1, new HashMap<>(available));
        CraftingPlan toolPlan = CraftingPlan.fromTree(tree);

        for (CraftingPlan.Step step : toolPlan.getSteps()) {
            // Don't add duplicates
            boolean exists = steps.stream().anyMatch(s ->
                    s.type == step.type && s.itemId.equals(step.itemId));
            if (!exists && step.type != RecipeResolver.StepType.AVAILABLE) {
                steps.add(step);
            }
        }
    }

    /**
     * Get the mining tier required for a specific ore/item.
     * Returns 0-3 matching Minecraft mining levels.
     */
    private static int getRequiredMiningTier(String itemId) {
        // Tier 1 (Stone pick required)
        if (itemId.contains("iron") || itemId.contains("copper") || itemId.contains("lapis")) {
            return 1;
        }
        // Tier 2 (Iron pick required)
        if (itemId.contains("gold") || itemId.contains("diamond") || itemId.contains("emerald")
                || itemId.contains("redstone")) {
            return 2;
        }
        // Tier 3 (Diamond pick required)
        if (itemId.contains("ancient_debris") || itemId.equals("obsidian")
                || itemId.equals("crying_obsidian")) {
            return 3;
        }
        // Tier 0 (Any pick or hand)
        return 0;
    }

    /**
     * Determine the best pickaxe tier the companion currently has.
     * Checks mainhand + inventory + available items map.
     * Returns tier: -1=none, 0=wood/gold, 1=stone, 2=iron, 3=diamond, 4=netherite
     */
    private static int getCompanionPickaxeTier(CompanionEntity companion, Map<Item, Integer> available) {
        int bestTier = -1;

        // Check available map (includes player + companion inventory items)
        for (Map.Entry<Item, Integer> entry : available.entrySet()) {
            if (entry.getValue() <= 0) continue;
            int tier = getPickaxeTier(entry.getKey());
            if (tier > bestTier) bestTier = tier;
        }

        // Check companion mainhand
        ItemStack mainHand = companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        if (!mainHand.isEmpty()) {
            int tier = getPickaxeTier(mainHand.getItem());
            if (tier > bestTier) bestTier = tier;
        }

        // Check companion inventory
        for (int i = 0; i < companion.getCompanionInventory().getContainerSize(); i++) {
            ItemStack stack = companion.getCompanionInventory().getItem(i);
            if (!stack.isEmpty()) {
                int tier = getPickaxeTier(stack.getItem());
                if (tier > bestTier) bestTier = tier;
            }
        }

        return bestTier;
    }

    /**
     * Get the mining tier of a specific item (if it's a pickaxe).
     * Returns -1 if not a pickaxe.
     */
    private static int getPickaxeTier(Item item) {
        if (!(item instanceof PickaxeItem pick)) return -1;
        float speed = pick.getTier().getSpeed();
        // Map speed to tier: wood=2, stone=4, iron=6, diamond=8, netherite=9
        if (speed >= 9) return 4;  // Netherite
        if (speed >= 8) return 3;  // Diamond
        if (speed >= 6) return 2;  // Iron
        if (speed >= 4) return 1;  // Stone
        return 0;                   // Wood/Gold
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
            case MINE -> "mine_ores(ore=\"" + step.itemId + "\") for " + step.count + " ores";
            case GATHER -> "gather " + step.count + "x " + step.itemId;
            case SMELT, BLAST -> "smelt " + step.count + "x " + step.itemId;
            case SMOKE, CAMPFIRE_COOK -> "cook " + step.count + "x " + step.itemId;
            case FARM -> "gather " + step.count + "x " + step.itemId + " (farm/plants)";
            case FISH -> "go_fishing for " + step.count + " fish";
            case KILL_MOB -> "kill mobs for " + step.count + "x " + step.itemId;
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
            case MINE -> {
                // Use targeted ore type when possible (e.g., "raw_iron" → IRON ore)
                OreGuide.Ore targetOre = OreGuide.findByName(step.itemId);
                if (targetOre != null) {
                    MCAi.LOGGER.info("createTaskForStep: MINE {} → targeted ore: {}",
                            step.itemId, targetOre.name);
                }
                yield new MineOresTask(companion, 16, Math.max(step.count, 3), targetOre);
            }
            case GATHER -> {
                Block block = resolveGatherBlock(step.itemId);
                yield block != null
                        ? new GatherBlocksTask(companion, block, 16, step.count)
                        : null;
            }
            case KILL_MOB -> {
                // Resolve which mob to kill based on the item being gathered
                String mobName = resolveMobForDrop(step.itemId);
                yield new KillMobTask(companion,
                        KillMobTask.resolveEntityType(mobName),
                        mobName, Math.max(step.count, 1));
            }
            case FISH -> {
                yield new FishingTask(companion, Math.max(step.count, 1));
            }
            case FARM -> {
                // FARM items (wheat, flowers, mushrooms, etc.) — use GatherBlocksTask
                // to scan and collect the block form of the item from the world.
                // FarmAreaTask requires corners and is for user-directed farming.
                Block block = resolveFarmBlock(step.itemId);
                if (block != null) {
                    yield new GatherBlocksTask(companion, block, 24, Math.max(step.count, 1));
                }
                MCAi.LOGGER.warn("createTaskForStep: cannot resolve farm block for {}", step.itemId);
                yield null;
            }
            case SMELT, BLAST, SMOKE, CAMPFIRE_COOK -> {
                // Resolve the raw material input from the smelting recipe
                // Step item is the OUTPUT (e.g., iron_ingot); we need the INPUT (e.g., raw_iron)
                Item inputItem = resolveSmeltInput(step.item, companion);
                if (inputItem != null) {
                    MCAi.LOGGER.info("createTaskForStep: SMELT {} → input={}, count={}",
                            step.itemId, BuiltInRegistries.ITEM.getKey(inputItem).getPath(), step.count);
                    yield new SmeltItemsTask(companion, inputItem, step.count);
                }
                MCAi.LOGGER.warn("createTaskForStep: cannot resolve smelting input for {}", step.itemId);
                yield null;
            }
            default -> null;
        };
    }

    /**
     * Resolve which mob to kill to get a specific drop item.
     * Maps item IDs to mob names for the kill_mob tool.
     */
    private static String resolveMobForDrop(String itemId) {
        return switch (itemId) {
            case "leather" -> "cow";
            case "beef" -> "cow";
            case "string" -> "spider";
            case "spider_eye" -> "spider";
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
            default -> {
                // Try to infer from item name
                if (itemId.contains("wool")) yield "sheep";
                if (itemId.contains("meat")) yield "cow";
                yield itemId; // Last resort: use the item name itself
            }
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
            case "sand", "red_sand" -> Blocks.SAND;
            case "gravel" -> Blocks.GRAVEL;
            case "dirt" -> Blocks.DIRT;
            case "stone" -> Blocks.STONE;
            case "obsidian" -> Blocks.OBSIDIAN;
            case "ice" -> Blocks.ICE;
            case "netherrack" -> Blocks.NETHERRACK;
            case "soul_sand" -> Blocks.SOUL_SAND;
            case "soul_soil" -> Blocks.SOUL_SOIL;
            case "basalt" -> Blocks.BASALT;
            case "blackstone" -> Blocks.BLACKSTONE;
            case "end_stone" -> Blocks.END_STONE;
            case "moss_block" -> Blocks.MOSS_BLOCK;
            case "mud" -> Blocks.MUD;
            case "calcite" -> Blocks.CALCITE;
            case "tuff" -> Blocks.TUFF;
            case "dripstone_block" -> Blocks.DRIPSTONE_BLOCK;
            case "pointed_dripstone" -> Blocks.POINTED_DRIPSTONE;
            case "clay" -> Blocks.CLAY;
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
     * Resolve a FARM item ID to a Block for gathering from the world.
     * Maps crop/plant items to their breakable block forms.
     * Most "farm" items are plants/flowers that exist as blocks in the world.
     */
    private Block resolveFarmBlock(String itemId) {
        return switch (itemId) {
            // Crops — break the crop block itself
            case "wheat", "wheat_seeds" -> Blocks.WHEAT;
            case "carrot" -> Blocks.CARROTS;
            case "potato" -> Blocks.POTATOES;
            case "beetroot", "beetroot_seeds" -> Blocks.BEETROOTS;
            case "melon_slice" -> Blocks.MELON;
            case "pumpkin" -> Blocks.PUMPKIN;
            case "sugar_cane", "sugar" -> Blocks.SUGAR_CANE;
            case "bamboo" -> Blocks.BAMBOO;
            case "cactus" -> Blocks.CACTUS;
            case "cocoa_beans" -> Blocks.COCOA;
            case "sweet_berries" -> Blocks.SWEET_BERRY_BUSH;
            case "kelp", "dried_kelp" -> Blocks.KELP;
            case "nether_wart" -> Blocks.NETHER_WART;
            case "chorus_fruit" -> Blocks.CHORUS_PLANT;
            // Flowers
            case "dandelion" -> Blocks.DANDELION;
            case "poppy" -> Blocks.POPPY;
            case "blue_orchid" -> Blocks.BLUE_ORCHID;
            case "allium" -> Blocks.ALLIUM;
            case "azure_bluet" -> Blocks.AZURE_BLUET;
            case "cornflower" -> Blocks.CORNFLOWER;
            case "lily_of_the_valley" -> Blocks.LILY_OF_THE_VALLEY;
            case "lily_pad" -> Blocks.LILY_PAD;
            // Mushrooms
            case "red_mushroom" -> Blocks.RED_MUSHROOM;
            case "brown_mushroom" -> Blocks.BROWN_MUSHROOM;
            // Other gatherable plants
            case "vine" -> Blocks.VINE;
            case "fern" -> Blocks.FERN;
            case "tall_grass" -> Blocks.SHORT_GRASS;
            case "seagrass" -> Blocks.SEAGRASS;
            case "apple" -> Blocks.OAK_LEAVES;  // Apple drops from oak leaves
            default -> {
                // Try to find block by registry name
                for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
                    if (entry.getKey().location().getPath().equals(itemId)) {
                        yield entry.getValue();
                    }
                }
                MCAi.LOGGER.debug("resolveFarmBlock: no block mapping for '{}'", itemId);
                yield null;
            }
        };
    }

    /**
     * Reverse-lookup a smelting recipe: given the OUTPUT item, find the raw INPUT item.
     * e.g., iron_ingot → raw_iron, gold_ingot → raw_gold, glass → sand
     * Prefers vanilla recipes with raw_ inputs (actual mining drops).
     */
    private static Item resolveSmeltInput(Item outputItem, CompanionEntity companion) {
        RecipeManager rm = companion.level().getServer().getRecipeManager();
        RegistryAccess ra = companion.level().registryAccess();

        Item bestInput = null;

        for (RecipeHolder<?> holder : rm.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                if (!(r instanceof SmeltingRecipe) && !(r instanceof BlastingRecipe)
                        && !(r instanceof SmokingRecipe) && !(r instanceof CampfireCookingRecipe)) {
                    continue;
                }
                ItemStack result = RecipeResolver.safeGetResult(r, ra);
                if (result.isEmpty() || !result.is(outputItem)) continue;

                List<Ingredient> ings = r.getIngredients();
                if (ings.isEmpty() || ings.get(0).isEmpty()) continue;

                ItemStack[] items = ings.get(0).getItems();
                if (items.length == 0) continue;

                // Prefer raw_ inputs (actual mining drops) — return immediately
                for (ItemStack stack : items) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (id.getPath().startsWith("raw_")) {
                        return stack.getItem();
                    }
                }

                // Accept non-raw input as fallback
                if (bestInput == null) {
                    bestInput = items[0].getItem();
                }
            } catch (Exception e) {
                continue;
            }
        }
        return bestInput;
    }

    /**
     * Build a map of all items available in player + companion inventories + tagged storage chests.
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

        // Also include items in tagged STORAGE containers + all home area containers
        CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
        if (companion != null) {
            java.util.Set<net.minecraft.core.BlockPos> scanned = new java.util.HashSet<>();

            // Tagged STORAGE containers (always checked, may be outside home area)
            var storageBlocks = companion.getTaggedBlocks(
                    com.apocscode.mcai.logistics.TaggedBlock.Role.STORAGE);
            for (var tb : storageBlocks) {
                scanned.add(tb.pos());
                net.minecraft.world.level.block.entity.BlockEntity be =
                        context.player().level().getBlockEntity(tb.pos());
                if (be instanceof net.minecraft.world.Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        ItemStack stack = container.getItem(i);
                        if (!stack.isEmpty()) {
                            available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                        }
                    }
                }
            }

            // ALL containers within the home area
            if (companion.hasHomeArea()) {
                net.minecraft.core.BlockPos c1 = companion.getHomeCorner1();
                net.minecraft.core.BlockPos c2 = companion.getHomeCorner2();
                if (c1 != null && c2 != null) {
                    int minX = Math.min(c1.getX(), c2.getX());
                    int minY = Math.min(c1.getY(), c2.getY());
                    int minZ = Math.min(c1.getZ(), c2.getZ());
                    int maxX = Math.max(c1.getX(), c2.getX());
                    int maxY = Math.max(c1.getY(), c2.getY());
                    int maxZ = Math.max(c1.getZ(), c2.getZ());
                    for (int x = minX; x <= maxX; x++) {
                        for (int y = minY; y <= maxY; y++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
                                if (scanned.contains(pos)) continue;
                                scanned.add(pos);
                                net.minecraft.world.level.block.entity.BlockEntity be =
                                        context.player().level().getBlockEntity(pos);
                                if (be instanceof net.minecraft.world.Container container) {
                                    for (int i = 0; i < container.getContainerSize(); i++) {
                                        ItemStack stack = container.getItem(i);
                                        if (!stack.isEmpty()) {
                                            available.merge(stack.getItem(), stack.getCount(), Integer::sum);
                                        }
                                    }
                                }
                            }
                        }
                    }
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
        RecipeHolder<?> vanillaMatch = null;

        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                if (!(r instanceof ShapedRecipe) && !(r instanceof ShapelessRecipe)) continue;
                ItemStack result = RecipeResolver.safeGetResult(r, registryAccess);
                if (result.isEmpty()) continue;
                if (result.getItem() != targetItem) continue;

                // Track first match as fallback
                if (firstMatch == null) firstMatch = holder;

                // Strongly prefer vanilla (minecraft:) namespace recipes
                ResourceLocation recipeId = holder.id();
                if (recipeId.getNamespace().equals("minecraft")) {
                    vanillaMatch = holder;
                }

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
            } catch (Exception e) {
                // Modded recipe threw during inspection — skip it
                continue;
            }
        }
        // Prefer vanilla recipe, then first match as fallback
        return vanillaMatch != null ? vanillaMatch : firstMatch;
    }

    /**
     * Find a smelting, blasting, smoking, or campfire recipe that produces the target item.
     */
    private RecipeHolder<?> findSmeltingRecipe(RecipeManager recipeManager,
                                                RegistryAccess registryAccess, Item targetItem) {
        for (RecipeHolder<?> holder : recipeManager.getRecipes()) {
            try {
                Recipe<?> r = holder.value();
                if (r == null) continue;
                if (r instanceof SmeltingRecipe || r instanceof BlastingRecipe
                        || r instanceof SmokingRecipe || r instanceof CampfireCookingRecipe) {
                    ItemStack result = RecipeResolver.safeGetResult(r, registryAccess);
                    if (!result.isEmpty() && result.getItem() == targetItem) {
                        return holder;
                    }
                }
            } catch (Exception e) {
                continue; // Modded recipe threw — skip
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
     * Consumes ingredient from companion inventory first, then player inventory.
     */
    private void consumeIngredient(ToolContext context, Ingredient ingredient, int amount) {
        int remaining = amount;

        // Try companion inventory first (where gathered materials are stored)
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

        // Fallback to player inventory
        if (remaining > 0) {
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
