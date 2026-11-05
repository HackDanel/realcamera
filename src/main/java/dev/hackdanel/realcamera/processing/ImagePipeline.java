package dev.hackdanel.realcamera.processing;

import dev.hackdanel.realcamera.optics.Optics;
import java.util.SplittableRandom;

/**
 * Develops a captured frame into a photograph. Works on linear-light float RGB (interleaved).
 * Stages, in order: exposure gain, depth of field, lens (vignetting, softness), sensor noise,
 * highlight roll-off, white balance, colour profile, output sRGB.
 */
public final class ImagePipeline {
    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    private ImagePipeline() {}

    /** ARGB/ABGR-agnostic: takes 0xRRGGBB ints, returns linear floats [r,g,b,...]. */
    public static float[] toLinear(int[] rgb) {
        float[] out = new float[rgb.length * 3];
        for (int i = 0, j = 0; i < rgb.length; i++, j += 3) {
            int p = rgb[i];
            out[j] = SRGB_TO_LINEAR[(p >> 16) & 255];
            out[j + 1] = SRGB_TO_LINEAR[(p >> 8) & 255];
            out[j + 2] = SRGB_TO_LINEAR[p & 255];
        }
        return out;
    }

    public static int[] toSrgb(float[] lin) {
        int[] out = new int[lin.length / 3];
        for (int i = 0, j = 0; i < out.length; i++, j += 3) {
            out[i] = 0xFF000000 | (encode(lin[j]) << 16) | (encode(lin[j + 1]) << 8) | encode(lin[j + 2]);
        }
        return out;
    }

    private static int encode(float v) {
        double c = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(Math.max(v, 0), 1 / 2.4) - 0.055;
        return Math.max(0, Math.min(255, (int) Math.round(c * 255)));
    }

    /**
     * Full development.
     *
     * @param rgb   captured frame, 0xRRGGBB, row-major top-down
     * @param depth distance in metres per pixel (same size), or null to skip depth of field
     */
    public static int[] develop(int[] rgb, float[] depth, int w, int h, ImageParams p) {
        float[] img = toLinear(rgb);
        expose(img, p.exposureEv());
        if (depth != null) {
            img = depthOfField(img, depth, w, h, p);
        }
        lens(img, w, h, p);
        noise(img, w, h, p);
        rollOff(img, p.dynamicRangeEv());
        colour(img, p);
        return toSrgb(img);
    }

    static void expose(float[] img, double ev) {
        float g = (float) Math.pow(2, ev);
        for (int i = 0; i < img.length; i++) {
            img[i] *= g;
        }
    }

    // ------------------------------------------------------------------ depth of field

    /** Blur radius in pixels for every pixel, from the thin-lens blur circle. */
    static float[] blurRadius(float[] depth, int w, ImageParams p, float maxRadius) {
        float[] r = new float[depth.length];
        double pxPerMm = w / p.sensorWidthMm();
        for (int i = 0; i < depth.length; i++) {
            double c = Optics.blurCircleMm(p.focalMm(), p.fNumber(), p.focusM(), depth[i]);
            r[i] = (float) Math.min(maxRadius, c * pxPerMm * 0.5);
        }
        return r;
    }

