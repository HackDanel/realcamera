package dev.hackdanel.realcamera.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.camera.CameraRays;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Works out what the active shader pack (Iris) does differently from the vanilla renderer, so that any pack works,
 * not only the one the mod was tested with:
 * <ul>
 * <li>whether a pack is in use and whether Iris is drawing its shadow pass (level events fired then are not the
 *     player's view);</li>
 * <li>whether the main depth buffer holds the real scene depth: some packs leave it cleared, at a fixed layer or
 *     in their own format. It is probed against ray casts now and then; the live depth-of-field preview stays off
 *     while it disagrees, and the photo falls back to the ray grid (see {@code DepthUpsampler.agrees});</li>
 * <li>how bright the pack draws the scene compared with the vanilla light curve (night brightness, auto exposure,
 *     tone mapping differ from pack to pack): the photo is exposed from what the pack actually drew.</li>
 * </ul>
 * Everything is re-learned when a pack is switched on or off.
 */
public final class ShaderCompat {
    private static boolean irisChecked;
    @Nullable
    private static Object irisApi;
    @Nullable
    private static Method irisInUse, irisShadowPass;

    private static boolean lastPack;
    /** Score of recent depth probes: > 0 trusted, < 0 not. */
    private static int depthScore = 2;
    private static int probeFrame;

    private ShaderCompat() {}

    private static void init() {
        if (irisChecked) return;
        irisChecked = true;
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = c.getMethod("getInstance").invoke(null);
            irisInUse = c.getMethod("isShaderPackInUse");
            try {
                irisShadowPass = c.getMethod("isRenderingShadowPass");
            } catch (NoSuchMethodException e) {
                irisShadowPass = null;
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            irisApi = null;
        }
    }

    private static boolean call(@Nullable Method m) {
        if (irisApi == null || m == null) return false;
        try {
            return (boolean) m.invoke(irisApi);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** An Iris shader pack is active: it finishes the picture after the level render. */
    public static boolean packInUse() {
        init();
        boolean pack = call(irisInUse);
        if (pack != lastPack) {
            // another pack (or none): learn its depth buffer anew
            lastPack = pack;
            depthScore = 2;
            probeFrame = 0;
        }
        return pack;
    }

    /** Iris is drawing the shadow map: level render events fired now are not the player's view. */
    public static boolean shadowPass() {
        init();
        return call(irisShadowPass);
    }

    /** Whether the main depth buffer can be used as the scene depth (always without a pack). */
    public static boolean depthTrusted() {
        return !packInUse() || depthScore > 0;
    }

    /**
     * The depth-buffer value (0..1) the pack's own depth of field should focus on while the camera is raised: the
     * lens's focus distance through the level projection of the last frame. NaN when the camera is not in use
     * (the pack keeps focusing under the crosshair as usual).
     */
    public static float packFocusDepth() {
        if (!dev.hackdanel.realcamera.client.camera.Viewfinder.isActive()) return Float.NaN;
        Matrix4f p = DofPreview.levelProjection();
        if (p.m23() == 0) return Float.NaN;
        float zv = -Math.max(0.1f, Math.min(5000f, dev.hackdanel.realcamera.client.camera.Viewfinder.liveFocusDistance()));
        float w = p.m23() * zv + p.m33();
        if (Math.abs(w) < 1e-6f) return Float.NaN;
        float ndc = (p.m22() * zv + p.m32()) / w;
        return Math.max(0f, Math.min(0.999f, ndc * 0.5f + 0.5f));
    }

    /** Overwrite a 1 x 1 R32F texture (Iris's centre depth) with one value. */
    public static void writeDepth1x1(int texture, float value) {
        if (texture <= 0) return;
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevUnpack = GL11.glGetInteger(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (prevUnpack != 0) org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            FloatBuffer one = stack.floats(value);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL30.GL_RED, GL11.GL_FLOAT, one);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
            if (prevUnpack != 0) org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, prevUnpack);
        }
    }

    /** The photo's own check of the full depth map (see {@code DepthUpsampler.agrees}): weighs more than a probe. */
    public static void reportPhotoDepth(boolean agreed) {
        if (!lastPack) return;
        depthScore = agreed ? Math.max(depthScore, 2) : Math.min(depthScore, -3);
    }

    /**
     * Every 40 frames with a pack: read the depth buffer at a 5 x 4 grid of points and compare it with ray casts
     * through the same pixels. Call at the end of the level render (the depth buffer still holds the world).
     */
    public static void probeDepth(RenderTarget main, Matrix4f projection) {
        if (!packInUse() || ++probeFrame % 40 != 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getCameraEntity() == null) return;
        int w = main.width, h = main.height;
        if (w < 16 || h < 16) return;
        int gw = 5, gh = 4;
        float[] gpu = new float[gw * gh];
        float m00 = projection.m00(), m11 = projection.m11();
        Matrix4f inv = new Matrix4f(projection).invert();
        float pa = inv.m22(), pb = inv.m32(), pc = inv.m23(), pd = inv.m33();
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer one = stack.mallocFloat(1);
            for (int gy = 0; gy < gh; gy++) {
                for (int gx = 0; gx < gw; gx++) {
                    int x = (int) ((gx + 0.5f) * w / gw);
                    int yTop = (int) ((gy + 0.5f) * h / gh);
                    GL11.glReadPixels(x, h - 1 - yTop, 1, 1, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, one);
                    float z = one.get(0);
                    float m;
                    if (z >= 0.999999f) m = 10_000;
                    else if (z <= 1e-6f) m = -1;
                    else {
                        float n = z * 2 - 1;
                        m = Math.max(0.05f, Math.min(10_000, -(pa * n + pb) / (pc * n + pd)));
                    }
                    gpu[gy * gw + gx] = m;
                }
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        }
        // rays through the same pixels: a frame whose half-heights are 1/m11 (tan) and half-widths 1/m00
        double vfov = Math.toDegrees(2 * Math.atan(1 / m11));
        double aspect = m11 / m00;
        CameraRays rays = new CameraRays(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(), vfov, aspect, 256);
        float[][] grids = rays.grids(gw, gh, false);
        int unknown = 0;
        for (float g : gpu) if (g < 0) unknown++;
        boolean ok = unknown < gpu.length / 2
                && dev.hackdanel.realcamera.processing.DepthUpsampler.agrees(gpu, gw, gh, grids[0], grids[2], gw, gh, true);
        int before = depthScore;
        depthScore = Math.max(-3, Math.min(3, depthScore + (ok ? 1 : -1)));
        if ((before > 0) != (depthScore > 0)) {
            RealCamera.LOGGER.info("Shader pack depth buffer {} for the live depth of field ({})",
                    depthScore > 0 ? "usable" : "not usable", dev.hackdanel.realcamera.processing.DepthUpsampler.lastCheck);
        }
    }

    /**
     * Brightness correction for a frame drawn by a shader pack: how much the photo must be brightened so the
     * metered area comes out as the vanilla renderer would have drawn it in full light (the reference the exposure
     * model is built on). The pack's picture is measured where the meter looked (log average over surfaces, not the
     * sky) and compared with the surfaces' own reflectance. 1 without a pack.
     *
     * @param img      linear RGB of the captured frame
     * @param depth    depth per pixel (sky &ge; 9999)
     * @param weights  9 x 6 metering weights (row-major, top-down), as the camera metered
     * @param albedo   log-average reflectance of the metered surfaces (linear, ~0.3 for a typical scene), or &le; 0
     * @param vanilla  the correction the vanilla light curve would give (fallback and bounds)
     */
    public static double packRelight(float[] img, float[] depth, int w, int h, float[] weights, double albedo, double vanilla) {
        if (albedo <= 0 || weights == null || weights.length != 54) return vanilla;
        double sum = 0, wsum = 0;
        int gx = 9, gy = 6;
        for (int cy = 0; cy < gy; cy++) {
            for (int cx = 0; cx < gx; cx++) {
                float wt = weights[cy * gx + cx];
                if (wt <= 0) continue;
                // a small patch around the cell centre
                int x0 = (int) ((cx + 0.3) * w / gx), x1 = (int) ((cx + 0.7) * w / gx);
                int y0 = (int) ((cy + 0.3) * h / gy), y1 = (int) ((cy + 0.7) * h / gy);
                int stepX = Math.max(1, (x1 - x0) / 8), stepY = Math.max(1, (y1 - y0) / 8);
                for (int y = y0; y < y1; y += stepY) {
                    for (int x = x0; x < x1; x += stepX) {
                        int i = y * w + x;
                        if (depth != null && depth[i] >= 9_999) continue;
                        int j = i * 3;
                        double lum = 0.2126 * img[j] + 0.7152 * img[j + 1] + 0.0722 * img[j + 2];
                        sum += Math.log(Math.max(lum, 1e-4)) * wt;
                        wsum += wt;
                    }
                }
            }
        }
        if (wsum <= 0) return vanilla; // only sky in the metered area: nothing to compare with
        return packRelight(Math.exp(sum / wsum), albedo, vanilla);
    }

    /**
     * {@link #packRelight(float[], float[], int, int, float[], double, double)} from the measured log-average
     * luminance (linear) of the metered surfaces in the pack's picture.
     */
    public static double packRelight(double measured, double albedo, double vanilla) {
        if (albedo <= 0 || !(measured > 0)) return vanilla;
        // a vanilla frame in full light shows a surface at about its reflectance x 0.85 (side shading)
        double factor = albedo * 0.85 / measured;
        // never more than 3 stops away from what the light curve says: a pack can draw a scene black or white
        return Math.max(vanilla / 8, Math.min(vanilla * 8, Math.max(0.25, Math.min(64, factor))));
    }
}
