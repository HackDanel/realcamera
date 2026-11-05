package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.menu.CameraBagMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class CameraBagScreen extends AbstractContainerScreen<CameraBagMenu> {
    public CameraBagScreen(CameraBagMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        g.fill(leftPos + 20, topPos + 13, leftPos + 158, topPos + 62, 0xFF5A4632);
        for (int i = 0; i < CameraBagMenu.SLOTS; i++) {
            UiDraw.slot(g, leftPos + 26 + (i % 6) * 22, topPos + 18 + (i / 6) * 22);
        }
        UiDraw.playerInventory(g, leftPos, topPos, 84);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
