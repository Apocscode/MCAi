package com.apocscode.mcai.client;

import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.inventory.CompanionInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * GUI screen for the companion's inventory.
 *
 * Layout:
 *   Top:    Title (companion name) + [Chat] button
 *   Row 1:  Equipment slots: [Helm] [Chest] [Legs] [Boots]  [Main] [Off]
 *   Rows 2-4: 27-slot general inventory
 *   Rows 5-7: Player inventory
 *   Row 8:    Player hotbar
 */
public class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {

    // Colors matching vanilla inventory style
    private static final int BG_COLOR     = 0xFFC6C6C6;
    private static final int BORDER_LIGHT = 0xFFFFFFFF;
    private static final int BORDER_DARK  = 0xFF555555;
    private static final int SLOT_BG      = 0xFF8B8B8B;
    private static final int SLOT_SHADOW  = 0xFF373737;
    private static final int LABEL_COLOR  = 0x404040;

    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 192;
        this.titleLabelY = 6;
        this.inventoryLabelY = this.imageHeight - 94; // 98 — just above player inv
    }

    @Override
    protected void init() {
        super.init();

        // "Chat" button in top-right corner of the GUI
        this.addRenderableWidget(Button.builder(
                Component.literal("Chat"),
                btn -> {
                    CompanionEntity companion = this.menu.getCompanion();
                    if (companion != null && this.minecraft != null) {
                        this.minecraft.setScreen(new CompanionChatScreen(companion.getId()));
                    }
                })
                .bounds(this.leftPos + this.imageWidth - 52, this.topPos + 4, 44, 14)
                .build());
    }

    // ================================================================
    // Background rendering (vanilla-style programmatic)
    // ================================================================

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos;
        int y = this.topPos;
        int w = this.imageWidth;
        int h = this.imageHeight;

        // Main panel
        renderPanel(g, x, y, w, h);

        // Equipment section label
        g.drawString(this.font, "Equipment", x + 8, y + 8, LABEL_COLOR, false);

        // Separator line between companion inv and player inv
        g.fill(x + 7, y + 100, x + w - 7, y + 101, BORDER_DARK);
        g.fill(x + 7, y + 101, x + w - 7, y + 102, BORDER_LIGHT);

        // Separator between equipment and companion inv
        g.fill(x + 7, y + 38, x + w - 7, y + 39, BORDER_DARK);
        g.fill(x + 7, y + 39, x + w - 7, y + 40, BORDER_LIGHT);

        // Slot backgrounds
        for (Slot slot : this.menu.slots) {
            renderSlotBg(g, x + slot.x, y + slot.y);
        }

        // Equipment slot tiny labels (above the slot row, y=18)
        int labelY = y + 11;
        g.drawString(this.font, "H", x + 12, labelY, 0x606060, false);
        g.drawString(this.font, "C", x + 30, labelY, 0x606060, false);
        g.drawString(this.font, "L", x + 48, labelY, 0x606060, false);
        g.drawString(this.font, "B", x + 66, labelY, 0x606060, false);
        g.drawString(this.font, "W", x + 100, labelY, 0x606060, false);
        g.drawString(this.font, "S", x + 120, labelY, 0x606060, false);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Companion name title (already set via menu.getDisplayName())
        // Draw at titleLabelX, titleLabelY
        g.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, LABEL_COLOR, false);

        // Draw "Items" label above companion inventory section
        g.drawString(this.font, "Items", 8, 34, LABEL_COLOR, false);

        // Player inventory label
        g.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, LABEL_COLOR, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        this.renderTooltip(g, mouseX, mouseY);
    }

    // ================================================================
    // Drawing helpers
    // ================================================================

    /** Draws a vanilla-style raised panel background. */
    private void renderPanel(GuiGraphics g, int x, int y, int w, int h) {
        // Fill
        g.fill(x, y, x + w, y + h, BG_COLOR);
        // Top edge (light)
        g.fill(x, y, x + w - 1, y + 1, BORDER_LIGHT);
        // Left edge (light)
        g.fill(x, y, x + 1, y + h - 1, BORDER_LIGHT);
        // Bottom edge (dark)
        g.fill(x + 1, y + h - 1, x + w, y + h, BORDER_DARK);
        // Right edge (dark)
        g.fill(x + w - 1, y + 1, x + w, y + h, BORDER_DARK);
    }

    /** Draws a vanilla-style inset slot box at the given slot position. */
    private void renderSlotBg(GuiGraphics g, int slotX, int slotY) {
        int x = slotX - 1;
        int y = slotY - 1;
        // Inset shadow (top + left = dark)
        g.fill(x, y, x + 18, y + 1, BORDER_DARK);
        g.fill(x, y + 1, x + 1, y + 17, BORDER_DARK);
        // Inset highlight (bottom + right = light)
        g.fill(x + 1, y + 17, x + 18, y + 18, BORDER_LIGHT);
        g.fill(x + 17, y + 1, x + 18, y + 17, BORDER_LIGHT);
        // Inner area
        g.fill(x + 1, y + 1, x + 17, y + 17, SLOT_BG);
    }
}
