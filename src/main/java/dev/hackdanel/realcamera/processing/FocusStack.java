package dev.hackdanel.realcamera.processing;

import java.util.stream.IntStream;

/**
 * Focus stacking: frames of one scene focused at different distances merged into one picture sharp throughout, as
 * stacking software does for macro and landscapes.
 *
 * <ol>
 *   <li>Alignment: each frame against the first, by a scale about the centre (focus breathing changes the framing)
 *       and a shift (a hand-held camera moves), searched coarse to fine on small luminance images.</li>
 *   <li>Fusion: Laplacian pyramids; at every level and pixel the frame with the most local detail wins, the base
 *       level is averaged. Frames are added one at a time, so only the running result is kept in memory.</li>
 * </ol>
 * Images are linear RGB, 3 floats per pixel. Pure Java, no game classes.
 */
public final class FocusStack {
    private FocusStack() {}

    /** Alignment of a frame to the reference: frame(c + scale (p - c) + (dx, dy)) shows reference(p). */
    public record Alignment(double scale, double dx, double dy) {
        public static final Alignment NONE = new Alignment(1, 0, 0);
    }

    public static float[] luminance(float[] rgb, int w, int h) {
        float[] l = new float[w * h];
        for (int i = 0; i < l.length; i++) {
            l[i] = 0.2126f * rgb[i * 3] + 0.7152f * rgb[i * 3 + 1] + 0.0722f * rgb[i * 3 + 2];
        }
        return l;
    }

    /** Finds how a frame is scaled and shifted against the reference (both luminance, same size). */
    public static Alignment align(float[] ref, float[] frame, int w, int h) {
        // perceptual-ish values: the search should not be ruled by a few bright pixels
        int sw = Math.min(w, 384), sh = Math.max(8, Math.round((float) h * sw / w));
        float[] r3 = gamma(resize(ref, w, h, sw, sh)), f3 = gamma(resize(frame, w, h, sw, sh));
        int[][] sizes = {{sw / 4, sh / 4}, {sw / 2, sh / 2}, {sw, sh}};
        double bestS = 1, bestX = 0, bestY = 0;
        for (int lv = 0; lv < 3; lv++) {
            int lw = Math.max(8, sizes[lv][0]), lh = Math.max(8, sizes[lv][1]);
            float[] r = lv == 2 ? r3 : resize(r3, sw, sh, lw, lh), f = lv == 2 ? f3 : resize(f3, sw, sh, lw, lh);
            double k = lv == 0 ? 1 : 2; // shifts double from one level to the next
            bestX *= k;
            bestY *= k;
            double sRange = lv == 0 ? 0.04 : lv == 1 ? 0.004 : 0.001, sStep = lv == 0 ? 0.005 : lv == 1 ? 0.001 : 0.00025;
            int tRange = lv == 0 ? Math.max(2, lw / 12) : 2;
            double tStep = lv == 0 ? 1 : 0.5;
            double cs = bestS, cx = bestX, cy = bestY, best = Double.MAX_VALUE;
            for (double s = cs - sRange; s <= cs + sRange + 1e-9; s += sStep) {
                for (double ty = cy - tRange; ty <= cy + tRange + 1e-9; ty += tStep) {
                    for (double tx = cx - tRange; tx <= cx + tRange + 1e-9; tx += tStep) {
                        double e = error(r, f, lw, lh, s, tx, ty);
                        if (e < best) {
                            best = e;
                            bestS = s;
                            bestX = tx;
                            bestY = ty;
                        }
                    }
                }
            }
        }
        double toFull = (double) w / sw;
        return new Alignment(bestS, bestX * toFull, bestY * toFull);
    }

    private static float[] gamma(float[] l) {
        float[] o = new float[l.length];
        for (int i = 0; i < l.length; i++) o[i] = (float) Math.sqrt(Math.max(0, l[i]));
        return o;
    }

