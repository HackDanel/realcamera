package dev.hackdanel.realcamera.client.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.hackdanel.realcamera.RealCamera;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Stage 0 spike: proves we can grab a clean frame (no HUD, no hand) at window resolution and at a
 * forced higher resolution, both with vanilla rendering and with Iris shaders.
 * F8 = window resolution, F9 = 3840px long side, F10 = 2 s long exposure (see AccumulationSpike). Output: screenshots/realcamera_spike/.
 */
public final class CaptureSpike {
    public static final KeyMapping KEY_CAPTURE = new KeyMapping(
            "key.realcamera.spike_capture", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.realcamera");
    public static final KeyMapping KEY_CAPTURE_HIRES = new KeyMapping(
            "key.realcamera.spike_capture_hires", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.realcamera");

    public static final KeyMapping KEY_LONG_EXPOSURE = new KeyMapping(
            "key.realcamera.spike_long_exposure", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, "key.categories.realcamera");

    public static final int HIRES_LONG_SIDE = 3840;

    private static Request pending;
    private static Active active;

    /** Frames rendered at the capture size before the one that is grabbed (-Drealcamera.spike.warmup). */
    private static final int WARMUP_FRAMES = Integer.getInteger("realcamera.spike.warmup", 1);

    private record Request(String tag, int longSide) {}

    private static final class Active {
        final String tag;
        final boolean savedHideGui;
        final int origW, origH;
        final boolean resized;
        final long startNanos;
        int warmupLeft;

        Active(String tag, boolean savedHideGui, int origW, int origH, boolean resized, long startNanos, int warmup) {
            this.tag = tag;
            this.savedHideGui = savedHideGui;
            this.origW = origW;
            this.origH = origH;
            this.resized = resized;
            this.startNanos = startNanos;
            this.warmupLeft = warmup;
        }
    }

    private CaptureSpike() {}

    /** Queue a capture for the next frame. longSide <= 0 means window resolution. */
    public static void request(String tag, int longSide) {
        pending = new Request(tag, longSide);
    }

    public static boolean isBusy() {
        return pending != null || active != null;
    }

    @EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
    public static final class ModEvents {
        @SubscribeEvent
        static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(KEY_CAPTURE);
            event.register(KEY_CAPTURE_HIRES);
            event.register(KEY_LONG_EXPOSURE);
        }
    }

    @EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
    public static final class GameEvents {
        @SubscribeEvent
        static void onTick(ClientTickEvent.Post event) {
            while (KEY_CAPTURE.consumeClick()) {
                request("window", 0);
            }
            while (KEY_CAPTURE_HIRES.consumeClick()) {
                request("hires", HIRES_LONG_SIDE);
            }
            while (KEY_LONG_EXPOSURE.consumeClick()) {
                AccumulationSpike.start(2.0);
            }
        }

        @SubscribeEvent
        static void onFramePre(RenderFrameEvent.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (pending == null || mc.level == null || active != null) {
                return;
            }
            Request req = pending;
            pending = null;

            Window window = mc.getWindow();
            int origW = window.getWidth();
            int origH = window.getHeight();
            boolean resized = false;
            if (req.longSide() > 0 && req.longSide() != Math.max(origW, origH)) {
                double scale = (double) req.longSide() / Math.max(origW, origH);
                int w = (int) Math.round(origW * scale);
                int h = (int) Math.round(origH * scale);
                resize(mc, w, h);
                resized = true;
            }
            active = new Active(req.tag(), mc.options.hideGui, origW, origH, resized, System.nanoTime(),
                    resized ? WARMUP_FRAMES : 0);
            mc.options.hideGui = true;
        }

        @SubscribeEvent
        static void onFramePost(RenderFrameEvent.Post event) {
            if (active == null) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            Active a = active;
            if (a.warmupLeft > 0) {
                a.warmupLeft--;
                return;
            }
            active = null;
            RenderTarget target = mc.getMainRenderTarget();
            NativeImage image = Screenshot.takeScreenshot(target);
            long grabMs = (System.nanoTime() - a.startNanos) / 1_000_000;
            mc.options.hideGui = a.savedHideGui;
            if (a.resized) {
                resize(mc, a.origW, a.origH);
            }
            RealCamera.LOGGER.info("[spike] captured {} {}x{} in {} ms (frames incl.)",
                    a.tag, image.getWidth(), image.getHeight(), grabMs);
            save(mc, image, a.tag);
        }
    }

    private static void resize(Minecraft mc, int w, int h) {
        Window window = mc.getWindow();
        window.setWidth(w);
        window.setHeight(h);
        mc.getMainRenderTarget().resize(w, h, Minecraft.ON_OSX);
        mc.gameRenderer.resize(w, h);
    }

    private static void save(Minecraft mc, NativeImage image, String tag) {
        File dir = new File(mc.gameDirectory, "screenshots/realcamera_spike");
        String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS"))
                + "_" + tag + "_" + image.getWidth() + "x" + image.getHeight() + ".png";
        Util.ioPool().execute(() -> {
            try (image) {
                dir.mkdirs();
                image.writeToFile(new File(dir, name));
                RealCamera.LOGGER.info("[spike] saved {}", name);
            } catch (IOException e) {
                RealCamera.LOGGER.error("[spike] failed to save {}", name, e);
            }
        });
    }
}
