package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.client.photo.PhotoCache;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Vanilla-looking panels and slots drawn with fills (no texture files), plus photo blitting. */
public final class UiDraw {
    private UiDraw() {}

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFFC6C6C6);
        g.fill(x, y, x + w - 1, y + 1, 0xFFFFFFFF);
        g.fill(x, y, x + 1, y + h - 1, 0xFFFFFFFF);
        g.fill(x + 1, y + h - 1, x + w, y + h, 0xFF555555);
        g.fill(x + w - 1, y + 1, x + w, y + h, 0xFF555555);
        g.fill(x - 1, y + 1, x, y + h - 1, 0xFF000000);
        g.fill(x + w, y + 1, x + w + 1, y + h - 1, 0xFF000000);
        g.fill(x + 1, y - 1, x + w - 1, y, 0xFF000000);
        g.fill(x + 1, y + h, x + w - 1, y + h + 1, 0xFF000000);
    }

    /** Slot background at the slot's item position (16x16 inside an 18x18 inset). */
    public static void slot(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B8B8B);
        g.fill(x - 1, y - 1, x + 16, y, 0xFF373737);
        g.fill(x - 1, y - 1, x, y + 16, 0xFF373737);
        g.fill(x, y + 16, x + 17, y + 17, 0xFFFFFFFF);
        g.fill(x + 16, y, x + 17, y + 17, 0xFFFFFFFF);
    }

    public static void inset(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF373737);
        g.fill(x, y, x + w + 1, y + h + 1, 0xFFFFFFFF);
        g.fill(x, y, x + w, y + h, color);
    }

    public static void playerInventory(GuiGraphics g, int left, int top, int invTop) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slot(g, left + 8 + col * 18, top + invTop + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            slot(g, left + 8 + col * 18, top + invTop + 58);
        }
    }

    /**
     * Draws a photo fitted inside the box (letterboxed). Shows a "developing" or "removed"
     * placeholder while the picture is not available.
     */
    public static void photo(GuiGraphics g, UUID id, int x, int y, int w, int h) {
        PhotoCache.Entry e = PhotoCache.get(id);
        if (e.state() == PhotoCache.State.READY && e.texture() != null) {
            double s = Math.min((double) w / e.width(), (double) h / e.height());
            int dw = (int) Math.round(e.width() * s), dh = (int) Math.round(e.height() * s);
            int dx = x + (w - dw) / 2, dy = y + (h - dh) / 2;
            g.blit(e.texture(), dx, dy, dw, dh, 0, 0, e.width(), e.height(), e.width(), e.height());
            return;
        }
        g.fill(x, y, x + w, y + h, 0xFF202020);
        Component label = Component.translatable(e.state() == PhotoCache.State.GONE
                ? "gui.realcamera.photo_removed" : "gui.realcamera.photo_loading");
        var font = Minecraft.getInstance().font;
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + h / 2 - 4, 0xFFAAAAAA, false);
    }
}
