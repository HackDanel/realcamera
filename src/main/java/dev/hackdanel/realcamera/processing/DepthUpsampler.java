package dev.hackdanel.realcamera.processing;

/**
 * Upsamples a coarse ray-cast depth grid to image resolution without smearing depth across object
 * edges: each pixel takes the depth of whichever of its 4 surrounding grid samples has the most
 * similar colour (a cheap joint-bilateral upsample).
 */
public final class DepthUpsampler {
    private DepthUpsampler() {}

    public static float[] upsample(float[] grid, int gw, int gh, int[] rgb, int w, int h) {
        // average colour of each grid cell, used as the guide
        float[] cell = new float[gw * gh * 3];
        int[] count = new int[gw * gh];
        for (int y = 0; y < h; y++) {
            int gy = Math.min(gh - 1, y * gh / h);
            for (int x = 0; x < w; x++) {
                int gx = Math.min(gw - 1, x * gw / w);
                int p = rgb[y * w + x];
                int c = gy * gw + gx;
                cell[c * 3] += (p >> 16) & 255;
                cell[c * 3 + 1] += (p >> 8) & 255;
                cell[c * 3 + 2] += p & 255;
                count[c]++;
            }
        }
        for (int c = 0; c < count.length; c++) {
            int n = Math.max(1, count[c]);
            cell[c * 3] /= n;
            cell[c * 3 + 1] /= n;
            cell[c * 3 + 2] /= n;
        }
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            float fy = (y + 0.5f) * gh / h - 0.5f;
            int y0 = Math.max(0, Math.min(gh - 1, (int) Math.floor(fy)));
            int y1 = Math.min(gh - 1, y0 + 1);
            for (int x = 0; x < w; x++) {
                float fx = (x + 0.5f) * gw / w - 0.5f;
                int x0 = Math.max(0, Math.min(gw - 1, (int) Math.floor(fx)));
                int x1 = Math.min(gw - 1, x0 + 1);
                int p = rgb[y * w + x];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int[] cand = {y0 * gw + x0, y0 * gw + x1, y1 * gw + x0, y1 * gw + x1};
                int best = cand[0];
                float bestD = Float.MAX_VALUE;
                for (int c : cand) {
                    float dr = cell[c * 3] - r, dg = cell[c * 3 + 1] - g, db = cell[c * 3 + 2] - b;
                    float d = dr * dr + dg * dg + db * db;
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
                out[y * w + x] = grid[best];
            }
        }
        return out;
    }

    /**
     * Whether a full-resolution depth map read back from the GPU matches the ray-cast grid: the GPU may see nearer
     * things the rays miss (grass, flowers, leaves have no collision shape) and farther ones where a ray hit an
     * entity's box but the model is thinner, but it must never be clearly farther than a block the ray hit. With
     * shader packs the main depth buffer may hold something else entirely; then the grid is used instead.
     */
    /** Counts of the last {@link #agrees} call, for the log. */
    public static volatile String lastCheck = "";

    public static boolean agrees(float[] full, int w, int h, float[] grid, float[] entity, int gw, int gh) {
        int checked = 0, farther = 0, muchNearer = 0, skyMismatch = 0, sky = 0;
        float mismatchMin = Float.MAX_VALUE, mismatchMax = 0;
        for (int gy = 0; gy < gh; gy++) {
            int y = Math.min(h - 1, (int) ((gy + 0.5f) * h / gh));
            for (int gx = 0; gx < gw; gx++) {
                int x = Math.min(w - 1, (int) ((gx + 0.5f) * w / gw));
                int c = gy * gw + gx;
                float ray = grid[c], gpu = full[y * w + x];
                if (gpu < 0) continue; // unknown in the GPU map
                if (ray >= 9_999) {
                    sky++;
                    if (gpu < 1) { // the GPU sees something right in front of the lens where rays see sky
                        skyMismatch++;
                        mismatchMin = Math.min(mismatchMin, gpu);
                        mismatchMax = Math.max(mismatchMax, gpu);
                    }
                    continue;
                }
                if (entity != null && entity[c] > 0) continue;
                checked++;
                if (gpu > ray * 1.1f + 0.3f) farther++;
                if (gpu < ray * 0.5f - 0.3f) muchNearer++;
            }
        }
        lastCheck = "checked " + checked + ", farther " + farther + ", much nearer " + muchNearer + ", sky " + sky + ", sky mismatch " + skyMismatch
                + (skyMismatch > 0 ? " (" + mismatchMin + ".." + mismatchMax + " m)" : "");
        if (checked == 0) {
            // nothing to compare (sky, or an entity filling the frame): fine unless the sky is contradicted
            return skyMismatch <= sky / 4;
        }
        // grass and leaves in front are fine, but not a frame where almost nothing is where the rays found it
        return farther <= checked / 10 && muchNearer <= checked * 0.7f && skyMismatch <= Math.max(2, sky / 4);
    }

    /** Pixels marked unknown (negative) in the GPU depth map take their depth from the fallback map. */
    public static float[] fillUnknown(float[] depth, java.util.function.Supplier<float[]> fallback) {
        float[] other = null;
        for (int i = 0; i < depth.length; i++) {
            if (depth[i] < 0) {
                if (other == null) other = fallback.get();
                depth[i] = other[i];
            }
        }
        return depth;
    }

    /**
     * Some shader packs leave a layer at one fixed depth in the buffer (the night sky, or even the whole frame, at
     * e.g. 0.6 m). Real geometry at one exact depth (a wall facing the lens) agrees with the rays; such a layer does
     * not: when a depth fills a good part of the frame and most of its cells contradict the rays, every pixel at that depth becomes
     * unknown (filled from the ray grid later). Returns how many pixels were marked.
     */
    public static int dropFixedLayer(float[] full, int w, int h, float[] grid, int gw, int gh) {
        java.util.HashMap<Float, int[]> counts = new java.util.HashMap<>(); // value -> {cells, contradicting}
        int cells = 0;
        for (int gy = 0; gy < gh; gy++) {
            int y = Math.min(h - 1, (int) ((gy + 0.5f) * h / gh));
            for (int gx = 0; gx < gw; gx++) {
                float d = full[y * w + Math.min(w - 1, (int) ((gx + 0.5f) * w / gw))];
                if (d < 0 || d >= 9_999) continue;
                cells++;
                float ray = grid[gy * gw + gx];
                int[] c = counts.computeIfAbsent(d, k -> new int[2]);
                c[0]++;
                if (ray >= 9_999 || d < ray * 0.7f - 0.2f) c[1]++;
            }
        }
        java.util.Set<Float> layers = new java.util.HashSet<>();
        for (var e : counts.entrySet()) {
            int[] c = e.getValue();
            if (c[0] >= 8 && c[0] >= cells * 0.15f && c[1] >= c[0] * 0.8f) layers.add(e.getKey());
        }
        if (layers.isEmpty()) return 0;
        int marked = 0;
        for (int i = 0; i < full.length; i++) {
            if (layers.contains(full[i])) {
                full[i] = -1;
                marked++;
            }
        }
        return marked;
    }
}
