package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists HUD panel positions to a JSON file.
 * Both companion HUD and wand HUD positions are stored as percentage of screen size
 * (0.0–1.0) so they work across resolutions.
 *
 * File: .minecraft/config/mcai-hud.json
 */
public class HudPositionStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "mcai-hud.json";

    // --- Companion HUD (default: top-left) ---
    private static float compXPercent = 0.0f;
    private static float compYPercent = 0.0f;

    // --- Wand HUD (default: bottom-center, above hotbar) ---
    private static float wandXPercent = 0.5f;
    private static float wandYPercent = 0.9f;

    private static boolean loaded = false;

    // ================================================================
    // Companion HUD accessors
    // ================================================================

    public static int getX(int screenWidth, int panelWidth) {
        ensureLoaded();
        int maxX = Math.max(0, screenWidth - panelWidth);
        return Math.round(compXPercent * maxX);
    }

    public static int getY(int screenHeight, int panelHeight) {
        ensureLoaded();
        int maxY = Math.max(0, screenHeight - panelHeight);
        return Math.round(compYPercent * maxY);
    }

    public static void setPosition(int pixelX, int pixelY, int screenWidth, int screenHeight,
                                    int panelWidth, int panelHeight) {
        int maxX = Math.max(1, screenWidth - panelWidth);
        int maxY = Math.max(1, screenHeight - panelHeight);
        compXPercent = Math.clamp((float) pixelX / maxX, 0.0f, 1.0f);
        compYPercent = Math.clamp((float) pixelY / maxY, 0.0f, 1.0f);
    }

    // ================================================================
    // Wand HUD accessors
    // ================================================================

    public static int getWandX(int screenWidth, int panelWidth) {
        ensureLoaded();
        int maxX = Math.max(0, screenWidth - panelWidth);
        return Math.round(wandXPercent * maxX);
    }

    public static int getWandY(int screenHeight, int panelHeight) {
        ensureLoaded();
        int maxY = Math.max(0, screenHeight - panelHeight);
        return Math.round(wandYPercent * maxY);
    }

    public static void setWandPosition(int pixelX, int pixelY, int screenWidth, int screenHeight,
                                        int panelWidth, int panelHeight) {
        int maxX = Math.max(1, screenWidth - panelWidth);
        int maxY = Math.max(1, screenHeight - panelHeight);
        wandXPercent = Math.clamp((float) pixelX / maxX, 0.0f, 1.0f);
        wandYPercent = Math.clamp((float) pixelY / maxY, 0.0f, 1.0f);
    }

    // ================================================================
    // Reset & getters
    // ================================================================

    /** Reset both panels to default positions. */
    public static void reset() {
        compXPercent = 0.0f;
        compYPercent = 0.0f;
        wandXPercent = 0.5f;
        wandYPercent = 0.9f;
        save();
    }

    /** Reset only companion panel. */
    public static void resetCompanion() {
        compXPercent = 0.0f;
        compYPercent = 0.0f;
    }

    /** Reset only wand panel. */
    public static void resetWand() {
        wandXPercent = 0.5f;
        wandYPercent = 0.9f;
    }

    public static float getCompXPercent() { return compXPercent; }
    public static float getCompYPercent() { return compYPercent; }
    public static float getWandXPercent() { return wandXPercent; }
    public static float getWandYPercent() { return wandYPercent; }

    // ================================================================
    // Persistence
    // ================================================================

    private static Path getFilePath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
            loaded = true;
        }
    }

    public static void load() {
        Path path = getFilePath();
        if (!Files.exists(path)) {
            loaded = true;
            return;
        }
        try {
            String json = Files.readString(path);
            HudData data = GSON.fromJson(json, HudData.class);
            if (data != null) {
                compXPercent = Math.clamp(data.compXPercent, 0.0f, 1.0f);
                compYPercent = Math.clamp(data.compYPercent, 0.0f, 1.0f);
                wandXPercent = Math.clamp(data.wandXPercent, 0.0f, 1.0f);
                wandYPercent = Math.clamp(data.wandYPercent, 0.0f, 1.0f);
            }
            loaded = true;
        } catch (Exception e) {
            MCAi.LOGGER.warn("Failed to load HUD position: {}", e.getMessage());
            loaded = true;
        }
    }

    public static void save() {
        try {
            Path path = getFilePath();
            Files.createDirectories(path.getParent());
            HudData data = new HudData();
            data.compXPercent = compXPercent;
            data.compYPercent = compYPercent;
            data.wandXPercent = wandXPercent;
            data.wandYPercent = wandYPercent;
            Files.writeString(path, GSON.toJson(data));
        } catch (IOException e) {
            MCAi.LOGGER.warn("Failed to save HUD position: {}", e.getMessage());
        }
    }

    /** Simple POJO for JSON serialization. */
    private static class HudData {
        float compXPercent;
        float compYPercent;
        float wandXPercent = 0.5f;
        float wandYPercent = 0.9f;
    }
}
