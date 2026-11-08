package dev.hackdanel.realcamera.client.hud;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.client.camera.SubjectDetector;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.optics.Exposure;
import dev.hackdanel.realcamera.optics.ExposureScales;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;

/**
 * Viewfinder overlay. Electronic viewfinders (mirrorless) show the exposure preview and full data;
 * optical ones (DSLR) show the scene as is with a green data strip; rangefinders show bright frame
 * lines inside a wider view and a focusing patch.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class ViewfinderHud {
    public static final ResourceLocation LAYER = RealCamera.id("viewfinder");
    private static final Set<ResourceLocation> KEEP = Set.of(VanillaGuiLayers.CHAT, VanillaGuiLayers.OVERLAY_MESSAGE,
            VanillaGuiLayers.SUBTITLE_OVERLAY, VanillaGuiLayers.DEBUG_OVERLAY, LAYER);
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFF9A9A9A, YELLOW = 0xFFFFD84A, RED = 0xFFFF5A4A,
            GREEN = 0xFF63E26B, ORANGE = 0xFFFF9F2E, OVF_GREEN = 0xFF7CFF6A, PEAK = 0xFFFF3030;

    private ViewfinderHud() {}

    @EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent
        static void register(RegisterGuiLayersEvent event) {
            event.registerAboveAll(LAYER, ViewfinderHud::render);
        }
    }

    @SubscribeEvent
    static void hideVanilla(RenderGuiLayerEvent.Pre event) {
        if (Viewfinder.isActive() && !KEEP.contains(event.getName())) {
            event.setCanceled(true);
        }
    }

    /** Frame rectangle on screen (GUI coordinates). */
    record Frame(int x, int y, int w, int h) {
        int cx() {
            return x + w / 2;
        }

        int cy() {
            return y + h / 2;
        }

        /** Frame coordinates (-1..1, y up) to screen. */
        int sx(double u) {
            return (int) Math.round(cx() + u * w / 2.0);
        }

        int sy(double v) {
            return (int) Math.round(cy() - v * h / 2.0);
        }
    }

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        // Mod GUI layers still run with hideGui; never draw over the frame being captured.
        if (!Viewfinder.isActive() || PhotoCapture.capturing() || mc.options.hideGui) {
            return;
        }
        int sw = g.guiWidth(), sh = g.guiHeight();
        double sensorAspect = Viewfinder.sensor().aspect();
        int fw, fh;
        if ((double) sw / sh >= sensorAspect) {
            fh = sh;
            fw = (int) Math.round(sh * sensorAspect);
        } else {
            fw = sw;
            fh = (int) Math.round(sw / sensorAspect);
        }
        Frame outer = new Frame((sw - fw) / 2, (sh - fh) / 2, fw, fh);
        String type = Viewfinder.body().viewfinder();
        boolean evf = "evf".equals(type) || "none".equals(type) || "hybrid".equals(type);
        boolean rangefinder = "rangefinder".equals(type);
        // the photo's frame: equal to the visible area, or smaller inside a rangefinder's wider view
        double scale = rangefinder ? 1 / Viewfinder.viewfinderOverscan() : 1;
        if (Viewfinder.magnified()) scale = 1;
        Frame frame = new Frame((int) Math.round(outer.cx() - fw * scale / 2), (int) Math.round(outer.cy() - fh * scale / 2),
                (int) Math.round(fw * scale), (int) Math.round(fh * scale));
        Exposure.Result exp = Viewfinder.exposure();
        CameraSettings s = Viewfinder.settings();
        Font font = mc.font;

        g.flush();
        if (evf && exp != null && !dev.hackdanel.realcamera.client.render.EvfPreview.applied()) {
            // fallback when the screen does not already show the photo's look (film body, shader not loaded)
            exposurePreview(g, outer.x(), outer.y(), outer.w(), outer.h(), previewEv(exp, s));
        }
        // mask outside the viewfinder area
        g.fill(0, 0, sw, outer.y(), 0xFF000000);
        g.fill(0, outer.y() + outer.h(), sw, sh, 0xFF000000);
        g.fill(0, outer.y(), outer.x(), outer.y() + outer.h(), 0xFF000000);
        g.fill(outer.x() + outer.w(), outer.y(), sw, outer.y() + outer.h(), 0xFF000000);
        if (!evf && !rangefinder) {
            opticalVignette(g, outer);
            if (dev.hackdanel.realcamera.client.RCKeys.DOF_PREVIEW.isDown() && exp != null) {
                // stopping the lens down for the depth-of-field preview darkens an optical finder
                double stops = Math.max(0, Math.log(exp.fNumber() / Viewfinder.chain().maxApertureAt(Viewfinder.focal())) / Math.log(2));
                int alpha = (int) (Math.min(0.88, 1 - Math.pow(2, -stops)) * 255);
                if (alpha > 0) g.fill(outer.x(), outer.y(), outer.x() + outer.w(), outer.y() + outer.h(), alpha << 24);
                g.drawString(font, "DOF", outer.x() + 8, outer.y() + 8, OVF_GREEN, true);
            }
        }
        if (Viewfinder.magnified()) {
            g.drawString(font, "×5", outer.x() + 8, outer.y() + 22, YELLOW, true);
        }
        int disp = s.disp();
        if (disp == CameraSettings.DISP_FULL && !Viewfinder.magnified()) {
            grid(g, frame);
        }
        if (rangefinder && !Viewfinder.magnified()) {
            frameLines(g, frame);
            rangefinderPatch(g, outer);
        } else {
            afOverlay(g, frame, s);
        }
        if (s.focusMode() == CameraSettings.FOCUS_MF || !Viewfinder.afAvailable()) {
            if (s.peaking()) peaking(g, frame);
        }
        double timer = Viewfinder.timerLeft();
        if (timer >= 0) {
            String t = String.valueOf((int) Math.ceil(timer));
            g.pose().pushPose();
            g.pose().translate(frame.cx(), frame.cy() - 30, 0);
            g.pose().scale(3, 3, 1);
            g.drawCenteredString(font, t, 0, 0, ORANGE);
            g.pose().popPose();
        }
        if (disp == CameraSettings.DISP_CLEAN) {
            return;
        }
        if (Viewfinder.focusRingTurnedRecently() && exp != null && Viewfinder.chain().tiltDeg(s) == 0) {
            dofScale(g, font, outer.cx(), outer.y() + outer.h() - (evf ? 52 : 44), evf ? WHITE : OVF_GREEN);
        }
        if (evf) {
            bottomBar(g, font, outer.x(), outer.y() + outer.h(), outer.w(), s, exp, WHITE, 0x90000000);
            topBar(g, font, outer.x(), outer.y(), outer.w(), s);
            if (exp != null && (disp == CameraSettings.DISP_FULL || disp == CameraSettings.DISP_HISTOGRAM)) {
                histogram(g, outer.x() + outer.w() - 72, outer.y() + outer.h() - 58, 64, 32, previewEv(exp, s));
            }
            if (exp != null && (s.mode() == CameraSettings.MODE_M || Math.abs(exp.delta()) > 0.34)) {
                meterScale(g, font, outer.cx(), outer.y() + outer.h() - 34, exp.delta());
            }
        } else {
            // optical finder: data strip below the image area, green LCD style
            bottomBar(g, font, outer.x(), outer.y() + outer.h(), outer.w(), s, exp, OVF_GREEN, 0xE0000000);
            if (exp != null && Viewfinder.meterWorks()) {
                meterScale(g, font, outer.cx(), outer.y() + outer.h() - 26, exp.delta());
            }
        }
    }

    /** Bulb exposure is unknown until release; preview it as one second. */
    public static double previewEv(Exposure.Result exp, CameraSettings s) {
        if (s.mode() == CameraSettings.MODE_M && s.shutterIndex() >= ExposureScales.BULB_INDEX) {
            return Viewfinder.meteredEv() - Exposure.cameraEv(exp.fNumber(), 1, exp.iso());
        }
        return exp.delta();
    }

    // ------------------------------------------------------------------ exposure preview

    /** Multiply (darker) or add (brighter) the frame so the EVF shows the exposure the photo will get. */
    private static void exposurePreview(GuiGraphics g, int x, int y, int w, int h, double deltaEv) {
        double gain = Math.pow(Math.pow(2, Math.max(-8, Math.min(6, deltaEv))), 1 / 2.2);
        Matrix4f m = g.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        if (gain < 0.99) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
            quad(m, x, y, w, h, (float) gain, (float) gain, (float) gain);
        } else if (gain > 1.01) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ONE);
            double remaining = gain;
            while (remaining > 1.01) {
                float add = (float) Math.min(1, remaining - 1);
                quad(m, x, y, w, h, add, add, add);
                remaining /= (1 + add);
            }
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private static void quad(Matrix4f m, int x, int y, int w, int h, float r, float gr, float b) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(m, x, y + h, 0).setColor(r, gr, b, 1f);
        bb.addVertex(m, x + w, y + h, 0).setColor(r, gr, b, 1f);
        bb.addVertex(m, x + w, y, 0).setColor(r, gr, b, 1f);
        bb.addVertex(m, x, y, 0).setColor(r, gr, b, 1f);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /** Darkened edges of an optical pentaprism finder. */
    private static void opticalVignette(GuiGraphics g, Frame f) {
        int band = Math.max(4, f.h() / 14);
        for (int i = 0; i < band; i++) {
            int a = (int) (110 * (1 - (double) i / band));
            int c = a << 24;
            g.fill(f.x() + i, f.y() + i, f.x() + f.w() - i, f.y() + i + 1, c);
            g.fill(f.x() + i, f.y() + f.h() - i - 1, f.x() + f.w() - i, f.y() + f.h() - i, c);
            g.fill(f.x() + i, f.y() + i, f.x() + i + 1, f.y() + f.h() - i, c);
            g.fill(f.x() + f.w() - i - 1, f.y() + i, f.x() + f.w() - i, f.y() + f.h() - i, c);
        }
    }

    // ------------------------------------------------------------------ overlays

    private static void grid(GuiGraphics g, Frame f) {
        int c = 0x50FFFFFF;
        for (int i = 1; i < 3; i++) {
            g.vLine(f.x() + f.w() * i / 3, f.y(), f.y() + f.h(), c);
            g.hLine(f.x(), f.x() + f.w(), f.y() + f.h() * i / 3, c);
        }
    }

    private static int afColor() {
        return switch (Viewfinder.afState()) {
            case Viewfinder.AF_LOCKED -> GREEN;
            case Viewfinder.AF_FAILED -> RED;
            case Viewfinder.AF_SEARCHING -> (System.currentTimeMillis() / 120 % 2 == 0) ? WHITE : GREY;
            default -> WHITE;
        };
    }

    private static void afOverlay(GuiGraphics g, Frame f, CameraSettings s) {
        boolean mf = s.focusMode() == CameraSettings.FOCUS_MF || !Viewfinder.afAvailable();
        int color = mf ? 0x90FFFFFF : afColor();
        SubjectDetector.Subject subject = Viewfinder.subject();
        if (subject != null) {
            int x0 = f.sx(subject.x0()), x1 = f.sx(subject.x1()), y0 = f.sy(subject.y1()), y1 = f.sy(subject.y0());
            int sc = Viewfinder.afState() == Viewfinder.AF_LOCKED ? GREEN : 0xC0FFFFFF;
            corners(g, x0, y0, x1, y1, sc);
            if (subject.eye()) {
                // eye marker: small box at the eye point
                int ex = f.sx(eyeU(subject)), ey = f.sy(eyeV(subject));
                corners(g, ex - 4, ey - 3, ex + 4, ey + 3, sc);
            }
            if (Viewfinder.afState() == Viewfinder.AF_LOCKED) return;
        }
        int size = Math.max(10, f.w() / 22);
        switch (s.afArea()) {
            case CameraSettings.AF_POINT -> {
                int cx = f.sx(s.afX()), cy = f.sy(s.afY());
                corners(g, cx - size / 2, cy - size / 3, cx + size / 2, cy + size / 3, color);
            }
            case CameraSettings.AF_ZONE -> corners(g, f.sx(-0.4), f.sy(0.4), f.sx(0.4), f.sy(-0.4), color);
            case CameraSettings.AF_TRACKING -> {
                corners(g, f.cx() - size, f.cy() - size * 2 / 3, f.cx() + size, f.cy() + size * 2 / 3, color);
                g.fill(f.cx() - 1, f.cy() - 1, f.cx() + 1, f.cy() + 1, color);
            }
            default -> {
                if (!"ovf".equals(Viewfinder.body().viewfinder())) {
                    corners(g, f.sx(-0.9), f.sy(0.85), f.sx(0.9), f.sy(-0.85), 0x60FFFFFF);
                } else {
                    // DSLR: a fixed pattern of AF points
                    for (int y = -2; y <= 2; y++) {
                        for (int x = -4; x <= 4; x++) {
                            int px = f.sx(x * 0.12), py = f.sy(y * 0.14);
                            g.fill(px - 2, py - 2, px + 2, py - 1, 0x80000000);
                            g.fill(px - 2, py + 1, px + 2, py + 2, 0x80000000);
                        }
                    }
                }
                if (Viewfinder.afState() != Viewfinder.AF_IDLE && subject == null) {
                    corners(g, f.cx() - size, f.cy() - size * 2 / 3, f.cx() + size, f.cy() + size * 2 / 3, color);
                }
            }
        }
    }

    private static float eyeU(SubjectDetector.Subject s) {
        return s.cx();
    }

    private static float eyeV(SubjectDetector.Subject s) {
        return s.y1() - (s.y1() - s.y0()) * 0.12f;
    }

    private static void corners(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int l = Math.max(2, Math.min(x1 - x0, y1 - y0) / 4);
        g.hLine(x0, x0 + l, y0, color);
        g.vLine(x0, y0, y0 + l, color);
        g.hLine(x1 - l, x1, y0, color);
        g.vLine(x1, y0, y0 + l, color);
        g.hLine(x0, x0 + l, y1, color);
        g.vLine(x0, y1 - l, y1, color);
        g.hLine(x1 - l, x1, y1, color);
        g.vLine(x1, y1 - l, y1, color);
    }

    private static void peaking(GuiGraphics g, Frame f) {
        boolean[] mask = Viewfinder.peaking();
        int cw = Math.max(1, f.w() / Viewfinder.PEAK_W), ch = Math.max(1, f.h() / Viewfinder.PEAK_H);
        for (int y = 0; y < Viewfinder.PEAK_H; y++) {
            for (int x = 0; x < Viewfinder.PEAK_W; x++) {
                if (mask[y * Viewfinder.PEAK_W + x]) {
                    int px = f.x() + x * f.w() / Viewfinder.PEAK_W, py = f.y() + y * f.h() / Viewfinder.PEAK_H;
                    g.fill(px, py, px + Math.max(1, cw / 2), py + Math.max(1, ch / 2), PEAK);
                }
            }
        }
    }

    /** Leica-style bright frame lines marking the picture inside the wider view. */
    private static void frameLines(GuiGraphics g, Frame f) {
        int c = 0xE0FFF4C0;
        int l = f.w() / 10;
        g.hLine(f.x(), f.x() + l, f.y(), c);
        g.vLine(f.x(), f.y(), f.y() + l, c);
        g.hLine(f.x() + f.w() - l, f.x() + f.w(), f.y(), c);
        g.vLine(f.x() + f.w(), f.y(), f.y() + l, c);
        g.hLine(f.x(), f.x() + l, f.y() + f.h(), c);
        g.vLine(f.x(), f.y() + f.h() - l, f.y() + f.h(), c);
        g.hLine(f.x() + f.w() - l, f.x() + f.w(), f.y() + f.h(), c);
        g.vLine(f.x() + f.w(), f.y() + f.h() - l, f.y() + f.h(), c);
    }

    /**
     * Rangefinder focusing patch: a brighter rectangle in the centre with a ghost image displaced
     * sideways by the focus error (1/focus - 1/subject distance); it merges when in focus.
     */
    private static void rangefinderPatch(GuiGraphics g, Frame outer) {
        int pw = outer.w() / 9, ph = outer.h() / 9;
        int x = outer.cx() - pw / 2, y = outer.cy() - ph / 2;
        float subject = Viewfinder.subjectDistanceForPatch();
        double error = 1 / Math.max(0.3, Viewfinder.focusDistance()) - 1 / Math.max(0.3, subject);
        int shift = (int) Math.round(Math.max(-1, Math.min(1, error * 1.5)) * pw * 0.6);
        g.fill(x, y, x + pw, y + ph, 0x30FFE9A0);
        // ghost: a second outline of the subject edge shifted by the error
        int c = Math.abs(shift) <= 1 ? 0xC063E26B : 0xB0FFE9A0;
        g.vLine(outer.cx() + shift, y + 2, y + ph - 2, c);
        g.vLine(outer.cx() + shift + 1, y + 2, y + ph - 2, c);
        g.hLine(x, x + pw, y, 0x80FFFFFF);
        g.hLine(x, x + pw, y + ph, 0x80FFFFFF);
        g.vLine(x, y, y + ph, 0x80FFFFFF);
        g.vLine(x + pw, y, y + ph, 0x80FFFFFF);
    }

    // ------------------------------------------------------------------ data

    private static String modeLabel(int mode) {
        return switch (mode) {
            case CameraSettings.MODE_AUTO -> "AUTO";
            case CameraSettings.MODE_P -> "P";
            case CameraSettings.MODE_A -> "A";
            case CameraSettings.MODE_S -> "S";
            default -> "M";
        };
    }

    public static String wbLabel(CameraSettings s) {
        return switch (s.whiteBalance()) {
            case CameraSettings.WB_AUTO -> "AWB";
            case CameraSettings.WB_DAYLIGHT -> "DAY";
            case CameraSettings.WB_CLOUDY -> "CLOUD";
            case CameraSettings.WB_SHADE -> "SHADE";
            case CameraSettings.WB_TUNGSTEN -> "TUNG";
            case CameraSettings.WB_FLUORESCENT -> "FLUO";
            case CameraSettings.WB_FLASH -> "FLASH";
            default -> s.kelvin() + "K";
        };
    }

    public static String driveLabel(int drive) {
        return switch (drive) {
            case CameraSettings.DRIVE_CONT_LO -> "CL";
            case CameraSettings.DRIVE_CONT_HI -> "CH";
            case CameraSettings.DRIVE_CONT_MAX -> "CH+";
            case CameraSettings.DRIVE_TIMER_2 -> "2s";
            case CameraSettings.DRIVE_TIMER_10 -> "10s";
            default -> "S";
        };
    }

    public static String afAreaLabel(int area) {
        return switch (area) {
            case CameraSettings.AF_ZONE -> "ZONE";
            case CameraSettings.AF_POINT -> "SPOT";
            case CameraSettings.AF_TRACKING -> "TRACK";
            default -> "WIDE";
        };
    }

    /**
     * ISO in the bottom bar. Digital: "ISO AUTO 400" / "ISO 400". Film: the rated speed, "DX" while it is read
     * from the cartridge, and the push/pull in stops when it differs from the box speed ("ISO 1600 +2.0");
     * nothing on a body without a meter (there is no speed to set).
     */
    private static String isoLabel(CameraSettings s, Exposure.Result exp) {
        int idx = Math.max(0, Math.min(ExposureScales.ISO.length - 1, exp.isoIdx()));
        String dial = Viewfinder.isoDial();
        var stock = Viewfinder.filmStock();
        if (dial == null || stock == null) {
            return (s.isoIndex() < 0 || s.mode() == CameraSettings.MODE_AUTO ? "ISO AUTO " : "ISO ") + ExposureScales.ISO[idx];
        }
        if (dial.equals(dev.hackdanel.realcamera.spec.CameraBodySpec.Film.DIAL_NONE)) return "";
        boolean dx = !dial.equals(dev.hackdanel.realcamera.spec.CameraBodySpec.Film.DIAL_MANUAL) && Viewfinder.effectiveIsoIndex() < 0;
        int push = idx - ExposureScales.nearestIso(stock.iso());
        return (dx ? "ISO DX " : "ISO ") + ExposureScales.ISO[idx] + (push != 0 ? " " + ExposureScales.evCompLabel(push) : "");
    }

    private static void bottomBar(GuiGraphics g, Font font, int x, int bottom, int w, CameraSettings s,
                                  Exposure.Result exp, int baseColor, int background) {
        int y = bottom - 12;
        g.fill(x, y - 3, x + w, bottom, background);
        int mode = s.mode();
        int sel = s.selectedParam();
        boolean shutterFree = mode == CameraSettings.MODE_S || mode == CameraSettings.MODE_M;
        boolean apertureFree = mode == CameraSettings.MODE_A || mode == CameraSettings.MODE_M;
        boolean isoFree = mode != CameraSettings.MODE_AUTO;
        boolean bulb = mode == CameraSettings.MODE_M && s.shutterIndex() >= ExposureScales.BULB_INDEX;
        String shutter = exp == null ? "--" : bulb ? "BULB" : ExposureScales.SHUTTER_LABEL[exp.shutterIdx()];
        String aperture = exp == null ? "--" : "F" + ExposureScales.APERTURE_LABEL[exp.apertureIdx()];
        String iso = exp == null ? "--" : isoLabel(s, exp);
        boolean isoSettable = isoFree && Viewfinder.isoAdjustable();
        String ev = ExposureScales.evCompLabel(s.evComp());
        int cx = x + 6;
        cx = text(g, font, modeLabel(mode), cx, y, YELLOW) + 12;
        cx = text(g, font, shutter, cx, y, color(sel == CameraSettings.PARAM_SHUTTER, shutterFree, baseColor)) + 10;
        cx = text(g, font, aperture, cx, y, color(sel == CameraSettings.PARAM_APERTURE, apertureFree, baseColor)) + 10;
        if (!iso.isEmpty()) {
            cx = text(g, font, iso, cx, y, color(sel == CameraSettings.PARAM_ISO, isoSettable, baseColor)) + 10;
        }
        cx = text(g, font, "EV" + ev, cx, y, color(sel == CameraSettings.PARAM_EV, isoFree, baseColor)) + 10;
        text(g, font, wbLabel(s), cx, y, color(sel == CameraSettings.PARAM_WB, isoFree, baseColor));
        if (Viewfinder.chain() != null && Viewfinder.chain().tiltShift().isPresent()) {
            // tilt-shift movements on their own line above the bar
            int ty = y - 15;
            String tilt = String.format(Locale.ROOT, "TILT %+.1f°", Viewfinder.chain().tiltDeg(s));
            String shift = String.format(Locale.ROOT, "SHIFT %+.1f mm", Viewfinder.chain().shiftMm(s));
            g.fill(x, ty - 3, x + 18 + font.width(tilt) + font.width(shift) + 10, ty + 10, background);
            int tx = text(g, font, tilt, x + 6, ty, color(sel == CameraSettings.PARAM_TILT, true, baseColor)) + 10;
            text(g, font, shift, tx, ty, color(sel == CameraSettings.PARAM_SHIFT, true, baseColor));
        }

        ItemStack cam = Viewfinder.camera();
        BatteryCharge charge = CameraHelper.charge(cam);
        long shots = Minecraft.getInstance().level == null ? -1
                : CameraHelper.shotsLeft(Minecraft.getInstance().level.registryAccess(), cam);
        String right = (shots >= 0 ? shots : "--") + "   " + (charge == null ? "--" : charge.percent() + "%");
        int batteryColor = charge != null && charge.percent() <= 15 ? RED : baseColor;
        g.drawString(font, right, x + w - 6 - font.width(right), y, batteryColor, true);
        int buffered = Math.max(PhotoCapture.buffered(), (int) Math.ceil(Viewfinder.cameraBuffer() - 0.05));
        if (buffered > 0) {
            String rec = "REC " + buffered;
            g.drawString(font, rec, x + w - 14 - font.width(right) - font.width(rec), y, RED, true);
        }
    }

    private static int color(boolean selected, boolean free, int base) {
        return selected ? (free ? YELLOW : ORANGE) : (free ? base : GREY);
    }

    private static int text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, true);
        return x + font.width(s);
    }

    private static void topBar(GuiGraphics g, Font font, int x, int y, int w, CameraSettings s) {
        boolean mf = s.focusMode() == CameraSettings.FOCUS_MF || !Viewfinder.afAvailable();
        String focus = mf ? "MF" : s.focusMode() == CameraSettings.FOCUS_AFC ? "AF-C" : "AF-S";
        if (!mf && Viewfinder.afButtonLock()) {
            focus += " AF-L"; // focus held by AF-ON
        }
        String meter = switch (s.metering()) {
            case CameraSettings.METER_CENTER -> "CTR";
            case CameraSettings.METER_SPOT -> "SPOT";
            default -> "MULTI";
        };
        float d = Viewfinder.focusDistance();
        String dist = d >= 999 ? "∞" : d >= 10 ? String.format(Locale.ROOT, "%.0f m", d) : String.format(Locale.ROOT, "%.1f m", d);
        String area = mf ? "" : afAreaLabel(s.afArea()) + "  ";
        String range = "";
        if (Viewfinder.exposure() != null && Viewfinder.chain().tiltDeg(s) == 0) {
            double[] dof = Viewfinder.dofLimits();
            range = " (" + distanceLabel(dof[0]) + "–" + distanceLabel(dof[1]) + ")";
        }
        String left = String.format(Locale.ROOT, "%.0f mm   %s %s%s  %s%s", Viewfinder.focal(), focus, dist, range, area, meter);
        // close-up: reproduction ratio, like the scale on a macro lens
        double mag = Viewfinder.chain() == null ? 0 : Viewfinder.chain().magnification(Viewfinder.focal(), d);
        if (mag >= 0.1) {
            left += String.format(Locale.ROOT, "   %s", mag >= 0.995 ? "1:1" : String.format(Locale.ROOT, "1:%.1f", 1 / mag));
        }
        g.drawString(font, left, x + 6, y + 6, WHITE, true);
        StringBuilder r = new StringBuilder();
        r.append(driveLabel(s.drive())).append("  ");
        if (s.flashMode() != CameraSettings.FLASH_OFF) {
            boolean has = Viewfinder.flashGn() > 0;
            String f = switch (s.flashMode()) {
                case CameraSettings.FLASH_REAR -> "F-REAR";
                case CameraSettings.FLASH_AUTO -> "F-AUTO";
                default -> "FLASH";
            };
            r.append(has ? (Viewfinder.flashReady() ? f : f + "…") : "NO FLASH").append("  ");
        }
        if (s.shutterType() == CameraSettings.SHUTTER_ELECTRONIC || !Viewfinder.body().shutter().hasMechanical()) {
            r.append("e  ");
        }
        if (s.stabilization() && Viewfinder.chain().stabilizationStops() > 0) {
            r.append("IS  ");
        }
        var wear = dev.hackdanel.realcamera.camera.CameraHelper.wear(Viewfinder.camera());
        if (wear.shutterJammed()) r.append("SHUTTER ERR  ");
        if (wear.moisture() >= 40) r.append("FOG  ");
        if (Viewfinder.chain().cropped()) {
            r.append("APS-C  ");
        }
        r.append(switch (s.fileFormat()) {
            case CameraSettings.FORMAT_RAW -> "RAW";
            case CameraSettings.FORMAT_RAW_JPEG -> "RAW+J";
            default -> s.quality() == CameraSettings.QUALITY_FINE ? "JPEG FINE" : "JPEG STD";
        });
        String right = r.toString();
        g.drawString(font, right, x + w - 6 - font.width(right), y + 6, WHITE, true);
        float pitch = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.getXRot();
        if (Math.abs(pitch) < 2.5f && s.disp() == CameraSettings.DISP_FULL) {
            g.drawString(font, "- 0° -", x + w / 2 - font.width("- 0° -") / 2, y + 18, GREEN, true);
        }
    }

    private static String distanceLabel(double m) {
        if (Double.isInfinite(m) || m >= 999) return "∞";
        if (m >= 10) return String.format(Locale.ROOT, "%.0f m", m);
        if (m >= 1) return String.format(Locale.ROOT, "%.1f m", m);
        return String.format(Locale.ROOT, "%.2f m", m);
    }

    /**
     * Depth-of-field scale while the focus ring turns, like the one engraved on a manual lens: distances on a
     * dioptre scale (1/d, infinity at the right end), the band that is acceptably sharp, the focus and the
     * hyperfocal distance (H: focused there, everything from half of it to infinity is sharp).
     */
    private static void dofScale(GuiGraphics g, Font font, int cx, int y, int colour) {
        double[] dof = Viewfinder.dofLimits();
        double near = Math.max(0.3, Viewfinder.chain().lens().minFocusM());
        int w = 220, x0 = cx - w / 2;
        java.util.function.DoubleUnaryOperator pos = d -> x0 + w * Math.max(0, Math.min(1, 1 - near / Math.max(near, d)));
        g.fill(x0 - 4, y - 12, x0 + w + 4, y + 10, 0x90000000);
        g.fill(x0, y, x0 + w, y + 1, colour);
        int a = (int) pos.applyAsDouble(dof[0]), b = (int) pos.applyAsDouble(Double.isInfinite(dof[1]) ? 1e9 : dof[1]);
        g.fill(a, y - 3, Math.max(a + 1, b), y, 0xC0FFD040);
        for (double d : new double[]{0.5, 1, 2, 3, 5, 10, 1e9}) {
            if (d < near) continue;
            int px = (int) pos.applyAsDouble(d);
            g.fill(px, y + 1, px + 1, y + 3, colour);
            String label = d >= 1e8 ? "∞" : d < 1 ? String.format(Locale.ROOT, "%.1f", d) : String.format(Locale.ROOT, "%.0f", d);
            g.pose().pushPose();
            g.pose().translate(px, y + 4, 0);
            g.pose().scale(0.5f, 0.5f, 1);
            g.drawCenteredString(font, label, 0, 0, colour);
            g.pose().popPose();
        }
        int h = (int) pos.applyAsDouble(dof[2]);
        g.pose().pushPose();
        g.pose().translate(h, y - 10, 0);
        g.pose().scale(0.5f, 0.5f, 1);
        g.drawCenteredString(font, "H", 0, 0, ORANGE);
        g.pose().popPose();
        int f = (int) pos.applyAsDouble(Viewfinder.liveFocusDistance());
        g.fill(f, y - 6, f + 1, y + 1, YELLOW);
    }

    private static void meterScale(GuiGraphics g, Font font, int cx, int y, double delta) {
        int unit = 12; // pixels per EV
        g.fill(cx - unit * 3 - 4, y - 2, cx + unit * 3 + 5, y + 9, 0x80000000);
        for (int i = -3; i <= 3; i++) {
            g.vLine(cx + i * unit, y, y + (i == 0 ? 5 : 3), WHITE);
            for (int t = 1; t < 3 && i < 3; t++) {
                g.vLine(cx + i * unit + t * unit / 3, y, y + 1, GREY);
            }
        }
        double d = Math.max(-3.2, Math.min(3.2, delta));
        int px = cx + (int) Math.round(d * unit);
        g.fill(px - 1, y + 6, px + 2, y + 9, Math.abs(delta) > 3 ? RED : YELLOW);
        g.drawString(font, Component.literal(String.format(Locale.ROOT, "%+.1f", delta)), cx + unit * 3 + 8, y, WHITE, true);
    }

    private static void histogram(GuiGraphics g, int x, int y, int w, int h, double delta) {
        var look = dev.hackdanel.realcamera.client.render.EvfPreview.look();
        int[] bins = look != null ? FrameSampler.histogram(look::grey) : FrameSampler.histogram(delta);
        int max = 1;
        for (int b : bins) max = Math.max(max, b);
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x80000000);
        for (int i = 0; i < bins.length; i++) {
            int bh = (int) Math.round(Math.sqrt((double) bins[i] / max) * h);
            int bx = x + i * w / bins.length;
            int color = i == bins.length - 1 && bins[i] > max / 20 ? RED : 0xD0E0E0E0;
            g.fill(bx, y + h - bh, bx + Math.max(1, w / bins.length), y + h, color);
        }
    }
}
