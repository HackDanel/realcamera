package dev.hackdanel.realcamera.client.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.hackdanel.realcamera.RealCamera;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * Stage 0 spike for long exposures: every rendered frame is read back asynchronously through two
 * pixel-pack buffers and summed in linear light on the CPU. The result is the time-average of what
 * the camera saw, so motion produces real blur and trails. Window resolution only.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class AccumulationSpike {
    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private static State state;

    private static final class State {
        final long endNanos;
        final long startNanos;
        final boolean savedHideGui;
        final int w, h;
        final int[] pbo = new int[2];
        final float[] sum;
        int frameIndex;
        int accumulated;
        long cpuNanos;

        State(int w, int h, double seconds, boolean savedHideGui) {
            this.w = w;
            this.h = h;
            this.sum = new float[w * h * 3];
            this.savedHideGui = savedHideGui;
            this.startNanos = System.nanoTime();
            this.endNanos = startNanos + (long) (seconds * 1e9);
            for (int i = 0; i < 2; i++) {
                pbo[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
    }

    private AccumulationSpike() {}

    public static void start(double seconds) {
        Minecraft mc = Minecraft.getInstance();
        if (state != null || mc.level == null) {
            return;
        }
        RenderTarget t = mc.getMainRenderTarget();
        state = new State(t.width, t.height, seconds, mc.options.hideGui);
        mc.options.hideGui = true;
        RealCamera.LOGGER.info("[spike] long exposure {}s started at {}x{}", seconds, t.width, t.height);
    }

    public static boolean isBusy() {
        return state != null;
    }

    @SubscribeEvent
    static void onFramePost(RenderFrameEvent.Post event) {
        State s = state;
        if (s == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        if (target.width != s.w || target.height != s.h) {
            RealCamera.LOGGER.warn("[spike] window resized during exposure, aborting");
            finish(mc, s, false);
            return;
        }
        long t0 = System.nanoTime();
        boolean last = t0 >= s.endNanos;

        // Kick off async read of this frame into pbo[cur].
        int cur = s.frameIndex & 1;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, s.pbo[cur]);
        RenderSystem.bindTexture(target.getColorTextureId());
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);

        // Consume the previous frame (already transferred, so mapping does not stall).
        if (s.frameIndex > 0) {
            accumulate(s, s.pbo[cur ^ 1]);
        }
        if (last) {
            accumulate(s, s.pbo[cur]); // final frame: wait for it
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        s.frameIndex++;
        s.cpuNanos += System.nanoTime() - t0;

        if (last) {
            finish(mc, s, true);
        }
    }

    private static void accumulate(State s, int pbo) {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        ByteBuffer buf = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
        if (buf == null) {
            return;
        }
        float[] sum = s.sum;
        int n = s.w * s.h;
        for (int i = 0, j = 0; i < n; i++, j += 3) {
            int p = i << 2;
            sum[j] += SRGB_TO_LINEAR[buf.get(p) & 0xFF];
            sum[j + 1] += SRGB_TO_LINEAR[buf.get(p + 1) & 0xFF];
            sum[j + 2] += SRGB_TO_LINEAR[buf.get(p + 2) & 0xFF];
        }
        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        s.accumulated++;
    }

    private static void finish(Minecraft mc, State s, boolean save) {
        state = null;
        mc.options.hideGui = s.savedHideGui;
        GL15.glDeleteBuffers(s.pbo[0]);
        GL15.glDeleteBuffers(s.pbo[1]);
        if (!save || s.accumulated == 0) {
            return;
        }
        double wallMs = (System.nanoTime() - s.startNanos) / 1e6;
        RealCamera.LOGGER.info("[spike] long exposure done: {} frames in {} ms, readback+sum {} ms/frame",
                s.accumulated, Math.round(wallMs), String.format("%.2f", s.cpuNanos / 1e6 / s.frameIndex));
        float inv = 1f / s.accumulated;
        int w = s.w, h = s.h;
        float[] sum = s.sum;
        Util.ioPool().execute(() -> {
            try (NativeImage img = new NativeImage(w, h, false)) {
                for (int y = 0; y < h; y++) {
                    int srcRow = (h - 1 - y) * w; // GL rows are bottom-up
                    for (int x = 0; x < w; x++) {
                        int j = (srcRow + x) * 3;
                        int r = toSrgb8(sum[j] * inv);
                        int g = toSrgb8(sum[j + 1] * inv);
                        int b = toSrgb8(sum[j + 2] * inv);
                        img.setPixelRGBA(x, y, 0xFF000000 | (b << 16) | (g << 8) | r); // ABGR
                    }
                }
                File dir = new File(mc.gameDirectory, "screenshots/realcamera_spike");
                dir.mkdirs();
                String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS"))
                        + "_longexp_" + w + "x" + h + ".png";
                img.writeToFile(new File(dir, name));
                RealCamera.LOGGER.info("[spike] saved {}", name);
            } catch (IOException e) {
                RealCamera.LOGGER.error("[spike] failed to save long exposure", e);
            }
        });
    }

    private static int toSrgb8(float lin) {
        double c = lin <= 0.0031308 ? lin * 12.92 : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
        return Math.max(0, Math.min(255, (int) Math.round(c * 255)));
    }
}
