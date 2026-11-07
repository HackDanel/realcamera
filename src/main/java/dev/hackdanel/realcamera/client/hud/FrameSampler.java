package dev.hackdanel.realcamera.client.hud;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * Asynchronous read-back of the rendered world for the live histogram and for the autofocus, which needs to know
 * how much detail there is where it measures (phase detection cannot lock on a clear sky or a blank wall).
 * Read on the finished picture (end of the level render; with a shader pack, just before the GUI), before the
 * live depth-of-field preview blurs it. Uses two pixel-pack buffers so the CPU never waits for the GPU.
 */
public final class FrameSampler {
    public static final int BINS = 64;
    private static final int EVERY_FRAMES = 4;
    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private static final int[] pbo = new int[2];
    private static int pboW, pboH, frame, filled;
    /** Linear luminance samples of the last read frame (subsampled, rows bottom-up). */
    private static float[] samples = new float[0];
    private static int sampleW, sampleH, sampleStep, sampleScreenW, sampleScreenH;
    private static long sampledAtMs;
    /** Screen pixels per unit of tan(angle) of the frame that was read (from its projection). */
    private static double pxPerTan;
    private static final double[] pboPxPerTan = new double[2];
    /** The last frame read, RGBA, rows bottom-up (sampleScreenW x sampleScreenH). */
    private static byte[] frameRgba = new byte[0];

    private FrameSampler() {}

