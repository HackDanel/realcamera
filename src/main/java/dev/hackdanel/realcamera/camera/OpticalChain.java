package dev.hackdanel.realcamera.camera;

import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.spec.AdapterSpec;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import dev.hackdanel.realcamera.spec.SensorSpec;
import dev.hackdanel.realcamera.spec.TeleconverterSpec;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/**
 * Everything light passes through, resolved once: body, optional adapter and teleconverter, lens,
 * and the effective sensor area (APS-C crop when a crop-circle lens sits on a full-frame body).
 */
public record OpticalChain(CameraBodySpec body, LensSpec lens, @Nullable AdapterSpec adapter,
                           @Nullable TeleconverterSpec tc, SensorSpec sensor, boolean cropped, float imageCircle) {

    /** Why a combination does not work, or null if it does. Used for messages and slot checks. */
    public static String incompatibility(CameraBodySpec body, @Nullable LensSpec lens, @Nullable AdapterSpec adapter,
                                         @Nullable TeleconverterSpec tc) {
        if (adapter != null && !adapter.to().equals(body.mount())) {
            return "message.realcamera.adapter_mismatch";
        }
        var lensSideMount = adapter != null ? adapter.from() : body.mount();
        if (tc != null && !tc.mount().equals(lensSideMount)) {
            return "message.realcamera.tc_mismatch";
        }
        if (lens == null) {
            return null;
        }
        if (!lens.mount().equals(lensSideMount)) {
            return "message.realcamera.lens_mismatch";
        }
        if (tc != null && !lens.tcCompatible()) {
            return "message.realcamera.tc_incompatible";
        }
        return null;
    }

    public static Optional<OpticalChain> resolve(HolderLookup.Provider r, ItemStack camera) {
        Optional<CameraBodySpec> body = Specs.body(r, camera);
        CameraParts parts = CameraHelper.parts(camera);
        Optional<LensSpec> lens = body.isPresent() && body.get().hasFixedLens()
                ? Specs.lookup(r, dev.hackdanel.realcamera.registry.RCRegistries.LENS, body.get().fixedLens().get())
                : Specs.lens(r, parts.lens());
        if (body.isEmpty() || lens.isEmpty()) {
            return Optional.empty();
        }
        AdapterSpec adapter = Specs.adapter(r, parts.adapter()).orElse(null);
        TeleconverterSpec tc = Specs.teleconverter(r, parts.teleconverter()).orElse(null);
        if (incompatibility(body.get(), lens.get(), adapter, tc) != null) {
            return Optional.empty();
        }
        SensorSpec sensor = body.get().sensor();
        boolean crop = false;
        float circle = 1f;
        boolean apscLensOnFf = "apsc".equals(lens.get().imageCircle()) && sensor.cropFactor() < 1.2f;
        float lensDiag = circleDiagonalMm(lens.get().imageCircle());
        if (!apscLensOnFf && lensDiag < sensor.diagonalMm() * 0.97f && !lens.get().optics().fisheye()) {
            // a small-format lens on a bigger sensor (e.g. a full-frame lens on medium format): dark corners
            circle = lensDiag / sensor.diagonalMm();
        }
        if (apscLensOnFf) {
            if (CameraHelper.settings(camera).cropMode() == CameraSettings.CROP_AUTO) {
                float k = apscCrop(lens.get());
                sensor = new SensorSpec(sensor.widthMm() / k, sensor.heightMm() / k, sensor.megapixels() / (k * k),
                        sensor.baseIso(), sensor.isoMin(), sensor.isoMax(), sensor.dynamicRangeEv(),
                        sensor.noiseLuma(), sensor.noiseChroma());
                crop = true;
            } else {
                circle = 1 / apscCrop(lens.get()); // the lens only lights the middle of the full-frame sensor
            }
        }
        return Optional.of(new OpticalChain(body.get(), lens.get(), adapter, tc, sensor, crop, circle));
    }

    /** Diameter of the image circle a lens of the given class projects (mm). */
    public static float circleDiagonalMm(String imageCircle) {
        return switch (imageCircle) {
            case "mft" -> 21.8f;
            case "apsc" -> 28.6f;
            case "mf" -> 56f;
            case "6x6" -> 80f;
            default -> 43.5f;
        };
    }

    public int projection() {
        return dev.hackdanel.realcamera.optics.Optics.projection(lens.optics().projection());
    }

    /** Tilt-shift movements of the lens, if it has any. */
    public java.util.Optional<LensSpec.TiltShift> tiltShift() {
        return tc == null ? lens.optics().tiltShift() : java.util.Optional.empty();
    }

    /** Lens shift actually applied (mm, clamped to the lens's range; 0 for normal lenses). */
    public float shiftMm(CameraSettings s) {
        return tiltShift().map(t -> Math.max(-t.shiftMm(), Math.min(t.shiftMm(), s.shift()))).orElse(0f);
    }

    public float tiltDeg(CameraSettings s) {
        return tiltShift().map(t -> Math.max(-t.tiltDeg(), Math.min(t.tiltDeg(), s.tilt()))).orElse(0f);
    }

    /** Extra field the frame is rendered with when the lens is shifted (the corners of the re-projection). */
    public static final float SHIFT_OVERSCAN = 1.12f;

    /**
     * Vertical field of view to render the frame with: the sensor's for normal lenses, a capped wide view for
     * fisheyes (remapped later), a slightly wider one for a shifted lens.
     */
    public double renderVfovDeg(float focal, CameraSettings s) {
        return renderVfovDeg(focal, s, 1e9);
    }

    /**
     * Same, focused at {@code focusM}: close up the image moves away from the lens (v = f(1+m)) and the field of
     * view narrows, a macro lens at 1:1 frames a subject the size of the sensor.
     */
    public double renderVfovDeg(float focal, CameraSettings s, double focusM) {
        double framing = focal;
        if (projection() == dev.hackdanel.realcamera.optics.Optics.PROJ_RECTILINEAR && focusM < 100) {
            framing = Math.max(focal, focusM * 1000 * magnification(focusM));
        }
        double v = dev.hackdanel.realcamera.optics.Optics.renderVfovDeg(projection(), (float) framing, sensor.widthMm(), sensor.heightMm());
        if (shiftMm(s) != 0) {
            v = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(v) / 2) * SHIFT_OVERSCAN));
        }
        return v;
    }

    /** Camera pitch (degrees, + = up) the frame is rendered with for a shifted lens: atan(shift / f). */
    public double shiftPitchDeg(float focal, CameraSettings s) {
        return Math.toDegrees(Math.atan(shiftMm(s) / focal));
    }

    public float tcFactor() {
        return tc == null ? 1f : tc.factor();
    }

    public float focalMin() {
        return lens.focalMin() * tcFactor();
    }

    public float focalMax() {
        return lens.focalMax() * tcFactor();
    }

    public boolean isZoom() {
        return lens.isZoom();
    }

    /** Effective focal length for the stored setting (0 = widest). */
    public float focal(float setting) {
        float lensFocal = setting <= 0 ? lens.focalMin() : lens.clampFocal(setting / tcFactor());
        return lensFocal * tcFactor();
    }

    /** Lens focal length behind the teleconverter. */
    public float lensFocal(float effectiveFocal) {
        return lens.clampFocal(effectiveFocal / tcFactor());
    }

    public float maxApertureAt(float effectiveFocal) {
        return lens.maxApertureAt(lensFocal(effectiveFocal)) * tcFactor();
    }

    public float minAperture() {
        return lens.apertureMin() * tcFactor();
    }

    /** Crop of an APS-C lens's image circle: Canon EF-S / RF-S are made for the 1.6x sensor, the rest for 1.5x. */
    static float apscCrop(LensSpec lens) {
        return lens.mount().location().getPath().startsWith("canon") ? 1.6f : 1.5f;
    }

    /** Reproduction ratio focused at focusM (a teleconverter multiplies it). */
    public double magnification(double focusM) {
        return lens.magnificationAt(focusM) * tcFactor();
    }

    /** Light lost to the extension at close focus (the bellows factor), EV: 2 at 1:1. */
    public double bellowsLossEv(double focusM) {
        double m = lens.magnificationAt(focusM);
        return m < 0.05 ? 0 : 2 * Math.log(1 + m) / Math.log(2);
    }

    public boolean afAvailable() {
        return (adapter == null || adapter.allowsAf()) && !"none".equals(lens.af().motor()) && body.af().speed() > 0
                && phaseAfReaches();
    }

    /**
     * A DSLR's phase-detect AF module needs enough light from the lens: f/5.6 on entry bodies, f/8 on the others
     * (a slow zoom with a 2x teleconverter goes manual). Mirrorless bodies focus on the sensor at any aperture.
     */
    public boolean phaseAfReaches() {
        if (!"dslr".equals(body.info().type())) return true;
        float limit = "entry".equals(body.info().tier()) ? 5.6f : 8f;
        return lens.maxApertureAt(lens.focalMax()) * tcFactor() <= limit * 1.06f;
    }

    /**
     * Lowest scene EV the AF still locks at with this lens: body ratings are measured with an f/1.2 lens, a slower
     * one gives the sensor 2·log2(N/1.2) EV less light.
     */
    public double afLowLightEv(float effectiveFocal) {
        double n = maxApertureAt(effectiveFocal);
        return body.af().lowLightEv() + 2 * Math.max(0, Math.log(n / 1.2) / Math.log(2));
    }

    public double afSpeed() {
        double s = body.af().speed() * lens.af().speed();
        if (adapter != null) s *= adapter.afSpeed();
        if (tc != null) s *= tc.afSpeed();
        return s;
    }

    public float sharpness() {
        return lens.optics().sharpness() * (tc == null ? 1f : tc.sharpness());
    }

    /** Lens name as the camera would record it; a dumb adapter passes no lens data. */
    public String exifLensModel() {
        if (adapter != null && !adapter.electronic()) {
            return "----";
        }
        return lens.exifLensModel() + (tc == null ? "" : " + " + tc.exifName());
    }

    public float stabilizationStops() {
        float ibis = body.ibisStops(), ois = lens.oisStops();
        return Math.max(ibis, ois) + (ibis > 0 && ois > 0 ? 0.5f : 0f);
    }
}
