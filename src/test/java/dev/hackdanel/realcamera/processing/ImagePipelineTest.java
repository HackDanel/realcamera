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
