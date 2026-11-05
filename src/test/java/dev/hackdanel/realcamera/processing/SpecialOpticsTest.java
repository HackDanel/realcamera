package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hackdanel.realcamera.optics.Optics;
import org.junit.jupiter.api.Test;

/** Fisheye remap, tilt, shift, macro magnification and vintage bokeh characters. */
class SpecialOpticsTest {
    private static int red(int p) {
        return (p >> 16) & 255;
    }

    private static int[] flat(int w, int h, int rgb) {
        int[] a = new int[w * h];
        java.util.Arrays.fill(a, rgb);
        return a;
    }

    // ------------------------------------------------------------------ math

    @Test
    void macroLensReachesLifeSize() {
        // 100 mm focused at 0.4 m from the sensor is exactly 1:1 (u = v = 2f)
        assertEquals(1.0, Optics.magnification(100, 0.4), 1e-6);
        // a 50 mm at 0.45 m is about 1:7
        double m = Optics.magnification(50, 0.45);
        assertTrue(m > 0.12 && m < 0.16, "m " + m);
    }

    @Test
    void fisheyeProjectionsRoundTrip() {
        for (int proj : new int[]{Optics.PROJ_EQUISOLID, Optics.PROJ_STEREOGRAPHIC, Optics.PROJ_EQUIDISTANT}) {
            double r = Optics.fisheyeRadius(proj, 8, Math.toRadians(70));
            assertEquals(Math.toRadians(70), Optics.fisheyeAngle(proj, 8, r), 1e-9);
        }
        // 8 mm equisolid on full frame is a circular fisheye: the corner is outside the circle
        assertTrue(Double.isNaN(Optics.fisheyeAngle(Optics.PROJ_EQUISOLID, 8, 21.6)));
        // 8 mm equisolid on Micro Four Thirds covers about 180° on the diagonal
        double corner = Optics.fisheyeCornerAngle(Optics.PROJ_EQUISOLID, 8, 10.8);
        assertTrue(Math.toDegrees(corner) > 80, "corner " + Math.toDegrees(corner));
    }

    @Test
    void renderFovIsCappedForFisheyes() {
        double v = Optics.renderVfovDeg(Optics.PROJ_EQUISOLID, 8, 17.3, 13);
        assertTrue(v < 140 && v > 60, "rendered vfov " + v);
        assertEquals(Optics.verticalFovDeg(24, 50), Optics.renderVfovDeg(Optics.PROJ_RECTILINEAR, 50, 36, 24), 1e-9);
    }

    @Test
    void tiltFocusesTheGroundCloserAndTheTopFarther() {
        double mid = Optics.tiltedFocusM(24, 5, 4, 0);
        double low = Optics.tiltedFocusM(24, 5, 4, -10);
        double high = Optics.tiltedFocusM(24, 5, 4, 5);
        assertEquals(5, mid, 1e-9);
        assertTrue(low < mid && high > mid, low + " " + mid + " " + high);
    }

    // ------------------------------------------------------------------ images

    @Test
    void fisheyeBendsStraightLines() {
        int w = 120, h = 90;
        int[] img = flat(w, h, 0x202020);
        for (int x = 0; x < w; x++) img[30 * w + x] = 0xFFFFFF; // a straight line above the centre
        ImageParams p = ImageParams.neutral().withLens(ImageParams.neutral().lens()
                .withProjection(Optics.PROJ_EQUISOLID, Optics.renderCornerAngle(Optics.PROJ_EQUISOLID, 8, 10.8)));
        p = new ImageParams(p.sensor(), new ImageParams.Focus(8, 8, 1e9, 17.3), p.lens(), p.motion(), p.sun(),
                p.colour(), p.extras(), 1);
        int[] out = ImagePipeline.develop(img, null, w, h, p);
        // barrel: the line bows outwards, its ends bend down towards the centre
        int rowMid = brightestRow(out, w, h, w / 2);
        int rowSide = brightestRow(out, w, h, 20);
        assertTrue(rowMid < rowSide - 2, "middle " + rowMid + " side " + rowSide);
    }

    @Test
    void circularFisheyeBlacksOutCorners() {
        int w = 90, h = 60;
        ImageParams p = ImageParams.neutral().withLens(ImageParams.neutral().lens()
                .withProjection(Optics.PROJ_EQUISOLID, Optics.MAX_RENDER_HALF_ANGLE));
        p = new ImageParams(p.sensor(), new ImageParams.Focus(8, 8, 1e9, 36), p.lens(), p.motion(), p.sun(),
                p.colour(), p.extras(), 1);
        int[] out = ImagePipeline.develop(flat(w, h, 0x909090), null, w, h, p);
        assertTrue(red(out[0]) < 10, "corner " + red(out[0]));
        assertTrue(red(out[30 * w + 45]) > 100, "centre " + red(out[30 * w + 45]));
    }

