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

/** Electronic viewfinder overlay: exposure preview, frame mask and the camera's information display. */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class ViewfinderHud {
    public static final ResourceLocation LAYER = RealCamera.id("viewfinder");
    private static final Set<ResourceLocation> KEEP = Set.of(VanillaGuiLayers.CHAT, VanillaGuiLayers.OVERLAY_MESSAGE,
            VanillaGuiLayers.SUBTITLE_OVERLAY, VanillaGuiLayers.DEBUG_OVERLAY, LAYER);
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFF9A9A9A, YELLOW = 0xFFFFD84A, RED = 0xFFFF5A4A,
            GREEN = 0xFF63E26B, ORANGE = 0xFFFF9F2E;

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

    static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        // Mod GUI layers still run with hideGui; never draw over the frame being captured.
        if (!Viewfinder.isActive() || PhotoCapture.capturing() || mc.options.hideGui) {
            return;
        }
        int sw = g.guiWidth(), sh = g.guiHeight();
        double sensorAspect = Viewfinder.body().sensor().aspect();
        int fw, fh;
        if ((double) sw / sh >= sensorAspect) {
            fh = sh;
            fw = (int) Math.round(sh * sensorAspect);
        } else {
            fw = sw;
            fh = (int) Math.round(sw / sensorAspect);
        }
        int fx = (sw - fw) / 2, fy = (sh - fh) / 2;
        Exposure.Result exp = Viewfinder.exposure();
        CameraSettings s = Viewfinder.settings();
        int disp = s.disp();

        g.flush();
        if (exp != null) {
            exposurePreview(g, fx, fy, fw, fh, exp.delta(), s.whiteBalance());
        }
        // frame mask
        g.fill(0, 0, sw, fy, 0xFF000000);
        g.fill(0, fy + fh, sw, sh, 0xFF000000);
        g.fill(0, fy, fx, fy + fh, 0xFF000000);
        g.fill(fx + fw, fy, sw, fy + fh, 0xFF000000);

        Font font = mc.font;
        if (disp == CameraSettings.DISP_FULL) {
            grid(g, fx, fy, fw, fh);
        }
        afFrame(g, fx + fw / 2, fy + fh / 2, fw);
        if (disp == CameraSettings.DISP_CLEAN) {
            return;
        }
        bottomBar(g, font, fx, fy + fh, fw, s, exp);
        topBar(g, font, fx, fy, fw, s);
        if (exp != null && (disp == CameraSettings.DISP_FULL || disp == CameraSettings.DISP_HISTOGRAM)) {
            histogram(g, fx + fw - 72, fy + fh - 58, 64, 32, exp.delta());
        }
        if (exp != null && (s.mode() == CameraSettings.MODE_M || Math.abs(exp.delta()) > 0.34)) {
            meterScale(g, font, fx + fw / 2, fy + fh - 34, exp.delta());
        }
    }

    // ------------------------------------------------------------------ exposure preview

    /** Multiply (darker) or add (brighter) the frame so the EVF shows the exposure the photo will get. */
    private static void exposurePreview(GuiGraphics g, int x, int y, int w, int h, double deltaEv, int wb) {
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

    // ------------------------------------------------------------------ overlays

    private static void grid(GuiGraphics g, int x, int y, int w, int h) {
        int c = 0x50FFFFFF;
        for (int i = 1; i < 3; i++) {
            g.vLine(x + w * i / 3, y, y + h, c);
            g.hLine(x, x + w, y + h * i / 3, c);
        }
    }

    private static void afFrame(GuiGraphics g, int cx, int cy, int fw) {
        int size = Math.max(10, fw / 22);
        int color = switch (Viewfinder.afState()) {
            case Viewfinder.AF_LOCKED -> GREEN;
            case Viewfinder.AF_FAILED -> RED;
            case Viewfinder.AF_SEARCHING -> (System.currentTimeMillis() / 120 % 2 == 0) ? WHITE : GREY;
            default -> WHITE;
        };
        if (Viewfinder.settings().focusMode() == CameraSettings.FOCUS_MF) {
            color = 0x90FFFFFF;
        }
        int l = size / 3;
        int x0 = cx - size, x1 = cx + size, y0 = cy - size * 2 / 3, y1 = cy + size * 2 / 3;
        g.hLine(x0, x0 + l, y0, color);
        g.vLine(x0, y0, y0 + l, color);
        g.hLine(x1 - l, x1, y0, color);
        g.vLine(x1, y0, y0 + l, color);
        g.hLine(x0, x0 + l, y1, color);
        g.vLine(x0, y1 - l, y1, color);
        g.hLine(x1 - l, x1, y1, color);
        g.vLine(x1, y1 - l, y1, color);
    }

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

    private static void bottomBar(GuiGraphics g, Font font, int x, int bottom, int w, CameraSettings s, Exposure.Result exp) {
        int y = bottom - 12;
        g.fill(x, y - 3, x + w, bottom, 0x90000000);
        int mode = s.mode();
        int sel = s.selectedParam();
        boolean shutterFree = mode == CameraSettings.MODE_S || mode == CameraSettings.MODE_M;
        boolean apertureFree = mode == CameraSettings.MODE_A || mode == CameraSettings.MODE_M;
        boolean isoFree = mode != CameraSettings.MODE_AUTO;
        String shutter = exp == null ? "--" : ExposureScales.SHUTTER_LABEL[exp.shutterIdx()];
        String aperture = exp == null ? "--" : "F" + ExposureScales.APERTURE_LABEL[exp.apertureIdx()];
        String iso = exp == null ? "--" : (s.isoIndex() < 0 || mode == CameraSettings.MODE_AUTO ? "ISO AUTO " : "ISO ")
                + ExposureScales.ISO[Math.max(0, Math.min(ExposureScales.ISO.length - 1, exp.isoIdx()))];
        String ev = ExposureScales.evCompLabel(s.evComp());
        int cx = x + 6;
        cx = text(g, font, modeLabel(mode), cx, y, YELLOW) + 12;
        cx = text(g, font, shutter, cx, y, color(sel == CameraSettings.PARAM_SHUTTER, shutterFree)) + 10;
        cx = text(g, font, aperture, cx, y, color(sel == CameraSettings.PARAM_APERTURE, apertureFree)) + 10;
        cx = text(g, font, iso, cx, y, color(sel == CameraSettings.PARAM_ISO, isoFree)) + 10;
        cx = text(g, font, "EV" + ev, cx, y, color(sel == CameraSettings.PARAM_EV, isoFree)) + 10;
        text(g, font, wbLabel(s), cx, y, color(sel == CameraSettings.PARAM_WB, isoFree));

        ItemStack cam = Viewfinder.camera();
        BatteryCharge charge = CameraHelper.charge(cam);
        long shots = Minecraft.getInstance().level == null ? -1
                : CameraHelper.shotsLeft(Minecraft.getInstance().level.registryAccess(), cam);
        String right = (shots >= 0 ? shots : "--") + "   " + (charge == null ? "--" : charge.percent() + "%");
        int batteryColor = charge != null && charge.percent() <= 15 ? RED : WHITE;
        g.drawString(font, right, x + w - 6 - font.width(right), y, batteryColor, true);
        if (PhotoCapture.busy()) {
            g.drawString(font, "REC", x + w - 14 - font.width(right) - font.width("REC"), y, RED, true);
        }
    }

    private static int color(boolean selected, boolean free) {
        return selected ? (free ? YELLOW : ORANGE) : (free ? WHITE : GREY);
    }

    private static int text(GuiGraphics g, Font font, String s, int x, int y, int color) {
        g.drawString(font, s, x, y, color, true);
        return x + font.width(s);
    }

    private static void topBar(GuiGraphics g, Font font, int x, int y, int w, CameraSettings s) {
        String focus = switch (s.focusMode()) {
            case CameraSettings.FOCUS_AFS -> "AF-S";
            case CameraSettings.FOCUS_AFC -> "AF-C";
            default -> "MF";
        };
        String meter = switch (s.metering()) {
            case CameraSettings.METER_CENTER -> "CTR";
            case CameraSettings.METER_SPOT -> "SPOT";
            default -> "MULTI";
        };
        float d = Viewfinder.focusDistance();
        String dist = d >= 999 ? "∞" : d >= 10 ? String.format(Locale.ROOT, "%.0f m", d) : String.format(Locale.ROOT, "%.1f m", d);
        String left = String.format(Locale.ROOT, "%.0f mm   %s %s   %s", Viewfinder.focal(), focus, dist, meter);
        g.drawString(Minecraft.getInstance().font, left, x + 6, y + 6, WHITE, true);
        String q = s.quality() == CameraSettings.QUALITY_FINE ? "JPEG FINE" : "JPEG STD";
        g.drawString(font, q, x + w - 6 - font.width(q), y + 6, WHITE, true);
        float pitch = Minecraft.getInstance().player == null ? 0 : Minecraft.getInstance().player.getXRot();
        if (Math.abs(pitch) < 2.5f && s.disp() == CameraSettings.DISP_FULL) {
            g.drawString(font, "- 0° -", x + w / 2 - font.width("- 0° -") / 2, y + 6, GREEN, true);
        }
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
        int[] bins = FrameSampler.histogram(delta);
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
