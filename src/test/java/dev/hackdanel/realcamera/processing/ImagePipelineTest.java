package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImagePipelineTest {
    private static int[] flat(int w, int h, int rgb) {
        int[] a = new int[w * h];
        java.util.Arrays.fill(a, rgb);
        return a;
    }

    private static int red(int p) {
        return (p >> 16) & 255;
    }

    @Test
    void neutralParamsKeepMidGrey() {
        int[] out = ImagePipeline.develop(flat(8, 8, 0x777777), null, 8, 8, ImageParams.neutral());
        assertEquals(0x77, red(out[27]), 2);
    }

    @Test
    void oneStopOverIsBrighter() {
        int[] base = ImagePipeline.develop(flat(8, 8, 0x404040), null, 8, 8, ImageParams.neutral());
        int[] over = ImagePipeline.develop(flat(8, 8, 0x404040), null, 8, 8, ImageParams.neutral().withExposure(1));
        // +1 EV doubles linear light: 0x40 (≈0.051 lin) -> ≈0.102 lin ≈ 0x59
        assertTrue(red(over[0]) > red(base[0]) + 15, base[0] + " vs " + over[0]);
    }

    @Test
    void highlightsRollOffInsteadOfWrapping() {
        int[] out = ImagePipeline.develop(flat(4, 4, 0xF0F0F0), null, 4, 4, ImageParams.neutral().withExposure(3));
        assertEquals(255, red(out[0]), 3);
    }

    @Test
    void outOfFocusAreaIsSmoothed() {
        int w = 64, h = 64;
        int[] checker = new int[w * h];
        float[] depth = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                checker[y * w + x] = ((x + y) & 1) == 0 ? 0xFFFFFF : 0x000000;
                depth[y * w + x] = x < w / 2 ? 2f : 50f; // left half in focus at 2 m, right far behind
            }
        }
        ImageParams p = ImageParams.neutral().withFocus(85, 1.8, 2);
        int[] out = ImagePipeline.develop(checker, depth, w, h, p);
        // in-focus half keeps full contrast, out-of-focus half becomes near-uniform grey
        int sharpDiff = Math.abs(red(out[10 * w + 10]) - red(out[10 * w + 11]));
        int blurDiff = Math.abs(red(out[10 * w + 50]) - red(out[10 * w + 51]));
        assertTrue(sharpDiff > 200, "sharp " + sharpDiff);
        assertTrue(blurDiff < 30, "blur " + blurDiff);
    }

    @Test
    void highIsoAddsNoise() {
        ImageParams clean = ImageParams.neutral().withNoise(1, 1, 1);
        ImageParams noisy = ImageParams.neutral().withNoise(128, 1, 1);
        assertTrue(spread(ImagePipeline.develop(flat(32, 32, 0x555555), null, 32, 32, noisy))
                > spread(ImagePipeline.develop(flat(32, 32, 0x555555), null, 32, 32, clean)) + 5);
    }

    private static int spread(int[] px) {
        int min = 255, max = 0;
        for (int p : px) {
            min = Math.min(min, red(p));
            max = Math.max(max, red(p));
        }
        return max - min;
    }

    @Test
    void motionBlurSmearsAlongDirection() {
        int w = 40, h = 10;
        int[] img = new int[w * h];
        for (int i = 0; i < img.length; i++) img[i] = (i % w) == 20 ? 0xFFFFFF : 0x000000; // vertical white line
        int[] out = ImagePipeline.develop(img, null, w, h, ImageParams.neutral().withMotion(new ImageParams.Motion(10, 0, 0)));
        assertTrue(red(out[5 * w + 24]) > 40, "smeared right");
        assertTrue(red(out[5 * w + 20]) < 200, "line dimmed");
        int[] vertical = ImagePipeline.develop(img, null, w, h, ImageParams.neutral().withMotion(new ImageParams.Motion(0, 6, 0)));
        assertTrue(red(vertical[5 * w + 24]) < 5, "vertical blur keeps a vertical line sharp sideways");
    }

    @Test
    void rollingShutterShearsVerticalLines() {
        int w = 40, h = 40;
        int[] img = new int[w * h];
        for (int i = 0; i < img.length; i++) img[i] = (i % w) == 20 ? 0xFFFFFF : 0x000000;
        int[] out = ImagePipeline.develop(img, null, w, h, ImageParams.neutral().withMotion(new ImageParams.Motion(0, 0, 16)));
        // top rows sampled from the left of the line, bottom rows from the right: the line leans
        assertTrue(red(out[2 * w + 27]) > 100, "top shifted");
        assertTrue(red(out[37 * w + 13]) > 100, "bottom shifted");
    }

    @Test
    void barrelDistortionCompressesEdgesTowardsCorner() {
        double k1 = -0.15;
        assertEquals(1, ImagePipeline.distortionScale(1, k1), 1e-9, "corner samples the corner");
        double centre = ImagePipeline.distortionScale(0, k1), edge = ImagePipeline.distortionScale(0.5, k1);
        assertTrue(centre < edge, "barrel: magnification falls towards the edge");
        // pincushion keeps everything inside the rendered frame
        assertTrue(ImagePipeline.distortionScale(1, 0.05) < 1);
        assertEquals(1, ImagePipeline.distortionScale(0.7, 0), 1e-12);
    }

    @Test
    void imageCircleBlacksOutCorners() {
        var lens = new ImageParams.Lens(0, 8, 1, 0, 0, 0.66, 7, true, 0);
        int[] out = ImagePipeline.develop(flat(30, 20, 0xAAAAAA), null, 30, 20, ImageParams.neutral().withLens(lens));
        assertTrue(red(out[0]) < 10, "corner dark");
        assertTrue(red(out[10 * 30 + 15]) > 150, "centre lit");
    }

    @Test
    void brightOutOfFocusPointBecomesDisc() {
        int w = 80, h = 80;
        int[] img = flat(w, h, 0x101010);
        img[40 * w + 40] = 0xFFFFFF;
        img[40 * w + 41] = 0xFFFFFF;
        img[41 * w + 40] = 0xFFFFFF;
        img[41 * w + 41] = 0xFFFFFF;
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, 100f);
        ImageParams p = ImageParams.neutral().withExposure(2).withFocus(85, 1.8, 1.5);
        int[] out = ImagePipeline.develop(img, depth, w, h, p);
        int edge = red(out[40 * w + 45]);
        int far = red(out[40 * w + 70]);
        assertTrue(edge > far + 10, "disc around the highlight: " + edge + " vs " + far);
    }

    @Test
    void sunInFrameAddsGlare() {
        int[] base = ImagePipeline.develop(flat(40, 30, 0x202020), null, 40, 30, ImageParams.neutral());
        int[] flare = ImagePipeline.develop(flat(40, 30, 0x202020), null, 40, 30,
                ImageParams.neutral().withLens(new ImageParams.Lens(0, 8, 1, 0, 0, 1, 7, true, 1))
                        .withSun(new ImageParams.Sun(0.5, 0.5, 1)));
        assertTrue(red(flare[15 * 40 + 20]) > red(base[15 * 40 + 20]), "veiling glare lifts the image");
    }

    @Test
    void polygonRadiusIsBetweenInscribedAndCircumscribed() {
        for (double t = 0; t < 2 * Math.PI; t += 0.1) {
            double r = ImagePipeline.polygonRadius(t, 7, 10);
            assertTrue(r <= 10.0001 && r >= 10 * Math.cos(Math.PI / 7) - 1e-4, "r=" + r);
        }
    }

    @Test
    void flashLightsNearThingsMoreThanFarOnes() {
        int w = 40, h = 20;
        int[] dark = flat(w, h, 0x101010);
        float[] depth = new float[w * h];
        for (int i = 0; i < depth.length; i++) depth[i] = (i % w) < w / 2 ? 1.5f : 8f;
        float[] light = new float[w * h];
        java.util.Arrays.fill(light, 0.05f);
        ImageParams p = ImageParams.neutral().withExtras(new ImageParams.Extras(2.0, 1, false, 1, 1, 1, 0, 0, new float[0]));
        int[] out = ImagePipeline.develop(dark, depth, light, w, h, p);
        int near = red(out[10 * w + 5]), far = red(out[10 * w + 35]);
        assertTrue(near > far + 40, "near " + near + " far " + far);
    }

    @Test
    void curtainShadowAboveSyncSpeed() {
        int w = 20, h = 40;
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, 1.5f);
        float[] light = new float[w * h];
        java.util.Arrays.fill(light, 0.05f); // dark scene: the flash is the main light
        ImageParams p = ImageParams.neutral().withExtras(new ImageParams.Extras(2.0, 0.5, false, 1, 1, 1, 0, 0, new float[0]));
        int[] out = ImagePipeline.develop(flat(w, h, 0x101010), depth, light, w, h, p);
        assertTrue(red(out[5 * w + 10]) > red(out[35 * w + 10]) + 30, "bottom half has no flash");
    }

    @Test
    void dustShowsOnlyStoppedDown() {
        float[] dust = {0, 0, 0.1f};
        var extras = new ImageParams.Extras(0, 1, false, 1, 1, 1, 0, 0, dust);
        int[] open = ImagePipeline.develop(flat(40, 40, 0xC0C0C0), null, 40, 40,
                ImageParams.neutral().withFocus(50, 2.8, 1e9).withExtras(extras));
        int[] closed = ImagePipeline.develop(flat(40, 40, 0xC0C0C0), null, 40, 40,
                ImageParams.neutral().withFocus(50, 16, 1e9).withExtras(extras));
        assertEquals(red(open[0]), red(open[20 * 40 + 20]), 2, "invisible wide open");
        assertTrue(red(closed[20 * 40 + 20]) < red(closed[0]) - 20, "dark spot at f/16");
    }

    @Test
    void redFilterDarkensBlueInMonochrome() {
        int[] blue = flat(4, 4, 0x3060E0);
        ImageParams.Colour mono = new ImageParams.Colour(1, 1, 1, 1, 1, true, 1, 1, 1, 0);
        ImageParams plain = new ImageParams(ImageParams.neutral().sensor(), ImageParams.neutral().focus(),
                ImageParams.neutral().lens(), ImageParams.STILL, ImageParams.NO_SUN, mono, ImageParams.NO_EXTRAS, 1);
        int base = red(ImagePipeline.develop(blue, null, 4, 4, plain)[0]);
        int filtered = red(ImagePipeline.develop(blue, null, 4, 4,
                plain.withExtras(new ImageParams.Extras(0, 1, false, 1, 0.35, 0.1, 0, 0, new float[0])))[0]);
        assertTrue(filtered < base - 20, base + " -> " + filtered);
    }

    @Test
    void tungstenSettingInDaylightTurnsBlue() {
        double[] m = ImagePipeline.whiteBalance(5500, 3200);
        assertTrue(m[2] > 1.2 && m[0] < 0.9, java.util.Arrays.toString(m));
        assertArrayEquals(new double[]{1, 1, 1}, ImagePipeline.whiteBalance(5500, 5500), 1e-9);
    }

    @Test
    void jpegRoundTripWithExif() throws Exception {
        int[] px = flat(16, 8, 0x3366CC);
        byte[] jpeg = JpegCodec.encode(px, 16, 8, 0.9f);
        byte[] withExif = ExifWriter.insert(jpeg, new ExifWriter.Data("SONY", "ILCE-7M4", "Sony", "FE 85mm F1.8",
                "Tester", "2026:11:05 12:00:00", 1 / 250.0, 1.8, 400, 85, 85, 3, -0.333, 5, false, 16, 8));
        assertEquals(0xFF, withExif[0] & 0xFF);
        assertEquals(0xE1, withExif[3] & 0xFF);
        assertTrue(new String(withExif, 0, 200, StandardCharsets.ISO_8859_1).contains("ILCE-7M4"));
        int[] size = new int[2];
        int[] back = JpegCodec.decode(withExif, size);
        assertEquals(16, size[0]);
        assertEquals(8, size[1]);
        assertEquals(0x33, red(back[0]), 6);
    }

    @Test
    void downscaleKeepsAspect() {
        int[] size = new int[2];
        ImagePipeline.downscale(flat(300, 200, 0xFF0000), 300, 200, 150, size);
        assertEquals(150, size[0]);
        assertEquals(100, size[1]);
    }
}
