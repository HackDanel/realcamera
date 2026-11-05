package dev.hackdanel.realcamera.optics;

/**
 * Standard camera value scales in 1/3-stop steps, as printed on real cameras. Settings store indices
 * into these tables; the exact values (not the rounded labels) are used for physics.
 */
public final class ExposureScales {
    /** Shutter speeds in seconds, fastest first: 1/32000 ... 30 s. */
    public static final double[] SHUTTER;
    public static final String[] SHUTTER_LABEL;
    /** f-numbers: f/1.0 ... f/32. */
    public static final double[] APERTURE;
    public static final String[] APERTURE_LABEL;
    /** ISO: 50 ... 204800. */
    public static final int[] ISO;

    private static final String[] SHUTTER_NOMINAL = {
            "1/32000", "1/25600", "1/20000", "1/16000", "1/12800", "1/10000", "1/8000", "1/6400", "1/5000",
            "1/4000", "1/3200", "1/2500", "1/2000", "1/1600", "1/1250", "1/1000", "1/800", "1/640", "1/500",
            "1/400", "1/320", "1/250", "1/200", "1/160", "1/125", "1/100", "1/80", "1/60", "1/50", "1/40",
            "1/30", "1/25", "1/20", "1/15", "1/13", "1/10", "1/8", "1/6", "1/5", "1/4", "0.3\"", "0.4\"",
            "0.5\"", "0.6\"", "0.8\"", "1\"", "1.3\"", "1.6\"", "2\"", "2.5\"", "3.2\"", "4\"", "5\"", "6\"",
            "8\"", "10\"", "13\"", "15\"", "20\"", "25\"", "30\""};
    private static final String[] APERTURE_NOMINAL = {
            "1.0", "1.1", "1.2", "1.4", "1.6", "1.8", "2", "2.2", "2.5", "2.8", "3.2", "3.5", "4", "4.5", "5",
            "5.6", "6.3", "7.1", "8", "9", "10", "11", "13", "14", "16", "18", "20", "22", "25", "29", "32"};
    private static final int[] ISO_NOMINAL = {
            50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200,
            4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000,
            102400, 128000, 160000, 204800};

    static {
        SHUTTER = new double[SHUTTER_NOMINAL.length];
        SHUTTER_LABEL = SHUTTER_NOMINAL;
        // 1/32000 = 2^-15 (approx.); each index is +1/3 EV of exposure time. 30" is index 60 -> 2^(-15+20)=32.
        // Exact powers keep EV arithmetic consistent; labels are the conventional rounded values.
        for (int i = 0; i < SHUTTER.length; i++) {
            SHUTTER[i] = Math.pow(2, -15 + i / 3.0);
        }
        APERTURE = new double[APERTURE_NOMINAL.length];
        APERTURE_LABEL = APERTURE_NOMINAL;
        for (int i = 0; i < APERTURE.length; i++) {
            APERTURE[i] = Math.pow(2, i / 6.0); // f-number grows by sqrt(2) per stop
        }
        ISO = ISO_NOMINAL;
    }

    public static final int ISO_100_INDEX = 3;
    /** Exposure compensation range in thirds: -5 EV ... +5 EV. */
    public static final int EV_COMP_MIN = -15;
    public static final int EV_COMP_MAX = 15;

    private ExposureScales() {}

    /** Exact ISO used for physics at index i (100 * 2^((i-3)/3)). */
    public static double isoValue(int index) {
        return 100.0 * Math.pow(2, (index - ISO_100_INDEX) / 3.0);
    }

    /** The value printed on the dial (1/8000, 0.3", 30"), used for EXIF like real cameras do. */
    public static double nominalShutter(int index) {
        String label = SHUTTER_LABEL[index];
        if (label.startsWith("1/")) {
            return 1.0 / Double.parseDouble(label.substring(2));
        }
        return Double.parseDouble(label.replace("\"", ""));
    }

    public static double nominalAperture(int index) {
        return Double.parseDouble(APERTURE_LABEL[index]);
    }

    public static int nearestShutter(double seconds) {
        return nearest(SHUTTER, seconds);
    }

    public static int nearestAperture(double fNumber) {
        return nearest(APERTURE, fNumber);
    }

    public static int nearestIso(double iso) {
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < ISO.length; i++) {
            double d = Math.abs(Math.log(isoValue(i) / iso));
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    private static int nearest(double[] table, double value) {
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < table.length; i++) {
            double d = Math.abs(Math.log(table[i] / value));
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    public static String evCompLabel(int thirds) {
        if (thirds == 0) {
            return "±0.0";
        }
        return String.format(java.util.Locale.ROOT, "%+.1f", thirds / 3.0);
    }
}
