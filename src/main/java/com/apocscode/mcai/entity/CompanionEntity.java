package com.apocscode.mcai.entity;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.goal.*;
import com.apocscode.mcai.inventory.CompanionInventoryMenu;
import com.apocscode.mcai.item.SoulCrystalItem;
import com.apocscode.mcai.logistics.ItemRoutingHelper;
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
        AUTO,    // Autonomous — wander, farm, cook, hunt, pickup independently
        GUARD    // Patrol and defend an area from hostile mobs
    }

    private static final EntityDataAccessor<Integer> DATA_BEHAVIOR_MODE =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_TASK_STATUS =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);

    private static final String TAG_OWNER = "OwnerUUID";
    private static final String TAG_NAME = "CompanionName";
    private static final String TAG_INVENTORY = "CompanionInventory";
    private static final String TAG_BEHAVIOR_MODE = "BehaviorMode";
    private static final String TAG_HOME_POS = "HomePos";
    private static final String TAG_HOME_CORNER1 = "HomeCorner1";
    private static final String TAG_HOME_CORNER2 = "HomeCorner2";
    private static final String TAG_LOGISTICS = "LogisticsBlocks";
    private static final int MAX_TAGGED_BLOCKS_DEFAULT = 32;

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

    // Home area corners — two-point bounding box set by Logistics Wand HOME_AREA mode
    @Nullable
    private BlockPos homeCorner1;
    @Nullable
    private BlockPos homeCorner2;

    // Guard position — center of patrol area in GUARD mode
    @Nullable
    private BlockPos guardPos;

    // Tagged logistics blocks — containers designated by the Logistics Wand
    private final List<TaggedBlock> taggedBlocks = new ArrayList<>();

    // Proactive chat system
    private final CompanionChat chat = new CompanionChat(this);

    // Task manager — handles queued multi-step tasks from chat AI
    private final com.apocscode.mcai.task.TaskManager taskManager =
            new com.apocscode.mcai.task.TaskManager(this);

    // Memory system — persistent facts and events
    private final com.apocscode.mcai.ai.CompanionMemory memory = new com.apocscode.mcai.ai.CompanionMemory();

    // Leveling system — XP and stat bonuses
    private final CompanionLevelSystem levelSystem = new CompanionLevelSystem();

    // Owner interaction freeze — companion stops moving while owner has UI open
    private boolean ownerInteracting = false;
    private long interactionStartTick = 0;
    private static final int INTERACTION_TIMEOUT_TICKS = 6000; // 5 minutes safety timeout

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

        // === Pathfinding safety — avoid hazardous blocks ===
        // Without these, the companion walks into lava, fire, and off cliffs
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.LAVA, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGE_FIRE, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DAMAGE_OTHER, -1.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DANGER_FIRE, 8.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.DANGER_OTHER, 8.0F);
        this.setPathfindingMalus(net.minecraft.world.level.pathfinder.PathType.WATER, 4.0F);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BEHAVIOR_MODE, BehaviorMode.FOLLOW.ordinal());
        builder.define(DATA_TASK_STATUS, "");
    }

    /**
     * Get task status text (synced to client via entity data).
     */
    public String getTaskStatus() {
        return this.entityData.get(DATA_TASK_STATUS);
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
                    case GUARD -> "§6Guard§r — patrolling and defending area";
                };
                owner.sendSystemMessage(Component.literal("§b[MCAi]§r " + companionName + " mode: " + label));
            }
        }
    }

    // ================================================================
    // Owner interaction freeze — stops movement while player has UI open
    // ================================================================

    /**
     * Set whether the owner is currently interacting with this companion
     * (inventory or chat screen open). When true, the companion freezes movement.
     */
    public void setOwnerInteracting(boolean interacting) {
        this.ownerInteracting = interacting;
        if (interacting) {
            this.interactionStartTick = this.tickCount;
            this.getNavigation().stop();
        }
    }

    /**
     * Returns true if the owner is interacting with the companion (UI open).
     * Includes safety timeout and distance check to auto-clear stale flags.
     */
    public boolean isOwnerInteracting() {
        if (!ownerInteracting) return false;
        // Safety timeout
        if (this.tickCount - interactionStartTick > INTERACTION_TIMEOUT_TICKS) {
            ownerInteracting = false;
            return false;
        }
        // Auto-clear if owner moved far away (disconnected, teleported, etc.)
        Player owner = getOwner();
        if (owner == null || !owner.isAlive() || this.distanceToSqr(owner) > 1024) { // 32 blocks
            ownerInteracting = false;
            return false;
        }
        return true;
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
        this.goalSelector.addGoal(2, new CompanionFetchFoodGoal(this));   // Fetch food from chests when hungry
        this.goalSelector.addGoal(3, new CompanionCookFoodGoal(this));   // Cook raw food at furnace/campfire
        this.goalSelector.addGoal(4, new CompanionFarmGoal(this));       // Harvest mature crops
        this.goalSelector.addGoal(5, new CompanionPickupItemGoal(this));   // Actively seek dropped items
        this.goalSelector.addGoal(6, new CompanionFollowGoal(this, 1.2D, 4.0F));
        this.goalSelector.addGoal(7, new CompanionLookAtPlayerGoal(this, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        // Wander only in AUTO mode, not while owner is interacting
        this.goalSelector.addGoal(9, new WaterAvoidingRandomStrollGoal(this, 0.6D) {
            @Override
            public boolean canUse() {
                return !isOwnerInteracting() && getBehaviorMode() == BehaviorMode.AUTO && super.canUse();
            }
        });
        // Guard mode — patrol and defend area
        this.goalSelector.addGoal(3, new CompanionGuardGoal(this));
        // Task queue — AI-requested tasks get high priority (player explicitly asked)
        this.goalSelector.addGoal(2, new com.apocscode.mcai.entity.goal.CompanionTaskGoal(this));
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

            // Freeze companion movement while owner has UI open
            setOwnerInteracting(true);

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
            // Save inventory + equipment to NBT for respawn recovery
            net.minecraft.nbt.CompoundTag deathData = new net.minecraft.nbt.CompoundTag();

            // Save inventory
            net.minecraft.nbt.ListTag invTag = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    net.minecraft.nbt.CompoundTag slotTag = new net.minecraft.nbt.CompoundTag();
                    slotTag.putByte("Slot", (byte) i);
                    invTag.add(stack.save(this.registryAccess(), slotTag));
                }
                inventory.setItem(i, ItemStack.EMPTY);
            }
            deathData.put("Inventory", invTag);

            // Save equipment
            net.minecraft.nbt.ListTag eqTag = new net.minecraft.nbt.ListTag();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack eq = this.getItemBySlot(slot);
                if (!eq.isEmpty()) {
                    net.minecraft.nbt.CompoundTag slotTag = new net.minecraft.nbt.CompoundTag();
                    slotTag.putString("Slot", slot.getName());
                    eqTag.add(eq.save(this.registryAccess(), slotTag));
                    this.setItemSlot(slot, ItemStack.EMPTY);
                }
            }
            deathData.put("Equipment", eqTag);

            // Save companion name
            deathData.putString("Name", companionName);

            // Store in SoulCrystal death data cache for recovery on respawn
            if (ownerUUID != null) {
                SoulCrystalItem.setDeathData(ownerUUID, deathData);
                SoulCrystalItem.setDeathCooldown(ownerUUID, this.level().getGameTime());
                unregisterLivingCompanion(ownerUUID);
            }

            Player owner = getOwner();
            if (owner != null) {
                long cooldownSec = SoulCrystalItem.RESPAWN_COOLDOWN_TICKS / 20;
                owner.sendSystemMessage(Component.literal(
                        "§c[MCAi]§r " + companionName +
                                " was defeated! Inventory preserved. Use your §dSoul Crystal§r to resummon in " +
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

        // Try routing directly to tagged storage (OUTPUT > STORAGE)
        // Auto-create a chest if no storage exists and companion has materials
        if (!ItemRoutingHelper.hasTaggedStorage(this)) {
            ItemRoutingHelper.ensureStorageAvailable(this);
        }
        if (ItemRoutingHelper.hasTaggedStorage(this)) {
            ItemStack toRoute = stack.copy();
            int before = toRoute.getCount();
            ItemRoutingHelper.tryInsertIntoTagged(this.level(), this, toRoute, TaggedBlock.Role.OUTPUT);
            if (!toRoute.isEmpty()) {
                ItemRoutingHelper.tryInsertIntoTagged(this.level(), this, toRoute, TaggedBlock.Role.STORAGE);
            }
            int routed = before - toRoute.getCount();
            if (routed > 0) {
                MCAi.LOGGER.debug("Auto-routed {}x {} to tagged storage on pickup",
                        routed, stack.getItem().getDescription().getString());
            }
            // If everything was routed, we're done
            if (toRoute.isEmpty()) {
                this.onItemPickup(itemEntity);
                this.take(itemEntity, stack.getCount());
                itemEntity.discard();
                return;
            }
            // Otherwise, try to put the remainder in companion inventory
            stack = toRoute;
        }

        // Fallback: companion inventory
        ItemStack remainder = inventory.addItem(stack.copy());
        if (remainder.isEmpty()) {
            this.onItemPickup(itemEntity);
            this.take(itemEntity, itemEntity.getItem().getCount());
            itemEntity.discard();
        } else if (remainder.getCount() < stack.getCount()) {
            int picked = itemEntity.getItem().getCount() - remainder.getCount();
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
            // Freeze all movement if owner is interacting with companion UI
            // BUT allow navigation when tasks are active (player sent a command via chat)
            if (isOwnerInteracting() && taskManager.isIdle()) {
                this.getNavigation().stop();
            }

            // Register as living companion on first server tick (handles chunk reload)
            if (!registeredLiving && ownerUUID != null) {
                registerLivingCompanion(ownerUUID, this);
                registeredLiving = true;
                // Sync tagged blocks to owner client
                if (!taggedBlocks.isEmpty()) {
                    syncTaggedBlocksToOwner();
                }
                // Sync home area to owner client
                if (hasHomeArea()) {
                    syncHomeAreaToOwner();
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
                    // Jump assist — try jumping when half-stuck
                    if (stuckTicks == STUCK_THRESHOLD / 2 && this.onGround()) {
                        this.setDeltaMovement(this.getDeltaMovement().add(0, 0.42, 0));
                    }
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

            // === Sync task status to client every 20 ticks ===
            if (this.tickCount % 20 == 0) {
                String status = taskManager.isIdle() ? "" : taskManager.getStatusSummary();
                if (!status.equals(this.entityData.get(DATA_TASK_STATUS))) {
                    this.entityData.set(DATA_TASK_STATUS, status);
                }
            }

            // === Auto-equip best gear periodically ===
            int equipInterval;
            try { equipInterval = AiConfig.AUTO_EQUIP_INTERVAL.get(); } catch (Exception e) { equipInterval = 100; }
            if (this.tickCount % equipInterval == equipInterval / 2) {
                autoEquipBestGear();
            }

            // === Leash teleport — emergency failsafe ===
            // Only teleports when companion is truly idle:
            //   - No active task (mining, building, etc.)
            //   - No active need (hungry, in combat, on fire, etc.)
            // Player must use the whistle (G key) to recall during tasks/needs.
            if (this.tickCount % 40 == 0 && getBehaviorMode() != BehaviorMode.STAY) {
                Player leashOwner = getOwner();
                double leashDist;
                try { leashDist = AiConfig.LEASH_DISTANCE.get(); } catch (Exception e) { leashDist = 48.0; }
                if (leashOwner != null && this.distanceTo(leashOwner) > leashDist
                        && taskManager.isIdle() && !hasActiveNeed()) {
                    // Find safe ground near the owner instead of blind teleport
                    BlockPos ownerPos = leashOwner.blockPosition();
                    BlockPos safePos = findSafeTeleportPos(ownerPos, 3);
                    if (safePos != null) {
                        this.moveTo(safePos.getX() + 0.5, safePos.getY(),
                                safePos.getZ() + 0.5, this.getYRot(), this.getXRot());
                    } else {
                        this.moveTo(leashOwner.getX(), leashOwner.getY(), leashOwner.getZ(),
                                this.getYRot(), this.getXRot());
                    }
                    this.getNavigation().stop();
                }
            }

            // === Periodic health warning if hungry and no food ===
            if (this.tickCount % 200 == 0) { // Every 10 sec
                float healthPct = this.getHealth() / this.getMaxHealth();
                if (healthPct < 0.5f && !hasFood()) {
                    chat.warn(CompanionChat.Category.HUNGRY,
                            "I'm low on health and have no food. Could you give me something to eat?");
                }
            }

            // === HAZARD MONITOR — detect and respond to environmental dangers ===
            if (this.tickCount % 10 == 0) { // Every 0.5 seconds
                hazardCheck();
            }
        }
    }

    /**
     * Check if the companion has an active "need" that should prevent leash teleporting.
     * Needs include: low health + seeking food, active combat target, on fire/in lava.
     */
    private boolean hasActiveNeed() {
        // In combat
        if (this.getTarget() != null && this.getTarget().isAlive()) return true;

        // On fire or in lava — hazardCheck handles this, don't teleport on top of it
        if (this.isOnFire() || this.isInLava()) return true;

        // Hungry — health below 75% means companion may be eating or seeking food
        float healthPct = this.getHealth() / this.getMaxHealth();
        if (healthPct < 0.75f) return true;

        return false;
    }

    /**
     * Find a safe teleport position near a target BlockPos.
     * Searches a small area around the target, checking both horizontally and vertically
     * for positions where isSafeToStand is true.
     *
     * @param target         The target position (e.g., owner's feet)
     * @param horizontalRange How many blocks around the target to check
     * @return A safe BlockPos, or null if none found
     */
    @Nullable
    private BlockPos findSafeTeleportPos(BlockPos target, int horizontalRange) {
        // Check target first
        if (com.apocscode.mcai.task.BlockHelper.isSafeToStand(this.level(), target)) return target;

        for (int dx = -horizontalRange; dx <= horizontalRange; dx++) {
            for (int dz = -horizontalRange; dz <= horizontalRange; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos candidate = target.offset(dx, 0, dz);
                // Search vertically (down 4, up 4)
                for (int dy = 0; dy <= 4; dy++) {
                    if (com.apocscode.mcai.task.BlockHelper.isSafeToStand(this.level(), candidate.below(dy))) {
                        return candidate.below(dy);
                    }
                    if (dy > 0 && com.apocscode.mcai.task.BlockHelper.isSafeToStand(this.level(), candidate.above(dy))) {
                        return candidate.above(dy);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Environmental hazard detection and emergency response.
     * Checks for lava, fire, void, and suffocation every 0.5 seconds.
     */
    private void hazardCheck() {
        // --- VOID: Emergency teleport if below world minimum ---
        if (this.getY() < this.level().getMinBuildHeight() + 2) {
            Player owner = getOwner();
            if (owner != null) {
                this.moveTo(owner.getX(), owner.getY(), owner.getZ(), this.getYRot(), this.getXRot());
                this.getNavigation().stop();
                taskManager.cancelAll();
                chat.warn(CompanionChat.Category.STUCK,
                        "I almost fell into the void! Teleported back to you.");
                MCAi.LOGGER.warn("Companion void rescue — teleported to owner from Y={}", (int)this.getY());
            }
            return;
        }

        // --- LAVA / FIRE: Cancel tasks and flee to owner ---
        if (this.isOnFire() || this.isInLava()) {
            // Stop whatever we're doing
            this.getNavigation().stop();
            taskManager.cancelAll();

            // Try to flee to owner
            Player owner = getOwner();
            if (owner != null && this.distanceTo(owner) < 64) {
                this.getNavigation().moveTo(owner.getX(), owner.getY(), owner.getZ(), 1.5D);
            }

            // Warn (rate-limited by chat system)
            if (this.isInLava()) {
                chat.warn(CompanionChat.Category.STUCK,
                        "I'm in lava! Trying to get out!");
            } else {
                chat.warn(CompanionChat.Category.STUCK,
                        "I'm on fire!");
            }
            return;
        }

        // --- SUFFOCATION: Detect if trapped inside solid blocks ---
        BlockPos headPos = this.blockPosition().above();
        if (this.level().getBlockState(headPos).isSuffocating(this.level(), headPos)
                && this.level().getBlockState(this.blockPosition()).isSuffocating(this.level(), this.blockPosition())) {
            // Trapped in solid blocks — mine our way out
            com.apocscode.mcai.task.BlockHelper.breakBlock(this, headPos);
            com.apocscode.mcai.task.BlockHelper.breakBlock(this, this.blockPosition());
            MCAi.LOGGER.warn("Companion suffocation rescue — broke blocks at {} and {}", this.blockPosition(), headPos);
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
        // Use real damage values from item attributes
        Item item = stack.getItem();
        if (item instanceof SwordItem sword) return 10 + sword.getTier().getAttackDamageBonus();
        if (item instanceof AxeItem) return 8 + ((DiggerItem)item).getTier().getAttackDamageBonus();
        if (item instanceof TridentItem) return 9;
        if (item instanceof PickaxeItem) return 4 + ((DiggerItem)item).getTier().getAttackDamageBonus();
        if (item instanceof ShovelItem) return 3 + ((DiggerItem)item).getTier().getAttackDamageBonus();
        if (item instanceof HoeItem) return 1;
        return 0;
    }

    /**
     * Get the best pickaxe from inventory for mining tasks.
     * Returns the equipped pickaxe or swaps to the best one available.
     */
    public void equipBestPickaxe() {
        ItemStack current = this.getItemBySlot(EquipmentSlot.MAINHAND);
        ItemStack bestPick = null;
        int bestSlot = -1;
        float bestTier = current.getItem() instanceof PickaxeItem p ? p.getTier().getSpeed() : -1;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof PickaxeItem pick) {
                float tier = pick.getTier().getSpeed();
                if (tier > bestTier) {
                    bestTier = tier;
                    bestPick = stack;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot >= 0 && bestPick != null) {
            if (!current.isEmpty()) {
                inventory.setItem(bestSlot, current);
            } else {
                inventory.setItem(bestSlot, ItemStack.EMPTY);
            }
            this.setItemSlot(EquipmentSlot.MAINHAND, bestPick);
        }
    }

    /**
     * Get the best axe from inventory for chopping tasks.
     */
    public void equipBestAxe() {
        ItemStack current = this.getItemBySlot(EquipmentSlot.MAINHAND);
        ItemStack bestAxe = null;
        int bestSlot = -1;
        float bestTier = current.getItem() instanceof AxeItem a ? ((DiggerItem)a).getTier().getSpeed() : -1;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof AxeItem axe) {
                float tier = ((DiggerItem)axe).getTier().getSpeed();
                if (tier > bestTier) {
                    bestTier = tier;
                    bestAxe = stack;
                    bestSlot = i;
                }
            }
        }

        if (bestSlot >= 0 && bestAxe != null) {
            if (!current.isEmpty()) {
                inventory.setItem(bestSlot, current);
            } else {
                inventory.setItem(bestSlot, ItemStack.EMPTY);
            }
            this.setItemSlot(EquipmentSlot.MAINHAND, bestAxe);
        }
    }

    /**
     * Equip the best tool from inventory for a specific block.
     * Checks all inventory slots and picks the tool that mines the given block fastest.
     * This handles pickaxe/axe/shovel/hoe selection automatically based on the block type.
     *
     * @param state The block state to be mined
     */
    public void equipBestToolForBlock(net.minecraft.world.level.block.state.BlockState state) {
        ItemStack current = this.getItemBySlot(EquipmentSlot.MAINHAND);
        float currentSpeed = current.getDestroySpeed(state);

        ItemStack bestTool = null;
        int bestSlot = -1;
        float bestSpeed = currentSpeed;

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestTool = stack;
                bestSlot = i;
            }
        }

        if (bestSlot >= 0 && bestTool != null) {
            // Swap current mainhand with the better tool
            if (!current.isEmpty()) {
                inventory.setItem(bestSlot, current);
            } else {
                inventory.setItem(bestSlot, ItemStack.EMPTY);
            }
            this.setItemSlot(EquipmentSlot.MAINHAND, bestTool);
        }
    }

    /**
     * Check if the companion has a tool that can properly harvest a block (get drops).
     * For example, iron ore requires at least a stone pickaxe.
     *
     * @param state The block state to check
     * @return true if the companion has a tool that grants drops from this block
     */
    public boolean canHarvestBlock(net.minecraft.world.level.block.state.BlockState state) {
        // Check mainhand first
        ItemStack mainHand = this.getItemBySlot(EquipmentSlot.MAINHAND);
        if (mainHand.isCorrectToolForDrops(state)) return true;

        // Check inventory
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    /**
     * Auto-equip the best armor from the companion's inventory.
     * Scans all inventory slots for armor items and equips the highest-defense piece
     * in each slot (head, chest, legs, feet). Old armor goes back to inventory.
     */
    public void equipBestArmor() {
        EquipmentSlot[] armorSlots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        };

        for (EquipmentSlot slot : armorSlots) {
            ItemStack currentArmor = this.getItemBySlot(slot);
            int currentDefense = getArmorDefense(currentArmor, slot);
            int bestSlotIdx = -1;
            int bestDefense = currentDefense;

            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof ArmorItem armor
                        && armor.getEquipmentSlot() == slot) {
                    int defense = getArmorDefense(stack, slot);
                    if (defense > bestDefense) {
                        bestDefense = defense;
                        bestSlotIdx = i;
                    }
                }
            }

            if (bestSlotIdx >= 0) {
                ItemStack newArmor = inventory.getItem(bestSlotIdx);
                if (!currentArmor.isEmpty()) {
                    inventory.setItem(bestSlotIdx, currentArmor);
                } else {
                    inventory.setItem(bestSlotIdx, ItemStack.EMPTY);
                }
                this.setItemSlot(slot, newArmor);
            }
        }
    }

    private int getArmorDefense(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armor)) return 0;
        if (armor.getEquipmentSlot() != slot) return 0;
        // Use material tier as a simple ranking
        return armor.getDefense();
    }

    /**
     * Auto-equip best weapon AND best armor from inventory.
     * Called periodically or after inventory changes.
     */
    public void autoEquipBestGear() {
        equipBestWeapon();
        equipBestArmor();
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

    /** Get the persistent memory system. */
    public com.apocscode.mcai.ai.CompanionMemory getMemory() {
        return memory;
    }

    /** Get the leveling system. */
    public CompanionLevelSystem getLevelSystem() {
        return levelSystem;
    }

    /**
     * Award XP to the companion and handle level-up effects.
     */
    public void awardXp(int xp) {
        if (levelSystem.addXp(xp)) {
            // Level up!
            levelSystem.applyBonuses(this);
            memory.addEvent("Leveled up to " + levelSystem.getLevel() + "!");
            Player owner = getOwner();
            if (owner != null) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§b[MCAi]§r §6" + companionName + " leveled up to " + levelSystem.getLevel() + "!§r" +
                                " (+" + (int) levelSystem.getBonusMaxHealth() + " HP, +" +
                                String.format("%.1f", levelSystem.getBonusDamage()) + " ATK)"));
            }
            // Play level up sound
            this.playSound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.0F, 1.0F);
        }
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
    // Home position & Home Area
    // ================================================================

    @Nullable
    public BlockPos getHomePos() {
        // If we have a home area, return its center; otherwise the legacy single point
        if (homeCorner1 != null && homeCorner2 != null) {
            return new BlockPos(
                    (homeCorner1.getX() + homeCorner2.getX()) / 2,
                    (homeCorner1.getY() + homeCorner2.getY()) / 2,
                    (homeCorner1.getZ() + homeCorner2.getZ()) / 2);
        }
        return homePos;
    }

    public void setHomePos(@Nullable BlockPos pos) {
        this.homePos = pos;
    }

    public boolean hasHomePos() {
        return homePos != null || (homeCorner1 != null && homeCorner2 != null);
    }

    // --- Home area (two-corner bounding box) ---

    @Nullable
    public BlockPos getHomeCorner1() {
        return homeCorner1;
    }

    @Nullable
    public BlockPos getHomeCorner2() {
        return homeCorner2;
    }

    public void setHomeCorner1(@Nullable BlockPos pos) {
        this.homeCorner1 = pos;
    }

    public void setHomeCorner2(@Nullable BlockPos pos) {
        this.homeCorner2 = pos;
        // When both corners are set, update legacy homePos to center
        if (this.homeCorner1 != null && pos != null) {
            this.homePos = getHomePos(); // cache center
        }
    }

    public boolean hasHomeArea() {
        return homeCorner1 != null && homeCorner2 != null;
    }

    /**
     * Check if a position is inside the home area bounding box.
     * Falls back to distance check from homePos if no area is defined.
     */
    public boolean isInHomeArea(BlockPos pos) {
        if (homeCorner1 != null && homeCorner2 != null) {
            int minX = Math.min(homeCorner1.getX(), homeCorner2.getX());
            int minY = Math.min(homeCorner1.getY(), homeCorner2.getY());
            int minZ = Math.min(homeCorner1.getZ(), homeCorner2.getZ());
            int maxX = Math.max(homeCorner1.getX(), homeCorner2.getX());
            int maxY = Math.max(homeCorner1.getY(), homeCorner2.getY());
            int maxZ = Math.max(homeCorner1.getZ(), homeCorner2.getZ());
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
        // Legacy fallback: 20-block manhattan distance from single home point
        if (homePos != null) {
            return homePos.distManhattan(pos) <= 20;
        }
        return false;
    }

    // ================================================================
    // Guard position
    // ================================================================

    @Nullable
    public BlockPos getGuardPos() {
        return guardPos;
    }

    public void setGuardPos(@Nullable BlockPos pos) {
        this.guardPos = pos;
    }

    // ================================================================
    // Logistics — tagged block management
    // ================================================================

    /**
     * Add or update a tagged block. If the position already exists, its role is updated.
     */
    public void addTaggedBlock(BlockPos pos, TaggedBlock.Role role) {
        removeTaggedBlock(pos); // Remove existing entry at same pos
        int maxBlocks;
        try { maxBlocks = AiConfig.MAX_TAGGED_BLOCKS.get(); } catch (Exception e) { maxBlocks = MAX_TAGGED_BLOCKS_DEFAULT; }
        if (taggedBlocks.size() >= maxBlocks) return;
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

    /**
     * Sync the home area corners to the owner client for outline rendering.
     */
    public void syncHomeAreaToOwner() {
        if (this.level().isClientSide) return;
        Player owner = getOwner();
        if (owner instanceof net.minecraft.server.level.ServerPlayer sp) {
            if (homeCorner1 != null && homeCorner2 != null) {
                PacketDistributor.sendToPlayer(sp,
                        new com.apocscode.mcai.network.SyncHomeAreaPacket(
                                homeCorner1.asLong(), homeCorner2.asLong()));
            } else {
                PacketDistributor.sendToPlayer(sp,
                        new com.apocscode.mcai.network.SyncHomeAreaPacket(0L, 0L));
            }
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
        // Persist home area corners
        if (homeCorner1 != null) {
            owner.getPersistentData().putLong("mcai:home_corner1", homeCorner1.asLong());
        }
        if (homeCorner2 != null) {
            owner.getPersistentData().putLong("mcai:home_corner2", homeCorner2.asLong());
        }
        // Persist logistics tagged blocks
        if (!taggedBlocks.isEmpty()) {
            ListTag logList = new ListTag();
            for (TaggedBlock tb : taggedBlocks) {
                logList.add(tb.save());
            }
            owner.getPersistentData().put("mcai:logistics_blocks", logList);
        }
        // Persist guard position
        if (guardPos != null) {
            owner.getPersistentData().putLong("mcai:guard_pos", guardPos.asLong());
        }
        // Persist memory and level
        owner.getPersistentData().put("mcai:memory", memory.save());
        owner.getPersistentData().put("mcai:level_system", levelSystem.save());
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

        // Guard position
        if (owner.getPersistentData().contains("mcai:guard_pos")) {
            guardPos = BlockPos.of(owner.getPersistentData().getLong("mcai:guard_pos"));
            owner.getPersistentData().remove("mcai:guard_pos");
        }

        // Home area corners
        if (owner.getPersistentData().contains("mcai:home_corner1")) {
            homeCorner1 = BlockPos.of(owner.getPersistentData().getLong("mcai:home_corner1"));
            owner.getPersistentData().remove("mcai:home_corner1");
        }
        if (owner.getPersistentData().contains("mcai:home_corner2")) {
            homeCorner2 = BlockPos.of(owner.getPersistentData().getLong("mcai:home_corner2"));
            owner.getPersistentData().remove("mcai:home_corner2");
        }

        // Memory
        if (owner.getPersistentData().contains("mcai:memory")) {
            memory.load(owner.getPersistentData().getCompound("mcai:memory"));
            owner.getPersistentData().remove("mcai:memory");
        }

        // Leveling
        if (owner.getPersistentData().contains("mcai:level_system")) {
            levelSystem.load(owner.getPersistentData().getCompound("mcai:level_system"));
            levelSystem.applyBonuses(this);
            owner.getPersistentData().remove("mcai:level_system");
        }

        // Clear saved state after restoring (one-time use)
        owner.getPersistentData().remove("mcai:companion_state");
    }

    /**
     * Restore inventory and equipment from death data (kept in memory, not dropped).
     * Called by SoulCrystalItem when resummoning after death.
     */
    public void restoreFromDeathData(CompoundTag deathData) {
        if (deathData == null || deathData.isEmpty()) return;

        // Restore inventory
        if (deathData.contains("Inventory")) {
            ListTag invList = deathData.getList("Inventory", 10);
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

        // Restore equipment
        if (deathData.contains("Equipment")) {
            ListTag equipList = deathData.getList("Equipment", 10);
            for (int i = 0; i < equipList.size(); i++) {
                CompoundTag eqTag = equipList.getCompound(i);
                String slotName = eqTag.getString("Slot");
                EquipmentSlot slot = EquipmentSlot.byName(slotName);
                ItemStack stack = ItemStack.parse(this.registryAccess(), eqTag)
                        .orElse(ItemStack.EMPTY);
                this.setItemSlot(slot, stack);
            }
        }

        // Restore name
        if (deathData.contains("Name")) {
            setCompanionName(deathData.getString("Name"));
        }

        MCAi.LOGGER.info("Restored companion death data (inventory + equipment preserved)");
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
        // Home area corners
        if (homeCorner1 != null) {
            tag.putLong(TAG_HOME_CORNER1, homeCorner1.asLong());
        }
        if (homeCorner2 != null) {
            tag.putLong(TAG_HOME_CORNER2, homeCorner2.asLong());
        }
        // Guard position
        if (guardPos != null) {
            tag.putLong("GuardPos", guardPos.asLong());
        }
        // Logistics tagged blocks
        if (!taggedBlocks.isEmpty()) {
            ListTag logList = new ListTag();
            for (TaggedBlock tb : taggedBlocks) {
                logList.add(tb.save());
            }
            tag.put(TAG_LOGISTICS, logList);
        }
        // Memory system
        tag.put("CompanionMemory", memory.save());
        // Leveling system
        tag.put("LevelSystem", levelSystem.save());
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
        // Home area corners
        if (tag.contains(TAG_HOME_CORNER1)) {
            homeCorner1 = BlockPos.of(tag.getLong(TAG_HOME_CORNER1));
        }
        if (tag.contains(TAG_HOME_CORNER2)) {
            homeCorner2 = BlockPos.of(tag.getLong(TAG_HOME_CORNER2));
        }
        // Guard position
        if (tag.contains("GuardPos")) {
            guardPos = BlockPos.of(tag.getLong("GuardPos"));
        }
        // Logistics tagged blocks
        if (tag.contains(TAG_LOGISTICS)) {
            taggedBlocks.clear();
            ListTag logList = tag.getList(TAG_LOGISTICS, 10);
            for (int i = 0; i < logList.size(); i++) {
                taggedBlocks.add(TaggedBlock.load(logList.getCompound(i)));
            }
        }
        // Memory system
        if (tag.contains("CompanionMemory")) {
            memory.load(tag.getCompound("CompanionMemory"));
        }
        // Leveling system
        if (tag.contains("LevelSystem")) {
            levelSystem.load(tag.getCompound("LevelSystem"));
            levelSystem.applyBonuses(this);
        }
    }
}
