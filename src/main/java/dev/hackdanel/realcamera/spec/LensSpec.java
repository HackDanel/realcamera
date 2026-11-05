package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.registry.RCRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/** An interchangeable lens. Loaded from data/<ns>/realcamera/lens/*.json. */
public record LensSpec(
        String brand,
        String exifLensModel,
        ResourceKey<MountSpec> mount,
        String imageCircle,
        float focalMin,
        float focalMax,
        float apertureWide,
        float apertureTele,
        float apertureMin,
        float minFocusM,
        Af af,
        float oisStops,
        Optics optics,
        int filterMm,
        boolean tcCompatible,
        ResourceLocation model
) {
    public record Af(String motor, float speed) {
        public static final Codec<Af> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("motor", "fast").forGetter(Af::motor),
                Codec.FLOAT.optionalFieldOf("speed", 1f).forGetter(Af::speed)
        ).apply(i, Af::new));
        public static final Af DEFAULT = new Af("fast", 1f);
    }

    /**
     * Optical character. blades/rounded: aperture shape (bokeh highlights); vignettingEv: corner fall-off
     * wide open; sharpness: 1 neutral; distortion: radial k1 at the wide and long end (- barrel, + pincushion);
     * caPx: lateral chromatic aberration at the corner in pixels of a 4K image; flare: ghosting strength;
     * bokeh: "smooth", "busy" (onion rings), "swirl" (Helios-style) or "bubble" (Trioplan soap bubbles);
     * projection: "rectilinear" or a fisheye mapping ("equisolid", "stereographic", "equidistant");
     * tiltShift: movements of a tilt-shift lens; maxMagnification: reproduction ratio at the closest focus
     * (1 = 1:1 macro; 0 = derive from focal length and minimum focus distance).
     */
    public record Optics(int blades, boolean rounded, float vignettingEv, float sharpness, float distortionWide,
                         float distortionTele, float caPx, float flare, String bokeh, String projection,
                         java.util.Optional<TiltShift> tiltShift, float maxMagnification) {
        public static final Codec<Optics> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("blades", 7).forGetter(Optics::blades),
                Codec.BOOL.optionalFieldOf("rounded", true).forGetter(Optics::rounded),
                Codec.FLOAT.optionalFieldOf("vignetting_ev", 1f).forGetter(Optics::vignettingEv),
                Codec.FLOAT.optionalFieldOf("sharpness", 1f).forGetter(Optics::sharpness),
                Codec.FLOAT.optionalFieldOf("distortion_wide", 0f).forGetter(Optics::distortionWide),
                Codec.FLOAT.optionalFieldOf("distortion_tele", 0f).forGetter(Optics::distortionTele),
                Codec.FLOAT.optionalFieldOf("ca_px", 1f).forGetter(Optics::caPx),
                Codec.FLOAT.optionalFieldOf("flare", 0.5f).forGetter(Optics::flare),
                Codec.STRING.optionalFieldOf("bokeh", "smooth").forGetter(Optics::bokeh),
                Codec.STRING.optionalFieldOf("projection", "rectilinear").forGetter(Optics::projection),
                TiltShift.CODEC.optionalFieldOf("tilt_shift").forGetter(Optics::tiltShift),
                Codec.FLOAT.optionalFieldOf("max_magnification", 0f).forGetter(Optics::maxMagnification)
        ).apply(i, Optics::new));
        public static final Optics DEFAULT = new Optics(7, true, 1f, 1f, 0f, 0f, 1f, 0.5f, "smooth", "rectilinear",
                java.util.Optional.empty(), 0f);

        public boolean fisheye() {
            return !"rectilinear".equals(projection);
        }
    }

    /** Tilt-shift movements: maximum tilt (degrees, either way) and shift (mm, either way). */
    public record TiltShift(float tiltDeg, float shiftMm) {
        public static final Codec<TiltShift> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("tilt_deg").forGetter(TiltShift::tiltDeg),
                Codec.FLOAT.fieldOf("shift_mm").forGetter(TiltShift::shiftMm)
        ).apply(i, TiltShift::new));
    }

    public static final Codec<LensSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("brand").forGetter(LensSpec::brand),
            Codec.STRING.fieldOf("exif_lens_model").forGetter(LensSpec::exifLensModel),
            ResourceKey.codec(RCRegistries.MOUNT).fieldOf("mount").forGetter(LensSpec::mount),
            Codec.STRING.optionalFieldOf("image_circle", "ff").forGetter(LensSpec::imageCircle),
            Codec.FLOAT.fieldOf("focal_min").forGetter(LensSpec::focalMin),
            Codec.FLOAT.fieldOf("focal_max").forGetter(LensSpec::focalMax),
            Codec.FLOAT.fieldOf("aperture_wide").forGetter(LensSpec::apertureWide),
            Codec.FLOAT.fieldOf("aperture_tele").forGetter(LensSpec::apertureTele),
            Codec.FLOAT.optionalFieldOf("aperture_min", 22f).forGetter(LensSpec::apertureMin),
            Codec.FLOAT.fieldOf("min_focus_m").forGetter(LensSpec::minFocusM),
            Af.CODEC.optionalFieldOf("af", Af.DEFAULT).forGetter(LensSpec::af),
            Codec.FLOAT.optionalFieldOf("ois_stops", 0f).forGetter(LensSpec::oisStops),
            Optics.CODEC.optionalFieldOf("optics", Optics.DEFAULT).forGetter(LensSpec::optics),
            Codec.INT.optionalFieldOf("filter_mm", 67).forGetter(LensSpec::filterMm),
            Codec.BOOL.optionalFieldOf("tc_compatible", false).forGetter(LensSpec::tcCompatible),
            ResourceLocation.CODEC.fieldOf("model").forGetter(LensSpec::model)
    ).apply(i, LensSpec::new));

    public int blades() {
        return optics.blades();
    }

    /** Radial distortion coefficient at a focal length (linear between the ends of a zoom). */
    public float distortionAt(float focal) {
        if (!isZoom()) {
            return optics.distortionWide();
        }
        float t = (clampFocal(focal) - focalMin) / (focalMax - focalMin);
        return optics.distortionWide() + (optics.distortionTele() - optics.distortionWide()) * t;
    }

    /** Reproduction ratio at the closest focus distance (thin lens), unless the spec states it. */
    public float maxMagnification() {
        if (optics.maxMagnification() > 0) return optics.maxMagnification();
        return (float) dev.hackdanel.realcamera.optics.Optics.magnification(focalMax, minFocusM);
    }

    public boolean isZoom() {
        return focalMax > focalMin + 0.01f;
    }

    public float clampFocal(float focal) {
        return Mth.clamp(focal, focalMin, focalMax);
    }

    /** Widest aperture available at the given focal length (variable-aperture zooms). */
    public float maxApertureAt(float focal) {
        if (!isZoom()) {
            return apertureWide;
        }
        float t = (clampFocal(focal) - focalMin) / (focalMax - focalMin);
        return apertureWide + (apertureTele - apertureWide) * t;
    }
}
