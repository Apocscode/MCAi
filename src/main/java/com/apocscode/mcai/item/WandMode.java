package com.apocscode.mcai.item;

/**
 * All modes for the Logistics Wand.
 *
 * The original TaggedBlock.Role modes (INPUT/OUTPUT/STORAGE) are for tagging containers.
 * HOME_AREA mode is for setting the two corners of the home area bounding box.
 */
public enum WandMode {
    INPUT("Input", 0xFF5599FF, "§9"),
    OUTPUT("Output", 0xFFFF8800, "§6"),
    STORAGE("Storage", 0xFF55FF55, "§a"),
    HOME_AREA("Home Area", 0xFF55FFAA, "§2"),
    CLEAR_HOME("Clear Home", 0xFFFF5555, "§c");

    private final String label;
    private final int color;
    private final String chatColor;

    WandMode(String label, int color, String chatColor) {
        this.label = label;
        this.color = color;
        this.chatColor = chatColor;
    }

    public String getLabel() { return label; }
    public int getColor() { return color; }
    public String getChatColor() { return chatColor; }

    public WandMode next() {
        WandMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public WandMode prev() {
        WandMode[] values = values();
        return values[(this.ordinal() + values.length - 1) % values.length];
    }

    /**
     * Whether this mode tags containers (INPUT/OUTPUT/STORAGE).
     */
    public boolean isContainerMode() {
        return this == INPUT || this == OUTPUT || this == STORAGE;
    }

    /**
     * Convert to TaggedBlock.Role for container tagging.
     * Only valid when isContainerMode() is true.
     */
    public com.apocscode.mcai.logistics.TaggedBlock.Role toRole() {
        return switch (this) {
            case INPUT -> com.apocscode.mcai.logistics.TaggedBlock.Role.INPUT;
            case OUTPUT -> com.apocscode.mcai.logistics.TaggedBlock.Role.OUTPUT;
            case STORAGE -> com.apocscode.mcai.logistics.TaggedBlock.Role.STORAGE;
            default -> throw new IllegalStateException("Not a container mode: " + this);
        };
    }
}
