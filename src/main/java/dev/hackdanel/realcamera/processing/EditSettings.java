package dev.hackdanel.realcamera.processing;

import java.util.Arrays;

/**
 * Everything the photo editor can change. Plain mutable fields (bound to sliders, copied into the
 * undo history, written to presets with Gson). Defaults leave the photo untouched.
 * Slider-type values are -100..100 (or 0..100) like in Lightroom; exposure is in EV.
 */
public final class EditSettings {
    public static final String[] HSL_NAMES = {"red", "orange", "yellow", "green", "aqua", "blue", "purple", "magenta"};
    /** Hue centres (degrees) of the eight HSL bands. */
    public static final double[] HSL_CENTRES = {0, 30, 60, 120, 180, 225, 270, 315};
    public static final String[] ASPECTS = {"original", "free", "3:2", "4:3", "1:1", "16:9", "4:5"};
    public static final double[] ASPECT_RATIO = {0, 0, 3 / 2.0, 4 / 3.0, 1, 16 / 9.0, 4 / 5.0};

    // light
    public double exposure, contrast, highlights, shadows, whites, blacks;
    // colour
    public double temperature, tint, vibrance, saturation;
    public boolean monochrome;
    /** Camera colour profile id for RAW development ("" = as shot). */
    public String profile = "";
    // tone curve: output at inputs 0, .25, .5, .75, 1
    public double[] curve = {0, 0.25, 0.5, 0.75, 1};
    // HSL per band
    public double[] hslHue = new double[8], hslSat = new double[8], hslLum = new double[8];
    // detail & effects
    public double sharpen, noiseReduction, vignette, grain;
    public boolean lensCorrection;
    // geometry
    public int rotate; // quarter turns clockwise
    public double straighten; // degrees, -15..15
    public int aspect; // index into ASPECTS
    public double cropX, cropY, cropW = 1, cropH = 1; // fraction of the rotated frame

    public EditSettings copy() {
        EditSettings c = new EditSettings();
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(EditSettings o) {
        exposure = o.exposure;
        contrast = o.contrast;
        highlights = o.highlights;
        shadows = o.shadows;
        whites = o.whites;
        blacks = o.blacks;
        temperature = o.temperature;
        tint = o.tint;
        vibrance = o.vibrance;
        saturation = o.saturation;
        monochrome = o.monochrome;
        profile = o.profile == null ? "" : o.profile;
        curve = o.curve == null || o.curve.length != 5 ? new double[]{0, 0.25, 0.5, 0.75, 1} : o.curve.clone();
        hslHue = safe8(o.hslHue);
        hslSat = safe8(o.hslSat);
        hslLum = safe8(o.hslLum);
        sharpen = o.sharpen;
        noiseReduction = o.noiseReduction;
        vignette = o.vignette;
        grain = o.grain;
        lensCorrection = o.lensCorrection;
        rotate = o.rotate;
        straighten = o.straighten;
        aspect = o.aspect;
        cropX = o.cropX;
        cropY = o.cropY;
        cropW = o.cropW;
        cropH = o.cropH;
    }

    /** Presets carry the look, not the framing of one particular picture. */
    public void applyPreset(EditSettings preset) {
        int r = rotate, a = aspect;
        double s = straighten, x = cropX, y = cropY, w = cropW, h = cropH;
        copyFrom(preset);
        rotate = r;
        aspect = a;
        straighten = s;
        cropX = x;
        cropY = y;
        cropW = w;
        cropH = h;
    }

    private static double[] safe8(double[] a) {
        return a == null || a.length != 8 ? new double[8] : a.clone();
    }

    public boolean isIdentity() {
        return equals(new EditSettings());
    }

    public boolean hasCrop() {
        return cropX > 1e-4 || cropY > 1e-4 || cropW < 1 - 1e-4 || cropH < 1 - 1e-4;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof EditSettings o)) return false;
        return exposure == o.exposure && contrast == o.contrast && highlights == o.highlights && shadows == o.shadows
                && whites == o.whites && blacks == o.blacks && temperature == o.temperature && tint == o.tint
                && vibrance == o.vibrance && saturation == o.saturation && monochrome == o.monochrome
                && profile.equals(o.profile) && Arrays.equals(curve, o.curve) && Arrays.equals(hslHue, o.hslHue)
                && Arrays.equals(hslSat, o.hslSat) && Arrays.equals(hslLum, o.hslLum) && sharpen == o.sharpen
                && noiseReduction == o.noiseReduction && vignette == o.vignette && grain == o.grain
                && lensCorrection == o.lensCorrection && rotate == o.rotate && straighten == o.straighten
                && aspect == o.aspect && cropX == o.cropX && cropY == o.cropY && cropW == o.cropW && cropH == o.cropH;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new double[]{exposure, contrast, highlights, shadows, temperature, cropX, cropW});
    }
}
