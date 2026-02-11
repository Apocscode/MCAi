package com.apocscode.mcai.entity;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.goal.CompanionFollowGoal;
import com.apocscode.mcai.entity.goal.CompanionLookAtPlayerGoal;
import com.apocscode.mcai.network.OpenChatScreenPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The AI Companion entity. Uses a player model, follows its owner,
 * and opens a chat screen on interaction.
 */
public class CompanionEntity extends PathfinderMob {
    private static final String TAG_OWNER = "OwnerUUID";
    private static final String TAG_NAME = "CompanionName";

    @Nullable
    private UUID ownerUUID;
    private String companionName = "MCAi";

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)        // Tanky — we don't want it dying
                .add(Attributes.MOVEMENT_SPEED, 0.35D)    // Slightly faster than player walking
                .add(Attributes.FOLLOW_RANGE, 48.0D)      // Can see owner from far away
                .add(Attributes.ARMOR, 10.0D)              // Some natural armor
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.8D);
    }

    @Override
    protected void registerGoals() {
        // Priority 0: Don't drown
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Priority 1: Follow owner
        this.goalSelector.addGoal(1, new CompanionFollowGoal(this, 1.2D, 4.0F, 32.0F));
        // Priority 2: Look at nearby players
        this.goalSelector.addGoal(2, new CompanionLookAtPlayerGoal(this, 8.0F));
        // Priority 3: Random look around when idle
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
        // Priority 4: Gentle wandering when idle and near owner
        this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.6D));
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;

        if (!this.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
            // Set owner if not set
            if (ownerUUID == null) {
                ownerUUID = player.getUUID();
                player.sendSystemMessage(Component.literal("§b[MCAi]§r " + companionName + " is now your companion!"));
            }

            // Send packet to open chat screen on client
            PacketDistributor.sendToPlayer(serverPlayer, new OpenChatScreenPacket(this.getId()));
        }

        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Immune to most damage — we want this companion to survive
        if (source.getEntity() instanceof Player player) {
            // Owner can sneak+hit to dismiss
            if (player.isShiftKeyDown() && player.getUUID().equals(ownerUUID)) {
                if (!this.level().isClientSide) {
                    player.sendSystemMessage(Component.literal("§b[MCAi]§r " + companionName + " dismissed."));
                    this.discard();
                }
                return false;
            }
        }
        // Ignore non-player damage entirely
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false; // Never despawn
    }

    @Override
    public boolean isInvulnerable() {
        return true;
    }

    // ---- Owner management ----

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

    // ---- Save / Load ----

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerUUID != null) {
            tag.putUUID(TAG_OWNER, ownerUUID);
        }
        tag.putString(TAG_NAME, companionName);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(TAG_OWNER)) {
            ownerUUID = tag.getUUID(TAG_OWNER);
        }
        if (tag.contains(TAG_NAME)) {
            companionName = tag.getString(TAG_NAME);
            this.setCustomName(Component.literal(companionName));
        }
    }

    @Override
    public boolean shouldShowName() {
        return true; // Always show name tag
    }
}
