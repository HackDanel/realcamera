package dev.hackdanel.realcamera.optics;

/** Geometric optics for a thin lens. Distances in millimetres unless the name says otherwise. */
public final class Optics {
    /** Circle of confusion for a 36x24 mm sensor (scaled by crop factor for other formats). */
    public static final double FF_COC_MM = 0.03;

    private Optics() {}

    /** Vertical field of view in degrees for a sensor height and focal length. */
    public static double verticalFovDeg(double sensorHeightMm, double focalMm) {
        return Math.toDegrees(2 * Math.atan(sensorHeightMm / (2 * focalMm)));
    }

    public static int focal35(double focalMm, double cropFactor) {
        return (int) Math.round(focalMm * cropFactor);
    }

    /**
     * Diameter of the blur circle on the sensor for an object at {@code objectM} when the lens is
     * focused at {@code focusM}.
     */
    public static double blurCircleMm(double focalMm, double fNumber, double focusM, double objectM) {
        return Math.abs(signedBlurMm(focalMm, fNumber, focusM, objectM));
    }

    /**
     * Blur circle with a sign: + for objects nearer than the focus distance (their image forms behind the sensor),
     * - for objects beyond it (image in front of the sensor). Axial colour shifts the focus of red and blue by the
     * same amount for both, so their blur differs from green's in opposite directions on the two sides.
     */
    public static double signedBlurMm(double focalMm, double fNumber, double focusM, double objectM) {
        double s = Math.max(focusM * 1000, focalMm * 4.001);
        double d = Math.max(objectM * 1000, 1e-3);
        // distances are measured from the sensor: the lens sits v in front of it (v = f(1+m)), which matters close up
        double u = (s + Math.sqrt(s * s - 4 * s * focalMm)) / 2, v = s - u;
        double ud = d - v;
        if (ud <= focalMm * 1.0005) {
            return focalMm / fNumber; // in front of the focal point: a fully spread disc
        }
        double vd = 1 / (1 / focalMm - 1 / ud);
        return (focalMm / fNumber) * (vd - v) / vd;
    }

    /**
     * Focus distance (m, from the sensor) a lens focused at {@code focusM} has where the plane of best focus lies
     * {@code imageShiftMm} further from the lens (+): field curvature brings the frame edges' focus closer. Shifts
     * that would focus beyond infinity are clamped to infinity (1e6 m).
     */
    public static double curvedFocusM(double focalMm, double focusM, double imageShiftMm) {
        if (imageShiftMm == 0) return focusM;
        double s = Math.max(focusM * 1000, focalMm * 4.001);
        double u = (s + Math.sqrt(s * s - 4 * s * focalMm)) / 2, v = s - u;
        double v2 = v + imageShiftMm;
        if (v2 <= focalMm * 1.000001) return 1e6;
        double u2 = 1 / (1 / focalMm - 1 / v2);
        return Math.min(1e6, (u2 + v2) / 1000);
    }

    /** Effective wavelengths (mm) of the red, green and blue channels of a camera's colour filter array. */
    public static final double[] CHANNEL_WAVELENGTH_MM = {0.000600, 0.000530, 0.000460};

    /**
     * Standard deviation (mm) of the Gaussian that best fits the Airy pattern of a lens at {@code fNumber}:
     * 0.42 lambda N (the Airy disc to its first dark ring is 2.44 lambda N across).
     */
    public static double diffractionSigmaMm(double fNumber, double wavelengthMm) {
        return 0.42 * wavelengthMm * fNumber;
    }

    /**
     * Degree of polarisation of clear skylight at {@code gamma} radians from the sun (single Rayleigh scattering,
     * sin^2 / (1 + cos^2)), scaled to what a real sky reaches (about 75% at 90°: multiple scattering and haze).
     */
    public static double skyPolarization(double gamma) {
        double c = Math.cos(gamma), s2 = 1 - c * c;
        return 0.75 * s2 / (1 + c * c);
    }

    public static double hyperfocalMm(double focalMm, double fNumber, double cocMm) {
        return focalMm * focalMm / (fNumber * cocMm) + focalMm;
    }

    /** Near and far limits of acceptable sharpness in metres (far may be +infinity). */
    public static double[] depthOfFieldM(double focalMm, double fNumber, double focusM, double cocMm) {
        double h = hyperfocalMm(focalMm, fNumber, cocMm);
        double s = focusM * 1000;
        double near = s * (h - focalMm) / (h + s - 2 * focalMm);
        double far = s >= h ? Double.POSITIVE_INFINITY : s * (h - focalMm) / (h - s);
        return new double[]{near / 1000, far / 1000};
    }

    /** Slowest shutter speed (seconds) that is sharp hand-held: 1/(35mm-equivalent focal), extended by stabilisation. */
    public static double handheldLimit(double focal35, double stabilizationStops) {
        return Math.pow(2, stabilizationStops) / Math.max(focal35, 1);
    }

