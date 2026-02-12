package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Registers custom keybindings for MCAi.
 * - Push-to-Talk: V key (hold to record, release to transcribe)
 * - Whistle: G key (call companion to player)
 */
@EventBusSubscriber(modid = MCAi.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModKeybinds {

    public static final KeyMapping PUSH_TO_TALK = new KeyMapping(
            "key.mcai.push_to_talk",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.mcai"
    );

    public static final KeyMapping WHISTLE = new KeyMapping(
            "key.mcai.whistle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "key.categories.mcai"
    );

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PUSH_TO_TALK);
        event.register(WHISTLE);
        MCAi.LOGGER.info("Registered MCAi keybinds (Push-to-Talk: V, Whistle: G)");
    }
}
