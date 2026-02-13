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
                        "mine_ores({\"maxOres\":%d,\"plan\":\"%s\"})", count, remainingPlan);
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
                        "farm_area({\"crop\":\"%s\",\"plan\":\"%s\"})", itemId, remainingPlan);
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

    /**
     * Prepend prerequisite steps to the front of the plan.
     * Used for tool prerequisites (e.g., craft stone_pickaxe before mining iron).
     * Steps are inserted in order, before existing steps, respecting priority.
     */
    public void prependSteps(List<Step> prereqs) {
        // Insert at the beginning, maintaining priority order
        List<Step> merged = new ArrayList<>(prereqs);
        merged.addAll(steps);
        // Re-sort by priority to maintain correct execution order
        merged.sort(Comparator.comparingInt(s -> typePriority(s.type)));
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
            default -> {
                if (itemId.contains("wool")) yield "sheep";
                if (itemId.contains("meat")) yield "cow";
                yield itemId;
            }
        };
    }
}
