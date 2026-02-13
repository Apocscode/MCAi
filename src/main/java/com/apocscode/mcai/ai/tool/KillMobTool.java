package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.task.KillMobTask;
import com.apocscode.mcai.task.TaskContinuation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Tool: Kill/attack specific mobs.
 *
 * Resolves entity types from user-friendly names (supports both vanilla and modded entities).
 * The companion navigates to the target mob and attacks it using CompanionCombatGoal.
 *
 * Examples:
 *   kill_mob(mob="zombie", count=3)
 *   kill_mob(mob="raccoon")              — Living Things mod
 *   kill_mob(mob="cow", count=2)         — for leather
 *   kill_mob(mob="livingthings:raccoon") — explicit mod ID
 */
public class KillMobTool implements AiTool {

    private static final int SCAN_RADIUS = 32;

    @Override
    public String name() {
        return "kill_mob";
    }

    @Override
    public String description() {
        return "Hunt and kill a specific mob. Supports both vanilla mobs (zombie, skeleton, cow) " +
                "and modded mobs (raccoon from Living Things, etc.). " +
                "The companion will find the nearest matching mob, navigate to it, and attack. " +
                "Specify 'mob' (name or ID), 'count' (how many to kill, default 1). " +
                "Works with mod:entity_name format (e.g. 'livingthings:raccoon') or just the mob name.";
    }

    @Override
    public JsonObject parameterSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject props = new JsonObject();

        JsonObject mob = new JsonObject();
        mob.addProperty("type", "string");
        mob.addProperty("description",
                "The mob to kill. Can be a simple name ('zombie', 'raccoon', 'cow', 'creeper') " +
                "or a full entity ID ('minecraft:zombie', 'livingthings:raccoon'). " +
                "Fuzzy matching is used — 'raccoon' will find 'livingthings:raccoon' automatically.");
        props.add("mob", mob);

        JsonObject count = new JsonObject();
        count.addProperty("type", "integer");
        count.addProperty("description", "How many to kill. Default: 1. Set higher for farming drops (e.g. 3 cows for leather).");
        props.add("count", count);

        JsonObject plan = new JsonObject();
        plan.addProperty("type", "string");
        plan.addProperty("description",
                "Optional: what to do AFTER killing completes. " +
                "Example: 'craft leather_armor' or 'craft bow'. " +
                "The plan will auto-execute when all kills are done.");
        props.add("plan", plan);

        schema.add("properties", props);

        JsonArray required = new JsonArray();
        required.add("mob");
        schema.add("required", required);

        return schema;
    }

    @Override
    public String execute(JsonObject args, ToolContext context) {
        if (context.player() == null || context.server() == null) {
            return "Error: no server context";
        }

        String mobQuery = args.has("mob") ? args.get("mob").getAsString().trim() : "";
        if (mobQuery.isEmpty()) return "Error: no mob specified. Provide the mob name (e.g. 'zombie', 'raccoon', 'cow').";

        int count = args.has("count") ? args.get("count").getAsInt() : 1;
        if (count < 1) count = 1;

        final int finalCount = count;
        final String finalQuery = mobQuery;

        return context.runOnServer(() -> {
            CompanionEntity companion = CompanionEntity.getLivingCompanion(context.player().getUUID());
            if (companion == null) return "No companion found.";

            // Resolve entity type
            EntityType<?> resolvedType = KillMobTask.resolveEntityType(finalQuery);

            // Even if type resolution fails, we can still try fuzzy matching at runtime
            String displayName;
            String typeInfo;
            if (resolvedType != null) {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(resolvedType);
                displayName = KillMobTask.getEntityName(resolvedType);
                typeInfo = id != null ? id.toString() : finalQuery;
                MCAi.LOGGER.info("Resolved mob '{}' → {} ({})", finalQuery, displayName, typeInfo);
            } else {
                // Type not resolved — the task will use fuzzy name matching at runtime
                displayName = finalQuery;
                typeInfo = "fuzzy:" + finalQuery;
                MCAi.LOGGER.info("Mob '{}' not found in registry — will use fuzzy name matching", finalQuery);
            }

            // Quick scan — check if any matching mobs are actually nearby
            int nearbyCount = countNearbyMobs(companion, resolvedType, finalQuery);
            if (nearbyCount == 0) {
                String suggestion = resolvedType == null
                        ? " Check the mob name — it might be spelled differently or from a mod not installed."
                        : " Try moving to where they spawn or wait for one to appear.";
                return "No " + displayName + " found within " + SCAN_RADIUS + " blocks." + suggestion;
            }

            // Equip best weapon before starting
            companion.equipBestWeapon();

            // Create the task
            KillMobTask task = new KillMobTask(companion, resolvedType, displayName, finalCount);

            // Continuation plan
            if (args.has("plan") && !args.get("plan").getAsString().isBlank()) {
                String planText = args.get("plan").getAsString();
                task.setContinuation(new TaskContinuation(
                        context.player().getUUID(),
                        "Kill " + finalCount + "x " + displayName + ", then: " + planText,
                        planText
                ));
            }

            companion.getTaskManager().queueTask(task);

            StringBuilder resp = new StringBuilder();
            resp.append("[ASYNC_TASK] Hunting ").append(finalCount).append("x ").append(displayName);
            resp.append(" (").append(typeInfo).append("). ");
            resp.append("Found ").append(nearbyCount).append(" nearby. ");
            if (resolvedType == null) {
                resp.append("Using fuzzy name matching — if wrong mob is targeted, use the full ID (e.g. 'modname:mob_name'). ");
            }
            resp.append("This task runs over time — STOP calling tools and tell the player you're on it.");

            return resp.toString();
        });
    }

    /**
     * Count matching mobs within scan radius.
     */
    private int countNearbyMobs(CompanionEntity companion, EntityType<?> resolvedType, String nameQuery) {
        AABB searchBox = new AABB(
                companion.getX() - SCAN_RADIUS, companion.getY() - SCAN_RADIUS / 2.0,
                companion.getZ() - SCAN_RADIUS,
                companion.getX() + SCAN_RADIUS, companion.getY() + SCAN_RADIUS / 2.0,
                companion.getZ() + SCAN_RADIUS
        );

        List<Entity> entities = companion.level().getEntities(companion, searchBox, e -> {
            if (!(e instanceof LivingEntity living)) return false;
            if (!living.isAlive()) return false;
            if (e instanceof Player) return false;
            if (e instanceof CompanionEntity) return false;

            if (resolvedType != null) {
                return e.getType() == resolvedType;
            }

            // Fuzzy name matching
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
            if (id != null) {
                String path = id.getPath().toLowerCase();
                String query = nameQuery.toLowerCase().replace(" ", "_");
                if (path.equals(query) || path.contains(query)) return true;
            }
            String display = e.getName().getString().toLowerCase();
            return display.contains(nameQuery.toLowerCase());
        });

        return entities.size();
    }
}
