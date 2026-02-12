package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.item.LogisticsWandItem;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.joml.Matrix4f;

/**
 * Renders color-coded cube outlines around containers tagged with the Logistics Wand.
 *
 * Only visible when the player is holding a Logistics Wand in either hand.
 * Colors:
 *   INPUT   = Blue   (0.33, 0.6, 1.0)
 *   OUTPUT  = Orange  (1.0, 0.53, 0.0)
 *   STORAGE = Green   (0.33, 1.0, 0.33)
 */
@EventBusSubscriber(modid = MCAi.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class LogisticsOutlineRenderer {

    // Client-side cache updated by SyncTaggedBlocksPacket
    private static volatile List<TaggedBlock> clientTaggedBlocks = List.of();

    /**
     * Called from SyncTaggedBlocksPacket handler to update the client cache.
     */
    public static void updateClientCache(List<TaggedBlock> blocks) {
        clientTaggedBlocks = List.copyOf(blocks);
    }

    /** Get the number of tagged blocks on the client (for HUD display) */
    public static int getClientTagCount() {
        return clientTaggedBlocks.size();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // Only render when holding the Logistics Wand
        boolean holdingWand = player.getMainHandItem().getItem() instanceof LogisticsWandItem
                || player.getOffhandItem().getItem() instanceof LogisticsWandItem;
        if (!holdingWand) return;

        // Try to get tagged blocks from the client-side companion entity first,
        // then fall back to our cache
        List<TaggedBlock> taggedBlocks = findClientTaggedBlocks(mc, player);
        if (taggedBlocks.isEmpty()) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();

        var bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer lineConsumer = bufferSource.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        for (TaggedBlock tagged : taggedBlocks) {
            BlockPos pos = tagged.pos();

            // Only render within 48 blocks
            if (pos.distToCenterSqr(camera.x, camera.y, camera.z) > 48 * 48) continue;

            float r, g, b, a;
            switch (tagged.role()) {
                case INPUT -> { r = 0.33f; g = 0.6f; b = 1.0f; a = 1.0f; }
                case OUTPUT -> { r = 1.0f; g = 0.53f; b = 0.0f; a = 1.0f; }
                case STORAGE -> { r = 0.33f; g = 1.0f; b = 0.33f; a = 1.0f; }
                default -> { r = 1.0f; g = 1.0f; b = 1.0f; a = 1.0f; }
            }

            // Slightly inflated AABB so the outline doesn't z-fight with the block
            AABB box = new AABB(pos).inflate(0.005);

            LevelRenderer.renderLineBox(poseStack, lineConsumer,
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    r, g, b, a);
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());

        // === Floating role labels above tagged blocks ===
        boolean showLabels;
        try { showLabels = AiConfig.SHOW_BLOCK_LABELS.get(); } catch (Exception e) { showLabels = true; }
        if (!showLabels) return;
        Font font = mc.font;
        for (TaggedBlock tagged : taggedBlocks) {
            BlockPos pos = tagged.pos();

            // Only render labels within 24 blocks
            if (pos.distToCenterSqr(camera.x, camera.y, camera.z) > 24 * 24) continue;

            String label = tagged.role().getLabel();
            int labelColor = switch (tagged.role()) {
                case INPUT -> 0xFF5599FF;
                case OUTPUT -> 0xFFFF8800;
                case STORAGE -> 0xFF55FF55;
            };

            poseStack.pushPose();
            poseStack.translate(
                    pos.getX() + 0.5 - camera.x,
                    pos.getY() + 1.3 - camera.y,
                    pos.getZ() + 0.5 - camera.z);

            // Billboard: face camera (same approach as vanilla nametag)
            poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            poseStack.scale(-0.025F, -0.025F, 0.025F);

            Matrix4f matrix = poseStack.last().pose();
            float halfWidth = font.width(label) / 2.0f;

            font.drawInBatch(label, -halfWidth, 0, labelColor, false, matrix,
                    bufferSource, Font.DisplayMode.NORMAL, 0x40000000, 15728880);

            poseStack.popPose();
        }
        bufferSource.endBatch();
    }

    /**
     * Find tagged blocks from the client-side companion entity.
     * Scans all loaded entities to find a CompanionEntity, since
     * the server-side LIVING_COMPANIONS map isn't available on client.
     */
    private static List<TaggedBlock> findClientTaggedBlocks(Minecraft mc, Player player) {
        // First try scanning entities in the client level
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof CompanionEntity companion) {
                List<TaggedBlock> blocks = companion.getTaggedBlocks();
                if (!blocks.isEmpty()) {
                    return blocks;
                }
            }
        }

        // Fall back to the packet-synced cache
        return clientTaggedBlocks;
    }
}
