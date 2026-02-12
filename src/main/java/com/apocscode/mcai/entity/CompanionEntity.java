package com.apocscode.mcai.entity;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.goal.CompanionCombatGoal;
import com.apocscode.mcai.entity.goal.CompanionEatFoodGoal;
import com.apocscode.mcai.entity.goal.CompanionFollowGoal;
import com.apocscode.mcai.entity.goal.CompanionLookAtPlayerGoal;
import com.apocscode.mcai.inventory.CompanionInventoryMenu;
import com.apocscode.mcai.network.OpenChatScreenPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The AI Companion entity — a fully capable player-like mob.
 *
 * Has:
 *   - Full inventory (27 general slots + 4 armor + 1 offhand via equipment)
 *   - Equipment rendering (armor, held weapons)
 *   - Combat AI (melee attack hostile mobs, fight back when hurt)
 *   - Item pickup (auto-equips armor, stores rest)
 *   - Food eating (heals when damaged)
 *   - Takes damage (not invulnerable)
 *   - Drops inventory on death
 *
 * Interaction:
 *   - Right-click = open inventory
 *   - Sneak + right-click = open chat
 *   - Sneak + hit (owner) = dismiss
 */
public class CompanionEntity extends PathfinderMob implements MenuProvider {
    private static final String TAG_OWNER = "OwnerUUID";
    private static final String TAG_NAME = "CompanionName";
    private static final String TAG_INVENTORY = "CompanionInventory";

    public static final int INVENTORY_SIZE = 27;

