package dev.hackdanel.realcamera.optics;

/**
 * Exposure arithmetic and the P/A/S/M program logic. Uses APEX: Av = log2(N^2), Tv = -log2(t),
 * EV(ISO 100) = Av + Tv; a higher ISO lets the same scene be exposed with Av + Tv larger by log2(ISO/100).
 */
public final class Exposure {
    public static final int MODE_AUTO = 0, MODE_P = 1, MODE_A = 2, MODE_S = 3, MODE_M = 4;
    public static final int AUTO_ISO_MAX_DEFAULT = 12800;

    private Exposure() {}

    /** Scene luminance the settings are "correct" for (EV at ISO 100). */
    public static double cameraEv(double fNumber, double seconds, double iso) {
        return log2(fNumber * fNumber / seconds) - log2(iso / 100.0);
    }

    public static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    /** Limits the program can use. Index ranges refer to {@link ExposureScales}. */
    /** autoIsoMinIdx: Auto ISO starts at the base ISO, not at an extended low setting. */
    public record Limits(int apertureWideIdx, int apertureNarrowIdx, int shutterFastIdx, int shutterSlowIdx,
                         int isoMinIdx, int isoMaxIdx, int autoIsoMaxIdx, double handheldLimitS, int autoIsoMinIdx) {
        public Limits(int apertureWideIdx, int apertureNarrowIdx, int shutterFastIdx, int shutterSlowIdx,
                      int isoMinIdx, int isoMaxIdx, int autoIsoMaxIdx, double handheldLimitS) {
            this(apertureWideIdx, apertureNarrowIdx, shutterFastIdx, shutterSlowIdx, isoMinIdx, isoMaxIdx, autoIsoMaxIdx,
                    handheldLimitS, isoMinIdx);
        }
    }

    /** Result: chosen indices plus the remaining error. delta > 0 means over-exposed by delta EV. */
    public record Result(int apertureIdx, int shutterIdx, int isoIdx, double delta) {
        public double fNumber() {
            return ExposureScales.APERTURE[apertureIdx];
        }

        public double seconds() {
            return ExposureScales.SHUTTER[shutterIdx];
        }

        public double iso() {
            return ExposureScales.isoValue(isoIdx);
        }
    }

    /**
     * Pick aperture / shutter / ISO for the mode.
     *
     * @param sceneEv     metered scene brightness (EV100)
     * @param evCompEv    exposure compensation in EV (+ = brighter picture)
     * @param isoIdx      set ISO index, or -1 for Auto ISO
     * @param apertureIdx set aperture (A, M)
     * @param shutterIdx  set shutter (S, M)
     */
    public static Result solve(int mode, double sceneEv, double evCompEv, int apertureIdx, int shutterIdx, int isoIdx,
                               Limits lim) {
        boolean autoIso = isoIdx < 0 || mode == MODE_AUTO;
        int iso = autoIso ? Math.max(lim.isoMinIdx(), lim.autoIsoMinIdx()) : clamp(isoIdx, lim.isoMinIdx(), lim.isoMaxIdx());
        double target = sceneEv - evCompEv; // EV100 the camera should be set to
        int av, tv;
        switch (mode) {
            case MODE_A -> {
                av = clamp(apertureIdx, lim.apertureWideIdx(), lim.apertureNarrowIdx());
                tv = shutterFor(target, av, iso, lim);
                if (autoIso) {
                    double t = ExposureScales.SHUTTER[tv];
                    if (t > lim.handheldLimitS()) {
                        iso = isoFor(target, av, ExposureScales.nearestShutter(lim.handheldLimitS()), lim);
                        tv = shutterFor(target, av, iso, lim);
                    }
                }
            }
            case MODE_S -> {
                tv = clamp(shutterIdx, lim.shutterFastIdx(), lim.shutterSlowIdx());
                av = apertureFor(target, tv, iso, lim);
                if (autoIso) {
                    iso = isoFor(target, av, tv, lim);
                    av = apertureFor(target, tv, iso, lim);
                }
            }
            case MODE_M -> {
                av = clamp(apertureIdx, lim.apertureWideIdx(), lim.apertureNarrowIdx());
                tv = clamp(shutterIdx, lim.shutterFastIdx(), lim.shutterSlowIdx());
                if (autoIso) {
                    iso = isoFor(target, av, tv, lim);
                }
            }
            default -> { // AUTO and P: program line (on the light the sensor gets: a high ISO closes the lens down)
                av = programAperture(target + log2(ExposureScales.isoValue(iso) / 100), lim);
                tv = shutterFor(target, av, iso, lim);
                if (autoIso && ExposureScales.SHUTTER[tv] > lim.handheldLimitS()) {
                    int hand = clamp(ExposureScales.nearestShutter(lim.handheldLimitS()), lim.shutterFastIdx(), lim.shutterSlowIdx());
                    iso = isoFor(target, av, hand, lim);
                    tv = shutterFor(target, av, iso, lim);
                }
            }
        }
        double camEv = cameraEv(ExposureScales.APERTURE[av], ExposureScales.SHUTTER[tv], ExposureScales.isoValue(iso));
        return new Result(av, tv, iso, sceneEv - camEv);
    }

    /** Program line: f/4 at EV 10, closing half a stop per EV, within lens limits and at most f/11. */
    private static int programAperture(double ev, Limits lim) {
        double av = log2(16) + 0.5 * (ev - 10);
        double n = Math.sqrt(Math.pow(2, av));
        int idx = ExposureScales.nearestAperture(n);
        int narrowCap = Math.min(lim.apertureNarrowIdx(), ExposureScales.nearestAperture(11));
        return clamp(idx, lim.apertureWideIdx(), Math.max(lim.apertureWideIdx(), narrowCap));
    }

    private static int shutterFor(double ev, int av, int iso, Limits lim) {
        double n = ExposureScales.APERTURE[av];
        double s = ExposureScales.isoValue(iso);
        // N^2/t = 2^(ev + log2(s/100))  ->  t = N^2 / 2^(...)
        double t = n * n / Math.pow(2, ev + log2(s / 100));
        return clamp(ExposureScales.nearestShutter(t), lim.shutterFastIdx(), lim.shutterSlowIdx());
    }

    private static int apertureFor(double ev, int tv, int iso, Limits lim) {
        double t = ExposureScales.SHUTTER[tv];
        double s = ExposureScales.isoValue(iso);
        double n = Math.sqrt(t * Math.pow(2, ev + log2(s / 100)));
        return clamp(ExposureScales.nearestAperture(n), lim.apertureWideIdx(), lim.apertureNarrowIdx());
    }

    private static int isoFor(double ev, int av, int tv, Limits lim) {
        double n = ExposureScales.APERTURE[av];
        double t = ExposureScales.SHUTTER[tv];
        double s = 100 * Math.pow(2, log2(n * n / t) - ev);
        return clamp(ExposureScales.nearestIso(s), Math.max(lim.isoMinIdx(), lim.autoIsoMinIdx()),
                Math.min(lim.isoMaxIdx(), lim.autoIsoMaxIdx()));
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
