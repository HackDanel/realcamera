package dev.hackdanel.realcamera.client.screen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.editor.EditSession;
import dev.hackdanel.realcamera.processing.EditSettings;
import dev.hackdanel.realcamera.processing.PhotoEditor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/**
 * The computer's photo editor: preview on the left, tool tabs on the right (light, colour, curve, HSL,
 * detail, crop, presets), undo/redo, before/after, save as a new photo, export to the PC.
 */
public class EditorScreen extends Screen {
    private static final ResourceLocation TEXTURE = RealCamera.id("editor/preview");
    private static final String[] TABS = {"light", "colour", "curve", "hsl", "detail", "crop", "presets"};
    private static final int PANEL_W = 172, TAB_Y = 24, HIST_Y = 58, HIST_H = 22, ROW = 15;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Screen parent;
    private final EditSession session;
    private int tab;
    private int hslMode; // 0 hue, 1 saturation, 2 luminance
    private boolean dirty = true, showBefore;
    @Nullable
    private DynamicTexture texture;
    private int texW, texH;
    @Nullable
    private PhotoEditor.Result beforeResult;
    private boolean renderingBefore;
    private final int[][] histogram = new int[3][64];
    private int histMax = 1;
    private String status = "";
    private long statusUntil;
    private EditBox presetName;
    // crop dragging
    private int dragMode = -1; // 0 move, 1..4 corners
    private double dragX, dragY;
    private double[] dragStart;
    // curve dragging
    private int curvePoint = -1;

    public EditorScreen(Screen parent, EditSession session) {
        super(Component.translatable("gui.realcamera.editor"));
        this.parent = parent;
        this.session = session;
    }

    // ------------------------------------------------------------------ layout

    private int previewX() {
        return 8;
    }

    private int previewY() {
        return TAB_Y;
    }

    private int previewW() {
        return width - PANEL_W - 24;
    }

    private int previewH() {
        return height - TAB_Y - 14;
    }

    private int panelX() {
        return width - PANEL_W - 8;
    }

    private int contentY() {
        return HIST_Y + HIST_H + 4;
    }

    @Override
    protected void init() {
        rebuild();
    }

    public void showTab(int t) {
        tab = Math.max(0, Math.min(TABS.length - 1, t));
        refresh();
    }

