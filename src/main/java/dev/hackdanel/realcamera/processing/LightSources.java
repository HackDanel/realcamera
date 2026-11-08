package dev.hackdanel.realcamera.processing;

/**
 * The brightness the game cuts off at white: a torch, a lantern or glowstone is drawn no brighter than a sunlit
 * white wall, while a real flame or lamp is about 10,000 cd/m², many stops above the surfaces it lights in a room or
 * at dusk. Pixels of light-emitting blocks (found by the client: the block under each pixel) are raised to that real
 * brightness relative to the metered exposure, before the depth of field, so their bokeh discs carry the light of a
 * lamp instead of a pale grey one. In sunlight a lamp is barely brighter than white, as in reality, and nothing
 * changes; in shade, overcast, rooms and at night it is several stops brighter.
 *
 * <p>Which texels of a light-emitting block are the light: those nearly as bright as the block's brightest pixel in
 * the frame (the flame of a torch but not its stick, the fire in a furnace but not its stone, all of a pale sea
 * lantern). An absolute threshold missed the sea lantern and caught the stick.
 *
 * <p>Image scale (after the photo's relight): middle grey 0.18 is the luminance the meter read, EV100 at ISO 100 with
 * the reflected-light constant K = 12.5: L = 2^EV · 12.5 / 100 cd/m². Pure Java, no game classes.
 */
public final class LightSources {
    /** Luminance of a light-level-15 source (a flame, a frosted lamp), cd/m². */
    static final double FULL_CD_M2 = 10_000;
    /** At most this many times the white the game drew (8 stops): a sensor clips far below; only blur spreads it. */
    static final float CAP = 256;
    /** Pixels drawn at least this bright (linear, brightest channel) are looked up at all. */
    public static final float CANDIDATE = 0.15f;
    /** A light source whose brightest pixel is dimmer than this is not drawn as a light (far away in fog...). */
    public static final float MIN_PEAK = 0.3f;
    /** Share of the block's brightest pixel from which a texel counts as the light itself... */
    static final float SHARE_LOW = 0.55f;
    /** ...fully from this one. */
    static final float SHARE_HIGH = 0.85f;

    /**
     * Light sources found in a frame, per pixel: the light level of the block (0 = none) and how bright the pixel is
     * relative to the brightest pixel of the same block (0..255).
     */
    public record Found(byte[] level, byte[] share) {}

    private LightSources() {}

    /** Luminance of a source of this light level (0..15), cd/m²: dim sources (redstone torch 7) are far dimmer. */
    public static double luminance(int emission) {
        double e = Math.max(0, Math.min(15, emission)) / 15.0;
        return FULL_CD_M2 * e * e * e * e;
    }

    /** Luminance the meter puts at middle grey for this scene EV (ISO 100), cd/m². */
    static double middleGreyLuminance(double sceneEv) {
        return Math.pow(2, sceneEv) * 12.5 / 100;
    }

    /**
     * How bright a source of this level is on the image scale (white = 1), capped. Torch-lit room (EV 6): 225, at
     * night the cap; dusk (EV 11): 7; overcast noon (EV 13): 1.8; clear noon (EV 15): 0.4, no brighter than white.
     */
    public static float sourceLevel(int emission, double sceneEv) {
        return (float) Math.min(CAP, 0.18 * luminance(emission) / middleGreyLuminance(sceneEv));
    }

    /** Whether any light level-15 source could get brighter than the game drew it (after a relight of {@code relight}). */
    public static boolean worthLooking(double sceneEv, double relight) {
        return sourceLevel(15, sceneEv) > relight * 1.05;
    }

    private static final float[] SRGB_TO_LINEAR = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            SRGB_TO_LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
    }

    /** How bright the game drew this pixel (sRGB 0xRRGGBB): its brightest channel, linear. */
    public static float drawnSrgb(int rgb) {
        return SRGB_TO_LINEAR[Math.max((rgb >> 16) & 255, Math.max((rgb >> 8) & 255, rgb & 255))];
    }

    /** How bright the game drew this pixel (linear RGB, 3 floats per pixel, game scale): its brightest channel. */
    public static float drawnLinear(float[] linear, int pixel) {
        int j = pixel * 3;
        return Math.max(linear[j], Math.max(linear[j + 1], linear[j + 2]));
    }

    /**
     * Turns the pixels found on light-emitting blocks into a {@link Found}: per pixel the block's index (-1 none), per
     * block its light level, and the drawn brightness of every pixel.
     *
     * @return null if no block's brightest pixel is drawn like a light
     */
    public static Found share(int[] block, int[] blockLevel, float[] drawn) {
        float[] peak = new float[blockLevel.length];
        for (int i = 0; i < block.length; i++) {
            if (block[i] >= 0) peak[block[i]] = Math.max(peak[block[i]], drawn[i]);
        }
        byte[] level = null, share = null;
        for (int i = 0; i < block.length; i++) {
            int b = block[i];
            if (b < 0 || peak[b] < MIN_PEAK) continue;
            if (level == null) {
                level = new byte[block.length];
                share = new byte[block.length];
            }
            level[i] = (byte) blockLevel[b];
            share[i] = (byte) Math.round(Math.min(1f, drawn[i] / peak[b]) * 255);
        }
        return level == null ? null : new Found(level, share);
    }

    /**
     * Raises the pixels of light sources to their real brightness: each keeps its colour and becomes as bright as
     * its source level says, never darker than it is. Texels between the dim and the bright part of a texture get a
     * share of the gain in stops (gain^weight), so a lamp's texture brightens smoothly instead of jumping to white.
     * {@code img} is linear and already multiplied by {@code relight}.
     *
     * @return how many pixels were raised
     */
    public static int restore(float[] img, Found found, double sceneEv, double relight) {
        if (found == null || relight <= 0) return 0;
        float[] gain = new float[16];
        for (int e = 1; e < 16; e++) gain[e] = Math.max(1, (float) (sourceLevel(e, sceneEv) / relight));
        byte[] level = found.level(), share = found.share();
        int raised = 0;
        for (int i = 0; i < level.length; i++) {
            int e = level[i];
            if (e <= 0 || gain[Math.min(15, e)] <= 1) continue;
            float t = Math.max(0, Math.min(1, ((share[i] & 255) / 255f - SHARE_LOW) / (SHARE_HIGH - SHARE_LOW)));
            float wt = t * t * (3 - 2 * t);
            if (wt <= 0) continue;
            float g = (float) Math.pow(gain[Math.min(15, e)], wt);
            int j = i * 3;
            img[j] *= g;
            img[j + 1] *= g;
            img[j + 2] *= g;
            raised++;
        }
        return raised;
    }
}
