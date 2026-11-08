package dev.hackdanel.realcamera.processing;

/**
 * The game draws shade, rooms and the light of a torch on a flat curve: a surface one block under an overhang looks
 * barely darker than one in the sun, while for a camera it is 2-3 stops darker. The photo is brightened as a whole
 * against a reference light (the frame's average); this map then puts every part of the picture at its real light
 * relative to that reference: 2^(EV here - reference EV), divided by how much brighter or darker the game already drew
 * it (its light curve here vs the reference's). Sky cells (no surface) keep 1. Pure Java, no game classes.
 */
public final class ShadeMap {
    /** Darkest and brightest correction: 6 stops down (a cave beside a sunlit field), 2 stops up. */
    public static final float MIN = 1 / 64f, MAX = 4f;

    private ShadeMap() {}

    /**
     * Correction for one cell.
     *
     * @param cellEv       scene EV of the light at the cell (NaN for the sky)
     * @param cellGameLight how brightly the game lit the cell (its light curve, 0..1)
     */
    public static float factor(float cellEv, float cellGameLight, double refEv, double refGameLight) {
        if (Float.isNaN(cellEv)) return 1f;
        double f = Math.pow(2, cellEv - refEv) * Math.max(0.01, refGameLight) / Math.max(0.01, cellGameLight);
        return (float) Math.max(MIN, Math.min(MAX, f));
    }

    /** Corrections of a grid of cells (EV and light curve per cell). */
    public static float[] factors(float[] ev, float[] gameLight, double refEv, double refGameLight) {
        float[] out = new float[ev.length];
        for (int i = 0; i < ev.length; i++) out[i] = factor(ev[i], gameLight[i], refEv, refGameLight);
        return out;
    }

    /** Encodes a correction into 0..255 (log scale, for an 8-bit texture). */
    public static int encode(float f) {
        double e = (Math.log(Math.max(MIN, Math.min(MAX, f))) / Math.log(2) + 6) / 8;
        return (int) Math.round(Math.max(0, Math.min(1, e)) * 255);
    }
}