    /**
     * Called by {@link dev.hackdanel.realcamera.client.render.DofPreview} on the finished, still sharp picture
     * (the end of the level render, or with a shader pack just before the GUI) before it blurs it: the AF must
     * see the sharp frame.
     *
     * @param projection the level's projection of this frame
     */
    public static void sample(org.joml.Matrix4f projection) {
        if (!Viewfinder.isActive() || dev.hackdanel.realcamera.client.capture.PhotoCapture.capturing()) {
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
        pboPxPerTan[cur] = h / 2.0 * projection.m11();
        if (filled > 0) {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[cur ^ 1]);
            ByteBuffer buf = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
            if (buf != null) {
                read(buf, w, h);
                pxPerTan = pboPxPerTan[cur ^ 1];
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
        }
        filled++;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    private static void read(ByteBuffer buf, int w, int h) {
        int step = Math.max(1, Math.min(w, h) / 180);
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
        // the full frame too, for the AF's contrast (it needs every pixel, the samples above skip most)
        if (frameRgba.length != w * h * 4) {
            frameRgba = new byte[w * h * 4];
        }
        buf.get(0, frameRgba);
        sampleW = w / step;
        sampleH = h / step;
        sampleStep = step;
        sampleScreenW = w;
        sampleScreenH = h;
        sampledAtMs = System.currentTimeMillis();
    }

    /**
     * Local contrast of the last frame read around a point of the photo frame: the mean luminance difference
     * between neighbouring blocks of pixels relative to the mean luminance (so it does not depend on the
     * brightness). Blocks of a few pixels, not single pixels: a shader pack's dithering and temporal noise are
     * pixel-sized and would look like detail on a clear sky. Measured (3 px blocks, vanilla / Complementary): clear
     * sky 0.0001 / 0.0024, white concrete 0.0022 / 0.0042, grass meadow 0.018 / 0.03, cobblestone 0.16 / 0.13.
     *
     * @param u      frame x, -1..1
     * @param v      frame y, -1..1 (up)
     * @param radius half size of the measured patch, in frame half-heights
     * @return the contrast, or -1 when unknown (no recent frame)
     */
    public static double contrastAt(double u, double v, double radius) {
        return contrastAt(u, v, radius, Math.max(3, Math.round(sampleScreenH / 240f)));
    }

    /** {@link #contrastAt(double, double, double)} with blocks of {@code block} x {@code block} pixels. */
    public static double contrastAt(double u, double v, double radius, int block) {
        byte[] f = frameRgba;
        int w = sampleScreenW, h = sampleScreenH;
        if (f.length < w * h * 4 || w < 16 || h < 16 || System.currentTimeMillis() - sampledAtMs > 500
                || pxPerTan <= 0 || !Viewfinder.isActive()) {
            return -1;
        }
        double tanV = Math.tan(Math.toRadians(Viewfinder.sensorVfov()) / 2);
        double halfH = pxPerTan * tanV, halfW = halfH * Viewfinder.sensor().aspect();
        double px = w / 2.0 + u * halfW, py = h / 2.0 + v * halfH; // rows are bottom-up
        int cells = (int) Math.max(3, Math.round(radius * halfH / block)); // cells from the centre to the edge
        int x0 = (int) Math.round(px) - cells * block, y0 = (int) Math.round(py) - cells * block;
        int n = cells * 2;
        // clip the patch to the screen
        int cx0 = Math.max(0, (-x0 + block - 1) / block), cy0 = Math.max(0, (-y0 + block - 1) / block);
        int cx1 = Math.min(n, (w - x0) / block), cy1 = Math.min(n, (h - y0) / block);
        int nw = cx1 - cx0, nh = cy1 - cy0;
        if (nw < 3 || nh < 3) {
            return -1;
        }
        float[] mean = new float[nw * nh];
        float inv = 1f / (block * block);
        double total = 0;
        for (int cy = 0; cy < nh; cy++) {
            int by = y0 + (cy + cy0) * block;
            for (int cx = 0; cx < nw; cx++) {
                int bx = x0 + (cx + cx0) * block;
                float sum = 0;
                for (int y = by; y < by + block; y++) {
                    int row = y * w;
                    for (int x = bx; x < bx + block; x++) {
                        int p = (row + x) * 4;
                        sum += 0.2126f * SRGB_TO_LINEAR[f[p] & 255] + 0.7152f * SRGB_TO_LINEAR[f[p + 1] & 255]
                                + 0.0722f * SRGB_TO_LINEAR[f[p + 2] & 255];
                    }
                }
                mean[cy * nw + cx] = sum * inv;
                total += sum * inv;
            }
        }
        double diff = 0;
        int nd = 0;
        for (int cy = 0; cy < nh; cy++) {
            for (int cx = 0; cx < nw; cx++) {
                float c = mean[cy * nw + cx];
                if (cx + 1 < nw) {
                    diff += Math.abs(mean[cy * nw + cx + 1] - c);
                    nd++;
                }
                if (cy + 1 < nh) {
                    diff += Math.abs(mean[(cy + 1) * nw + cx] - c);
                    nd++;
                }
            }
        }
        return (diff / nd) / (total / (nw * nh) + 0.005);
    }

    /** Histogram of what the photo would look like with the given exposure gain (EV). */
    public static int[] histogram(double exposureEv) {
        double gain = Math.pow(2, exposureEv);
        return histogram(v -> v * gain);
    }

    /**
     * Histogram of what the photo would look like.
     *
     * @param look linear luminance of the game's picture to linear luminance of the photo
     */
    public static int[] histogram(java.util.function.DoubleUnaryOperator look) {
        int[] bins = new int[BINS];
        // the samples take 256 values at most: map each once
        float[] lut = new float[257];
        for (int i = 0; i <= 256; i++) lut[i] = (float) look.applyAsDouble(i / 256.0);
        for (float v : samples) {
            float x = Math.min(1, Math.max(0, v)) * 256;
            int i0 = Math.min(255, (int) x);
            float lin = Math.max(0, Math.min(1, lut[i0] + (lut[i0 + 1] - lut[i0]) * (x - i0)));
            double srgb = lin <= 0.0031308 ? lin * 12.92 : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
            bins[Math.min(BINS - 1, (int) (srgb * BINS))]++;
        }
        return bins;
    }

    /**
     * Log-average linear luminance of the last frame read over the metering grid (9 x 6 cells, top-down; a patch
     * in the middle of each cell, as {@link dev.hackdanel.realcamera.client.render.ShaderCompat#packRelight} measures
     * the photo), the photo's frame being the largest rectangle of the sensor's aspect in the middle of the screen.
     *
     * @return the luminance, or -1 when there is no recent frame or no weighted cell
     */
    public static double meteredLuminance(float[] weights, double aspect) {
        if (samples.length == 0 || weights == null || weights.length != 54
                || System.currentTimeMillis() - sampledAtMs > 1000) {
            return -1;
        }
        int sw = sampleScreenW, sh = sampleScreenH;
        double fw = sw, fh = sh;
        if ((double) sw / sh >= aspect) fw = sh * aspect;
        else fh = sw / aspect;
        double x0 = (sw - fw) / 2, y0 = (sh - fh) / 2;
        double sum = 0, wsum = 0;
        for (int cy = 0; cy < 6; cy++) {
            for (int cx = 0; cx < 9; cx++) {
                float wt = weights[cy * 9 + cx];
                if (wt <= 0) continue;
                for (int j = 0; j < 4; j++) {
                    for (int i = 0; i < 4; i++) {
                        double x = x0 + (cx + 0.3 + 0.4 * (i + 0.5) / 4) * fw / 9;
                        double yTop = y0 + (cy + 0.3 + 0.4 * (j + 0.5) / 4) * fh / 6;
                        int sx = Math.min(sampleW - 1, (int) (x / sampleStep));
                        int sy = Math.min(sampleH - 1, (int) ((sh - 1 - yTop) / sampleStep)); // rows bottom-up
                        if (sx < 0 || sy < 0) continue;
                        sum += Math.log(Math.max(samples[sy * sampleW + sx], 1e-4)) * wt;
                        wsum += wt;
                    }
                }
            }
        }
        return wsum > 0 ? Math.exp(sum / wsum) : -1;
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
