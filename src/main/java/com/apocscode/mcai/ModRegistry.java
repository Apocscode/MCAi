package com.apocscode.mcai;

import com.apocscode.mcai.entity.CompanionEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModRegistry {
    // Registries
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MCAi.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, MCAi.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MCAi.MOD_ID);

    // Entity
    public static final DeferredHolder<EntityType<?>, EntityType<CompanionEntity>> COMPANION =
            ENTITY_TYPES.register("companion", () ->
                    EntityType.Builder.of(CompanionEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, 1.8F) // Player-sized
                            .clientTrackingRange(10)
                            .build(MCAi.MOD_ID + ":companion"));

    // Spawn egg
    public static final DeferredHolder<Item, SpawnEggItem> COMPANION_SPAWN_EGG =
            ITEMS.register("companion_spawn_egg", () ->
                    new SpawnEggItem(COMPANION.get(), 0x3498DB, 0x2ECC71,
                            new Item.Properties()));

    // Creative tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MCAI_TAB =
            CREATIVE_TABS.register("mcai_tab", () ->
                    CreativeModeTab.builder()
                            .title(Component.literal("MCAi"))
                            .icon(() -> COMPANION_SPAWN_EGG.get().getDefaultInstance())
                            .displayItems((params, output) -> {
                                output.accept(COMPANION_SPAWN_EGG.get());
                            })
                            .build());

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
        ITEMS.register(bus);
        CREATIVE_TABS.register(bus);
    }
}
