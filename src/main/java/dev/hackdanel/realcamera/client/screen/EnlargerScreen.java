package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity;
import dev.hackdanel.realcamera.client.photo.CardListings;
import dev.hackdanel.realcamera.menu.EnlargerMenu;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Enlarger: choose the frame, exposure and paper grade, then expose. */
public class EnlargerScreen extends AbstractContainerScreen<EnlargerMenu> {
    private Button expose;

    public EnlargerScreen(EnlargerMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        int l = leftPos, t = topPos;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> click(EnlargerBlockEntity.B_PREV)).bounds(l + 28, t + 60, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> click(EnlargerBlockEntity.B_NEXT)).bounds(l + 92, t + 60, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> click(EnlargerBlockEntity.B_DARKER)).bounds(l + 110, t + 16, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> click(EnlargerBlockEntity.B_LIGHTER)).bounds(l + 156, t + 16, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> click(EnlargerBlockEntity.B_SOFTER)).bounds(l + 110, t + 34, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> click(EnlargerBlockEntity.B_HARDER)).bounds(l + 156, t + 34, 14, 14).build());
        expose = addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.enlarger.expose"),
                b -> click(EnlargerBlockEntity.B_EXPOSE)).bounds(l + 110, t + 54, 38, 16).build());
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private UUID selectedFrame() {
        UUID roll = menu.rollId();
        if (roll == null) return null;
        List<PhotoMeta> list = CardListings.get(roll);
        if (list == null || list.isEmpty()) return null;
        return list.get(Math.max(0, Math.min(list.size() - 1, menu.selected()))).id();
    }

    @Override
    protected void containerTick() {
        expose.active = menu.progress() < 0 && menu.frameCount() > 0;
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        UiDraw.slot(g, leftPos + 8, topPos + 18);
        UiDraw.slot(g, leftPos + 8, topPos + 54);
        UiDraw.slot(g, leftPos + 152, topPos + 54);
        UiDraw.slot(g, leftPos + 8, topPos + 36);
        int px = leftPos + 28, py = topPos + 16, pw = 78, ph = 42;
        // the projected image: red-lit baseboard
        UiDraw.inset(g, px, py, pw, ph, 0xFF2A0606);
        UUID frame = selectedFrame();
        if (frame != null) UiDraw.photo(g, frame, px + 2, py + 2, pw - 4, ph - 4);
        int p = menu.progress();
        if (p >= 0) g.fill(px, py + ph + 1, px + pw * p / EnlargerBlockEntity.EXPOSE_TICKS, py + ph + 3, 0xFFC03030);
        UiDraw.playerInventory(g, leftPos, topPos, 84);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        int e = menu.exposure();
        String exp = (e > 0 ? "+" : "") + (e / 2.0f) + " EV";
        g.drawString(font, exp, 140 - font.width(exp) / 2, 19, 0xFF404040, false);
        String grade = Component.translatable("gui.realcamera.enlarger.grade", menu.grade()).getString();
        g.drawString(font, grade, 140 - font.width(grade) / 2, 37, 0xFF404040, false);
        if (menu.frameCount() > 0) {
            String n = (menu.selected() + 1) + "/" + menu.frameCount();
            g.drawString(font, n, 67 - font.width(n) / 2, 63, 0xFF404040, false);
        }
        Component light = Component.translatable(menu.dark() ? "gui.realcamera.darkroom.safe" : "gui.realcamera.darkroom.light");
        g.drawString(font, light, 8, 74, menu.dark() ? 0xFF207020 : 0xFFB02020, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
