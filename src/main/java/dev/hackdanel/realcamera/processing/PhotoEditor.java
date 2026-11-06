package dev.hackdanel.realcamera.processing;

import java.util.SplittableRandom;

/**
 * The computer's photo editor ("develop" module). Works on linear light:
 * <ul>
 *   <li>RAW sources hold sensor data before white balance, tone curve and colour profile, with headroom
 *       above JPEG white up to the sensor's saturation, so exposure pulls recover highlights;</li>
 *   <li>JPEG sources are already finished: their clipped highlights stay clipped and pushing them
 *       degrades quality, as with a real JPEG.</li>
 * </ul>
 * Order: lens correction, geometry (rotate, straighten, crop), exposure and white balance, RAW
 * finishing (highlight roll-off + camera profile), tone (blacks/whites/shadows/highlights/contrast,
 * curve), colour (HSL, vibrance, saturation, monochrome), detail (noise reduction, sharpening), effects
 * (vignette, grain). Pure Java, unit-tested.
 */
public final class PhotoEditor {
    private PhotoEditor() {}

    /**
     * What the editor starts from.
     *
     * @param linear        linear RGB floats; RAW values may exceed 1 (up to the saturation level)
     * @param raw           true for RAW (sensor data), false for a finished JPEG
     * @param dynamicRangeEv the camera's dynamic range (RAW highlight roll-off)
     * @param asShot        white balance and colour profile of the shot (RAW only)
     * @param vignettingEv  lens vignetting baked into the image, EV at the corner (for profile correction)
     * @param distortion    radial k1 baked into the image
     * @param caFrac        lateral CA at the corner as a fraction of the half-diagonal
     */
    public record Source(float[] linear, int w, int h, boolean raw, double dynamicRangeEv,
                         ImageParams.Colour asShot, double vignettingEv, double distortion, double caFrac) {
        public Source scaled(int maxSide) {
            if (Math.max(w, h) <= maxSide) return this;
            double s = (double) maxSide / Math.max(w, h);
            int nw = Math.max(1, (int) Math.round(w * s)), nh = Math.max(1, (int) Math.round(h * s));
            return new Source(resizeArea(linear, w, h, nw, nh), nw, nh, raw, dynamicRangeEv, asShot, vignettingEv,
                    distortion, caFrac);
        }
    }

    /** Result in 0xRRGGBB plus its size. */
    public record Result(int[] rgb, int w, int h) {}

    public static Result apply(Source src, EditSettings e, ImageParams.Colour profileOverride) {
        float[] img = src.linear().clone();
        int w = src.w(), h = src.h();
        if (e.lensCorrection) {
            img = correctLens(img, w, h, src);
        }
        // geometry
        for (int i = 0; i < Math.floorMod(e.rotate, 4); i++) {
            img = rotate90(img, w, h);
            int t = w;
            w = h;
            h = t;
        }
        if (Math.abs(e.straighten) > 0.01) {
            img = straighten(img, w, h, e.straighten);
        }
        if (e.hasCrop()) {
            int x0 = (int) Math.round(clamp(e.cropX, 0, 1) * w), y0 = (int) Math.round(clamp(e.cropY, 0, 1) * h);
            int cw = Math.max(8, Math.min(w - x0, (int) Math.round(e.cropW * w)));
            int ch = Math.max(8, Math.min(h - y0, (int) Math.round(e.cropH * h)));
            x0 = Math.min(x0, w - cw);
            y0 = Math.min(y0, h - ch);
            img = crop(img, w, x0, y0, cw, ch);
            w = cw;
            h = ch;
        }
        // exposure + white balance (relative to as-shot)
        double[] wb = temperatureTint(e.temperature, e.tint);
        float gain = (float) Math.pow(2, e.exposure);
        for (int i = 0; i < img.length; i += 3) {
            img[i] *= (float) (gain * wb[0]);
            img[i + 1] *= (float) (gain * wb[1]);
            img[i + 2] *= (float) (gain * wb[2]);
        }
        if (src.raw()) {
            // highlight recovery: the shoulder starts lower when pulling exposure (more headroom used)
            // white balance on the sensor data first (clipped highlights stay neutral), then roll-off and tone
            ImageParams.Colour c = profileOverride != null ? profileOverride : src.asShot();
            ImageParams.Colour withWb = new ImageParams.Colour(src.asShot().wbR(), src.asShot().wbG(),
                    src.asShot().wbB(), c.contrast(), c.saturation(), c.monochrome(), c.tintR(), c.tintG(), c.tintB(),
                    c.shadowLift());
            ImagePipeline.whiteBalance(img, withWb, ImagePipeline.rawSaturation(src.dynamicRangeEv()));
            ImagePipeline.rollOff(img, src.dynamicRangeEv());
            ImagePipeline.tone(img, withWb);
        } else {
            for (int i = 0; i < img.length; i++) {
                img[i] = Math.min(1, img[i]);
            }
        }
        // from here on: display-referred values (sRGB-encoded 0..1)
        float[] v = encode(img);
        tone(v, e);
        colour(v, e);
        if (e.noiseReduction > 0.5) v = denoise(v, w, h, e.noiseReduction / 100);
        if (e.sharpen > 0.5) v = sharpen(v, w, h, e.sharpen / 100);
        if (Math.abs(e.vignette) > 0.5) vignette(v, w, h, e.vignette / 100);
        if (e.grain > 0.5) grain(v, w, h, e.grain / 100);
        int[] out = new int[w * h];
        for (int i = 0, j = 0; i < out.length; i++, j += 3) {
            out[i] = 0xFF000000 | (q(v[j]) << 16) | (q(v[j + 1]) << 8) | q(v[j + 2]);
        }
        return new Result(out, w, h);
    }

