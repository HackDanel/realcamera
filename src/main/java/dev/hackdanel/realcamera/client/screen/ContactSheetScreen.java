package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.client.photo.CardListings;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A developed roll held against the light: all frames as a contact sheet; click one to look closer. */
public class ContactSheetScreen extends Screen {
    private static final int COLS = 6, TW = 60, TH = 40, GAP = 6;
    private final UUID roll;

    public ContactSheetScreen(UUID roll, Component title) {
        super(title);
        this.roll = roll;
    }

    private List<PhotoMeta> frames() {
        List<PhotoMeta> l = CardListings.get(roll);
        return l == null ? List.of() : l;
    }

    private int left() {
        return (width - (COLS * (TW + GAP) - GAP)) / 2;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xF0141210);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 10, 0xFFFFFFFF);
        List<PhotoMeta> list = frames();
        if (list.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gui.realcamera.film.empty"), width / 2, height / 2, 0xFFAAAAAA);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            int x = left() + (i % COLS) * (TW + GAP), y = 26 + (i / COLS) * (TH + 12);
            // film edge with sprocket holes
            g.fill(x - 2, y - 4, x + TW + 2, y + TH + 4, 0xFF2B1A0E);
            for (int k = 0; k < 6; k++) {
                g.fill(x + 3 + k * 10, y - 3, x + 7 + k * 10, y - 1, 0xFF0C0806);
                g.fill(x + 3 + k * 10, y + TH + 1, x + 7 + k * 10, y + TH + 3, 0xFF0C0806);
            }
            UiDraw.photo(g, list.get(i).id(), x, y, TW, TH);
            g.drawString(font, String.valueOf(i + 1), x, y + TH + 4, 0xFFE0A040, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        List<PhotoMeta> list = frames();
        for (int i = 0; i < list.size(); i++) {
            int x = left() + (i % COLS) * (TW + GAP), y = 26 + (i / COLS) * (TH + 12);
            if (mx >= x && mx < x + TW && my >= y && my < y + TH) {
                minecraft.setScreen(new PrintViewerScreen(new PrintData(list.get(i), "10x15", "glossy", 0, ""), this));
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
