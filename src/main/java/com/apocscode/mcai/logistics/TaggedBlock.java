package com.apocscode.mcai.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * A block position tagged with a logistics role.
 *
 * Roles:
 *   INPUT   — companion pulls items from here (furnace output, farm chest)
 *   OUTPUT  — companion pushes items to here (sorted destination)
 *   STORAGE — general storage the companion manages and sorts
 */
public record TaggedBlock(BlockPos pos, Role role) {

    public enum Role {
        INPUT("Input", 0x5599FF),    // Blue
        OUTPUT("Output", 0xFF8800),  // Orange
        STORAGE("Storage", 0x55FF55); // Green

        private final String label;
        private final int color;

        Role(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public String getLabel() { return label; }
        public int getColor() { return color; }

        /**
         * Cycle to the next role for mode switching.
         */
        public Role next() {
            Role[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }

    // ================================================================
    // NBT serialization
    // ================================================================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putInt("Role", role.ordinal());
        return tag;
    }

    public static TaggedBlock load(CompoundTag tag) {
        BlockPos pos = BlockPos.of(tag.getLong("Pos"));
        int roleOrd = tag.getInt("Role");
        Role[] roles = Role.values();
        Role role = (roleOrd >= 0 && roleOrd < roles.length) ? roles[roleOrd] : Role.STORAGE;
        return new TaggedBlock(pos, role);
    }
}