    /** Mean squared difference over the middle of the frame (edges may fall outside after the shift). */
    private static double error(float[] r, float[] f, int w, int h, double s, double dx, double dy) {
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, sum = 0;
        int n = 0;
        int x0 = w / 8, x1 = w - w / 8, y0 = h / 8, y1 = h - h / 8;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                double fx = cx + s * (x - cx) + dx, fy = cy + s * (y - cy) + dy;
                if (fx < 0 || fy < 0 || fx > w - 1.001 || fy > h - 1.001) continue;
                double d = sample(f, w, h, fx, fy) - r[y * w + x];
                sum += d * d;
                n++;
            }
        }
        return n == 0 ? Double.MAX_VALUE : sum / n;
    }

    private static float sample(float[] a, int w, int h, double fx, double fy) {
        int x0 = (int) fx, y0 = (int) fy;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float tx = (float) (fx - x0), ty = (float) (fy - y0);
        float top = a[y0 * w + x0] + (a[y0 * w + x1] - a[y0 * w + x0]) * tx;
        float bot = a[y1 * w + x0] + (a[y1 * w + x1] - a[y1 * w + x0]) * tx;
        return top + (bot - top) * ty;
    }

    /** Box-filtered resize of a single-channel image. */
    static float[] resize(float[] a, int w, int h, int nw, int nh) {
        float[] o = new float[nw * nh];
        double sx = (double) w / nw, sy = (double) h / nh;
        IntStream.range(0, nh).parallel().forEach(y -> {
            int ya = (int) (y * sy), yb = Math.max(ya + 1, (int) Math.min(h, (y + 1) * sy));
            for (int x = 0; x < nw; x++) {
                int xa = (int) (x * sx), xb = Math.max(xa + 1, (int) Math.min(w, (x + 1) * sx));
                double sum = 0;
                for (int yy = ya; yy < yb; yy++) {
                    for (int xx = xa; xx < xb; xx++) sum += a[yy * w + xx];
                }
                o[y * nw + x] = (float) (sum / ((yb - ya) * (xb - xa)));
            }
        });
        return o;
    }

    /** The frame resampled into the reference's geometry (outside its edges: the nearest edge pixel). */
    public static float[] warp(float[] rgb, int w, int h, Alignment a) {
        if (a.scale() == 1 && a.dx() == 0 && a.dy() == 0) return rgb;
        float[] out = new float[rgb.length];
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0;
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                double fx = Math.max(0, Math.min(w - 1.001, cx + a.scale() * (x - cx) + a.dx()));
                double fy = Math.max(0, Math.min(h - 1.001, cy + a.scale() * (y - cy) + a.dy()));
                int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
                float tx = (float) (fx - x0), ty = (float) (fy - y0);
                int o = (y * w + x) * 3;
                for (int c = 0; c < 3; c++) {
                    float p00 = rgb[(y0 * w + x0) * 3 + c], p10 = rgb[(y0 * w + x1) * 3 + c];
                    float p01 = rgb[(y1 * w + x0) * 3 + c], p11 = rgb[(y1 * w + x1) * 3 + c];
                    float top = p00 + (p10 - p00) * tx, bot = p01 + (p11 - p01) * tx;
                    out[o + c] = top + (bot - top) * ty;
                }
            }
        });
        return out;
    }

    /** Merges aligned frames one by one into a Laplacian pyramid that keeps the sharpest detail at each place. */
    public static final class Fuser {
        private final int w, h, levels;
        private final int[] lw, lh;
        private final float[][] lap, energy;
        private float[] base;
        private int count;

        public Fuser(int w, int h) {
            this.w = w;
            this.h = h;
            int n = 1;
            while (n < 8 && Math.min(w, h) >> n >= 16) n++;
            levels = n;
            lw = new int[levels];
            lh = new int[levels];
            for (int k = 0; k < levels; k++) {
                lw[k] = Math.max(1, (w + (1 << k) - 1) >> k);
                lh[k] = Math.max(1, (h + (1 << k) - 1) >> k);
            }
            lap = new float[levels - 1][];
            energy = new float[levels - 1][];
        }

        public void add(float[] rgb) {
            float[][] g = gaussian(rgb);
            for (int k = 0; k < levels - 1; k++) {
                float[] l = laplacian(g[k], g[k + 1], k);
                float[] e = detail(l, lw[k], lh[k]);
                if (lap[k] == null) {
                    lap[k] = l;
                    energy[k] = e;
                    continue;
                }
                float[] dst = lap[k], best = energy[k];
                int lvl = k;
                IntStream.range(0, lh[lvl]).parallel().forEach(y -> {
                    for (int x = 0; x < lw[lvl]; x++) {
                        int i = y * lw[lvl] + x;
                        if (e[i] > best[i]) {
                            best[i] = e[i];
                            dst[i * 3] = l[i * 3];
                            dst[i * 3 + 1] = l[i * 3 + 1];
                            dst[i * 3 + 2] = l[i * 3 + 2];
                        }
                    }
                });
            }
            float[] top = g[levels - 1];
            if (base == null) {
                base = top.clone();
            } else {
                for (int i = 0; i < base.length; i++) base[i] += (top[i] - base[i]) / (count + 1);
            }
            count++;
        }

        public float[] result() {
            float[] img = base.clone();
            for (int k = levels - 2; k >= 0; k--) {
                float[] up = upsample(img, lw[k + 1], lh[k + 1], lw[k], lh[k]);
                float[] l = lap[k];
                for (int i = 0; i < up.length; i++) up[i] += l[i];
                img = up;
            }
            for (int i = 0; i < img.length; i++) img[i] = Math.max(0, img[i]);
            return img;
        }

        private float[][] gaussian(float[] rgb) {
            float[][] g = new float[levels][];
            g[0] = rgb;
            for (int k = 1; k < levels; k++) g[k] = downsample(g[k - 1], lw[k - 1], lh[k - 1], lw[k], lh[k]);
            return g;
        }

        private float[] laplacian(float[] fine, float[] coarse, int k) {
            float[] up = upsample(coarse, lw[k + 1], lh[k + 1], lw[k], lh[k]);
            for (int i = 0; i < up.length; i++) up[i] = fine[i] - up[i];
            return up;
        }

        /** Local detail: the luminance of the Laplacian, absolute, averaged over 3x3. */
        private static float[] detail(float[] l, int w, int h) {
            float[] a = new float[w * h];
            for (int i = 0; i < a.length; i++) {
                a[i] = Math.abs(0.2126f * l[i * 3] + 0.7152f * l[i * 3 + 1] + 0.0722f * l[i * 3 + 2]);
            }
            float[] o = new float[w * h];
            IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0; x < w; x++) {
                    float s = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= h) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= w) continue;
                            s += a[yy * w + xx];
                            n++;
                        }
                    }
                    o[y * w + x] = s / n;
                }
            });
            return o;
        }
    }

    /** 2x2 average (RGB). */
    static float[] downsample(float[] a, int w, int h, int nw, int nh) {
        float[] o = new float[nw * nh * 3];
        IntStream.range(0, nh).parallel().forEach(y -> {
            for (int x = 0; x < nw; x++) {
                for (int c = 0; c < 3; c++) {
                    float s = 0;
                    int n = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        int yy = y * 2 + dy;
                        if (yy >= h) continue;
                        for (int dx = 0; dx < 2; dx++) {
                            int xx = x * 2 + dx;
                            if (xx >= w) continue;
                            s += a[(yy * w + xx) * 3 + c];
                            n++;
                        }
                    }
                    o[(y * nw + x) * 3 + c] = s / n;
                }
            }
        });
        return o;
    }

    /** Bilinear upsample (RGB) to (w, h). */
    static float[] upsample(float[] a, int sw, int sh, int w, int h) {
        float[] o = new float[w * h * 3];
        IntStream.range(0, h).parallel().forEach(y -> {
            float fy = Math.max(0, Math.min(sh - 1, (y + 0.5f) / 2 - 0.5f));
            int y0 = (int) fy, y1 = Math.min(sh - 1, y0 + 1);
            float ty = fy - y0;
            for (int x = 0; x < w; x++) {
                float fx = Math.max(0, Math.min(sw - 1, (x + 0.5f) / 2 - 0.5f));
                int x0 = (int) fx, x1 = Math.min(sw - 1, x0 + 1);
                float tx = fx - x0;
                for (int c = 0; c < 3; c++) {
                    float p00 = a[(y0 * sw + x0) * 3 + c], p10 = a[(y0 * sw + x1) * 3 + c];
                    float p01 = a[(y1 * sw + x0) * 3 + c], p11 = a[(y1 * sw + x1) * 3 + c];
                    float top = p00 + (p10 - p00) * tx, bot = p01 + (p11 - p01) * tx;
                    o[(y * w + x) * 3 + c] = top + (bot - top) * ty;
                }
            }
        });
        return o;
    }
}
