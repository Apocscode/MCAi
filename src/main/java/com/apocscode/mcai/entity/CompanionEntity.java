package com.apocscode.mcai.entity;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.goal.*;
import com.apocscode.mcai.inventory.CompanionInventoryMenu;
import com.apocscode.mcai.item.SoulCrystalItem;
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
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private boolean registeredLiving = false; // Tracks if we've registered in LIVING_COMPANIONS

    // Proactive chat system
    private final CompanionChat chat = new CompanionChat(this);

    // Stuck detection
    private double lastX, lastY, lastZ;
    private int stuckTicks = 0;
    private static final int STUCK_THRESHOLD = 100; // 5 seconds not moving while navigating

    // ================================================================
    // Living companion tracker — prevents duplicate summons
    // ================================================================
    private static final Map<UUID, WeakReference<CompanionEntity>> LIVING_COMPANIONS =
            new ConcurrentHashMap<>();

    /**
     * Register a living companion for the given owner.
     */
    public static void registerLivingCompanion(UUID ownerUUID, CompanionEntity entity) {
        LIVING_COMPANIONS.put(ownerUUID, new WeakReference<>(entity));
    }

    /**
     * Unregister a living companion (on death, removal, or dismissal).
     */
    public static void unregisterLivingCompanion(UUID ownerUUID) {
        LIVING_COMPANIONS.remove(ownerUUID);
    }

    /**
     * Check if the given player already has a living companion in the world.
     * Validates the entity is still alive and cleans stale entries.
     */
    public static boolean hasLivingCompanion(UUID ownerUUID) {
        WeakReference<CompanionEntity> ref = LIVING_COMPANIONS.get(ownerUUID);
        if (ref == null) return false;
        CompanionEntity companion = ref.get();
        if (companion == null || !companion.isAlive() || companion.isRemoved()) {
            LIVING_COMPANIONS.remove(ownerUUID);
            return false;
        }
        return true;
    }

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
        this.goalSelector.addGoal(3, new CompanionCookFoodGoal(this));   // Cook raw food at furnace/campfire
        this.goalSelector.addGoal(4, new CompanionFarmGoal(this));       // Harvest mature crops
        this.goalSelector.addGoal(5, new CompanionFollowGoal(this, 1.2D, 4.0F, 32.0F));
        this.goalSelector.addGoal(6, new CompanionLookAtPlayerGoal(this, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 0.6D));

        // Targeting
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this, Monster.class, true));
        this.targetSelector.addGoal(3, new CompanionHuntFoodGoal(this)); // Hunt food animals when hungry
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
                // Persist companion name to owner's data for respawn
                player.getPersistentData().putString("mcai:companion_name", companionName);
                registerLivingCompanion(ownerUUID, this);
                registeredLiving = true;
                player.sendSystemMessage(Component.literal(
                        "§b[MCAi]§r " + companionName + " is now your companion!"));
                chat.say(CompanionChat.Category.GREETING,
                        "Hey there! I'm ready to help. Right-click me for inventory, Shift+right-click to chat!");
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

        boolean wasHurt = super.hurt(source, amount);

        if (wasHurt && !this.level().isClientSide) {
            float healthPct = this.getHealth() / this.getMaxHealth();
            if (healthPct < 0.25f) {
                chat.warn(CompanionChat.Category.CRITICAL_HEALTH,
                        "§cI'm in critical condition! (" + String.format("%.0f", this.getHealth()) + " HP) I need help!");
            } else if (healthPct < 0.5f) {
                String attacker = source.getEntity() != null ? source.getEntity().getName().getString() : "something";
                chat.warn(CompanionChat.Category.LOW_HEALTH,
                        "Taking heavy damage from " + attacker + "! (" + String.format("%.0f", this.getHealth()) + "/" +
                                String.format("%.0f", this.getMaxHealth()) + " HP)");
            }
        }

        return wasHurt;
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

            // Set respawn cooldown on the owner's soul crystal tracker
            if (ownerUUID != null) {
                SoulCrystalItem.setDeathCooldown(ownerUUID, this.level().getGameTime());
                unregisterLivingCompanion(ownerUUID);
            }

            Player owner = getOwner();
            if (owner != null) {
                long cooldownSec = SoulCrystalItem.RESPAWN_COOLDOWN_TICKS / 20;
                owner.sendSystemMessage(Component.literal(
                        "§c[MCAi]§r " + companionName +
                                " was defeated! Items dropped. Use your §dSoul Crystal§r to resummon in " +
                                cooldownSec + " seconds."));
            }
        }
        super.die(source);
    }

    // ================================================================
    // Entity lifecycle — track living companions
    // ================================================================

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (!this.level().isClientSide && ownerUUID != null) {
            unregisterLivingCompanion(ownerUUID);
        }
        super.remove(reason);
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
        } else {
            // Nothing could fit — inventory full
            chat.warn(CompanionChat.Category.INVENTORY_FULL,
                    "My inventory is full! I can't pick up any more items.");
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
        if (!this.level().isClientSide) {
            // Register as living companion on first server tick (handles chunk reload)
            if (!registeredLiving && ownerUUID != null) {
                registerLivingCompanion(ownerUUID, this);
                registeredLiving = true;
            }

            if (eatCooldown > 0) eatCooldown--;

            // Tick proactive chat cooldowns
            chat.tick();

            // === Stuck detection ===
            if (this.getNavigation().isInProgress()) {
                double dx = this.getX() - lastX;
                double dy = this.getY() - lastY;
                double dz = this.getZ() - lastZ;
                double movedSq = dx * dx + dy * dy + dz * dz;

                if (movedSq < 0.01) { // Barely moved
                    stuckTicks++;
                    if (stuckTicks >= STUCK_THRESHOLD) {
                        chat.warn(CompanionChat.Category.STUCK,
                                "I'm stuck and can't reach my destination. Can you help me?");
                        this.getNavigation().stop();
                        stuckTicks = 0;
                    }
                } else {
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
            lastX = this.getX();
            lastY = this.getY();
            lastZ = this.getZ();

            // === Periodic health warning if hungry and no food ===
            if (this.tickCount % 200 == 0) { // Every 10 sec
                float healthPct = this.getHealth() / this.getMaxHealth();
                if (healthPct < 0.5f && !hasFood()) {
                    chat.warn(CompanionChat.Category.HUNGRY,
                            "I'm low on health and have no food. Could you give me something to eat?");
                }
            }
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

    /**
     * Get the proactive chat system for this companion.
     */
    public CompanionChat getChat() {
        return chat;
    }

    /**
     * Check if companion has any food (cooked or raw) in inventory.
     */
    public boolean hasFood() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.get(DataComponents.FOOD) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if companion has raw/cookable food in inventory.
     */
    public boolean hasRawFood() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && CompanionCookFoodGoal.isRawFood(stack)) {
                return true;
            }
        }
        return false;
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

    /**
     * Set the owner UUID. Used by SoulCrystalItem when summoning.
     */
    public void setOwnerUUID(UUID uuid) {
        this.ownerUUID = uuid;
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
        // Persist name to owner's data so it survives death/respawn
        Player owner = getOwner();
        if (owner != null) {
            owner.getPersistentData().putString("mcai:companion_name", name);
        }
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
