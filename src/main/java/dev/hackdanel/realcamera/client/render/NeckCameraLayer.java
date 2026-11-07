package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hackdanel.realcamera.camera.CameraHelper;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;

/**
 * A camera carried but not held hangs on its strap in front of the chest (the server says which one, see
 * {@code NeckCameras}). The strap goes around the neck; the camera swings with the body.
 */
public final class NeckCameraLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final Int2ObjectOpenHashMap<ItemStack> cameras = new Int2ObjectOpenHashMap<>();

    public NeckCameraLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    public static void set(int entityId, ItemStack stack) {
        if (stack.isEmpty()) cameras.remove(entityId);
        else cameras.put(entityId, stack);
    }

    public static void clear() {
        cameras.clear();
    }

    @SuppressWarnings("unchecked")
    public static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            var renderer = event.getSkin(skin);
            if (renderer instanceof net.minecraft.client.renderer.entity.player.PlayerRenderer pr) {
                pr.addLayer(new NeckCameraLayer(pr));
            }
        }
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack stack = cameras.get(player.getId());
        if (stack == null || player.isInvisible() || CameraHelper.handHoldingCamera(player).isPresent()) return;
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        // the strap: two thin bands from the shoulders down to the camera
        VertexConsumer strap = buffers.getBuffer(RenderType.entitySolid(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/block/black_wool.png")));
        Matrix4f m = pose.last().pose();
        float zf = -2.25f / 16f;
        band(strap, pose, m, -2.6f / 16, 0f, -1.9f / 16, 5.6f / 16, zf, light);
        band(strap, pose, m, 2.6f / 16, 0f, 1.9f / 16, 5.6f / 16, zf, light);
        // the camera itself, lens forward, resting on the chest a little below the strap's end
        pose.translate(0, 7.0f / 16, -3.1f / 16);
        pose.mulPose(Axis.YP.rotationDegrees(180));
        pose.mulPose(Axis.XP.rotationDegrees(player.isCrouching() ? 20 : 8));
        pose.scale(0.55f, 0.55f, 0.55f);
        Minecraft.getInstance().getItemRenderer().renderStatic(player, stack, ItemDisplayContext.FIXED, false, pose, buffers,
                player.level(), light, OverlayTexture.NO_OVERLAY, player.getId() + 7);
        pose.popPose();
    }

    /** A strap band from (x0, y0) to (x1, y1) in front of the chest, 1 px wide. */
    private static void band(VertexConsumer vc, PoseStack pose, Matrix4f m, float x0, float y0, float x1, float y1, float z,
                             int light) {
        float hw = 0.5f / 16;
        var n = pose.last();
        vc.addVertex(m, x0 - hw, y0, z).setColor(40, 34, 30, 255).setUv(0, 0).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(n, 0, 0, -1);
        vc.addVertex(m, x1 - hw, y1, z).setColor(40, 34, 30, 255).setUv(0, 1).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(n, 0, 0, -1);
        vc.addVertex(m, x1 + hw, y1, z).setColor(40, 34, 30, 255).setUv(1, 1).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(n, 0, 0, -1);
        vc.addVertex(m, x0 + hw, y0, z).setColor(40, 34, 30, 255).setUv(1, 0).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(n, 0, 0, -1);
    }
}
