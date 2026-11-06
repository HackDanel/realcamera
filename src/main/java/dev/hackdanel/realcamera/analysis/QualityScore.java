package dev.hackdanel.realcamera.analysis;

/**
 * Photo quality 0..100 and stars (plan §7.1), from what the server measured. Pure Java, unit-tested.
 * Weights: exposure 25, sharpness 25, subject size 25, composition 15, noise 10. A picture without a subject is
 * judged as a landscape: sky and phenomena replace subject size and composition.
 */
public final class QualityScore {
    private QualityScore() {}

    /**
     * @param hasSubject   a living thing is in the frame
     * @param area         share of the frame the main subject covers (0..1)
     * @param x            main subject centre, -1..1 (left..right)
     * @param y            main subject centre, -1..1 (bottom..top)
     * @param inFocus      the main subject lies inside the depth of field
     * @param blur         motion blur as a fraction of the frame width
     * @param exposureEv   exposure error in EV (+ over, - under)
     * @param isoStops     stops above the sensor's base ISO
     * @param sky          share of the frame that is sky (0..1)
     * @param phenomenon   something special is in the frame (sunset, lightning, full moon…)
     */
    public static int score(boolean hasSubject, double area, double x, double y, boolean inFocus, double blur,
                            double exposureEv, double isoStops, double sky, boolean phenomenon) {
        return score(hasSubject, area, x, y, inFocus, blur, exposureEv, isoStops, sky, phenomenon, true);
    }

    /**
     * @param sceneInFocus for a picture without a subject: the point in the middle of the frame is sharp (a
     *                     landscape focused at one's feet is not a good landscape)
     */
    public static int score(boolean hasSubject, double area, double x, double y, boolean inFocus, double blur,
                            double exposureEv, double isoStops, double sky, boolean phenomenon, boolean sceneInFocus) {
        // a creature covering less than 2% of the frame is not the subject, just part of the scene
        hasSubject = hasSubject && area >= 0.02;
        double exposure = clamp(1 - Math.max(0, Math.abs(exposureEv) - 0.3) / 2.2);
        double blurPx = blur * 1000; // pixels on a 1000 px wide picture
        double sharp = clamp(1 - Math.max(0, blurPx - 1) / 8);
        double noise = clamp(1 - Math.max(0, isoStops - 3) / 6);
        double size, composition;
        if (hasSubject) {
            if (!inFocus) sharp *= 0.35;
            size = area < 0.08 ? Math.sqrt(area / 0.08) : area > 0.6 ? clamp(1 - (area - 0.6) / 0.6) : 1;
            composition = composition(x, y);
        } else {
            // landscape: some sky but not only sky; a phenomenon makes the picture
            if (!sceneInFocus) sharp *= 0.2;
            size = sky <= 0.02 ? 0.45 : sky > 0.85 ? 0.5 : 0.75;
            if (phenomenon) size = Math.min(1, size + 0.35);
            composition = sky > 0.15 && sky < 0.75 ? 0.9 : 0.6;
        }
        double total = 25 * exposure + 25 * sharp + 25 * size + 15 * composition + 10 * noise;
        if (hasSubject && !inFocus) total = Math.min(total, 70); // a blurred subject is never a three-star picture
        return (int) Math.round(Math.max(0, Math.min(100, total)));
    }

    /** Rule of thirds, with a centred subject still acceptable (portraits). */
    static double composition(double x, double y) {
        double best = Double.MAX_VALUE;
        for (double tx : new double[]{-1 / 3.0, 1 / 3.0}) {
            for (double ty : new double[]{-1 / 3.0, 1 / 3.0}) {
                best = Math.min(best, Math.hypot(x - tx, y - ty));
            }
        }
        double thirds = clamp(1 - best / 0.6);
        double centre = clamp(1 - Math.hypot(x, y) / 0.5) * 0.75;
        double edge = Math.max(Math.abs(x), Math.abs(y)) > 0.85 ? 0.5 : 1; // cut off at the edge
        return Math.max(thirds, centre) * edge;
    }

    public static int stars(int quality) {
        return quality >= 75 ? 3 : quality >= 50 ? 2 : 1;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
