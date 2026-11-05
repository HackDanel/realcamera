package dev.hackdanel.realcamera.processing;

import java.util.SplittableRandom;

/**
 * Film rendering on top of the development pipeline: grain and halation when the frame is exposed
 * (client), push/pull and light leaks when the roll is developed (server, on the stored JPEG).
 * Pure Java, unit-tested.
 */
public final class FilmLook {
    private FilmLook() {}

    /**
     * Grain: luminance noise generated on a coarse grid (clump size in pixels) and interpolated, strongest
     * in the midtones, multiplicative on linear light.
     */
    public static void grain(float[] lin, int w, int h, double amount, double sizePx, long seed) {
        if (amount <= 0) return;
        double size = Math.max(0.6, sizePx);
        int gw = Math.max(2, (int) Math.ceil(w / size) + 2), gh = Math.max(2, (int) Math.ceil(h / size) + 2);
        float[] field = new float[gw * gh];
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < field.length; i++) field[i] = (float) rnd.nextGaussian();
        float a = (float) (amount * 0.32);
        for (int y = 0; y < h; y++) {
            double fy = y / size;
            int y0 = (int) fy;
            float ty = (float) (fy - y0);
            for (int x = 0; x < w; x++) {
                double fx = x / size;
                int x0 = (int) fx;
                float tx = (float) (fx - x0);
                float n00 = field[y0 * gw + x0], n10 = field[y0 * gw + x0 + 1];
                float n01 = field[(y0 + 1) * gw + x0], n11 = field[(y0 + 1) * gw + x0 + 1];
                float n = (n00 + (n10 - n00) * tx) * (1 - ty) + (n01 + (n11 - n01) * tx) * ty;
                int j = (y * w + x) * 3;
                float l = 0.2126f * lin[j] + 0.7152f * lin[j + 1] + 0.0722f * lin[j + 2];
                float v = (float) Math.pow(Math.min(1, Math.max(0, l)), 1 / 2.2);
                float mid = 0.35f + 2.6f * v * (1 - v);
                float k = Math.max(0, 1 + n * a * mid);
                lin[j] *= k;
                lin[j + 1] *= k;
                lin[j + 2] *= k;
            }
        }
    }

    /** Halation: a red-orange glow spreading from bright highlights (no anti-halation backing). */
    public static void halation(float[] lin, int w, int h, double strength) {
        if (strength <= 0) return;
        float[] bright = new float[lin.length];
        boolean any = false;
        for (int i = 0; i < lin.length; i += 3) {
            float l = 0.2126f * lin[i] + 0.7152f * lin[i + 1] + 0.0722f * lin[i + 2];
            float e = Math.max(0, l - 0.85f);
            if (e > 0) any = true;
            bright[i] = bright[i + 1] = bright[i + 2] = e;
        }
        if (!any) return;
        int r = Math.max(2, w / 120);
        float[] glow = ImagePipeline.boxBlur(ImagePipeline.boxBlur(bright, w, h, r), w, h, r);
        float k = (float) strength * 2.2f;
        for (int i = 0; i < lin.length; i += 3) {
            float g = glow[i] * k;
            lin[i] += g;
            lin[i + 1] += g * 0.28f;
            lin[i + 2] += g * 0.06f;
        }
    }

    /**
     * Push (+) or pull (-) processing applied to a developed frame: brighter and contrastier with more grain
     * when pushed, softer when pulled.
     */
    public static int[] push(int[] rgb, int w, int h, int stops, long seed) {
        if (stops == 0) return rgb;
        PhotoEditor.Source src = new PhotoEditor.Source(ImagePipeline.toLinear(rgb), w, h, false, 12,
                new ImageParams.Colour(1, 1, 1, 1, 1, false, 1, 1, 1, 0), 0, 0, 0);
        EditSettings e = new EditSettings();
        e.exposure = Math.max(-1.5, Math.min(1.5, stops * 0.8));
        e.contrast = stops * 14;
        e.blacks = stops > 0 ? -stops * 8 : 0;
        e.grain = stops > 0 ? stops * 18 : 0;
        return PhotoEditor.apply(src, e, null).rgb();
    }

    /**
     * Light that got onto the film: severity 1 burns the frame to an orange-white blank, lower values leave
     * warm streaks coming in from one edge (like opening the back of the camera).
     */
    public static int[] lightLeak(int[] rgb, int w, int h, double severity, long seed) {
        if (severity <= 0) return rgb;
        SplittableRandom rnd = new SplittableRandom(seed);
        boolean fromLeft = rnd.nextBoolean();
        double width = 0.25 + 0.5 * severity + rnd.nextDouble() * 0.15;
        double wobble = rnd.nextDouble() * Math.PI * 2;
        int[] out = new int[rgb.length];
        for (int y = 0; y < h; y++) {
            double fy = (double) y / h;
            double edge = width * (0.85 + 0.25 * Math.sin(fy * 5 + wobble));
            for (int x = 0; x < w; x++) {
                double fx = fromLeft ? (double) x / w : 1 - (double) x / w;
                double leak = Math.max(0, 1 - fx / Math.max(0.05, edge));
                leak = Math.min(1, leak * leak * (0.6 + severity) + Math.max(0, severity - 0.6) * 2.1);
                int p = rgb[y * w + x];
                double r = ((p >> 16) & 255) / 255.0, g = ((p >> 8) & 255) / 255.0, b = (p & 255) / 255.0;
                // orange-red light, saturating towards white at full strength
                r = r + (1.0 - r) * leak;
                g = g + (0.55 + 0.4 * leak * severity - g) * leak;
                b = b + (0.15 + 0.6 * leak * severity * severity - b) * leak;
                out[y * w + x] = 0xFF000000 | (q(r) << 16) | (q(g) << 8) | q(b);
            }
        }
        return out;
    }

    private static int q(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    /** Dynamic range the frame is rendered with: negative film tolerates overexposure, slide film does not. */
    public static double dynamicRange(double latitudeOver) {
        return 9 + latitudeOver * 1.8;
    }
}
