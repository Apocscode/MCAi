package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.ai.ConversationManager;
import com.apocscode.mcai.network.ChatMessagePacket;
import com.apocscode.mcai.network.StopInteractingPacket;
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
 * Includes mic button for Whisper voice input.
 */
public class CompanionChatScreen extends Screen {
    private static final int PADDING = 10;
    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 50;
    private static final int MIC_BUTTON_WIDTH = 24;
    private static final int MESSAGE_LINE_HEIGHT = 12;

    private final int entityId;
    private EditBox inputBox;
    private Button sendButton;
    private Button micButton;
    private int scrollOffset = 0;
    private boolean isRecordingVoice = false;
    private long recordingStartTime = 0;
    private String companionName = "MCAi";

    public CompanionChatScreen(int entityId) {
        super(Component.literal("MCAi Chat"));
        this.entityId = entityId;
    }

    @Override
    protected void init() {
        // Resolve companion name from entity
        if (Minecraft.getInstance().level != null) {
            net.minecraft.world.entity.Entity entity =
                    Minecraft.getInstance().level.getEntity(entityId);
            if (entity != null && entity.getCustomName() != null) {
                companionName = entity.getCustomName().getString();
            }
        }

        // Initialize Whisper on first open
        if (!WhisperService.isAvailable()) {
            WhisperService.init();
        }

        int inputY = this.height - PADDING - INPUT_HEIGHT;
        boolean hasMic = WhisperService.isAvailable();
        int micSpace = hasMic ? MIC_BUTTON_WIDTH + 4 : 0;
        int inputWidth = this.width - PADDING * 3 - BUTTON_WIDTH - micSpace;

        // Input field
        inputBox = new EditBox(this.font, PADDING, inputY, inputWidth, INPUT_HEIGHT,
                Component.literal("Type a message..."));
        inputBox.setMaxLength(500);
        inputBox.setFocused(true);
        inputBox.setCanLoseFocus(false);
        this.addRenderableWidget(inputBox);

        // Mic button (only if mic available)
        if (hasMic) {
            micButton = Button.builder(Component.literal("\uD83C\uDF99"), button -> toggleVoiceRecording())
                    .bounds(PADDING + inputWidth + 4, inputY, MIC_BUTTON_WIDTH, INPUT_HEIGHT)
                    .build();
            this.addRenderableWidget(micButton);
        }

        // Send button
        int sendX = hasMic ? PADDING + inputWidth + 4 + MIC_BUTTON_WIDTH + 4 : PADDING * 2 + inputWidth;
        sendButton = Button.builder(Component.literal("Send"), button -> sendMessage())
                .bounds(sendX, inputY, BUTTON_WIDTH, INPUT_HEIGHT)
                .build();
        this.addRenderableWidget(sendButton);
    }