    /** Re-reads the settings into the widgets and re-renders (after an outside change). */
    public void refresh() {
        dirty = true;
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        int x = 4;
        x = topButton(x, "back", b -> onClose());
        x = topButton(x, "undo", b -> { session.undo(); changed(true); });
        x = topButton(x, "redo", b -> { session.redo(); changed(true); });
        x = topButton(x, "before_after", b -> { showBefore = !showBefore; dirty = true; });
        x = topButton(x, "reset", b -> { session.checkpoint(); session.settings = new EditSettings(); changed(true); });
        x = topButton(x, "save", b -> session.saveCopy(this::setStatus));
        topButton(x, "export", b -> session.export(this::setStatus, p -> {}));
        int px = panelX();
        for (int i = 0; i < TABS.length; i++) {
            int row = i < 4 ? 0 : 1, col = i < 4 ? i : i - 4;
            int w = row == 0 ? PANEL_W / 4 : PANEL_W / 3;
            int t = i;
            Button b = Button.builder(Component.translatable("gui.realcamera.editor.tab." + TABS[i]), btn -> {
                tab = t;
                dirty = true;
                rebuild();
            }).bounds(px + col * w, TAB_Y + row * 16, w - 1, 15).build();
            b.active = tab != i;
            addRenderableWidget(b);
        }
        int y = contentY();
        EditSettings s = session.settings;
        switch (TABS[tab]) {
            case "light" -> {
                y = slider(y, "exposure", session.raw() ? -4 : -1.5, session.raw() ? 4 : 1.5, () -> session.settings.exposure, v -> session.settings.exposure = v, "%+.2f EV");
                y = slider(y, "contrast", -100, 100, () -> session.settings.contrast, v -> session.settings.contrast = v, null);
                y = slider(y, "highlights", -100, 100, () -> session.settings.highlights, v -> session.settings.highlights = v, null);
                y = slider(y, "shadows", -100, 100, () -> session.settings.shadows, v -> session.settings.shadows = v, null);
                y = slider(y, "whites", -100, 100, () -> session.settings.whites, v -> session.settings.whites = v, null);
                slider(y, "blacks", -100, 100, () -> session.settings.blacks, v -> session.settings.blacks = v, null);
            }
            case "colour" -> {
                y = slider(y, "temperature", -100, 100, () -> session.settings.temperature, v -> session.settings.temperature = v, null);
                y = slider(y, "tint", -100, 100, () -> session.settings.tint, v -> session.settings.tint = v, null);
                y = slider(y, "vibrance", -100, 100, () -> session.settings.vibrance, v -> session.settings.vibrance = v, null);
                y = slider(y, "saturation", -100, 100, () -> session.settings.saturation, v -> session.settings.saturation = v, null);
                addRenderableWidget(Button.builder(Component.translatable(s.monochrome
                        ? "gui.realcamera.editor.mono_on" : "gui.realcamera.editor.mono_off"), b -> {
                    session.checkpoint();
                    session.settings.monochrome = !session.settings.monochrome;
                    changed(true);
                }).bounds(px, y, PANEL_W, ROW - 1).build());
                y += ROW;
                Button prof = Button.builder(profileLabel(), b -> {
                    session.checkpoint();
                    cycleProfile();
                    changed(true);
                }).bounds(px, y, PANEL_W, ROW - 1).build();
                prof.active = session.raw() && !session.profiles().isEmpty();
                addRenderableWidget(prof);
            }
            case "curve" -> addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.editor.curve_reset"), b -> {
                session.checkpoint();
                session.settings.curve = new double[]{0, 0.25, 0.5, 0.75, 1};
                changed(false);
            }).bounds(px, y + curveRect()[3] + 6, PANEL_W, ROW - 1).build());
            case "hsl" -> {
                for (int m = 0; m < 3; m++) {
                    int mode = m;
                    Button b = Button.builder(Component.translatable("gui.realcamera.editor.hsl." + m), btn -> {
                        hslMode = mode;
                        rebuild();
                    }).bounds(px + m * (PANEL_W / 3), y, PANEL_W / 3 - 1, ROW - 1).build();
                    b.active = hslMode != m;
                    addRenderableWidget(b);
                }
                y += ROW;
                for (int i = 0; i < 8; i++) {
                    int band = i;
                    y = slider(y, "color." + EditSettings.HSL_NAMES[i], -100, 100, () -> arr()[band], v -> arr()[band] = v, null);
                }
            }
            case "detail" -> {
                y = slider(y, "sharpen", 0, 100, () -> session.settings.sharpen, v -> session.settings.sharpen = v, null);
                y = slider(y, "noise", 0, 100, () -> session.settings.noiseReduction, v -> session.settings.noiseReduction = v, null);
                y = slider(y, "vignette", -100, 100, () -> session.settings.vignette, v -> session.settings.vignette = v, null);
                y = slider(y, "grain", 0, 100, () -> session.settings.grain, v -> session.settings.grain = v, null);
                addRenderableWidget(Button.builder(Component.translatable(s.lensCorrection
                        ? "gui.realcamera.editor.lens_on" : "gui.realcamera.editor.lens_off"), b -> {
                    session.checkpoint();
                    session.settings.lensCorrection = !session.settings.lensCorrection;
                    changed(true);
                }).bounds(px, y, PANEL_W, ROW - 1).build());
            }
            case "crop" -> {
                addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.editor.aspect",
                        Component.translatable("gui.realcamera.editor.aspect." + EditSettings.ASPECTS[s.aspect])), b -> {
                    session.checkpoint();
                    session.settings.aspect = (session.settings.aspect + 1) % EditSettings.ASPECTS.length;
                    fitCropToAspect();
                    changed(true);
                }).bounds(px, y, PANEL_W, ROW - 1).build());
                y += ROW;
                addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.editor.rotate"), b -> {
                    session.checkpoint();
                    EditSettings e = session.settings;
                    e.rotate = (e.rotate + 1) % 4;
                    // the crop rectangle turns with the picture
                    double nx = 1 - e.cropY - e.cropH, ny = e.cropX, nw = e.cropH, nh = e.cropW;
                    e.cropX = nx;
                    e.cropY = ny;
                    e.cropW = nw;
                    e.cropH = nh;
                    fitCropToAspect();
                    changed(true);
                }).bounds(px, y, PANEL_W, ROW - 1).build());
                y += ROW;
                y = slider(y, "straighten", -15, 15, () -> session.settings.straighten, v -> session.settings.straighten = v, "%+.1f°");
                addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.editor.crop_reset"), b -> {
                    session.checkpoint();
                    EditSettings e = session.settings;
                    e.cropX = e.cropY = 0;
                    e.cropW = e.cropH = 1;
                    e.straighten = 0;
                    fitCropToAspect();
                    changed(true);
                }).bounds(px, y, PANEL_W, ROW - 1).build());
            }
            case "presets" -> {
                presetName = new EditBox(font, px, y, PANEL_W - 52, ROW - 1, Component.translatable("gui.realcamera.editor.preset_name"));
                presetName.setMaxLength(32);
                presetName.setHint(Component.translatable("gui.realcamera.editor.preset_name"));
                addRenderableWidget(presetName);
                addRenderableWidget(Button.builder(Component.translatable("gui.realcamera.editor.preset_save"),
                        b -> savePreset()).bounds(px + PANEL_W - 50, y, 50, ROW - 1).build());
                y += ROW + 2;
                for (String name : presets()) {
                    if (y > height - 30) break;
                    addRenderableWidget(Button.builder(Component.literal(name), b -> applyPreset(name))
                            .bounds(px, y, PANEL_W, ROW - 1).build());
                    y += ROW;
                }
            }
            default -> {}
        }
    }

    private double[] arr() {
        EditSettings s = session.settings;
        return hslMode == 0 ? s.hslHue : hslMode == 1 ? s.hslSat : s.hslLum;
    }

    private int topButton(int x, String key, Button.OnPress press) {
        Component label = Component.translatable("gui.realcamera.editor." + key);
        int w = font.width(label) + 8;
        addRenderableWidget(Button.builder(label, press).bounds(x, 4, w, 16).build());
        return x + w + 2;
    }

    private int slider(int y, String key, double min, double max, DoubleSupplier get, DoubleConsumer set, @Nullable String fmt) {
        addRenderableWidget(new Slider(panelX(), y, PANEL_W, ROW - 1, key, min, max, get, set, fmt));
        return y + ROW;
    }

    private Component profileLabel() {
        String id = session.settings.profile;
        String name = Component.translatable("gui.realcamera.editor.profile_as_shot").getString();
        for (EditSession.Profile p : session.profiles()) {
            if (p.id().equals(id)) name = p.name();
        }
        return Component.translatable("gui.realcamera.editor.profile", name);
    }

    private void cycleProfile() {
        List<EditSession.Profile> list = session.profiles();
        String cur = session.settings.profile;
        int i = -1;
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).id().equals(cur)) i = k;
        }
        session.settings.profile = i + 1 >= list.size() ? "" : list.get(i + 1).id();
    }

    private void changed(boolean rebuildWidgets) {
        dirty = true;
        if (rebuildWidgets) rebuild();
    }

    private void setStatus(String key) {
        status = Component.translatable(key).getString();
        statusUntil = System.currentTimeMillis() + 4000;
    }

    // ------------------------------------------------------------------ crop helpers

    /** Width/height of the frame after rotation, for aspect-locked crops. */
    private double frameAspect() {
        PhotoEditor.Source src = session.preview();
        if (src == null) return 1.5;
        return session.settings.rotate % 2 == 0 ? (double) src.w() / src.h() : (double) src.h() / src.w();
    }

    private double targetAspect() {
        EditSettings s = session.settings;
        if (s.aspect == 0) {
            PhotoEditor.Source src = session.preview();
            return src == null ? 1.5 : frameAspect() >= 1 ? Math.max(src.w(), src.h()) / (double) Math.min(src.w(), src.h())
                    : Math.min(src.w(), src.h()) / (double) Math.max(src.w(), src.h());
        }
        double r = EditSettings.ASPECT_RATIO[s.aspect];
        // follow the picture's orientation: a 3:2 crop of a portrait frame is 2:3
        return r > 0 && frameAspect() < 1 && r > 1 ? 1 / r : r;
    }

    private void fitCropToAspect() {
        EditSettings s = session.settings;
        double ratio = targetAspect();
        if (s.aspect == 1 || ratio <= 0) return; // free
        double fa = frameAspect();
        // crop size in fractions: (cw * fa) / ch = ratio
        double cx = s.cropX + s.cropW / 2, cy = s.cropY + s.cropH / 2;
        double cw = s.cropW, ch = cw * fa / ratio;
        if (ch > 1) {
            ch = 1;
            cw = ratio / fa;
        }
        if (cw > 1) {
            cw = 1;
            ch = fa / ratio;
        }
        s.cropW = cw;
        s.cropH = ch;
        s.cropX = clamp(cx - cw / 2, 0, 1 - cw);
        s.cropY = clamp(cy - ch / 2, 0, 1 - ch);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Where the preview image is drawn: x, y, w, h. */
    private int[] imageRect() {
        if (texture == null || texW == 0) return new int[]{previewX(), previewY(), previewW(), previewH()};
        double s = Math.min((double) previewW() / texW, (double) previewH() / texH);
        int w = (int) Math.round(texW * s), h = (int) Math.round(texH * s);
        return new int[]{previewX() + (previewW() - w) / 2, previewY() + (previewH() - h) / 2, w, h};
    }

    private boolean cropTab() {
        return TABS[tab].equals("crop");
    }

    // ------------------------------------------------------------------ rendering the preview

    @Override
    public void tick() {
        if (session.state() != EditSession.State.READY) return;
        if (showBefore && beforeResult == null && !renderingBefore && !session.busy()) {
            renderingBefore = true;
            session.renderPreview(new EditSettings(), r -> {
                beforeResult = r;
                renderingBefore = false;
                upload(r);
            });
            return;
        }
        if (showBefore) {
            if (beforeResult != null && !session.busy() && dirty) {
                upload(beforeResult);
                dirty = false;
            }
            return;
        }
        if (dirty && !session.busy()) {
            dirty = false;
            EditSettings s = session.settings.copy();
            if (cropTab()) {
                // show the whole frame with the crop rectangle on top
                s.cropX = s.cropY = 0;
                s.cropW = s.cropH = 1;
            }
            session.renderPreview(s, this::upload);
        }
    }

    /** The pixels currently in the texture, before any crop dimming. */
    @Nullable
    private int[] shownRgb;
    private String dimKey = "";

    /**
     * In the crop tab the picture outside the crop rectangle is shown darker. Done on the pixels (not with
     * blending) so it works under shader packs; re-applied only when the rectangle changes.
     */
    private void dimOutsideCrop(boolean on) {
        if (texture == null || shownRgb == null || shownRgb.length != texW * texH) return;
        EditSettings s = session.settings;
        String key = on ? s.cropX + "," + s.cropY + "," + s.cropW + "," + s.cropH : "";
        if (key.equals(dimKey)) return;
        dimKey = key;
        int x0 = (int) Math.round(s.cropX * texW), y0 = (int) Math.round(s.cropY * texH);
        int x1 = (int) Math.round((s.cropX + s.cropW) * texW), y1 = (int) Math.round((s.cropY + s.cropH) * texH);
        NativeImage img = texture.getPixels();
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < texW; x++) {
                int p = shownRgb[y * texW + x];
                int red = (p >> 16) & 255, gr = (p >> 8) & 255, b = p & 255;
                if (on && (x < x0 || x >= x1 || y < y0 || y >= y1)) {
                    red = red * 2 / 5;
                    gr = gr * 2 / 5;
                    b = b * 2 / 5;
                }
                img.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (gr << 8) | red);
            }
        }
        texture.upload();
    }

    private void upload(PhotoEditor.Result r) {
        if (texture == null || texW != r.w() || texH != r.h()) {
            if (texture != null) minecraft.getTextureManager().release(TEXTURE);
            texture = new DynamicTexture(r.w(), r.h(), false);
            texture.setFilter(true, false);
            minecraft.getTextureManager().register(TEXTURE, texture);
            texW = r.w();
            texH = r.h();
        }
        NativeImage img = texture.getPixels();
        int[] rgb = r.rgb();
        shownRgb = rgb;
        dimKey = "";
        for (int i = 0; i < 3; i++) java.util.Arrays.fill(histogram[i], 0);
        for (int y = 0; y < r.h(); y++) {
            for (int x = 0; x < r.w(); x++) {
                int p = rgb[y * r.w() + x];
                int red = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                img.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | red);
                histogram[0][red >> 2]++;
                histogram[1][g >> 2]++;
                histogram[2][b >> 2]++;
            }
        }
        histMax = 1;
        for (int[] ch : histogram) {
            for (int i = 1; i < 63; i++) histMax = Math.max(histMax, ch[i]);
        }
        texture.upload();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.fill(previewX() - 1, previewY() - 1, previewX() + previewW() + 1, previewY() + previewH() + 1, 0xFF1A1A1A);
        if (session.state() == EditSession.State.LOADING) {
            g.drawCenteredString(font, Component.translatable("gui.realcamera.editor.loading"),
                    previewX() + previewW() / 2, previewY() + previewH() / 2, 0xFFAAAAAA);
        } else if (session.state() == EditSession.State.FAILED) {
            g.drawCenteredString(font, Component.translatable("gui.realcamera.photo_removed"),
                    previewX() + previewW() / 2, previewY() + previewH() / 2, 0xFFFF6060);
        } else if (texture != null) {
            int[] r = imageRect();
            boolean crop = cropTab() && !showBefore;
            dimOutsideCrop(crop);
            g.blit(TEXTURE, r[0], r[1], r[2], r[3], 0, 0, texW, texH, texW, texH);
            if (crop) drawCrop(g, r);
            if (showBefore) {
                g.drawString(font, Component.translatable("gui.realcamera.editor.before"), r[0] + 4, r[1] + 4, 0xFFFFFF00);
            }
        }
        if (session.busy()) {
            g.drawString(font, "…", previewX() + previewW() - 10, previewY() + 2, 0xFFFFFFFF);
        }
        // info line
        g.drawString(font, session.sourceLabel() + "   " + session.meta.exif().camera(), previewX(), height - 12, 0xFFA0A0A0);
        if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
            g.drawString(font, status, panelX(), height - 12, 0xFF80FF80);
        }
        if (TABS[tab].equals("curve")) drawCurve(g);
        drawHistogram(g, panelX(), HIST_Y, PANEL_W, HIST_H);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF2B2B2E);
    }

    private void drawCrop(GuiGraphics g, int[] r) {
        EditSettings s = session.settings;
        int x0 = r[0] + (int) Math.round(s.cropX * r[2]), y0 = r[1] + (int) Math.round(s.cropY * r[3]);
        int x1 = r[0] + (int) Math.round((s.cropX + s.cropW) * r[2]), y1 = r[1] + (int) Math.round((s.cropY + s.cropH) * r[3]);
        g.renderOutline(x0, y0, x1 - x0, y1 - y0, 0xFFFFFFFF);
        // rule of thirds
        for (int i = 1; i < 3; i++) {
            int gx = x0 + (x1 - x0) * i / 3, gy = y0 + (y1 - y0) * i / 3;
            g.fill(gx, y0, gx + 1, y1, 0x60FFFFFF);
            g.fill(x0, gy, x1, gy + 1, 0x60FFFFFF);
        }
        for (int[] c : new int[][]{{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
            g.fill(c[0] - 3, c[1] - 3, c[0] + 3, c[1] + 3, 0xFFFFFFFF);
        }
    }

    private int[] curveRect() {
        int size = Math.min(PANEL_W - 12, height - contentY() - 40);
        return new int[]{panelX() + (PANEL_W - size) / 2, contentY() + 2, size, size};
    }

    private void drawCurve(GuiGraphics g) {
        int[] r = curveRect();
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF161616);
        for (int i = 1; i < 4; i++) {
            g.fill(r[0] + r[2] * i / 4, r[1], r[0] + r[2] * i / 4 + 1, r[1] + r[3], 0xFF333333);
            g.fill(r[0], r[1] + r[3] * i / 4, r[0] + r[2], r[1] + r[3] * i / 4 + 1, 0xFF333333);
        }
        double[] c = session.settings.curve;
        int prevY = -1;
        for (int x = 0; x < r[2]; x++) {
            double v = clamp(PhotoEditor.spline(c, x / (double) (r[2] - 1)), 0, 1);
            int y = r[1] + r[3] - 1 - (int) Math.round(v * (r[3] - 1));
            if (prevY >= 0) g.fill(r[0] + x, Math.min(y, prevY), r[0] + x + 1, Math.max(y, prevY) + 1, 0xFFE0E0E0);
            prevY = y;
        }
        for (int i = 0; i < 5; i++) {
            int px = r[0] + (int) Math.round(i * 0.25 * (r[2] - 1));
            int py = r[1] + r[3] - 1 - (int) Math.round(c[i] * (r[3] - 1));
            g.fill(px - 2, py - 2, px + 3, py + 3, curvePoint == i ? 0xFFFFD040 : 0xFFFFFFFF);
        }
    }

    private void drawHistogram(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF121212);
        int[] colors = {0x90FF4040, 0x9040FF40, 0x904080FF};
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < 64; i++) {
                int bh = (int) Math.min(h, (long) histogram[c][i] * h / histMax);
                int bx = x + i * w / 64, bw = Math.max(1, w / 64);
                g.fill(bx, y + h - bh, bx + bw, y + h, colors[c]);
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0 || texture == null) return false;
        if (TABS[tab].equals("curve")) {
            int[] r = curveRect();
            for (int i = 0; i < 5; i++) {
                double px = r[0] + i * 0.25 * (r[2] - 1);
                double py = r[1] + r[3] - 1 - session.settings.curve[i] * (r[3] - 1);
                if (Math.abs(mx - px) < 6 && Math.abs(my - py) < 6) {
                    session.checkpoint();
                    curvePoint = i;
                    return true;
                }
            }
        }
        if (cropTab()) {
            int[] r = imageRect();
            EditSettings s = session.settings;
            double fx = (mx - r[0]) / r[2], fy = (my - r[1]) / r[3];
            double tol = 8.0 / Math.min(r[2], r[3]);
            double[][] corners = {{s.cropX, s.cropY}, {s.cropX + s.cropW, s.cropY}, {s.cropX, s.cropY + s.cropH},
                    {s.cropX + s.cropW, s.cropY + s.cropH}};
            for (int i = 0; i < 4; i++) {
                if (Math.abs(fx - corners[i][0]) < tol && Math.abs(fy - corners[i][1]) < tol) {
                    startDrag(i + 1, fx, fy);
                    return true;
                }
            }
            if (fx > s.cropX && fx < s.cropX + s.cropW && fy > s.cropY && fy < s.cropY + s.cropH) {
                startDrag(0, fx, fy);
                return true;
            }
        }
        return false;
    }

    private void startDrag(int mode, double fx, double fy) {
        session.checkpoint();
        dragMode = mode;
        dragX = fx;
        dragY = fy;
        EditSettings s = session.settings;
        dragStart = new double[]{s.cropX, s.cropY, s.cropW, s.cropH};
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (curvePoint >= 0) {
            int[] r = curveRect();
            double v = clamp((r[1] + r[3] - 1 - my) / (r[3] - 1), 0, 1);
            session.settings.curve[curvePoint] = v;
            dirty = true;
            return true;
        }
        if (dragMode >= 0) {
            int[] r = imageRect();
            double fx = (mx - r[0]) / r[2], fy = (my - r[1]) / r[3];
            dragCrop(fx - dragX, fy - dragY);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    private void dragCrop(double ddx, double ddy) {
        EditSettings s = session.settings;
        double x = dragStart[0], y = dragStart[1], w = dragStart[2], h = dragStart[3];
        double min = 0.05;
        if (dragMode == 0) {
            s.cropX = clamp(x + ddx, 0, 1 - w);
            s.cropY = clamp(y + ddy, 0, 1 - h);
            return;
        }
        // corner: the opposite corner stays put
        boolean left = dragMode == 1 || dragMode == 3, top = dragMode == 1 || dragMode == 2;
        double ax = left ? x + w : x, ay = top ? y + h : y; // anchor
        double cx = clamp((left ? x : x + w) + ddx, 0, 1), cy = clamp((top ? y : y + h) + ddy, 0, 1);
        double nw = Math.max(min, Math.abs(cx - ax)), nh = Math.max(min, Math.abs(cy - ay));
        double ratio = targetAspect();
        if (s.aspect != 1 && ratio > 0) {
            double fa = frameAspect();
            // keep (nw * fa) / nh == ratio, limited by the room towards the frame edges
            double roomW = left ? ax : 1 - ax, roomH = top ? ay : 1 - ay;
            nh = Math.min(Math.max(nh, nw * fa / ratio), roomH);
            nw = nh * ratio / fa;
            if (nw > roomW) {
                nw = roomW;
                nh = nw * fa / ratio;
            }
        }
        s.cropW = nw;
        s.cropH = nh;
        s.cropX = left ? ax - nw : ax;
        s.cropY = top ? ay - nh : ay;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (curvePoint >= 0) {
            curvePoint = -1;
            return true;
        }
        if (dragMode >= 0) {
            dragMode = -1;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && key == GLFW.GLFW_KEY_Z) {
            if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) session.redo(); else session.undo();
            changed(true);
            return true;
        }
        if (ctrl && key == GLFW.GLFW_KEY_Y) {
            session.redo();
            changed(true);
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSLASH) {
            showBefore = !showBefore;
            dirty = true;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        if (texture != null) {
            minecraft.getTextureManager().release(TEXTURE);
            texture = null;
        }
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ presets

    private Path presetDir() {
        return minecraft.gameDirectory.toPath().resolve("config").resolve("realcamera").resolve("presets");
    }

    private List<String> presets() {
        List<String> out = new ArrayList<>();
        Path dir = presetDir();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(p -> {
                String n = p.getFileName().toString();
                out.add(n.substring(0, n.length() - 5));
            });
        } catch (IOException ignored) {
        }
        return out;
    }

    private void savePreset() {
        String name = presetName == null ? "" : presetName.getValue().strip().replaceAll("[^\\p{L}\\p{N} _-]", "");
        if (name.isEmpty()) return;
        try {
            Files.createDirectories(presetDir());
            Files.writeString(presetDir().resolve(name + ".json"), GSON.toJson(session.settings));
            setStatus("gui.realcamera.editor.preset_saved");
        } catch (IOException e) {
            setStatus("gui.realcamera.editor.failed");
        }
        rebuild();
    }

    private void applyPreset(String name) {
        try {
            EditSettings p = GSON.fromJson(Files.readString(presetDir().resolve(name + ".json")), EditSettings.class);
            if (p == null) return;
            session.checkpoint();
            session.settings.applyPreset(p);
            changed(true);
        } catch (IOException | RuntimeException e) {
            setStatus("gui.realcamera.editor.failed");
        }
    }

    // ------------------------------------------------------------------ slider

    private final class Slider extends AbstractSliderButton {
        private final String key;
        private final double min, max;
        private final DoubleConsumer set;
        @Nullable
        private final String fmt;

        Slider(int x, int y, int w, int h, String key, double min, double max, DoubleSupplier get, DoubleConsumer set,
               @Nullable String fmt) {
            super(x, y, w, h, Component.empty(), (clamp(get.getAsDouble(), min, max) - min) / (max - min));
            this.key = key;
            this.min = min;
            this.max = max;
            this.set = set;
            this.fmt = fmt;
            updateMessage();
        }

        private double current() {
            double v = min + value * (max - min);
            if (fmt == null) v = Math.round(v);
            else v = Math.round(v * 20) / 20.0;
            return Math.abs(v) < 1e-9 ? 0 : v;
        }

        @Override
        protected void updateMessage() {
            double v = current();
            String num = fmt == null ? String.format(Locale.ROOT, "%+d", (int) v) : String.format(Locale.ROOT, fmt, v);
            setMessage(Component.translatable("gui.realcamera.editor." + key).append(": " + num));
        }

        @Override
        protected void applyValue() {
            set.accept(current());
            dirty = true;
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            session.checkpoint();
            super.onClick(mouseX, mouseY);
        }
    }
}
