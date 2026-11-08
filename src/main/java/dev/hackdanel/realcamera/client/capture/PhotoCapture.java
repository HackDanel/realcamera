package dev.hackdanel.realcamera.client.capture;

import dev.hackdanel.realcamera.client.render.ShaderCompat;
import dev.hackdanel.realcamera.client.render.DepthLayers;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.OpticalChain;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.client.camera.CameraRays;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.photo.PhotoUploader;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.config.ClientConfig;
import dev.hackdanel.realcamera.config.ServerConfig;
import dev.hackdanel.realcamera.optics.Exposure;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.optics.Optics;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.processing.DepthUpsampler;
import dev.hackdanel.realcamera.processing.ExifWriter;
import dev.hackdanel.realcamera.processing.RawFile;
import dev.hackdanel.realcamera.client.photo.LocalOriginals;
import dev.hackdanel.realcamera.processing.ImageParams;
import dev.hackdanel.realcamera.processing.LayeredDepth;
import dev.hackdanel.realcamera.processing.ImagePipeline;
import dev.hackdanel.realcamera.processing.JpegCodec;
import dev.hackdanel.realcamera.spec.ColorProfileSpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * Turns a shutter release into a photo. The frame is rendered at the sensor's output resolution
 * with no HUD and no hand (one warm-up frame first, see docs/stage0-capture.md). Exposures of
 * 1/4 s and longer (and bulb) really last that long: every rendered frame is accumulated in linear
 * light through pixel-pack buffers, so motion leaves trails. Development, export and upload run on
 * a worker thread; several shots can wait there (the camera's buffer).
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class PhotoCapture {
    public static final double LONG_EXPOSURE_S = 0.25;
    private static final int MEMORY_BUFFER = 6;
    private static final int WARMUP_FRAMES = Integer.getInteger("realcamera.captureWarmup", 1);
    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /**
     * Everything frozen at the moment the shutter fired. seconds = -1 for bulb. motion is in fractions
     * of the image width.
     */
    public record Shot(dev.hackdanel.realcamera.camera.CameraRef ref, OpticalChain chain, CameraSettings settings, Exposure.Result exposure,
                       float focal, float focusDistance, double sceneEv, boolean blockLit, int frameNumber,
                       String cameraName, String lensName, double seconds, ImageParams.Motion motion,
                       ImageParams.Sun sun, ImageParams.Extras extras, int nonce, double gameLight, double sceneK,
                       double sensorC, Metering metering) {
        boolean longExposure() {
            return seconds < 0 || seconds >= LONG_EXPOSURE_S;
        }

        Shot withField(@javax.annotation.Nullable ImageParams.MotionField f) {
            return new Shot(ref, chain, settings, exposure, focal, focusDistance, sceneEv, blockLit, frameNumber, cameraName,
                    lensName, seconds, motion.withField(f), sun, extras, nonce, gameLight, sceneK, sensorC, metering);
        }
    }

    /**
     * Where the camera metered (9 x 6 weights, top-down) and the log-average reflectance of the metered surfaces
     * (-1 = only sky): lets a frame drawn by a shader pack be exposed from what the pack actually drew.
     */
    public record Metering(float[] weights, double albedo) {}

    private static final java.util.concurrent.ForkJoinPool DEVELOP_POOL =
            new java.util.concurrent.ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RealCamera developer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static Shot pending;
    private static Active active;
    private static volatile int developing;
    private static volatile boolean bulbStop;
    /** Long-exposure noise reduction: the camera takes a dark frame as long as the exposure and is busy until then. */
    private static long darkFrameUntil;

    private static final class Active {
        final Shot shot;
        final boolean savedHideGui;
        final net.minecraft.world.entity.Entity savedCamera;
        final int origW, origH, w, h;
        /**
         * Size of the frame the game renders during the capture and where the photo sits in it. Without a shader pack
         * the window is resized to the sensor's output, so this is the photo itself. A shader pack keeps its state
         * (auto exposure, temporal accumulation, its title screen timer) in buffers that a resize recreates, so with a
         * pack the frame stays at the window's size and the photo is the centred crop with the sensor's aspect.
         */
        int fw, fh, cx, cy;
        boolean noResize;
        /** With no resize the vertical field of view the frame is rendered with (wider when the window is narrower). */
        double renderVfov = -1;
        int warmup = WARMUP_FRAMES;
        // long exposure
        long startNanos = -1;
        final int[] pbo = new int[2];
        float[] sum;
        int frameIndex, accumulated;
        float[] depthGrid, lightGrid, entityGrid;
        ImageParams.MotionField field;
        int gw, gh;
        double savedViewScale = 1;
        /** Per-pixel depth from the GPU of the captured (first) frame, or null. */
        float[] gpuDepth;
        /** The same frame's depth without translucent blocks and particles, and with the translucent blocks; or null. */
        float[] gpuOpaque, gpuSurface;
        boolean inWater;
        float[] kindGrid;
        /** Drawn by a shader pack (exposed from what it drew, see {@link ShaderCompat#packRelight}). */
        boolean pack;
        /** Geometry of the open long exposure, for flashes of other photographers that fire meanwhile. */
        CameraRays rays;
        /** Light those flashes added, on the ray grid (exposure relative to a correct one), or null. */
        float[] remote;

        Active(Shot shot, boolean savedHideGui, net.minecraft.world.entity.Entity savedCamera, int origW, int origH, int w, int h) {
            this.shot = shot;
            this.savedHideGui = savedHideGui;
            this.savedCamera = savedCamera;
            this.origW = origW;
            this.origH = origH;
            this.w = w;
            this.h = h;
            this.fw = w;
            this.fh = h;
        }
    }

    private PhotoCapture() {}

    public static void request(Shot shot) {
        bulbStop = false;
        pending = shot;
    }

    /** Ends a bulb exposure (shutter button released). */
    public static void stopBulb() {
        bulbStop = true;
    }

    /** True if another frame can be taken now: nothing is being captured and the buffer has room. */
    public static boolean canAccept() {
        int buffer = Viewfinder.isActive() ? Math.min(MEMORY_BUFFER, Viewfinder.body().shutter().bufferShots()) : 1;
        return pending == null && active == null && developing < buffer && PhotoUploader.pending() < 12
                && System.nanoTime() >= darkFrameUntil;
    }

    public static boolean busy() {
        return pending != null || active != null || developing > 0 || PhotoUploader.busy()
                || System.nanoTime() < darkFrameUntil;
    }

    public static boolean capturing() {
        return active != null;
    }

    public static int buffered() {
        return developing + PhotoUploader.pending();
    }

    /** The photo covers exactly the shot's sensor frame, whatever the viewfinder state is now. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onFov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        Active a = active;
        if (a != null && event.usedConfiguredFov()) {
            event.setFOV(a.renderVfov > 0 ? a.renderVfov
                    : a.shot.chain().renderVfovDeg(a.shot.focal(), a.shot.settings(), a.shot.focusDistance()));
        }
    }

    /** A shifted lens: the frame is rendered pitched up by atan(shift / f) and re-projected when developing. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void onCameraAngles(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        Active a = active;
        if (a != null) {
            double pitch = a.shot.chain().shiftPitchDeg(a.shot.focal(), a.shot.settings());
            if (pitch != 0) {
                event.setPitch((float) (event.getPitch() - pitch));
            }
        }
    }

    @SubscribeEvent
    static void onFramePre(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (pending == null || active != null || mc.level == null) {
            return;
        }
        Shot shot = pending;
        pending = null;
        Window window = mc.getWindow();
        int longSide = shot.chain().sensor().outputLongSide();
        float aspect = shot.chain().sensor().aspect();
        int w = aspect >= 1 ? longSide : Math.round(longSide * aspect);
        int h = aspect >= 1 ? Math.round(longSide / aspect) : longSide;
        boolean pack = ShaderCompat.packInUse();
        int ww = window.getWidth(), wh = window.getHeight();
        if (pack && ww >= 16 && wh >= 16) {
            // the crop of the window with the sensor's aspect
            float winAspect = (float) ww / wh;
            if (winAspect >= aspect) {
                h = wh;
                w = Math.min(ww, Math.round(wh * aspect));
            } else {
                w = ww;
                h = Math.min(wh, Math.round(ww / aspect));
            }
        }
        active = new Active(shot, mc.options.hideGui, mc.getCameraEntity(), ww, wh, w, h);
        active.pack = pack;
        if (pack && ww >= 16 && wh >= 16) {
            Active a = active;
            a.noResize = true;
            a.fw = ww;
            a.fh = wh;
            a.cx = (ww - w) / 2;
            a.cy = (wh - h) / 2;
            double v = shot.chain().renderVfovDeg(shot.focal(), shot.settings(), shot.focusDistance());
            float winAspect = (float) ww / wh;
            if (winAspect < aspect) {
                // the window is narrower than the frame: render taller so the crop has the sensor's width
                double t = Math.tan(Math.toRadians(v) / 2) * aspect / winAspect;
                v = Math.toDegrees(2 * Math.atan(t));
            }
            a.renderVfov = v;
        }
        if (shot.ref().isTripod() && mc.level.getEntity(shot.ref().tripodId()) instanceof net.minecraft.world.entity.Entity tripod) {
            // the tripod's camera takes the picture, wherever the photographer is (self-timer selfies)
            mc.setCameraEntity(tripod);
        }
        if (!active.noResize) {
            resize(mc, w, h);
        }
        mc.options.hideGui = true;
        // a long lens shows animals far beyond the normal entity draw distance (the server's analysis assumes 4x)
        active.savedViewScale = net.minecraft.world.entity.Entity.getViewScale();
        net.minecraft.world.entity.Entity.setViewScale(Math.max(active.savedViewScale, 4));
    }

    @SubscribeEvent
    static void onFramePost(RenderFrameEvent.Post event) {
        Active a = active;
        if (a == null) {
            return;
        }
        if (a.warmup-- > 0) {
            // The frame is about to be shown on screen at its own (sensor) size, so the window would show a
            // magnified corner of it, a jump of the view. Show the shutter blackout instead.
            blackout(Minecraft.getInstance().getMainRenderTarget());
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!a.shot.longExposure()) {
            active = null;
            NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            if (image.getWidth() != a.fw || image.getHeight() != a.fh) {
                // the window was resized meanwhile: no usable frame
                image.close();
                active = null;
                finish(mc, a);
                return;
            }
            int gw = 192, gh = Math.max(1, Math.round(gw / a.shot.chain().sensor().aspect()));
            CameraRays rays = rays(mc, a.shot);
            float[][] grids = rays.grids(gw, gh, true);
            Shot shot = a.shot.withField(rays.motionField());
            // other photographers' flashes that fired while this (short) exposure was open
            long now = System.nanoTime(), open = (long) (Math.max(0, a.shot.seconds()) * 1e9);
            float[] remote = null;
            for (RemoteFlash f : recentFlashes) {
                if (now - f.nanos() <= open) remote = add(remote, flashGrid(rays, grids[0], gw, gh, a.shot, f));
            }
            finish(mc, a);
            int[] rgb = toRgb(image, a.cx, a.cy, a.w, a.h);
            image.close();
            submit(mc, shot, rgb, null, a.w, a.h, grids[0], grids[1], grids[2], a.gpuDepth, layers(a, grids[3]), gw, gh,
                    a.shot.seconds(), remote, a.pack);
            return;
        }
        accumulateFrame(mc, a);
    }

    /**
     * The depth buffer of the frame that becomes the photo, read at the end of the level render (before the
     * hand and the GUI clear it) and turned into metres along the view axis with that frame's projection.
     */
    @SubscribeEvent
    static void onLevelStage(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        Active a = active;
        if (a == null || a.warmup > 0 || a.gpuDepth != null
                || event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL
                || ShaderCompat.shadowPass()) {
            return;
        }
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target.width != a.fw || target.height != a.fh) {
            return;
        }
        try {
            double pitch = a.shot.chain().shiftPitchDeg(a.shot.focal(), a.shot.settings());
            org.joml.Matrix4f proj = event.getProjectionMatrix();
            float[] full = readDepth(target.getDepthTextureId(), target.width, target.height, proj, pitch);
            // behind water, stained glass and ice (see DepthLayers): the opaque world and the translucent surfaces
            int opaqueTex = DepthLayers.opaqueTexture(), surfaceTex = DepthLayers.surfaceTexture();
            float[] opaque = null, surface = null;
            if (!a.pack && opaqueTex >= 0 && surfaceTex >= 0) {
                if (Minecraft.useShaderTransparency()) {
                    // Fabulous: the main target holds only the opaque world, particles and water have their own
                    opaque = full;
                    surface = readDepth(surfaceTex, target.width, target.height, proj, pitch);
                    int particlesTex = DepthLayers.particlesTexture();
                    float[] particles = particlesTex >= 0 ? readDepth(particlesTex, target.width, target.height, proj, pitch) : surface;
                    full = new float[opaque.length];
                    for (int i = 0; i < full.length; i++) full[i] = Math.min(surface[i], particles[i]);
                } else {
                    opaque = readDepth(opaqueTex, target.width, target.height, proj, pitch);
                    surface = readDepth(surfaceTex, target.width, target.height, proj, pitch);
                }
                a.inWater = Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera()
                        == net.minecraft.world.level.material.FogType.WATER;
            }
            a.gpuDepth = a.noResize ? crop(full, a.fw, a.cx, a.cy, a.w, a.h, 1) : full;
            if (opaque != null) {
                a.gpuOpaque = a.noResize ? crop(opaque, a.fw, a.cx, a.cy, a.w, a.h, 1) : opaque;
                a.gpuSurface = a.noResize ? crop(surface, a.fw, a.cx, a.cy, a.w, a.h, 1) : surface;
            }
        } catch (RuntimeException e) {
            RealCamera.LOGGER.warn("Could not read the depth buffer", e);
        }
    }

    private static float[] readDepth(int texture, int w, int h, org.joml.Matrix4f projection, double levelPitchDeg) {
        java.nio.FloatBuffer buf = org.lwjgl.system.MemoryUtil.memAllocFloat(w * h);
        try {
            RenderSystem.bindTexture(texture);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
            RenderSystem.bindTexture(0);
            // view-space z is linear in NDC depth through the inverse projection: z = (a*n + b) / (c*n + d)
            org.joml.Matrix4f inv = new org.joml.Matrix4f(projection).invert();
            float pa = inv.m22(), pb = inv.m32(), pc = inv.m23(), pd = inv.m33();
            // shifted lens: the frame is pitched up, but the plane of focus stays parallel to the (level) sensor
            float sinB = (float) Math.sin(Math.toRadians(levelPitchDeg)), cosB = (float) Math.cos(Math.toRadians(levelPitchDeg));
            boolean level = Math.abs(levelPitchDeg) > 1e-3;
            float[] out = new float[w * h];
            java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
                int src = (h - 1 - y) * w; // GL rows are bottom-up
                for (int x = 0; x < w; x++) {
                    float z = buf.get(src + x);
                    float m;
                    if (z >= 0.999999f) {
                        m = 10_000; // sky
                    } else if (z <= 1e-6f) {
                        m = -1; // nothing can be on the near plane: a shader pack cleared it to 0 (unknown)
                    } else {
                        float n = z * 2 - 1;
                        m = -(pa * n + pb) / (pc * n + pd);
                        if (level) {
                            org.joml.Vector4f v = inv.transform(new org.joml.Vector4f((x + 0.5f) / w * 2 - 1,
                                    1 - (y + 0.5f) / h * 2, n, 1));
                            m = -(v.y / v.w) * sinB - (v.z / v.w) * cosB;
                        }
                        m = Math.max(0.05f, Math.min(10_000, m));
                    }
                    out[y * w + x] = m;
                }
            });
            return out;
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buf);
        }
    }

    // ------------------------------------------------------------------ long exposure

    private static void accumulateFrame(Minecraft mc, Active a) {
        RenderTarget target = mc.getMainRenderTarget();
        if (a.startNanos < 0) {
            a.startNanos = System.nanoTime();
            a.sum = new float[a.w * a.h * 3];
            a.gw = 192;
            a.gh = Math.max(1, Math.round(a.gw / a.shot.chain().sensor().aspect()));
            a.rays = rays(mc, a.shot);
            float[][] grids = a.rays.grids(a.gw, a.gh, true);
            a.depthGrid = grids[0];
            a.lightGrid = grids[1];
            a.entityGrid = grids[2];
            a.kindGrid = grids[3];
            for (int i = 0; i < 2; i++) {
                a.pbo[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, a.pbo[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) a.fw * a.fh * 4, GL15.GL_STREAM_READ);
            }
        }
        double elapsed = (System.nanoTime() - a.startNanos) / 1e9;
        boolean bulb = a.shot.seconds() < 0;
        boolean last = bulb ? (bulbStop || elapsed > 3600) : elapsed >= a.shot.seconds();
        int cur = a.frameIndex & 1;
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, a.pbo[cur]);
        RenderSystem.bindTexture(target.getColorTextureId());
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        if (a.frameIndex > 0) {
            accumulate(a, a.pbo[cur ^ 1]);
        }
        if (last) {
            accumulate(a, a.pbo[cur]);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        a.frameIndex++;
        if (!last) {
            // the electronic viewfinder blacks out while the shutter is open
            blackout(target);
            return;
        }
        active = null;
        GL15.glDeleteBuffers(a.pbo[0]);
        GL15.glDeleteBuffers(a.pbo[1]);
        finish(mc, a);
        float inv = 1f / Math.max(1, a.accumulated);
        float[] linear = a.sum;
        for (int i = 0; i < linear.length; i++) {
            linear[i] *= inv;
        }
        RealCamera.LOGGER.debug("Long exposure {} s: {} frames", String.format("%.2f", elapsed), a.accumulated);
        if (a.shot.settings().longExposureNr() && elapsed >= 1 && !a.shot.chain().body().isFilm()) {
            darkFrameUntil = System.nanoTime() + (long) (elapsed * 1e9);
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("message.realcamera.dark_frame",
                        String.format(java.util.Locale.ROOT, "%.0f", Math.max(1, elapsed))), true);
            }
        }
        submit(mc, a.shot, null, linear, a.w, a.h, a.depthGrid, a.lightGrid, a.entityGrid, a.gpuDepth, layers(a, a.kindGrid),
                a.gw, a.gh, elapsed, a.remote, a.pack);
    }

    /** Sum one frame (GL rows are bottom-up) into the float buffer in linear light. */
    private static void accumulate(Active a, int pbo) {
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        ByteBuffer buf = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
        if (buf == null) {
            return;
        }
        float[] sum = a.sum;
        int w = a.w, h = a.h;
        for (int y = 0; y < h; y++) {
            int src = ((a.fh - 1 - (y + a.cy)) * a.fw + a.cx) * 4;
            int dst = y * w * 3;
            for (int x = 0; x < w; x++, src += 4, dst += 3) {
                sum[dst] += SRGB_TO_LINEAR[buf.get(src) & 0xFF];
                sum[dst + 1] += SRGB_TO_LINEAR[buf.get(src + 1) & 0xFF];
                sum[dst + 2] += SRGB_TO_LINEAR[buf.get(src + 2) & 0xFF];
            }
        }
        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        a.accumulated++;
    }

    // ------------------------------------------------------------------ helpers (render thread)

    // ------------------------------------------------------------------ other photographers' flashes

    private record RemoteFlash(Vec3 pos, Vec3 dir, float gn, long nanos) {}

    private static final java.util.ArrayDeque<RemoteFlash> recentFlashes = new java.util.ArrayDeque<>();

    /**
     * Someone else's flash fired nearby. If this camera's shutter is open, the flash lights the frame like it lights
     * the world: from where it is, along its beam, with shadows. A long exposure takes it in at once; a short one
     * looks back when its frame is captured.
     */
    public static void remoteFlash(Vec3 pos, Vec3 dir, float gn) {
        long now = System.nanoTime();
        RemoteFlash f = new RemoteFlash(pos, dir, gn, now);
        recentFlashes.addLast(f);
        while (!recentFlashes.isEmpty() && now - recentFlashes.peekFirst().nanos() > 2_000_000_000L) {
            recentFlashes.removeFirst();
        }
        Active a = active;
        if (a != null && a.startNanos >= 0 && a.rays != null && a.depthGrid != null) {
            a.remote = add(a.remote, flashGrid(a.rays, a.depthGrid, a.gw, a.gh, a.shot, f));
        }
    }

    /**
     * Light share of a flash at an angle from its axis (cosine): a flash head covers about the angle of a normal lens,
     * full inside ~30°, gone by ~60°, plus a little spill.
     */
    public static double flashCone(double cos) {
        double c = Math.max(0, Math.min(1, (cos - 0.5) / 0.35));
        return 0.04 + 0.96 * c * c * (3 - 2 * c);
    }

    @javax.annotation.Nullable
    private static float[] add(@javax.annotation.Nullable float[] sum, @javax.annotation.Nullable float[] g) {
        if (g == null) return sum;
        if (sum == null) return g;
        for (int i = 0; i < sum.length; i++) sum[i] += g[i];
        return sum;
    }

    /** Exposure (relative to a correct one) a flash adds on each cell of the ray grid, or null if it lights nothing. */
    @javax.annotation.Nullable
    private static float[] flashGrid(CameraRays rays, float[] depth, int gw, int gh, Shot s, RemoteFlash f) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        // what the photographer's own flash would give with this guide number, at 1 m, through this lens and ISO
        double at1m = Math.pow(f.gn() * Math.sqrt(s.exposure().iso() / 100) / s.exposure().fNumber(), 2);
        float[] out = new float[gw * gh];
        boolean any = false;
        for (int y = 0; y < gh; y++) {
            double v = 1 - (y + 0.5) * 2 / gh;
            for (int x = 0; x < gw; x++) {
                int c = y * gw + x;
                if (depth[c] >= 9_999) continue;
                double u = (x + 0.5) * 2 / gw - 1;
                Vec3 p = rays.point(u, v, depth[c]);
                Vec3 to = p.subtract(f.pos());
                double d = Math.max(0.3, to.length());
                double e = at1m * flashCone(to.dot(f.dir()) / d) / (d * d);
                if (e < 0.004) continue;
                // in the shadow of something between the flash and the surface
                Vec3 end = p.subtract(to.scale(0.3 / d));
                if (d > 0.4 && mc.level.clip(new net.minecraft.world.level.ClipContext(f.pos(), end,
                        net.minecraft.world.level.ClipContext.Block.VISUAL, net.minecraft.world.level.ClipContext.Fluid.NONE,
                        net.minecraft.world.phys.shapes.CollisionContext.empty())).getType()
                        != net.minecraft.world.phys.HitResult.Type.MISS) continue;
                out[c] = (float) e;
                any = true;
            }
        }
        return any ? out : null;
    }

    private static CameraRays rays(Minecraft mc, Shot s) {
        return new CameraRays(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(),
                s.chain().renderVfovDeg(s.focal(), s.settings(), s.focusDistance()), s.chain().sensor().aspect(), 512)
                .withLevelAxis(s.chain().shiftPitchDeg(s.focal(), s.settings()));
    }

    private static void finish(Minecraft mc, Active a) {
        mc.options.hideGui = a.savedHideGui;
        net.minecraft.world.entity.Entity.setViewScale(a.savedViewScale);
        if (a.savedCamera != null && mc.getCameraEntity() != a.savedCamera) {
            mc.setCameraEntity(a.savedCamera);
        }
        if (!a.noResize) {
            resize(mc, a.origW, a.origH); // resizing clears the buffer: the screen blacks out like a real EVF
        } else {
            blackout(mc.getMainRenderTarget());
        }
    }

    /** The {@code w} x {@code h} block at ({@code cx}, {@code cy}) of a row-major array {@code fw} wide. */
    private static float[] crop(float[] full, int fw, int cx, int cy, int w, int h, int channels) {
        float[] out = new float[w * h * channels];
        for (int y = 0; y < h; y++) {
            System.arraycopy(full, ((y + cy) * fw + cx) * channels, out, y * w * channels, w * channels);
        }
        return out;
    }

    private static void blackout(RenderTarget target) {
        target.bindWrite(true);
        RenderSystem.clearColor(0, 0, 0, 1);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
    }

    private static void resize(Minecraft mc, int w, int h) {
        Window window = mc.getWindow();
        window.setWidth(w);
        window.setHeight(h);
        mc.getMainRenderTarget().resize(w, h, Minecraft.ON_OSX);
        mc.gameRenderer.resize(w, h);
    }

    private static int[] toRgb(NativeImage image, int cx, int cy, int w, int h) {
        int[] rgb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x + cx, y + cy);
                rgb[y * w + x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
            }
        }
        return rgb;
    }

    /** The depth buffers behind translucent surfaces, for {@link LayeredDepth}. */
    private record DepthStack(float[] opaque, float[] surface, float[] kind, boolean inWater) {}

    @javax.annotation.Nullable
    private static DepthStack layers(Active a, float[] kind) {
        return a.gpuOpaque == null || a.gpuSurface == null || kind == null ? null
                : new DepthStack(a.gpuOpaque, a.gpuSurface, kind, a.inWater);
    }

    private static void submit(Minecraft mc, Shot shot, int[] rgb, float[] linear, int w, int h, float[] grid,
                               float[] lightGrid, float[] entityGrid, float[] gpuDepth, @javax.annotation.Nullable DepthStack layers,
                               int gw, int gh, double actualSeconds, float[] remote, boolean pack) {
        ColorProfileSpec profile = Specs.profile(mc.level.registryAccess(), shot.chain().body(), shot.settings().profile())
                .orElse(null);
        String artist = mc.getUser().getName();
        int maxUpload = ServerConfig.MAX_UPLOAD_RESOLUTION.get();
        dev.hackdanel.realcamera.spec.FilmStockSpec film = shot.chain().body().isFilm()
                ? dev.hackdanel.realcamera.client.camera.Viewfinder.filmStock() : null;
        developing++;
        // development uses half the cores: the game keeps rendering smoothly (and a burst keeps its pace)
        WORKER.execute(() -> DEVELOP_POOL.submit(() -> develop(shot, rgb, linear, w, h, grid, lightGrid, entityGrid, gpuDepth, layers,
                gw, gh, actualSeconds, remote, pack, profile, film, artist, maxUpload)).join());
    }

    // ------------------------------------------------------------------ worker thread

    private static void develop(Shot s, int[] rgb, float[] linear, int w, int h, float[] grid, float[] lightGrid,
                                float[] entityGrid, float[] gpuDepth, @javax.annotation.Nullable DepthStack layers,
                                int gw, int gh, double seconds, float[] remote,
                                boolean pack, ColorProfileSpec profile,
                                @javax.annotation.Nullable dev.hackdanel.realcamera.spec.FilmStockSpec film, String artist,
                                int maxUpload) {
        long t0 = System.nanoTime();
        try {
            int[] guide = rgb != null ? rgb : ImagePipeline.toSrgb(linear);
            // per-pixel depth from the GPU sees grass, leaves and the real shape of mobs; the ray grid is the fallback
            if (gpuDepth != null) {
                int dropped = DepthUpsampler.dropFixedLayer(gpuDepth, w, h, grid, gw, gh);
                if (dropped > 0) RealCamera.LOGGER.debug("Depth map: {} pixels of a fixed-depth layer taken from the rays", dropped);
            }
            boolean gpu = gpuDepth != null && DepthUpsampler.agrees(gpuDepth, w, h, grid, entityGrid, gw, gh);
            if (pack && gpuDepth != null) {
                Minecraft.getInstance().execute(() -> ShaderCompat.reportPhotoDepth(gpu));
            }
            float[] depth = gpu ? DepthUpsampler.fillUnknown(gpuDepth, () -> DepthUpsampler.upsample(grid, gw, gh, guide, w, h))
                    : DepthUpsampler.upsample(grid, gw, gh, guide, w, h);
            // the depth check compares what the rays see (they stop at water surfaces) with the full depth buffer;
            // then the camera focuses through water, glass and ice on what lies behind them
            if (gpu && layers != null) {
                depth = LayeredDepth.combine(depth, layers.opaque(), layers.surface(), layers.kind(), gw, gh, w, h, layers.inWater());
            }
            RealCamera.LOGGER.debug("Depth map: {}", gpu ? "GPU depth buffer" : gpuDepth == null ? "ray grid" : "ray grid (GPU depth rejected: " + DepthUpsampler.lastCheck + ")");
            float[] light = lightGrid == null ? null : DepthUpsampler.upsample(lightGrid, gw, gh, guide, w, h);
            ImageParams params = params(s, profile, film, w, h, seconds);
            if (remote != null) {
                params = params.withExtras(params.extras().withOtherFlash(DepthUpsampler.upsample(remote, gw, gh, guide, w, h)));
            }
            if (Boolean.getBoolean("realcamera.debugDumps")) {
                debugDump(guide, depth, w, h);
            }
            float[] img = linear != null ? linear : ImagePipeline.toLinear(rgb);
            // A correct exposure puts the metered area at middle grey, however dimly the game drew it: a moonlit
            // field correctly exposed on a tripod looks almost like day, as on a real camera. Light grid in step.
            // A shader pack draws the scene as bright as it likes (night brightness, auto exposure): then the
            // brightening comes from what it drew. The light grid stays on the vanilla curve (surface colour for flash).
            float lightScale = (float) relight(s);
            float relight = pack ? (float) ShaderCompat.packRelight(img, depth, w, h, s.metering().weights(),
                    s.metering().albedo(), lightScale) : lightScale;
            if (pack) RealCamera.LOGGER.debug("Shader pack exposure: x{} (light curve x{})", relight, lightScale);
            if (relight != 1) {
                for (int i = 0; i < img.length; i++) img[i] *= relight;
            }
            if (lightScale != 1 && light != null) {
                for (int i = 0; i < light.length; i++) light[i] *= lightScale;
            }
            float[] sensor = ImagePipeline.developSensor(img, depth, light, w, h, params);
            int format = film != null ? CameraSettings.FORMAT_JPEG : s.settings().fileFormat();
            float[] rawData = format != CameraSettings.FORMAT_JPEG && ClientConfig.AUTO_EXPORT.get() ? sensor.clone() : null;
            if (film == null && s.settings().lensCorrection()) {
                sensor = ImagePipeline.correctLens(sensor, w, h, params);
            }
            float[] finished = ImagePipeline.finish(sensor, params);
            if (film != null) {
                double scale = w / 2560.0;
                dev.hackdanel.realcamera.processing.FilmLook.halation(finished, w, h, film.grain().halation());
                // underexposed negatives show more grain; a bigger negative (120) is enlarged less, so finer grain
                double under = Math.max(0, -(s.exposure().delta() - Math.log(s.exposure().iso() / film.iso()) / Math.log(2)));
                double enlarge = 36.0 / Math.max(36, Math.max(s.chain().sensor().widthMm(), s.chain().sensor().heightMm()));
                dev.hackdanel.realcamera.processing.FilmLook.grain(finished, w, h,
                        film.grain().amount() * (1 + under * 0.4) * Math.sqrt(enlarge), film.grain().size() * scale * 2 * enlarge,
                        params.seed());
            }
            int[] developed = ImagePipeline.toSrgb(finished);

            float quality = s.settings().quality() == CameraSettings.QUALITY_NORMAL ? 0.8f : 0.93f;
            LocalDateTime now = LocalDateTime.now();
            Path localJpeg = null, localRaw = null;
            if (ClientConfig.AUTO_EXPORT.get() && film == null) { // film: nothing to see until it is developed
                Path base = exportBase(s, now);
                if (format != CameraSettings.FORMAT_RAW) {
                    byte[] full = ExifWriter.insert(JpegCodec.encode(developed, w, h, quality),
                            exif(s, artist, now, w, h, seconds));
                    localJpeg = base.resolveSibling(base.getFileName() + ".JPG");
                    Files.write(localJpeg, full);
                }
                if (rawData != null) {
                    localRaw = base.resolveSibling(base.getFileName() + ".PNG");
                    writeRaw(localRaw, rawData, w, h, s, params, seconds);
                }
            }
            int[] size = new int[2];
            int[] small = ImagePipeline.downscale(developed, w, h, maxUpload, size);
            byte[] upload = JpegCodec.encode(small, size[0], size[1], 0.85f);
            PhotoMeta meta = meta(s, size[0], size[1], seconds);
            RealCamera.LOGGER.debug("Developed {}x{} in {} ms (upload {} KB)", w, h,
                    (System.nanoTime() - t0) / 1_000_000, upload.length / 1024);
            Path jpegFile = localJpeg, rawFile = localRaw;
            Minecraft.getInstance().execute(() -> {
                developing--;
                PhotoUploader.upload(s.ref(), meta, upload, s.settings().quality(), format, film != null, s.nonce(),
                        id -> LocalOriginals.put(id, jpegFile, rawFile));
            });
        } catch (IOException | RuntimeException e) {
            RealCamera.LOGGER.error("Developing photo failed", e);
            Minecraft.getInstance().execute(() -> {
                developing--;
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            Component.translatable("message.realcamera.develop_failed"), true);
                }
            });
        }
    }

    /** Brightening of a dimly drawn scene (1 in daylight, up to 16x by night or in a dark cave). */
    static double relight(Shot s) {
        return Math.max(1, Math.min(16, 1 / Math.max(0.01, s.gameLight())));
    }

    /** Exposure error, recomputed with the real time for bulb shots. */
    private static double exposureEv(Shot s, double seconds) {
        if (s.seconds() >= 0) {
            return s.exposure().delta();
        }
        return s.sceneEv() - Exposure.cameraEv(s.exposure().fNumber(), seconds, s.exposure().iso());
    }

    private static ImageParams params(Shot s, ColorProfileSpec profile,
                                      @javax.annotation.Nullable dev.hackdanel.realcamera.spec.FilmStockSpec film,
                                      int w, int h, double seconds) {
        if (film != null) {
            ImageParams digital = params(s, null, null, w, h, seconds);
            // what the emulsion got: rated above box speed it is underexposed (to be pushed), long exposures lose
            // speed (reciprocity failure)
            double ev = digital.sensor().exposureEv() - Math.log(s.exposure().iso() / film.iso()) / Math.log(2)
                    - film.reciprocityLossEv(seconds < 0 ? 1 : seconds);
            double latOver = film.latitudeOver(), latUnder = film.latitudeUnder();
            double shown = film.printedEv(ev), contrastK = 1;
            if (film.negative()) {
                // printed back to normal, but an overexposed negative prints a little flatter, a thin one muddier
                contrastK = ev >= 0 ? 1 - 0.05 * Math.min(ev, latOver) : 1 - 0.06 * Math.min(-ev, latUnder + 1);
            }
            // film is balanced for daylight (or tungsten for "T" stocks) and cannot auto white-balance
            double[] wb = ImagePipeline.whiteBalance(s.sceneK(), film.name().endsWith("T") ? 3200 : 5500);
            var look = film.look();
            var sn = digital.sensor();
            double[] drift = film.reciprocityTint(seconds < 0 ? 1 : seconds);
            return new ImageParams(new ImageParams.Sensor(shown, 1, 0, 0, sn.resolutionScale(),
                    dev.hackdanel.realcamera.processing.FilmLook.dynamicRange(film.latitudeOver())),
                    digital.focus(), digital.lens(), digital.motion(), digital.sun(),
                    new ImageParams.Colour(wb[0], wb[1], wb[2], look.contrast() * contrastK, look.saturation(), film.monochrome(),
                            look.tintR() * drift[0], look.tintG() * drift[1], look.tintB() * drift[2], look.shadowLift()),
                    digital.extras(), digital.seed());
        }
        // dark current integrates for the whole exposure; noise reduction subtracts a dark frame from 1 s on
        double darkS = seconds < 0 ? 1 : seconds;
        var dark = new ImageParams.Dark(darkS >= 0.25 ? darkS : 0, s.sensorC(), s.settings().longExposureNr() && darkS >= 1,
                sensorSeed(s));
        ImageParams base = digitalParams(s.chain(), s.settings(), s.exposure(), s.focal(), s.focusDistance(),
                exposureEv(s, seconds), s.sceneK(), s.extras(), profile, w, h);
        return new ImageParams(base.sensor().withDark(dark), base.focus(), base.lens(), s.motion().scaled(w), s.sun(),
                base.colour(), base.extras(), base.seed());
    }

    /**
     * A digital photo's development parameters, without what only the shot knows (motion, sun, dark current). The
     * electronic viewfinder builds its preview from the same parameters ({@link dev.hackdanel.realcamera.client.render.EvfPreview}).
     *
     * @param exposureEv exposure error (+ brighter than the meter's middle grey)
     * @param sceneK     colour temperature of the metered light
     * @param w          frame width in pixels (lateral colour and shift scale with it)
     */
    public static ImageParams digitalParams(OpticalChain chain, CameraSettings settings, Exposure.Result exposure, float focal,
                                            float focusDistance, double exposureEv, double sceneK, ImageParams.Extras extras,
                                            @javax.annotation.Nullable ColorProfileSpec profile, int w, int h) {
        var sensor = chain.sensor();
        double isoGain = exposure.iso() / 100.0;
        double areaFactor = Math.sqrt(864.0 / Math.max(1, sensor.widthMm() * sensor.heightMm()));
        double belowBase = Math.max(0, Math.log(sensor.baseIso() / exposure.iso()) / Math.log(2));
        double settingK = switch (settings.whiteBalance()) {
            case CameraSettings.WB_DAYLIGHT, CameraSettings.WB_FLASH -> 5500;
            case CameraSettings.WB_CLOUDY -> 6500;
            case CameraSettings.WB_SHADE -> 7500;
            case CameraSettings.WB_TUNGSTEN -> 3200;
            case CameraSettings.WB_FLUORESCENT -> 4000;
            case CameraSettings.WB_KELVIN -> settings.kelvin();
            default -> sceneK * 0.7 + 5500 * 0.3; // auto keeps a little of the light's mood
        };
        double[] wb = ImagePipeline.whiteBalance(sceneK, settingK);
        var optics = chain.lens().optics();
        // uncorrected optics: corrections are applied after the RAW is taken (ImagePipeline.correctLens)
        // a lens made for a bigger format only shows the middle of its image on a smaller sensor (a full-frame lens
        // on APS-C): less vignetting, distortion and lateral colour at the frame corner
        double q = Math.min(1, Math.hypot(sensor.widthMm(), sensor.heightMm()) / OpticalChain.circleDiagonalMm(chain.lens().imageCircle()));
        var lens = new ImageParams.Lens(optics.vignettingEv() * q * q, chain.maxApertureAt(focal),
                chain.sharpness(), chain.lens().distortionAt(chain.lensFocal(focal)) * q * q,
                optics.caPx() * (w / 3840.0) * q, chain.imageCircle(), optics.blades(),
                optics.rounded(), optics.flare());
        var ch = optics.character();
        lens = lens.withBokeh(ImageParams.bokeh(optics.bokeh())).withLoca(chain.locaMm(focal))
                .withCharacter(new ImageParams.Character(ch.spherical(), ch.apodization(), ch.obstruction(), ch.soft()));
        int projection = chain.projection();
        if (projection != Optics.PROJ_RECTILINEAR) {
            double halfDiag = Math.hypot(sensor.widthMm(), sensor.heightMm()) / 2;
            lens = lens.withProjection(projection, Optics.renderCornerAngle(projection, focal, halfDiag));
        }
        float shiftMm = chain.shiftMm(settings);
        if (shiftMm != 0) {
            lens = lens.withShift(shiftMm * w / sensor.widthMm(), 1 / OpticalChain.SHIFT_OVERSCAN);
        }
        return new ImageParams(
                new ImageParams.Sensor(exposureEv, isoGain, sensor.noiseLuma(), sensor.noiseChroma(),
                        (double) w * h / (sensor.megapixels() * 1e6), sensor.dynamicRangeEv(), areaFactor, belowBase),
                new ImageParams.Focus(focal, exposure.fNumber(), focusDistance, sensor.widthMm(),
                        chain.tiltDeg(settings), ch.fieldCurvature() * q * q), // a smaller sensor sees less of the curve
                lens, ImageParams.STILL, ImageParams.NO_SUN,
                new ImageParams.Colour(wb[0], wb[1], wb[2],
                        profile == null ? 1 : profile.contrast(), profile == null ? 1 : profile.saturation(),
                        profile != null && profile.monochrome(), profile == null ? 1 : profile.tintR(),
                        profile == null ? 1 : profile.tintG(), profile == null ? 1 : profile.tintB(),
                        profile == null ? 0 : profile.shadowLift()),
                extras, System.nanoTime());
    }

    /**
     * Identifies the sensor (its hot pixels stay in the same places): camera items carry no serial number, so it is
     * the camera model of this player.
     */
    private static long sensorSeed(Shot s) {
        var info = s.chain().body().info();
        var player = Minecraft.getInstance().getUser().getProfileId();
        return (info.exifMake() + "/" + info.exifModel()).hashCode() * 31L + (player == null ? 0 : player.hashCode());
    }

    private static double nominalSeconds(Shot s, double seconds) {
        return s.seconds() < 0 ? Math.round(seconds * 10) / 10.0 : ExposureScales.nominalShutter(s.exposure().shutterIdx());
    }

    private static int nominalIso(Shot s) {
        return ExposureScales.ISO[Math.max(0, Math.min(ExposureScales.ISO.length - 1, s.exposure().isoIdx()))];
    }

    private static PhotoMeta meta(Shot s, int w, int h, double seconds) {
        int focal35 = Optics.focal35(s.focal(), s.chain().sensor().cropFactor());
        PhotoMeta.Exif exif = new PhotoMeta.Exif(s.cameraName(), s.lensName(),
                (float) ExposureScales.nominalAperture(s.exposure().apertureIdx()), nominalSeconds(s, seconds),
                nominalIso(s), s.focal(), focal35, s.settings().mode(), s.settings().evComp(), s.settings().whiteBalance());
        // author, time and place are filled in by the server
        return new PhotoMeta(new UUID(0, 0), new UUID(0, 0), "", 0, w, h, exif, new PhotoMeta.Place("", "", 0, 0, 0, 0));
    }

    private static ExifWriter.Data exif(Shot s, String artist, LocalDateTime now, int w, int h, double seconds) {
        int program = switch (s.settings().mode()) {
            case CameraSettings.MODE_M -> 1;
            case CameraSettings.MODE_A -> 3;
            case CameraSettings.MODE_S -> 4;
            default -> 2;
        };
        int metering = switch (s.settings().metering()) {
            case CameraSettings.METER_CENTER -> 2;
            case CameraSettings.METER_SPOT -> 3;
            default -> 5;
        };
        var body = s.chain().body();
        return new ExifWriter.Data(body.info().exifMake(), body.info().exifModel(), s.chain().lens().brand(),
                s.chain().exifLensModel(), artist, now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")),
                nominalSeconds(s, seconds), ExposureScales.nominalAperture(s.exposure().apertureIdx()), nominalIso(s),
                s.focal(), Optics.focal35(s.focal(), s.chain().sensor().cropFactor()), program,
                s.settings().evComp() / 3.0, metering, s.settings().whiteBalance() != CameraSettings.WB_AUTO, w, h);
    }

    /** screenshots/realcamera/<date>/DSC0NNNN (extension added by the caller), like a camera's card folder. */
    private static Path exportBase(Shot s, LocalDateTime now) throws IOException {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve("realcamera")
                .resolve(now.format(DateTimeFormatter.ISO_LOCAL_DATE));
        Files.createDirectories(dir);
        String name = String.format("DSC%05d", s.frameNumber() % 100000);
        Path base = dir.resolve(name);
        for (int i = 1; Files.exists(dir.resolve(base.getFileName() + ".JPG"))
                || Files.exists(dir.resolve(base.getFileName() + ".PNG")); i++) {
            base = dir.resolve(name + "_" + i);
        }
        return base;
    }

    /**
     * "RAW": the sensor data (after optics and noise, before white balance, tone curve and profile) as a
     * 16-bit linear PNG, plus a JSON sidecar with what the computer's editor needs to develop it.
     */
    private static void writeRaw(Path png, float[] sensor, int w, int h, Shot s, ImageParams p, double seconds)
            throws IOException {
        RawFile.Info info = new RawFile.Info();
        info.camera = s.chain().body().info().exifMake() + " " + s.chain().body().info().exifModel();
        info.lens = s.chain().exifLensModel();
        info.exposure_ev = p.sensor().exposureEv();
        info.iso = nominalIso(s);
        info.shutter_s = nominalSeconds(s, seconds);
        info.f_number = ExposureScales.nominalAperture(s.exposure().apertureIdx());
        info.focal_mm = s.focal();
        info.dynamic_range_ev = p.sensor().dynamicRangeEv();
        info.white = ImagePipeline.rawSaturation(p.sensor().dynamicRangeEv());
        ImageParams.Colour c = p.colour();
        info.white_balance = new double[]{c.wbR(), c.wbG(), c.wbB()};
        var profiles = s.chain().body().colorProfiles();
        info.profiles = profiles.stream().map(k -> k.location().toString()).toArray(String[]::new);
        info.profile = profiles.isEmpty() ? "" : info.profiles[Math.floorMod(s.settings().profile(), profiles.size())];
        info.profile_params = new double[]{c.contrast(), c.saturation(), c.monochrome() ? 1 : 0, c.tintR(), c.tintG(),
                c.tintB(), c.shadowLift()};
        double stopsDown = Math.max(0, Math.log(p.focus().fNumber() / p.lens().maxAperture()) / Math.log(2));
        info.vignetting_ev = p.lens().vignettingEv() * Math.max(0, 1 - stopsDown * 0.45);
        info.distortion = p.lens().distortion();
        info.ca_frac = p.lens().caPx() / Math.hypot((w - 1) / 2.0, (h - 1) / 2.0);
        RawFile.write(png, sensor, w, h, info);
    }

    /** Dev aid: raw frame and depth map (log scale) next to the exported photos. */
    private static void debugDump(int[] rgb, float[] depth, int w, int h) throws IOException {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve("realcamera_debug");
        Files.createDirectories(dir);
        int[] d = new int[depth.length];
        for (int i = 0; i < depth.length; i++) {
            int v = (int) Math.max(0, Math.min(255, Math.log(Math.max(0.05, depth[i])) / Math.log(10000) * 255));
            d[i] = (v << 16) | (v << 8) | v;
        }
        long t = System.currentTimeMillis();
        Files.write(dir.resolve("raw_" + t + ".jpg"), JpegCodec.encode(rgb, w, h, 0.95f));
        Files.write(dir.resolve("depth_" + t + ".jpg"), JpegCodec.encode(d, w, h, 0.95f));
    }
}
