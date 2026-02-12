package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.apocscode.mcai.item.LogisticsWandItem;
import com.apocscode.mcai.logistics.TaggedBlock;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;
import java.util.UUID;

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

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        // Only render when holding the Logistics Wand
        boolean holdingWand = player.getMainHandItem().getItem() instanceof LogisticsWandItem
                || player.getOffhandItem().getItem() instanceof LogisticsWandItem;
        if (!holdingWand) return;

        // Find the player's companion
        UUID playerUUID = player.getUUID();
        CompanionEntity companion = CompanionEntity.getLivingCompanion(playerUUID);
        if (companion == null) return;

        List<TaggedBlock> taggedBlocks = companion.getTaggedBlocks();
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
                case INPUT -> { r = 0.33f; g = 0.6f; b = 1.0f; a = 0.85f; }
                case OUTPUT -> { r = 1.0f; g = 0.53f; b = 0.0f; a = 0.85f; }
                case STORAGE -> { r = 0.33f; g = 1.0f; b = 0.33f; a = 0.85f; }
                default -> { r = 1.0f; g = 1.0f; b = 1.0f; a = 0.85f; }
            }

            // Slightly inflated AABB so the outline doesn't z-fight with the block
            AABB box = new AABB(pos).inflate(0.002);

            LevelRenderer.renderLineBox(poseStack, lineConsumer,
                    box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ,
                    r, g, b, a);
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.lines());
    }
}
