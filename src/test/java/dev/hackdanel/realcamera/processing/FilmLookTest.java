package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hackdanel.realcamera.photo.FilmLab;
import org.junit.jupiter.api.Test;

class FilmLookTest {
    private static int[] grey(int w, int h, int v) {
        int[] rgb = new int[w * h];
        java.util.Arrays.fill(rgb, (v << 16) | (v << 8) | v);
        return rgb;
    }

    private static int g(int p) {
        return (p >> 8) & 255;
    }

    private static double spread(float[] lin) {
        double mean = 0, sq = 0;
        int n = lin.length / 3;
        for (int i = 0; i < lin.length; i += 3) mean += lin[i + 1];
        mean /= n;
        for (int i = 0; i < lin.length; i += 3) sq += (lin[i + 1] - mean) * (lin[i + 1] - mean);
        return Math.sqrt(sq / n);
    }

    @Test
    void grainAddsNoiseThatGrowsWithAmountAndKeepsTheMean() {
        float[] a = ImagePipeline.toLinear(grey(64, 64, 128)), b = a.clone();
        FilmLook.grain(a, 64, 64, 0.2, 1.5, 7);
        FilmLook.grain(b, 64, 64, 0.6, 1.5, 7);
        assertTrue(spread(a) > 0.005, "visible grain");
        assertTrue(spread(b) > spread(a) * 2, "more grain");
        double mean = 0;
        for (int i = 1; i < b.length; i += 3) mean += b[i];
        assertEquals(ImagePipeline.toLinear(grey(1, 1, 128))[1], mean / (64 * 64), 0.02);
    }

    @Test
    void halationGlowsRedAroundHighlights() {
        int w = 40, h = 40;
        float[] lin = new float[w * h * 3];
        for (int y = 18; y < 22; y++) {
            for (int x = 18; x < 22; x++) {
                int j = (y * w + x) * 3;
                lin[j] = lin[j + 1] = lin[j + 2] = 4f; // a bright light
            }
        }
        FilmLook.halation(lin, w, h, 1);
        int j = (20 * w + 24) * 3; // just beside the light
        assertTrue(lin[j] > 0.05, "glow");
        assertTrue(lin[j] > lin[j + 1] * 2 && lin[j + 1] > lin[j + 2], "red-orange");
    }

    @Test
    void pushBrightensAndPullDarkens() {
        int[] img = grey(16, 16, 90);
        assertTrue(g(FilmLook.push(img, 16, 16, 2, 1)[0]) > 110);
        assertTrue(g(FilmLook.push(img, 16, 16, -1, 1)[0]) < 90);
        assertArrayEquals(img, FilmLook.push(img, 16, 16, 0, 1));
    }

    @Test
    void lightLeakBurnsTheFrameAtFullSeverity() {
        int[] img = grey(32, 20, 40);
        int[] burnt = FilmLook.lightLeak(img, 32, 20, 1, 3);
        int bright = 0;
        for (int p : burnt) if (((p >> 16) & 255) > 200) bright++;
        assertTrue(bright > burnt.length * 0.9, "almost all burnt: " + bright);
        int[] leak = FilmLook.lightLeak(img, 32, 20, 0.3, 3);
        int touched = 0;
        for (int i = 0; i < img.length; i++) if (leak[i] != (img[i] | 0xFF000000)) touched++;
        assertTrue(touched > 0 && touched < img.length, "a streak from one edge");
    }

    @Test
    void darkroomPrintsAreMonochromeOnBlackAndWhitePaperAndFoggedPaperIsGrey() {
        int[] img = new int[64];
        for (int i = 0; i < 64; i++) img[i] = i % 2 == 0 ? 0xC03020 : 0x2040C0;
        int[] bw = FilmLab.print(img, 8, 8, 0, 2, "rc_bw", false);
        for (int p : bw) {
            int r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
            assertTrue(Math.abs(r - gg) <= 2 && Math.abs(gg - b) <= 2, "neutral grey");
        }
        int[] hard = FilmLab.print(grey(4, 4, 60), 4, 4, 0, 5, "rc_bw", false);
        int[] soft = FilmLab.print(grey(4, 4, 60), 4, 4, 0, 0, "rc_bw", false);
        assertTrue(g(hard[0]) < g(soft[0]), "harder grade: darker shadows");
        int[] fog = FilmLab.print(grey(4, 4, 220), 4, 4, 0, 2, "rc_bw", true);
        assertTrue(g(fog[0]) < 120, "fogged paper goes grey");
    }

    @Test
    void negativeFilmHasMoreLatitudeThanSlide() {
        assertTrue(FilmLook.dynamicRange(3) > FilmLook.dynamicRange(0.7) + 3);
    }
}
