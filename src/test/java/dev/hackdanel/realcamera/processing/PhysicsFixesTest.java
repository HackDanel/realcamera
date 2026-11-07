package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hackdanel.realcamera.optics.Optics;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The physics added after comparing the pipeline with real camera behaviour. */
class PhysicsFixesTest {
    private static final int W = 160, H = 100;

    private static float[] flat(float v) {
        float[] a = new float[W * H * 3];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static float[] depth(float d) {
        float[] a = new float[W * H];
        java.util.Arrays.fill(a, d);
        return a;
    }

    /** Horizontal contrast in a row band: mean |difference| of neighbouring pixels (green channel); sharp stripes 0.375. */
    private static double contrast(float[] img, int x0, int x1, int y0, int y1) {
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1 - 1; x++) {
                sum += Math.abs(img[(y * W + x) * 3 + 1] - img[(y * W + x + 1) * 3 + 1]);
                n++;
            }
        }
        return sum / n;
    }

    /** Vertical stripes (4 px period) everywhere. */
    private static float[] stripes() {
        float[] a = new float[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float v = (x / 2) % 2 == 0 ? 0.8f : 0.05f;
                for (int c = 0; c < 3; c++) a[(y * W + x) * 3 + c] = v;
            }
        }
        return a;
    }

    // ------------------------------------------------------------------ motion

    private static ImageParams.MotionField moverField(double vx, float near, float far) {
        // a 16x10 grid; the mover covers grid columns 6..9, rows 3..6 (pixels 60..99 x 30..69)
        int gw = 16, gh = 10;
        short[] cells = new short[gw * gh];
        java.util.Arrays.fill(cells, (short) -1);
        for (int y = 3; y <= 6; y++) for (int x = 6; x <= 9; x++) cells[y * gw + x] = 0;
        return new ImageParams.MotionField(gw, gh, cells, List.of(new ImageParams.Mover(vx, 0, 0, near, far)));
    }

    /** Background stripes at 20 m, the mover (also striped) at 5 m in the middle. */
    private static float[] sceneDepth() {
        float[] d = depth(20);
        for (int y = 32; y < 68; y++) for (int x = 62; x < 98; x++) d[y * W + x] = 5;
        return d;
    }

    private static ImageParams motionParams(double panPx, double moverVx, double seconds) {
        // 36 mm sensor imaged 160 px wide with a 50 mm lens: f = 222 px; a mover 5 m away at v m/s moves 44.4 v px/s
        var motion = new ImageParams.Motion(panPx, 0, 0, 0, seconds, 0, 0, 0, 0, 0, moverField(moverVx, 4.5f, 5.5f));
        return ImageParams.neutral().withFocus(50, 16, 1e9).withMotion(motion);
    }

    @Test
    void panningWithASubjectKeepsItSharpAndStreaksTheBackground() {
        // the subject runs right at 2.25 m/s for 1/10 s: +10 px; the photographer pans with it (world moves -10 px)
        ImageParams p = motionParams(-10, 2.25, 0.1);
        float[] out = MotionBlur.apply(stripes(), null, sceneDepth(), W, H, p);
        double subject = contrast(out, 66, 94, 40, 60), background = contrast(out, 5, 40, 10, 90);
        assertTrue(subject > 0.3, "subject sharp " + subject);
        assertTrue(background < 0.1, "background streaked " + background);
    }

    @Test
    void aStillCameraFreezesTheBackgroundAndSmearsTheRunner() {
        ImageParams p = motionParams(0, 2.25, 0.1);
        float[] out = MotionBlur.apply(stripes(), null, sceneDepth(), W, H, p);
        double subject = contrast(out, 66, 94, 40, 60), background = contrast(out, 5, 40, 10, 90);
        assertTrue(background > 0.3, "background sharp " + background);
        assertTrue(subject < 0.1, "runner smeared " + subject);
    }

    @Test
    void aMovingCameraBlursNearThingsMoreThanFarOnes() {
        float[] d = depth(200);
        for (int y = 0; y < H / 2; y++) for (int x = 0; x < W; x++) d[y * W + x] = 3; // top half near
        // riding sideways at 5 m/s, 1/30 s
        var motion = new ImageParams.Motion(0, 0, 0, 0, 1 / 30.0, 5, 0, 0, 0, 0, null);
        ImageParams p = ImageParams.neutral().withFocus(50, 16, 1e9).withMotion(motion);
        float[] out = MotionBlur.apply(stripes(), null, d, W, H, p);
        double near = contrast(out, 20, 140, 10, 40), far = contrast(out, 20, 140, 60, 90);
        assertTrue(near < 0.1 && far > 0.3, "near " + near + " far " + far);
    }

    @Test
    void flashFreezesWhatTheAmbientLightSmears() {
        float[] ambient = flat(0), flash = stripes();
        ImageParams p = ImageParams.neutral().withFocus(50, 16, 1e9).withMotion(new ImageParams.Motion(-12, 0, 0));
        float[] out = MotionBlur.apply(ambient, flash, depth(20), W, H, p);
        assertTrue(contrast(out, 20, 140, 10, 90) > 0.3, "flash image stays sharp");
    }

    @Test
    void rearCurtainPlacesTheFlashImageAtTheEnd() {
        float[] spot = flat(0);
        int j = (50 * W + 80) * 3;
        spot[j] = spot[j + 1] = spot[j + 2] = 1;
        ImageParams front = ImageParams.neutral().withMotion(new ImageParams.Motion(20, 0, 0));
        ImageParams rear = front.withExtras(new ImageParams.Extras(0, 1, false, 1, 1, 1, 0, 0, new float[0], true));
        int xf = peakX(MotionBlur.apply(flat(0), spot.clone(), depth(20), W, H, front));
        int xr = peakX(MotionBlur.apply(flat(0), spot.clone(), depth(20), W, H, rear));
        assertEquals(70, xf, 1); // the picture moves +20 px: the flash at the start sits 10 px before the middle
        assertEquals(90, xr, 1);
    }

    /** Column of the brightest pixel (hand shake may move the point up or down a little). */
    private static int peakX(float[] img) {
        int best = 0;
        for (int i = 0; i < W * H; i++) if (img[i * 3] > img[best * 3]) best = i;
        return best % W;
    }

    @Test
    void rollingShutterStretchesOnATilt() {
        float[] img = flat(0);
        for (int x = 0; x < W; x++) for (int c = 0; c < 3; c++) img[(80 * W + x) * 3 + c] = 1; // a line low in the frame
        float[] out = ImagePipeline.rollingShutter(img, W, H, 0, 20);
        int row = -1;
        for (int y = 0; y < H; y++) if (out[(y * W + 80) * 3] > 0.5f) row = y;
        // sampled 20 * (y/H - 0.5) further down: the line shows higher up
        assertTrue(row >= 0 && row < 80, "row " + row);
    }

    // ------------------------------------------------------------------ noise

    private static ImageParams.Sensor sensor(double iso, ImageParams.Dark dark) {
        return new ImageParams.Sensor(0, iso / 100, 1, 1, 1, 13, 1, 0, dark);
    }

    private static double spread(ImageParams.Sensor s) {
        float[] img = flat(0.01f);
        ImagePipeline.noise(img, W, H, ImageParams.neutral().withSensor(s));
        double sum = 0, sum2 = 0;
        int n = W * H;
        for (int i = 0; i < n; i++) {
            double v = img[i * 3 + 1];
            sum += v;
            sum2 += v * v;
        }
        double m = sum / n;
        return Math.sqrt(sum2 / n - m * m);
    }

    @Test
    void longWarmExposuresAreNoisier() {
        double quick = spread(sensor(1600, ImageParams.NO_DARK));
        double room = spread(sensor(1600, new ImageParams.Dark(60, 20, true, 1)));
        double hot = spread(sensor(1600, new ImageParams.Dark(60, 40, true, 1)));
        assertTrue(room > quick * 1.1, quick + " -> " + room);
        assertTrue(hot > room * 1.5, room + " -> " + hot);
    }

    @Test
    void hotPixelsStayPutAndDarkFramesRemoveThem() {
        var s = new ImageParams.Sensor(0, 16, 1, 1, 0.05, 13, 1, 0, new ImageParams.Dark(30, 25, false, 42));
        float[] a = flat(0), b = flat(0);
        ImagePipeline.hotPixels(a, W, H, s, 16);
        ImagePipeline.hotPixels(b, W, H, s, 16);
        assertArrayEquals(a, b);
        int lit = 0;
        for (float v : a) if (v > 0) lit++;
        assertTrue(lit > 0, "hot pixels " + lit);
        float[] c = flat(0);
        ImagePipeline.hotPixels(c, W, H, s.withDark(new ImageParams.Dark(30, 25, true, 42)), 16);
        for (float v : c) assertEquals(0, v);
    }

    // ------------------------------------------------------------------ optics

    @Test
    void axialColourFringesPurpleInFrontAndGreenBehind() {
        // a bright bar against black, wide open: in front of the focus plane red/blue spread further than green
        float[] img = flat(0);
        for (int y = 30; y < 70; y++) for (int x = 70; x < 90; x++) for (int c = 0; c < 3; c++) img[(y * W + x) * 3 + c] = 0.6f;
        ImageParams base = ImageParams.neutral().withFocus(50, 1.4, 3);
        // (exaggerated so the 160 px test frame shows it)
        base = base.withLens(new ImageParams.Lens(0, 1.4, 1, 0, 0, 1, 9, true, 0).withLoca(1.0));
        float[] front = ImagePipeline.depthOfField(img.clone(), depth(0.8f), W, H, base);
        float[] behind = ImagePipeline.depthOfField(img.clone(), depth(30f), W, H, base);
        // light spilled outside the bar (its outer fringe), per channel
        float[] fr = spill(front), bh = spill(behind);
        assertTrue(fr[0] > fr[1] * 1.02 && fr[2] > fr[1] * 1.02, "purple in front " + java.util.Arrays.toString(fr));
        assertTrue(bh[1] > bh[0] * 1.02 && bh[1] > bh[2] * 1.02, "green behind " + java.util.Arrays.toString(bh));
    }

    private static float[] spill(float[] img) {
        float[] sum = new float[3];
        for (int y = 30; y < 70; y++) {
            for (int x = 90; x < 110; x++) {
                for (int c = 0; c < 3; c++) sum[c] += img[(y * W + x) * 3 + c];
            }
        }
        return sum;
    }

    @Test
    void diffractionSpreadsRedMoreThanBlue() {
        float[] img = flat(0);
        int j = (50 * W + 80) * 3;
        img[j] = img[j + 1] = img[j + 2] = 1;
        ImagePipeline.diffraction(img, W, H, new double[]{2.0, 1.75, 1.5});
        assertTrue(img[j] < img[j + 2], "red peak lower (spread wider) than blue");
        assertEquals(Optics.diffractionSigmaMm(22, 0.00053), 0.42 * 0.00053 * 22, 1e-12);
    }

    @Test
    void polariserDarkensTheSkyMostAtRightAnglesToTheSun() {
        assertEquals(0.75, Optics.skyPolarization(Math.PI / 2), 1e-9);
        assertEquals(0, Optics.skyPolarization(0), 1e-9);
        // sun to the right, at the horizon: the sky straight ahead (90°) darkens, the sky near the sun hardly
        ImageParams p = ImageParams.neutral().withFocus(14, 8, 1e9)
                .withSun(new ImageParams.Sun(0, 0, 0, 0.9, 0, 0.436, 1));
        float[] img = flat(0.4f);
        ImagePipeline.polariser(img, depth(10_000), W, H, p);
        float ahead = img[(50 * W + 10) * 3 + 1], nearSun = img[(50 * W + 159) * 3 + 1];
        assertTrue(ahead < nearSun * 0.8, ahead + " vs " + nearSun);
    }

    @Test
    void bokehIsNoLongerCappedAtAnEighthOfTheFrame() {
        // 600 mm f/4 focused at 5 m, lights at infinity: a 20 mm disc on a 36 mm sensor
        var f = new ImageParams.Focus(600, 4, 5, 36);
        float r = ImagePipeline.blurRadius(depth(10_000), W, f, W * 0.3f)[0];
        assertTrue(r > W / 8f * 1.5, "radius " + r);
    }

    // ------------------------------------------------------------------ specs

    private static dev.hackdanel.realcamera.spec.LensSpec lens(float min, float max, float n, float mfd, float maxMag) {
        var optics = new dev.hackdanel.realcamera.spec.LensSpec.Optics(9, true, 1, 1, 0, 0, 1, 0.5f, "smooth",
                "rectilinear", java.util.Optional.empty(), maxMag, -1, -1);
        return new dev.hackdanel.realcamera.spec.LensSpec("Test", "Test", net.minecraft.resources.ResourceKey.create(
                dev.hackdanel.realcamera.registry.RCRegistries.MOUNT, net.minecraft.resources.ResourceLocation.parse("realcamera:test")),
                "ff", min, max, n, n, 22, mfd, dev.hackdanel.realcamera.spec.LensSpec.Af.DEFAULT, 0, optics, 77, false,
                net.minecraft.resources.ResourceLocation.parse("realcamera:test"));
    }

    @Test
    void internalFocusingZoomBreathesWiderCloseUp() {
        // a 70-200 that reaches 0.12x at 1.4 m: thin-lens 200 mm there would be 0.17x
        var zoom = lens(70, 200, 2.8f, 1.4f, 0.12f);
        assertEquals(0.12, zoom.magnificationAt(200, 1.4), 1e-6);
        double framing = 1400 * zoom.magnificationAt(200, 1.4);
        assertTrue(framing < 200, "frames like " + framing + " mm");
        // far away it is its nominal focal length again
        double far = 50_000 * zoom.magnificationAt(200, 50);
        assertEquals(200, far, 3);
    }

    @Test
    void zoomFramesNarrowerAtEveryFocusDistanceAndKeepsItsWideEnd() {
        // a 200-600 that reaches 0.2x at 2.4 m (where a thin 600 mm lens would already be at 1:1)
        var zoom = lens(200, 600, 5.6f, 2.4f, 0.2f);
        for (double focus : new double[] {2.4, 5, 20, 1000}) {
            double prev = 0;
            for (float f = 200; f <= 600; f += 20) {
                double framing = zoom.framingAt(f, focus);
                assertTrue(framing > prev, "zooming in at " + focus + " m: " + f + " mm frames like " + framing);
                // breathing stays modest: the 200 mm end must not turn into a 100 mm lens
                assertTrue(framing > f * 0.74 && framing < f * 1.01, f + " mm at " + focus + " m frames like " + framing);
                prev = framing;
            }
        }
        assertEquals(480, zoom.framingAt(600, 2.4), 1e-6);
        assertEquals(160, zoom.framingAt(200, 2.4), 1e-6);
        // closer than the minimum focus distance the lens stays at its closest
        assertEquals(zoom.framingAt(600, 2.4), zoom.framingAt(600, 0.5), 1e-9);
    }

    @Test
    void lensTransmissionAndAxialColourDefaults() {
        assertEquals(0.3, lens(24, 70, 2.8f, 0.38f, 0).transmissionLossEv(), 1e-9);
        assertEquals(0.15, lens(50, 50, 1.4f, 0.45f, 0).transmissionLossEv(), 1e-9);
        // a fast prime shows more axial colour than a slow one of the same focal length
        assertTrue(lens(50, 50, 1.4f, 0.45f, 0).locaMm(50) > lens(50, 50, 4f, 0.45f, 0).locaMm(50) * 2);
    }

    @Test
    void slideFilmDriftsGreenOnLongExposures() {
        var velvia = new dev.hackdanel.realcamera.spec.FilmStockSpec("Velvia", "e6", 50, 36, 12, 0.7f, 0.7f,
                new dev.hackdanel.realcamera.spec.FilmStockSpec.Look(1, 1, false, 1, 1, 1, 0),
                new dev.hackdanel.realcamera.spec.FilmStockSpec.Grain(0.1f, 1, 0), 0.88f,
                java.util.Optional.of(List.of(1f, 1.045f, 0.99f)));
        double[] t = velvia.reciprocityTint(32);
        assertEquals(Math.pow(1.045, 5), t[1], 1e-6); // CC 12.5M territory at 32 s
        assertArrayEquals(new double[]{1, 1, 1}, velvia.reciprocityTint(0.5), 1e-12);
    }
}
