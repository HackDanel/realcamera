package dev.hackdanel.realcamera.processing;

/**
 * Everything the development pipeline needs, as plain numbers (no game classes), so the pipeline can
 * be unit-tested and run on a worker thread. Grouped by what produces the effect.
 */
public record ImageParams(Sensor sensor, Focus focus, Lens lens, Motion motion, Sun sun, Colour colour, Extras extras,
                          long seed) {

    /**
     * Accessories and wear.
     *
     * @param flashAt1m   flash exposure at 1 m relative to a correct exposure (0 = no flash); falls off with 1/d²
     * @param flashTop    fraction of the frame (from the top) the flash lights; < 1 above the sync speed
     * @param cpl         circular polariser on the lens
     * @param filterR     colour filter transmission per channel (1,1,1 = none)
     * @param fog         0..1 condensation inside the lens (moisture)
     * @param decenter    0..1 knocked lens element: one side of the frame goes soft
     * @param dust        sensor dust spots as x, y (frame -1..1), radius (fraction of width) triples
     */
    public record Extras(double flashAt1m, double flashTop, boolean cpl, double filterR, double filterG, double filterB,
                         double fog, double decenter, float[] dust) {}

    public static final Extras NO_EXTRAS = new Extras(0, 1, false, 1, 1, 1, 0, 0, new float[0]);

    /**
     * @param exposureEv      exposure error: + brightens (over-exposure), - darkens
     * @param isoGain         ISO / 100: the amplification that drives noise
     * @param resolutionScale output pixels / sensor pixels (downsampling averages noise away)
     * @param areaFactor      sqrt(full-frame area / sensor area): a smaller sensor collects less light per frame
     * @param headroomLossEv  highlight headroom lost by shooting below the base ISO (extended low ISO)
     */
    public record Sensor(double exposureEv, double isoGain, double noiseLuma, double noiseChroma, double resolutionScale,
                         double dynamicRangeEv, double areaFactor, double headroomLossEv) {
        public Sensor(double exposureEv, double isoGain, double noiseLuma, double noiseChroma, double resolutionScale,
                      double dynamicRangeEv) {
            this(exposureEv, isoGain, noiseLuma, noiseChroma, resolutionScale, dynamicRangeEv, 1, 0);
        }

        /** Sensor saturation relative to JPEG white for this shot. */
        public double saturation() {
            return Math.max(1, dev.hackdanel.realcamera.processing.ImagePipeline.rawSaturation(dynamicRangeEv)
                    * Math.pow(2, -headroomLossEv));
        }
    }

    /**
     * Thin-lens focus geometry; sensorWidthMm converts the blur circle to pixels. tiltDeg: lens tilt of a
     * tilt-shift lens (+ = plane of focus turned towards the ground).
     */
    public record Focus(double focalMm, double fNumber, double focusM, double sensorWidthMm, double tiltDeg) {
        public Focus(double focalMm, double fNumber, double focusM, double sensorWidthMm) {
            this(focalMm, fNumber, focusM, sensorWidthMm, 0);
        }
    }

    /**
     * @param distortion  radial k1 still visible after in-camera correction (- barrel, + pincushion)
     * @param caPx        lateral chromatic aberration at the corner, pixels of this image
     * @param imageCircle fraction of the half-diagonal the lens illuminates (1 = whole frame)
     * @param flare       ghosting / veiling glare strength
     * @param bokeh       BOKEH_* character of out-of-focus areas
     * @param projection  optics.Optics.PROJ_* (fisheye mappings remap the rendered rectilinear frame)
     * @param renderCorner half-angle (radians) at the corner of the rendered frame (fisheye only)
     * @param shiftPx     lens shift (rise) in output pixels, + = the picture moves up; the frame was rendered
     *                    with the camera pitched up by atan(shift / focal)
     * @param renderScale rendered focal length / output focal length (< 1: the frame was rendered wider)
     */
    public record Lens(double vignettingEv, double maxAperture, double sharpness, double distortion, double caPx,
                       double imageCircle, int blades, boolean rounded, double flare, int bokeh, int projection,
                       double renderCorner, double shiftPx, double renderScale) {
        public Lens(double vignettingEv, double maxAperture, double sharpness, double distortion, double caPx,
                    double imageCircle, int blades, boolean rounded, double flare) {
            this(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    BOKEH_SMOOTH, 0, 0, 0, 1);
        }

        public Lens withBokeh(int b) {
            return new Lens(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    b, projection, renderCorner, shiftPx, renderScale);
        }

        public Lens withProjection(int proj, double corner) {
            return new Lens(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    bokeh, proj, corner, shiftPx, renderScale);
        }

        public Lens withShift(double px, double scale) {
            return new Lens(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    bokeh, projection, renderCorner, px, scale);
        }
    }

    /** Smooth discs, "onion ring" discs, Helios-style swirl, Trioplan-style soap bubbles. */
    public static final int BOKEH_SMOOTH = 0, BOKEH_BUSY = 1, BOKEH_SWIRL = 2, BOKEH_BUBBLE = 3;

    public static int bokeh(String name) {
        return switch (name) {
            case "busy" -> BOKEH_BUSY;
            case "swirl" -> BOKEH_SWIRL;
            case "bubble" -> BOKEH_BUBBLE;
            default -> BOKEH_SMOOTH;
        };
    }

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
                new Colour(1, 1, 1, 1, 1, false, 1, 1, 1, 0), NO_EXTRAS, 1);
    }

    public ImageParams withExposure(double ev) {
        Sensor s = sensor;
        return new ImageParams(new Sensor(ev, s.isoGain, s.noiseLuma, s.noiseChroma, s.resolutionScale, s.dynamicRangeEv,
                s.areaFactor, s.headroomLossEv),
                focus, lens, motion, sun, colour, extras, seed);
    }

    public ImageParams withFocus(double focal, double f, double focusM) {
        return new ImageParams(sensor, new Focus(focal, f, focusM, focus.sensorWidthMm, focus.tiltDeg), lens, motion, sun, colour, extras, seed);
    }

    public ImageParams withNoise(double iso, double luma, double chroma) {
        Sensor s = sensor;
        return new ImageParams(new Sensor(s.exposureEv, iso, luma, chroma, s.resolutionScale, s.dynamicRangeEv,
                s.areaFactor, s.headroomLossEv),
                focus, lens, motion, sun, colour, extras, seed);
    }

    public ImageParams withTilt(double tiltDeg) {
        return new ImageParams(sensor, new Focus(focus.focalMm, focus.fNumber, focus.focusM, focus.sensorWidthMm, tiltDeg),
                lens, motion, sun, colour, extras, seed);
    }

    public ImageParams withLens(Lens l) {
        return new ImageParams(sensor, focus, l, motion, sun, colour, extras, seed);
    }

    public ImageParams withMotion(Motion m) {
        return new ImageParams(sensor, focus, lens, m, sun, colour, extras, seed);
    }

    public ImageParams withExtras(Extras e) {
        return new ImageParams(sensor, focus, lens, motion, sun, colour, e, seed);
    }

    public ImageParams withSun(Sun s) {
        return new ImageParams(sensor, focus, lens, motion, s, colour, extras, seed);
    }
}
