package com.apocscode.mcai.entity.goal;

import com.apocscode.mcai.entity.CompanionChat;
import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Combat goal that equips the companion's best weapon before engaging.
 * Uses healing/strength potions during combat when health is low.
 * Extends vanilla MeleeAttackGoal with weapon selection and proactive chat.
 */
public class CompanionCombatGoal extends MeleeAttackGoal {
    private final CompanionEntity companion;
    private LivingEntity lastTarget;
    private int potionCooldown = 0;

    public CompanionCombatGoal(CompanionEntity companion, double speedModifier,
                                boolean followingTargetEvenIfNotSeen) {
        super(companion, speedModifier, followingTargetEvenIfNotSeen);
        this.companion = companion;
    }

    @Override
    public boolean canUse() {
        if (companion.getOwner() == null) return false;
        return super.canUse();
    }

    @Override
    public void start() {
        companion.equipBestWeapon();
        potionCooldown = 0;

        LivingEntity target = companion.getTarget();
        if (target != null) {
            lastTarget = target;
            String mobName = target.getName().getString();
            if (companion.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
                companion.getChat().warn(CompanionChat.Category.NO_WEAPON,
                        "Engaging " + mobName + " but I have no weapon! Give me a sword!");
            } else {
                companion.getChat().say(CompanionChat.Category.COMBAT,
                        "Engaging hostile " + mobName + "!");
            }
        }

        super.start();
    }

    @Override
    public void tick() {
        super.tick();

        if (potionCooldown > 0) potionCooldown--;

        // Use potions during combat
        if (potionCooldown <= 0) {
            float healthPct = companion.getHealth() / companion.getMaxHealth();

            // Use healing potion when below 40% health
            if (healthPct < 0.4f) {
                if (tryDrinkPotion(true)) {
                    potionCooldown = 60; // 3 sec cooldown
                    return;
                }
            }

            // Use strength potion when fighting strong mobs at reasonable health
            if (healthPct > 0.5f && lastTarget != null && lastTarget.getMaxHealth() > 30) {
                if (!companion.hasEffect(MobEffects.DAMAGE_BOOST)) {
                    if (tryDrinkPotion(false)) {
                        potionCooldown = 60;
                    }
                }
            }
        }
    }

    @Override
    public void stop() {
        if (lastTarget != null && !lastTarget.isAlive()) {
            companion.getChat().say(CompanionChat.Category.COMBAT_VICTORY,
                    "Defeated the " + lastTarget.getName().getString() + "!");
            // Award XP for the kill
            companion.awardXp(com.apocscode.mcai.entity.CompanionLevelSystem.MOB_KILL_XP);
        }
        lastTarget = null;
        super.stop();
    }

    /**
     * Try to drink a healing or strength potion from companion inventory.
     * @param healing true = look for healing/regen potions, false = look for strength potions
     * @return true if a potion was consumed
     */
    private boolean tryDrinkPotion(boolean healing) {
        SimpleContainer inv = companion.getCompanionInventory();

        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() != Items.POTION && stack.getItem() != Items.SPLASH_POTION) continue;

            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            if (contents == null) continue;

            boolean isUseful = false;
            for (MobEffectInstance effect : contents.getAllEffects()) {
                if (healing && (effect.getEffect().value() == MobEffects.HEAL.value()
                        || effect.getEffect().value() == MobEffects.REGENERATION.value())) {
                    isUseful = true;
                    break;
                }
                if (!healing && (effect.getEffect().value() == MobEffects.DAMAGE_BOOST.value()
                        || effect.getEffect().value() == MobEffects.MOVEMENT_SPEED.value())) {
                    isUseful = true;
                    break;
                }
            }

            if (isUseful) {
                // Apply effects
                for (MobEffectInstance effect : contents.getAllEffects()) {
                    companion.addEffect(new MobEffectInstance(effect));
                }

                // Consume potion, leave glass bottle
                companion.playSound(SoundEvents.GENERIC_DRINK, 1.0F, 1.0F);
                stack.shrink(1);
                if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                inv.addItem(new ItemStack(Items.GLASS_BOTTLE));

                String potionType = healing ? "healing" : "strength";
                companion.getChat().say(CompanionChat.Category.COMBAT,
                        "Drinking " + potionType + " potion!");
                return true;
            }
        }
        return false;
    }
}
