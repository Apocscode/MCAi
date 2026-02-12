package com.apocscode.mcai.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Companion leveling system.
 * XP is earned from completing tasks, combat kills, and various activities.
 * Each level grants stat bonuses: max health, movement speed, attack damage.
 *
 * Level thresholds use a simple quadratic curve:
 *   XP needed for level N = 100 * N^2
 *   Level 1:   100 XP
 *   Level 5:  2500 XP
 *   Level 10: 10000 XP
 *   Level 20: 40000 XP
 *
 * Per-level bonuses:
 *   +2 max health (1 heart) per level
 *   +0.005 movement speed per level
 *   +0.5 attack damage per level
 */
public class CompanionLevelSystem {

    private int level = 0;
    private int totalXp = 0;

    // Base attributes (level 0 values from CompanionEntity.createAttributes)
    private static final double BASE_MAX_HEALTH = 40.0;
    private static final double BASE_MOVEMENT_SPEED = 0.35;
    private static final double BASE_ATTACK_DAMAGE = 3.0;

    // Per-level bonuses
    private static final double HEALTH_PER_LEVEL = 2.0;     // +1 heart per level
    private static final double SPEED_PER_LEVEL = 0.005;
    private static final double DAMAGE_PER_LEVEL = 0.5;

    private static final int MAX_LEVEL = 30;

    // ================================================================
    // XP / Level Management
    // ================================================================

    /**
     * Add XP and check for level up.
     * @return true if the companion leveled up
     */
    public boolean addXp(int xp) {
        if (level >= MAX_LEVEL) return false;
        totalXp += xp;

        boolean leveled = false;
        while (level < MAX_LEVEL && totalXp >= xpForNextLevel()) {
            level++;
            leveled = true;
        }
        return leveled;
    }

    /** XP required to reach the next level. */
    public int xpForNextLevel() {
        int next = level + 1;
        return 100 * next * next;
    }

    /** XP progress toward next level (0.0 to 1.0). */
    public float getProgress() {
        if (level >= MAX_LEVEL) return 1.0f;
        int prevThreshold = 100 * level * level;
        int nextThreshold = xpForNextLevel();
        int range = nextThreshold - prevThreshold;
        if (range <= 0) return 1.0f;
        return (float)(totalXp - prevThreshold) / range;
    }

    public int getLevel() { return level; }
    public int getTotalXp() { return totalXp; }

    // ================================================================
    // Stat Bonuses
    // ================================================================

    public double getBonusMaxHealth() { return level * HEALTH_PER_LEVEL; }
    public double getBonusSpeed() { return level * SPEED_PER_LEVEL; }
    public double getBonusDamage() { return level * DAMAGE_PER_LEVEL; }

    /**
     * Apply level bonuses to the companion's attributes.
     * Should be called after level-up and on entity load.
     */
    public void applyBonuses(CompanionEntity companion) {
        AttributeInstance health = companion.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(BASE_MAX_HEALTH + getBonusMaxHealth());
        }

        AttributeInstance speed = companion.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(BASE_MOVEMENT_SPEED + getBonusSpeed());
        }

        AttributeInstance damage = companion.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(BASE_ATTACK_DAMAGE + getBonusDamage());
        }
    }

    // ================================================================
    // XP Rewards (static helpers)
    // ================================================================

    /** XP for completing a task (mining, gathering, building, etc.) */
    public static int TASK_COMPLETE_XP = 25;

    /** XP for killing a hostile mob */
    public static int MOB_KILL_XP = 10;

    /** XP for crafting an item */
    public static int CRAFT_XP = 5;

    /** XP for smelting */
    public static int SMELT_XP = 8;

    /** XP for fishing a catch */
    public static int FISH_XP = 3;

    /** XP for completing a delivery */
    public static int DELIVER_XP = 15;

    /** XP for a trade */
    public static int TRADE_XP = 10;

    // ================================================================
    // NBT Persistence
    // ================================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Level", level);
        tag.putInt("TotalXp", totalXp);
        return tag;
    }

    public void load(CompoundTag tag) {
        level = tag.getInt("Level");
        totalXp = tag.getInt("TotalXp");
    }

    /** Get a short display string like "Lvl 5 (2500/3600 XP)" */
    public String getDisplayString() {
        if (level >= MAX_LEVEL) {
            return "Lvl " + level + " (MAX)";
        }
        return "Lvl " + level + " (" + totalXp + "/" + xpForNextLevel() + " XP)";
    }
}
