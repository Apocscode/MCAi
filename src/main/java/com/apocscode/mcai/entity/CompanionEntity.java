package com.apocscode.mcai.entity;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.goal.*;
import com.apocscode.mcai.inventory.CompanionInventoryMenu;
import com.apocscode.mcai.item.SoulCrystalItem;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.apocscode.mcai.network.OpenChatScreenPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    // ================================================================
    // Behavior modes — STAY, FOLLOW, AUTO (autonomous)
    // ================================================================
    public enum BehaviorMode {
        STAY,    // Stand in place, don't follow or wander
        FOLLOW,  // Follow the owner (default)
        AUTO     // Autonomous — wander, farm, cook, hunt, pickup independently
    }

    private static final EntityDataAccessor<Integer> DATA_BEHAVIOR_MODE =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);

    private static final String TAG_OWNER = "OwnerUUID";
    private static final String TAG_NAME = "CompanionName";
    private static final String TAG_INVENTORY = "CompanionInventory";
    private static final String TAG_BEHAVIOR_MODE = "BehaviorMode";
    private static final String TAG_HOME_POS = "HomePos";
    private static final String TAG_LOGISTICS = "LogisticsBlocks";
    private static final int MAX_TAGGED_BLOCKS = 32;

    public static final int INVENTORY_SIZE = 27;

    @Nullable
    private UUID ownerUUID;
    private String companionName = "MCAi";

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    private int eatCooldown = 0;
    private boolean registeredLiving = false; // Tracks if we've registered in LIVING_COMPANIONS

    // Home position — set by shift+clicking Soul Crystal
    @Nullable
    private BlockPos homePos;

    // Tagged logistics blocks — containers designated by the Logistics Wand
    private final List<TaggedBlock> taggedBlocks = new ArrayList<>();

    // Proactive chat system
    private final CompanionChat chat = new CompanionChat(this);

    // Task manager — handles queued multi-step tasks from chat AI
    private final com.apocscode.mcai.task.TaskManager taskManager =
            new com.apocscode.mcai.task.TaskManager(this);

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

    /**
     * Get the living companion for the given owner, or null if none exists.
     */
    @Nullable
    public static CompanionEntity getLivingCompanion(UUID ownerUUID) {
        WeakReference<CompanionEntity> ref = LIVING_COMPANIONS.get(ownerUUID);
        if (ref == null) return null;
        CompanionEntity companion = ref.get();
        if (companion == null || !companion.isAlive() || companion.isRemoved()) {
            LIVING_COMPANIONS.remove(ownerUUID);
            return null;
        }
        return companion;
    }

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(true); // Enable item pickup from ground
        try {
            this.companionName = AiConfig.DEFAULT_COMPANION_NAME.get();
        } catch (Exception ignored) {}
        // Sync custom name so the nametag above the entity matches
        this.setCustomName(Component.literal(this.companionName));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BEHAVIOR_MODE, BehaviorMode.FOLLOW.ordinal());
    }

    // ================================================================
    // BehaviorMode getters / setters
    // ================================================================

    public BehaviorMode getBehaviorMode() {
        int ordinal = this.entityData.get(DATA_BEHAVIOR_MODE);
        BehaviorMode[] modes = BehaviorMode.values();
        return (ordinal >= 0 && ordinal < modes.length) ? modes[ordinal] : BehaviorMode.FOLLOW;
    }

    public void setBehaviorMode(BehaviorMode mode) {
        this.entityData.set(DATA_BEHAVIOR_MODE, mode.ordinal());
        if (!this.level().isClientSide) {
            // Stop navigation on mode change
            this.getNavigation().stop();
            Player owner = getOwner();
            if (owner != null) {
                String label = switch (mode) {
                    case STAY -> "§eStay§r — standing in place";
                    case FOLLOW -> "§aFollow§r — following you";
                    case AUTO -> "§bAuto§r — acting autonomously";
                };
                owner.sendSystemMessage(Component.literal("§b[MCAi]§r " + companionName + " mode: " + label));
            }
        }
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
        this.goalSelector.addGoal(5, new CompanionPickupItemGoal(this));   // Actively seek dropped items
        this.goalSelector.addGoal(6, new CompanionFollowGoal(this, 1.2D, 4.0F, 32.0F));
        this.goalSelector.addGoal(7, new CompanionLookAtPlayerGoal(this, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        // Wander only in AUTO mode
        this.goalSelector.addGoal(9, new WaterAvoidingRandomStrollGoal(this, 0.6D) {
            @Override
            public boolean canUse() {
                return getBehaviorMode() == BehaviorMode.AUTO && super.canUse();
            }
        });
        // Automation — task queue and logistics (AUTO mode only)
        this.goalSelector.addGoal(3, new com.apocscode.mcai.entity.goal.CompanionTaskGoal(this));
        this.goalSelector.addGoal(4, new com.apocscode.mcai.entity.goal.CompanionLogisticsGoal(this));

        // Targeting
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(
                this, Monster.class, true));
        this.targetSelector.addGoal(3, new CompanionHuntFoodGoal(this)); // Hunt food animals when hungry
    }

    // ================================================================
    // Interaction — Right-click = Inventory (Chat via GUI button)
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
                        "Hey there! I'm ready to help. Right-click me for inventory, use the Chat button inside!");
            }

            // Always open inventory — chat is accessed via the Chat button in the GUI
            serverPlayer.openMenu(this, buf -> buf.writeInt(this.getId()));
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
                    // Save companion state to owner's persistent data (survives dismiss)
                    saveStateToOwner(player);
                    player.sendSystemMessage(Component.literal(
                            "§b[MCAi]§r " + companionName + " dismissed. Inventory and equipment saved."));
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
                // Sync tagged blocks to owner client
                if (!taggedBlocks.isEmpty()) {
                    syncTaggedBlocksToOwner();
                }
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
     * Check if the companion's inventory has at least one empty slot.
     */
    public boolean hasInventorySpace() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Public wrapper for the protected pickUpItem method, used by CompanionPickupItemGoal.
     */
    public void pickUpNearbyItem(net.minecraft.world.entity.item.ItemEntity itemEntity) {
        this.pickUpItem(itemEntity);
    }

    /**
     * Get the proactive chat system for this companion.
     */
    public CompanionChat getChat() {
        return chat;
    }

    /**
     * Get the task manager for queued multi-step tasks.
     */
    public com.apocscode.mcai.task.TaskManager getTaskManager() {
        return taskManager;
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

    // ================================================================
    // Home position
    // ================================================================

    @Nullable
    public BlockPos getHomePos() {
        return homePos;
    }

    public void setHomePos(@Nullable BlockPos pos) {
        this.homePos = pos;
    }

    public boolean hasHomePos() {
        return homePos != null;
    }

    // ================================================================
    // Logistics — tagged block management
    // ================================================================

    /**
     * Add or update a tagged block. If the position already exists, its role is updated.
     */
    public void addTaggedBlock(BlockPos pos, TaggedBlock.Role role) {
        removeTaggedBlock(pos); // Remove existing entry at same pos
        if (taggedBlocks.size() >= MAX_TAGGED_BLOCKS) return;
        taggedBlocks.add(new TaggedBlock(pos, role));
        syncTaggedBlocksToOwner();
    }

    /**
     * Remove a tagged block by position. Returns true if it was found and removed.
     */
    public boolean removeTaggedBlock(BlockPos pos) {
        boolean removed = taggedBlocks.removeIf(tb -> tb.pos().equals(pos));
        if (removed) syncTaggedBlocksToOwner();
        return removed;
    }

    /**
     * Get all tagged blocks (unmodifiable view).
     */
    public List<TaggedBlock> getTaggedBlocks() {
        return Collections.unmodifiableList(taggedBlocks);
    }

    /**
     * Get tagged blocks filtered by role.
     */
    public List<TaggedBlock> getTaggedBlocks(TaggedBlock.Role role) {
        return taggedBlocks.stream().filter(tb -> tb.role() == role).toList();
    }

    /**
     * Get the tagged block at a specific position, or null if not tagged.
     */
    @Nullable
    public TaggedBlock getTaggedBlockAt(BlockPos pos) {
        return taggedBlocks.stream().filter(tb -> tb.pos().equals(pos)).findFirst().orElse(null);
    }

    /**
     * Get the total number of tagged blocks.
     */
    public int getTaggedBlockCount() {
        return taggedBlocks.size();
    }

    /**
     * Set the full tagged block list (used by sync packet on client).
     */
    public void setTaggedBlocks(List<TaggedBlock> blocks) {
        taggedBlocks.clear();
        taggedBlocks.addAll(blocks);
    }

    /**
     * Sync the tagged block list to the owner client via network packet.
     */
    private void syncTaggedBlocksToOwner() {
        if (this.level().isClientSide) return;
        Player owner = getOwner();
        if (owner instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp,
                    new com.apocscode.mcai.network.SyncTaggedBlocksPacket(
                            this.getId(), taggedBlocks));
        }
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
    // Dismiss persistence — save/restore full state to owner's data
    // ================================================================

    /**
     * Save full companion state (inventory, equipment, health) to the owner's
     * persistent NBT data. Called on dismiss (shift+punch) so state persists.
     */
    public void saveStateToOwner(Player owner) {
        CompoundTag data = new CompoundTag();

        // Inventory
        ListTag invList = new ListTag();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putByte("Slot", (byte) i);
                invList.add(stack.save(this.registryAccess(), itemTag));
            }
        }
        data.put("Inventory", invList);

        // Equipment
        ListTag equipList = new ListTag();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack eq = this.getItemBySlot(slot);
            if (!eq.isEmpty()) {
                CompoundTag eqTag = new CompoundTag();
                eqTag.putString("Slot", slot.getName());
                equipList.add(eq.save(this.registryAccess(), eqTag));
            }
        }
        data.put("Equipment", equipList);

        // Health
        data.putFloat("Health", this.getHealth());

        // Name
        data.putString("Name", companionName);

        owner.getPersistentData().put("mcai:companion_state", data);
        owner.getPersistentData().putString("mcai:companion_name", companionName);
        // Persist home position to owner data
        if (homePos != null) {
            owner.getPersistentData().putLong("mcai:home_pos", homePos.asLong());
        }
        // Persist logistics tagged blocks
        if (!taggedBlocks.isEmpty()) {
            ListTag logList = new ListTag();
            for (TaggedBlock tb : taggedBlocks) {
                logList.add(tb.save());
            }
            owner.getPersistentData().put("mcai:logistics_blocks", logList);
        }
    }

    /**
     * Restore companion state from the owner's persistent data.
     * Called by SoulCrystalItem when resummoning after dismiss.
     */
    public void restoreStateFromOwner(Player owner) {
        CompoundTag data = owner.getPersistentData().getCompound("mcai:companion_state");
        if (data.isEmpty()) return;

        // Inventory
        if (data.contains("Inventory")) {
            ListTag invList = data.getList("Inventory", 10);
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

        // Equipment
        if (data.contains("Equipment")) {
            ListTag equipList = data.getList("Equipment", 10);
            for (int i = 0; i < equipList.size(); i++) {
                CompoundTag eqTag = equipList.getCompound(i);
                String slotName = eqTag.getString("Slot");
                EquipmentSlot slot = EquipmentSlot.byName(slotName);
                ItemStack stack = ItemStack.parse(this.registryAccess(), eqTag)
                        .orElse(ItemStack.EMPTY);
                this.setItemSlot(slot, stack);
            }
        }

        // Health
        if (data.contains("Health")) {
            this.setHealth(data.getFloat("Health"));
        }

        // Name
        if (data.contains("Name")) {
            setCompanionName(data.getString("Name"));
        }

        // Logistics tagged blocks
        if (owner.getPersistentData().contains("mcai:logistics_blocks")) {
            taggedBlocks.clear();
            ListTag logList = owner.getPersistentData().getList("mcai:logistics_blocks", 10);
            for (int i = 0; i < logList.size(); i++) {
                taggedBlocks.add(TaggedBlock.load(logList.getCompound(i)));
            }
            owner.getPersistentData().remove("mcai:logistics_blocks");
        }

        // Clear saved state after restoring (one-time use)
        owner.getPersistentData().remove("mcai:companion_state");
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
        tag.putInt(TAG_BEHAVIOR_MODE, getBehaviorMode().ordinal());
        if (homePos != null) {
            tag.putLong(TAG_HOME_POS, homePos.asLong());
        }
        // Logistics tagged blocks
        if (!taggedBlocks.isEmpty()) {
            ListTag logList = new ListTag();
            for (TaggedBlock tb : taggedBlocks) {
                logList.add(tb.save());
            }
            tag.put(TAG_LOGISTICS, logList);
        }
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

        if (tag.contains(TAG_BEHAVIOR_MODE)) {
            int modeOrd = tag.getInt(TAG_BEHAVIOR_MODE);
            BehaviorMode[] modes = BehaviorMode.values();
            if (modeOrd >= 0 && modeOrd < modes.length) {
                this.entityData.set(DATA_BEHAVIOR_MODE, modeOrd);
            }
        }
        if (tag.contains(TAG_HOME_POS)) {
            homePos = BlockPos.of(tag.getLong(TAG_HOME_POS));
        }
        // Logistics tagged blocks
        if (tag.contains(TAG_LOGISTICS)) {
            taggedBlocks.clear();
            ListTag logList = tag.getList(TAG_LOGISTICS, 10);
            for (int i = 0; i < logList.size(); i++) {
                taggedBlocks.add(TaggedBlock.load(logList.getCompound(i)));
            }
        }
    }
}
