package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hackdanel.realcamera.client.photo.PhotoCache;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.entity.PhotoFrameEntity;
import dev.hackdanel.realcamera.registry.RCComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

/** Draws the moulding, the mat and the photo of a wall frame. */
public class PhotoFrameRenderer extends EntityRenderer<PhotoFrameEntity> {
    private static final float BACK = 0.03125f;
    private static final float FRONT = -0.09f;

    public PhotoFrameRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    static ResourceLocation materialSprite(String material) {
        return switch (material) {
            case "spruce" -> ResourceLocation.withDefaultNamespace("block/spruce_planks");
            case "dark_oak" -> ResourceLocation.withDefaultNamespace("block/dark_oak_planks");
            case "birch" -> ResourceLocation.withDefaultNamespace("block/birch_planks");
            case "cherry" -> ResourceLocation.withDefaultNamespace("block/cherry_planks");
            case "bamboo" -> ResourceLocation.withDefaultNamespace("block/bamboo_planks");
            case "black_metal" -> ResourceLocation.withDefaultNamespace("block/black_concrete");
            case "gold" -> ResourceLocation.withDefaultNamespace("block/gold_block");
            default -> ResourceLocation.withDefaultNamespace("block/oak_planks");
        };
    }

    @Override
    public void render(PhotoFrameEntity frame, float yaw, float partialTicks, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        float w = frame.frameWidth(), h = frame.frameHeight();
        float x0 = -w / 2, y0 = -h / 2, x1 = w / 2, y1 = h / 2;
        float border = 0.06f + 0.02f * Math.min(w, h);
        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        TextureAtlasSprite wood = atlas.apply(materialSprite(frame.material()));
        TextureAtlasSprite plain = atlas.apply(ResourceLocation.withDefaultNamespace("block/white_concrete"));
        VertexConsumer solid = buffers.getBuffer(RenderType.entitySolid(InventoryMenu.BLOCK_ATLAS));
        PoseStack.Pose p = pose.last();

        // moulding: four bars
        QuadHelper.box(p, solid, x0, y1 - border, FRONT, x1, y1, BACK, wood, -1, light);
        QuadHelper.box(p, solid, x0, y0, FRONT, x1, y0 + border, BACK, wood, -1, light);
        QuadHelper.box(p, solid, x0, y0 + border, FRONT, x0 + border, y1 - border, BACK, wood, -1, light);
        QuadHelper.box(p, solid, x1 - border, y0 + border, FRONT, x1, y1 - border, BACK, wood, -1, light);
        // backing board
        QuadHelper.rectZ(p, solid, x0, y0, x1, y1, BACK, false, wood.getU0(), wood.getV0(), wood.getU1(), wood.getV1(), -1, light);

        float ix0 = x0 + border, iy0 = y0 + border, ix1 = x1 - border, iy1 = y1 - border;
        byte mat = frame.mat();
        int matColor = mat == PhotoFrameEntity.MAT_BLACK ? 0xFF1A1A1A : 0xFFF2F0EA;
        float zMat = -0.035f, zPhoto = -0.04f;
        float px0 = ix0, py0 = iy0, px1 = ix1, py1 = iy1;
        if (mat != PhotoFrameEntity.MAT_NONE) {
            float m = 0.10f * Math.min(w, h);
            px0 += m;
            py0 += m;
            px1 -= m;
            py1 -= m;
        }
        QuadHelper.rectZ(p, solid, ix0, iy0, ix1, iy1, zMat, true, plain.getU0(), plain.getV0(), plain.getU1(), plain.getV1(),
                mat == PhotoFrameEntity.MAT_NONE ? 0xFF303030 : matColor, light);

        PrintData print = frame.print().get(RCComponents.PRINT.get());
        if (print != null) {
            PhotoCache.Entry e = PhotoCache.get(print.photo().id());
            if (e.state() == PhotoCache.State.READY && e.texture() != null) {
                // cover-crop the picture to the window
                float areaAspect = (px1 - px0) / (py1 - py0);
                float photoAspect = (float) e.width() / e.height();
                float u0 = 0, u1 = 1, v0 = 0, v1 = 1;
                if (photoAspect > areaAspect) {
                    float keep = areaAspect / photoAspect;
                    u0 = (1 - keep) / 2;
                    u1 = 1 - u0;
                } else {
                    float keep = photoAspect / areaAspect;
                    v0 = (1 - keep) / 2;
                    v1 = 1 - v0;
                }
                VertexConsumer photo = buffers.getBuffer(RenderType.entitySolid(e.texture()));
                QuadHelper.rectZ(p, photo, px0, py0, px1, py1, zPhoto, true, u0, v0, u1, v1,
                        PrintTint.color(print.missingInks()), light);
            } else {
                QuadHelper.rectZ(p, solid, px0, py0, px1, py1, zPhoto, true, plain.getU0(), plain.getV0(),
                        plain.getU1(), plain.getV1(), 0xFF5A5A5A, light);
            }
        }
        pose.popPose();
        super.render(frame, yaw, partialTicks, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(PhotoFrameEntity entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
