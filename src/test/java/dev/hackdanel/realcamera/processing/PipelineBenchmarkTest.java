package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Plan stage 8 profiling: develops a 100 MP-class frame (3840×2560) with depth of field, bokeh highlights, motion,
 * lens geometry, flare and noise, and prints how long each variant takes. Fails only if something is absurdly slow.
 */
class PipelineBenchmarkTest {
    private static final int W = 3840, H = 2560;

    private static int[] scene() {
        SplittableRandom r = new SplittableRandom(7);
        int[] rgb = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int v = 60 + ((x / 64 + y / 64) & 1) * 80 + r.nextInt(20);
                rgb[y * W + x] = (v << 16) | ((v - 10) << 8) | (v - 20);
            }
        }
        for (int i = 0; i < 400; i++) { // small bright lights
            int x = r.nextInt(W), y = r.nextInt(H / 2);
            rgb[y * W + x] = 0xFFFFFF;
        }
        return rgb;
    }

    private static float[] depth() {
        float[] d = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                d[y * W + x] = x > W / 3 && x < 2 * W / 3 && y > H / 3 ? 3f : 40f;
            }
        }
        return d;
    }

    private static long time(String label, int[] rgb, float[] depth, ImageParams p) {
        long t0 = System.nanoTime();
        ImagePipeline.developLinear(ImagePipeline.toLinear(rgb), depth, null, W, H, p);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("[bench] %-28s %6d ms%n", label, ms);
        return ms;
    }

    private static void stage(String label, Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        System.out.printf("[bench]   %-26s %6d ms%n", label, (System.nanoTime() - t0) / 1_000_000);
    }

    @Test
    void developFourK() {
        int[] rgb = scene();
        float[] depth = depth();
        ImageParams base = ImageParams.neutral().withFocus(85, 1.8, 3).withNoise(8, 1, 1)
                .withLens(new ImageParams.Lens(1.2, 1.8, 1.05, -0.03, 2, 1, 9, true, 0.5))
                .withMotion(new ImageParams.Motion(3, 1, 0)).withSun(new ImageParams.Sun(0.4, 0.5, 1));
        time("warm-up", rgb, depth, base);
        long dof = time("85/1.8 portrait", rgb, depth, base);
        long swirl = time("Helios swirl", rgb, depth, base.withLens(base.lens().withBokeh(ImageParams.BOKEH_SWIRL)));
        long fisheye = time("fisheye", rgb, null, base.withLens(base.lens().withProjection(
                dev.hackdanel.realcamera.optics.Optics.PROJ_EQUISOLID, Math.toRadians(70))));
        long sharp = time("f/8 landscape, no depth", rgb, null, base.withFocus(24, 8, 1e9));
        // per stage (the portrait settings)
        float[] img = ImagePipeline.toLinear(rgb);
        stage("toLinear", () -> ImagePipeline.toLinear(rgb));
        stage("depthOfField", () -> ImagePipeline.depthOfField(img.clone(), depth, W, H, base));
        stage("swirl", () -> ImagePipeline.swirl(img.clone(), ImagePipeline.blurRadius(depth, W, base.focus(), 64), W, H));
        stage("motionBlur", () -> ImagePipeline.motionBlur(img, W, H, 3, 1));
        stage("lensGeometry", () -> ImagePipeline.lensGeometry(img, W, H, base.lens()));
        stage("lens (vignette+sharp)", () -> ImagePipeline.lens(img.clone(), W, H, base));
        stage("wear", () -> ImagePipeline.wear(img.clone(), W, H, base));
        stage("flare", () -> ImagePipeline.flare(img.clone(), W, H, base));
        stage("noise", () -> ImagePipeline.noise(img.clone(), W, H, base));
        stage("finish", () -> ImagePipeline.finish(img.clone(), base));
        stage("toSrgb", () -> ImagePipeline.toSrgb(img));
        assertTrue(dof + swirl + fisheye + sharp < 120_000, "far too slow");
    }
}