    // ------------------------------------------------------------------ lens & geometry

    static float[] correctLens(float[] img, int w, int h, Source s) {
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, halfDiag = Math.hypot(cx, cy);
        double k1 = s.distortion(), ca = s.caFrac(), vig = s.vignettingEv();
        float[] out = new float[img.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double nx = (x - cx) / halfDiag, ny = (y - cy) / halfDiag;
                double r2 = nx * nx + ny * ny;
                double m = Math.abs(k1) < 1e-5 ? 1 : 1 / ImagePipeline.distortionScale(r2, k1);
                double[] sc = {m * (1 - ca), m, m * (1 + ca)};
                float undo = vig < 0.01 ? 1f : (float) Math.pow(2, vig * r2 * r2 * 0.5 + vig * r2 * 0.5);
                int j = (y * w + x) * 3;
                for (int c = 0; c < 3; c++) {
                    out[j + c] = sample(img, w, h, (float) (cx + nx * sc[c] * halfDiag),
                            (float) (cy + ny * sc[c] * halfDiag), c) * undo;
                }
            }
        }
        return out;
    }

    static float[] rotate90(float[] img, int w, int h) {
        float[] out = new float[img.length];
        // clockwise: (x, y) -> (h - 1 - y, x) in an h-wide image
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int s = (y * w + x) * 3, d = (x * h + (h - 1 - y)) * 3;
                out[d] = img[s];
                out[d + 1] = img[s + 1];
                out[d + 2] = img[s + 2];
            }
        }
        return out;
    }

    /** Rotates by a small angle and zooms just enough that no empty corners show. */
    static float[] straighten(float[] img, int w, int h, double degrees) {
        double a = Math.toRadians(degrees), cos = Math.cos(a), sin = Math.sin(a);
        double zoom = straightenZoom(w, h, degrees);
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0;
        float[] out = new float[img.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (x - cx) / zoom, dy = (y - cy) / zoom;
                float sx = (float) (cx + dx * cos + dy * sin), sy = (float) (cy - dx * sin + dy * cos);
                int j = (y * w + x) * 3;
                for (int c = 0; c < 3; c++) out[j + c] = sample(img, w, h, sx, sy, c);
            }
        }
        return out;
    }

    /** Scale that makes a w x h rectangle rotated by the angle cover the original frame. */
    public static double straightenZoom(int w, int h, double degrees) {
        double a = Math.abs(Math.toRadians(degrees));
        return Math.max((w * Math.cos(a) + h * Math.sin(a)) / w, (w * Math.sin(a) + h * Math.cos(a)) / h);
    }

    static float[] crop(float[] img, int w, int x0, int y0, int cw, int ch) {
        float[] out = new float[cw * ch * 3];
        for (int y = 0; y < ch; y++) {
            System.arraycopy(img, ((y0 + y) * w + x0) * 3, out, y * cw * 3, cw * 3);
        }
        return out;
    }

    // ------------------------------------------------------------------ white balance

    /**
     * Relative white balance multipliers: +temperature warms (more red, less blue), +tint goes magenta
     * (less green). -100..100 spans roughly 2000 K either way around daylight.
     */
    public static double[] temperatureTint(double temperature, double tint) {
        double[] base = ImagePipeline.kelvinRgb(5500);
        double[] shifted = ImagePipeline.kelvinRgb(5500 - temperature * 25);
        double r = shifted[0] / base[0], b = shifted[2] / base[2];
        double g = Math.pow(2, -tint / 100 * 0.35);
        // keep overall brightness roughly constant
        double norm = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return new double[]{r / norm, g / norm, b / norm};
    }

    // ------------------------------------------------------------------ tone

    static void tone(float[] v, EditSettings e) {
        boolean curveIdentity = isIdentityCurve(e.curve);
        if (e.blacks == 0 && e.whites == 0 && e.shadows == 0 && e.highlights == 0 && e.contrast == 0 && curveIdentity) {
            return;
        }
        // one lookup table for all channels (applied to luminance ratio to keep hues)
        float[] lut = new float[1025];
        for (int i = 0; i <= 1024; i++) {
            lut[i] = (float) toneCurve(i / 1024.0, e);
        }
        for (int i = 0; i < v.length; i += 3) {
            float l = Math.max(v[i], Math.max(v[i + 1], v[i + 2]));
            if (l <= 1e-5f) {
                float b = lut[0];
                v[i] = v[i + 1] = v[i + 2] = b;
                continue;
            }
            float nl = lut[Math.min(1024, (int) (l * 1024 + 0.5f))];
            float k = nl / l;
            v[i] = clamp01(v[i] * k);
            v[i + 1] = clamp01(v[i + 1] * k);
            v[i + 2] = clamp01(v[i + 2] * k);
        }
    }

    /** The combined parametric + point curve for a display value x in 0..1. */
    public static double toneCurve(double x, EditSettings e) {
        // levels: blacks move the black point, whites the white point
        double lo = -e.blacks / 100 * 0.12, hi = 1 - e.whites / 100 * 0.12;
        x = (x - lo) / (hi - lo);
        x = clamp(x, 0, 1);
        // shadows lift/crush the bottom half, highlights the top half
        double sh = e.shadows / 100 * 0.35, hl = e.highlights / 100 * 0.35;
        x += sh * x * Math.pow(1 - x, 2) * 2.5;
        x += hl * Math.pow(x, 2) * (1 - x) * 2.5;
        x = clamp(x, 0, 1);
        // contrast: S-curve around middle grey (display 0.46)
        if (e.contrast != 0) {
            double c = e.contrast / 100;
            double p = 0.46;
            double k = 1 + c * 1.2;
            double y = x < p ? p * Math.pow(x / p, k) : 1 - (1 - p) * Math.pow((1 - x) / (1 - p), k);
            x = y;
        }
        return clamp(spline(e.curve, x), 0, 1);
    }

    private static boolean isIdentityCurve(double[] c) {
        for (int i = 0; i < 5; i++) {
            if (Math.abs(c[i] - i * 0.25) > 1e-6) return false;
        }
        return true;
    }

    /** Monotone cubic (Fritsch-Carlson) through five evenly spaced points. */
    public static double spline(double[] y, double x) {
        int n = y.length;
        double hStep = 1.0 / (n - 1);
        double[] d = new double[n - 1];
        for (int i = 0; i < n - 1; i++) d[i] = (y[i + 1] - y[i]) / hStep;
        double[] m = new double[n];
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) {
            m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        }
        for (int i = 0; i < n - 1; i++) {
            if (d[i] == 0) {
                m[i] = m[i + 1] = 0;
                continue;
            }
            double a = m[i] / d[i], b = m[i + 1] / d[i];
            double s = a * a + b * b;
            if (s > 9) {
                double t = 3 / Math.sqrt(s);
                m[i] = t * a * d[i];
                m[i + 1] = t * b * d[i];
            }
        }
        int k = Math.min(n - 2, Math.max(0, (int) (x / hStep)));
        double t = (x - k * hStep) / hStep;
        double t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * y[k] + (t3 - 2 * t2 + t) * hStep * m[k]
                + (-2 * t3 + 3 * t2) * y[k + 1] + (t3 - t2) * hStep * m[k + 1];
    }

    // ------------------------------------------------------------------ colour

    static void colour(float[] v, EditSettings e) {
        boolean hsl = false;
        for (int i = 0; i < 8; i++) {
            if (e.hslHue[i] != 0 || e.hslSat[i] != 0 || e.hslLum[i] != 0) hsl = true;
        }
        double sat = 1 + e.saturation / 100, vib = e.vibrance / 100;
        if (!hsl && sat == 1 && vib == 0 && !e.monochrome) {
            return;
        }
        float[] hslv = new float[3];
        for (int i = 0; i < v.length; i += 3) {
            float r = v[i], g = v[i + 1], b = v[i + 2];
            if (hsl) {
                rgbToHsl(r, g, b, hslv);
                double hue = hslv[0] * 360, s = hslv[1], l = hslv[2];
                double dh = 0, ds = 0, dl = 0;
                for (int k = 0; k < 8; k++) {
                    double wgt = bandWeight(hue, k);
                    if (wgt <= 0) continue;
                    dh += wgt * e.hslHue[k] / 100 * 30;
                    ds += wgt * e.hslSat[k] / 100;
                    dl += wgt * e.hslLum[k] / 100;
                }
                hue = (hue + dh + 360) % 360;
                s = clamp(s * (1 + ds), 0, 1);
                // luminance changes matter less for grey pixels
                l = clamp(l * (1 + dl * 0.5 * Math.min(1, s * 2)), 0, 1);
                hslToRgb((float) (hue / 360), (float) s, (float) l, hslv);
                r = hslv[0];
                g = hslv[1];
                b = hslv[2];
            }
            float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            if (e.monochrome) {
                r = g = b = luma;
            } else {
                float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                float cur = mx <= 1e-5f ? 0 : (mx - mn) / mx;
                // vibrance boosts muted colours and protects already saturated ones
                float k = (float) (sat * (1 + vib * (1 - cur)));
                r = luma + (r - luma) * k;
                g = luma + (g - luma) * k;
                b = luma + (b - luma) * k;
            }
            v[i] = clamp01(r);
            v[i + 1] = clamp01(g);
            v[i + 2] = clamp01(b);
        }
    }

    /** Triangular weight of a hue for one of the eight bands (neighbouring centres overlap). */
    static double bandWeight(double hue, int band) {
        double c = EditSettings.HSL_CENTRES[band];
        double prev = EditSettings.HSL_CENTRES[(band + 7) % 8], next = EditSettings.HSL_CENTRES[(band + 1) % 8];
        double left = Math.floorMod((long) Math.round((c - prev) * 1000), 360_000) / 1000.0;
        double right = Math.floorMod((long) Math.round((next - c) * 1000), 360_000) / 1000.0;
        double d = hue - c;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        if (d < 0) return Math.max(0, 1 + d / left);
        return Math.max(0, 1 - d / right);
    }

    static void rgbToHsl(float r, float g, float b, float[] out) {
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        float l = (mx + mn) / 2, h = 0, s = 0;
        float d = mx - mn;
        if (d > 1e-6f) {
            s = l > 0.5f ? d / (2 - mx - mn) : d / (mx + mn);
            if (mx == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (mx == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        out[0] = h;
        out[1] = s;
        out[2] = l;
    }

    static void hslToRgb(float h, float s, float l, float[] out) {
        if (s <= 1e-6f) {
            out[0] = out[1] = out[2] = l;
            return;
        }
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
        float p = 2 * l - q;
        out[0] = hue(p, q, h + 1 / 3f);
        out[1] = hue(p, q, h);
        out[2] = hue(p, q, h - 1 / 3f);
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1 / 6f) return p + (q - p) * 6 * t;
        if (t < 1 / 2f) return q;
        if (t < 2 / 3f) return p + (q - p) * (2 / 3f - t) * 6;
        return p;
    }

    // ------------------------------------------------------------------ detail & effects

    /** Luminance smoothing (edge-aware) plus stronger chroma smoothing. */
    static float[] denoise(float[] v, int w, int h, double amount) {
        int r = Math.max(1, (int) Math.round(1 + amount * 2));
        float[] blur = ImagePipeline.boxBlur(v, w, h, r);
        float[] out = new float[v.length];
        float a = (float) amount;
        for (int i = 0; i < v.length; i += 3) {
            float l = 0.2126f * v[i] + 0.7152f * v[i + 1] + 0.0722f * v[i + 2];
            float bl = 0.2126f * blur[i] + 0.7152f * blur[i + 1] + 0.0722f * blur[i + 2];
            // edges (big difference to the local mean) keep their detail
            float edge = Math.min(1, Math.abs(l - bl) * 12);
            float lk = a * 0.8f * (1 - edge);
            float nl = l + (bl - l) * lk;
            for (int c = 0; c < 3; c++) {
                float chroma = v[i + c] - l, bchroma = blur[i + c] - bl;
                float nc = chroma + (bchroma - chroma) * Math.min(1, a * 1.5f);
                out[i + c] = clamp01(nl + nc);
            }
        }
        return out;
    }

    static float[] sharpen(float[] v, int w, int h, double amount) {
        float[] blur = ImagePipeline.boxBlur(v, w, h, 1);
        float k = (float) (amount * 1.6);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = clamp01(v[i] + (v[i] - blur[i]) * k);
        }
        return out;
    }

    /** Negative darkens the corners, positive brightens them. */
    static void vignette(float[] v, int w, int h, double amount) {
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, norm = cx * cx + cy * cy;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double r2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / norm;
                double f = r2 * r2;
                int j = (y * w + x) * 3;
                for (int c = 0; c < 3; c++) {
                    float val = v[j + c];
                    v[j + c] = clamp01((float) (amount < 0 ? val * (1 + amount * 0.8 * f)
                            : val + (1 - val) * amount * 0.6 * f));
                }
            }
        }
    }

    /** Film-like grain: monochrome noise, strongest in the midtones, a little clumped. */
    static void grain(float[] v, int w, int h, double amount) {
        SplittableRandom rnd = new SplittableRandom(0x5EED);
        float[] n = new float[w * h];
        for (int i = 0; i < n.length; i++) n[i] = (float) rnd.nextGaussian();
        // clump: average with right/down neighbour
        float[] c = new float[n.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                c[i] = (n[i] * 2 + n[y * w + Math.min(w - 1, x + 1)] + n[Math.min(h - 1, y + 1) * w + x]) / 2.45f;
            }
        }
        float a = (float) (amount * 0.09);
        for (int i = 0, j = 0; i < c.length; i++, j += 3) {
            float l = 0.2126f * v[j] + 0.7152f * v[j + 1] + 0.0722f * v[j + 2];
            float mid = 4 * l * (1 - l);
            float d = c[i] * a * (0.35f + mid);
            v[j] = clamp01(v[j] + d);
            v[j + 1] = clamp01(v[j + 1] + d);
            v[j + 2] = clamp01(v[j + 2] + d);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Linear -> sRGB-encoded floats. */
    static float[] encode(float[] lin) {
        float[] out = new float[lin.length];
        for (int i = 0; i < lin.length; i++) {
            float x = Math.max(0, Math.min(1, lin[i]));
            out[i] = (float) (x <= 0.0031308 ? x * 12.92 : 1.055 * Math.pow(x, 1 / 2.4) - 0.055);
        }
        return out;
    }

    private static int q(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    static float sample(float[] src, int w, int h, float fx, float fy, int c) {
        fx = Math.max(0, Math.min(w - 1.001f, fx));
        fy = Math.max(0, Math.min(h - 1.001f, fy));
        int x0 = (int) fx, y0 = (int) fy;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float tx = fx - x0, ty = fy - y0;
        float a = src[(y0 * w + x0) * 3 + c], b = src[(y0 * w + x1) * 3 + c];
        float d = src[(y1 * w + x0) * 3 + c], e = src[(y1 * w + x1) * 3 + c];
        float top = a + (b - a) * tx, bot = d + (e - d) * tx;
        return top + (bot - top) * ty;
    }

    /** Area-average resize of linear float RGB. */
    public static float[] resizeArea(float[] src, int w, int h, int nw, int nh) {
        float[] acc = new float[nw * nh * 3];
        float[] cnt = new float[nw * nh];
        for (int y = 0; y < h; y++) {
            int ny = Math.min(nh - 1, y * nh / h);
            for (int x = 0; x < w; x++) {
                int nx = Math.min(nw - 1, x * nw / w);
                int k = ny * nw + nx, s = (y * w + x) * 3;
                acc[k * 3] += src[s];
                acc[k * 3 + 1] += src[s + 1];
                acc[k * 3 + 2] += src[s + 2];
                cnt[k] += 1;
            }
        }
        for (int i = 0; i < cnt.length; i++) {
            float n = Math.max(1, cnt[i]);
            acc[i * 3] /= n;
            acc[i * 3 + 1] /= n;
            acc[i * 3 + 2] /= n;
        }
        return acc;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
