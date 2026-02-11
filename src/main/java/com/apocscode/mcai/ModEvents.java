package com.apocscode.mcai;

import com.apocscode.mcai.entity.CompanionEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import com.apocscode.mcai.client.CompanionRenderer;

@EventBusSubscriber(modid = MCAi.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class ModEvents {

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModRegistry.COMPANION.get(), CompanionEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModRegistry.COMPANION.get(), CompanionRenderer::new);
    }
}
