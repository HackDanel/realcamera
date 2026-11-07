package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hackdanel.realcamera.entity.TripodEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;

/** Three splayed legs, a centre column, a pan/tilt head and the mounted camera. */
public class TripodRenderer extends EntityRenderer<TripodEntity> {
    private static final float HEAD = 1.45f;

    public TripodRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(TripodEntity tripod, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        TextureAtlasSprite metal = atlas.apply(ResourceLocation.withDefaultNamespace("block/black_concrete"));
        TextureAtlasSprite grip = atlas.apply(ResourceLocation.withDefaultNamespace("block/gray_concrete"));
        VertexConsumer vc = buffers.getBuffer(RenderType.entitySolid(InventoryMenu.BLOCK_ATLAS));
        float headYaw = tripod.getYRot();
        // legs, spread 120° apart, leaning out by ~22°
        for (int i = 0; i < 3; i++) {
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(i * 120f + 30));
            pose.translate(0, HEAD - 0.12f, 0);
            pose.mulPose(Axis.XP.rotationDegrees(158f));
            QuadHelper.box(pose.last(), vc, -0.025f, 0, -0.025f, 0.025f, 1.45f, 0.025f, metal, -1, light);
            QuadHelper.box(pose.last(), vc, -0.035f, 0.9f, -0.035f, 0.035f, 1.0f, 0.035f, grip, -1, light);
            pose.popPose();
        }
        // centre column and head
        QuadHelper.box(pose.last(), vc, -0.04f, HEAD - 0.35f, -0.04f, 0.04f, HEAD, 0.04f, metal, -1, light);
        pose.pushPose();
        pose.translate(0, HEAD, 0);
        pose.mulPose(Axis.YP.rotationDegrees(180 - headYaw));
        QuadHelper.box(pose.last(), vc, -0.08f, 0, -0.06f, 0.08f, 0.06f, 0.06f, metal, -1, light);
        // the camera, tilted with the head: the lens faces -z here, and a positive pitch (looking down) must
        // turn it down, i.e. a negative turn about +x
        if (!tripod.camera().isEmpty()) {
            pose.mulPose(Axis.XP.rotationDegrees(-tripod.getXRot()));
            pose.translate(0, 0.2f, 0);
            pose.scale(0.55f, 0.55f, 0.55f);
            Minecraft.getInstance().getItemRenderer().renderStatic(tripod.camera(), ItemDisplayContext.FIXED, light,
                    OverlayTexture.NO_OVERLAY, pose, buffers, tripod.level(), tripod.getId());
        }
        pose.popPose();
        super.render(tripod, yaw, partialTicks, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(TripodEntity entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
