package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.menu.CameraPartsMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class CameraPartsScreen extends AbstractContainerScreen<CameraPartsMenu> {
    private static final String[] LABELS = {"gui.realcamera.slot.lens", "gui.realcamera.slot.adapter",
            "gui.realcamera.slot.tc", "gui.realcamera.slot.battery", "gui.realcamera.slot.card"};

    public CameraPartsScreen(CameraPartsMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageHeight = 166;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (int i = 0; i < CameraPartsMenu.PART_SLOTS; i++) {
            UiDraw.slot(g, leftPos + CameraPartsMenu.SLOT_X[i], topPos + CameraPartsMenu.SLOT_Y);
        }
        UiDraw.playerInventory(g, leftPos, topPos, 84);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        for (int i = 0; i < CameraPartsMenu.PART_SLOTS; i++) {
            Component label = Component.translatable(LABELS[i]);
            int cx = CameraPartsMenu.SLOT_X[i] + 8;
            g.pose().pushPose();
            g.pose().translate(cx, CameraPartsMenu.SLOT_Y + 21, 0);
            g.pose().scale(0.75f, 0.75f, 1);
            g.drawString(font, label, -font.width(label) / 2, 0, 0xFF404040, false);
            g.pose().popPose();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
