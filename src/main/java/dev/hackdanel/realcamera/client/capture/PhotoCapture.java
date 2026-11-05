package dev.hackdanel.realcamera.client.capture;

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
                       ImageParams.Sun sun, ImageParams.Extras extras, int nonce) {
        boolean longExposure() {
            return seconds < 0 || seconds >= LONG_EXPOSURE_S;
        }
    }

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

    private static final class Active {
        final Shot shot;
        final boolean savedHideGui;
        final net.minecraft.world.entity.Entity savedCamera;
        final int origW, origH, w, h;
        int warmup = WARMUP_FRAMES;
        // long exposure
        long startNanos = -1;
        final int[] pbo = new int[2];
        float[] sum;
        int frameIndex, accumulated;
        float[] depthGrid, lightGrid;
        int gw, gh;

        Active(Shot shot, boolean savedHideGui, net.minecraft.world.entity.Entity savedCamera, int origW, int origH, int w, int h) {
            this.shot = shot;
            this.savedHideGui = savedHideGui;
            this.savedCamera = savedCamera;
            this.origW = origW;
            this.origH = origH;
            this.w = w;
            this.h = h;
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
        return pending == null && active == null && developing < buffer && PhotoUploader.pending() < 12;
    }

    public static boolean busy() {
        return pending != null || active != null || developing > 0 || PhotoUploader.busy();
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
            event.setFOV(a.shot.chain().renderVfovDeg(a.shot.focal(), a.shot.settings()));
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
        active = new Active(shot, mc.options.hideGui, mc.getCameraEntity(), window.getWidth(), window.getHeight(), w, h);
        if (shot.ref().isTripod() && mc.level.getEntity(shot.ref().tripodId()) instanceof net.minecraft.world.entity.Entity tripod) {
            // the tripod's camera takes the picture, wherever the photographer is (self-timer selfies)
            mc.setCameraEntity(tripod);
        }
        resize(mc, w, h);
        mc.options.hideGui = true;
    }

    @SubscribeEvent
    static void onFramePost(RenderFrameEvent.Post event) {
        Active a = active;
        if (a == null) {
            return;
        }
        if (a.warmup-- > 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!a.shot.longExposure()) {
            active = null;
            NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            int gw = 192, gh = Math.max(1, Math.round(gw / a.shot.chain().sensor().aspect()));
            float[][] grids = grids(mc, a.shot, gw, gh);
            finish(mc, a);
            int[] rgb = toRgb(image);
            image.close();
            submit(mc, a.shot, rgb, null, a.w, a.h, grids[0], grids[1], gw, gh, a.shot.seconds());
            return;
        }
        accumulateFrame(mc, a);
    }

    // ------------------------------------------------------------------ long exposure

    private static void accumulateFrame(Minecraft mc, Active a) {
        RenderTarget target = mc.getMainRenderTarget();
        if (a.startNanos < 0) {
            a.startNanos = System.nanoTime();
            a.sum = new float[a.w * a.h * 3];
            a.gw = 192;
            a.gh = Math.max(1, Math.round(a.gw / a.shot.chain().sensor().aspect()));
            float[][] grids = grids(mc, a.shot, a.gw, a.gh);
            a.depthGrid = grids[0];
            a.lightGrid = grids[1];
            for (int i = 0; i < 2; i++) {
                a.pbo[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, a.pbo[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) a.w * a.h * 4, GL15.GL_STREAM_READ);
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
            target.bindWrite(true);
            RenderSystem.clearColor(0, 0, 0, 1);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
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
        submit(mc, a.shot, null, linear, a.w, a.h, a.depthGrid, a.lightGrid, a.gw, a.gh, elapsed);
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
            int src = (h - 1 - y) * w * 4;
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

    private static float[][] grids(Minecraft mc, Shot s, int gw, int gh) {
        return new CameraRays(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(),
                s.chain().renderVfovDeg(s.focal(), s.settings()), s.chain().sensor().aspect(), 512)
                .grids(gw, gh, s.extras().flashAt1m() > 0);
    }

    private static void finish(Minecraft mc, Active a) {
        mc.options.hideGui = a.savedHideGui;
        if (a.savedCamera != null && mc.getCameraEntity() != a.savedCamera) {
            mc.setCameraEntity(a.savedCamera);
        }
        resize(mc, a.origW, a.origH); // resizing clears the buffer: the screen blacks out like a real EVF
    }

    private static void resize(Minecraft mc, int w, int h) {
        Window window = mc.getWindow();
        window.setWidth(w);
        window.setHeight(h);
        mc.getMainRenderTarget().resize(w, h, Minecraft.ON_OSX);
        mc.gameRenderer.resize(w, h);
    }

    private static int[] toRgb(NativeImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int[] rgb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = image.getPixelRGBA(x, y);
                rgb[y * w + x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
            }
        }
        return rgb;
    }

    private static void submit(Minecraft mc, Shot shot, int[] rgb, float[] linear, int w, int h, float[] grid,
                               float[] lightGrid, int gw, int gh, double actualSeconds) {
        ColorProfileSpec profile = Specs.profile(mc.level.registryAccess(), shot.chain().body(), shot.settings().profile())
                .orElse(null);
        String artist = mc.getUser().getName();
        int maxUpload = ServerConfig.MAX_UPLOAD_RESOLUTION.get();
        dev.hackdanel.realcamera.spec.FilmStockSpec film = shot.chain().body().isFilm()
                ? dev.hackdanel.realcamera.client.camera.Viewfinder.filmStock() : null;
        developing++;
        WORKER.execute(() -> develop(shot, rgb, linear, w, h, grid, lightGrid, gw, gh, actualSeconds, profile, film, artist,
                maxUpload));
    }

    // ------------------------------------------------------------------ worker thread

    private static void develop(Shot s, int[] rgb, float[] linear, int w, int h, float[] grid, float[] lightGrid,
                                int gw, int gh, double seconds, ColorProfileSpec profile,
                                @javax.annotation.Nullable dev.hackdanel.realcamera.spec.FilmStockSpec film, String artist,
                                int maxUpload) {
        long t0 = System.nanoTime();
        try {
            int[] guide = rgb != null ? rgb : ImagePipeline.toSrgb(linear);
            float[] depth = DepthUpsampler.upsample(grid, gw, gh, guide, w, h);
            float[] light = lightGrid == null ? null : DepthUpsampler.upsample(lightGrid, gw, gh, guide, w, h);
            ImageParams params = params(s, profile, film, w, h, seconds);
            if (Boolean.getBoolean("realcamera.debugDumps")) {
                debugDump(guide, depth, w, h);
            }
            float[] img = linear != null ? linear : ImagePipeline.toLinear(rgb);
            float[] sensor = ImagePipeline.developSensor(img, depth, light, w, h, params);
            int format = film != null ? CameraSettings.FORMAT_JPEG : s.settings().fileFormat();
            float[] rawData = format != CameraSettings.FORMAT_JPEG && ClientConfig.AUTO_EXPORT.get() ? sensor.clone() : null;
            float[] finished = ImagePipeline.finish(sensor, params);
            if (film != null) {
                double scale = w / 2560.0;
                dev.hackdanel.realcamera.processing.FilmLook.halation(finished, w, h, film.grain().halation());
                // underexposed negatives show more grain
                double under = Math.max(0, -params.sensor().exposureEv());
                dev.hackdanel.realcamera.processing.FilmLook.grain(finished, w, h,
                        film.grain().amount() * (1 + under * 0.4), film.grain().size() * scale * 2, params.seed());
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
            // film is balanced for daylight (or tungsten for "T" stocks) and cannot auto white-balance
            double sceneK = s.blockLit() ? 3400 : 5500;
            double[] wb = ImagePipeline.whiteBalance(sceneK, film.name().endsWith("T") ? 3200 : 5500);
            var look = film.look();
            var sn = digital.sensor();
            return new ImageParams(new ImageParams.Sensor(sn.exposureEv(), 1, 0, 0, sn.resolutionScale(),
                    dev.hackdanel.realcamera.processing.FilmLook.dynamicRange(film.latitudeOver())),
                    digital.focus(), digital.lens(), digital.motion(), digital.sun(),
                    new ImageParams.Colour(wb[0], wb[1], wb[2], look.contrast(), look.saturation(), film.monochrome(),
                            look.tintR(), look.tintG(), look.tintB(), look.shadowLift()),
                    digital.extras(), digital.seed());
        }
        OpticalChain chain = s.chain();
        var sensor = chain.sensor();
        double isoGain = s.exposure().iso() / Math.max(1, sensor.baseIso());
        double sceneK = s.blockLit() ? 4200 : 5500;
        double settingK = switch (s.settings().whiteBalance()) {
            case CameraSettings.WB_DAYLIGHT, CameraSettings.WB_FLASH -> 5500;
            case CameraSettings.WB_CLOUDY -> 6500;
            case CameraSettings.WB_SHADE -> 7500;
            case CameraSettings.WB_TUNGSTEN -> 3200;
            case CameraSettings.WB_FLUORESCENT -> 4000;
            case CameraSettings.WB_KELVIN -> s.settings().kelvin();
            default -> sceneK * 0.7 + 5500 * 0.3; // auto keeps a little of the light's mood
        };
        double[] wb = ImagePipeline.whiteBalance(sceneK, settingK);
        boolean correct = s.settings().lensCorrection();
        var optics = chain.lens().optics();
        var lens = new ImageParams.Lens(optics.vignettingEv() * (correct ? 0.5 : 1), chain.maxApertureAt(s.focal()),
                chain.sharpness(), chain.lens().distortionAt(chain.lensFocal(s.focal())) * (correct ? 0.15 : 1),
                optics.caPx() * (w / 3840.0) * (correct ? 0.2 : 1), chain.imageCircle(), optics.blades(),
                optics.rounded(), optics.flare());
        lens = lens.withBokeh(ImageParams.bokeh(optics.bokeh()));
        int projection = chain.projection();
        if (projection != Optics.PROJ_RECTILINEAR) {
            double halfDiag = Math.hypot(sensor.widthMm(), sensor.heightMm()) / 2;
            lens = lens.withProjection(projection, Optics.renderCornerAngle(projection, s.focal(), halfDiag));
        }
        float shiftMm = chain.shiftMm(s.settings());
        if (shiftMm != 0) {
            lens = lens.withShift(shiftMm * w / sensor.widthMm(), 1 / OpticalChain.SHIFT_OVERSCAN);
        }
        var motion = new ImageParams.Motion(s.motion().blurX() * w, s.motion().blurY() * w, s.motion().rollingSkewPx() * w);
        return new ImageParams(
                new ImageParams.Sensor(exposureEv(s, seconds), isoGain, sensor.noiseLuma(), sensor.noiseChroma(),
                        (double) w * h / (sensor.megapixels() * 1e6), sensor.dynamicRangeEv()),
                new ImageParams.Focus(s.focal(), s.exposure().fNumber(), s.focusDistance(), sensor.widthMm(),
                        chain.tiltDeg(s.settings())),
                lens, motion, s.sun(),
                new ImageParams.Colour(wb[0], wb[1], wb[2],
                        profile == null ? 1 : profile.contrast(), profile == null ? 1 : profile.saturation(),
                        profile != null && profile.monochrome(), profile == null ? 1 : profile.tintR(),
                        profile == null ? 1 : profile.tintG(), profile == null ? 1 : profile.tintB(),
                        profile == null ? 0 : profile.shadowLift()),
                s.extras(), System.nanoTime());
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
        Files.write(dir.resolve("raw.jpg"), JpegCodec.encode(rgb, w, h, 0.95f));
        Files.write(dir.resolve("depth.jpg"), JpegCodec.encode(d, w, h, 0.95f));
    }
}
