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
        Optional<LensSpec> lens = Specs.lens(r, parts.lens());
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
        if (apscLensOnFf) {
            if (CameraHelper.settings(camera).cropMode() == CameraSettings.CROP_AUTO) {
                float k = 1.5f;
                sensor = new SensorSpec(sensor.widthMm() / k, sensor.heightMm() / k, sensor.megapixels() / (k * k),
                        sensor.baseIso(), sensor.isoMin(), sensor.isoMax(), sensor.dynamicRangeEv(),
                        sensor.noiseLuma(), sensor.noiseChroma());
                crop = true;
            } else {
                circle = 1 / 1.5f; // the lens only lights the middle of the full-frame sensor
            }
        }
        return Optional.of(new OpticalChain(body.get(), lens.get(), adapter, tc, sensor, crop, circle));
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

    public boolean afAvailable() {
        return (adapter == null || adapter.allowsAf()) && !"none".equals(lens.af().motor()) && body.af().speed() > 0;
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