    @Test
    void shiftStraightensConvergingVerticals() {
        int w = 100, h = 80;
        int[] img = flat(w, h, 0x101010);
        for (int y = 0; y < h; y++) img[y * w + 80] = 0xFFFFFF; // vertical in the pitched-up render
        for (int x = 0; x < w; x++) img[40 * w + x] = 0xFFFFFF; // whatever the render centre shows
        ImageParams base = ImageParams.neutral();
        ImageParams p = base.withLens(base.lens().withShift(40, 1));
        int[] out = ImagePipeline.develop(img, null, w, h, p);
        // the re-projection leans lines that were vertical in the tilted render (the converging ones)
        int top = brightestCol(out, w, 4, 60, 100);
        int bottom = brightestCol(out, w, 76, 60, 100);
        assertTrue(top - bottom >= 2, "top " + top + " bottom " + bottom);
        // the centre of the picture is what the pitched-up render showed in its centre
        assertEquals(40, brightestRow(out, w, h, 20), 1);
    }

    @Test
    void tiltLimitsSharpnessToABand() {
        int w = 128, h = 128;
        int[] checker = new int[w * h];
        float[] depth = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                checker[y * w + x] = ((x + y) & 1) == 0 ? 0xFFFFFF : 0x000000;
                depth[y * w + x] = 6f; // a wall at the focus distance
            }
        }
        ImageParams flat = ImageParams.neutral().withFocus(90, 2.8, 6);
        ImageParams tilted = flat.withTilt(-8); // reverse tilt: the "miniature" look
        int[] a = ImagePipeline.develop(checker, depth, w, h, flat);
        int[] b = ImagePipeline.develop(checker, depth, w, h, tilted);
        int edgeFlat = Math.abs(red(a[4 * w + 20]) - red(a[4 * w + 21]));
        int edgeTilt = Math.abs(red(b[4 * w + 20]) - red(b[4 * w + 21]));
        int midTilt = Math.abs(red(b[64 * w + 20]) - red(b[64 * w + 21]));
        assertTrue(edgeFlat > 200, "flat " + edgeFlat);
        assertTrue(midTilt > 200, "tilted middle " + midTilt);
        assertTrue(edgeTilt < 120, "tilted top " + edgeTilt);
    }

    @Test
    void bubbleBokehHasABrightRim() {
        int w = 90, h = 90;
        int[] img = flat(w, h, 0x080808);
        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) img[(45 + dy) * w + 45 + dx] = 0xFFFFFF;
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, 100f);
        ImageParams base = ImageParams.neutral().withExposure(2).withFocus(100, 2.8, 1.2);
        ImageParams bubble = base.withLens(base.lens().withBokeh(ImageParams.BOKEH_BUBBLE));
        int[] out = ImagePipeline.develop(img, depth, w, h, bubble);
        int radius = discEdge(out, w, 45, 45);
        int centre = red(out[45 * w + 45 + radius / 4]);
        int rim = red(out[45 * w + 45 + radius - 1]);
        assertTrue(rim > centre + 8, "rim " + rim + " centre " + centre + " r " + radius);
    }

    @Test
    void swirlSmearsTheBackgroundAlongCircles() {
        int w = 96, h = 96;
        int[] stripes = new int[w * h];
        float[] depth = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                stripes[y * w + x] = (x / 6) % 2 == 0 ? 0xC0C0C0 : 0x303030; // vertical stripes
                depth[y * w + x] = 60f;
            }
        }
        ImageParams base = ImageParams.neutral().withFocus(58, 2, 1.2);
        int[] smooth = ImagePipeline.develop(stripes, depth, w, h, base);
        int[] swirl = ImagePipeline.develop(stripes, depth, w, h, base.withLens(base.lens().withBokeh(ImageParams.BOKEH_SWIRL)));
        // at the left edge the tangent is vertical: stripes there are unaffected; at the top the tangent is
        // horizontal, so vertical stripes get smeared out
        int topSmooth = contrast(smooth, w, 4, 30, 66);
        int topSwirl = contrast(swirl, w, 4, 30, 66);
        assertTrue(topSwirl < topSmooth, "top contrast " + topSwirl + " vs " + topSmooth);
    }

    private static int brightestRow(int[] img, int w, int h, int col) {
        int best = 0, bestV = -1;
        for (int y = 0; y < h; y++) {
            int v = red(img[y * w + col]);
            if (v > bestV) {
                bestV = v;
                best = y;
            }
        }
        return best;
    }

    private static int brightestCol(int[] img, int w, int row, int from, int to) {
        int best = from, bestV = -1;
        for (int x = from; x < to; x++) {
            int v = red(img[row * w + x]);
            if (v > bestV) {
                bestV = v;
                best = x;
            }
        }
        return best;
    }

    private static int discEdge(int[] img, int w, int cx, int cy) {
        int bg = red(img[cy * w + w - 2]);
        int r = 1;
        while (cx + r < w - 1 && red(img[cy * w + cx + r]) > bg + 4) r++;
        return r;
    }

    private static int contrast(int[] img, int w, int row, int from, int to) {
        int min = 255, max = 0;
        for (int x = from; x < to; x++) {
            int v = red(img[row * w + x]);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return max - min;
    }
}
