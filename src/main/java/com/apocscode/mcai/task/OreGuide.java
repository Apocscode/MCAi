package com.apocscode.mcai.task;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ore & resource mining guide — Y-level ranges, tool tier requirements, and matching logic.
 *
 * Covers vanilla Minecraft 1.21 ores, Nether resources, and modded ores from
 * ATM10 mods (Mekanism, Thermal Series, Create, AE2, Immersive Engineering, etc.)
 * using NeoForge common tags (c:ores/*).
 *
 * All Y-levels are for the Overworld unless noted. Nether ores use Nether Y coords.
 */
public class OreGuide {

    // ================================================================
    // Helper: create a NeoForge common tag (c:ores/<name>)
    // ================================================================
    private static TagKey<Block> commonOreTag(String name) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores/" + name));
    }

    /**
     * Ore/resource entry with all mining-relevant data.
     *
     * Each entry has:
     *   name     — lowercase key for matching (e.g. "iron", "osmium", "ancient debris")
     *   tag      — block tag used to identify this resource in-world
     *   minY     — lowest Y where this resource generates
     *   maxY     — highest Y where this resource generates
     *   bestY    — optimal Y-level for highest concentration
     *   minTier  — minimum pickaxe tier: 0=wood, 1=stone, 2=iron, 3=diamond, 4=netherite
     *   tip      — human-readable mining advice
     *   modded   — true if this is from a mod (not vanilla Minecraft)
     *   nether   — true if this generates in the Nether dimension
     */
    public enum Ore {
        // ────────────────────────────────────────────────────────────
        // Vanilla Overworld Ores
        // ────────────────────────────────────────────────────────────
        COAL("coal", BlockTags.COAL_ORES, 0, 320, 96, 0,
                "Abundant above Y=0. Best around Y=96. Any pickaxe works.", false, false),
        COPPER("copper", BlockTags.COPPER_ORES, -16, 112, 48, 0,
                "Most common around Y=48. Any pickaxe works.", false, false),
        IRON("iron", BlockTags.IRON_ORES, -64, 320, 16, 1,
                "Two peaks: Y=16 and Y=256 (mountains). Best at Y=16. Stone pickaxe+.", false, false),
        LAPIS("lapis", BlockTags.LAPIS_ORES, -64, 64, 0, 1,
                "Best at Y=0 (triangle distribution). Stone pickaxe+.", false, false),
        GOLD("gold", BlockTags.GOLD_ORES, -64, 32, -16, 2,
                "Best at Y=-16. Iron pickaxe+. Also found in Nether.", false, false),
        REDSTONE("redstone", BlockTags.REDSTONE_ORES, -64, 16, -59, 2,
                "Most common at Y=-59 (bottom of world). Iron pickaxe+.", false, false),
        DIAMOND("diamond", BlockTags.DIAMOND_ORES, -64, 16, -59, 2,
                "Most common at Y=-59. Reduced near air exposure. Iron pickaxe+.", false, false),
        EMERALD("emerald", BlockTags.EMERALD_ORES, -16, 320, 232, 2,
                "Mountain biomes only. Best at high Y in mountains. Iron pickaxe+.", false, false),

        // ────────────────────────────────────────────────────────────
        // Vanilla Nether Resources
        // ────────────────────────────────────────────────────────────
        NETHER_QUARTZ("nether quartz", commonOreTag("quartz"), 10, 117, 15, 0,
                "Found throughout the Nether. Any pickaxe works.", false, true),
        NETHER_GOLD("nether gold", commonOreTag("gold"), 10, 117, 15, 0,
                "Found in Nether. Drops gold nuggets. Any pickaxe works.", false, true),
        ANCIENT_DEBRIS("ancient debris", commonOreTag("netherite_scrap"), 8, 119, 15, 3,
                "Extremely rare in Nether around Y=15. Diamond pickaxe required. Blast-resistant.", false, true),

        // ────────────────────────────────────────────────────────────
        // Mekanism Ores
        // ────────────────────────────────────────────────────────────
        OSMIUM("osmium", commonOreTag("osmium"), -64, 60, 16, 1,
                "Mekanism. Similar to iron distribution. Stone pickaxe+.", true, false),
        TIN("tin", commonOreTag("tin"), -20, 90, 20, 1,
                "Mekanism/Thermal. Common around Y=20. Stone pickaxe+.", true, false),
        LEAD("lead", commonOreTag("lead"), -64, 40, 8, 1,
                "Mekanism/Thermal/IE. Best around Y=8. Stone pickaxe+.", true, false),
        URANIUM("uranium", commonOreTag("uranium"), -64, 20, -20, 2,
                "Mekanism. Deep underground near Y=-20. Iron pickaxe+.", true, false),
        FLUORITE("fluorite", commonOreTag("fluorite"), -64, 16, -10, 2,
                "Mekanism. Deep underground. Iron pickaxe+.", true, false),

        // ────────────────────────────────────────────────────────────
        // Thermal Series Ores
        // ────────────────────────────────────────────────────────────
        SILVER("silver", commonOreTag("silver"), -64, 40, -10, 2,
                "Thermal/IE. Deep underground. Iron pickaxe+.", true, false),
        NICKEL("nickel", commonOreTag("nickel"), -64, 40, -10, 2,
                "Thermal/IE. Deep underground. Iron pickaxe+.", true, false),
        SULFUR("sulfur", commonOreTag("sulfur"), -64, 20, -20, 1,
                "Thermal. Deep underground near lava level. Stone pickaxe+.", true, false),
        APATITE("apatite", commonOreTag("apatite"), 48, 200, 80, 0,
                "Thermal/Forestry. Upper levels, similar to coal. Any pickaxe.", true, false),

        // ────────────────────────────────────────────────────────────
        // Create Mod Ores
        // ────────────────────────────────────────────────────────────
        ZINC("zinc", commonOreTag("zinc"), -64, 70, 20, 1,
                "Create. Common around Y=20. Stone pickaxe+.", true, false),

        // ────────────────────────────────────────────────────────────
        // Applied Energistics 2 (AE2)
        // ────────────────────────────────────────────────────────────
        CERTUS_QUARTZ("certus quartz", commonOreTag("certus_quartz"), -64, 40, 16, 2,
                "AE2. Found underground. Iron pickaxe+.", true, false),

        // ────────────────────────────────────────────────────────────
        // Immersive Engineering
        // ────────────────────────────────────────────────────────────
        ALUMINUM("aluminum", commonOreTag("aluminum"), -64, 72, 20, 1,
                "Immersive Engineering. Common around Y=20. Stone pickaxe+.", true, false),

        // ────────────────────────────────────────────────────────────
        // Gems & Precious Resources
        // ────────────────────────────────────────────────────────────
        RUBY("ruby", commonOreTag("ruby"), -64, 30, -20, 2,
                "Various mods (Gems). Deep underground. Iron pickaxe+.", true, false),
        SAPPHIRE("sapphire", commonOreTag("sapphire"), -64, 30, -20, 2,
                "Various mods (Gems). Deep underground. Iron pickaxe+.", true, false),
        PERIDOT("peridot", commonOreTag("peridot"), -64, 30, -10, 2,
                "Various mods (Gems). Deep underground. Iron pickaxe+.", true, false),

        // ────────────────────────────────────────────────────────────
        // Miscellaneous Modded
        // ────────────────────────────────────────────────────────────
        IRIDIUM("iridium", commonOreTag("iridium"), -64, 10, -40, 3,
                "Various mods. Very deep, very rare. Diamond pickaxe required.", true, false),
        PLATINUM("platinum", commonOreTag("platinum"), -64, 16, -40, 2,
                "Various mods. Very deep underground. Iron pickaxe+.", true, false);

        /** Name used for matching (lowercase). */
        public final String name;
        /** Block tag used to identify this ore type. */
        public final TagKey<Block> tag;
        /** Minimum Y where this ore generates. */
        public final int minY;
        /** Maximum Y where this ore generates. */
        public final int maxY;
        /** Optimal Y-level for mining (highest concentration). */
        public final int bestY;
        /** Minimum pickaxe tier needed: 0=wood, 1=stone, 2=iron, 3=diamond, 4=netherite. */
        public final int minTier;
        /** Human-readable mining tip. */
        public final String tip;
        /** True if this ore is from a mod (not vanilla Minecraft). */
        public final boolean modded;
        /** True if this ore generates in the Nether dimension. */
        public final boolean nether;

        Ore(String name, TagKey<Block> tag, int minY, int maxY, int bestY, int minTier,
            String tip, boolean modded, boolean nether) {
            this.name = name;
            this.tag = tag;
            this.minY = minY;
            this.maxY = maxY;
            this.bestY = bestY;
            this.minTier = minTier;
            this.tip = tip;
            this.modded = modded;
            this.nether = nether;
        }

        /** Tool tier name for display. */
        public String tierName() {
            return switch (minTier) {
                case 0 -> "wood";
                case 1 -> "stone";
                case 2 -> "iron";
                case 3 -> "diamond";
                case 4 -> "netherite";
                default -> "unknown";
            };
        }

        /** Check if a block state matches this ore. */
        public boolean matches(BlockState state) {
            return state.is(tag);
        }
    }

    /**
     * Find an Ore entry by name. Matches partial/fuzzy:
     * "iron", "iron_ore", "raw_iron", "iron ore", "nether quartz" all match.
     *
     * @return matching Ore, or null if no match
     */
    @Nullable
    public static Ore findByName(String query) {
        if (query == null || query.isBlank()) return null;

        String normalized = query.toLowerCase().trim()
                .replace("_ore", "")
                .replace(" ore", "")
                .replace("raw_", "")
                .replace("raw ", "")
                .replace("deepslate_", "")
                .replace("deepslate ", "")
                .replace("_ingot", "")
                .replace(" ingot", "")
                .replace("_dust", "")
                .replace(" dust", "")
                .replace("_gem", "")
                .replace(" gem", "")
                .replace("_crystal", "")
                .replace(" crystal", "")
                .replace("_", " ")
                .trim();

        // Exact match first
        for (Ore ore : Ore.values()) {
            if (ore.name.equals(normalized)) return ore;
        }
        // Partial match
        for (Ore ore : Ore.values()) {
            if (normalized.contains(ore.name) || ore.name.contains(normalized)) return ore;
        }
        // Alias matching for common alternate names
        return matchAlias(normalized);
    }

    /**
     * Match common aliases and alternate names.
     */
    @Nullable
    private static Ore matchAlias(String normalized) {
        return switch (normalized) {
            case "netherite", "debris", "ancient" -> Ore.ANCIENT_DEBRIS;
            case "quartz" -> Ore.NETHER_QUARTZ;
            case "certus", "ae2 quartz" -> Ore.CERTUS_QUARTZ;
            case "bauxite" -> Ore.ALUMINUM;
            case "aluminium" -> Ore.ALUMINUM;
            case "lapis lazuli" -> Ore.LAPIS;
            case "nether gold nugget", "piglins" -> Ore.NETHER_GOLD;
            default -> null;
        };
    }

    /**
     * Get all ores as a list.
     */
    public static List<Ore> allOres() {
        return Arrays.asList(Ore.values());
    }

    /**
     * Get only vanilla (non-modded) ores.
     */
    public static List<Ore> vanillaOres() {
        return Arrays.stream(Ore.values()).filter(o -> !o.modded).collect(Collectors.toList());
    }

    /**
     * Get only modded ores.
     */
    public static List<Ore> moddedOres() {
        return Arrays.stream(Ore.values()).filter(o -> o.modded).collect(Collectors.toList());
    }

    /**
     * Get only Overworld ores (excludes Nether resources).
     */
    public static List<Ore> overworldOres() {
        return Arrays.stream(Ore.values()).filter(o -> !o.nether).collect(Collectors.toList());
    }

    /**
     * Get all ore names as a comma-separated string for error messages.
     */
    public static String allOreNames() {
        return Arrays.stream(Ore.values())
                .map(o -> o.name)
                .collect(Collectors.joining(", "));
    }

    /**
     * Check if a block state is any ore type.
     */
    public static boolean isOre(BlockState state) {
        for (Ore ore : Ore.values()) {
            if (ore.matches(state)) return true;
        }
        return false;
    }

    /**
     * Identify which ore a block state is.
     *
     * @return matching Ore, or null if not an ore
     */
    @Nullable
    public static Ore identifyOre(BlockState state) {
        for (Ore ore : Ore.values()) {
            if (ore.matches(state)) return ore;
        }
        return null;
    }

    /**
     * Get a detailed mining guide string for AI context.
     */
    public static String getMiningGuide() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ore Mining Guide (Y-levels, optimal depths, tool requirements):\n");
        sb.append("--- Vanilla Ores ---\n");
        for (Ore ore : Ore.values()) {
            if (!ore.modded && !ore.nether) {
                appendOreInfo(sb, ore);
            }
        }
        sb.append("--- Nether Resources ---\n");
        for (Ore ore : Ore.values()) {
            if (ore.nether) {
                appendOreInfo(sb, ore);
            }
        }
        sb.append("--- Modded Ores (ATM10) ---\n");
        for (Ore ore : Ore.values()) {
            if (ore.modded && !ore.nether) {
                appendOreInfo(sb, ore);
            }
        }
        return sb.toString();
    }

    private static void appendOreInfo(StringBuilder sb, Ore ore) {
        sb.append("  ").append(ore.name).append(": Y=")
                .append(ore.minY).append(" to ").append(ore.maxY)
                .append(", best Y=").append(ore.bestY)
                .append(", needs ").append(ore.tierName()).append(" pickaxe+")
                .append(ore.nether ? " [NETHER]" : "")
                .append(". ").append(ore.tip).append("\n");
    }
}