    /**
     * Variable-radius blur using a pyramid: level k approximates a blur of radius 2^k. Each pixel
     * blends the two levels around its own blur radius. Pixels are weighted by their own blur so a
     * sharp subject does not bleed into the blurred background around it.
     */
    static float[] depthOfField(float[] img, float[] depth, int w, int h, ImageParams p) {
        float maxR = Math.max(4, w / 40f);
        float[] radius = blurRadius(depth, w, p, maxR);
        float largest = 0;
        for (float r : radius) largest = Math.max(largest, r);
        if (largest < 0.75f) {
            return img;
        }
        int levels = Math.max(1, (int) Math.ceil(Math.log(largest) / Math.log(2)) + 1);
        Level[] pyr = new Level[levels + 1];
        pyr[0] = null; // level 0 = original image
        for (int k = 1; k <= levels; k++) {
            pyr[k] = buildLevel(img, radius, w, h, k);
        }
        float[] out = new float[img.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float r = radius[i];
                int j = i * 3;
                if (r < 0.5f) {
                    out[j] = img[j];
                    out[j + 1] = img[j + 1];
                    out[j + 2] = img[j + 2];
                    continue;
                }
                double lv = Math.max(0, Math.log(r) / Math.log(2) + 1); // r=1 -> level 1
                int k0 = Math.min(levels, (int) Math.floor(lv));
                int k1 = Math.min(levels, k0 + 1);
                float t = (float) (lv - Math.floor(lv));
                float[] a = new float[3];
                float[] b = new float[3];
                sampleLevel(pyr[k0], img, w, h, x, y, a);
                sampleLevel(pyr[k1], img, w, h, x, y, b);
                out[j] = a[0] + (b[0] - a[0]) * t;
                out[j + 1] = a[1] + (b[1] - a[1]) * t;
                out[j + 2] = a[2] + (b[2] - a[2]) * t;
            }
        }
        return out;
    }

    private record Level(int scale, int lw, int lh, float[] rgb) {}

    private static Level buildLevel(float[] img, float[] radius, int w, int h, int k) {
        int scale = 1 << k;
        int lw = Math.max(1, (w + scale - 1) / scale);
        int lh = Math.max(1, (h + scale - 1) / scale);
        float[] acc = new float[lw * lh * 4];
        float levelR = scale;
        for (int y = 0; y < h; y++) {
            int ly = y / scale;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float r = radius[i];
                // smoothstep(0.25 R, R, r): pixels sharper than this level contribute little
                float t = clamp01((r - 0.25f * levelR) / (0.75f * levelR));
                float wgt = 0.02f + t * t * (3 - 2 * t);
                int li = (ly * lw + x / scale) * 4;
                int j = i * 3;
                acc[li] += img[j] * wgt;
                acc[li + 1] += img[j + 1] * wgt;
                acc[li + 2] += img[j + 2] * wgt;
                acc[li + 3] += wgt;
            }
        }
        tent(acc, lw, lh);
        tent(acc, lw, lh);
        float[] rgb = new float[lw * lh * 3];
        for (int i = 0; i < lw * lh; i++) {
            float wsum = Math.max(acc[i * 4 + 3], 1e-6f);
            rgb[i * 3] = acc[i * 4] / wsum;
            rgb[i * 3 + 1] = acc[i * 4 + 1] / wsum;
            rgb[i * 3 + 2] = acc[i * 4 + 2] / wsum;
        }
        return new Level(scale, lw, lh, rgb);
    }

    /** Separable [1 2 1]/4 blur on 4-channel data, in place. */
    private static void tent(float[] a, int w, int h) {
        float[] tmp = new float[a.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int l = Math.max(0, x - 1), r = Math.min(w - 1, x + 1);
                for (int c = 0; c < 4; c++) {
                    tmp[(y * w + x) * 4 + c] = (a[(y * w + l) * 4 + c] + 2 * a[(y * w + x) * 4 + c] + a[(y * w + r) * 4 + c]) * 0.25f;
                }
            }
        }
        for (int y = 0; y < h; y++) {
            int u = Math.max(0, y - 1), d = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 4; c++) {
                    a[(y * w + x) * 4 + c] = (tmp[(u * w + x) * 4 + c] + 2 * tmp[(y * w + x) * 4 + c] + tmp[(d * w + x) * 4 + c]) * 0.25f;
                }
            }
        }
    }

    private static void sampleLevel(Level lvl, float[] img, int w, int h, int x, int y, float[] out) {
        if (lvl == null) {
            int j = (y * w + x) * 3;
            out[0] = img[j];
            out[1] = img[j + 1];
            out[2] = img[j + 2];
            return;
        }
        float fx = (x + 0.5f) / lvl.scale() - 0.5f;
        float fy = (y + 0.5f) / lvl.scale() - 0.5f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int x1 = Math.min(lvl.lw() - 1, Math.max(0, x0 + 1)), y1 = Math.min(lvl.lh() - 1, Math.max(0, y0 + 1));
        x0 = Math.min(lvl.lw() - 1, Math.max(0, x0));
        y0 = Math.min(lvl.lh() - 1, Math.max(0, y0));
        float[] d = lvl.rgb();
        for (int c = 0; c < 3; c++) {
            float a = d[(y0 * lvl.lw() + x0) * 3 + c], b = d[(y0 * lvl.lw() + x1) * 3 + c];
            float e = d[(y1 * lvl.lw() + x0) * 3 + c], f = d[(y1 * lvl.lw() + x1) * 3 + c];
            out[c] = (a + (b - a) * tx) + ((e + (f - e) * tx) - (a + (b - a) * tx)) * ty;
        }
    }

    // ------------------------------------------------------------------ lens

    static void lens(float[] img, int w, int h, ImageParams p) {
        // Vignetting: strongest wide open, mostly gone two stops down.
        double stopsDown = Math.max(0, Math.log(p.fNumber() / p.maxAperture()) / Math.log(2));
        double vig = p.vignettingEv() * Math.max(0, 1 - stopsDown * 0.45);
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, norm = cx * cx + cy * cy;
        if (vig > 0.01) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double r2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / norm;
                    float m = (float) Math.pow(2, -vig * r2 * r2 * 0.5 - vig * r2 * 0.5);
                    int j = (y * w + x) * 3;
                    img[j] *= m;
                    img[j + 1] *= m;
                    img[j + 2] *= m;
                }
            }
        }
        // Softness / crispness: unsharp mask with a 3x3 box (negative amount softens).
        double amount = (p.lensSharpness() - 0.95) * 1.5;
        if (Math.abs(amount) > 0.02) {
            float[] blur = box3(img, w, h);
            float a = (float) amount;
            for (int i = 0; i < img.length; i++) {
                img[i] = Math.max(0, img[i] + (img[i] - blur[i]) * a);
            }
        }
    }

    private static float[] box3(float[] img, int w, int h) {
        float[] out = new float[img.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 3; c++) {
                    float s = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= h) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= w) continue;
                            s += img[(yy * w + xx) * 3 + c];
                            n++;
                        }
                    }
                    out[(y * w + x) * 3 + c] = s / n;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ noise

    /**
     * Photon (shot) noise grows with sqrt(signal x amplification), read noise with amplification.
     * Downsampling a high-resolution sensor averages noise, which is why resolutionScale matters.
     */
    static void noise(float[] img, int w, int h, ImageParams p) {
        double amp = Math.max(1, p.isoGain()) * Math.max(1, Math.pow(2, p.exposureEv()));
        double avg = Math.sqrt(Math.min(1, p.resolutionScale()));
        double shotK = 6e-5 * p.noiseLuma();
        double readK = 1.2e-4 * p.noiseLuma();
        double chromaK = 0.6 * p.noiseChroma();
        if (p.noiseLuma() <= 0 && p.noiseChroma() <= 0) {
            return;
        }
        SplittableRandom rnd = new SplittableRandom(p.seed());
        for (int i = 0; i < img.length; i += 3) {
            float luma = 0.2126f * img[i] + 0.7152f * img[i + 1] + 0.0722f * img[i + 2];
            double sigma = (Math.sqrt(Math.max(luma, 0) * amp * shotK) + amp * readK) * avg;
            double common = rnd.nextGaussian() * sigma;
            img[i] = (float) Math.max(0, img[i] + common + rnd.nextGaussian() * sigma * chromaK);
            img[i + 1] = (float) Math.max(0, img[i + 1] + common + rnd.nextGaussian() * sigma * chromaK);
            img[i + 2] = (float) Math.max(0, img[i + 2] + common + rnd.nextGaussian() * sigma * chromaK);
        }
    }

    // ------------------------------------------------------------------ tone & colour

    /** Highlights compress smoothly towards white instead of clipping hard; more DR = longer shoulder. */
    static void rollOff(float[] img, double dynamicRangeEv) {
        float knee = (float) Math.max(0.6, Math.min(0.9, 1.25 - dynamicRangeEv * 0.035));
        float span = 1 - knee;
        for (int i = 0; i < img.length; i++) {
            float v = img[i];
            if (v > knee) {
                img[i] = knee + span * (1 - (float) Math.exp(-(v - knee) / span));
            }
        }
    }

    static void colour(float[] img, ImageParams p) {
        float wr = (float) (p.wbR() * p.tintR()), wg = (float) (p.wbG() * p.tintG()), wb = (float) (p.wbB() * p.tintB());
        float sat = (float) p.saturation();
        float lift = (float) p.shadowLift();
        double contrast = p.contrast();
        for (int i = 0; i < img.length; i += 3) {
            float r = img[i] * wr, g = img[i + 1] * wg, b = img[i + 2] * wb;
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            if (p.monochrome()) {
                r = g = b = luma;
            } else {
                r = luma + (r - luma) * sat;
                g = luma + (g - luma) * sat;
                b = luma + (b - luma) * sat;
            }
            img[i] = curve(r, contrast, lift);
            img[i + 1] = curve(g, contrast, lift);
            img[i + 2] = curve(b, contrast, lift);
        }
    }

    /** Contrast pivoting around middle grey (0.18) in log space, plus a shadow lift. */
    private static float curve(float v, double contrast, float lift) {
        v = Math.max(v, 0);
        if (contrast != 1) {
            double l = Math.log(Math.max(v, 1e-5) / 0.18);
            v = (float) Math.min(1, 0.18 * Math.exp(l * contrast));
        }
        return Math.min(1, v + lift * (1 - v) * (1 - v));
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    // ------------------------------------------------------------------ helpers

    /** Area-average downscale of 0xRRGGBB pixels (in linear light) to fit within maxSide. */
    public static int[] downscale(int[] rgb, int w, int h, int maxSide, int[] outSize) {
        double s = Math.min(1.0, (double) maxSide / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
        outSize[0] = nw;
        outSize[1] = nh;
        if (nw == w && nh == h) {
            return rgb.clone();
        }
        float[] acc = new float[nw * nh * 4];
        for (int y = 0; y < h; y++) {
            int ny = Math.min(nh - 1, y * nh / h);
            for (int x = 0; x < w; x++) {
                int nx = Math.min(nw - 1, x * nw / w);
                int p = rgb[y * w + x];
                int k = (ny * nw + nx) * 4;
                acc[k] += SRGB_TO_LINEAR[(p >> 16) & 255];
                acc[k + 1] += SRGB_TO_LINEAR[(p >> 8) & 255];
                acc[k + 2] += SRGB_TO_LINEAR[p & 255];
                acc[k + 3] += 1;
            }
        }
        int[] out = new int[nw * nh];
        for (int i = 0; i < out.length; i++) {
            float n = Math.max(1, acc[i * 4 + 3]);
            out[i] = 0xFF000000 | (encode(acc[i * 4] / n) << 16) | (encode(acc[i * 4 + 1] / n) << 8) | encode(acc[i * 4 + 2] / n);
        }
        return out;
    }

    /** Approximate RGB of a black body (normalised so G = 1), for white balance. */
    public static double[] kelvinRgb(double kelvin) {
        double t = kelvin / 100.0;
        double r, g, b;
        if (t <= 66) {
            r = 255;
            g = 99.4708025861 * Math.log(t) - 161.1195681661;
            b = t <= 19 ? 0 : 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        } else {
            r = 329.698727446 * Math.pow(t - 60, -0.1332047592);
            g = 288.1221695283 * Math.pow(t - 60, -0.0755148492);
            b = 255;
        }
        r = Math.max(1, Math.min(255, r));
        g = Math.max(1, Math.min(255, g));
        b = Math.max(1, Math.min(255, b));
        return new double[]{r / g, 1, b / g};
    }

    /**
     * White balance multipliers for a scene lit at sceneK when the camera is set to settingK:
     * setting a lower temperature than the light makes the picture bluer, and vice versa.
     */
    public static double[] whiteBalance(double sceneK, double settingK) {
        double[] scene = kelvinRgb(sceneK);
        double[] set = kelvinRgb(settingK);
        return new double[]{scene[0] / set[0], 1, scene[2] / set[2]};
    }
}
