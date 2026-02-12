package com.apocscode.mcai.client;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.resources.ResourceLocation;

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
    }
}