    /** Reproduction ratio (image size / object size) of a thin lens focused at {@code focusM} (from the sensor). */
    public static double magnification(double focalMm, double focusM) {
        double s = focusM * 1000;
        if (s <= 4 * focalMm) return 1; // closer than 1:1 is not reachable with plain extension
        // sensor-to-subject distance s = u + v with 1/u + 1/v = 1/f; m = v/u
        double u = (s + Math.sqrt(s * s - 4 * s * focalMm)) / 2;
        double v = s - u;
        return v / u;
    }

    // ------------------------------------------------------------------ fisheye

    public static final int PROJ_RECTILINEAR = 0, PROJ_EQUISOLID = 1, PROJ_STEREOGRAPHIC = 2, PROJ_EQUIDISTANT = 3;

    /** The widest a rectilinear frame is rendered (half-angle at the corner); fisheyes beyond it are compressed. */
    public static final double MAX_RENDER_HALF_ANGLE = Math.toRadians(74);

    public static int projection(String name) {
        return switch (name) {
            case "equisolid" -> PROJ_EQUISOLID;
            case "stereographic" -> PROJ_STEREOGRAPHIC;
            case "equidistant" -> PROJ_EQUIDISTANT;
            default -> PROJ_RECTILINEAR;
        };
    }

    /** Off-axis angle (radians) imaged at {@code rMm} from the centre; NaN outside the fisheye's image circle. */
    public static double fisheyeAngle(int projection, double focalMm, double rMm) {
        double x = rMm / focalMm;
        return switch (projection) {
            case PROJ_EQUISOLID -> x / 2 > 1 ? Double.NaN : 2 * Math.asin(x / 2);
            case PROJ_STEREOGRAPHIC -> 2 * Math.atan(x / 2);
            case PROJ_EQUIDISTANT -> x;
            default -> Math.atan(x);
        };
    }

    /** Distance from the centre (mm) at which a fisheye images the off-axis angle {@code theta}. */
    public static double fisheyeRadius(int projection, double focalMm, double theta) {
        return switch (projection) {
            case PROJ_EQUISOLID -> 2 * focalMm * Math.sin(theta / 2);
            case PROJ_STEREOGRAPHIC -> 2 * focalMm * Math.tan(theta / 2);
            case PROJ_EQUIDISTANT -> focalMm * theta;
            default -> focalMm * Math.tan(theta);
        };
    }

    /**
     * Half-angle at the frame corner a fisheye covers: what its picture shows. Circular fisheyes (the image
     * circle is smaller than the frame) and all lenses are limited to 90° off-axis (a 180° view).
     */
    public static double fisheyeCornerAngle(int projection, double focalMm, double halfDiagMm) {
        double a = fisheyeAngle(projection, focalMm, halfDiagMm);
        return Double.isNaN(a) ? Math.PI / 2 : Math.min(Math.PI / 2, a);
    }

    /**
     * Half-angle at the frame corner of the rectilinear frame rendered for a lens: the lens's own coverage,
     * but never wider than {@link #MAX_RENDER_HALF_ANGLE} (a game camera cannot render 180°).
     */
    public static double renderCornerAngle(int projection, double focalMm, double halfDiagMm) {
        double a = projection == PROJ_RECTILINEAR ? Math.atan(halfDiagMm / focalMm)
                : fisheyeCornerAngle(projection, focalMm, halfDiagMm);
        return Math.min(MAX_RENDER_HALF_ANGLE, a);
    }

    /** Vertical FOV (degrees) to render for a lens on a sensor: rectilinear lenses as usual, fisheyes capped. */
    public static double renderVfovDeg(int projection, double focalMm, double widthMm, double heightMm) {
        if (projection == PROJ_RECTILINEAR) {
            return verticalFovDeg(heightMm, focalMm);
        }
        double halfDiag = Math.hypot(widthMm, heightMm) / 2;
        double corner = renderCornerAngle(projection, focalMm, halfDiag);
        // the rendered frame has the sensor's aspect: its half-height is tan(corner) * h / diag
        return Math.toDegrees(2 * Math.atan(Math.tan(corner) * (heightMm / 2) / halfDiag));
    }

    // ------------------------------------------------------------------ tilt

    /**
     * Distance (m, along the axis) the lens focuses at for a ray {@code rowMm} above (+) or below (-) the
     * centre of the sensor when the lens is tilted (Scheimpflug): the plane of sharp focus turns around the
     * hinge line at J = f / sin(tilt) below (tilt > 0, "lens tilted down") the lens. Infinity where the ray
     * runs parallel to or away from the plane.
     */
    public static double tiltedFocusM(double focalMm, double focusM, double tiltDeg, double rowMm) {
        if (Math.abs(tiltDeg) < 1e-3) return focusM;
        double j = focalMm / Math.sin(Math.toRadians(tiltDeg)) / 1000; // hinge distance, m (signed)
        double tanPhi = rowMm / focalMm; // ray angle; + = above the axis in the scene
        // plane through the hinge (y = -J, z = 0) and the axis point (0, D): z = D (y + J) / J
        double denom = 1 - focusM * tanPhi / j;
        if (denom <= 1e-4) return Double.POSITIVE_INFINITY;
        return focusM / denom;
    }
}
