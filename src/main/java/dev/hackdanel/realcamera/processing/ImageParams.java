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
     * @param flashRear   rear-curtain sync: the flash fires at the end of the exposure (moving subjects trail behind
     *                    their sharp flash image instead of ahead of it)
     * @param otherFlash  per pixel, light from other photographers' flashes that fired while the shutter was open
     *                    (exposure relative to a correct one, already with distance, beam and shadows), or null
     */
    /**
     * @param mist       diffusion filter strength (Black Mist 1/4 = 0.25), 0 = none
     * @param gndStops   graduated ND: stops taken from the top half of the frame (soft edge), 0 = none
     * @param starPoints cross-screen filter: rays per bright point (4, 6, 8), 0 = none
     */
    public record Extras(double flashAt1m, double flashTop, boolean cpl, double filterR, double filterG, double filterB,
                         double fog, double decenter, float[] dust, boolean flashRear, float[] otherFlash,
                         double mist, double gndStops, int starPoints) {
        public Extras(double flashAt1m, double flashTop, boolean cpl, double filterR, double filterG, double filterB,
                      double fog, double decenter, float[] dust, boolean flashRear, float[] otherFlash) {
            this(flashAt1m, flashTop, cpl, filterR, filterG, filterB, fog, decenter, dust, flashRear, otherFlash, 0, 0, 0);
        }

        public Extras(double flashAt1m, double flashTop, boolean cpl, double filterR, double filterG, double filterB,
                      double fog, double decenter, float[] dust, boolean flashRear) {
            this(flashAt1m, flashTop, cpl, filterR, filterG, filterB, fog, decenter, dust, flashRear, null);
        }

        public Extras(double flashAt1m, double flashTop, boolean cpl, double filterR, double filterG, double filterB,
                      double fog, double decenter, float[] dust) {
            this(flashAt1m, flashTop, cpl, filterR, filterG, filterB, fog, decenter, dust, false);
        }

        public Extras withOtherFlash(float[] light) {
            return new Extras(flashAt1m, flashTop, cpl, filterR, filterG, filterB, fog, decenter, dust, flashRear, light,
                    mist, gndStops, starPoints);
        }

        public Extras withFilterEffects(double mist, double gndStops, int starPoints) {
            return new Extras(flashAt1m, flashTop, cpl, filterR, filterG, filterB, fog, decenter, dust, flashRear, otherFlash,
                    mist, gndStops, starPoints);
        }

        /** Any flash light at all in this exposure. */
        public boolean anyFlash() {
            return flashAt1m > 0 || otherFlash != null;
        }
    }

    public static final Extras NO_EXTRAS = new Extras(0, 1, false, 1, 1, 1, 0, 0, new float[0]);

    /**
     * @param exposureEv      exposure error: + brightens (over-exposure), - darkens
     * @param isoGain         ISO / 100: the amplification that drives noise
     * @param resolutionScale output pixels / sensor pixels (downsampling averages noise away)
     * @param areaFactor      sqrt(full-frame area / sensor area): a smaller sensor collects less light per frame
     * @param headroomLossEv  highlight headroom lost by shooting below the base ISO (extended low ISO)
     * @param dark            thermal signal of a long exposure (dark current, hot pixels)
     */
    public record Sensor(double exposureEv, double isoGain, double noiseLuma, double noiseChroma, double resolutionScale,
                         double dynamicRangeEv, double areaFactor, double headroomLossEv, Dark dark) {
        public Sensor(double exposureEv, double isoGain, double noiseLuma, double noiseChroma, double resolutionScale,
                      double dynamicRangeEv) {
            this(exposureEv, isoGain, noiseLuma, noiseChroma, resolutionScale, dynamicRangeEv, 1, 0);
        }

        public Sensor(double exposureEv, double isoGain, double noiseLuma, double noiseChroma, double resolutionScale,
                      double dynamicRangeEv, double areaFactor, double headroomLossEv) {
            this(exposureEv, isoGain, noiseLuma, noiseChroma, resolutionScale, dynamicRangeEv, areaFactor, headroomLossEv,
                    NO_DARK);
        }

        public Sensor withDark(Dark d) {
            return new Sensor(exposureEv, isoGain, noiseLuma, noiseChroma, resolutionScale, dynamicRangeEv, areaFactor,
                    headroomLossEv, d);
        }

        /** Sensor saturation relative to JPEG white for this shot. */
        public double saturation() {
            return Math.max(1, dev.hackdanel.realcamera.processing.ImagePipeline.rawSaturation(dynamicRangeEv)
                    * Math.pow(2, -headroomLossEv));
        }
    }

    /**
     * Dark current of a long exposure. Electrons leak into the pixels while the shutter is open, about twice as
     * many for every 6.5 °C the sensor is warmer; the mean is taken out with the masked (optical black) pixels, but
     * its shot noise stays, and a few defective pixels leak far more than the rest (hot pixels, always the same ones
     * on a given sensor).
     *
     * @param seconds    how long the pixels integrated (0 = too short to matter)
     * @param sensorC    sensor temperature, °C
     * @param subtracted long-exposure noise reduction: a dark frame of the same length was subtracted (hot pixels
     *                   gone, the random part of the dark noise counted twice)
     * @param sensorSeed identifies the sensor: its hot pixels are always in the same places
     */
    public record Dark(double seconds, double sensorC, boolean subtracted, long sensorSeed) {
        /** Dark current relative to 20 °C: doubles every 6.5 °C. */
        public double thermal() {
            return Math.pow(2, (sensorC - 20) / 6.5);
        }
    }

    public static final Dark NO_DARK = new Dark(0, 20, false, 0);

    /**
     * Thin-lens focus geometry; sensorWidthMm converts the blur circle to pixels. tiltDeg: lens tilt of a
     * tilt-shift lens (+ = plane of focus turned towards the ground).
     */
    public record Focus(double focalMm, double fNumber, double focusM, double sensorWidthMm, double tiltDeg,
                        double fieldCurvatureMm) {
        public Focus(double focalMm, double fNumber, double focusM, double sensorWidthMm, double tiltDeg) {
            this(focalMm, fNumber, focusM, sensorWidthMm, tiltDeg, 0);
        }

        public Focus(double focalMm, double fNumber, double focusM, double sensorWidthMm) {
            this(focalMm, fNumber, focusM, sensorWidthMm, 0, 0);
        }

        /** fieldCurvatureMm: image-side focus shift at the frame corner (+ = the corners focus closer). */
        public Focus withFieldCurvature(double mm) {
            return new Focus(focalMm, fNumber, focusM, sensorWidthMm, tiltDeg, mm);
        }
    }

    /**
     * Out-of-focus character beyond blade shape and bokeh style (spec.LensSpec.Character): spherical -1..1 (+ soft
     * discs behind the focus plane, bright-rimmed ones in front), apodization 0..1 (discs fade to their edge),
     * obstruction 0..0.6 (mirror lens ring discs, fraction of the radius), soft 0..1 (soft-focus glow).
     */
    public record Character(double spherical, double apodization, double obstruction, double soft) {
        public static final Character NONE = new Character(0, 0, 0, 0);

        public Character(double spherical, double apodization, double obstruction) {
            this(spherical, apodization, obstruction, 0);
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
     * @param locaMm      longitudinal (axial) chromatic aberration: how much further behind the lens red and blue
     *                    come to focus than green, mm in image space (the secondary spectrum of an achromat)
     * @param character   out-of-focus character (spherical aberration, apodisation, mirror obstruction)
     */
    public record Lens(double vignettingEv, double maxAperture, double sharpness, double distortion, double caPx,
                       double imageCircle, int blades, boolean rounded, double flare, int bokeh, int projection,
                       double renderCorner, double shiftPx, double renderScale, double locaMm, Character character) {
        public Lens(double vignettingEv, double maxAperture, double sharpness, double distortion, double caPx,
                    double imageCircle, int blades, boolean rounded, double flare, int bokeh, int projection,
                    double renderCorner, double shiftPx, double renderScale, double locaMm) {
            this(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    bokeh, projection, renderCorner, shiftPx, renderScale, locaMm, Character.NONE);
        }

        public Lens(double vignettingEv, double maxAperture, double sharpness, double distortion, double caPx,
                    double imageCircle, int blades, boolean rounded, double flare) {
            this(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    BOKEH_SMOOTH, 0, 0, 0, 1, 0);
        }

        public Lens(double vignettingEv, double maxAperture, double sharpness, double distortion, double caPx,
                    double imageCircle, int blades, boolean rounded, double flare, int bokeh, int projection,
                    double renderCorner, double shiftPx, double renderScale) {
            this(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    bokeh, projection, renderCorner, shiftPx, renderScale, 0);
        }

        public Lens withBokeh(int b) {
            return new Lens(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    b, projection, renderCorner, shiftPx, renderScale, locaMm, character);
        }

        public Lens withProjection(int proj, double corner) {
            return new Lens(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    bokeh, proj, corner, shiftPx, renderScale, locaMm, character);
        }

        public Lens withShift(double px, double scale) {
            return new Lens(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    bokeh, projection, renderCorner, px, scale, locaMm, character);
        }

        public Lens withLoca(double mm) {
            return new Lens(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    bokeh, projection, renderCorner, shiftPx, renderScale, mm, character);
        }

        public Lens withCharacter(Character c) {
            return new Lens(vignettingEv, maxAperture, sharpness, distortion, caPx, imageCircle, blades, rounded, flare,
                    bokeh, projection, renderCorner, shiftPx, renderScale, locaMm, c);
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
     * Movement during the exposure. blurX/blurY: how far the whole picture moves while the shutter is open (hand
     * shake not removed by stabilisation, plus the photographer turning the camera), pixels, + = right/down.
     * rollingSkewPx/Y: offset of the last read-out row relative to the first (electronic shutter), horizontal from
     * a pan, vertical from a tilt.
     *
     * @param seconds    exposure time for the motion of the camera's position and of moving subjects (0 = none:
     *                   long exposures record it for real by accumulating frames)
     * @param camVx      camera velocity in m/s along the frame's right, up and forward axes (moving vehicle):
     *                   near things smear more than far ones
     * @param readoutS   electronic shutter read-out time (s), 0 for a mechanical shutter: moving subjects lean
     * @param subjectBlur smear of the main subject in pixels (what the photographer sees as "is it sharp")
     * @param field      moving entities in the frame, or null
     */
    public record Motion(double blurX, double blurY, double rollingSkewPx, double rollingSkewY, double seconds,
                         double camVx, double camVy, double camVz, double readoutS, double subjectBlur,
                         @javax.annotation.Nullable MotionField field) {
        public Motion(double blurX, double blurY, double rollingSkewPx) {
            this(blurX, blurY, rollingSkewPx, 0, 0, 0, 0, 0, 0, Math.hypot(blurX, blurY), null);
        }

        /** Pixel quantities (given as fractions of the image width) for an image {@code w} pixels wide. */
        public Motion scaled(double w) {
            return new Motion(blurX * w, blurY * w, rollingSkewPx * w, rollingSkewY * w, seconds, camVx, camVy, camVz,
                    readoutS, subjectBlur * w, field);
        }

        public Motion withField(@javax.annotation.Nullable MotionField f) {
            return new Motion(blurX, blurY, rollingSkewPx, rollingSkewY, seconds, camVx, camVy, camVz, readoutS,
                    subjectBlur, f);
        }

        /** True if anything in the frame moves differently from the rest (subjects, or parallax of a moving camera). */
        public boolean local() {
            return seconds > 0 && (field != null && !field.movers().isEmpty()
                    || Math.abs(camVx) + Math.abs(camVy) + Math.abs(camVz) > 0.05);
        }
    }

    /**
     * Moving entities on a coarse grid over the frame (the ray grid): which mover each cell's ray hit (-1 = none).
     *
     * @param cells  gw x gh mover indices, row-major top-down
     * @param movers velocity relative to the ground in m/s along the frame's right/up/forward axes, and the
     *               depth range (m along the axis) the entity occupies
     */
    public record MotionField(int gw, int gh, short[] cells, java.util.List<Mover> movers) {}

    public record Mover(double vx, double vy, double vz, float near, float far) {}

    /**
     * Sun position in frame coordinates (-1..1, may lie outside) and how much of it is visible (0 = none) for flare;
     * dirX/Y/Z: unit vector towards the sun along the frame's right/up/forward axes (0,0,0 = unknown, e.g. night or
     * no sky), clearSky: 1 for a clear sky, 0 overcast (skylight polarisation needs a clear sky).
     */
    public record Sun(double x, double y, double strength, double dirX, double dirY, double dirZ, double clearSky) {
        public Sun(double x, double y, double strength) {
            this(x, y, strength, 0, 0, 0, 1);
        }

        public boolean hasDirection() {
            return dirX != 0 || dirY != 0 || dirZ != 0;
        }
    }

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
                s.areaFactor, s.headroomLossEv, s.dark),
                focus, lens, motion, sun, colour, extras, seed);
    }

    public ImageParams withFocus(double focal, double f, double focusM) {
        return new ImageParams(sensor, new Focus(focal, f, focusM, focus.sensorWidthMm, focus.tiltDeg, focus.fieldCurvatureMm),
                lens, motion, sun, colour, extras, seed);
    }

    public ImageParams withNoise(double iso, double luma, double chroma) {
        Sensor s = sensor;
        return new ImageParams(new Sensor(s.exposureEv, iso, luma, chroma, s.resolutionScale, s.dynamicRangeEv,
                s.areaFactor, s.headroomLossEv, s.dark),
                focus, lens, motion, sun, colour, extras, seed);
    }

    public ImageParams withTilt(double tiltDeg) {
        return new ImageParams(sensor, new Focus(focus.focalMm, focus.fNumber, focus.focusM, focus.sensorWidthMm, tiltDeg,
                focus.fieldCurvatureMm),
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

    public ImageParams withSensor(Sensor s) {
        return new ImageParams(s, focus, lens, motion, sun, colour, extras, seed);
    }

    public ImageParams withSun(Sun s) {
        return new ImageParams(sensor, focus, lens, motion, s, colour, extras, seed);
    }
}
