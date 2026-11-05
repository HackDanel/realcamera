package dev.hackdanel.realcamera.client.hud;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * Asynchronous read-back of the rendered world (before the GUI is drawn) for the live histogram.
 * Uses two pixel-pack buffers so the CPU never waits for the GPU.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class FrameSampler {
    public static final int BINS = 64;
    private static final int EVERY_FRAMES = 6;
    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private static final int[] pbo = new int[2];
    private static int pboW, pboH, frame, filled;
    /** Linear luminance samples of the last read frame (subsampled). */
    private static float[] samples = new float[0];

    private FrameSampler() {}

    @SubscribeEvent
    static void onGuiPre(RenderGuiEvent.Pre event) {
        if (!Viewfinder.isActive()) {
            return;
        }
        if (++frame % EVERY_FRAMES != 0) {
            return;
        }
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        int w = target.width, h = target.height;
        if (pbo[0] == 0 || w != pboW || h != pboH) {
            release();
            for (int i = 0; i < 2; i++) {
                pbo[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) w * h * 4, GL15.GL_STREAM_READ);
            }
            pboW = w;
            pboH = h;
            filled = 0;
        }
        int cur = (frame / EVERY_FRAMES) & 1;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[cur]);
        RenderSystem.bindTexture(target.getColorTextureId());
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        if (filled > 0) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[cur ^ 1]);
            ByteBuffer buf = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
            if (buf != null) {
                read(buf, w, h);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
        }
        filled++;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    private static void read(ByteBuffer buf, int w, int h) {
        int step = Math.max(1, Math.min(w, h) / 90);
        int n = (w / step) * (h / step);
        float[] out = new float[n];
        int k = 0;
        for (int y = 0; y + step <= h && k < n; y += step) {
            for (int x = 0; x + step <= w && k < n; x += step) {
                int p = (y * w + x) * 4;
                out[k++] = 0.2126f * SRGB_TO_LINEAR[buf.get(p) & 255] + 0.7152f * SRGB_TO_LINEAR[buf.get(p + 1) & 255]
                        + 0.0722f * SRGB_TO_LINEAR[buf.get(p + 2) & 255];
            }
        }
        samples = out;
    }

    /** Histogram of what the photo would look like with the given exposure gain (EV). */
    public static int[] histogram(double exposureEv) {
        int[] bins = new int[BINS];
        float gain = (float) Math.pow(2, exposureEv);
        for (float v : samples) {
            float lin = Math.min(1, v * gain);
            double srgb = lin <= 0.0031308 ? lin * 12.92 : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
            bins[Math.min(BINS - 1, (int) (srgb * BINS))]++;
        }
        return bins;
    }

    private static void release() {
        for (int i = 0; i < 2; i++) {
            if (pbo[i] != 0) {
                GL15.glDeleteBuffers(pbo[i]);
                pbo[i] = 0;
            }
        }
    }
}
