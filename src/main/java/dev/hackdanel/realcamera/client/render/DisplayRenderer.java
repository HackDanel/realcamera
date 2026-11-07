package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.hackdanel.realcamera.block.DisplayBlock;
import dev.hackdanel.realcamera.block.PhotoStandBlock;
import dev.hackdanel.realcamera.block.TableFrameBlock;
import dev.hackdanel.realcamera.blockentity.DisplayBlockEntity;
import dev.hackdanel.realcamera.client.photo.PhotoCache;
import dev.hackdanel.realcamera.component.PrintData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The print inside a table frame or on a photo stand. Coordinates are in pixels (1/16 block) of a
 * north-facing block (front towards -z), matching the JSON models from tools/gen_assets.py.
 */
public class DisplayRenderer implements BlockEntityRenderer<DisplayBlockEntity> {
    public DisplayRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(DisplayBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light,
                       int overlay) {
        BlockState state = be.getBlockState();
        if (!state.hasProperty(DisplayBlock.FACING)) return;
        boolean turned = state.hasProperty(TableFrameBlock.TURN) && state.getValue(TableFrameBlock.TURN) != 0;
        float yaw = state.getBlock() instanceof TableFrameBlock ? TableFrameBlock.yaw(state) : state.getValue(DisplayBlock.FACING).toYRot();
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
        pose.translate(-0.5, 0, -0.5);
        if (turned) {
            // block models only turn in 90 degree steps: a frame between them has an empty model and is drawn here
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                    state.setValue(DisplayBlock.FACING, net.minecraft.core.Direction.NORTH).setValue(TableFrameBlock.TURN, 0),
                    pose, buffers, light, overlay);
        }
        PrintData print = be.printData();
        if (print == null) {
            pose.popPose();
            return;
        }
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
        boolean stand = state.getBlock() instanceof PhotoStandBlock;
        double aspect = print.photo().height() == 0 ? 1.5 : (double) print.photo().width() / print.photo().height();
        float x0, y0, x1, y1, z;
        if (stand) {
            // board on the ledge (y = 10), centred, at most 14 x 18 px, white border
            float maxW = 14, maxH = 18;
            float w = (float) Math.min(maxW, maxH * aspect), h = (float) (w / aspect);
            x0 = 8 - w / 2;
            x1 = 8 + w / 2;
            y0 = 10.2f;
            y1 = y0 + h;
            z = 4.9f;
            TextureAtlasSprite white = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                    .apply(ResourceLocation.withDefaultNamespace("block/white_concrete"));
            VertexConsumer solid = buffers.getBuffer(RenderType.entitySolid(InventoryMenu.BLOCK_ATLAS));
            QuadHelper.box(pose.last(), solid, x0 - 0.6f, y0 - 0.2f, z + 0.05f, x1 + 0.6f, y1 + 0.6f, z + 0.6f, white,
                    0xFFF4F2EC, light);
        } else {
            // window of the table frame model
            if (aspect < 1) {
                x0 = 5.5f; x1 = 10.5f; y0 = 1; y1 = 10;
            } else {
                x0 = 4; x1 = 12; y0 = 1; y1 = 7;
            }
            z = 7.45f;
        }
        PhotoCache.Entry e = PhotoCache.get(print.photo().id());
        if (e.state() == PhotoCache.State.READY && e.texture() != null) {
            float areaAspect = (x1 - x0) / (y1 - y0);
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
            VertexConsumer vc = buffers.getBuffer(RenderType.entitySolid(e.texture()));
            QuadHelper.rectZ(pose.last(), vc, x0, y0, x1, y1, z, true, u0, v0, u1, v1, PrintTint.color(print.missingInks()), light);
        }
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(DisplayBlockEntity be) {
        return new AABB(be.getBlockPos()).expandTowards(0, 1, 0);
    }
}
