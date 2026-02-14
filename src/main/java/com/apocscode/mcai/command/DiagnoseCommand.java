package com.apocscode.mcai.command;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.planner.CraftingPlan;
import com.apocscode.mcai.ai.planner.RecipeResolver;
import com.apocscode.mcai.ai.planner.RecipeResolver.DependencyNode;
import com.apocscode.mcai.ai.planner.RecipeResolver.StepType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * In-game diagnostic command: /mcai diagnose
 * 
 * Iterates ALL vanilla (minecraft:) items through RecipeResolver and CraftingPlan,
 * producing a comprehensive report of:
 *   - Items with UNKNOWN steps (can't be auto-gathered)
 *   - Items with difficulty warnings (EXTREME, HARD, etc.)
 *   - Items with no recipe at all
 *   - Summary statistics
 *
 * Results are logged to latest.log and summarized in chat.
 * 
 * Usage:
 *   /mcai diagnose         — run full diagnostic on ALL vanilla items
 *   /mcai diagnose <item>  — run diagnostic on a specific item
 */
public class DiagnoseCommand {

    /** Items to skip — not craftable, not blocks, or purely creative-only */
    private static final Set<String> SKIP_ITEMS = Set.of(
            // Creative-only / unobtainable
            "air", "barrier", "light", "structure_void", "structure_block",
            "command_block", "chain_command_block", "repeating_command_block",
            "command_block_minecart", "jigsaw", "debug_stick",
            "knowledge_book", "bundle",
            // Spawn eggs — hundreds of them, skip them all by checking in code
            // Technical blocks
            "petrified_oak_slab", "player_head", "player_wall_head",
            "spawner", "trial_spawner", "vault",
            "infested_stone", "infested_cobblestone", "infested_stone_bricks",
            "infested_mossy_stone_bricks", "infested_cracked_stone_bricks",
            "infested_chiseled_stone_bricks", "infested_deepslate",
            // Wall variants of heads/banners/signs (placed only, not items)
            "skeleton_wall_skull", "wither_skeleton_wall_skull", "zombie_wall_head",
            "creeper_wall_head", "dragon_wall_head", "piglin_wall_head",
            // Potions / tipped arrows (too many variants, require brewing)
            "potion", "splash_potion", "lingering_potion", "tipped_arrow",
            // Technical / not real items
            "filled_map", "written_book", "writable_book",
            "firework_rocket", "firework_star",
            // Bedrock
            "bedrock", "end_portal_frame", "reinforced_deepslate",
            // Music discs (dungeon loot only)
            "music_disc_13", "music_disc_cat", "music_disc_blocks",
            "music_disc_chirp", "music_disc_far", "music_disc_mall",
            "music_disc_mellohi", "music_disc_stal", "music_disc_strad",
            "music_disc_ward", "music_disc_11", "music_disc_wait",
            "music_disc_otherside", "music_disc_5", "music_disc_pigstep",
            "music_disc_relic", "music_disc_creator", "music_disc_creator_music_box",
            "music_disc_precipice",
            // Banner patterns (loot/trade only)
            "globe_banner_pattern", "piglin_banner_pattern",
            "snout_banner_pattern", "flow_banner_pattern", "guster_banner_pattern",
            // Pottery sherds (archaeology only)
            "angler_pottery_sherd", "archer_pottery_sherd", "arms_up_pottery_sherd",
            "blade_pottery_sherd", "brewer_pottery_sherd", "burn_pottery_sherd",
            "danger_pottery_sherd", "explorer_pottery_sherd", "flow_pottery_sherd",
            "friend_pottery_sherd", "guster_pottery_sherd", "heart_pottery_sherd",
            "heartbreak_pottery_sherd", "howl_pottery_sherd", "miner_pottery_sherd",
            "mourner_pottery_sherd", "plenty_pottery_sherd", "prize_pottery_sherd",
            "scrape_pottery_sherd", "sheaf_pottery_sherd", "shelter_pottery_sherd",
            "skull_pottery_sherd", "snort_pottery_sherd",
            // Smithing templates (loot only)
            "coast_armor_trim_smithing_template", "dune_armor_trim_smithing_template",
            "eye_armor_trim_smithing_template", "host_armor_trim_smithing_template",
            "raiser_armor_trim_smithing_template", "rib_armor_trim_smithing_template",
            "sentry_armor_trim_smithing_template", "shaper_armor_trim_smithing_template",
            "silence_armor_trim_smithing_template", "snout_armor_trim_smithing_template",
            "spire_armor_trim_smithing_template", "tide_armor_trim_smithing_template",
            "vex_armor_trim_smithing_template", "ward_armor_trim_smithing_template",
            "wayfinder_armor_trim_smithing_template", "wild_armor_trim_smithing_template",
            "flow_armor_trim_smithing_template", "bolt_armor_trim_smithing_template",
            "netherite_upgrade_smithing_template"
    );

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("mcai")
                        .then(Commands.literal("diagnose")
                                .executes(DiagnoseCommand::runFullDiagnostic)
                                .then(Commands.argument("item", StringArgumentType.greedyString())
                                        .executes(DiagnoseCommand::runSingleDiagnostic)))
        );
    }

    /**
     * /mcai diagnose — run full diagnostic on ALL vanilla items.
     * Runs async to avoid freezing the server.
     */
    private static int runFullDiagnostic(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("§e[MCAi] Starting full recipe diagnostic for ALL vanilla items..."), false);
        source.sendSuccess(() -> Component.literal("§7Results will appear in chat and latest.log"), false);

        // Run on the server thread to access recipes safely
        server.execute(() -> {
            try {
                RecipeResolver resolver = new RecipeResolver(
                        server.getRecipeManager(), server.registryAccess());

                // Collect ALL vanilla items
                List<Item> vanillaItems = new ArrayList<>();
                for (Item item : BuiltInRegistries.ITEM) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    if (id == null || !id.getNamespace().equals("minecraft")) continue;
                    String path = id.getPath();
                    if (SKIP_ITEMS.contains(path)) continue;
                    if (path.contains("spawn_egg")) continue; // Skip all spawn eggs
                    if (item == Items.AIR) continue;
                    vanillaItems.add(item);
                }

                MCAi.LOGGER.info("========================================");
                MCAi.LOGGER.info("  MCAi FULL RECIPE DIAGNOSTIC");
                MCAi.LOGGER.info("  Testing {} vanilla items", vanillaItems.size());
                MCAi.LOGGER.info("========================================");

                // Categories for the report
                List<String> fullyResolved = new ArrayList<>();      // All steps known
                List<String> hasUnknown = new ArrayList<>();          // Has UNKNOWN steps
                List<String> impossibleItems = new ArrayList<>();     // IMPOSSIBLE difficulty
                List<String> extremeItems = new ArrayList<>();        // EXTREME difficulty
                List<String> hardItems = new ArrayList<>();           // HARD difficulty
                List<String> resolveErrors = new ArrayList<>();       // Threw exception
                Map<String, String> unknownDetails = new LinkedHashMap<>(); // itemId -> unknown ingredients

                for (Item item : vanillaItems) {
                    String itemId = BuiltInRegistries.ITEM.getKey(item).getPath();

                    try {
                        DependencyNode tree = resolver.resolve(item, 1, new HashMap<>());
                        CraftingPlan plan = CraftingPlan.fromTree(tree);

                        // Check for UNKNOWN steps
                        List<CraftingPlan.Step> unknowns = plan.getSteps().stream()
                                .filter(s -> s.type == StepType.UNKNOWN)
                                .toList();

                        if (!unknowns.isEmpty()) {
                            String unknownList = unknowns.stream()
                                    .map(s -> s.itemId)
                                    .distinct()
                                    .collect(java.util.stream.Collectors.joining(", "));
                            hasUnknown.add(itemId + " → UNKNOWN: " + unknownList);
                            unknownDetails.put(itemId, unknownList);
                        }

                        // Run difficulty analysis
                        List<CraftingPlan.DifficultyWarning> warnings = plan.analyzeDifficulty();
                        CraftingPlan.Difficulty maxDiff = CraftingPlan.getMaxDifficulty(warnings);

                        if (maxDiff == CraftingPlan.Difficulty.IMPOSSIBLE) {
                            String warnText = warnings.stream()
                                    .filter(w -> w.level() == CraftingPlan.Difficulty.IMPOSSIBLE)
                                    .map(w -> w.itemId() + ": " + w.warning())
                                    .collect(java.util.stream.Collectors.joining("; "));
                            impossibleItems.add(itemId + " → " + warnText);
                        } else if (maxDiff == CraftingPlan.Difficulty.EXTREME) {
                            extremeItems.add(itemId);
                        } else if (maxDiff == CraftingPlan.Difficulty.HARD) {
                            hardItems.add(itemId);
                        }

                        if (unknowns.isEmpty()) {
                            fullyResolved.add(itemId);
                        }

                    } catch (Exception e) {
                        resolveErrors.add(itemId + " → " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        MCAi.LOGGER.error("Diagnostic error for {}: {}", itemId, e.getMessage());
                    }
                }

                // ========== LOG FULL REPORT ==========
                final int totalTested = vanillaItems.size();
                MCAi.LOGGER.info("========================================");
                MCAi.LOGGER.info("  DIAGNOSTIC RESULTS");
                MCAi.LOGGER.info("========================================");
                MCAi.LOGGER.info("  Total tested: {}", totalTested);
                MCAi.LOGGER.info("  Fully resolved: {}", fullyResolved.size());
                MCAi.LOGGER.info("  Has UNKNOWN steps: {}", hasUnknown.size());
                MCAi.LOGGER.info("  IMPOSSIBLE items: {}", impossibleItems.size());
                MCAi.LOGGER.info("  EXTREME items: {}", extremeItems.size());
                MCAi.LOGGER.info("  HARD items: {}", hardItems.size());
                MCAi.LOGGER.info("  Errors: {}", resolveErrors.size());
                MCAi.LOGGER.info("----------------------------------------");

                if (!hasUnknown.isEmpty()) {
                    MCAi.LOGGER.info("--- ITEMS WITH UNKNOWN STEPS ---");
                    for (String s : hasUnknown) {
                        MCAi.LOGGER.info("  {}", s);
                    }
                }

                if (!impossibleItems.isEmpty()) {
                    MCAi.LOGGER.info("--- IMPOSSIBLE ITEMS ---");
                    for (String s : impossibleItems) {
                        MCAi.LOGGER.info("  {}", s);
                    }
                }

                if (!extremeItems.isEmpty()) {
                    MCAi.LOGGER.info("--- EXTREME DIFFICULTY ITEMS ---");
                    for (String s : extremeItems) {
                        MCAi.LOGGER.info("  {}", s);
                    }
                }

                if (!hardItems.isEmpty()) {
                    MCAi.LOGGER.info("--- HARD DIFFICULTY ITEMS ---");
                    for (String s : hardItems) {
                        MCAi.LOGGER.info("  {}", s);
                    }
                }

                if (!resolveErrors.isEmpty()) {
                    MCAi.LOGGER.info("--- RESOLUTION ERRORS ---");
                    for (String s : resolveErrors) {
                        MCAi.LOGGER.info("  {}", s);
                    }
                }

                // Collect all unique UNKNOWN ingredients across all items
                Set<String> allUnknownIngredients = new TreeSet<>();
                for (String unknownList : unknownDetails.values()) {
                    for (String u : unknownList.split(", ")) {
                        allUnknownIngredients.add(u.trim());
                    }
                }

                if (!allUnknownIngredients.isEmpty()) {
                    MCAi.LOGGER.info("--- ALL UNIQUE UNKNOWN INGREDIENTS ({}) ---", allUnknownIngredients.size());
                    for (String u : allUnknownIngredients) {
                        // Count how many items need this unknown ingredient
                        long count = unknownDetails.values().stream()
                                .filter(v -> v.contains(u))
                                .count();
                        MCAi.LOGGER.info("  {} (needed by {} items)", u, count);
                    }
                }

                MCAi.LOGGER.info("========================================");
                MCAi.LOGGER.info("  END DIAGNOSTIC REPORT");
                MCAi.LOGGER.info("========================================");

                // ========== CHAT SUMMARY ==========
                source.sendSuccess(() -> Component.literal(
                        "§a[MCAi] Diagnostic complete! Tested " + totalTested + " vanilla items."), false);
                source.sendSuccess(() -> Component.literal(
                        "§a  ✓ " + fullyResolved.size() + " fully resolved"), false);

                if (!hasUnknown.isEmpty()) {
                    source.sendSuccess(() -> Component.literal(
                            "§c  ✗ " + hasUnknown.size() + " items have UNKNOWN ingredients"), false);
                }
                if (!impossibleItems.isEmpty()) {
                    source.sendSuccess(() -> Component.literal(
                            "§c  ⚠ " + impossibleItems.size() + " items are IMPOSSIBLE to auto-craft"), false);
                }
                if (!extremeItems.isEmpty()) {
                    source.sendSuccess(() -> Component.literal(
                            "§6  ⚠ " + extremeItems.size() + " items are EXTREME difficulty"), false);
                }
                if (!hardItems.isEmpty()) {
                    source.sendSuccess(() -> Component.literal(
                            "§e  △ " + hardItems.size() + " items are HARD difficulty"), false);
                }
                if (!resolveErrors.isEmpty()) {
                    source.sendSuccess(() -> Component.literal(
                            "§c  ✗ " + resolveErrors.size() + " items threw errors"), false);
                }
                if (!allUnknownIngredients.isEmpty()) {
                    source.sendSuccess(() -> Component.literal(
                            "§7  " + allUnknownIngredients.size() + " unique unknown ingredients (see log)"), false);
                    // Show first 15 in chat
                    List<String> unknownList = new ArrayList<>(allUnknownIngredients);
                    int limit = Math.min(15, unknownList.size());
                    for (int i = 0; i < limit; i++) {
                        final String ingredient = unknownList.get(i);
                        source.sendSuccess(() -> Component.literal("§7    - " + ingredient), false);
                    }
                    if (unknownList.size() > 15) {
                        final int remaining = unknownList.size() - 15;
                        source.sendSuccess(() -> Component.literal(
                                "§7    ... and " + remaining + " more (see latest.log)"), false);
                    }
                }
                source.sendSuccess(() -> Component.literal(
                        "§7Full details in latest.log (search for 'DIAGNOSTIC')"), false);

            } catch (Exception e) {
                MCAi.LOGGER.error("Diagnostic command failed: {}", e.getMessage(), e);
                source.sendFailure(Component.literal("§c[MCAi] Diagnostic failed: " + e.getMessage()));
            }
        });

        return 1;
    }

    /**
     * /mcai diagnose <item> — run diagnostic on a single item.
     */
    private static int runSingleDiagnostic(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String itemName = StringArgumentType.getString(ctx, "item");

        // Normalize — strip "minecraft:" prefix if present
        String path = itemName.replace("minecraft:", "");

        server.execute(() -> {
            try {
                // Find the item
                ResourceLocation rl = ResourceLocation.withDefaultNamespace(path);
                Item item = BuiltInRegistries.ITEM.get(rl);
                if (item == Items.AIR) {
                    source.sendFailure(Component.literal("§c[MCAi] Unknown item: " + path));
                    return;
                }

                RecipeResolver resolver = new RecipeResolver(
                        server.getRecipeManager(), server.registryAccess());
                DependencyNode tree = resolver.resolve(item, 1, new HashMap<>());

                // Print tree
                String treeStr = RecipeResolver.printTree(tree);
                MCAi.LOGGER.info("Diagnostic for {}:\n{}", path, treeStr);

                // Build plan
                CraftingPlan plan = CraftingPlan.fromTree(tree);

                source.sendSuccess(() -> Component.literal("§e[MCAi] Diagnostic for: " + path), false);
                source.sendSuccess(() -> Component.literal("§7Plan: " + plan.summarize()), false);

                // Show steps
                for (CraftingPlan.Step step : plan.getSteps()) {
                    String color = switch (step.type) {
                        case UNKNOWN -> "§c";
                        case KILL_MOB -> "§6";
                        case MINE -> "§b";
                        case CHOP -> "§2";
                        case GATHER -> "§a";
                        case FARM -> "§a";
                        case FISH -> "§3";
                        case CRAFT -> "§f";
                        case SMELT, BLAST, SMOKE, CAMPFIRE_COOK -> "§6";
                        case STONECUT -> "§7";
                        case AVAILABLE -> "§a";
                    };
                    source.sendSuccess(() -> Component.literal(
                            color + "  " + step.type + " " + step.itemId + " x" + step.count), false);
                }

                // Difficulty warnings
                List<CraftingPlan.DifficultyWarning> warnings = plan.analyzeDifficulty();
                if (!warnings.isEmpty()) {
                    source.sendSuccess(() -> Component.literal("§eDifficulty warnings:"), false);
                    for (CraftingPlan.DifficultyWarning w : warnings) {
                        String prefix = switch (w.level()) {
                            case IMPOSSIBLE -> "§c[IMPOSSIBLE]";
                            case EXTREME -> "§c[EXTREME]";
                            case HARD -> "§6[HARD]";
                            case MODERATE -> "§e[MODERATE]";
                            case EASY -> "§a[EASY]";
                        };
                        source.sendSuccess(() -> Component.literal(
                                "  " + prefix + " §f" + w.warning()), false);
                    }
                } else {
                    source.sendSuccess(() -> Component.literal("§a  No difficulty warnings — should be straightforward!"), false);
                }

                source.sendSuccess(() -> Component.literal("§7Full tree logged to latest.log"), false);

            } catch (Exception e) {
                MCAi.LOGGER.error("Diagnostic error for {}: {}", path, e.getMessage(), e);
                source.sendFailure(Component.literal("§c[MCAi] Error: " + e.getMessage()));
            }
        });

        return 1;
    }
}
