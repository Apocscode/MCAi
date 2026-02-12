package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.item.LogisticsWandItem;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.apocscode.mcai.network.SyncWandModePacket;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * HUD overlay showing companion status:
 * - Name and health bar
 * - Behavior mode (STAY / FOLLOW / AUTO)
 * - Current task status
 *
 * Renders in the top-left corner of the screen.
 */
@EventBusSubscriber(modid = MCAi.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class CompanionHudOverlay {

    private static final ResourceLocation HUD_ID =
            ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "companion_hud");

    @SubscribeEvent
    public static void registerGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HUD_ID, CompanionHudOverlay::render);
        MCAi.LOGGER.info("Registered companion HUD overlay");
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (mc.options.hideGui) return;

        // Find our companion in the client world
        CompanionEntity companion = findCompanion(mc);

        // --- Companion status HUD (top-left) ---
        boolean showCompanionHud;
        try { showCompanionHud = AiConfig.SHOW_COMPANION_HUD.get(); } catch (Exception e) { showCompanionHud = true; }
        if (companion != null && showCompanionHud) {
            renderCompanionStatus(graphics, mc, companion);
        }

        // === Wand mode overlay (bottom-center, like Create goggles) ===
        boolean showWandHud;
        try { showWandHud = AiConfig.SHOW_WAND_HUD.get(); } catch (Exception e) { showWandHud = true; }
        if (showWandHud) {
            renderWandOverlay(graphics, mc);
        }
    }

    private static void renderCompanionStatus(GuiGraphics graphics, Minecraft mc, CompanionEntity companion) {

        int x = 4;
        int y = 4;

        // --- Background panel ---
        int panelWidth = 120;
        int panelHeight = 38;
        String taskStatus = companion.getTaskStatus();
        if (!taskStatus.isEmpty()) {
            panelHeight += 10;
        }
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0x80000000);

        // --- Companion name ---
        String name = companion.getCustomName() != null
                ? companion.getCustomName().getString()
                : "Companion";
        graphics.drawString(mc.font, name, x + 3, y + 3, 0xFFFFFF, true);

        // --- Health bar ---
        float health = companion.getHealth();
        float maxHealth = companion.getMaxHealth();
        float healthPct = Math.clamp(health / maxHealth, 0f, 1f);

        int barX = x + 3;
        int barY = y + 14;
        int barWidth = panelWidth - 6;
        int barHeight = 6;

        // Background (dark red)
        graphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF400000);
        // Health fill (gradient: green→yellow→red based on %)
        int healthColor = getHealthColor(healthPct);
        int fillWidth = (int) (barWidth * healthPct);
        if (fillWidth > 0) {
            graphics.fill(barX, barY, barX + fillWidth, barY + barHeight, healthColor);
        }
        // Health text
        String healthText = String.format("%.0f/%.0f", health, maxHealth);
        graphics.drawString(mc.font, healthText,
                barX + (barWidth - mc.font.width(healthText)) / 2,
                barY - 1, 0xFFFFFF, true);

        // --- Behavior mode badge ---
        int modeY = barY + barHeight + 3;
        CompanionEntity.BehaviorMode mode = companion.getBehaviorMode();
        String modeText = mode.name();
        int modeColor = switch (mode) {
            case STAY -> 0xFF5555FF;   // Blue
            case FOLLOW -> 0xFFFFAA00; // Orange
            case AUTO -> 0xFF55FF55;   // Green
        };
        graphics.drawString(mc.font, "[" + modeText + "]", barX, modeY, modeColor, true);

        // --- Distance ---
        double dist = mc.player.distanceTo(companion);
        String distText = String.format("%.0fm", dist);
        int distWidth = mc.font.width(distText);
        graphics.drawString(mc.font, distText, barX + barWidth - distWidth, modeY, 0xAAAAAA, true);

        // --- Task status (if any) ---
        if (!taskStatus.isEmpty()) {
            int taskY = modeY + 10;
            // Truncate if too long
            if (mc.font.width(taskStatus) > barWidth) {
                while (mc.font.width(taskStatus + "..") > barWidth && taskStatus.length() > 3) {
                    taskStatus = taskStatus.substring(0, taskStatus.length() - 1);
                }
                taskStatus += "..";
            }
            graphics.drawString(mc.font, taskStatus, barX, taskY, 0xFFFF55, true);
        }
    }

    /**
     * Render wand mode + tag count overlay when holding Logistics Wand.
     * Positioned at bottom-center, above the hotbar.
     */
    private static void renderWandOverlay(GuiGraphics graphics, Minecraft mc) {
        if (mc.player == null) return;

        boolean holdingWand = mc.player.getMainHandItem().getItem() instanceof LogisticsWandItem
                || mc.player.getOffhandItem().getItem() instanceof LogisticsWandItem;
        if (!holdingWand) return;

        int modeOrd = SyncWandModePacket.getClientMode();
        TaggedBlock.Role[] roles = TaggedBlock.Role.values();
        TaggedBlock.Role mode = (modeOrd >= 0 && modeOrd < roles.length) ? roles[modeOrd] : TaggedBlock.Role.INPUT;

        // Get tag count from client cache
        int tagCount = LogisticsOutlineRenderer.getClientTagCount();

        String modeLabel = mode.getLabel();
        int modeColor = switch (mode) {
            case INPUT -> 0xFF5599FF;
            case OUTPUT -> 0xFFFF8800;
            case STORAGE -> 0xFF55FF55;
        };

        // Position: bottom-center, above hotbar
        int screenW = graphics.guiWidth();
        int screenH = graphics.guiHeight();
        int panelW = 110;
        int panelH = 22;
        int px = (screenW - panelW) / 2;
        int py = screenH - 58;

        // Background
        graphics.fill(px, py, px + panelW, py + panelH, 0x90000000);

        // Mode label
        String modeText = "Mode: " + modeLabel;
        graphics.drawString(mc.font, modeText, px + 4, py + 3, modeColor, true);

        // Tag count
        String tagText = tagCount + " tagged";
        int tagWidth = mc.font.width(tagText);
        graphics.drawString(mc.font, tagText, px + panelW - tagWidth - 4, py + 3, 0xAAAAAA, true);

        // Mode indicator dots (show all 3, highlight active)
        int dotY = py + 14;
        int dotX = px + 4;
        for (TaggedBlock.Role r : roles) {
            int dotColor = (r == mode) ? modeColor : 0xFF444444;
            graphics.fill(dotX, dotY, dotX + 8, dotY + 4, dotColor);
            dotX += 12;
        }
    }

    private static int getHealthColor(float pct) {
        if (pct > 0.6f) return 0xFF00CC00; // Green
        if (pct > 0.3f) return 0xFFCCCC00; // Yellow
        return 0xFFCC0000; // Red
    }

    /**
     * Find the player's companion entity in the client world.
     * Scans loaded entities (they must be in render distance).
     */
    private static CompanionEntity cachedCompanion = null;
    private static int cacheTimer = 0;

    private static CompanionEntity findCompanion(Minecraft mc) {
        // Cache for 20 ticks to avoid scanning every frame
        if (cachedCompanion != null && !cachedCompanion.isRemoved() && cacheTimer > 0) {
            cacheTimer--;
            return cachedCompanion;
        }

        cachedCompanion = null;
        cacheTimer = 20;

        if (mc.level == null || mc.player == null) return null;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof CompanionEntity comp) {
                // Return the first found companion (in singleplayer, there's usually just one)
                cachedCompanion = comp;
                return comp;
            }
        }
        return null;
    }
}
