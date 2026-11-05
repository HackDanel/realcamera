package dev.hackdanel.realcamera.processing;

/**
 * Everything the development pipeline needs, as plain numbers (no game classes), so the pipeline can
 * be unit-tested and run on a worker thread. Grouped by what produces the effect.
 */
public record ImageParams(Sensor sensor, Focus focus, Lens lens, Motion motion, Sun sun, Colour colour, long seed) {

    /**
     * @param exposureEv      exposure error: + brightens (over-exposure), - darkens
     * @param isoGain         ISO / base ISO (>= 1), drives noise
     * @param resolutionScale output pixels / sensor pixels (downsampling averages noise away)
     */
    public record Sensor(double exposureEv, double isoGain, double noiseLuma, double noiseChroma, double resolutionScale,
                         double dynamicRangeEv) {}

    /** Thin-lens focus geometry; sensorWidthMm converts the blur circle to pixels. */
    public record Focus(double focalMm, double fNumber, double focusM, double sensorWidthMm) {}

    /**
     * @param distortion  radial k1 still visible after in-camera correction (- barrel, + pincushion)
     * @param caPx        lateral chromatic aberration at the corner, pixels of this image
     * @param imageCircle fraction of the half-diagonal the lens illuminates (1 = whole frame)
     * @param flare       ghosting / veiling glare strength
     */
    public record Lens(double vignettingEv, double maxAperture, double sharpness, double distortion, double caPx,
                       double imageCircle, int blades, boolean rounded, double flare) {}

    /**
     * Linear smear in pixels during the exposure (hand shake not removed by stabilisation, plus panning);
     * rollingSkewPx: horizontal offset of the last read-out row relative to the first (electronic shutter).
     */
    public record Motion(double blurX, double blurY, double rollingSkewPx) {}

    /** Sun position in frame coordinates (-1..1, may lie outside) and how much of it is visible (0 = none). */
    public record Sun(double x, double y, double strength) {}

    public record Colour(double wbR, double wbG, double wbB, double contrast, double saturation, boolean monochrome,
                         double tintR, double tintG, double tintB, double shadowLift) {}

    public static final Motion STILL = new Motion(0, 0, 0);
    public static final Sun NO_SUN = new Sun(0, 0, 0);

    public static ImageParams neutral() {
        return new ImageParams(new Sensor(0, 1, 0, 0, 1, 14), new Focus(50, 8, 1e9, 36),
                new Lens(0, 8, 1, 0, 0, 1, 7, true, 0), STILL, NO_SUN,
                new Colour(1, 1, 1, 1, 1, false, 1, 1, 1, 0), 1);
    }

    public ImageParams withExposure(double ev) {
        Sensor s = sensor;
        return new ImageParams(new Sensor(ev, s.isoGain, s.noiseLuma, s.noiseChroma, s.resolutionScale, s.dynamicRangeEv),
                focus, lens, motion, sun, colour, seed);
    }

    public ImageParams withFocus(double focal, double f, double focusM) {
        return new ImageParams(sensor, new Focus(focal, f, focusM, focus.sensorWidthMm), lens, motion, sun, colour, seed);
    }

    public ImageParams withNoise(double iso, double luma, double chroma) {
        Sensor s = sensor;
        return new ImageParams(new Sensor(s.exposureEv, iso, luma, chroma, s.resolutionScale, s.dynamicRangeEv),
                focus, lens, motion, sun, colour, seed);
    }

    public ImageParams withLens(Lens l) {
        return new ImageParams(sensor, focus, l, motion, sun, colour, seed);
    }

    public ImageParams withMotion(Motion m) {
        return new ImageParams(sensor, focus, lens, m, sun, colour, seed);
    }

    public ImageParams withSun(Sun s) {
        return new ImageParams(sensor, focus, lens, motion, s, colour, seed);
    }
}
