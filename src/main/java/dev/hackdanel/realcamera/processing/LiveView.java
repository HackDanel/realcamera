package dev.hackdanel.realcamera.processing;

/**
 * The per-pixel part of a digital photo's development, for the electronic viewfinder: what the photo makes of each
 * pixel of the game's picture. In the photo's order: colour filter, brightening of a dimly drawn scene, exposure,
 * graduated ND, lens vignetting, sensor noise, sensor clipping, the in-camera vignetting correction, white balance
 * (clipped highlights go neutral), highlight roll-off and the colour profile.
 *
 * <p>The GPU pass {@code realcamera_evf.fsh} computes the same per screen pixel; this copy is its reference (checked
 * against {@link ImagePipeline} by a unit test) and gives the viewfinder histogram. Not part of it: what spreads
 * light over the frame (depth of field is drawn by its own preview; motion, flare, mist, star, distortion,
 * polariser, flash) and wear.
 *
 * @param filterR    colour filter transmission, normalised to its luminance (the camera meters through it)
 * @param gain       linear gain: brightening of the game's picture × 2^exposure error
 * @param gndStops   graduated ND: stops lost at the top of the frame
 * @param vignetteEv lens vignetting at the corner, EV
 * @param correctEv  in-camera vignetting correction at the corner, EV (0 with lens corrections off)
 * @param saturation sensor clipping level (JPEG white = 1)
 * @param knee       start of the highlight roll-off
 */
public record LiveView(double filterR, double filterG, double filterB, double gain, double gndStops, double vignetteEv,
                       double correctEv, double saturation, double knee, ImageParams.Colour colour,
                       ImagePipeline.NoiseModel noise) {

    /**
     * @param p              the photo's parameters
     * @param relight        brightening of the game's picture the photo will get (1 in full light)
     * @param lensCorrection the camera corrects its lens in the JPEG
     */
    public static LiveView of(ImageParams p, double relight, boolean lensCorrection) {
        ImageParams.Extras x = p.extras();
        double fr = x.filterR(), fg = x.filterG(), fb = x.filterB();
        if (fr != 1 || fg != 1 || fb != 1) {
            double lum = 0.2126 * fr + 0.7152 * fg + 0.0722 * fb;
            fr /= lum;
            fg /= lum;
            fb /= lum;
        }
        double vig = ImagePipeline.vignetteEv(p);
        return new LiveView(fr, fg, fb, relight * Math.pow(2, p.sensor().exposureEv()), x.gndStops(),
                vig > 0.01 ? vig : 0, lensCorrection && vig * 0.5 > 0.01 ? vig * 0.5 : 0, p.sensor().saturation(),
                ImagePipeline.rollOffKnee(p.sensor().dynamicRangeEv()), p.colour(), ImagePipeline.noiseModel(p.sensor()));
    }

    /**
     * One pixel without noise, in place.
     *
     * @param rgb linear colour of the game's picture
     * @param r2  squared distance from the frame centre over the squared half diagonal
     * @param t   height in the frame, 0 at the top, 1 at the bottom
     */
    public void apply(float[] rgb, double r2, double t) {
        double k = gain;
        if (gndStops > 0) {
            double s = Math.max(0, Math.min(1, (0.6 - t) / 0.2));
            s = s * s * (3 - 2 * s);
            k *= Math.pow(2, -gndStops * s);
        }
        if (vignetteEv > 0) k *= ImagePipeline.vignetteGain(vignetteEv, r2);
        float sat = (float) saturation;
        float corr = correctEv > 0 ? (float) ImagePipeline.vignetteGain(-correctEv, r2) : 1;
        rgb[0] = Math.min((float) (rgb[0] * filterR * k), sat) * corr;
        rgb[1] = Math.min((float) (rgb[1] * filterG * k), sat) * corr;
        rgb[2] = Math.min((float) (rgb[2] * filterB * k), sat) * corr;
        ImagePipeline.whiteBalance(rgb, colour, saturation);
        ImagePipeline.rollOffFrom(rgb, (float) knee);
        ImagePipeline.tone(rgb, colour);
    }

    /** Linear output luminance of a grey pixel of the given linear luminance at the frame centre (histogram). */
    public double grey(double luminance) {
        float v = (float) luminance;
        float[] rgb = {v, v, v};
        apply(rgb, 0, 0.5);
        return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
    }
}
