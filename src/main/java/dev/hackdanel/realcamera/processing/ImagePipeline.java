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

    /** Linear 0..1 -> sRGB byte, 65536 steps (fine enough near black, where sRGB is steepest). */
    private static final byte[] LINEAR_TO_SRGB = new byte[65536];

    static {
        for (int i = 0; i < LINEAR_TO_SRGB.length; i++) {
            LINEAR_TO_SRGB[i] = (byte) encode((i + 0.5f) / LINEAR_TO_SRGB.length);
        }
    }

    private static int fastEncode(float v) {
        if (!(v > 0)) return 0;
        if (v >= 1) return 255;
        return LINEAR_TO_SRGB[(int) (v * 65536)] & 255;
    }

    public static int[] toSrgb(float[] lin) {
        int[] out = new int[lin.length / 3];
        java.util.stream.IntStream.range(0, out.length).parallel().forEach(i -> {
            int j = i * 3;
            out[i] = 0xFF000000 | (fastEncode(lin[j]) << 16) | (fastEncode(lin[j + 1]) << 8) | fastEncode(lin[j + 2]);
        });
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
        float[] beforeExposure = x.anyFlash() && depth != null ? img.clone() : null;
        expose(img, p.sensor().exposureEv());
        graduatedNd(img, w, h, x.gndStops());
        if (x.cpl() && depth != null) {
            polariser(img, depth, w, h, p);
        }
        // The flash lasts about 1/1000 s: when anything moves during the exposure, its light is kept apart and
        // placed sharp at the moment it fired, while the ambient light smears.
        boolean moving = MotionBlur.moves(p.motion());
        float[] flashLight = null;
        if (beforeExposure != null) {
            flashLight = moving ? new float[img.length] : img;
            flash(flashLight, beforeExposure, depth, light, w, h, x);
            if (!moving) flashLight = null;
        }
        if (depth != null) {
            img = depthOfField(img, depth, w, h, p);
            if (flashLight != null) flashLight = depthOfField(flashLight, depth, w, h, p);
        }
        img = MotionBlur.apply(img, flashLight, depth, w, h, p);
        img = rollingShutter(img, w, h, p.motion().rollingSkewPx(), p.motion().rollingSkewY());
        img = lensShift(img, w, h, p);
        img = p.lens().projection() != 0 ? fisheye(img, w, h, p) : lensGeometry(img, w, h, p.lens());
        lens(img, w, h, p);
        img = wear(img, w, h, p);
        flare(img, w, h, p);
        img = mist(img, w, h, x.mist());
        star(img, w, h, x.starPoints());
        noise(img, w, h, p);
        float sat = (float) p.sensor().saturation();
        for (int i = 0; i < img.length; i++) {
            if (img[i] > sat) img[i] = sat;
        }
        return img;
    }

    /**
     * The in-camera image processor, in the order a real one works: white balance on the sensor data (clipped
     * highlights are made neutral, so blown skies stay white), highlight roll-off, colour profile.
     */
    public static float[] finish(float[] img, ImageParams p) {
        whiteBalance(img, p.colour(), p.sensor().saturation());
        rollOff(img, p.sensor().dynamicRangeEv());
        tone(img, p.colour());
        return img;
    }

    /**
     * Sensor saturation relative to JPEG white: the RAW highlight headroom. Real raw files clip about 3-3.5 EV
     * above middle grey whatever the sensor, i.e. roughly 1 EV above JPEG white; a high-DR sensor gains its
     * extra stops in the shadows (less read noise), not in the highlights.
     */
    public static double rawSaturation(double dynamicRangeEv) {
        return Math.max(1.7, Math.min(2.6, Math.pow(2, 0.9 + (dynamicRangeEv - 12) * 0.12)));
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
     * Graduated ND with a soft edge, held level: the top of the frame loses {@code stops}, the bottom nothing, with
     * the transition over the middle fifth (blurred further by the aperture, as the filter is far out of focus).
     */
    static void graduatedNd(float[] img, int w, int h, double stops) {
        if (stops <= 0) return;
        for (int y = 0; y < h; y++) {
            double t = (y + 0.5) / h;
            double s = Math.max(0, Math.min(1, (0.6 - t) / 0.2));
            s = s * s * (3 - 2 * s);
            float k = (float) Math.pow(2, -stops * s);
            if (k >= 0.9999f) continue;
            for (int i = y * w * 3, end = i + w * 3; i < end; i++) {
                img[i] *= k;
            }
        }
    }

    /**
     * Diffusion filter (Black Mist): a small part of the light is scattered by the particles in the glass, so bright
     * areas glow into their surroundings and the shadows get a little of that light, lowering contrast. The glow is
     * wide and soft, strongest around highlights.
     */
    static float[] mist(float[] img, int w, int h, double strength) {
        if (strength <= 0) return img;
        float[] blur = boxBlur(img, w, h, Math.max(2, w / 90));
        blur = boxBlur(blur, w, h, Math.max(2, w / 90));
        float[] wide = boxBlur(blur, w, h, Math.max(3, w / 30));
        float k = (float) strength;
        for (int i = 0; i < img.length; i++) {
            float glow = Math.max(0, blur[i] - 0.5f) * 1.6f + Math.max(0, wide[i] - 0.4f) * 0.8f;
            img[i] = img[i] * (1 - 0.35f * k) + blur[i] * 0.25f * k + glow * k + wide[i] * 0.06f * k;
        }
        return img;
    }

    /**
     * Cross-screen (star) filter: fine grooves diffract the light of bright points into rays. Each ray's length grows
     * with how bright the point is; at most a few hundred of the brightest points get them.
     */
    static void star(float[] img, int w, int h, int points) {
        if (points <= 0) return;
        float threshold = 1.2f;
        int cell = Math.max(4, w / 160);
        java.util.List<float[]> sources = new java.util.ArrayList<>();
        for (int cy = 0; cy < h; cy += cell) {
            for (int cx = 0; cx < w; cx += cell) {
                float best = threshold;
                int bx = -1, by = -1;
                for (int y = cy; y < Math.min(h, cy + cell); y++) {
                    for (int x = cx; x < Math.min(w, cx + cell); x++) {
                        int j = (y * w + x) * 3;
                        float l = 0.2126f * img[j] + 0.7152f * img[j + 1] + 0.0722f * img[j + 2];
                        if (l > best) {
                            best = l;
                            bx = x;
                            by = y;
                        }
                    }
                }
                if (bx >= 0 && pointLike(img, w, h, bx, by, best, cell)) {
                    int j = (by * w + bx) * 3;
                    sources.add(new float[]{bx, by, best, img[j], img[j + 1], img[j + 2]});
                }
            }
        }
        if (sources.isEmpty()) return;
        sources.sort((a, b) -> Float.compare(b[2], a[2]));
        if (sources.size() > 120) sources = sources.subList(0, 120);
        float[] add = new float[img.length];
        int dirs = Math.max(2, points);
        for (float[] s : sources) {
            float excess = s[2] - threshold;
            double len = Math.min(w * 0.12, w * 0.012 * (1 + 4 * Math.log1p(excess)));
            float scale = 0.06f / s[2];
            for (int d = 0; d < dirs; d++) {
                double a = Math.PI * 2 * d / dirs + Math.PI / 12;
                double dx = Math.cos(a), dy = Math.sin(a);
                for (int step = 2; step < len; step++) {
                    int x = (int) Math.round(s[0] + dx * step), y = (int) Math.round(s[1] + dy * step);
                    if (x < 0 || y < 0 || x >= w || y >= h) break;
                    float fall = (float) Math.pow(1 - step / len, 2);
                    int j = (y * w + x) * 3;
                    for (int c = 0; c < 3; c++) {
                        add[j + c] += s[3 + c] * excess * scale * fall * 4;
                    }
                }
            }
        }
        for (int i = 0; i < img.length; i++) img[i] += add[i];
    }

    /**
     * A bright point rather than part of a bright area: the ring around it (a few cells out) is much darker. Only
     * such points make distinct stars; a lit wall or the sky makes no rays of its own.
     */
    private static boolean pointLike(float[] img, int w, int h, int x, int y, float lum, int cell) {
        int r = cell * 3;
        float sum = 0;
        int n = 0;
        for (int k = 0; k < 16; k++) {
            double a = Math.PI * 2 * k / 16;
            int px = (int) Math.round(x + Math.cos(a) * r), py = (int) Math.round(y + Math.sin(a) * r);
            if (px < 0 || py < 0 || px >= w || py >= h) continue;
            int j = (py * w + px) * 3;
            sum += 0.2126f * img[j] + 0.7152f * img[j + 1] + 0.0722f * img[j + 2];
            n++;
        }
        return n > 0 && sum / n < lum * 0.35f;
    }

    /**
     * Flash light: E(d) = flashAt1m / d² relative to a correct exposure, applied to the surface colour
     * (pixel brightness divided by the light the game lit it with). Rows below the curtain edge (when
     * shooting faster than the sync speed) get no flash. Other photographers' flashes ({@code otherFlash}) come
     * already computed per pixel and light the whole frame.
     */
    static void flash(float[] img, float[] raw, float[] depth, float[] light, int w, int h, ImageParams.Extras x) {
        int lastRow = x.flashAt1m() > 0 ? (int) Math.round(h * Math.max(0, Math.min(1, x.flashTop()))) : 0;
        float[] other = x.otherFlash() != null && x.otherFlash().length == w * h ? x.otherFlash() : null;
        for (int y = 0; y < h; y++) {
            if (y >= lastRow && other == null) break;
            for (int px = 0; px < w; px++) {
                int i = y * w + px;
                double e = 0;
                double d = Math.max(0.3, depth[i]);
                if (y < lastRow && d <= 200) { // sky and far away: the flash does not reach
                    e = x.flashAt1m() / (d * d);
                }
                if (other != null) e += other[i];
                if (e <= 0) continue;
                float lit = light == null ? 0.5f : Math.max(0.04f, light[i]);
                int j = i * 3;
                for (int c = 0; c < 3; c++) {
                    float albedo = Math.min(1.2f, raw[j + c] / lit);
                    img[j + c] += (float) (albedo * e);
                }
            }
        }
    }

    /**
     * Circular polariser, turned for the strongest effect. Clear skylight is polarised most 90° from the sun and
     * hardly at all towards or away from it, so the sky darkens by the polarised fraction (1 - P) in a band across
     * it (unevenly on a wide angle); an overcast sky barely changes. Elsewhere glare goes: slightly richer colours.
     */
    static void polariser(float[] img, float[] depth, int w, int h, ImageParams p) {
        ImageParams.Sun sun = p.sun();
        double fpx = p.focus().focalMm() * w / p.focus().sensorWidthMm();
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0;
        boolean dir = sun.hasDirection();
        double flatP = 0.3 * sun.clearSky(); // no sun direction known (night, no sky): an average sky
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, j = i * 3;
                float sat, dim;
                if (depth[i] > 5000) {
                    double pol = flatP;
                    if (dir) {
                        double dx = (x - cx) / fpx, dy = -(y - cy) / fpx, n = Math.sqrt(dx * dx + dy * dy + 1);
                        double cosG = (dx * sun.dirX() + dy * sun.dirY() + sun.dirZ()) / n;
                        pol = Optics.skyPolarization(Math.acos(Math.max(-1, Math.min(1, cosG)))) * sun.clearSky();
                    }
                    dim = (float) (1 - pol);
                    sat = (float) (1 + 0.6 * pol);
                } else {
                    sat = 1.08f;
                    dim = 0.97f;
                }
                float l = 0.2126f * img[j] + 0.7152f * img[j + 1] + 0.0722f * img[j + 2];
                for (int c = 0; c < 3; c++) {
                    img[j + c] = Math.max(0, (l + (img[j + c] - l) * sat) * dim);
                }
            }
        });
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
        return blurRadius(depth, w, f, maxRadius, 0);
    }

    /**
     * offsetMm: added to the signed blur circle (+ nearer than focus): red and blue of a lens with axial colour come
     * to focus behind green, so their discs are larger in front of the focus plane and smaller behind it.
     */
    static float[] blurRadius(float[] depth, int w, ImageParams.Focus f, float maxRadius, double offsetMm) {
        float[] r = new float[depth.length];
        int h = depth.length / w;
        double pxPerMm = w / f.sensorWidthMm();
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            double focus = rowFocusM(f, y, w, h);
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double c = Math.abs(Optics.signedBlurMm(f.focalMm(), f.fNumber(), focus, depth[i]) + offsetMm);
                r[i] = (float) Math.min(maxRadius, c * pxPerMm * 0.5);
            }
        });
        return r;
    }

    /**
     * Blur-circle diameter (mm) by which red and blue differ from green: the axial focus shift seen through the
     * aperture, Δ / N.
     */
    static double locaOffsetMm(ImageParams p) {
        return p.lens().locaMm() / Math.max(0.5, p.focus().fNumber());
    }

    /** Focus distance of an image row: a tilted lens focuses every row at a different distance. */
    static double rowFocusM(ImageParams.Focus f, int y, int w, int h) {
        if (Math.abs(f.tiltDeg()) <= 1e-3) {
            return f.focusM();
        }
        double rowMm = (h / 2.0 - y - 0.5) / (w / f.sensorWidthMm());
        return Math.min(1e6, Optics.tiltedFocusM(f.focalMm(), f.focusM(), f.tiltDeg(), rowMm));
    }

    /**
     * Variable-radius blur using a pyramid: level k approximates a blur of radius 2^k. Each pixel
     * blends the two levels around its own blur radius. Pixels are weighted by their own blur so a
     * sharp subject does not bleed into the blurred background around it. Bright out-of-focus points
     * are then drawn as discs in the shape of the aperture (bokeh highlights).
     */
    static float[] depthOfField(float[] img, float[] depth, int w, int h, ImageParams p) {
        // real backgrounds blur a lot: 85 mm f/1.8 at 2 m gives a disc 6% of the frame wide, 600 mm f/4 at 5 m
        // (lights at infinity) more than half of it
        float maxR = Math.max(4, w * 0.3f);
        double loca = locaOffsetMm(p);
        if (loca * w / p.focus().sensorWidthMm() * 0.5 < 0.4) {
            return depthOfField(img, depth, blurRadius(depth, w, p.focus(), maxR), w, h, p);
        }
        // axial colour: green and red/blue blur by different amounts (purple fringes in front of the focus,
        // green ones behind it, a purple halo on contrasty edges wide open)
        float[] green = depthOfField(img.clone(), depth, blurRadius(depth, w, p.focus(), maxR), w, h, p);
        float[] out = depthOfField(img, depth, blurRadius(depth, w, p.focus(), maxR, loca), w, h, p);
        for (int j = 1; j < out.length; j += 3) {
            out[j] = green[j];
        }
        return out;
    }

    private static float[] depthOfField(float[] img, float[] depth, float[] radius, int w, int h, ImageParams p) {
        float largest = 0;
        for (float r : radius) largest = Math.max(largest, r);
        if (largest < 0.75f) {
            return img;
        }
        List<Highlight> highlights = extractHighlights(img, findHighlights(img, radius, w, h), w, h);
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
            pyr[k] = buildLevel(img, radius, null, w, h, k, 0.02f, 1f);
        }
        float[] blurred = new float[img.length];
        float[] src = img;
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            float[] a = new float[3], b = new float[3]; // per row: rows run in parallel
            float[] out = blurred, img2 = src;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float r = radius[i];
                int j = i * 3;
                if (r < 0.5f) {
                    out[j] = img2[j];
                    out[j + 1] = img2[j + 1];
                    out[j + 2] = img2[j + 2];
                    continue;
                }
                double lv = Math.max(0, Math.log(r) / Math.log(2) + 1); // r=1 -> level 1
                int k0 = Math.min(levels, (int) Math.floor(lv));
                int k1 = Math.min(levels, k0 + 1);
                float t = (float) (lv - Math.floor(lv));
                sampleLevel(pyr[k0], img2, w, h, x, y, a);
                sampleLevel(pyr[k1], img2, w, h, x, y, b);
                out[j] = a[0] + (b[0] - a[0]) * t;
                out[j + 1] = a[1] + (b[1] - a[1]) * t;
                out[j + 2] = a[2] + (b[2] - a[2]) * t;
            }
        });
        foregroundSpread(img, blurred, depth, radius, w, h, p.focus());
        float[] out = blurred;
        if (p.lens().bokeh() == ImageParams.BOKEH_SWIRL && stopsDown(p) < 2) {
            out = swirl(out, radius, w, h, 1 - stopsDown(p) / 2); // the swirl fades stopping down
        }
        drawBokeh(out, w, h, highlights, p.lens(), stopsDown(p));
        return out;
    }

    /**
     * Helios-style swirl: the out-of-focus background is smeared along circles around the frame centre,
     * more towards the edges and the more it is blurred.
     */
    static float[] swirl(float[] img, float[] radius, int w, int h) {
        return swirl(img, radius, w, h, 1);
    }

    static float[] swirl(float[] img, float[] radius, int w, int h, double strength) {
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, halfDiag = Math.hypot(cx, cy);
        float[] out = img.clone();
        int taps = 9;
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                double ox = x - cx, oy = y - cy;
                double dist = Math.hypot(ox, oy) / halfDiag;
                double len = radius[i] * 4.0 * Math.pow(dist, 1.5) * strength;
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
        });
        return out;
    }

    /**
     * Out-of-focus things in front of the focus plane spread over whatever lies behind them (a blurred grass blade
     * in front of a sharp cow covers it half-transparently), but a gather blur keeps every sharp pixel sharp and
     * leaves the blurred foreground with a hard outline. Here the foreground alone is blurred again with the radius
     * of the nearest foreground around each pixel and laid over the result by its coverage.
     */
    static void foregroundSpread(float[] img, float[] out, float[] depth, float[] radius, int w, int h, ImageParams.Focus f) {
        float[] fg = new float[w * h];
        float largest = 0;
        for (int y = 0; y < h; y++) {
            double focus = rowFocusM(f, y, w, h);
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (depth[i] < focus && radius[i] >= 1.5f) {
                    fg[i] = radius[i];
                    largest = Math.max(largest, radius[i]);
                }
            }
        }
        if (largest < 1.5f) {
            return;
        }
        // reach: the largest foreground radius that can touch each 8x8 cell
        int cs = Math.max(8, (int) Math.ceil(largest / 6)), cw = (w + cs - 1) / cs, ch = (h + cs - 1) / cs;
        float[] cell = new float[cw * ch];
        for (int i = 0; i < fg.length; i++) {
            int c = (i / w / cs) * cw + (i % w) / cs;
            cell[c] = Math.max(cell[c], fg[i]);
        }
        float[] reach = new float[cw * ch];
        int span = (int) Math.ceil(largest / cs) + 1;
        java.util.stream.IntStream.range(0, ch).parallel().forEach(cy -> {
            for (int cx = 0; cx < cw; cx++) {
                float best = 0;
                for (int dy = -span; dy <= span; dy++) {
                    int yy = cy + dy;
                    if (yy < 0 || yy >= ch) continue;
                    for (int dx = -span; dx <= span; dx++) {
                        int xx = cx + dx;
                        if (xx < 0 || xx >= cw) continue;
                        float r = cell[yy * cw + xx];
                        if (r > best && Math.hypot(dx, dy) * cs <= r + cs) best = r;
                    }
                }
                reach[cy * cw + cx] = best;
            }
        });
        // background mask: what is not blurred foreground (seen next to and, by extrapolation, behind it)
        float[] bgMask = new float[w * h];
        for (int i = 0; i < bgMask.length; i++) bgMask[i] = fg[i] > 0 ? 0 : 1;
        int levels = Math.max(1, (int) Math.ceil(Math.log(largest) / Math.log(2)) + 1);
        Level[] pyr = new Level[levels + 1], bgPyr = new Level[levels + 1];
        for (int k = 1; k <= levels; k++) {
            // coverage counts foreground pixels blurred about as much as the radius this level stands for
            pyr[k] = buildLevel(img, fg, null, w, h, k, 0f, 0.3f);
            bgPyr[k] = buildLevel(img, null, bgMask, w, h, k, 1f, 1f);
        }
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            float[] fgc = new float[4], bgc = new float[4];
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int j = i * 3;
                float own = radius[i];
                if (fg[i] > 0) {
                    // the pixel's own blur, laid over what is around (and so presumably behind) it: a thin blurred
                    // blade turns half-transparent at its edges instead of keeping a hard outline
                    sampleBlend(pyr, levels, img, w, h, x, y, own, fgc);
                    sampleBlend(bgPyr, levels, img, w, h, x, y, own, bgc);
                    float alpha = clamp01(fgc[3] * 1.15f), behindW = clamp01(bgc[3] * 8f);
                    for (int c = 0; c < 3; c++) {
                        float behind = out[j + c] + (bgc[c] - out[j + c]) * behindW;
                        out[j + c] = behind + (fgc[c] - behind) * alpha;
                    }
                }
                // more blurred foreground nearby reaches over this pixel
                float r = sampleGrid(reach, cw, ch, (x + 0.5f) / cs - 0.5f, (y + 0.5f) / cs - 0.5f);
                if (r < 1.5f || r < own * 1.25f + 1) continue;
                sampleBlend(pyr, levels, img, w, h, x, y, r, fgc);
                float alpha = clamp01(fgc[3] * 1.15f);
                if (alpha < 0.004f) continue;
                for (int c = 0; c < 3; c++) {
                    out[j + c] += (fgc[c] - out[j + c]) * alpha;
                }
            }
        });
    }

    /** Colour and coverage of a pyramid at blur radius r (between the two levels around it). */
    private static void sampleBlend(Level[] pyr, int levels, float[] img, int w, int h, int x, int y, float r, float[] out) {
        double lv = Math.max(1, Math.log(Math.max(1, r)) / Math.log(2) + 1);
        int k0 = Math.min(levels, (int) Math.floor(lv));
        int k1 = Math.min(levels, k0 + 1);
        float t = (float) (lv - Math.floor(lv));
        float[] a = new float[4], b = new float[4];
        sampleLevel(pyr[k0], img, w, h, x, y, a);
        sampleLevel(pyr[k1], img, w, h, x, y, b);
        for (int c = 0; c < 4; c++) {
            out[c] = a[c] + (b[c] - a[c]) * t;
        }
    }

    private static float sampleGrid(float[] g, int gw, int gh, float fx, float fy) {
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int x1 = Math.min(gw - 1, Math.max(0, x0 + 1)), y1 = Math.min(gh - 1, Math.max(0, y0 + 1));
        x0 = Math.min(gw - 1, Math.max(0, x0));
        y0 = Math.min(gh - 1, Math.max(0, y0));
        float top = g[y0 * gw + x0] + (g[y0 * gw + x1] - g[y0 * gw + x0]) * tx;
        float bot = g[y1 * gw + x0] + (g[y1 * gw + x1] - g[y1 * gw + x0]) * tx;
        return top + (bot - top) * ty;
    }

    /** A pyramid level: colour normalised by weight, and the mean weight per pixel (coverage). */
    private record Level(int scale, int lw, int lh, float[] rgb, float[] alpha) {}

    /**
     * Level k approximates a blur of radius 2^k. Pixels are weighted by how blurred they are relative to the level
     * (smoothstep(0.25 R, R, r)) plus {@code floor}, so sharp pixels do not bleed into the blur around them. Without
     * radii every pixel weighs {@code floor}; a mask multiplies the weights. {@code rel} scales the reference radius R.
     */
    private static Level buildLevel(float[] img, float[] radius, float[] mask, int w, int h, int k, float floor, float rel) {
        int scale = 1 << k;
        int lw = Math.max(1, (w + scale - 1) / scale);
        int lh = Math.max(1, (h + scale - 1) / scale);
        float[] acc = new float[lw * lh * 4];
        float levelR = scale * rel;
        // each level row gathers its own block of source rows, so level rows can run in parallel
        java.util.stream.IntStream.range(0, lh).parallel().forEach(ly -> {
            for (int y = ly * scale; y < Math.min(h, (ly + 1) * scale); y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float wgt = floor;
                    if (radius != null) {
                        // smoothstep(0.25 R, R, r): pixels sharper than this level contribute little
                        float t = clamp01((radius[i] - 0.25f * levelR) / (0.75f * levelR));
                        wgt += t * t * (3 - 2 * t);
                    }
                    if (mask != null) wgt *= mask[i];
                    int li = (ly * lw + x / scale) * 4;
                    int j = i * 3;
                    acc[li] += img[j] * wgt;
                    acc[li + 1] += img[j + 1] * wgt;
                    acc[li + 2] += img[j + 2] * wgt;
                    acc[li + 3] += wgt;
                }
            }
        });
        tent(acc, lw, lh);
        tent(acc, lw, lh);
        float[] rgb = new float[lw * lh * 3];
        float[] alpha = new float[lw * lh];
        float perCell = 1f / (scale * scale);
        for (int i = 0; i < lw * lh; i++) {
            float wsum = Math.max(acc[i * 4 + 3], 1e-6f);
            rgb[i * 3] = acc[i * 4] / wsum;
            rgb[i * 3 + 1] = acc[i * 4 + 1] / wsum;
            rgb[i * 3 + 2] = acc[i * 4 + 2] / wsum;
            alpha[i] = acc[i * 4 + 3] * perCell;
        }
        return new Level(scale, lw, lh, rgb, alpha);
    }

    /** Separable [1 2 1]/4 blur on 4-channel data, in place. */
    private static void tent(float[] a, int w, int h) {
        float[] tmp = new float[a.length];
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int l = Math.max(0, x - 1), r = Math.min(w - 1, x + 1);
                for (int c = 0; c < 4; c++) {
                    tmp[(y * w + x) * 4 + c] = (a[(y * w + l) * 4 + c] + 2 * a[(y * w + x) * 4 + c] + a[(y * w + r) * 4 + c]) * 0.25f;
                }
            }
        });
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            int u = Math.max(0, y - 1), d = Math.min(h - 1, y + 1);
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < 4; c++) {
                    a[(y * w + x) * 4 + c] = (tmp[(u * w + x) * 4 + c] + 2 * tmp[(y * w + x) * 4 + c] + tmp[(d * w + x) * 4 + c]) * 0.25f;
                }
            }
        });
    }

    private static void sampleLevel(Level lvl, float[] img, int w, int h, int x, int y, float[] out) {
        if (lvl == null) {
            int j = (y * w + x) * 3;
            out[0] = img[j];
            out[1] = img[j + 1];
            out[2] = img[j + 2];
            if (out.length > 3) out[3] = 0;
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
        if (out.length > 3) {
            float[] al = lvl.alpha();
            float a = al[y0 * lvl.lw() + x0], b = al[y0 * lvl.lw() + x1];
            float e = al[y1 * lvl.lw() + x0], f = al[y1 * lvl.lw() + x1];
            out[3] = (a + (b - a) * tx) + ((e + (f - e) * tx) - (a + (b - a) * tx)) * ty;
        }
    }

    // ------------------------------------------------------------------ bokeh highlights

    /** A bright out-of-focus point: position, blur radius, colour and (after extraction) its light energy. */
    record Highlight(float x, float y, float r, float cr, float cg, float cb, float er, float eg, float eb) {
        Highlight(float x, float y, float r, float cr, float cg, float cb) {
            this(x, y, r, cr, cg, cb, 0, 0, 0);
        }
    }

    /**
     * Takes each highlight's light out of the image (its bright core is set to the level of its surroundings) and
     * returns the highlights carrying that energy, so the bokeh disc spreads the light instead of adding it twice.
     */
    static List<Highlight> extractHighlights(float[] img, List<Highlight> found, int w, int h) {
        List<Highlight> out = new ArrayList<>(found.size());
        for (Highlight hl : found) {
            int cx = (int) hl.x, cy = (int) hl.y, k = 2;
            // the surroundings: a ring 3-4 px out
            float rr = 0, rg = 0, rb = 0;
            int n = 0;
            for (int d = -4; d <= 4; d += 8) {
                for (int t = -4; t <= 4; t += 2) {
                    int[][] pts = {{cx + d, cy + t}, {cx + t, cy + d}};
                    for (int[] q : pts) {
                        if (q[0] < 0 || q[1] < 0 || q[0] >= w || q[1] >= h) continue;
                        int j = (q[1] * w + q[0]) * 3;
                        rr += img[j];
                        rg += img[j + 1];
                        rb += img[j + 2];
                        n++;
                    }
                }
            }
            if (n == 0) continue;
            rr /= n;
            rg /= n;
            rb /= n;
            float er = 0, eg = 0, eb = 0;
            for (int y = Math.max(0, cy - k); y <= Math.min(h - 1, cy + k); y++) {
                for (int x = Math.max(0, cx - k); x <= Math.min(w - 1, cx + k); x++) {
                    int j = (y * w + x) * 3;
                    float l = 0.2126f * img[j] + 0.7152f * img[j + 1] + 0.0722f * img[j + 2];
                    float ring = 0.2126f * rr + 0.7152f * rg + 0.0722f * rb;
                    if (l <= ring * 1.3f) continue;
                    er += Math.max(0, img[j] - rr);
                    eg += Math.max(0, img[j + 1] - rg);
                    eb += Math.max(0, img[j + 2] - rb);
                    img[j] = Math.min(img[j], rr);
                    img[j + 1] = Math.min(img[j + 1], rg);
                    img[j + 2] = Math.min(img[j + 2], rb);
                }
            }
            if (er + eg + eb > 0) {
                out.add(new Highlight(hl.x, hl.y, hl.r, hl.cr, hl.cg, hl.cb, er, eg, eb));
            }
        }
        return out;
    }

    static double stopsDown(ImageParams p) {
        return Math.max(0, Math.log(p.focus().fNumber() / p.lens().maxAperture()) / Math.log(2));
    }

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
        drawBokeh(img, w, h, highlights, lens, 0);
    }

    /**
     * stopsDown: wide open the blades are retracted, so even a straight-bladed lens draws round discs; cat's eye and
     * swirl come from the barrel vignetting the aperture and fade within two stops.
     */
    static void drawBokeh(float[] img, int w, int h, List<Highlight> highlights, ImageParams.Lens lens, double stopsDown) {
        double cx = w / 2.0, cy = h / 2.0, halfDiag = Math.hypot(cx, cy);
        int blades = Math.max(5, lens.blades());
        int style = lens.bokeh();
        boolean round = lens.rounded() || stopsDown < 0.17;
        double barrel = Math.max(0, 1 - stopsDown / 2);
        double budget = 6.0 * w * h; // pixels of disc area drawn at most (the brightest highlights come first)
        for (Highlight hl : highlights) {
            float r = hl.r;
            float area = (float) (Math.PI * r * r);
            if ((budget -= area) < 0) break;
            // the extracted light spread over the disc, with a slightly brighter rim (mean rim factor ~1.07)
            float k = 1f / area / 1.07f;
            double ox = hl.x - cx, oy = hl.y - cy;
            double dist = Math.hypot(ox, oy) / halfDiag;
            double clipShift = r * 1.2 * dist * barrel; // second circle shifted towards the centre
            double ux = dist > 1e-6 ? -ox / Math.hypot(ox, oy) : 0, uy = dist > 1e-6 ? -oy / Math.hypot(ox, oy) : 0;
            // swirl: discs squeezed along the radius into ovals that line up on circles around the centre
            double squeeze = style == ImageParams.BOKEH_SWIRL ? 1 - 0.7 * Math.min(1, dist * 1.2) * barrel : 1;
            int x0 = (int) Math.floor(hl.x - r), x1 = (int) Math.ceil(hl.x + r);
            int y0 = (int) Math.floor(hl.y - r), y1 = (int) Math.ceil(hl.y + r);
            for (int y = Math.max(0, y0); y <= Math.min(h - 1, y1); y++) {
                for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
                    double dx = x - hl.x, dy = y - hl.y;
                    double rr = Math.hypot(dx, dy);
                    double edge = round ? r : polygonRadius(Math.atan2(dy, dx), blades, r);
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
                    img[j] += hl.er * k * rim;
                    img[j + 1] += hl.eg * k * rim;
                    img[j + 2] += hl.eb * k * rim;
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
        // a hand-held smear is not a straight line: the path wanders a little to the side
        double px = -dy / len, py = dx / len, wobble = 0.12 * len, phase = (len * 7.3) % (2 * Math.PI);
        for (int t = 0; t < taps; t++) {
            double f = taps == 1 ? 0 : (double) t / (taps - 1) - 0.5;
            double side = len >= 3 ? wobble * Math.sin(2 * Math.PI * 1.3 * (f + 0.5) + phase) : 0;
            float sx = (float) (dx * f + px * side), sy = (float) (dy * f + py * side);
            java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0; x < w; x++) {
                    int j = (y * w + x) * 3;
                    bilinearAdd(img, w, h, x + sx, y + sy, out, j, inv);
                }
            });
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
        return rollingShutter(img, w, h, skewPx, 0);
    }

    /**
     * Electronic shutter: rows are read top to bottom, so a pan shears the picture and a tilt stretches (tilting
     * down: the scene moves up while the lower rows are read) or squeezes it.
     */
    static float[] rollingShutter(float[] img, int w, int h, double skewPx, double skewY) {
        if (Math.abs(skewPx) < 0.5 && Math.abs(skewY) < 0.5) {
            return img;
        }
        float[] out = new float[img.length];
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            double t = (double) y / h - 0.5;
            float shift = (float) (skewPx * t), shiftY = (float) (skewY * t);
            for (int x = 0; x < w; x++) {
                bilinearAdd(img, w, h, x + shift, y + shiftY, out, (y * w + x) * 3, 1f);
            }
        });
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
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
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
        });
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
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
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
        });
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
        // angles beyond the rendered cap are folded into the outer zone only: the centre keeps its true scale
        double knee = Math.min(render, cover) * 0.6;
        double expo = cover > render ? (cover - knee) / (render - knee) : 1;
        double tanRender = Math.tan(render);
        double caScale = l.caPx() / halfDiag;
        float[] out = new float[img.length];
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
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
                double phi = theta;
                if (cover > render && theta > knee) {
                    double u = Math.min(1, (theta - knee) / (cover - knee));
                    phi = knee + (render - knee) * (1 - Math.pow(1 - u, expo));
                }
                phi = Math.min(phi, Math.PI / 2 - 1e-3);
                double srcR = Math.tan(phi) / tanRender * halfDiag;
                double k = rPx > 1e-6 ? srcR / rPx : (pxPerMm * focal > 0 ? halfDiag / (tanRender * focal * pxPerMm) : 1);
                double[] scale = {k * (1 + caScale), k, k * (1 - caScale)};
                for (int c = 0; c < 3; c++) {
                    float sx = (float) (cx + nx * scale[c]), sy = (float) (cy + ny * scale[c]);
                    out[j + c] = sampleChannel(img, w, h, sx, sy, c) * fall;
                }
            }
        });
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

    /** Corner light fall-off (EV) at this aperture: strongest wide open, mostly gone two stops down. */
    public static double vignetteEv(ImageParams p) {
        ImageParams.Lens l = p.lens();
        // the optical part goes away stopping down; the natural cos^4 fall-off of wide angles stays
        double residual = Math.max(0.08, Math.min(0.35, (35 - p.focus().focalMm()) / 20 * 0.35));
        return l.vignettingEv() * Math.max(residual, 1 - stopsDown(p) * 0.45);
    }

    /**
     * In-camera lens corrections for the JPEG (the RAW is written before them, uncorrected, as on a real camera):
     * about 85% of the distortion, 80% of the lateral colour and half the vignetting are taken out.
     */
    public static float[] correctLens(float[] src, int w, int h, ImageParams p) {
        ImageParams.Lens l = p.lens();
        final float[] img = src;
        double vig = vignetteEv(p) * 0.5;
        if (vig > 0.01) {
            vignette(img, w, h, -vig);
        }
        if (l.projection() == 0 && (Math.abs(l.distortion()) > 1e-4 || Math.abs(l.caPx()) > 0.3)) {
            ImageParams.Lens inverse = new ImageParams.Lens(l.vignettingEv(), l.maxAperture(), l.sharpness(),
                    -0.85 * l.distortion(), -0.8 * l.caPx(), 1, l.blades(), l.rounded(), l.flare(), l.bokeh(), 0, 0, 0, 1);
            return lensGeometry(img, w, h, inverse);
        }
        return img;
    }

    /** Light fall-off towards the corners: {@code ev} lost at the corner (negative: brightened, the correction). */
    static void vignette(float[] img, int w, int h, double ev) {
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, norm = cx * cx + cy * cy;
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                double r2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / norm;
                float m = (float) vignetteGain(ev, r2);
                int j = (y * w + x) * 3;
                img[j] *= m;
                img[j + 1] *= m;
                img[j + 2] *= m;
            }
        });
    }

    /** Vignetting gain at r2 = squared distance from the centre over the squared half diagonal. */
    public static double vignetteGain(double ev, double r2) {
        return Math.pow(2, -ev * (r2 * r2 * 0.5 + r2 * 0.5));
    }

    static void lens(float[] img, int w, int h, ImageParams p) {
        ImageParams.Lens l = p.lens();
        double vig = vignetteEv(p);
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, norm = cx * cx + cy * cy;
        if (vig > 0.01) {
            vignette(img, w, h, vig);
        }
        // Diffraction: the Airy pattern grows with the f-number and the wavelength (red spreads more than blue).
        double pitchMm = p.focus().sensorWidthMm() / w;
        double[] sigmaPx = new double[3];
        for (int c = 0; c < 3; c++) {
            sigmaPx[c] = Optics.diffractionSigmaMm(p.focus().fNumber(), Optics.CHANNEL_WAVELENGTH_MM[c]) / Math.max(1e-6, pitchMm);
        }
        diffraction(img, w, h, sigmaPx);
        // corners are softer wide open (coma, astigmatism, field curvature)
        double corner = Math.max(0, 1 - stopsDown(p) / 2.5) * Math.max(0, 1.05 - l.sharpness()) * 2.5;
        if (corner > 0.02) {
            float[] soft = box3(img, w, h);
            float edge = (float) Math.min(1, corner);
            java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0; x < w; x++) {
                    double r2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / norm;
                    float t = Math.min(1, edge * (float) (r2 * r2));
                    if (t <= 0) continue;
                    int j = (y * w + x) * 3;
                    for (int c = 0; c < 3; c++) img[j + c] += (soft[j + c] - img[j + c]) * t;
                }
            });
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

    /**
     * Gaussian blur per channel (separable), in place. A sigma below ~0.35 px is lost in the pixel itself (the
     * render already averages that much), so only the part beyond it is applied.
     */
    static void diffraction(float[] img, int w, int h, double[] sigmaPx) {
        for (int c = 0; c < 3; c++) {
            double s2 = sigmaPx[c] * sigmaPx[c] - 0.35 * 0.35;
            if (s2 <= 0.01) continue;
            double sigma = Math.sqrt(s2);
            int rad = Math.max(1, (int) Math.ceil(sigma * 3));
            float[] k = new float[rad * 2 + 1];
            float sum = 0;
            for (int i = -rad; i <= rad; i++) {
                k[i + rad] = (float) Math.exp(-i * i / (2 * s2));
                sum += k[i + rad];
            }
            for (int i = 0; i < k.length; i++) k[i] /= sum;
            final int ch = c;
            float[] tmp = new float[w * h];
            java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0; x < w; x++) {
                    float a = 0;
                    for (int i = -rad; i <= rad; i++) {
                        int xx = Math.max(0, Math.min(w - 1, x + i));
                        a += k[i + rad] * img[(y * w + xx) * 3 + ch];
                    }
                    tmp[y * w + x] = a;
                }
            });
            java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0; x < w; x++) {
                    float a = 0;
                    for (int i = -rad; i <= rad; i++) {
                        int yy = Math.max(0, Math.min(h - 1, y + i));
                        a += k[i + rad] * tmp[yy * w + x];
                    }
                    img[(y * w + x) * 3 + ch] = a;
                }
            });
        }
    }

    private static float[] box3(float[] img, int w, int h) {
        float[] out = new float[img.length];
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
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
        });
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
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                double d = Math.hypot(x - sx, y - sy) / diag;
                float g = (float) (strength * (0.012 + 0.06 * Math.exp(-d * 6)));
                int j = (y * w + x) * 3;
                img[j] += g * 1.0f;
                img[j + 1] += g * 0.95f;
                img[j + 2] += g * 0.85f;
            }
        });
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

    /** Dark-current variance relative to the read noise: equal after 30 s at 20 °C. */
    static final double DARK_K = 8e-6;

    /**
     * Sensor noise. Independent sources add in quadrature: photon (shot) noise grows with sqrt(signal x
     * amplification), read noise and the shot noise of the dark current with the amplification. Some read noise is
     * common to a whole row (banding in lifted shadows, more on sensors with less dynamic range). Downsampling a
     * high-resolution sensor averages noise, which is why resolutionScale matters. Long exposures add hot pixels
     * unless a dark frame was subtracted.
     */
    static void noise(float[] img, int w, int h, ImageParams p) {
        ImageParams.Sensor s = p.sensor();
        if (s.noiseLuma() <= 0 && s.noiseChroma() <= 0) {
            return;
        }
        NoiseModel m = noiseModel(s);
        SplittableRandom rnd = new SplittableRandom(p.seed());
        float[] row = new float[h];
        for (int y = 0; y < h; y++) row[y] = (float) (rnd.nextGaussian() * m.rowSigma());
        for (int i = 0, px = 0; i < img.length; i += 3, px++) {
            float luma = 0.2126f * img[i] + 0.7152f * img[i + 1] + 0.0722f * img[i + 2];
            double sigma = m.sigma(luma);
            double common = rnd.nextGaussian() * sigma + row[px / w];
            img[i] = (float) Math.max(0, img[i] + common + rnd.nextGaussian() * sigma * m.chroma());
            img[i + 1] = (float) Math.max(0, img[i + 1] + common + rnd.nextGaussian() * sigma * m.chroma());
            img[i + 2] = (float) Math.max(0, img[i + 2] + common + rnd.nextGaussian() * sigma * m.chroma());
        }
        hotPixels(img, w, h, s, m.amp());
    }

    /**
     * Sensor noise of one exposure. sigma(luma) = sqrt(luma * shot + read2) * avg is the noise common to the three
     * channels, chroma the share added per channel, rowSigma the banding common to a row.
     */
    public record NoiseModel(double amp, double shot, double read2, double chroma, double avg, double rowSigma) {
        public double sigma(double luma) {
            return Math.sqrt(Math.max(luma, 0) * shot + read2) * avg;
        }
    }

    public static NoiseModel noiseModel(ImageParams.Sensor s) {
        // amplification is the ISO alone: exposing brighter (to the right) gives a cleaner picture, not a noisier one
        double amp = Math.max(0.25, s.isoGain());
        // a smaller sensor gathers less light for the same framing; a higher-DR sensor has less read noise
        double avg = Math.sqrt(Math.min(1, s.resolutionScale())) * s.areaFactor();
        double shotK = 6e-5 * s.noiseLuma();
        double readK = 1.2e-4 * s.noiseLuma() * Math.pow(2, Math.max(-2, Math.min(2.5, 13.5 - s.dynamicRangeEv())));
        double chromaK = 0.6 * s.noiseChroma();
        ImageParams.Dark dark = s.dark();
        // the dark frame's own noise is subtracted along with its pattern: the random part counts twice
        double darkVar = dark.seconds() > 0
                ? amp * amp * shotK * DARK_K * dark.seconds() * dark.thermal() * (dark.subtracted() ? 2 : 1) : 0;
        double read2 = amp * readK * amp * readK + darkVar;
        // row banding: a row averages many sensor rows, so it fades more slowly with downsampling than pixel noise
        double band = Math.max(0.03, Math.min(0.35, (13 - s.dynamicRangeEv()) * 0.12));
        double rowSigma = amp * readK * band * Math.pow(Math.min(1, s.resolutionScale()), 0.25) * s.areaFactor();
        return new NoiseModel(amp, amp * shotK, read2, chromaK, avg, rowSigma);
    }

    /**
     * Hot pixels: about one sensor pixel in 100 000 leaks charge far faster than the rest. Their places are fixed
     * for a sensor (seeded by it), their brightness grows with exposure time, temperature and ISO; each sits under
     * one colour filter, so it shows as a red, green or blue dot. A dark-frame subtraction removes them. In a
     * downsampled picture a hot pixel is a fraction of an output pixel, so it shows fainter.
     */
    static void hotPixels(float[] img, int w, int h, ImageParams.Sensor s, double amp) {
        ImageParams.Dark dark = s.dark();
        if (dark.seconds() < 0.5 || dark.subtracted()) return;
        double scale = Math.min(1, Math.max(1e-6, s.resolutionScale()));
        int n = (int) Math.min(20_000, (double) w * h / scale * 1e-5);
        double k = amp * dark.seconds() * dark.thermal() * scale * 0.004;
        SplittableRandom hr = new SplittableRandom(dark.sensorSeed());
        for (int i = 0; i < n; i++) {
            double fx = hr.nextDouble(), fy = hr.nextDouble(), z = hr.nextGaussian();
            int filter = hr.nextInt(4); // RGGB
            double v = k * Math.exp(1.3 * z);
            if (v < 0.003) continue;
            int x = (int) (fx * w), y = (int) (fy * h);
            int c = filter == 0 ? 0 : filter == 3 ? 2 : 1;
            img[(y * w + x) * 3 + c] += (float) v;
        }
    }

    // ------------------------------------------------------------------ tone & colour

    /** Highlights compress smoothly towards white instead of clipping hard; more DR = longer shoulder. */
    public static void rollOff(float[] img, double dynamicRangeEv) {
        rollOffFrom(img, (float) rollOffKnee(dynamicRangeEv));
    }

    /** Highlight roll-off starting at {@code knee}. */
    public static void rollOffFrom(float[] img, float knee) {
        float span = 1 - knee;
        for (int i = 0; i < img.length; i++) {
            float v = img[i];
            if (v > knee) {
                img[i] = knee + span * (1 - (float) Math.exp(-(v - knee) / span));
            }
        }
    }

    /** Where the highlight shoulder starts (linear, JPEG white = 1). */
    public static double rollOffKnee(double dynamicRangeEv) {
        return Math.max(0.6, Math.min(0.9, 1.25 - dynamicRangeEv * 0.035));
    }

    /** White balance, then profile and tone curve (for callers that have no sensor data to clip). */
    public static void colour(float[] img, ImageParams.Colour c) {
        whiteBalance(img, c, Double.MAX_VALUE);
        tone(img, c);
    }

    /**
     * White balance multipliers on linear sensor data. Pixels that clipped in the sensor (any channel near
     * {@code saturation}) lost their colour: like a real camera's highlight reconstruction they go neutral
     * instead of turning cyan or magenta when one channel is scaled down.
     */
    public static void whiteBalance(float[] img, ImageParams.Colour c, double saturation) {
        float wr = (float) c.wbR(), wg = (float) c.wbG(), wb = (float) c.wbB();
        float lo = (float) (saturation * 0.8), span = (float) Math.max(1e-6, saturation - lo);
        for (int i = 0; i < img.length; i += 3) {
            float pre = Math.max(img[i], Math.max(img[i + 1], img[i + 2]));
            float r = img[i] * wr, g = img[i + 1] * wg, b = img[i + 2] * wb;
            if (pre > lo) {
                float t = clamp01((pre - lo) / span);
                t = t * t * (3 - 2 * t);
                float top = Math.max(r, Math.max(g, b));
                r += (top - r) * t;
                g += (top - g) * t;
                b += (top - b) * t;
            }
            img[i] = r;
            img[i + 1] = g;
            img[i + 2] = b;
        }
    }

    /**
     * Colour profile: tint (fading out towards white, so whites stay neutral), saturation, monochrome, contrast
     * curve and shadow lift.
     */
    public static void tone(float[] img, ImageParams.Colour c) {
        float tr = (float) c.tintR(), tg = (float) c.tintG(), tb = (float) c.tintB();
        boolean tinted = tr != 1 || tg != 1 || tb != 1;
        float sat = (float) c.saturation();
        float lift = (float) c.shadowLift();
        double contrast = c.contrast();
        for (int i = 0; i < img.length; i += 3) {
            float r = img[i], g = img[i + 1], b = img[i + 2];
            if (tinted) {
                float l0 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                float k = 1 - clamp01((l0 - 0.35f) / 0.6f); // full tint in shadows and mid-tones, none at white
                r *= 1 + (tr - 1) * k;
                g *= 1 + (tg - 1) * k;
                b *= 1 + (tb - 1) * k;
            }
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

    /**
     * Contrast as an S-curve pivoting on middle grey (0.18) in log space, with fixed end points: black stays
     * black and white stays white (a flat profile still reaches white, a contrasty one keeps its shoulder).
     */
    static float curve(float v, double contrast, float lift) {
        v = Math.max(v, 0);
        if (contrast != 1 && v > 0) {
            double x = Math.log(Math.max(v, 1e-6) / 0.18) / Math.log(2), top = Math.log(1 / 0.18) / Math.log(2);
            double c = Math.max(0.5, Math.min(1.45, contrast));
            double y;
            if (x <= 0) {
                y = x * c;
            } else {
                double u = Math.min(x, top) / top;
                y = x * (c + (1 - c) * u * u); // slope c at grey, reaches white exactly at white
                if (x > top) y = top + (x - top) * 0.5;
            }
            v = (float) Math.min(1, 0.18 * Math.pow(2, y));
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
