package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.config.AiConfig;
import com.apocscode.mcai.entity.CompanionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * Renders the companion entity using the vanilla player model (Steve/Alex proportions).
 * Uses a custom skin texture.
 */
public class CompanionRenderer extends LivingEntityRenderer<CompanionEntity, PlayerModel<CompanionEntity>> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "textures/entity/companion.png");

    public CompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        // Add armor rendering layer
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
        // Add held item rendering layer (weapons, tools, shields)
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
    }

    @Override
    public ResourceLocation getTextureLocation(CompanionEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(CompanionEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // Ensure ALL model parts are visible.
        // PlayerModel has overlay layers (jacket, sleeves, pants, hat) that default
        // to hidden because PlayerRenderer.setModelProperties() checks player skin
        // settings — which don't exist for non-Player entities.
        PlayerModel<CompanionEntity> m = this.getModel();
        m.setAllVisible(true);
        m.hat.visible = true;
        m.jacket.visible = true;
        m.leftSleeve.visible = true;
        m.rightSleeve.visible = true;
        m.leftPants.visible = true;
        m.rightPants.visible = true;

        m.crouching = entity.isCrouching();
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);

        // Render in-world health bar when damaged
        boolean showBar;
        try { showBar = AiConfig.SHOW_HEALTH_BAR.get(); } catch (Exception e) { showBar = true; }
        float health = entity.getHealth();
        float maxHealth = entity.getMaxHealth();
        if (showBar && health < maxHealth && health > 0) {
            renderHealthBar(entity, poseStack, buffer, health, maxHealth);
        }
    }

    /**
     * Renders a health bar above the companion entity (below the nametag).
     * Only visible when the companion has taken damage.
     */
    private void renderHealthBar(CompanionEntity entity, PoseStack poseStack,
                                  MultiBufferSource buffer, float health, float maxHealth) {
        poseStack.pushPose();

        // Position above entity, slightly below nametag
        double bbHeight = entity.getBbHeight() + 0.5;
        poseStack.translate(0.0, bbHeight, 0.0);

        // Billboard — always face camera
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);

        Font font = this.getFont();
        Matrix4f matrix = poseStack.last().pose();

        // Color the text based on health percentage
        float pct = health / maxHealth;
        int color;
        if (pct > 0.6F) {
            color = 0xFF55FF55; // Green
        } else if (pct > 0.3F) {
            color = 0xFFFFFF55; // Yellow
        } else {
            color = 0xFFFF5555; // Red
        }

        String text = String.format("%.0f / %.0f", health, maxHealth);
        float textWidth = font.width(text);
        float x = -textWidth / 2.0F;

        // Draw background
        font.drawInBatch(text, x, 0, color, false, matrix, buffer,
                Font.DisplayMode.SEE_THROUGH, 0x40000000, 15728880);
        // Draw foreground
        font.drawInBatch(text, x, 0, color, false, matrix, buffer,
                Font.DisplayMode.NORMAL, 0, 15728880);

        poseStack.popPose();
    }
}
