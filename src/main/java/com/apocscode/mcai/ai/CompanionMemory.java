package com.apocscode.mcai.ai;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.*;

/**
 * Persistent memory system for the AI companion.
 * Stores key-value facts, player preferences, and notable events.
 * All data is persisted via NBT with the CompanionEntity.
 */
public class CompanionMemory {

    /** General key-value facts (e.g. "player_favorite_biome" -> "plains") */
    private final Map<String, String> facts = new LinkedHashMap<>();

    /** Notable events with timestamps (e.g. "Built a house at x=100 z=200") */
    private final List<String> events = new ArrayList<>();

    /** Max number of stored events (FIFO when exceeded) */
    private static final int MAX_EVENTS = 50;

    /** Max number of facts */
    private static final int MAX_FACTS = 100;

    // ================================================================
    // Facts API
    // ================================================================

    /**
     * Store or update a fact.
     * @param key   Short key like "base_location", "favorite_tool"
     * @param value The value to remember
     */
    public void setFact(String key, String value) {
        if (facts.size() >= MAX_FACTS && !facts.containsKey(key)) {
            // Remove oldest entry
            String oldest = facts.keySet().iterator().next();
            facts.remove(oldest);
        }
        facts.put(key, value);
    }

    /** Retrieve a fact, or null if not stored. */
    public String getFact(String key) {
        return facts.get(key);
    }

    /** Remove a fact. */
    public void removeFact(String key) {
        facts.remove(key);
    }

    /** Get all stored facts. */
    public Map<String, String> getAllFacts() {
        return Collections.unmodifiableMap(facts);
    }

    // ================================================================
    // Events API
    // ================================================================

    /**
     * Record a notable event.
     * @param event Description of what happened
     */
    public void addEvent(String event) {
        if (events.size() >= MAX_EVENTS) {
            events.remove(0); // FIFO
        }
        events.add(event);
    }

    /** Get all recorded events (oldest first). */
    public List<String> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /** Get the most recent N events. */
    public List<String> getRecentEvents(int count) {
        int start = Math.max(0, events.size() - count);
        return events.subList(start, events.size());
    }

    // ================================================================
    // Build context string for AI system prompt
    // ================================================================

    /**
     * Build a summary of companion memory for inclusion in the AI system prompt.
     * Keeps it concise to avoid blowing up token count.
     */
    public String buildContextString() {
        if (facts.isEmpty() && events.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("\nCompanion Memory:\n");

        if (!facts.isEmpty()) {
            sb.append("Known facts:\n");
            for (Map.Entry<String, String> e : facts.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        }

        if (!events.isEmpty()) {
            sb.append("Recent events:\n");
            List<String> recent = getRecentEvents(10);
            for (String ev : recent) {
                sb.append("  - ").append(ev).append("\n");
            }
        }

        return sb.toString();
    }

    // ================================================================
    // NBT Persistence
    // ================================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();

        // Facts
        CompoundTag factsTag = new CompoundTag();
        for (Map.Entry<String, String> e : facts.entrySet()) {
            factsTag.putString(e.getKey(), e.getValue());
        }
        tag.put("Facts", factsTag);

        // Events
        ListTag eventList = new ListTag();
        for (String event : events) {
            eventList.add(StringTag.valueOf(event));
        }
        tag.put("Events", eventList);

        return tag;
    }

    public void load(CompoundTag tag) {
        facts.clear();
        events.clear();

        if (tag.contains("Facts")) {
            CompoundTag factsTag = tag.getCompound("Facts");
            for (String key : factsTag.getAllKeys()) {
                facts.put(key, factsTag.getString(key));
            }
        }

        if (tag.contains("Events")) {
            ListTag eventList = tag.getList("Events", 8); // 8 = StringTag
            for (int i = 0; i < eventList.size(); i++) {
                events.add(eventList.getString(i));
            }
        }
    }

    /** Clear all memory. */
    public void clear() {
        facts.clear();
        events.clear();
    }
}
