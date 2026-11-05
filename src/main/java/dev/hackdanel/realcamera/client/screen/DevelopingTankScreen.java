package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity;
import dev.hackdanel.realcamera.menu.DevelopingTankMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Developing tank: roll, chemistry, optional changing bag; push/pull and start. */
public class DevelopingTankScreen extends AbstractContainerScreen<DevelopingTankMenu> {
    private Button pushButton, startButton;

    public DevelopingTankScreen(DevelopingTankMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        pushButton = addRenderableWidget(Button.builder(pushLabel(), b -> click(DevelopingTankBlockEntity.BUTTON_PUSH))
                .bounds(leftPos + 50, topPos + 24, 74, 16).build());
        startButton = addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.tank.start"),
                b -> click(DevelopingTankBlockEntity.BUTTON_START)).bounds(leftPos + 50, topPos + 44, 74, 16).build());
    }

    private Component pushLabel() {
        int p = menu.push();
        return Component.translatable(p == 0 ? "gui.realcamera.tank.normal" : "gui.realcamera.tank.push",
                (p > 0 ? "+" : "") + p);
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void containerTick() {
        pushButton.setMessage(pushLabel());
        boolean idle = menu.progress() < 0;
        pushButton.active = idle;
        startButton.active = idle;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        UiDraw.slot(g, leftPos + 26, topPos + 26);
        UiDraw.slot(g, leftPos + 26, topPos + 50);
        UiDraw.slot(g, leftPos + 8, topPos + 26);
        UiDraw.slot(g, leftPos + 134, topPos + 38);
        int p = menu.progress();
        if (p >= 0) {
            int w = 74 * p / DevelopingTankBlockEntity.DEVELOP_TICKS;
            g.fill(leftPos + 50, topPos + 64, leftPos + 50 + w, topPos + 67, 0xFF3CB043);
        }
        UiDraw.playerInventory(g, leftPos, topPos, 84);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        Component light = Component.translatable(menu.dark() ? "gui.realcamera.darkroom.dark" : "gui.realcamera.darkroom.light");
        g.drawString(font, light, 8, 70, menu.dark() ? 0xFF207020 : 0xFFB02020, false);
        int p = menu.progress();
        if (p >= 0) {
            int left = (DevelopingTankBlockEntity.DEVELOP_TICKS - p) / 20;
            g.drawString(font, left / 60 + ":" + String.format("%02d", left % 60), 128, 62, 0xFF404040, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
