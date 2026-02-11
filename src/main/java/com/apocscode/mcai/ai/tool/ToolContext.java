package com.apocscode.mcai.ai.tool;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

/**
 * Context object passed to tools during execution.
 * Provides access to the player and server for game-state queries.
 */
public record ToolContext(
        ServerPlayer player,
        MinecraftServer server
) {
}
