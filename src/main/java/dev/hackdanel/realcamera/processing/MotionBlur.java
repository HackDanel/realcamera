package dev.hackdanel.realcamera.processing;

import java.util.ArrayList;
import java.util.List;

/**
 * Smear during the exposure when not everything moves alike. The frame is rendered once, at the middle of the
 * exposure; every point is then spread along the path its image takes while the shutter is open:
 * <ul>
 *   <li>the whole picture moves with hand shake and with the photographer turning the camera;</li>
 *   <li>a camera that moves (a boat, a cart, a horse) adds parallax: near things move across the frame faster than
 *   far ones;</li>
 *   <li>moving entities add their own motion. A pan that follows a running animal cancels it: the animal stays sharp
 *   while the background streaks, as with real panning.</li>
 * </ul>
 * Moving entities are separate layers: the background behind them is filled in from around them, each entity is
 * smeared with its own coverage (it turns see-through where it only passed for part of the time) and laid over it.
 * A flash is a short pulse: its light is placed sharp at the moment it fires (start or end of the exposure).
 * Pure Java (no game classes).
 */
final class MotionBlur {
    private static final int MAX_TAPS = 32;

    private MotionBlur() {}

    /** True if anything smears by a visible amount. */
    static boolean moves(ImageParams.Motion m) {
        return Math.hypot(m.blurX(), m.blurY()) >= 0.7 || m.local();
    }

    static float[] apply(float[] img, float[] flash, float[] depth, int w, int h, ImageParams p) {
        ImageParams.Motion m = p.motion();
        double gx = m.blurX(), gy = m.blurY();
        double flashT = p.extras().flashRear() ? 0.5 : -0.5;
        if (!m.local() || depth == null) {
            if (Math.hypot(gx, gy) < 0.7) {
                if (flash != null) addInPlace(img, flash);
                return img;
            }
            float[] out = gather(img, w, h, new Path(gx, gy, -0.5, 0.5, MAX_TAPS));
            if (flash != null) {
                addInPlace(out, gather(flash, w, h, new Path(gx, gy, flashT, flashT, 1)));
            }
            return out;
        }
        Scene sc = new Scene(depth, w, h, p);
        Path ambient = new Path(gx, gy, -0.5, 0.5, MAX_TAPS);
        float[] out = sc.render(img, ambient);
        if (flash != null) {
            addInPlace(out, sc.render(flash, new Path(gx, gy, flashT, flashT, 1)));
        }
        return out;
    }

    private static void addInPlace(float[] a, float[] b) {
        for (int i = 0; i < a.length; i++) a[i] += b[i];
    }

    /**
     * The sampling path: a point whose image moves by (vx, vy) pixels over the exposure is seen at t * v for t from
     * t0 to t1 (0 = mid-exposure), plus the sideways wander of hand shake (from the global smear g).
     */
    record Path(double gx, double gy, double t0, double t1, int maxTaps) {
        int taps(double len) {
            if (t0 == t1) return 1;
            return (int) Math.max(1, Math.min(maxTaps, Math.ceil(len * (t1 - t0)) + 1));
        }

        double t(int i, int n) {
            return n == 1 ? (t0 + t1) / 2 : t0 + (t1 - t0) * i / (n - 1);
        }

        /** Sideways wander of a hand-held smear at time t (pixels, along the perpendicular of g). */
        double side(double t) {
            double len = Math.hypot(gx, gy);
            if (len < 3) return 0;
            double phase = (len * 7.3) % (2 * Math.PI);
            return 0.12 * len * Math.sin(2 * Math.PI * 1.3 * (t + 0.5) + phase);
        }

        double sideX() {
            double len = Math.hypot(gx, gy);
            return len < 1e-9 ? 0 : -gy / len;
        }

        double sideY() {
            double len = Math.hypot(gx, gy);
            return len < 1e-9 ? 0 : gx / len;
        }
    }

