package com.apocscode.mcai.client;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.logistics.TaggedBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * A screen that lets the player drag BOTH the companion HUD panel and
 * the wand HUD panel to any position on screen. Press H to open,
 * drag either panel, Escape to close and save, R to reset both.
 *
 * Shows ghost previews of both HUD panels that follow the mouse while dragging.
 */
public class HudEditScreen extends Screen {

    // Companion panel dimensions — must match CompanionHudOverlay
    private static final int COMP_W = 120;
    private static final int COMP_H = 48;

    // Wand panel dimensions — must match CompanionHudOverlay.renderWandOverlay
    private static final int WAND_W = 140;
    private static final int WAND_H = 22;

    // Companion panel position
    private int compX, compY;
    // Wand panel position
    private int wandX, wandY;

    // Which panel is being dragged? null = none
    private enum DragTarget { COMPANION, WAND }
    private DragTarget dragging = null;
    private int dragOffsetX, dragOffsetY;

    public HudEditScreen() {
        super(Component.literal("Edit HUD Positions"));
    }

    @Override
    protected void init() {
        compX = HudPositionStore.getX(this.width, COMP_W);
        compY = HudPositionStore.getY(this.height, COMP_H);
        wandX = HudPositionStore.getWandX(this.width, WAND_W);
        wandY = HudPositionStore.getWandY(this.height, WAND_H);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0x60000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        Minecraft mc = Minecraft.getInstance();

        // Clamp positions
        compX = Math.max(0, Math.min(compX, this.width - COMP_W));
        compY = Math.max(0, Math.min(compY, this.height - COMP_H));
        wandX = Math.max(0, Math.min(wandX, this.width - WAND_W));
        wandY = Math.max(0, Math.min(wandY, this.height - WAND_H));

        // --- Draw companion HUD preview ---
        drawCompanionPreview(graphics, mc, mouseX, mouseY);

        // --- Draw wand HUD preview ---
        drawWandPreview(graphics, mc, mouseX, mouseY);

        // --- Instructions ---
        String inst1 = "\u00A7e\u00A7lDrag \u00A7reither panel to reposition";
        String inst2 = "\u00A77Press \u00A7fEsc\u00A77 to save | \u00A7fR\u00A77 to reset both";
        graphics.drawCenteredString(mc.font, inst1, this.width / 2, this.height - 30, 0xFFFFFF);
        graphics.drawCenteredString(mc.font, inst2, this.width / 2, this.height - 18, 0xAAAAAA);

        // Title
        graphics.drawCenteredString(mc.font, "\u00A7b\u00A7lEdit HUD Positions", this.width / 2, 6, 0xFFFFFF);
    }

    private void drawCompanionPreview(GuiGraphics graphics, Minecraft mc, int mouseX, int mouseY) {
        int x = compX, y = compY;

        // Border: blue if hovering/dragging, grey otherwise
        boolean hover = dragging == DragTarget.COMPANION || isOverComp(mouseX, mouseY);
        int border = hover ? 0xFF3498DB : 0xFF666666;
        graphics.fill(x - 1, y - 1, x + COMP_W + 1, y + COMP_H + 1, border);

        // Background
        graphics.fill(x, y, x + COMP_W, y + COMP_H, 0xCC000000);

        // Label
        graphics.drawString(mc.font, "\u00A7b[Companion HUD]", x + 3, y + 3, 0xFFFFFF, true);

        // Name
        String name = "MCAi";
        CompanionEntity comp = findCompanion(mc);
        if (comp != null && comp.getCustomName() != null) {
            name = comp.getCustomName().getString();
        }
        graphics.drawString(mc.font, name, x + 3, y + 14, 0xFFFFFF, true);

        // Health bar
        int barX = x + 3, barY = y + 24, barW = COMP_W - 6;
        graphics.fill(barX, barY, barX + barW, barY + 6, 0xFF400000);
        float hp = comp != null ? comp.getHealth() / comp.getMaxHealth() : 0.75f;
        graphics.fill(barX, barY, barX + (int)(barW * hp), barY + 6, 0xFF00CC00);

        // Mode badge
        String modeText = comp != null ? "[" + comp.getBehaviorMode().name() + "]" : "[FOLLOW]";
        graphics.drawString(mc.font, modeText, barX, barY + 9, 0xFFFFAA00, true);

        // Task line
        graphics.drawString(mc.font, "Idle", barX, barY + 19, 0xFFFF55, true);
    }

