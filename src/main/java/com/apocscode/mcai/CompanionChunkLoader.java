package com.apocscode.mcai;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/**
 * Manages chunk force-loading for the companion entity.
 *
 * When the companion has active tasks, the chunk it's in (and adjacent chunks)
 * are force-loaded so the entity continues ticking even when the player walks away.
 * Tickets are released when all tasks complete or are cancelled.
 *
 * Uses NeoForge's TicketController system — tickets survive server restarts
 * and are automatically validated on world load.
 */
public class CompanionChunkLoader {

    /** Ticket controller registered via RegisterTicketControllersEvent. */
    public static final TicketController TICKET_CONTROLLER = new TicketController(
            ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "companion_task"),
            (level, ticketHelper) -> {
                // Validation callback on world load — clear all stale tickets.
                // Tasks don't survive restarts anyway, so no point keeping old tickets.
                ticketHelper.getEntityTickets().keySet().forEach(uuid -> {
                    ticketHelper.removeAllTickets(uuid);
                });
                MCAi.LOGGER.debug("Cleared stale companion chunk tickets on world load");
            }
    );

    /** Currently loaded chunk position (null if not loading). */
    private ChunkPos loadedChunk;
    /** The entity whose UUID owns the tickets. */
    private Entity owner;
    private ServerLevel level;

    /**
     * Start force-loading the chunk the companion is in.
     * Also loads the 4 adjacent chunks (3x3 cross) so the companion can work at chunk borders.
     */
    public void startLoading(Entity companion) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) return;
        this.owner = companion;
        this.level = serverLevel;
        ChunkPos pos = new ChunkPos(companion.blockPosition());
        forceChunks(pos, true);
        this.loadedChunk = pos;
        MCAi.LOGGER.info("Companion chunk loading STARTED at chunk ({}, {})", pos.x, pos.z);
    }

    /**
     * Update the loaded chunk if the companion has moved to a different chunk.
     * Call this periodically from TaskManager.tick().
     */
    public void updatePosition(Entity companion) {
        if (loadedChunk == null || level == null) return;
        ChunkPos current = new ChunkPos(companion.blockPosition());
        if (!current.equals(loadedChunk)) {
            // Unload old, load new
            forceChunks(loadedChunk, false);
            forceChunks(current, true);
            MCAi.LOGGER.debug("Companion chunk loading moved: ({},{}) → ({},{})",
                    loadedChunk.x, loadedChunk.z, current.x, current.z);
            loadedChunk = current;
        }
    }

    /**
     * Stop force-loading all companion chunks.
     */
    public void stopLoading() {
        if (loadedChunk != null && level != null) {
            forceChunks(loadedChunk, false);
            MCAi.LOGGER.info("Companion chunk loading STOPPED at chunk ({}, {})",
                    loadedChunk.x, loadedChunk.z);
            loadedChunk = null;
        }
    }

    /**
     * Whether we currently have a chunk force-loaded.
     */
    public boolean isLoading() {
        return loadedChunk != null;
    }

    /**
     * Force or unforce a 3x3 cross of chunks centered on the given chunk.
     * Uses ticking tickets so the companion entity ticks and pathfinds normally.
     */
    private void forceChunks(ChunkPos center, boolean add) {
        if (owner == null || level == null) return;
        // Load center + 4 cardinal neighbors (cross pattern, not full 3x3)
        int[][] offsets = {{0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] off : offsets) {
            TICKET_CONTROLLER.forceChunk(level, owner,
                    center.x + off[0], center.z + off[1],
                    add, true); // ticking = true
        }
    }
}
