package dev.hackdanel.realcamera.processing;

/**
 * One depth map for the depth of field from three depth buffers of the same frame: the opaque world, the world with
 * the surfaces of translucent blocks (water, stained glass, ice), and everything (particles too).
 *
 * <p>A camera focuses through a window or a pond's surface on what lies behind it, so behind a translucent surface
 * the depth is that of the opaque world, seen through water at its apparent depth (refraction: surface + d / 1.33).
 * Particles are drawn as they are: smoke or a leaf right in front of the lens blurs into a big soft disc. Which
 * surface is water and which is glass comes from the ray grid (cells whose ray hit a fluid or a translucent block).
 * Pure Java, no game classes.
 */
public final class LayeredDepth {
    /** Ray grid cell kinds. */
    public static final float NONE = 0, WATER = 1, CLEAR = 2;
    /** Refractive index of water: the bottom of a pond looks this much nearer. */
    static final double WATER_INDEX = 1.33;

    private LayeredDepth() {}

    /**
     * @param full      depth with everything (metres; what the GPU depth check compared against the rays)
     * @param opaque    the opaque world
     * @param surface   the opaque world plus translucent block surfaces (on Fast/Fancy graphics also solid particles)
     * @param kind      per ray grid cell: NONE, WATER or CLEAR (a translucent block)
     * @param inWater   the camera itself is under water (no refraction correction looking out)
     * @return a new depth map
     */
    public static float[] combine(float[] full, float[] opaque, float[] surface, float[] kind, int gw, int gh,
                                  int w, int h, boolean inWater) {
        float[] out = new float[w * h];
        java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float f = full[i], o = opaque[i], s = surface[i];
                if (f < 0 || o < 0 || s < 0) {
                    out[i] = f; // unknown somewhere: leave it to the usual fill
                } else if (nearer(f, s)) {
                    out[i] = f; // a particle drawn after the translucent blocks
                } else if (nearer(s, o)) {
                    float k = kindAt(kind, gw, gh, x, y, w, h);
                    if (k == WATER) {
                        out[i] = inWater ? o : (float) (s + (o - s) / WATER_INDEX);
                    } else if (k == CLEAR) {
                        out[i] = o;
                    } else {
                        out[i] = s; // no translucent block here: a solid particle
                    }
                } else {
                    out[i] = f;
                }
            }
        });
        return out;
    }

    /** a lies clearly in front of b. */
    static boolean nearer(float a, float b) {
        return a < b * 0.99f - 0.02f;
    }

    /** The kind of the ray cells around a pixel: water if any is water, else clear if any is clear. */
    static float kindAt(float[] kind, int gw, int gh, int x, int y, int w, int h) {
        int cx = Math.min(gw - 1, (int) ((x + 0.5) * gw / w)), cy = Math.min(gh - 1, (int) ((y + 0.5) * gh / h));
        float best = NONE;
        for (int dy = -1; dy <= 1; dy++) {
            int yy = cy + dy;
            if (yy < 0 || yy >= gh) continue;
            for (int dx = -1; dx <= 1; dx++) {
                int xx = cx + dx;
                if (xx < 0 || xx >= gw) continue;
                float k = kind[yy * gw + xx];
                if (k == WATER) return WATER;
                if (k == CLEAR) best = CLEAR;
            }
        }
        return best;
    }
}
