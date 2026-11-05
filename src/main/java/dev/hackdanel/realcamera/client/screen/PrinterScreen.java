package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.blockentity.PrinterBlockEntity;
import dev.hackdanel.realcamera.client.photo.CardListings;
import dev.hackdanel.realcamera.menu.PrinterMenu;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Printer: pick a photo from the inserted card, then print on the loaded paper. */
public class PrinterScreen extends AbstractContainerScreen<PrinterMenu> {
    private static final int[] INK_COLOR = {0xFF00B7EB, 0xFFFF00A0, 0xFFFFE600, 0xFF303030};

    public PrinterScreen(PrinterMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("<"), b -> click(PrinterBlockEntity.BUTTON_PREV))
                .bounds(leftPos + 30, topPos + 58, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> click(PrinterBlockEntity.BUTTON_NEXT))
                .bounds(leftPos + 96, topPos + 58, 14, 14).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.print"), b -> click(PrinterBlockEntity.BUTTON_PRINT))
                .bounds(leftPos + 46, topPos + 58, 48, 14).build());
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private UUID selectedPhoto() {
        UUID card = menu.cardId();
        if (card == null) return null;
        List<PhotoMeta> list = CardListings.get(card);
        if (list == null || list.isEmpty()) return null;
        int i = Math.max(0, Math.min(list.size() - 1, menu.selected()));
        return list.get(i).id();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        UiDraw.slot(g, leftPos + 8, topPos + 18);
        UiDraw.slot(g, leftPos + 8, topPos + 54);
        for (int i = 0; i < 4; i++) {
            int sx = leftPos + 116 + (i % 2) * 18, sy = topPos + 18 + (i / 2) * 18;
            UiDraw.slot(g, sx, sy);
            g.fill(sx, sy + 15, sx + 16, sy + 16, INK_COLOR[i]);
        }
        UiDraw.slot(g, leftPos + 152, topPos + 54);
        // preview
        int px = leftPos + 30, py = topPos + 14, pw = 80, ph = 42;
        UiDraw.inset(g, px, py, pw, ph, 0xFF101010);
        UUID photo = selectedPhoto();
        if (photo != null) {
            UiDraw.photo(g, photo, px, py, pw, ph);
        } else {
            Component none = Component.translatable("gui.realcamera.printer.no_photos");
            g.drawString(font, none, px + (pw - font.width(none)) / 2, py + ph / 2 - 4, 0xFF888888, false);
        }
        // progress bar
        int progress = menu.progress();
        if (progress >= 0) {
            g.fill(px, py + ph + 1, px + pw * progress / PrinterBlockEntity.PRINT_TICKS, py + ph + 3, 0xFF3CB043);
        }
        UiDraw.playerInventory(g, leftPos, topPos, 84);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        if (menu.photoCount() > 0) {
            String n = (menu.selected() + 1) + "/" + menu.photoCount();
            g.drawString(font, n, 112 - font.width(n), 6, 0xFF404040, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
