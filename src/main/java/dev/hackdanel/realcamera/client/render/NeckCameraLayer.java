package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hackdanel.realcamera.camera.CameraHelper;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import org.joml.Matrix4f;

/**
 * A camera carried but not held hangs on its strap in front of the chest (the server says which one, see
 * {@code NeckCameras}), the way a camera hangs on a neck strap: lens forward, back (screen) against the chest, so even
 * the longest lens with a teleconverter stays outside the body. The strap goes around the neck; the camera swings
 * with the body.
 */
public final class NeckCameraLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final Int2ObjectOpenHashMap<ItemStack> cameras = new Int2ObjectOpenHashMap<>();
    private static final Map<BakedModel, float[]> bounds = new WeakHashMap<>();
    /** Item model scale on the chest; where the top of the camera hangs (5 px below the neck); lens-down tilt. */
    private static final float SCALE = 0.385f, TOP = 5f / 16, TILT = (float) Math.toRadians(8);

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
        var renderer = Minecraft.getInstance().getItemRenderer();
        BakedModel model = renderer.getModel(stack, player.level(), player, player.getId() + 7);
        float[] b = bounds(model, stack);
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);
        // body model space: y down, front towards -z, chest surface at z = -2 px (plus the armour over it)
        float chest = (2f + armour(player)) / 16f;
        // the strap: two thin bands from the shoulders down to the camera's lugs, between the chest and the camera
        VertexConsumer strap = buffers.getBuffer(RenderType.entitySolid(
                net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/block/black_wool.png")));
        Matrix4f m = pose.last().pose();
        float zf = -chest - 0.1f / 16;
        band(strap, pose, m, -2.6f / 16, 0f, -2.2f / 16, TOP + 0.8f / 16, zf, light);
        band(strap, pose, m, 2.6f / 16, 0f, 2.2f / 16, TOP + 0.8f / 16, zf, light);
        // the camera: back against the chest, lens forward, hanging by gravity: a little lens-down while standing,
        // back to that when the body leans forward (sneaking). It turns about the edge of its back that touches the
        // chest (bottom when it tips lens-down, top when it swings out), so no part ever goes into the body.
        float h = (b[3] - b[2]) * SCALE;
        float angle = TILT - getParentModel().body.xRot;
        boolean bottom = angle >= 0;
        pose.translate(0, TOP + (bottom ? h : 0), -chest - 0.2f / 16);
        pose.mulPose(Axis.XP.rotation(angle));
        pose.mulPose(Axis.ZP.rotationDegrees(180)); // item model upright with its front (-z) facing forward
        pose.scale(SCALE, SCALE, SCALE);
        // the item renderer centres the model (-0.5); put the back edge of the pivot at the origin instead
        pose.translate(0.5f - (b[0] + b[1]) / 2, 0.5f - (bottom ? b[2] : b[3]), 0.5f - b[4]);
        renderer.render(stack, ItemDisplayContext.NONE, false, pose, buffers, light, OverlayTexture.NO_OVERLAY, model);
        pose.popPose();
    }

    /** How far armour on the chest stands out (px): chestplate 1, leggings (cover the waist) 0.5. */
    private static float armour(AbstractClientPlayer player) {
        if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ArmorItem) return 1f;
        return player.getItemBySlot(EquipmentSlot.LEGS).getItem() instanceof ArmorItem ? 0.5f : 0f;
    }

    /**
     * Extent of the whole camera as drawn (body and every part along the optical axis), in item model coordinates
     * (block units, front towards -z): {xMin, xMax, yMin, yMax, zMax}; zMax is the back of the body.
     */
    private static float[] bounds(BakedModel model, ItemStack stack) {
        return bounds.computeIfAbsent(model, k -> {
            float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            RandomSource rand = RandomSource.create(42);
            for (BakedModel pass : k.getRenderPasses(stack, false)) {
                for (int d = 0; d <= Direction.values().length; d++) {
                    Direction side = d < Direction.values().length ? Direction.values()[d] : null;
                    for (BakedQuad q : pass.getQuads(null, side, rand)) {
                        int[] v = q.getVertices();
                        for (int i = 0; i < 4; i++) {
                            int o = i * IQuadTransformer.STRIDE + IQuadTransformer.POSITION;
                            float x = Float.intBitsToFloat(v[o]), y = Float.intBitsToFloat(v[o + 1]);
                            b[0] = Math.min(b[0], x);
                            b[1] = Math.max(b[1], x);
                            b[2] = Math.min(b[2], y);
                            b[3] = Math.max(b[3], y);
                            b[4] = Math.max(b[4], Float.intBitsToFloat(v[o + 2]));
                        }
                    }
                }
            }
            return b[0] > b[1] ? new float[] {0, 1, 0, 1, 1} : b;
        });
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
