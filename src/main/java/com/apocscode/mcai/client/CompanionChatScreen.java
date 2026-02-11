package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.ConversationManager;
import com.apocscode.mcai.network.ChatMessagePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * Chat screen for interacting with the AI companion.
 * Shows conversation history with a text input at the bottom.
 */
public class CompanionChatScreen extends Screen {
    private static final int PADDING = 10;
    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 50;
    private static final int MESSAGE_LINE_HEIGHT = 12;

    private final int entityId;
    private EditBox inputBox;
    private Button sendButton;
    private int scrollOffset = 0;

    public CompanionChatScreen(int entityId) {
        super(Component.literal("MCAi Chat"));
        this.entityId = entityId;
    }

    @Override
    protected void init() {
        int inputY = this.height - PADDING - INPUT_HEIGHT;
        int inputWidth = this.width - PADDING * 3 - BUTTON_WIDTH;

        // Input field
        inputBox = new EditBox(this.font, PADDING, inputY, inputWidth, INPUT_HEIGHT,
                Component.literal("Type a message..."));
        inputBox.setMaxLength(500);
        inputBox.setFocused(true);
        inputBox.setCanLoseFocus(false);
        this.addRenderableWidget(inputBox);

        // Send button
        sendButton = Button.builder(Component.literal("Send"), button -> sendMessage())
                .bounds(PADDING * 2 + inputWidth, inputY, BUTTON_WIDTH, INPUT_HEIGHT)
                .build();
        this.addRenderableWidget(sendButton);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Semi-transparent dark background
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Title bar
        graphics.drawCenteredString(this.font, "§b§lMCAi Companion", this.width / 2, 6, 0xFFFFFF);
        graphics.fill(PADDING, 18, this.width - PADDING, 19, 0xFF3498DB);

        // Chat area
        int chatTop = 24;
        int chatBottom = this.height - PADDING - INPUT_HEIGHT - 8;
        int chatWidth = this.width - PADDING * 2;

        // Draw message history
        List<ConversationManager.ChatMessage> messages = ConversationManager.getMessages();
        int y = chatBottom;

        // Render from bottom up
        for (int i = messages.size() - 1 - scrollOffset; i >= 0 && y > chatTop; i--) {
            ConversationManager.ChatMessage msg = messages.get(i);

            // Word-wrap the message
            List<net.minecraft.util.FormattedCharSequence> lines =
                    this.font.split(Component.literal(msg.content()), chatWidth - 20);

            // Draw lines bottom-up
            for (int lineIdx = lines.size() - 1; lineIdx >= 0; lineIdx--) {
                y -= MESSAGE_LINE_HEIGHT;
                if (y < chatTop) break;

                int color;
                String prefix = "";
                if (lineIdx == 0) {
                    if (msg.isPlayer()) {
                        prefix = "§a[You]§r ";
                        color = 0xFFAAFFAA;
                    } else if (msg.isSystem()) {
                        color = 0xFFFFAA00;
                        prefix = "";
                    } else {
                        prefix = "§b[MCAi]§r ";
                        color = 0xFFAADDFF;
                    }
                } else {
                    color = msg.isPlayer() ? 0xFFCCFFCC : 0xFFCCEEFF;
                }

                if (lineIdx == 0 && !prefix.isEmpty()) {
                    graphics.drawString(this.font, prefix + msg.content().split("\n")[0],
                            PADDING + 4, y, color, false);
                } else {
                    graphics.drawString(this.font, lines.get(lineIdx),
                            PADDING + 4, y, color, false);
                }
            }

            y -= 4; // Gap between messages
        }

        // Scroll hint
        if (messages.size() > 10) {
            graphics.drawCenteredString(this.font, "§7(scroll with mouse wheel)",
                    this.width / 2, chatTop, 0x888888);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int maxScroll = Math.max(0, ConversationManager.getMessages().size() - 5);
        scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) scrollY));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter key sends message
        if (keyCode == 257 || keyCode == 335) { // Enter or numpad enter
            sendMessage();
            return true;
        }
        // Escape closes
        if (keyCode == 256) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void sendMessage() {
        String text = inputBox.getValue().trim();
        if (text.isEmpty()) return;

        // Add to local conversation
        ConversationManager.addPlayerMessage(text);

        // Send to server for AI processing
        PacketDistributor.sendToServer(new ChatMessagePacket(entityId, text));

        // Clear input
        inputBox.setValue("");
        scrollOffset = 0;

        // Show thinking indicator
        ConversationManager.addSystemMessage("Thinking...");
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause the game
    }
}
