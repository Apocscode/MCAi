package com.apocscode.mcai.task;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Task: Hunt and kill specific mobs.
 *
 * Resolves entity types by name (supports both vanilla and modded entities),
 * scans for nearby matching entities, navigates to them, and sets them as the
 * companion's attack target. The CompanionCombatGoal handles the actual combat.
 *
 * Supports:
 *   - Exact entity IDs: "minecraft:zombie", "livingthings:raccoon"
 *   - Simple names: "zombie", "raccoon", "cow"
 *   - Fuzzy matching: "creeper" matches "minecraft:creeper"
 *   - Kill count: hunt N entities before stopping
 */
public class KillMobTask extends CompanionTask {

    private static final int SCAN_RADIUS = 32;
    private static final int STUCK_TIMEOUT = 200; // 10 seconds per target
    private static final int RESCAN_INTERVAL = 40; // Rescan every 2 seconds

    @Nullable
    private final EntityType<?> targetType;
    private final String targetName;
    private final int targetCount;

    private int killCount = 0;
    private LivingEntity currentTarget = null;
    private int stuckTimer = 0;
    private int rescanTimer = 0;
    private int noTargetScans = 0;
    private static final int MAX_NO_TARGET_SCANS = 5;

    /**
     * @param companion   The companion entity
     * @param targetType  Resolved EntityType (null = hunt by name matching)
     * @param targetName  Display name for the target (e.g. "raccoon", "zombie")
     * @param targetCount How many to kill (0 = kill all nearby)
     */
    public KillMobTask(CompanionEntity companion, @Nullable EntityType<?> targetType,
                       String targetName, int targetCount) {
        super(companion);
        this.targetType = targetType;
        this.targetName = targetName;
        this.targetCount = targetCount > 0 ? targetCount : 1;
    }

    @Override
    public String getTaskName() {
        return "Kill " + targetCount + "x " + targetName;
    }

    @Override
    public int getProgressPercent() {
        return targetCount > 0 ? (killCount * 100 / targetCount) : -1;
    }

    @Override
    protected void start() {
        LivingEntity target = findTarget();
        if (target != null) {
            currentTarget = target;
            companion.setTarget(target);
            say("Found a " + targetName + "! Engaging!");
        } else {
            say("No " + targetName + " found nearby (searched " + SCAN_RADIUS + " blocks).");
            complete();
        }
    }

    @Override
    protected void tick() {
        // Check if we've killed enough
        if (killCount >= targetCount) {
            say("Killed " + killCount + "x " + targetName + "!");
            complete();
            return;
        }

        // Check if current target is dead or gone
        if (currentTarget != null && (!currentTarget.isAlive() || currentTarget.isRemoved())) {
            killCount++;
            MCAi.LOGGER.debug("KillMobTask: killed {} ({}/{})", targetName, killCount, targetCount);

            if (killCount >= targetCount) {
                say("Killed " + killCount + "x " + targetName + "!");
                complete();
                return;
            }

            // Find next target
            currentTarget = null;
            companion.setTarget(null);
            stuckTimer = 0;
        }

        // If no current target, search for one
        if (currentTarget == null) {
            rescanTimer++;
            if (rescanTimer >= RESCAN_INTERVAL) {
                rescanTimer = 0;
                LivingEntity target = findTarget();
                if (target != null) {
                    currentTarget = target;
                    companion.setTarget(target);
                    noTargetScans = 0;
                } else {
                    noTargetScans++;
                    if (noTargetScans >= MAX_NO_TARGET_SCANS) {
                        say("Can't find any more " + targetName + " nearby. Killed " + killCount + " total.");
                        complete();
                        return;
                    }
                }
            }
            return;
        }

        // Navigate towards target if too far
        double distSq = companion.distanceToSqr(currentTarget);
        if (distSq > 4.0) {
            navigateTo(currentTarget.blockPosition());
            stuckTimer++;
            if (stuckTimer > STUCK_TIMEOUT) {
                // Can't reach this target — skip it
                MCAi.LOGGER.debug("KillMobTask: stuck trying to reach {}, skipping", targetName);
                currentTarget = null;
                companion.setTarget(null);
                stuckTimer = 0;
            }
        } else {
            stuckTimer = 0;
            // Ensure combat goal has the right target
            if (companion.getTarget() != currentTarget) {
                companion.setTarget(currentTarget);
            }
        }
    }

    @Override
    protected void cleanup() {
        companion.setTarget(null);
        currentTarget = null;
    }

