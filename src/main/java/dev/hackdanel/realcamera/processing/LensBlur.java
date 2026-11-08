package dev.hackdanel.realcamera.processing;

import java.util.stream.IntStream;

/**
 * Depth of field as a lens draws it: every point spreads into a disc shaped like the aperture ({@link BokehKernel}),
 * and nearer things cover farther ones.
 *
 * <p>The frame is split into layers by signed blur radius (+ in front of the focus plane). A pixel shares its weight
 * between the two layers whose radii bracket its own (geometric steps of 1.5x), so there are no steps between them.
 * Each layer is convolved with the disc of its radius at a reduced resolution (the disc is at most ~8 px there),
 * then the layers are composited back to front:
 * <ol>
 *   <li>background: the layers behind the focus plane, farthest first, each over the ones behind it (occluding by
 *       how much of the pixel its own pixels cover), normalised by the coverage so it continues behind whatever is
 *       nearer instead of darkening at its edge;</li>
 *   <li>in focus: sharp pixels over the background;</li>
 *   <li>foreground: the layers in front of the focus plane, the one nearest the focus plane first, each over the
 *       ones behind it, not normalised, so a blurred blade turns half-transparent at its edges and smoke right at the
 *       lens covers the slightly blurred ground between it and the subject;</li>
 *   <li>holes (pixels taken by the blurred foreground) filled with what the foreground layers behind them hold (the
 *       ground behind the smoke), then pull-push from around them: the background seen past a grass blade;</li>
 *   <li>the foreground over the filled picture.</li>
 * </ol>
 * Pure Java, no game classes.
 */
final class LensBlur {
    private LensBlur() {}

    /** Radius ratio between neighbouring layers. */
    static final double STEP = 1.5;
    /** Largest disc radius (pixels) convolved at full layer resolution; larger layers are downsampled. */
    static final double MAX_TAP_RADIUS = 8;
    private static final int TILE = 16;

