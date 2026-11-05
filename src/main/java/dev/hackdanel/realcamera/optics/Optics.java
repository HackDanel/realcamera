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
        double s = Math.max(focusM * 1000, focalMm * 1.001);
        double d = Math.max(objectM * 1000, 1e-3);
        return (focalMm * focalMm / fNumber) * Math.abs(d - s) / (d * (s - focalMm));
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
}
