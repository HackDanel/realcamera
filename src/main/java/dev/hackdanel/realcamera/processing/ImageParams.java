package dev.hackdanel.realcamera.processing;

/**
 * Everything the development pipeline needs, as plain numbers (no game classes), so the pipeline
 * can be unit-tested and run on a worker thread.
 *
 * @param exposureEv      exposure error: + brightens (over-exposure), - darkens
 * @param isoGain         ISO / base ISO (>= 1), drives noise
 * @param noiseLuma       sensor luminance noise factor (1 = typical full frame)
 * @param noiseChroma     sensor colour noise factor
 * @param resolutionScale output pixels / sensor pixels (downsampling averages noise away)
 * @param dynamicRangeEv  sensor dynamic range; wider = softer highlight roll-off
 * @param focalMm         real focal length
 * @param fNumber         aperture
 * @param focusM          focus distance in metres
 * @param sensorWidthMm   sensor width (for converting the blur circle to pixels)
 * @param vignettingEv    lens corner fall-off wide open
 * @param maxAperture     widest aperture of the lens at this focal length (vignetting shrinks when stopped down)
 * @param lensSharpness   1 = neutral, < 1 softer, > 1 crisper
 * @param wbR             white balance multipliers (green = 1)
 * @param contrast        colour profile
 * @param seed            noise seed (deterministic output for tests)
 */
public record ImageParams(double exposureEv, double isoGain, double noiseLuma, double noiseChroma,
                          double resolutionScale, double dynamicRangeEv,
                          double focalMm, double fNumber, double focusM, double sensorWidthMm,
                          double vignettingEv, double maxAperture, double lensSharpness,
                          double wbR, double wbG, double wbB,
                          double contrast, double saturation, boolean monochrome,
                          double tintR, double tintG, double tintB, double shadowLift,
                          long seed) {

    public static ImageParams neutral() {
        return new ImageParams(0, 1, 0, 0, 1, 14, 50, 8, 1e9, 36, 0, 8, 1, 1, 1, 1, 1, 1, false, 1, 1, 1, 0, 1);
    }

    public ImageParams withExposure(double ev) {
        return new ImageParams(ev, isoGain, noiseLuma, noiseChroma, resolutionScale, dynamicRangeEv, focalMm, fNumber,
                focusM, sensorWidthMm, vignettingEv, maxAperture, lensSharpness, wbR, wbG, wbB, contrast, saturation,
                monochrome, tintR, tintG, tintB, shadowLift, seed);
    }

    public ImageParams withFocus(double focal, double f, double focus) {
        return new ImageParams(exposureEv, isoGain, noiseLuma, noiseChroma, resolutionScale, dynamicRangeEv, focal, f,
                focus, sensorWidthMm, vignettingEv, maxAperture, lensSharpness, wbR, wbG, wbB, contrast, saturation,
                monochrome, tintR, tintG, tintB, shadowLift, seed);
    }

    public ImageParams withNoise(double iso, double luma, double chroma) {
        return new ImageParams(exposureEv, iso, luma, chroma, resolutionScale, dynamicRangeEv, focalMm, fNumber,
                focusM, sensorWidthMm, vignettingEv, maxAperture, lensSharpness, wbR, wbG, wbB, contrast, saturation,
                monochrome, tintR, tintG, tintB, shadowLift, seed);
    }
}