    /** Uniform smear of a whole image along the global path (no parallax, no movers). */
    private static float[] gather(float[] src, int w, int h, Path path) {
        float[] out = new float[src.length];
        int n = path.taps(Math.hypot(path.gx(), path.gy()));
        double sx = path.sideX(), sy = path.sideY();
        float inv = 1f / n;
        for (int i = 0; i < n; i++) {
            double t = path.t(i, n), side = path.side(t);
            float ox = (float) (path.gx() * t + sx * side), oy = (float) (path.gy() * t + sy * side);
            java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0; x < w; x++) {
                    addSample(src, w, h, x - ox, y - oy, out, (y * w + x) * 3, inv);
                }
            });
        }
        return out;
    }

    /** Bilinear sample of src at (fx, fy) (clamped to the frame) added to dst[j..j+2] with a weight. */
    static void addSample(float[] src, int w, int h, float fx, float fy, float[] dst, int j, float weight) {
        fx = Math.max(0, Math.min(w - 1.001f, fx));
        fy = Math.max(0, Math.min(h - 1.001f, fy));
        int x0 = (int) fx, y0 = (int) fy;
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        float tx = fx - x0, ty = fy - y0;
        float w00 = (1 - tx) * (1 - ty) * weight, w10 = tx * (1 - ty) * weight;
        float w01 = (1 - tx) * ty * weight, w11 = tx * ty * weight;
        int a = (y0 * w + x0) * 3, b = (y0 * w + x1) * 3, c = (y1 * w + x0) * 3, d = (y1 * w + x1) * 3;
        for (int k = 0; k < 3; k++) {
            dst[j + k] += src[a + k] * w00 + src[b + k] * w10 + src[c + k] * w01 + src[d + k] * w11;
        }
    }

    /** One moving entity found in the frame: its pixels, image smear and how its image moves per second. */
    private static final class Mover {
        final int id;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        double sumX, sumY, sumZ;
        int count;
        double vx, vy; // smear over the exposure, px (camera motion included)
        double rateX, rateY; // own image motion, px/s (for the rolling shutter)
        double depth;

        Mover(int id) {
            this.id = id;
        }
    }

    /** The frame split into a static background and moving entities. */
    private static final class Scene {
        final float[] depth;
        final int w, h;
        final ImageParams.Motion m;
        final double fpx, cx, cy, seconds;
        final double vcx, vcy, vcz; // velocity of static points relative to the camera, m/s
        final boolean parallax;
        final int[] owner;
        final List<Mover> movers = new ArrayList<>();

        Scene(float[] depth, int w, int h, ImageParams p) {
            this.depth = depth;
            this.w = w;
            this.h = h;
            this.m = p.motion();
            this.fpx = p.focus().focalMm() * w / p.focus().sensorWidthMm();
            this.cx = (w - 1) / 2.0;
            this.cy = (h - 1) / 2.0;
            this.seconds = m.seconds();
            this.vcx = -m.camVx();
            this.vcy = -m.camVy();
            this.vcz = -m.camVz();
            this.parallax = Math.abs(vcx) + Math.abs(vcy) + Math.abs(vcz) > 0.05;
            this.owner = findMovers();
        }

        /** Image velocity (px/s) of a point at pixel (x, y), depth z (m), moving at (ux, uy, uz) m/s relative to the camera. */
        double[] imageRate(double ux, double uy, double uz, double x, double y, double z) {
            z = Math.max(0.05, z);
            return new double[]{fpx * ux / z - (x - cx) * uz / z, -fpx * uy / z - (y - cy) * uz / z};
        }

        private int[] findMovers() {
            ImageParams.MotionField f = m.field();
            int[] own = new int[w * h];
            java.util.Arrays.fill(own, -1);
            if (f == null || f.movers().isEmpty()) return own;
            int gw = f.gw(), gh = f.gh();
            short[] cells = f.cells();
            List<ImageParams.Mover> specs = f.movers();
            // a pixel belongs to a mover if its ray cell (or a neighbour: the grid is coarse) hit it and the pixel's
            // depth lies within the entity's box (the box is bigger than the mob: the ground beside it stays put)
            java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
                int gy = Math.min(gh - 1, y * gh / h);
                for (int x = 0; x < w; x++) {
                    int gxc = Math.min(gw - 1, x * gw / w);
                    float d = depth[y * w + x];
                    int best = -1;
                    float bestNear = Float.MAX_VALUE;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = gy + dy;
                        if (yy < 0 || yy >= gh) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = gxc + dx;
                            if (xx < 0 || xx >= gw) continue;
                            int id = cells[yy * gw + xx];
                            if (id < 0 || id == best) continue;
                            ImageParams.Mover mv = specs.get(id);
                            if (d >= mv.near() - 0.25f && d <= mv.far() + 0.25f && mv.near() < bestNear) {
                                best = id;
                                bestNear = mv.near();
                            }
                        }
                    }
                    own[y * w + x] = best;
                }
            });
            Mover[] byId = new Mover[specs.size()];
            for (int i = 0; i < own.length; i++) {
                int id = own[i];
                if (id < 0) continue;
                Mover mv = byId[id];
                if (mv == null) mv = byId[id] = new Mover(id);
                int x = i % w, y = i / w;
                mv.minX = Math.min(mv.minX, x);
                mv.maxX = Math.max(mv.maxX, x);
                mv.minY = Math.min(mv.minY, y);
                mv.maxY = Math.max(mv.maxY, y);
                mv.sumX += x;
                mv.sumY += y;
                mv.sumZ += depth[i];
                mv.count++;
            }
            for (Mover mv : byId) {
                if (mv == null) continue;
                if (mv.count < 4) {
                    continue;
                }
                ImageParams.Mover spec = specs.get(mv.id);
                double x = mv.sumX / mv.count, y = mv.sumY / mv.count;
                mv.depth = mv.sumZ / mv.count;
                // the entity moves rigidly: one image velocity, taken at its centre
                double[] r = imageRate(spec.vx() + vcx, spec.vy() + vcy, spec.vz() + vcz, x, y, mv.depth);
                mv.rateX = r[0];
                mv.rateY = r[1];
                mv.vx = m.blurX() + r[0] * seconds;
                mv.vy = m.blurY() + r[1] * seconds;
                movers.add(mv);
            }
            // tiny or vanished movers go back to the background
            boolean[] keep = new boolean[specs.size()];
            for (Mover mv : movers) keep[mv.id] = true;
            for (int i = 0; i < own.length; i++) {
                if (own[i] >= 0 && !keep[own[i]]) own[i] = -1;
            }
            movers.sort((a, b) -> Double.compare(b.depth, a.depth)); // far to near: nearer ones are laid on top
            return own;
        }

        /** The smear of the static world at a pixel over the exposure (px). */
        double staticX(int x, int y) {
            if (!parallax) return m.blurX();
            return m.blurX() + imageRate(vcx, vcy, vcz, x, y, depth[y * w + x])[0] * seconds;
        }

        double staticY(int x, int y) {
            if (!parallax) return m.blurY();
            return m.blurY() + imageRate(vcx, vcy, vcz, x, y, depth[y * w + x])[1] * seconds;
        }

        float[] render(float[] src, Path path) {
            float[] background = movers.isEmpty() ? src : fill(src, owner, w, h);
            float[] out;
            if (!parallax) {
                out = gather(background, w, h, path);
            } else {
                out = gatherVarying(background, path);
            }
            for (Mover mv : movers) {
                overlay(out, src, mv, path);
            }
            return out;
        }

        /** Each pixel smeared along its own path (parallax of a moving camera). */
        private float[] gatherVarying(float[] src, Path path) {
            float[] out = new float[src.length];
            double sx = path.sideX(), sy = path.sideY();
            java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0; x < w; x++) {
                    double vx = staticX(x, y), vy = staticY(x, y);
                    int n = path.taps(Math.hypot(vx, vy));
                    float inv = 1f / n;
                    int j = (y * w + x) * 3;
                    for (int i = 0; i < n; i++) {
                        double t = path.t(i, n), side = path.side(t);
                        addSample(src, w, h, (float) (x - vx * t - sx * side), (float) (y - vy * t - sy * side), out, j, inv);
                    }
                }
            });
            return out;
        }

        /**
         * Lays a mover over the picture: its pixels (with their coverage) are smeared along its own path; where it
         * covered a pixel for part of the exposure the background shows through for the rest.
         */
        private void overlay(float[] out, float[] src, Mover mv, Path path) {
            int n = path.taps(Math.hypot(mv.vx, mv.vy));
            double sx = path.sideX(), sy = path.sideY();
            // electronic shutter: lower rows are read later, so the entity has moved on (leans or stretches)
            double roll = m.readoutS();
            double reach = Math.hypot(mv.vx, mv.vy) * Math.max(Math.abs(path.t0()), Math.abs(path.t1()))
                    + Math.abs(path.side(0)) + Math.hypot(path.gx(), path.gy()) * 0.12
                    + Math.hypot(mv.rateX, mv.rateY) * roll * 0.5 + 2;
            int x0 = Math.max(0, (int) Math.floor(mv.minX - reach)), x1 = Math.min(w - 1, (int) Math.ceil(mv.maxX + reach));
            int y0 = Math.max(0, (int) Math.floor(mv.minY - reach)), y1 = Math.min(h - 1, (int) Math.ceil(mv.maxY + reach));
            float inv = 1f / n;
            int id = mv.id;
            java.util.stream.IntStream.rangeClosed(y0, y1).parallel().forEach(y -> {
                float[] acc = new float[4];
                double rt = roll * ((double) y / h - 0.5);
                double rx = mv.rateX * rt, ry = mv.rateY * rt;
                for (int x = x0; x <= x1; x++) {
                    acc[0] = acc[1] = acc[2] = acc[3] = 0;
                    for (int i = 0; i < n; i++) {
                        double t = path.t(i, n), side = path.side(t);
                        sampleMasked(src, id, (float) (x - mv.vx * t - sx * side - rx),
                                (float) (y - mv.vy * t - sy * side - ry), acc, inv);
                    }
                    float a = acc[3];
                    if (a <= 1e-5f) continue;
                    int j = (y * w + x) * 3;
                    float keep = 1 - Math.min(1, a);
                    out[j] = out[j] * keep + acc[0];
                    out[j + 1] = out[j + 1] * keep + acc[1];
                    out[j + 2] = out[j + 2] * keep + acc[2];
                }
            });
        }

        /** Bilinear sample of the pixels that belong to mover {@code id}: colour times coverage, and coverage. */
        private void sampleMasked(float[] src, int id, float fx, float fy, float[] acc, float weight) {
            if (fx < 0 || fy < 0 || fx > w - 1 || fy > h - 1) return;
            int x0 = (int) fx, y0 = (int) fy;
            int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
            float tx = fx - x0, ty = fy - y0;
            int[] px = {y0 * w + x0, y0 * w + x1, y1 * w + x0, y1 * w + x1};
            float[] wt = {(1 - tx) * (1 - ty), tx * (1 - ty), (1 - tx) * ty, tx * ty};
            for (int k = 0; k < 4; k++) {
                if (owner[px[k]] != id || wt[k] == 0) continue;
                float a = wt[k] * weight;
                int j = px[k] * 3;
                acc[0] += src[j] * a;
                acc[1] += src[j + 1] * a;
                acc[2] += src[j + 2] * a;
                acc[3] += a;
            }
        }
    }

    /**
     * The picture with the pixels of moving entities replaced by what surrounds them (push-pull: averaged down a
     * pyramid of the remaining pixels, then interpolated back up into the holes): the background they uncover.
     */
    static float[] fill(float[] img, int[] owner, int w, int h) {
        List<float[]> colour = new ArrayList<>(), weight = new ArrayList<>();
        List<int[]> size = new ArrayList<>();
        float[] c0 = img.clone();
        float[] w0 = new float[w * h];
        for (int i = 0; i < w0.length; i++) w0[i] = owner[i] < 0 ? 1 : 0;
        colour.add(c0);
        weight.add(w0);
        size.add(new int[]{w, h});
        int lw = w, lh = h;
        while ((lw > 1 || lh > 1) && colour.size() < 24) {
            int nw = (lw + 1) / 2, nh = (lh + 1) / 2;
            float[] pc = colour.get(colour.size() - 1), pw = weight.get(weight.size() - 1);
            float[] cc = new float[nw * nh * 3], cw = new float[nw * nh];
            final int fw = lw, fh = lh;
            java.util.stream.IntStream.range(0, nh).parallel().forEach(y -> {
                for (int x = 0; x < nw; x++) {
                    float r = 0, g = 0, b = 0, ws = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        int yy = y * 2 + dy;
                        if (yy >= fh) continue;
                        for (int dx = 0; dx < 2; dx++) {
                            int xx = x * 2 + dx;
                            if (xx >= fw) continue;
                            int i = yy * fw + xx;
                            float k = pw[i];
                            r += pc[i * 3] * k;
                            g += pc[i * 3 + 1] * k;
                            b += pc[i * 3 + 2] * k;
                            ws += k;
                        }
                    }
                    int o = y * nw + x;
                    if (ws > 0) {
                        cc[o * 3] = r / ws;
                        cc[o * 3 + 1] = g / ws;
                        cc[o * 3 + 2] = b / ws;
                    }
                    cw[o] = Math.min(1, ws);
                }
            });
            colour.add(cc);
            weight.add(cw);
            size.add(new int[]{nw, nh});
            lw = nw;
            lh = nh;
        }
        for (int lv = colour.size() - 2; lv >= 0; lv--) {
            float[] fc = colour.get(lv), fwt = weight.get(lv), cc = colour.get(lv + 1);
            int fw = size.get(lv)[0], fh = size.get(lv)[1], cwid = size.get(lv + 1)[0], chei = size.get(lv + 1)[1];
            java.util.stream.IntStream.range(0, fh).parallel().forEach(y -> {
                for (int x = 0; x < fw; x++) {
                    int i = y * fw + x;
                    float k = fwt[i];
                    if (k >= 1) continue;
                    float sx = Math.max(0, Math.min(cwid - 1, (x + 0.5f) / 2 - 0.5f));
                    float sy = Math.max(0, Math.min(chei - 1, (y + 0.5f) / 2 - 0.5f));
                    int x0 = (int) sx, y0 = (int) sy, x1 = Math.min(cwid - 1, x0 + 1), y1 = Math.min(chei - 1, y0 + 1);
                    float tx = sx - x0, ty = sy - y0;
                    for (int c = 0; c < 3; c++) {
                        float a = cc[(y0 * cwid + x0) * 3 + c], b = cc[(y0 * cwid + x1) * 3 + c];
                        float d = cc[(y1 * cwid + x0) * 3 + c], e = cc[(y1 * cwid + x1) * 3 + c];
                        float up = (a + (b - a) * tx) * (1 - ty) + (d + (e - d) * tx) * ty;
                        fc[i * 3 + c] = fc[i * 3 + c] * k + up * (1 - k);
                    }
                    fwt[i] = 1;
                }
            });
        }
        return c0;
    }
}
