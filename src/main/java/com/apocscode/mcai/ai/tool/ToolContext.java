package com.apocscode.mcai.ai.tool;

import com.apocscode.mcai.MCAi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Context object passed to tools during execution.
 * Provides access to the player and server for game-state queries.
 *
 * CRITICAL: Tools execute on a background thread. Any code that MODIFIES
 * the game world (blocks, entities, inventories, commands) MUST use
 * runOnServer() to schedule work on the server tick thread.
 *
 * Read-only queries (scanning blocks, reading inventories) are generally
 * safe from background threads, but writes are NOT.
 */
public record ToolContext(
        ServerPlayer player,
        MinecraftServer server
) {
    /**
     * Execute a task on the server tick thread and block until complete.
     * Use this for ANY world-modifying operation (placing blocks, moving items,
     * running commands, modifying entities, etc.).
     *
     * @param task The task to run on the server thread
     * @param <T> Return type
     * @return The result from the task
     * @throws RuntimeException if the task fails or times out
     */
    public <T> T runOnServer(Supplier<T> task) {
        if (server == null) throw new RuntimeException("No server context");

        // If we're already on the server thread, just run directly
        if (server.isSameThread()) {
            return task.get();
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                T result = task.get();
                future.complete(result);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            MCAi.LOGGER.error("Server thread task timed out after 10s");
            throw new RuntimeException("Server thread task timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for server thread", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Server thread task failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    /**
     * Fire-and-forget on server thread (for tasks where we don't need the result).
     */
    public void executeOnServer(Runnable task) {
        if (server == null) return;
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }
}