    /**
     * Find the nearest matching entity within scan radius.
     */
    @Nullable
    private LivingEntity findTarget() {
        AABB searchBox = new AABB(
                companion.getX() - SCAN_RADIUS, companion.getY() - SCAN_RADIUS / 2.0,
                companion.getZ() - SCAN_RADIUS,
                companion.getX() + SCAN_RADIUS, companion.getY() + SCAN_RADIUS / 2.0,
                companion.getZ() + SCAN_RADIUS
        );

        List<Entity> entities = companion.level().getEntities(companion, searchBox, e -> {
            if (!(e instanceof LivingEntity living)) return false;
            if (!living.isAlive()) return false;
            // Don't attack the owner, other players, or the companion itself
            if (e instanceof Player) return false;
            if (e instanceof CompanionEntity) return false;

            return matchesTarget(e);
        });

        // Sort by distance, return closest
        return entities.stream()
                .map(e -> (LivingEntity) e)
                .min(Comparator.comparingDouble(e -> companion.distanceToSqr(e)))
                .orElse(null);
    }

    /**
     * Check if an entity matches the target criteria.
     * Supports exact EntityType match and fuzzy name matching for modded mobs.
     */
    private boolean matchesTarget(Entity entity) {
        // Exact type match (fastest)
        if (targetType != null) {
            return entity.getType() == targetType;
        }

        // Fuzzy matching by entity type registry name and display name
        EntityType<?> type = entity.getType();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id != null) {
            String path = id.getPath().toLowerCase();
            String fullId = id.toString().toLowerCase();
            String query = targetName.toLowerCase().replace(" ", "_");

            // Exact path match
            if (path.equals(query)) return true;
            // Full ID match
            if (fullId.equals(query)) return true;
            // Path contains query
            if (path.contains(query)) return true;
        }

        // Display name match
        String displayName = entity.getName().getString().toLowerCase();
        if (displayName.contains(targetName.toLowerCase())) return true;

        return false;
    }

    // ========== Static entity resolution utilities ==========

    /**
     * Resolve an entity type from a user-provided name.
     * Searches both vanilla and modded registries.
     *
     * Tries in order:
     *   1. Exact ResourceLocation ("livingthings:raccoon")
     *   2. minecraft: namespace ("zombie" → "minecraft:zombie")
     *   3. Fuzzy path match across ALL registered entity types
     *   4. Display name match across ALL registered entity types
     *
     * @return Resolved EntityType, or null if not found
     */
    @Nullable
    public static EntityType<?> resolveEntityType(String query) {
        if (query == null || query.isBlank()) return null;

        String normalized = query.trim().toLowerCase().replace(" ", "_");

        // 1. Try exact ResourceLocation
        if (normalized.contains(":")) {
            return BuiltInRegistries.ENTITY_TYPE
                    .getOptional(ResourceLocation.parse(normalized))
                    .orElse(null);
        }

        // 2. Try minecraft: namespace
        Optional<EntityType<?>> vanilla = BuiltInRegistries.ENTITY_TYPE
                .getOptional(ResourceLocation.withDefaultNamespace(normalized));
        if (vanilla.isPresent()) return vanilla.get();

        // 3. Fuzzy search across all registered entity types
        EntityType<?> pathMatch = null;
        EntityType<?> partialMatch = null;

        for (var entry : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String path = id.getPath().toLowerCase();

            // Exact path match (any namespace)
            if (path.equals(normalized)) {
                pathMatch = entry.getValue();
                break; // Prefer exact path match
            }

            // Partial match
            if (path.contains(normalized) || normalized.contains(path)) {
                if (partialMatch == null) partialMatch = entry.getValue();
            }
        }

        if (pathMatch != null) return pathMatch;
        if (partialMatch != null) return partialMatch;

        // 4. Display name match (slower, last resort)
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            try {
                String name = type.getDescription().getString().toLowerCase();
                if (name.equals(normalized.replace("_", " ")) || name.contains(normalized.replace("_", " "))) {
                    return type;
                }
            } catch (Exception ignored) {
                // Some modded entity types may throw during getDescription
            }
        }

        return null;
    }

    /**
     * Get a human-readable name for an entity type.
     */
    public static String getEntityName(EntityType<?> type) {
        try {
            return type.getDescription().getString();
        } catch (Exception e) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            return id != null ? id.getPath() : "unknown";
        }
    }

    /**
     * Get the registry ID for an entity type.
     */
    public static String getEntityId(EntityType<?> type) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return id != null ? id.toString() : "unknown";
    }
}