    /**
     * @param img    linear RGB, 3 floats per pixel (not modified)
     * @param signed signed blur radius in pixels per pixel (+ nearer than the focus plane)
     * @return the blurred image
     */
    static float[] apply(float[] img, float[] signed, int w, int h, BokehKernel.Shape shape) {
        int n = w * h;
        float largest = 0;
        for (float s : signed) largest = Math.max(largest, Math.abs(s));
        if (largest < 0.75f) {
            return img.clone();
        }
        int levels = Math.max(1, (int) Math.ceil(Math.log(Math.max(1, largest)) / Math.log(STEP)) + 1);

        // which layers hold anything (far: index 0..levels-1, near likewise)
        boolean[] farUsed = new boolean[levels], nearUsed = new boolean[levels];
        for (float s : signed) {
            float a = Math.abs(s);
            if (a <= 0.5f) continue;
            double t = layerPos(a);
            int k0 = (int) Math.floor(t);
            boolean[] used = s < 0 ? farUsed : nearUsed;
            if (k0 >= 0 && k0 < levels) used[k0] = true;
            if (k0 + 1 < levels && k0 + 1 >= 0) used[k0 + 1] = true;
        }

        // 1. background
        float[] bg = new float[n * 4];
        for (int k = levels - 1; k >= 0; k--) {
            if (!farUsed[k]) continue;
            Layer l = blurLayer(img, signed, w, h, shape, k, false);
            composite(l, bg, w, h, true);
        }

        // 2. in focus over the background (its holes are filled after the foreground, step 5)
        float[] under = new float[n * 4];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, j = i * 3, q = i * 4;
                float wf = focusWeight(Math.abs(signed[i]));
                float ba = bg[q + 3];
                float a = wf + (1 - wf) * Math.min(1f, ba);
                // the background's colour (normalised) at the coverage it has here
                float br = 0, bgc = 0, bb = 0;
                if (ba > 1e-6f) {
                    float k = Math.min(1f, ba) / ba;
                    br = bg[q] * k;
                    bgc = bg[q + 1] * k;
                    bb = bg[q + 2] * k;
                }
                float r = wf * img[j] + (1 - wf) * br;
                float g = wf * img[j + 1] + (1 - wf) * bgc;
                float b = wf * img[j + 2] + (1 - wf) * bb;
                // well-covered pixels are complete: normalise them so the fill only reaches into real holes
                if (a > 0.3f) {
                    float inv = 1f / a;
                    r *= inv;
                    g *= inv;
                    b *= inv;
                    a = 1;
                }
                under[q] = r;
                under[q + 1] = g;
                under[q + 2] = b;
                under[q + 3] = a;
            }
        });

        // 4. foreground, farthest (nearest the focus plane) first; what lay under each pixel's own layer when it was
        // laid on is what is behind that pixel
        float[] fg = bg; // the background is no longer needed: reuse its memory
        java.util.Arrays.fill(fg, 0);
        boolean anyNear = false;
        float[] behind = null;
        for (int k = 0; k < levels; k++) {
            if (!nearUsed[k]) continue;
            if (anyNear) {
                if (behind == null) behind = new float[n * 4];
                remember(fg, behind, signed, w, h, k);
            }
            anyNear = true;
            Layer l = blurLayer(img, signed, w, h, shape, k, true);
            composite(l, fg, w, h, true);
        }

        // 5. holes: first from the foreground behind them, then from around them
        if (behind != null) {
            float[] b = behind;
            IntStream.range(0, h).parallel().forEach(y -> {
                for (int i = y * w, end = i + w; i < end; i++) {
                    int q = i * 4;
                    float ba = Math.min(1f, b[q + 3]), rest = 1 - under[q + 3];
                    if (rest <= 0 || ba <= 1e-6f) continue;
                    float k = rest / Math.max(1f, b[q + 3]);
                    under[q] += b[q] * k;
                    under[q + 1] += b[q + 1] * k;
                    under[q + 2] += b[q + 2] * k;
                    under[q + 3] += rest * ba;
                }
            });
        }
        pullPush(under, w, h);

        float[] out = new float[n * 3];
        boolean near = anyNear;
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, j = i * 3, q = i * 4;
                float r = under[q], g = under[q + 1], b = under[q + 2];
                if (near) {
                    float fa = fg[q + 3];
                    if (fa > 1e-6f) {
                        float inv = fa > 1 ? 1f / fa : 1f;
                        float a = Math.min(1f, fa);
                        r = fg[q] * inv + (1 - a) * r;
                        g = fg[q + 1] * inv + (1 - a) * g;
                        b = fg[q + 2] * inv + (1 - a) * b;
                    }
                }
                out[j] = r;
                out[j + 1] = g;
                out[j + 2] = b;
            }
        });
        return out;
    }

    /** Position of a blur radius on the layer scale: radius 1 is layer 0, each layer STEP times larger. */
    static double layerPos(float a) {
        return Math.log(Math.max(a, 1e-3)) / Math.log(STEP);
    }

    /** Layer radius in pixels. */
    static double layerRadius(int k) {
        return Math.pow(STEP, k);
    }

    /** Weight of the sharp layer: 1 up to half a pixel of blur, none from one pixel. */
    static float focusWeight(float a) {
        if (a <= 0.5f) return 1;
        if (a >= 1f) return 0;
        return (1f - a) * 2f;
    }

    /** Weight of layer k for a pixel of blur radius a (|signed|). */
    static float layerWeight(float a, int k) {
        if (a <= 0.5f) return 0;
        float rest = 1 - focusWeight(a);
        if (a < 1f) {
            return k == 0 ? rest : 0; // between sharp and layer 0
        }
        double t = layerPos(a);
        double d = Math.abs(t - k);
        return d >= 1 ? 0 : (float) ((1 - d) * rest);
    }

    /**
     * What a pixel in front of the focus plane hides in layer k: all of what is behind it in its farther layer (or in
     * layer 0 while it is still partly sharp), nothing in the nearer one.
     */
    static float nearOccupancy(float a, int k) {
        if (a < 1f) return k == 0 ? Math.min(1f, 2 * layerWeight(a, 0)) : 0;
        return (int) Math.floor(layerPos(a)) == k ? 1 : 0;
    }

    /** Copies what the foreground holds under the pixels whose farther layer is k, before that layer is laid on. */
    private static void remember(float[] fg, float[] behind, float[] signed, int w, int h, int k) {
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int i = y * w, end = i + w; i < end; i++) {
                float s = signed[i];
                if (s <= 0.5f || nearOccupancy(s, k) < 1) continue;
                System.arraycopy(fg, i * 4, behind, i * 4, 4);
            }
        });
    }

    /** A blurred layer at reduced resolution: premultiplied RGB, weight and occupancy per cell. */
    private record Layer(int scale, int lw, int lh, float[] data) {}

    /**
     * Layer k of one side, convolved with the disc of its radius. Channels: premultiplied RGB, weight (alpha) and
     * occupancy (1 where any pixel of this layer lies: what it hides of the layers behind).
     */
    private static Layer blurLayer(float[] img, float[] signed, int w, int h, BokehKernel.Shape shape, int k, boolean near) {
        double r = layerRadius(k);
        int scale = Math.max(1, (int) Math.ceil(r / MAX_TAP_RADIUS));
        int lw = (w + scale - 1) / scale, lh = (h + scale - 1) / scale;
        float[] src = new float[lw * lh * 5];
        float inv = 1f / (scale * scale);
        // downsample: each cell row gathers its own block of source rows
        IntStream.range(0, lh).parallel().forEach(ly -> {
            for (int y = ly * scale; y < Math.min(h, (ly + 1) * scale); y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float s = signed[i];
                    if ((s > 0) != near || s == 0) continue;
                    float wt = layerWeight(Math.abs(s), k);
                    if (wt <= 0) continue;
                    int c = (ly * lw + x / scale) * 5, j = i * 3;
                    src[c] += img[j] * wt * inv;
                    src[c + 1] += img[j + 1] * wt * inv;
                    src[c + 2] += img[j + 2] * wt * inv;
                    src[c + 3] += wt * inv;
                    // occupancy: a pixel split evenly between two layers hides what is behind either of them; in
                    // front of the focus plane, where the layers are laid over each other, only its farther layer
                    // hides (all of) what is behind, so it does not hide its own share in the other one
                    src[c + 4] += (near ? nearOccupancy(Math.abs(s), k) : Math.min(1f, 2 * wt)) * inv;
                }
            }
        });
        BokehKernel.Kernel ker = BokehKernel.kernel(shape, r / scale, near);
        // tiles with nothing within reach are skipped
        int tw = (lw + TILE - 1) / TILE, th = (lh + TILE - 1) / TILE;
        boolean[] has = new boolean[tw * th];
        for (int ly = 0; ly < lh; ly++) {
            for (int lx = 0; lx < lw; lx++) {
                if (src[(ly * lw + lx) * 5 + 4] > 0 || src[(ly * lw + lx) * 5 + 3] > 0) has[(ly / TILE) * tw + lx / TILE] = true;
            }
        }
        int spread = (ker.reach() + TILE - 1) / TILE;
        boolean[] active = new boolean[tw * th];
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                if (!has[ty * tw + tx]) continue;
                for (int dy = -spread; dy <= spread; dy++) {
                    for (int dx = -spread; dx <= spread; dx++) {
                        int yy = ty + dy, xx = tx + dx;
                        if (yy >= 0 && yy < th && xx >= 0 && xx < tw) active[yy * tw + xx] = true;
                    }
                }
            }
        }
        float[] dst = new float[lw * lh * 5];
        // towards the frame edges the barrel clips the disc (cat's eye) and a swirl lens squeezes it: one kernel per
        // tile, by where the tile lies (direction in 16 steps, distance in 6)
        boolean shaped = Math.max(0, 1 - shape.stopsDown() / 2) > 0.02;
        BokehKernel.Kernel[] variants = new BokehKernel.Kernel[16 * 6];
        int[] tileKernel = new int[tw * th];
        double hcx = w / 2.0, hcy = h / 2.0, halfDiag = Math.hypot(hcx, hcy);
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                if (!active[ty * tw + tx] || !shaped) {
                    tileKernel[ty * tw + tx] = -1;
                    continue;
                }
                double ox = ((tx + 0.5) * TILE * scale) - hcx, oy = ((ty + 0.5) * TILE * scale) - hcy;
                double dist = Math.min(1, Math.hypot(ox, oy) / halfDiag);
                int db = (int) Math.min(5, Math.floor(dist * 6));
                if (db == 0) {
                    tileKernel[ty * tw + tx] = -1;
                    continue;
                }
                int ab = (int) Math.floorMod((long) Math.floor((Math.atan2(oy, ox) + Math.PI) / (2 * Math.PI) * 16 + 0.5), 16L);
                int key = db * 16 + ab;
                if (variants[key] == null) {
                    double ang = ab * 2 * Math.PI / 16 - Math.PI, d = (db + 0.5) / 6;
                    variants[key] = BokehKernel.kernel(shape, r / scale, near, d, -Math.cos(ang), -Math.sin(ang));
                }
                tileKernel[ty * tw + tx] = key;
            }
        }
        IntStream.range(0, lh).parallel().forEach(ly -> {
            int ty = ly / TILE;
            for (int lx = 0; lx < lw; lx++) {
                if (!active[ty * tw + lx / TILE]) continue;
                int kv = tileKernel[ty * tw + lx / TILE];
                BokehKernel.Kernel kk = kv < 0 ? ker : variants[kv];
                int[] kdx = kk.dx(), kdy = kk.dy();
                float[] kw = kk.w();
                float a0 = 0, a1 = 0, a2 = 0, a3 = 0, a4 = 0;
                for (int t = 0; t < kw.length; t++) {
                    int sx = lx - kdx[t], sy = ly - kdy[t];
                    if (sx < 0 || sy < 0 || sx >= lw || sy >= lh) continue;
                    int c = (sy * lw + sx) * 5;
                    float occ = src[c + 4];
                    if (occ == 0 && src[c + 3] == 0) continue;
                    float wt = kw[t];
                    a0 += src[c] * wt;
                    a1 += src[c + 1] * wt;
                    a2 += src[c + 2] * wt;
                    a3 += src[c + 3] * wt;
                    a4 += occ * wt;
                }
                int c = (ly * lw + lx) * 5;
                dst[c] = a0;
                dst[c + 1] = a1;
                dst[c + 2] = a2;
                dst[c + 3] = a3;
                dst[c + 4] = a4;
            }
        });
        return new Layer(scale, lw, lh, dst);
    }

    /**
     * Adds a layer (bilinearly upsampled) into a full-resolution RGBA accumulator: {@code over} puts it in front of
     * what is there (the layers behind show through where it does not cover the pixel), otherwise it is summed.
     */
    private static void composite(Layer l, float[] acc, int w, int h, boolean over) {
        int s = l.scale(), lw = l.lw(), lh = l.lh();
        float[] d = l.data();
        IntStream.range(0, h).parallel().forEach(y -> {
            float fy = s == 1 ? y : (y + 0.5f) / s - 0.5f;
            int y0 = Math.max(0, Math.min(lh - 1, (int) Math.floor(fy)));
            int y1 = Math.min(lh - 1, y0 + 1);
            float ty = s == 1 ? 0 : Math.max(0, Math.min(1, fy - (float) Math.floor(fy)));
            if (fy < 0) ty = 0;
            float[] v = new float[5];
            for (int x = 0; x < w; x++) {
                if (s == 1) {
                    int c = (y * lw + x) * 5;
                    for (int ch = 0; ch < 5; ch++) v[ch] = d[c + ch];
                } else {
                    float fx = (x + 0.5f) / s - 0.5f;
                    int x0 = Math.max(0, Math.min(lw - 1, (int) Math.floor(fx)));
                    int x1 = Math.min(lw - 1, x0 + 1);
                    float tx = fx < 0 ? 0 : Math.max(0, Math.min(1, fx - (float) Math.floor(fx)));
                    int c00 = (y0 * lw + x0) * 5, c10 = (y0 * lw + x1) * 5, c01 = (y1 * lw + x0) * 5, c11 = (y1 * lw + x1) * 5;
                    for (int ch = 0; ch < 5; ch++) {
                        float top = d[c00 + ch] + (d[c10 + ch] - d[c00 + ch]) * tx;
                        float bot = d[c01 + ch] + (d[c11 + ch] - d[c01 + ch]) * tx;
                        v[ch] = top + (bot - top) * ty;
                    }
                }
                if (v[4] <= 0 && v[3] <= 0) continue;
                int q = (y * w + x) * 4;
                if (over) {
                    float keep = 1 - Math.min(1f, v[4]);
                    acc[q] = v[0] + keep * acc[q];
                    acc[q + 1] = v[1] + keep * acc[q + 1];
                    acc[q + 2] = v[2] + keep * acc[q + 2];
                    acc[q + 3] = v[3] + keep * acc[q + 3];
                } else {
                    acc[q] += v[0];
                    acc[q + 1] += v[1];
                    acc[q + 2] += v[2];
                    acc[q + 3] += v[3];
                }
            }
        });
    }

    /**
     * Fills pixels with coverage below 1 from their surroundings (premultiplied RGBA, in place, alpha becomes 1): the
     * image is halved down to one pixel summing coverage, then each level fills what its own pixels lack from the
     * level below it.
     */
    static void pullPush(float[] img, int w, int h) {
        boolean holes = false;
        for (int i = 3; i < img.length; i += 4) {
            if (img[i] < 0.999f) {
                holes = true;
                break;
            }
        }
        if (!holes) return;
        java.util.List<float[]> lv = new java.util.ArrayList<>();
        java.util.List<int[]> dims = new java.util.ArrayList<>();
        lv.add(img);
        dims.add(new int[]{w, h});
        while (dims.get(dims.size() - 1)[0] > 1 || dims.get(dims.size() - 1)[1] > 1) {
            float[] a = lv.get(lv.size() - 1);
            int aw = dims.get(dims.size() - 1)[0], ah = dims.get(dims.size() - 1)[1];
            int bw = (aw + 1) / 2, bh = (ah + 1) / 2;
            float[] b = new float[bw * bh * 4];
            IntStream.range(0, bh).parallel().forEach(by -> {
                for (int bx = 0; bx < bw; bx++) {
                    float r = 0, g = 0, bl = 0, al = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        int yy = by * 2 + dy;
                        if (yy >= ah) continue;
                        for (int dx = 0; dx < 2; dx++) {
                            int xx = bx * 2 + dx;
                            if (xx >= aw) continue;
                            int q = (yy * aw + xx) * 4;
                            r += a[q];
                            g += a[q + 1];
                            bl += a[q + 2];
                            al += a[q + 3];
                        }
                    }
                    int q = (by * bw + bx) * 4;
                    float na = Math.min(1f, al), k = al > 1e-9f ? na / al : 0;
                    b[q] = r * k;
                    b[q + 1] = g * k;
                    b[q + 2] = bl * k;
                    b[q + 3] = na;
                }
            });
            lv.add(b);
            dims.add(new int[]{bw, bh});
        }
        // the coarsest level: normalise to full coverage
        float[] top = lv.get(lv.size() - 1);
        if (top[3] > 1e-9f) {
            float k = 1f / top[3];
            top[0] *= k;
            top[1] *= k;
            top[2] *= k;
        }
        top[3] = 1;
        for (int li = lv.size() - 2; li >= 0; li--) {
            float[] a = lv.get(li), b = lv.get(li + 1);
            int aw = dims.get(li)[0], ah = dims.get(li)[1], bw = dims.get(li + 1)[0], bh = dims.get(li + 1)[1];
            IntStream.range(0, ah).parallel().forEach(y -> {
                float fy = (y + 0.5f) / 2 - 0.5f;
                int y0 = Math.max(0, Math.min(bh - 1, (int) Math.floor(fy))), y1 = Math.min(bh - 1, y0 + 1);
                float ty = Math.max(0, Math.min(1, fy - (float) Math.floor(fy)));
                for (int x = 0; x < aw; x++) {
                    int q = (y * aw + x) * 4;
                    float cover = a[q + 3];
                    if (cover >= 0.999f) continue;
                    float fx = (x + 0.5f) / 2 - 0.5f;
                    int x0 = Math.max(0, Math.min(bw - 1, (int) Math.floor(fx))), x1 = Math.min(bw - 1, x0 + 1);
                    float tx = Math.max(0, Math.min(1, fx - (float) Math.floor(fx)));
                    float rest = 1 - cover;
                    for (int c = 0; c < 3; c++) {
                        float p00 = b[(y0 * bw + x0) * 4 + c], p10 = b[(y0 * bw + x1) * 4 + c];
                        float p01 = b[(y1 * bw + x0) * 4 + c], p11 = b[(y1 * bw + x1) * 4 + c];
                        float top2 = p00 + (p10 - p00) * tx, bot = p01 + (p11 - p01) * tx;
                        a[q + c] += rest * (top2 + (bot - top2) * ty);
                    }
                    a[q + 3] = 1;
                }
            });
        }
    }
}
