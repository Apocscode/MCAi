package com.apocscode.mcai.task;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * Ore mining guide — Y-level ranges, tool tier requirements, and matching logic.
 *
 * Based on Minecraft 1.21 ore distribution (post-1.18 world generation).
 * All Y-levels are for the Overworld unless noted.
 */
public class OreGuide {

    /**
     * Ore entry with all mining-relevant data.
     */
    public enum Ore {
        COAL("coal", BlockTags.COAL_ORES, 0, 320, 96, 0,
                "Abundant above Y=0. Best around Y=96. Any pickaxe works."),
        COPPER("copper", BlockTags.COPPER_ORES, -16, 112, 48, 0,
                "Most common around Y=48. Any pickaxe works."),
        IRON("iron", BlockTags.IRON_ORES, -64, 320, 16, 1,
                "Two peaks: Y=16 and Y=256 (mountains). Best strip-mine at Y=16. Needs stone pickaxe+."),
        LAPIS("lapis", BlockTags.LAPIS_ORES, -64, 64, 0, 1,
                "Best at Y=0 (triangle distribution). Needs stone pickaxe+."),
        GOLD("gold", BlockTags.GOLD_ORES, -64, 32, -16, 2,
                "Best at Y=-16. Needs iron pickaxe+. Also found as nether_gold_ore in Nether."),
        REDSTONE("redstone", BlockTags.REDSTONE_ORES, -64, 16, -59, 2,
                "Most common at Y=-59 (bottom of world). Needs iron pickaxe+."),
        DIAMOND("diamond", BlockTags.DIAMOND_ORES, -64, 16, -59, 2,
                "Most common at Y=-59. Reduced near air exposure. Needs iron pickaxe+."),
        EMERALD("emerald", BlockTags.EMERALD_ORES, -16, 320, 232, 2,
                "Mountain biomes only. Best at high Y in mountains. Needs iron pickaxe+.");

        /** Name used for matching (lowercase, no "ore" suffix). */
        public final String name;
        /** Block tag used to identify this ore type. */
        public final TagKey<Block> tag;
        /** Minimum Y where this ore generates. */
        public final int minY;
        /** Maximum Y where this ore generates. */
        public final int maxY;
        /** Optimal Y-level for mining (highest concentration). */
        public final int bestY;
        /** Minimum pickaxe tier needed: 0=wood, 1=stone, 2=iron, 3=diamond. */
        public final int minTier;
        /** Human-readable mining tip. */
        public final String tip;

        Ore(String name, TagKey<Block> tag, int minY, int maxY, int bestY, int minTier, String tip) {
            this.name = name;
            this.tag = tag;
            this.minY = minY;
            this.maxY = maxY;
            this.bestY = bestY;
            this.minTier = minTier;
            this.tip = tip;
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
     * "iron", "iron_ore", "raw_iron", "iron ore" all match IRON.
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
        return null;
    }

    /**
     * Get all ores as a list.
     */
    public static List<Ore> allOres() {
        return Arrays.asList(Ore.values());
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
        for (Ore ore : Ore.values()) {
            sb.append("  ").append(ore.name).append(": Y=")
                    .append(ore.minY).append(" to ").append(ore.maxY)
                    .append(", best Y=").append(ore.bestY)
                    .append(", needs ").append(ore.tierName()).append(" pickaxe+")
                    .append(". ").append(ore.tip).append("\n");
        }
        return sb.toString();
    }
}
