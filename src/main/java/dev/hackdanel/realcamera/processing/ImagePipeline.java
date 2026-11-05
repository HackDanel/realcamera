package dev.hackdanel.realcamera.processing;

import dev.hackdanel.realcamera.optics.Optics;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Develops a captured frame into a photograph. Works on linear-light float RGB (interleaved).
 * Order follows the light: exposure, depth of field (with bokeh highlights), motion blur, rolling
 * shutter, lens geometry (distortion, chromatic aberration, image circle), vignetting/sharpness,
 * flare, sensor noise, highlight roll-off, colour (white balance + profile), sRGB output.
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

    /** Takes 0xRRGGBB ints, returns linear floats [r,g,b,...]. */
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

    static int encode(float v) {
        if (!(v > 0)) {
            return 0; // also maps NaN to black instead of propagating
        }
        double c = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return Math.max(0, Math.min(255, (int) Math.round(c * 255)));
    }

    /**
     * Full development.
     *
     * @param rgb   captured frame, 0xRRGGBB, row-major top-down
     * @param depth distance in metres per pixel (same size), or null to skip depth of field
     */
    public static int[] develop(int[] rgb, float[] depth, int w, int h, ImageParams p) {
        return toSrgb(developLinear(toLinear(rgb), depth, null, w, h, p));
    }

    public static int[] develop(int[] rgb, float[] depth, float[] light, int w, int h, ImageParams p) {
        return toSrgb(developLinear(toLinear(rgb), depth, light, w, h, p));
    }

    /**
     * Same as {@link #develop} but starting from linear light (used for long-exposure averages).
     *
     * @param light per-pixel brightness the game lit each surface with (0..1), or null; used to recover
     *              surface colour for the flash
     */
    public static float[] developLinear(float[] img, float[] depth, float[] light, int w, int h, ImageParams p) {
        return finish(developSensor(img, depth, light, w, h, p), p);
    }

    /**
     * Everything that happens before the image processor: light through filters and lens, exposure,
     * motion, optics, wear, flare and sensor noise. The result is what a RAW file holds (linear, may
     * exceed 1 up to {@link #rawSaturation}).
     */
    public static float[] developSensor(float[] img, float[] depth, float[] light, int w, int h, ImageParams p) {
        ImageParams.Extras x = p.extras();
        filterTransmission(img, x);
        float[] beforeExposure = x.flashAt1m() > 0 && depth != null ? img.clone() : null;
        expose(img, p.sensor().exposureEv());
        if (beforeExposure != null) {
            flash(img, beforeExposure, depth, light, w, h, x);
        }
        if (x.cpl() && depth != null) {
            polariser(img, depth);
        }
        if (depth != null) {
            img = depthOfField(img, depth, w, h, p);
        }
        img = motionBlur(img, w, h, p.motion().blurX(), p.motion().blurY());
        img = rollingShutter(img, w, h, p.motion().rollingSkewPx());
        img = lensShift(img, w, h, p);
        img = p.lens().projection() != 0 ? fisheye(img, w, h, p) : lensGeometry(img, w, h, p.lens());
        lens(img, w, h, p);
        img = wear(img, w, h, p);
        flare(img, w, h, p);
        noise(img, w, h, p);
        float sat = (float) rawSaturation(p.sensor().dynamicRangeEv());
        for (int i = 0; i < img.length; i++) {
            if (img[i] > sat) img[i] = sat;
        }
        return img;
    }

    /** The in-camera image processor: highlight roll-off, white balance, colour profile. */
    public static float[] finish(float[] img, ImageParams p) {
        rollOff(img, p.sensor().dynamicRangeEv());
        colour(img, p.colour());
        return img;
    }

    /**
     * Sensor saturation relative to JPEG white: the RAW highlight headroom (about 1 EV for a small
     * sensor, 3 EV for a modern full frame with 14+ stops).
     */
    public static double rawSaturation(double dynamicRangeEv) {
        return Math.max(2, Math.min(8, Math.pow(2, (dynamicRangeEv - 10) * 0.75)));
    }

    // ------------------------------------------------------------------ accessories

    /**
     * Colour filter: per-channel transmission normalised to its luminance, because the camera meters
     * through the filter (so a red filter makes blue darker rather than the whole picture).
     */
    static void filterTransmission(float[] img, ImageParams.Extras x) {
        double r = x.filterR(), g = x.filterG(), b = x.filterB();
        if (r == 1 && g == 1 && b == 1) return;
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        float fr = (float) (r / lum), fg = (float) (g / lum), fb = (float) (b / lum);
        for (int i = 0; i < img.length; i += 3) {
            img[i] *= fr;
            img[i + 1] *= fg;
            img[i + 2] *= fb;
        }
    }

    /**
     * Flash light: E(d) = flashAt1m / d² relative to a correct exposure, applied to the surface colour
     * (pixel brightness divided by the light the game lit it with). Rows below the curtain edge (when
     * shooting faster than the sync speed) get no flash.
     */
    static void flash(float[] img, float[] raw, float[] depth, float[] light, int w, int h, ImageParams.Extras x) {
        int lastRow = (int) Math.round(h * Math.max(0, Math.min(1, x.flashTop())));
        for (int y = 0; y < lastRow; y++) {
            for (int px = 0; px < w; px++) {
                int i = y * w + px;
                double d = Math.max(0.3, depth[i]);
                if (d > 200) continue; // sky and far away: the flash does not reach
                double e = x.flashAt1m() / (d * d);
                float lit = light == null ? 0.5f : Math.max(0.04f, light[i]);
                int j = i * 3;
                for (int c = 0; c < 3; c++) {
                    float albedo = Math.min(1.2f, raw[j + c] / lit);
                    img[j + c] += (float) (albedo * e);
                }
            }
        }
    }

    /** Polariser: deeper, more saturated sky; slightly richer colours elsewhere (less glare). */
    static void polariser(float[] img, float[] depth) {
        for (int i = 0, j = 0; i < depth.length; i++, j += 3) {
            boolean sky = depth[i] > 5000;
            float l = 0.2126f * img[j] + 0.7152f * img[j + 1] + 0.0722f * img[j + 2];
            float sat = sky ? 1.35f : 1.08f, dim = sky ? 0.6f : 0.97f;
            for (int c = 0; c < 3; c++) {
                img[j + c] = Math.max(0, (l + (img[j + c] - l) * sat) * dim);
            }
        }
    }

    /** Moisture fog, a knocked (decentred) lens and sensor dust. */
    static float[] wear(float[] img, int w, int h, ImageParams p) {
        ImageParams.Extras x = p.extras();
        if (x.fog() > 0.01 || x.decenter() > 0.01) {
            float[] blur = boxBlur(img, w, h, Math.max(2, w / 120));
            float fog = (float) x.fog();
            float dec = (float) x.decenter();
            for (int y = 0; y < h; y++) {
                for (int px = 0; px < w; px++) {
                    float side = (float) px / w; // the right side suffers
                    float k = Math.min(1, fog * 0.85f + dec * side * side * 1.4f);
                    int j = (y * w + px) * 3;
                    for (int c = 0; c < 3; c++) {
                        img[j + c] = img[j + c] + (blur[j + c] - img[j + c]) * k + fog * 0.06f;
                    }
                }
            }
        }
        float[] dust = x.dust();
        if (dust.length >= 3) {
            // dust shadows sharpen and darken as the aperture closes (f/8 and beyond)
            float opacity = (float) Math.max(0, Math.min(0.55, (p.focus().fNumber() - 4.5) / 14));
            if (opacity > 0.01) {
                for (int k = 0; k + 2 < dust.length; k += 3) {
                    double cx = (dust[k] + 1) / 2 * w, cy = (1 - dust[k + 1]) / 2 * h, r = dust[k + 2] * w;
                    int x0 = (int) Math.max(0, cx - 3 * r), x1 = (int) Math.min(w - 1, cx + 3 * r);
                    int y0 = (int) Math.max(0, cy - 3 * r), y1 = (int) Math.min(h - 1, cy + 3 * r);
                    for (int y = y0; y <= y1; y++) {
                        for (int px = x0; px <= x1; px++) {
                            double d2 = ((px - cx) * (px - cx) + (y - cy) * (y - cy)) / (r * r);
                            float m = 1 - opacity * (float) Math.exp(-d2);
                            int j = (y * w + px) * 3;
                            img[j] *= m;
                            img[j + 1] *= m;
                            img[j + 2] *= m;
                        }
                    }
                }
            }
        }
        return img;
    }

    /** Separable box blur with running sums (radius r). */
    static float[] boxBlur(float[] img, int w, int h, int r) {
        float[] tmp = new float[img.length], out = new float[img.length];
        float inv = 1f / (2 * r + 1);
        for (int y = 0; y < h; y++) {
            for (int c = 0; c < 3; c++) {
                float sum = 0;
                for (int k = -r; k <= r; k++) sum += img[(y * w + Math.max(0, Math.min(w - 1, k))) * 3 + c];
                for (int x = 0; x < w; x++) {
                    tmp[(y * w + x) * 3 + c] = sum * inv;
                    int add = Math.min(w - 1, x + r + 1), rem = Math.max(0, x - r);
                    sum += img[(y * w + add) * 3 + c] - img[(y * w + rem) * 3 + c];
                }
            }
        }
        for (int x = 0; x < w; x++) {
            for (int c = 0; c < 3; c++) {
                float sum = 0;
                for (int k = -r; k <= r; k++) sum += tmp[(Math.max(0, Math.min(h - 1, k)) * w + x) * 3 + c];
                for (int y = 0; y < h; y++) {
                    out[(y * w + x) * 3 + c] = sum * inv;
                    int add = Math.min(h - 1, y + r + 1), rem = Math.max(0, y - r);
                    sum += tmp[(add * w + x) * 3 + c] - tmp[(rem * w + x) * 3 + c];
                }
            }
        }
        return out;
    }

    static void expose(float[] img, double ev) {
        float g = (float) Math.pow(2, ev);
        for (int i = 0; i < img.length; i++) {
            img[i] *= g;
        }
    }

    // ------------------------------------------------------------------ depth of field

    /**
     * Blur radius in pixels for every pixel, from the thin-lens blur circle. A tilted lens focuses each row
     * at a different distance (the plane of focus is no longer parallel to the sensor).
     */
    static float[] blurRadius(float[] depth, int w, ImageParams.Focus f, float maxRadius) {
        float[] r = new float[depth.length];
        int h = depth.length / w;
        double pxPerMm = w / f.sensorWidthMm();
        for (int y = 0; y < h; y++) {
            double focus = f.focusM();
            if (Math.abs(f.tiltDeg()) > 1e-3) {
                double rowMm = (h / 2.0 - y - 0.5) / pxPerMm;
                focus = Math.min(1e6, Optics.tiltedFocusM(f.focalMm(), f.focusM(), f.tiltDeg(), rowMm));
            }
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double c = Optics.blurCircleMm(f.focalMm(), f.fNumber(), focus, depth[i]);
                r[i] = (float) Math.min(maxRadius, c * pxPerMm * 0.5);
            }
        }
        return r;
    }

    /**
     * Variable-radius blur using a pyramid: level k approximates a blur of radius 2^k. Each pixel
     * blends the two levels around its own blur radius. Pixels are weighted by their own blur so a
     * sharp subject does not bleed into the blurred background around it. Bright out-of-focus points
     * are then drawn as discs in the shape of the aperture (bokeh highlights).
     */
    static float[] depthOfField(float[] img, float[] depth, int w, int h, ImageParams p) {
        float maxR = Math.max(4, w / 40f);
        float[] radius = blurRadius(depth, w, p.focus(), maxR);
        float largest = 0;
        for (float r : radius) largest = Math.max(largest, r);
        if (largest < 0.75f) {
            return img;
        }
        List<Highlight> highlights = findHighlights(img, radius, w, h);
        // small bright sources (lamps, glints) often get no reliable depth of their own: blur their core like
        // their surroundings, otherwise a sharp dot stays in the middle of the bokeh disc
        for (Highlight hl : highlights) {
            int cx = (int) hl.x, cy = (int) hl.y, k = 3;
            for (int y = Math.max(0, cy - k); y <= Math.min(h - 1, cy + k); y++) {
                for (int x = Math.max(0, cx - k); x <= Math.min(w - 1, cx + k); x++) {
                    radius[y * w + x] = Math.max(radius[y * w + x], hl.r);
                }
            }
        }
        int levels = Math.max(1, (int) Math.ceil(Math.log(largest) / Math.log(2)) + 1);
        Level[] pyr = new Level[levels + 1];
        for (int k = 1; k <= levels; k++) {
            pyr[k] = buildLevel(img, radius, w, h, k);
        }
        float[] out = new float[img.length];
        float[] a = new float[3];
        float[] b = new float[3];
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
                sampleLevel(pyr[k0], img, w, h, x, y, a);
                sampleLevel(pyr[k1], img, w, h, x, y, b);
                out[j] = a[0] + (b[0] - a[0]) * t;
                out[j + 1] = a[1] + (b[1] - a[1]) * t;
                out[j + 2] = a[2] + (b[2] - a[2]) * t;
            }
        }
        if (p.lens().bokeh() == ImageParams.BOKEH_SWIRL) {
            out = swirl(out, radius, w, h);
        }
        drawBokeh(out, w, h, highlights, p.lens());
        return out;
    }

    /**
     * Helios-style swirl: the out-of-focus background is smeared along circles around the frame centre,
     * more towards the edges and the more it is blurred.
     */
    static float[] swirl(float[] img, float[] radius, int w, int h) {
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, halfDiag = Math.hypot(cx, cy);
        float[] out = img.clone();
        int taps = 9;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double ox = x - cx, oy = y - cy;
                double dist = Math.hypot(ox, oy) / halfDiag;
                double len = radius[i] * 4.0 * Math.pow(dist, 1.5);
                if (len < 1) continue;
                double tx = -oy / Math.max(1e-6, Math.hypot(ox, oy)), ty = ox / Math.max(1e-6, Math.hypot(ox, oy));
                int j = i * 3;
                out[j] = out[j + 1] = out[j + 2] = 0;
                float wsum = 0;
                for (int t = 0; t < taps; t++) {
                    double f = (double) t / (taps - 1) - 0.5;
                    float sx = (float) (x + tx * len * f), sy = (float) (y + ty * len * f);
                    if (sx < 0 || sy < 0 || sx > w - 1.001f || sy > h - 1.001f) continue;
                    int si = (int) sy * w + (int) sx;
                    // keep sharp pixels out of the smear (the subject does not swirl)
                    float wt = Math.min(1f, radius[si] / Math.max(1f, radius[i]));
                    bilinearAdd(img, w, h, sx, sy, out, j, wt);
                    wsum += wt;
                }
                if (wsum > 0) {
                    out[j] /= wsum;
                    out[j + 1] /= wsum;
                    out[j + 2] /= wsum;
                } else {
                    out[j] = img[j];
                    out[j + 1] = img[j + 1];
                    out[j + 2] = img[j + 2];
                }
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

    // ------------------------------------------------------------------ bokeh highlights

    record Highlight(float x, float y, float r, float cr, float cg, float cb) {}

    /**
     * Bright, out-of-focus points (lamps, sun glints, sky through leaves): the brightest pixel of
     * each 4x4 block that clearly stands out from its surroundings and would be blurred by > 3 px.
     */
    static List<Highlight> findHighlights(float[] img, float[] radius, int w, int h) {
        List<Highlight> found = new ArrayList<>();
        int block = 4;
        for (int by = block; by < h - block; by += block) {
            for (int bx = block; bx < w - block; bx += block) {
                int best = -1;
                float bestL = 0;
                for (int y = by; y < by + block; y++) {
                    for (int x = bx; x < bx + block; x++) {
                        int j = (y * w + x) * 3;
                        float l = 0.2126f * img[j] + 0.7152f * img[j + 1] + 0.0722f * img[j + 2];
                        if (l > bestL) {
                            bestL = l;
                            best = y * w + x;
                        }
                    }
                }
                if (best < 0 || bestL < 0.75f) continue;
                int x = best % w, y = best / w;
                float r = neighbourhoodRadius(radius, w, h, x, y, 2 * block);
                if (r < 3f) continue;
                // must be a local peak: clearly brighter than pixels 2 blocks away
                float ring = 0;
                int n = 0;
                for (int d = -1; d <= 1; d += 2) {
                    ring += lum(img, w, Math.max(0, Math.min(w - 1, x + d * 2 * block)), y);
                    ring += lum(img, w, x, Math.max(0, Math.min(h - 1, y + d * 2 * block)));
                    n += 2;
                }
                if (bestL < 1.6f * ring / n) continue;
                int j = best * 3;
                found.add(new Highlight(x, y, r, img[j], img[j + 1], img[j + 2]));
            }
        }
        found.sort((a, b) -> Float.compare(b.cr + b.cg + b.cb, a.cr + a.cg + a.cb));
        return found.size() > 3000 ? found.subList(0, 3000) : found;
    }

    /** Largest blur radius around a point: the depth of a tiny light is that of what surrounds it. */
    private static float neighbourhoodRadius(float[] radius, int w, int h, int x, int y, int k) {
        float r = radius[y * w + x];
        for (int dy = -k; dy <= k; dy += 2) {
            int yy = y + dy;
            if (yy < 0 || yy >= h) continue;
            for (int dx = -k; dx <= k; dx += 2) {
                int xx = x + dx;
                if (xx < 0 || xx >= w) continue;
                r = Math.max(r, radius[yy * w + xx]);
            }
        }
        return r;
    }

    private static float lum(float[] img, int w, int x, int y) {
        int j = (y * w + x) * 3;
        return 0.2126f * img[j] + 0.7152f * img[j + 1] + 0.0722f * img[j + 2];
    }

    /**
     * Each highlight becomes a disc shaped like the aperture: a regular polygon (blade count) or a
     * circle for rounded blades, clipped towards the frame edge into a "cat's eye" by the lens barrel.
     */
    static void drawBokeh(float[] img, int w, int h, List<Highlight> highlights, ImageParams.Lens lens) {
        double cx = w / 2.0, cy = h / 2.0, halfDiag = Math.hypot(cx, cy);
        int blades = Math.max(5, lens.blades());
        int style = lens.bokeh();
        for (Highlight hl : highlights) {
            float r = hl.r;
            // energy of the source spread over the disc, with a slightly brighter rim
            float area = (float) (Math.PI * r * r);
            float k = 16f / area * 0.7f;
            double ox = hl.x - cx, oy = hl.y - cy;
            double dist = Math.hypot(ox, oy) / halfDiag;
            double clipShift = r * 1.2 * dist; // second circle shifted towards the centre
            double ux = dist > 1e-6 ? -ox / Math.hypot(ox, oy) : 0, uy = dist > 1e-6 ? -oy / Math.hypot(ox, oy) : 0;
            // swirl: discs squeezed along the radius into ovals that line up on circles around the centre
            double squeeze = style == ImageParams.BOKEH_SWIRL ? 1 - 0.7 * Math.min(1, dist * 1.2) : 1;
            int x0 = (int) Math.floor(hl.x - r), x1 = (int) Math.ceil(hl.x + r);
            int y0 = (int) Math.floor(hl.y - r), y1 = (int) Math.ceil(hl.y + r);
            for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) {
                for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
                    double dx = x - hl.x, dy = y - hl.y;
                    double rr = Math.hypot(dx, dy);
                    double edge = lens.rounded() ? r : polygonRadius(Math.atan2(dy, dx), blades, r);
                    if (squeeze < 1) {
                        double rad = -(dx * ux + dy * uy), tan = dx * uy - dy * ux;
                        double q = Math.hypot(rad / squeeze, tan);
                        if (q > r) continue;
                        rr = q;
                        edge = r;
                    }
                    if (rr > edge) continue;
                    // cat's eye: also inside a circle of radius r shifted towards the image centre
                    double ex = dx - ux * clipShift, ey = dy - uy * clipShift;
                    if (Math.hypot(ex, ey) > r * 1.05) continue;
                    double e = rr / Math.max(1, edge);
                    float rim = switch (style) {
                        // soap bubble: a bright thin outline with a dim centre
                        case ImageParams.BOKEH_BUBBLE -> (float) (0.3 + 2.6 * Math.pow(e, 7));
                        // onion rings from aspherical elements
                        case ImageParams.BOKEH_BUSY -> (float) ((0.8 + 0.4 * e) * (1 + 0.3 * Math.sin(e * 22)));
                        default -> (float) (0.8 + 0.4 * e);
                    };
                    int j = (y * w + x) * 3;
                    img[j] += hl.cr * k * rim;
                    img[j + 1] += hl.cg * k * rim;
                    img[j + 2] += hl.cb * k * rim;
                }
            }
        }
    }

    /** Distance from the centre to the edge of a regular n-gon of circumradius r in direction theta. */
    static double polygonRadius(double theta, int n, double r) {
        double sector = 2 * Math.PI / n;
        double t = theta - Math.floor(theta / sector) * sector - sector / 2;
        return r * Math.cos(Math.PI / n) / Math.cos(t);
    }

    // ------------------------------------------------------------------ motion

    /** Linear smear along (dx, dy) pixels, sampled with up to 32 taps (bilinear). */
    static float[] motionBlur(float[] img, int w, int h, double dx, double dy) {
        double len = Math.hypot(dx, dy);
        if (len < 0.7) {
            return img;
        }
        int taps = (int) Math.min(32, Math.ceil(len) + 1);
        float[] out = new float[img.length];
        float inv = 1f / taps;
        for (int t = 0; t < taps; t++) {
            double f = taps == 1 ? 0 : (double) t / (taps - 1) - 0.5;
            float sx = (float) (dx * f), sy = (float) (dy * f);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int j = (y * w + x) * 3;
                    bilinearAdd(img, w, h, x + sx, y + sy, out, j, inv);
                }
            }
        }
        return out;
    }

    private static void bilinearAdd(float[] src, int w, int h, float fx, float fy, float[] dst, int j, float weight) {
        fx = Math.max(0, Math.min(w - 1.001f, fx));
        fy = Math.max(0, Math.min(h - 1.001f, fy));
        int x0 = (int) fx, y0 = (int) fy;
        float tx = fx - x0, ty = fy - y0;
        int a = (y0 * w + x0) * 3, b = a + 3, c = a + w * 3, d = c + 3;
        for (int k = 0; k < 3; k++) {
            float top = src[a + k] + (src[b + k] - src[a + k]) * tx;
            float bot = src[c + k] + (src[d + k] - src[c + k]) * tx;
            dst[j + k] += (top + (bot - top) * ty) * weight;
        }
    }

    /** Electronic shutter: rows are read top to bottom, so a pan shears the picture. */
    static float[] rollingShutter(float[] img, int w, int h, double skewPx) {
        if (Math.abs(skewPx) < 0.5) {
            return img;
        }
        float[] out = new float[img.length];
        for (int y = 0; y < h; y++) {
            float shift = (float) (skewPx * ((double) y / h - 0.5));
            for (int x = 0; x < w; x++) {
                bilinearAdd(img, w, h, x + shift, y, out, (y * w + x) * 3, 1f);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ lens

    /**
     * Radial remap: distortion for all channels plus lateral chromatic aberration (red and blue
     * magnified slightly differently), then the image circle fall-off.
     */
    static float[] lensGeometry(float[] img, int w, int h, ImageParams.Lens lens) {
        double k1 = lens.distortion();
        double ca = lens.caPx();
        boolean circle = lens.imageCircle() < 0.999;
        if (Math.abs(k1) < 1e-4 && Math.abs(ca) < 0.3 && !circle) {
            return img;
        }
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, halfDiag = Math.hypot(cx, cy);
        double caScale = ca / halfDiag; // relative magnification difference at the corner
        float[] out = new float[img.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double nx = (x - cx) / halfDiag, ny = (y - cy) / halfDiag;
                double r2 = nx * nx + ny * ny;
                double m = distortionScale(r2, k1);
                int j = (y * w + x) * 3;
                double[] scale = {m * (1 + caScale), m, m * (1 - caScale)};
                for (int c = 0; c < 3; c++) {
                    float sx = (float) (cx + nx * scale[c] * halfDiag), sy = (float) (cy + ny * scale[c] * halfDiag);
                    out[j + c] = sampleChannel(img, w, h, sx, sy, c);
                }
                if (circle) {
                    double r = Math.sqrt(r2);
                    double edge = lens.imageCircle();
                    float fall = (float) clamp01((float) ((edge - r) / 0.06 + 0.5));
                    out[j] *= fall;
                    out[j + 1] *= fall;
                    out[j + 2] *= fall;
                }
            }
        }
        return out;
    }

    /**
     * Shift (rise/fall) of a tilt-shift lens. The frame was rendered with the camera pitched up by
     * beta = atan(shift / f) (and possibly wider); re-projecting it onto the level sensor with the lens axis
     * moved gives the shifted picture: verticals stay parallel while the frame shows more above.
     */
    static float[] lensShift(float[] img, int w, int h, ImageParams p) {
        ImageParams.Lens l = p.lens();
        if (Math.abs(l.shiftPx()) < 0.5 && Math.abs(l.renderScale() - 1) < 1e-4) {
            return img;
        }
        double f = p.focus().focalMm() * w / p.focus().sensorWidthMm(); // output focal length, px
        double fr = f * l.renderScale();
        double s = l.shiftPx();
        double beta = Math.atan2(s, f), sb = Math.sin(beta), cb = Math.cos(beta);
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0;
        float[] out = new float[img.length];
        for (int y = 0; y < h; y++) {
            double v = (y - cy) - s;
            double zr = -v * sb + f * cb, yr = v * cb + f * sb;
            for (int x = 0; x < w; x++) {
                double u = x - cx;
                float sx = (float) (cx + fr * u / zr), sy = (float) (cy + fr * yr / zr);
                int j = (y * w + x) * 3;
                for (int c = 0; c < 3; c++) {
                    out[j + c] = sampleChannel(img, w, h, sx, sy, c);
                }
            }
        }
        return out;
    }

    /**
     * Fisheye: every output pixel looks along the off-axis angle the lens's projection assigns to its
     * distance from the centre, and samples the rectilinear render there. Angles the render does not reach
     * (it is capped at {@link Optics#MAX_RENDER_HALF_ANGLE}) are compressed into it; pixels outside the
     * image circle of a circular fisheye stay black. Lateral CA works as for other lenses.
     */
    static float[] fisheye(float[] img, int w, int h, ImageParams p) {
        ImageParams.Lens l = p.lens();
        double focal = p.focus().focalMm(), pxPerMm = w / p.focus().sensorWidthMm();
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, halfDiag = Math.hypot(cx, cy);
        double cover = Optics.fisheyeCornerAngle(l.projection(), focal, halfDiag / pxPerMm);
        double render = l.renderCorner() > 0 ? l.renderCorner() : Math.min(cover, Optics.MAX_RENDER_HALF_ANGLE);
        double squeeze = cover > render ? render / cover : 1;
        double tanRender = Math.tan(render);
        double caScale = l.caPx() / halfDiag;
        float[] out = new float[img.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double nx = x - cx, ny = y - cy, rPx = Math.hypot(nx, ny);
                int j = (y * w + x) * 3;
                double theta = Optics.fisheyeAngle(l.projection(), focal, rPx / pxPerMm);
                if (Double.isNaN(theta) || theta > Math.PI / 2 + 0.01) {
                    continue; // outside the image circle
                }
                // soft edge of a circular image
                double edgePx = Optics.fisheyeRadius(l.projection(), focal, Math.PI / 2) * pxPerMm;
                float fall = (float) clamp01((float) ((edgePx - rPx) / Math.max(1.5, edgePx * 0.012)));
                double phi = Math.min(theta * squeeze, Math.PI / 2 - 1e-3);
                double srcR = Math.tan(phi) / tanRender * halfDiag;
                double k = rPx > 1e-6 ? srcR / rPx : (squeeze * pxPerMm * focal > 0 ? halfDiag / (tanRender * focal * pxPerMm) * squeeze : 1);
                double[] scale = {k * (1 + caScale), k, k * (1 - caScale)};
                for (int c = 0; c < 3; c++) {
                    float sx = (float) (cx + nx * scale[c]), sy = (float) (cy + ny * scale[c]);
                    out[j + c] = sampleChannel(img, w, h, sx, sy, c) * fall;
                }
            }
        }
        return out;
    }

    /**
     * Where an output pixel at squared radius r2 (1 = corner) samples the rendered frame, as a factor
     * of its radius. Barrel (k1 < 0) shows more towards the edges; the scale is normalised so the
     * corner still samples the corner, i.e. the rendered field of view is never exceeded.
     */
    static double distortionScale(double r2, double k1) {
        return (1 - k1 * r2) / Math.max(1, 1 - k1);
    }

    private static float sampleChannel(float[] src, int w, int h, float fx, float fy, int c) {
        if (fx < 0 || fy < 0 || fx > w - 1 || fy > h - 1) {
            fx = Math.max(0, Math.min(w - 1.001f, fx));
            fy = Math.max(0, Math.min(h - 1.001f, fy));
        }
        int x0 = (int) fx, y0 = (int) fy;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float tx = fx - x0, ty = fy - y0;
        float a = src[(y0 * w + x0) * 3 + c], b = src[(y0 * w + x1) * 3 + c];
        float d = src[(y1 * w + x0) * 3 + c], e = src[(y1 * w + x1) * 3 + c];
        float top = a + (b - a) * tx, bot = d + (e - d) * tx;
        return top + (bot - top) * ty;
    }

    static void lens(float[] img, int w, int h, ImageParams p) {
        ImageParams.Lens l = p.lens();
        // Vignetting: strongest wide open, mostly gone two stops down.
        double stopsDown = Math.max(0, Math.log(p.focus().fNumber() / l.maxAperture()) / Math.log(2));
        double vig = l.vignettingEv() * Math.max(0, 1 - stopsDown * 0.45);
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
        double amount = (l.sharpness() - 0.95) * 1.5;
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

    // ------------------------------------------------------------------ flare

    private static final float[][] GHOST_TINT = {{0.6f, 1.0f, 0.5f}, {1.0f, 0.5f, 0.9f}, {1.0f, 0.75f, 0.35f},
            {0.45f, 0.7f, 1.0f}, {0.8f, 1.0f, 0.7f}};
    private static final double[] GHOST_POS = {0.45, -0.25, -0.6, -0.95, -1.4};
    private static final double[] GHOST_SIZE = {0.025, 0.06, 0.035, 0.09, 0.05};

    /**
     * Sun in or near the frame: veiling glare (lifts shadows, lowers contrast), aperture-shaped
     * ghosts along the line through the centre, and a starburst when stopped down.
     */
    static void flare(float[] img, int w, int h, ImageParams p) {
        ImageParams.Sun sun = p.sun();
        double strength = sun.strength() * p.lens().flare();
        if (strength < 0.01) {
            return;
        }
        double cx = w / 2.0, cy = h / 2.0;
        double sx = cx + sun.x() * cx, sy = cy - sun.y() * cy;
        double diag = Math.hypot(w, h);
        boolean inFrame = Math.abs(sun.x()) <= 1 && Math.abs(sun.y()) <= 1;
        // veiling glare: stronger near the sun
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double d = Math.hypot(x - sx, y - sy) / diag;
                float g = (float) (strength * (0.012 + 0.06 * Math.exp(-d * 6)));
                int j = (y * w + x) * 3;
                img[j] += g * 1.0f;
                img[j + 1] += g * 0.95f;
                img[j + 2] += g * 0.85f;
            }
        }
        int blades = Math.max(5, p.lens().blades());
        for (int i = 0; i < GHOST_POS.length; i++) {
            double gx = cx + (sx - cx) * GHOST_POS[i], gy = cy + (sy - cy) * GHOST_POS[i];
            double r = GHOST_SIZE[i] * diag;
            float k = (float) (strength * 0.05);
            for (int y = (int) Math.max(0, gy - r); y < Math.min(h, gy + r); y++) {
                for (int x = (int) Math.max(0, gx - r); x < Math.min(w, gx + r); x++) {
                    double dx = x - gx, dy = y - gy, rr = Math.hypot(dx, dy);
                    double edge = polygonRadius(Math.atan2(dy, dx), blades, r);
                    if (rr > edge) continue;
                    float soft = (float) (0.5 + 0.5 * (rr / edge));
                    int j = (y * w + x) * 3;
                    img[j] += k * GHOST_TINT[i][0] * soft;
                    img[j + 1] += k * GHOST_TINT[i][1] * soft;
                    img[j + 2] += k * GHOST_TINT[i][2] * soft;
                }
            }
        }
        // starburst: blade count rays (twice as many for odd counts), visible when stopped down
        double stopsDown = Math.log(p.focus().fNumber() / Math.max(0.7, p.lens().maxAperture())) / Math.log(2);
        if (inFrame && stopsDown > 1.5) {
            int rays = blades % 2 == 0 ? blades : blades * 2;
            double len = diag * 0.12 * Math.min(1, (stopsDown - 1.5) / 2.5);
            float k = (float) (strength * 0.9);
            int x0 = (int) Math.max(0, sx - len), x1 = (int) Math.min(w - 1, sx + len);
            int y0 = (int) Math.max(0, sy - len), y1 = (int) Math.min(h - 1, sy + len);
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    double dx = x - sx, dy = y - sy, rr = Math.hypot(dx, dy);
                    if (rr < 1 || rr > len) continue;
                    double phase = Math.atan2(dy, dx) * rays / 2;
                    double ray = Math.pow(Math.abs(Math.cos(phase)), 60);
                    float v = (float) (k * ray * (1 - rr / len) * (1 - rr / len));
                    int j = (y * w + x) * 3;
                    img[j] += v;
                    img[j + 1] += v * 0.97f;
                    img[j + 2] += v * 0.9f;
                }
            }
        }
    }

    // ------------------------------------------------------------------ noise

    /**
     * Photon (shot) noise grows with sqrt(signal x amplification), read noise with amplification.
     * Downsampling a high-resolution sensor averages noise, which is why resolutionScale matters.
     */
    static void noise(float[] img, int w, int h, ImageParams p) {
        ImageParams.Sensor s = p.sensor();
        if (s.noiseLuma() <= 0 && s.noiseChroma() <= 0) {
            return;
        }
        double amp = Math.max(1, s.isoGain()) * Math.max(1, Math.pow(2, s.exposureEv()));
        double avg = Math.sqrt(Math.min(1, s.resolutionScale()));
        double shotK = 6e-5 * s.noiseLuma();
        double readK = 1.2e-4 * s.noiseLuma();
        double chromaK = 0.6 * s.noiseChroma();
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
    public static void rollOff(float[] img, double dynamicRangeEv) {
        float knee = (float) Math.max(0.6, Math.min(0.9, 1.25 - dynamicRangeEv * 0.035));
        float span = 1 - knee;
        for (int i = 0; i < img.length; i++) {
            float v = img[i];
            if (v > knee) {
                img[i] = knee + span * (1 - (float) Math.exp(-(v - knee) / span));
            }
        }
    }

    public static void colour(float[] img, ImageParams.Colour c) {
        float wr = (float) (c.wbR() * c.tintR()), wg = (float) (c.wbG() * c.tintG()), wb = (float) (c.wbB() * c.tintB());
        float sat = (float) c.saturation();
        float lift = (float) c.shadowLift();
        double contrast = c.contrast();
        for (int i = 0; i < img.length; i += 3) {
            float r = img[i] * wr, g = img[i + 1] * wg, b = img[i + 2] * wb;
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            if (c.monochrome()) {
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
