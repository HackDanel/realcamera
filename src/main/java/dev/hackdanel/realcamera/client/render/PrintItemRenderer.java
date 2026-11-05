package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.hackdanel.realcamera.client.photo.PhotoCache;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.registry.RCComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a print as a thin paper card showing the real photo, wherever the item appears: inventory
 * icon, hand, item frame, dropped item. Card lies in the z = 0.5 plane of the unit item space.
 */
public class PrintItemRenderer extends BlockEntityWithoutLevelRenderer {
    public PrintItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose, MultiBufferSource buffers,
                             int light, int overlay) {
        PrintData print = stack.get(RCComponents.PRINT.get());
        TextureAtlasSprite paper = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(ResourceLocation.withDefaultNamespace("block/white_concrete"));
        float aspect = 1.5f;
        PhotoCache.Entry e = null;
        if (print != null) {
            e = PhotoCache.get(print.photo().id());
            if (print.photo().height() > 0) aspect = (float) print.photo().width() / print.photo().height();
        }
        float cw = aspect >= 1 ? 1f : aspect, ch = aspect >= 1 ? 1f / aspect : 1f;
        float x0 = 0.5f - cw / 2, x1 = 0.5f + cw / 2, y0 = 0.5f - ch / 2, y1 = 0.5f + ch / 2;
        float margin = 0.04f;
        PoseStack.Pose p = pose.last();
        VertexConsumer solid = buffers.getBuffer(RenderType.entitySolid(InventoryMenu.BLOCK_ATLAS));
        int paperColor = print != null && "matte".equals(print.finish()) ? 0xFFF1EEE6 : 0xFFFFFFFF;
        // front paper (border) and back of the card
        QuadHelper.rectZ(p, solid, x0, y0, x1, y1, 0.499f, false, paper.getU0(), paper.getV0(), paper.getU1(),
                paper.getV1(), paperColor, light);
        QuadHelper.rectZ(p, solid, x0, y0, x1, y1, 0.499f, true, paper.getU0(), paper.getV0(), paper.getU1(),
                paper.getV1(), 0xFFE8E4DA, light);
        float px0 = x0 + margin, px1 = x1 - margin, py0 = y0 + margin, py1 = y1 - margin;
        if (e != null && e.state() == PhotoCache.State.READY && e.texture() != null) {
            VertexConsumer photo = buffers.getBuffer(RenderType.entitySolid(e.texture()));
            QuadHelper.rectZ(p, photo, px0, py0, px1, py1, 0.5f, false, 0, 0, 1, 1,
                    PrintTint.color(print.missingInks()), light);
        } else {
            QuadHelper.rectZ(p, solid, px0, py0, px1, py1, 0.5f, false, paper.getU0(), paper.getV0(), paper.getU1(),
                    paper.getV1(), e != null && e.state() == PhotoCache.State.GONE ? 0xFF2A2A2A : 0xFF6E6A62, light);
        }
    }
}
