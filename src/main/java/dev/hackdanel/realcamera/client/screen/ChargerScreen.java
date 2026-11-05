package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.menu.ChargerMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class ChargerScreen extends AbstractContainerScreen<ChargerMenu> {
    public ChargerScreen(ChargerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (int i = 0; i < 4; i++) {
            UiDraw.slot(g, leftPos + 53 + i * 18, topPos + 35);
        }
        UiDraw.playerInventory(g, leftPos, topPos, 84);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        boolean powered = menu.powered();
        Component status = Component.translatable(powered ? "gui.realcamera.charger.powered" : "gui.realcamera.charger.no_power");
        g.drawString(font, status, (imageWidth - font.width(status)) / 2, 58, powered ? 0xFF2E7D32 : 0xFFB71C1C, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
