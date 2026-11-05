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
}
