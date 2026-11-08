package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LensBlurTest {
    private static final BokehKernel.Shape ROUND = new BokehKernel.Shape(9, true, ImageParams.BOKEH_SMOOTH, 0);

    private static float[] grey(int w, int h, float v) {
        float[] a = new float[w * h * 3];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static float[] fill(int n, float v) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static void set(float[] img, int w, int x, int y, float r, float g, float b) {
        int j = (y * w + x) * 3;
        img[j] = r;
        img[j + 1] = g;
        img[j + 2] = b;
    }

    @Test
    void anEdgeBlursOnlyWithinTheDisc() {
        // a gaussian leaves long tails; a lens disc of radius r moves the edge by at most r
        int w = 120, h = 40, r = 10, edge = 60;
        float[] img = grey(w, h, 0);
        for (int y = 0; y < h; y++) for (int x = edge; x < w; x++) set(img, w, x, y, 1, 1, 1);
        float[] out = LensBlur.apply(img, fill(w * h, -r), w, h, ROUND);
        int y = h / 2;
        float before = out[(y * w + edge - r - 2) * 3], after = out[(y * w + edge + r + 1) * 3];
        float half = out[(y * w + edge + r / 2) * 3];
        assertTrue(before < 0.01f, "dark side untouched beyond the disc: " + before);
        assertTrue(after > 0.99f, "bright side untouched beyond the disc: " + after);
        assertTrue(half > 0.75f, "half-way into the disc mostly bright: " + half);
    }

    @Test
    void aMidToneDotTakesTheApertureShape() {
        // six straight blades two stops down: a hexagon, corner along +x (also for points below the highlight threshold)
        int w = 64, h = 64, cx = 32, cy = 32;
        float[] img = grey(w, h, 0);
        set(img, w, cx, cy, 0.4f, 0.4f, 0.4f);
        BokehKernel.Shape hex = new BokehKernel.Shape(6, false, ImageParams.BOKEH_SMOOTH, 2);
        // radii of whole layers (1.5^k), so the disc is not a mix of two sizes
        float small = (float) LensBlur.layerRadius(4), large = (float) LensBlur.layerRadius(8); // 5.1 and 25.6 px
        float[] out = LensBlur.apply(img, fill(w * h, -small), w, h, hex);
        float corner = out[(cy * w + cx + 4) * 3];
        float flat = out[((cy + 3) * w + cx + 5) * 3]; // 5.8 px out at 31°, past the flat side (4.4 px)
        assertTrue(corner > 1e-4f, "corner lit " + corner);
        assertEquals(0, flat, 1e-6, "outside the flat side");
        // a large disc is convolved at reduced resolution (softer rim) and keeps its corners: at the same distance a
        // corner is brighter than the middle of a side
        out = LensBlur.apply(img, fill(w * h, -large), w, h, hex);
        corner = out[(cy * w + cx + 24) * 3];
        flat = out[((cy + 12) * w + cx + 21) * 3]; // 24.2 px out at 30°, past the flat side (22.2 px)
        assertTrue(corner > 1.3f * flat, "corner " + corner + " vs flat side " + flat);
    }

    @Test
    void aSharpSubjectGetsNoHalo() {
        int w = 120, h = 80;
        float[] img = grey(w, h, 1);
        float[] signed = fill(w * h, -20);
        for (int y = 30; y < 50; y++) {
            for (int x = 50; x < 70; x++) {
                set(img, w, x, y, 0.2f, 0.2f, 0.2f);
                signed[y * w + x] = 0;
            }
        }
        float[] out = LensBlur.apply(img, signed, w, h, ROUND);
        for (int y = 31; y < 49; y++) {
            for (int x = 51; x < 69; x++) {
                assertEquals(0.2f, out[(y * w + x) * 3], 1e-5, "subject unchanged at " + x + "," + y);
            }
        }
        // the blurred background next to the subject does not take its colour
        assertTrue(out[(40 * w + 48) * 3] > 0.97f, "background beside the subject " + out[(40 * w + 48) * 3]);
        assertTrue(out[(28 * w + 60) * 3] > 0.97f, "background above the subject " + out[(28 * w + 60) * 3]);
    }

    @Test
    void aBlurredForegroundIsSeeThroughAtItsEdges() {
        // a red blade 1 m in front of a sharp green wall: the wall shows through it and the blade spreads over it
        int w = 160, h = 60, x0 = 77, x1 = 83;
        float[] img = new float[w * h * 3];
        float[] signed = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean blade = x >= x0 && x < x1;
                set(img, w, x, y, blade ? 1 : 0, blade ? 0 : 0.5f, 0);
                signed[y * w + x] = blade ? 10 : 0;
            }
        }
        float[] out = LensBlur.apply(img, signed, w, h, ROUND);
        int y = h / 2, mid = (y * w + 80) * 3, beside = (y * w + x1 + 4) * 3;
        assertTrue(out[mid] < 0.8f && out[mid + 1] > 0.15f, "wall through the blade: r=" + out[mid] + " g=" + out[mid + 1]);
        assertTrue(out[beside] > 0.1f, "blade over the wall: r=" + out[beside]);
        assertEquals(0.5f, out[(y * w + 20) * 3 + 1], 1e-5, "wall far from the blade untouched");
    }

    @Test
    void lightIsKept() {
        int w = 96, h = 64;
        float[] img = new float[w * h * 3];
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < img.length; i++) img[i] = rnd.nextFloat();
        float[] out = LensBlur.apply(img, fill(w * h, -7), w, h, ROUND);
        double a = 0, b = 0;
        for (int i = 0; i < img.length; i++) {
            a += img[i];
            b += out[i];
        }
        assertEquals(1, b / a, 0.01);
    }

    @Test
    void frontBokehIsTheInvertedAperture() {
        BokehKernel.Shape tri = new BokehKernel.Shape(5, false, ImageParams.BOKEH_SMOOTH, 2);
        BokehKernel.Kernel far = BokehKernel.kernel(tri, 8, false), near = BokehKernel.kernel(tri, 8, true);
        java.util.Map<Long, Float> f = new java.util.HashMap<>();
        for (int i = 0; i < far.w().length; i++) f.put(((long) far.dx()[i] << 32) ^ (far.dy()[i] & 0xffffffffL), far.w()[i]);
        for (int i = 0; i < near.w().length; i++) {
            long mirrored = ((long) -near.dx()[i] << 32) ^ (-near.dy()[i] & 0xffffffffL);
            assertEquals(near.w()[i], f.getOrDefault(mirrored, 0f), 1e-6);
        }
    }
}
