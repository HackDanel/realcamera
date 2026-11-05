package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Full-screen look at a print, with the shooting data on the back. */
public class PrintViewerScreen extends Screen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final PrintData print;
    @javax.annotation.Nullable
    private final net.minecraft.client.gui.screens.Screen parent;

    public PrintViewerScreen(PrintData print) {
        this(print, null);
    }

    public PrintViewerScreen(PrintData print, @javax.annotation.Nullable net.minecraft.client.gui.screens.Screen parent) {
        super(Component.translatable("gui.realcamera.print_viewer"));
        this.print = print;
        this.parent = parent;
    }

    @Override
    public void onClose() {
        if (minecraft != null && parent != null) {
            minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xE0101010);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int border = print.finish().equals("matte") ? 0xFFF4F1EA : 0xFFFFFFFF;
        int top = 16, bottom = height - (print.caption().isEmpty() ? 44 : 56);
        if (!print.caption().isEmpty()) {
            g.drawCenteredString(font, "«" + print.caption() + "»", width / 2, height - 48, 0xFFFFE9A8);
        }
        PhotoMeta p = print.photo();
        double aspect = p.height() == 0 ? 1.5 : (double) p.width() / p.height();
        int maxW = width - 40, maxH = bottom - top;
        int w = (int) Math.min(maxW, maxH * aspect), h = (int) Math.round(w / aspect);
        int x = (width - w) / 2, y = top + (maxH - h) / 2;
        g.fill(x - 6, y - 6, x + w + 6, y + h + 6, border);
        UiDraw.photo(g, p.id(), x, y, w, h);
        PhotoMeta.Exif e = p.exif();
        String line = String.format(Locale.ROOT, "%s · %s · %s   F%.1f   ISO %d   %.0f mm", p.authorName(),
                DATE.format(Instant.ofEpochMilli(p.createdMillis())),
                ExposureScales.SHUTTER_LABEL[ExposureScales.nearestShutter(e.shutter())], e.fNumber(), e.iso(), e.focal());
        g.drawCenteredString(font, line, width / 2, height - 34, 0xFFFFFFFF);
        g.drawCenteredString(font, e.camera() + "  ·  " + e.lens(), width / 2, height - 22, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
