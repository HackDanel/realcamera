package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.menu.AssemblyStationMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class AssemblyStationScreen extends AbstractContainerScreen<AssemblyStationMenu> {
    public AssemblyStationScreen(AssemblyStationMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (int i = 0; i < 6; i++) {
            UiDraw.slot(g, leftPos + 26 + (i % 3) * 18, topPos + 17 + (i / 3) * 18);
        }
        UiDraw.slot(g, leftPos + 134, topPos + 26);
        // progress arrow
        int ax = leftPos + 90, ay = topPos + 30;
        g.fill(ax, ay, ax + 30, ay + 8, 0xFF8B8B8B);
        g.fill(ax, ay, ax + Math.round(30 * menu.progress()), ay + 8, 0xFF3CB043);
        g.fill(ax + 30, ay - 3, ax + 34, ay + 11, 0xFF8B8B8B);
        UiDraw.playerInventory(g, leftPos, topPos, 84);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
