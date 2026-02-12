package com.apocscode.mcai.inventory;

import com.apocscode.mcai.ModRegistry;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

/**
 * Server-synchronized container menu for the companion's inventory.
 *
 * Layout (from top):
 *   Row 0: [Helmet] [Chest] [Legs] [Boots]  [MainHand] [OffHand]  (6 equipment slots)
 *   Rows 1-3: 27 general inventory slots (9 per row)
 *   Rows 4-6: Player inventory (27 slots)
 *   Row 7: Player hotbar (9 slots)
 */
public class CompanionInventoryMenu extends AbstractContainerMenu {
    private final CompanionEntity companion;
    private final SimpleContainer companionInv;

    // Client-side constructor (from network)
    public CompanionInventoryMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(containerId, playerInventory, resolveEntity(playerInventory, extraData));
    }

    // Server-side constructor
    public CompanionInventoryMenu(int containerId, Inventory playerInventory, CompanionEntity companion) {
        super(ModRegistry.COMPANION_MENU.get(), containerId);
        this.companion = companion;
        this.companionInv = companion != null ? companion.getCompanionInventory()
                : new SimpleContainer(CompanionEntity.INVENTORY_SIZE);

        // === Equipment slots (row 0) ===
        // Armor slots — each only accepts the correct armor type
        addArmorSlot(EquipmentSlot.HEAD, 0, 8, 18);
        addArmorSlot(EquipmentSlot.CHEST, 1, 26, 18);
        addArmorSlot(EquipmentSlot.LEGS, 2, 44, 18);
        addArmorSlot(EquipmentSlot.FEET, 3, 62, 18);

        // Main hand slot
        this.addSlot(new EquipmentEntitySlot(companion, EquipmentSlot.MAINHAND, 4, 98, 18));
        // Off hand slot
        this.addSlot(new EquipmentEntitySlot(companion, EquipmentSlot.OFFHAND, 5, 116, 18));

        // === Companion general inventory (27 slots, 3 rows starting at y=44) ===
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(companionInv, col + row * 9, 8 + col * 18, 44 + row * 18));
            }
        }

        // === Player inventory (3 rows, starting at y=108) ===
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        8 + col * 18, 108 + row * 18));
            }
        }

        // === Player hotbar (y=166) ===
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, 166));
        }
    }

    private void addArmorSlot(EquipmentSlot armorSlot, int index, int x, int y) {
        this.addSlot(new EquipmentEntitySlot(companion, armorSlot, index, x, y));
    }

    private static CompanionEntity resolveEntity(Inventory playerInventory, FriendlyByteBuf buf) {
        int entityId = buf.readInt();
        Entity entity = playerInventory.player.level().getEntity(entityId);
        if (entity instanceof CompanionEntity c) return c;
        return null;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        ItemStack current = slot.getItem();
        ItemStack copy = current.copy();

        int equipEnd = 6;
        int companionEnd = 6 + 27;
        int playerEnd = companionEnd + 27;
        int hotbarEnd = playerEnd + 9;

        if (index < equipEnd) {
            // From equipment → player
            if (!this.moveItemStackTo(current, companionEnd, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index < companionEnd) {
            // From companion inv → player
            if (!this.moveItemStackTo(current, companionEnd, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // From player → try equipment, then companion inv
            boolean moved = false;
            // Try armor slot
            if (current.getItem() instanceof ArmorItem armor) {
                int armorIdx = switch (armor.getEquipmentSlot()) {
                    case HEAD -> 0;
                    case CHEST -> 1;
                    case LEGS -> 2;
                    case FEET -> 3;
                    default -> -1;
                };
                if (armorIdx >= 0 && !this.slots.get(armorIdx).hasItem()) {
                    moved = this.moveItemStackTo(current, armorIdx, armorIdx + 1, false);
                }
            }
            // Try mainhand/offhand for weapons and shields
            if (!moved && current.getItem() instanceof ShieldItem) {
                if (!this.slots.get(5).hasItem()) {
                    moved = this.moveItemStackTo(current, 5, 6, false);
                }
            }
            // Try companion general inventory
            if (!moved) {
                moved = this.moveItemStackTo(current, 6, companionEnd, false);
            }
            if (!moved) return ItemStack.EMPTY;
        }

        if (current.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return companion != null && companion.isAlive() &&
                player.distanceToSqr(companion) < 64.0;
    }

    public CompanionEntity getCompanion() {
        return companion;
    }

    // ================================================================
    // Equipment slot that reads/writes directly to entity armor/hand
    // ================================================================
    private static class EquipmentEntitySlot extends Slot {
        private final CompanionEntity entity;
        private final EquipmentSlot equipmentSlot;

        // We use a 1-slot dummy container; real data comes from entity
        private static final SimpleContainer DUMMY = new SimpleContainer(6);

        public EquipmentEntitySlot(CompanionEntity entity, EquipmentSlot equipmentSlot,
                                    int index, int x, int y) {
            super(DUMMY, index, x, y);
            this.entity = entity;
            this.equipmentSlot = equipmentSlot;
        }

        @Override
        public ItemStack getItem() {
            if (entity == null) return ItemStack.EMPTY;
            return entity.getItemBySlot(equipmentSlot);
        }

        @Override
        public void set(ItemStack stack) {
            if (entity != null) {
                entity.setItemSlot(equipmentSlot, stack);
                this.setChanged();
            }
        }

        @Override
        public ItemStack remove(int amount) {
            ItemStack current = getItem();
            if (current.isEmpty()) return ItemStack.EMPTY;
            ItemStack taken = current.split(amount);
            set(current.isEmpty() ? ItemStack.EMPTY : current);
            return taken;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return switch (equipmentSlot) {
                case HEAD, CHEST, LEGS, FEET -> {
                    if (stack.getItem() instanceof ArmorItem armor) {
                        yield armor.getEquipmentSlot() == equipmentSlot;
                    }
                    yield false;
                }
                case MAINHAND, OFFHAND -> true;
                default -> false;
            };
        }

        @Override
        public int getMaxStackSize() {
            return equipmentSlot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? 1 : 64;
        }

        @Override
        public void setChanged() {
            // Entity equipment is synced automatically
        }
    }
}