    private void drawWandPreview(GuiGraphics graphics, Minecraft mc, int mouseX, int mouseY) {
        int x = wandX, y = wandY;

        // Border: orange if hovering/dragging, grey otherwise
        boolean hover = dragging == DragTarget.WAND || isOverWand(mouseX, mouseY);
        int border = hover ? 0xFFFF8800 : 0xFF666666;
        graphics.fill(x - 1, y - 1, x + WAND_W + 1, y + WAND_H + 1, border);

        // Background
        graphics.fill(x, y, x + WAND_W, y + WAND_H, 0xCC000000);

        // Mode text (left)
        String modeText = "Mode: INPUT";
        graphics.drawString(mc.font, modeText, x + 4, y + 3, 0xFF5599FF, true);

        // Tag count (right)
        String tagText = "0 tagged";
        int tagW = mc.font.width(tagText);
        graphics.drawString(mc.font, tagText, x + WAND_W - tagW - 4, y + 3, 0xAAAAAA, true);

        // Mode dots
        int dotY = y + 14, dotX = x + 4;
        TaggedBlock.Role[] roles = TaggedBlock.Role.values();
        for (int i = 0; i < roles.length; i++) {
            int dotColor = (i == 0) ? 0xFF5599FF : 0xFF444444;
            graphics.fill(dotX, dotY, dotX + 8, dotY + 4, dotColor);
            dotX += 12;
        }

        // Label
        int labelW = mc.font.width("[Wand HUD]");
        graphics.drawString(mc.font, "\u00A76[Wand HUD]", x + WAND_W - labelW - 4, y + 12, 0xFFFF8800, true);
    }

    // --- Hit testing ---

    private boolean isOverComp(int mx, int my) {
        return mx >= compX && mx <= compX + COMP_W && my >= compY && my <= compY + COMP_H;
    }

    private boolean isOverWand(int mx, int my) {
        return mx >= wandX && mx <= wandX + WAND_W && my >= wandY && my <= wandY + WAND_H;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int mx = (int) mouseX, my = (int) mouseY;
            // Companion panel takes priority if overlapping
            if (isOverComp(mx, my)) {
                dragging = DragTarget.COMPANION;
                dragOffsetX = mx - compX;
                dragOffsetY = my - compY;
                return true;
            }
            if (isOverWand(mx, my)) {
                dragging = DragTarget.WAND;
                dragOffsetX = mx - wandX;
                dragOffsetY = my - wandY;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging != null) {
            dragging = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging == DragTarget.COMPANION) {
            compX = Math.max(0, Math.min((int) mouseX - dragOffsetX, this.width - COMP_W));
            compY = Math.max(0, Math.min((int) mouseY - dragOffsetY, this.height - COMP_H));
            return true;
        }
        if (dragging == DragTarget.WAND) {
            wandX = Math.max(0, Math.min((int) mouseX - dragOffsetX, this.width - WAND_W));
            wandY = Math.max(0, Math.min((int) mouseY - dragOffsetY, this.height - WAND_H));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // R = reset both to defaults
        if (keyCode == 82) {
            HudPositionStore.reset();
            compX = HudPositionStore.getX(this.width, COMP_W);
            compY = HudPositionStore.getY(this.height, COMP_H);
            wandX = HudPositionStore.getWandX(this.width, WAND_W);
            wandY = HudPositionStore.getWandY(this.height, WAND_H);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        // Save both positions
        HudPositionStore.setPosition(compX, compY, this.width, this.height, COMP_W, COMP_H);
        HudPositionStore.setWandPosition(wandX, wandY, this.width, this.height, WAND_W, WAND_H);
        HudPositionStore.save();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static CompanionEntity findCompanion(Minecraft mc) {
        if (mc.level == null) return null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof CompanionEntity comp) return comp;
        }
        return null;
    }
}