    @Nullable
    private UUID ownerUUID;
    private String companionName = "MCAi";

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private int eatCooldown = 0;

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        try {
            this.companionName = AiConfig.DEFAULT_COMPANION_NAME.get();
        } catch (Exception ignored) {}
    }

    // ================================================================
    // Attributes — now has attack damage for combat
    // ================================================================

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D)
                .add(Attributes.FOLLOW_RANGE, 48.0D)
                .add(Attributes.ARMOR, 4.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D)
                .add(Attributes.ATTACK_SPEED, 4.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.2D);
    }

    // ================================================================
    // AI Goals — combat, survival, follow, look
    // ================================================================

    @Override
    protected void registerGoals() {
        // Passive / movement
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new CompanionCombatGoal(this, 1.2D, true));
        this.goalSelector.addGoal(2, new CompanionEatFoodGoal(this));
        this.goalSelector.addGoal(3, new CompanionFollowGoal(this, 1.2D, 4.0F, 32.0F));
        this.goalSelector.addGoal(4, new CompanionLookAtPlayerGoal(this, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.6D));

        // Targeting
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this, Monster.class, true));
    }

    // ================================================================
    // Interaction — Right-click = Inventory, Shift+RC = Chat
    // ================================================================

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (ownerUUID == null) {
                ownerUUID = player.getUUID();
                player.sendSystemMessage(Component.literal(
                        "§b[MCAi]§r " + companionName + " is now your companion!"));
            }

            if (player.isShiftKeyDown()) {
                // Shift+right-click → chat
                PacketDistributor.sendToPlayer(serverPlayer,
                        new OpenChatScreenPacket(this.getId()));
            } else {
                // Normal right-click → inventory
                serverPlayer.openMenu(this, buf -> buf.writeInt(this.getId()));
            }
        }

        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    // ================================================================
    // MenuProvider
    // ================================================================

    @Override
    public Component getDisplayName() {
        return Component.literal(companionName);
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new CompanionInventoryMenu(containerId, playerInventory, this);
    }

    // ================================================================
    // Damage / Combat
    // ================================================================

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof Player player) {
            if (player.isShiftKeyDown() && player.getUUID().equals(ownerUUID)) {
                if (!this.level().isClientSide) {
                    player.sendSystemMessage(Component.literal(
                            "§b[MCAi]§r " + companionName + " dismissed."));
                    this.discard();
                }
                return false;
            }
            // Ignore owner's casual hits
            if (player.getUUID().equals(ownerUUID)) return false;
        }

        return super.hurt(source, amount);
    }

    @Override
    public boolean isInvulnerable() {
        return false;
    }

    @Override
    public void die(DamageSource source) {
        if (!this.level().isClientSide) {
            // Drop inventory
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    this.spawnAtLocation(stack);
                    inventory.setItem(i, ItemStack.EMPTY);
                }
            }
            // Drop equipment
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack eq = this.getItemBySlot(slot);
                if (!eq.isEmpty()) {
                    this.spawnAtLocation(eq);
                    this.setItemSlot(slot, ItemStack.EMPTY);
                }
            }

            Player owner = getOwner();
            if (owner != null) {
                owner.sendSystemMessage(Component.literal(
                        "§c[MCAi]§r " + companionName +
                                " was defeated! Their items dropped. Use a spawn egg to resummon."));
            }
        }
        super.die(source);
    }

    // ================================================================
    // Item Pickup
    // ================================================================

    @Override
    public boolean wantsToPickUp(ItemStack stack) {
        return true;
    }

    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();

        // Try auto-equipping armor first
        if (tryAutoEquip(stack)) {
            this.onItemPickup(itemEntity);
            this.take(itemEntity, stack.getCount());
            itemEntity.discard();
            return;
        }

        // Then general inventory
        ItemStack remainder = inventory.addItem(stack.copy());
        if (remainder.isEmpty()) {
            this.onItemPickup(itemEntity);
            this.take(itemEntity, stack.getCount());
            itemEntity.discard();
        } else if (remainder.getCount() < stack.getCount()) {
            int picked = stack.getCount() - remainder.getCount();
            this.take(itemEntity, picked);
            itemEntity.getItem().setCount(remainder.getCount());
        }
    }

    private boolean tryAutoEquip(ItemStack stack) {
        EquipmentSlot slot = getEquipmentSlotForItem(stack);
        if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) return false;

        ItemStack current = this.getItemBySlot(slot);
        if (current.isEmpty()) {
            this.setItemSlot(slot, stack.copy());
            this.setGuaranteedDrop(slot);
            return true;
        }

        if (stack.getItem() instanceof ArmorItem newArmor &&
                current.getItem() instanceof ArmorItem oldArmor) {
            if (newArmor.getDefense() > oldArmor.getDefense()) {
                inventory.addItem(current);
                this.setItemSlot(slot, stack.copy());
                return true;
            }
        }

        return false;
    }

    // ================================================================
    // Food Eating
    // ================================================================

    public boolean tryEatFood() {
        if (this.getHealth() >= this.getMaxHealth()) return false;
        if (eatCooldown > 0) return false;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                FoodProperties props = stack.get(DataComponents.FOOD);
                if (props != null) {
                    this.heal(props.nutrition());
                    this.playSound(SoundEvents.GENERIC_EAT, 1.0F, 1.0F);
                    stack.shrink(1);
                    if (stack.isEmpty()) inventory.setItem(i, ItemStack.EMPTY);
                    eatCooldown = 40;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && eatCooldown > 0) {
            eatCooldown--;
        }
    }

    // ================================================================
    // Weapon equip for combat
    // ================================================================

    public void equipBestWeapon() {
        ItemStack current = this.getItemBySlot(EquipmentSlot.MAINHAND);
        ItemStack bestWeapon = current;
        int bestSlot = -1;
        double bestDamage = getWeaponDamage(current);

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            double dmg = getWeaponDamage(stack);
            if (dmg > bestDamage) {
                bestDamage = dmg;
                bestWeapon = stack;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0) {
            if (!current.isEmpty()) {
                inventory.setItem(bestSlot, current);
            } else {
                inventory.setItem(bestSlot, ItemStack.EMPTY);
            }
            this.setItemSlot(EquipmentSlot.MAINHAND, bestWeapon);
        }
    }

    private double getWeaponDamage(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        // Priority-based ranking for weapon selection
        Item item = stack.getItem();
        if (item instanceof SwordItem) return 10;
        if (item instanceof AxeItem) return 8;
        if (item instanceof TridentItem) return 7;
        if (item instanceof PickaxeItem) return 4;
        if (item instanceof ShovelItem) return 3;
        if (item instanceof HoeItem) return 2;
        return 0;
    }

    // ================================================================
    // Inventory access
    // ================================================================

    public SimpleContainer getCompanionInventory() {
        return inventory;
    }

    // ================================================================
    // Owner management
    // ================================================================

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Nullable
    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    @Nullable
    public Player getOwner() {
        if (ownerUUID == null) return null;
        return this.level().getPlayerByUUID(ownerUUID);
    }

    public String getCompanionName() {
        return companionName;
    }

    public void setCompanionName(String name) {
        this.companionName = name;
        this.setCustomName(Component.literal(name));
    }

    @Override
    public boolean shouldShowName() {
        return true;
    }

    // ================================================================
    // Save / Load
    // ================================================================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerUUID != null) tag.putUUID(TAG_OWNER, ownerUUID);
        tag.putString(TAG_NAME, companionName);

        ListTag invList = new ListTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) i);
                invList.add(stack.save(this.registryAccess(), itemTag));
            }
        }
        tag.put(TAG_INVENTORY, invList);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(TAG_OWNER)) ownerUUID = tag.getUUID(TAG_OWNER);
        if (tag.contains(TAG_NAME)) {
            companionName = tag.getString(TAG_NAME);
            this.setCustomName(Component.literal(companionName));
        }

        if (tag.contains(TAG_INVENTORY)) {
            ListTag invList = tag.getList(TAG_INVENTORY, 10);
            for (int i = 0; i < invList.size(); i++) {
                CompoundTag itemTag = invList.getCompound(i);
                int slot = itemTag.getByte("Slot") & 255;
                if (slot < inventory.getContainerSize()) {
                    inventory.setItem(slot,
                            ItemStack.parse(this.registryAccess(), itemTag)
                                    .orElse(ItemStack.EMPTY));
                }
            }
        }
    }
}
