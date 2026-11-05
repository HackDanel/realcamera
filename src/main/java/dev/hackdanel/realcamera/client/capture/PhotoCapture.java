package dev.hackdanel.realcamera.client.capture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import dev.hackdanel.realcamera.RealCamera;
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
import dev.hackdanel.realcamera.processing.ImageParams;
import dev.hackdanel.realcamera.processing.ImagePipeline;
import dev.hackdanel.realcamera.processing.JpegCodec;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.ColorProfileSpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Turns a shutter press into a photo: renders one clean frame at the sensor's output resolution
 * (no HUD, no hand), ray-casts a depth map from the same camera pose, then develops, exports and
 * uploads on a worker thread. See docs/stage0-capture.md for why the warm-up frame exists.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class PhotoCapture {
    /** Everything frozen at the moment the shutter fired. */
    public record Shot(InteractionHand hand, CameraBodySpec body, LensSpec lens, CameraSettings settings,
                       Exposure.Result exposure, float focal, float focusDistance, double sceneEv, boolean blockLit,
                       int frameNumber, String cameraName, String lensName) {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RealCamera developer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final int WARMUP_FRAMES = Integer.getInteger("realcamera.captureWarmup", 1);

    private static Shot pending;
    private static Active active;
    private static volatile int developing;

    private static final class Active {
        final Shot shot;
        final boolean savedHideGui;
        final int origW, origH, w, h;
        int warmup = WARMUP_FRAMES;

        Active(Shot shot, boolean savedHideGui, int origW, int origH, int w, int h) {
            this.shot = shot;
            this.savedHideGui = savedHideGui;
            this.origW = origW;
            this.origH = origH;
            this.w = w;
            this.h = h;
        }
    }

    private PhotoCapture() {}

    public static void request(Shot shot) {
        pending = shot;
    }

    /** One shot in flight at a time (stage 1 has no burst buffer yet). */
    public static boolean busy() {
        return pending != null || active != null || developing > 0 || PhotoUploader.busy();
    }

    public static boolean capturing() {
        return active != null;
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
        int longSide = shot.body().sensor().outputLongSide();
        float aspect = shot.body().sensor().aspect();
        int w = aspect >= 1 ? longSide : Math.round(longSide * aspect);
        int h = aspect >= 1 ? Math.round(longSide / aspect) : longSide;
        active = new Active(shot, mc.options.hideGui, window.getWidth(), window.getHeight(), w, h);
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
        active = null;
        Minecraft mc = Minecraft.getInstance();
        NativeImage image = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget());
        // depth map from exactly this camera pose (aspect = sensor aspect while resized)
        int gw = 192, gh = Math.max(1, Math.round(gw / a.shot.body().sensor().aspect()));
        float[] grid = new CameraRays(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(),
                Viewfinder.sensorVfov(), a.shot.body().sensor().aspect(), 512).depthGrid(gw, gh);
        mc.options.hideGui = a.savedHideGui;
        resize(mc, a.origW, a.origH); // resizing clears the buffer: the screen blacks out like a real EVF
        Optional<ColorProfileSpec> profile = dev.hackdanel.realcamera.camera.Specs.profile(
                mc.level.registryAccess(), a.shot.body(), a.shot.settings().profile());
        String artist = mc.getUser().getName();
        int maxUpload = ServerConfig.MAX_UPLOAD_RESOLUTION.get();
        developing++;
        WORKER.execute(() -> develop(a.shot, image, grid, gw, gh, profile.orElse(null), artist, maxUpload));
    }

    private static void resize(Minecraft mc, int w, int h) {
        Window window = mc.getWindow();
        window.setWidth(w);
        window.setHeight(h);
        mc.getMainRenderTarget().resize(w, h, Minecraft.ON_OSX);
        mc.gameRenderer.resize(w, h);
    }

    // ------------------------------------------------------------------ worker thread

    private static void develop(Shot s, NativeImage image, float[] grid, int gw, int gh, ColorProfileSpec profile,
                                String artist, int maxUpload) {
        long t0 = System.nanoTime();
        try {
            int w = image.getWidth(), h = image.getHeight();
            int[] rgb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    rgb[y * w + x] = ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
                }
            }
            image.close();
            float[] depth = DepthUpsampler.upsample(grid, gw, gh, rgb, w, h);
            if (Boolean.getBoolean("realcamera.debugDumps")) {
                debugDump(rgb, depth, w, h);
            }
            ImageParams params = params(s, profile, w, h);
            int[] developed = ImagePipeline.develop(rgb, depth, w, h, params);

            float quality = s.settings().quality() == CameraSettings.QUALITY_NORMAL ? 0.8f : 0.93f;
            LocalDateTime now = LocalDateTime.now();
            if (ClientConfig.AUTO_EXPORT.get()) {
                byte[] full = ExifWriter.insert(JpegCodec.encode(developed, w, h, quality), exif(s, artist, now, w, h));
                export(full, s, now);
            }
            int[] size = new int[2];
            int[] small = ImagePipeline.downscale(developed, w, h, maxUpload, size);
            byte[] upload = JpegCodec.encode(small, size[0], size[1], 0.85f);
            PhotoMeta meta = meta(s, size[0], size[1]);
            RealCamera.LOGGER.debug("Developed {}x{} in {} ms (upload {} KB)", w, h,
                    (System.nanoTime() - t0) / 1_000_000, upload.length / 1024);
            Minecraft.getInstance().execute(() -> {
                developing--;
                PhotoUploader.upload(s.hand(), meta, upload, s.settings().quality());
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

    private static ImageParams params(Shot s, ColorProfileSpec profile, int w, int h) {
        CameraBodySpec body = s.body();
        double sensorPixels = body.sensor().megapixels() * 1e6;
        double isoGain = s.exposure().iso() / Math.max(1, body.sensor().baseIso());
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
        boolean mono = profile != null && profile.monochrome();
        return new ImageParams(s.exposure().delta(), isoGain, body.sensor().noiseLuma(), body.sensor().noiseChroma(),
                (double) w * h / sensorPixels, body.sensor().dynamicRangeEv(),
                s.focal(), s.exposure().fNumber(), s.focusDistance(), body.sensor().widthMm(),
                s.lens().optics().vignettingEv(), s.lens().maxApertureAt(s.focal()), s.lens().optics().sharpness(),
                wb[0], wb[1], wb[2],
                profile == null ? 1 : profile.contrast(), profile == null ? 1 : profile.saturation(), mono,
                profile == null ? 1 : profile.tintR(), profile == null ? 1 : profile.tintG(),
                profile == null ? 1 : profile.tintB(), profile == null ? 0 : profile.shadowLift(),
                System.nanoTime());
    }

    private static PhotoMeta meta(Shot s, int w, int h) {
        int focal35 = Optics.focal35(s.focal(), s.body().sensor().cropFactor());
        PhotoMeta.Exif exif = new PhotoMeta.Exif(s.cameraName(), s.lensName(),
                (float) ExposureScales.nominalAperture(s.exposure().apertureIdx()),
                ExposureScales.nominalShutter(s.exposure().shutterIdx()), nominalIso(s), s.focal(), focal35, s.settings().mode(),
                s.settings().evComp(), s.settings().whiteBalance());
        // author, time and place are filled in by the server
        return new PhotoMeta(new UUID(0, 0), new UUID(0, 0), "", 0, w, h, exif,
                new PhotoMeta.Place("", "", 0, 0, 0, 0));
    }

    private static int nominalIso(Shot s) {
        return ExposureScales.ISO[Math.max(0, Math.min(ExposureScales.ISO.length - 1, s.exposure().isoIdx()))];
    }

    private static ExifWriter.Data exif(Shot s, String artist, LocalDateTime now, int w, int h) {
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
        return new ExifWriter.Data(s.body().info().exifMake(), s.body().info().exifModel(), s.lens().brand(),
                s.lens().exifLensModel(), artist, now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")),
                ExposureScales.nominalShutter(s.exposure().shutterIdx()),
                ExposureScales.nominalAperture(s.exposure().apertureIdx()), nominalIso(s), s.focal(),
                Optics.focal35(s.focal(), s.body().sensor().cropFactor()), program, s.settings().evComp() / 3.0,
                metering, s.settings().whiteBalance() != CameraSettings.WB_AUTO, w, h);
    }

    /** screenshots/realcamera/<date>/DSC0NNNN.JPG, like a camera's card folder. */
    private static void export(byte[] jpeg, Shot s, LocalDateTime now) throws IOException {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve("realcamera")
                .resolve(now.format(DateTimeFormatter.ISO_LOCAL_DATE));
        Files.createDirectories(dir);
        String base = String.format("DSC%05d", s.frameNumber() % 100000);
        Path file = dir.resolve(base + ".JPG");
        for (int i = 1; Files.exists(file); i++) {
            file = dir.resolve(base + "_" + i + ".JPG");
        }
        Files.write(file, jpeg);
    }
}