    private void toggleVoiceRecording() {
        if (isRecordingVoice) {
            // Stop recording → transcribe → insert into input
            isRecordingVoice = false;
            if (micButton != null) micButton.setMessage(Component.literal("\uD83C\uDF99"));

            WhisperService.stopRecordingAndTranscribe().thenAccept(text -> {
                if (text != null && !text.isBlank()) {
                    // Must run on render thread to modify UI
                    Minecraft.getInstance().execute(() -> {
                        String current = inputBox.getValue();
                        String newText = current.isEmpty() ? text : current + " " + text;
                        inputBox.setValue(newText);
                        inputBox.moveCursorToEnd(false);
                    });
                }
            });
        } else {
            // Start recording
            WhisperService.startRecording();
            isRecordingVoice = true;
            recordingStartTime = System.currentTimeMillis();
            if (micButton != null) micButton.setMessage(Component.literal("\u23F9")); // Stop icon
        }
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
        graphics.drawCenteredString(this.font, "§b§l" + companionName, this.width / 2, 6, 0xFFFFFF);
        graphics.fill(PADDING, 18, this.width - PADDING, 19, 0xFF3498DB);

        // Recording indicator
        if (isRecordingVoice) {
            long elapsed = (System.currentTimeMillis() - recordingStartTime) / 1000;
            boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
            String recText = (blink ? "§c\u25CF " : "§4\u25CF ") + "§cRecording... " + elapsed + "s";
            graphics.drawCenteredString(this.font, recText, this.width / 2, this.height - PADDING - INPUT_HEIGHT - 16, 0xFF5555);

            // Red glow on mic button
            if (micButton != null) {
                int bx = micButton.getX() - 1;
                int by = micButton.getY() - 1;
                int bw = micButton.getWidth() + 2;
                int bh = micButton.getHeight() + 2;
                graphics.fill(bx, by, bx + bw, by + bh, blink ? 0x44FF0000 : 0x22FF0000);
            }
        }

        // Whisper status hint (bottom-left, subtle)
        if (WhisperService.isAvailable()) {
            graphics.drawString(this.font, "§8[V = push-to-talk]", PADDING, this.height - PADDING - INPUT_HEIGHT - 14, 0x444444, false);
        }

        // Persistent hint (bottom-right, subtle): remind about ! commands
        String hintText = "§8! = commands";
        int hintW = this.font.width(hintText);
        graphics.drawString(this.font, hintText, this.width - PADDING - hintW, this.height - PADDING - INPUT_HEIGHT - 14, 0x444444, false);

        // Chat area
        int chatTop = 24;
        int chatBottom = this.height - PADDING - INPUT_HEIGHT - (isRecordingVoice ? 24 : 8);
        int chatWidth = this.width - PADDING * 2;

        // Draw message history
        List<ConversationManager.ChatMessage> messages = ConversationManager.getMessages();

        // Show help/welcome panel when conversation is empty
        if (messages.isEmpty()) {
            int helpY = chatTop + 16;
            int helpX = PADDING + 12;
            int lineH = 12;

            graphics.drawCenteredString(this.font, "§e§lWelcome to MCAi Chat!", this.width / 2, helpY, 0xFFFF55);
            helpY += lineH + 6;

            graphics.drawString(this.font, "§7Type a message below to talk to your companion.", helpX, helpY, 0xAAAAAA, false);
            helpY += lineH + 8;

            graphics.drawString(this.font, "§f§lQuick Commands §7(prefix with §f!§7):", helpX, helpY, 0xFFFFFF, false);
            helpY += lineH + 2;
            graphics.drawString(this.font, "§a!follow §7- Follow you", helpX + 8, helpY, 0xAAAAAA, false);
            helpY += lineH;
            graphics.drawString(this.font, "§a!stay   §7- Stay in place", helpX + 8, helpY, 0xAAAAAA, false);
            helpY += lineH;
            graphics.drawString(this.font, "§a!auto   §7- Autonomous mode", helpX + 8, helpY, 0xAAAAAA, false);
            helpY += lineH;
            graphics.drawString(this.font, "§a!come   §7- Teleport to you", helpX + 8, helpY, 0xAAAAAA, false);
            helpY += lineH;
            graphics.drawString(this.font, "§a!status §7- Health & task info", helpX + 8, helpY, 0xAAAAAA, false);
            helpY += lineH;
            graphics.drawString(this.font, "§a!cancel §7- Cancel all tasks", helpX + 8, helpY, 0xAAAAAA, false);
            helpY += lineH;
            graphics.drawString(this.font, "§a!equip  §7- Auto-equip best gear", helpX + 8, helpY, 0xAAAAAA, false);
            helpY += lineH;
            graphics.drawString(this.font, "§a!help   §7- Show all commands", helpX + 8, helpY, 0xAAAAAA, false);
            helpY += lineH + 10;

            graphics.drawString(this.font, "§f§lGame Chat §7(press T):", helpX, helpY, 0xFFFFFF, false);
            helpY += lineH + 2;
            graphics.drawString(this.font, "§7Type §f!command§7 in normal chat too!", helpX + 8, helpY, 0xAAAAAA, false);
            helpY += lineH;
            graphics.drawString(this.font, "§7e.g. §f!come§7, §f!follow§7, §f!status", helpX + 8, helpY, 0xAAAAAA, false);
        }

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
                        prefix = "§b[" + companionName + "]§r ";
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
        // V key = push-to-talk (start recording)
        if (keyCode == 86 && !inputBox.isFocused()) { // 86 = V
            if (!isRecordingVoice && WhisperService.isAvailable()) {
                toggleVoiceRecording();
                return true;
            }
        }

        // Enter key sends message
        if (keyCode == 257 || keyCode == 335) { // Enter or numpad enter
            sendMessage();
            return true;
        }
        // Escape closes
        if (keyCode == 256) {
            if (isRecordingVoice) {
                WhisperService.cancelRecording();
                isRecordingVoice = false;
                if (micButton != null) micButton.setMessage(Component.literal("\uD83C\uDF99"));
            }
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        // V key released = stop recording and transcribe
        if (keyCode == 86 && isRecordingVoice) { // 86 = V
            toggleVoiceRecording(); // Stops and transcribes
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
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
    public void onClose() {
        // Cancel any active recording
        if (isRecordingVoice) {
            WhisperService.cancelRecording();
            isRecordingVoice = false;
        }
        // Tell server the owner is done interacting — unfreeze companion
        PacketDistributor.sendToServer(new StopInteractingPacket(entityId));
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause the game
    }
}
