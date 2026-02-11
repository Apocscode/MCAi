package com.apocscode.mcai;

import com.apocscode.mcai.ai.AIService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MCAi.MOD_ID)
public class MCAi {
    public static final String MOD_ID = "mcai";
    public static final Logger LOGGER = LoggerFactory.getLogger("MCAi");

    public MCAi(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("MCAi initializing...");

        // Register all deferred registries
        ModRegistry.register(modEventBus);

        // Register networking
        ModNetworking.init(modEventBus);

        // Start AI service
        AIService.init();

        LOGGER.info("MCAi initialized");
    }
}
