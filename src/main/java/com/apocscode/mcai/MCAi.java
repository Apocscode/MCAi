package com.apocscode.mcai;

import com.apocscode.mcai.ai.AIService;
import com.apocscode.mcai.ai.AiLogger;
import com.apocscode.mcai.config.AiConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MCAi.MOD_ID)
public class MCAi {
    public static final String MOD_ID = "mcai";
    public static final Logger LOGGER = LoggerFactory.getLogger("MCAi");

    public MCAi(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("MCAi initializing...");

        // Register configuration
        modContainer.registerConfig(ModConfig.Type.COMMON, AiConfig.SPEC);

        // Register all deferred registries
        ModRegistry.register(modEventBus);

        // Register networking
        ModNetworking.init(modEventBus);

        // Start diagnostic logger
        AiLogger.init();

        // Auto-detect and start Ollama (local LLM fallback)
        com.apocscode.mcai.ai.OllamaManager.init();

        // Start AI service
        AIService.init();

        LOGGER.info("MCAi initialized");
    }
}
