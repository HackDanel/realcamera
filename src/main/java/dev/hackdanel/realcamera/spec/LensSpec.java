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
        ResourceLocation model
) {
    public record Af(String motor, float speed) {
        public static final Codec<Af> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("motor", "fast").forGetter(Af::motor),
                Codec.FLOAT.optionalFieldOf("speed", 1f).forGetter(Af::speed)
        ).apply(i, Af::new));
        public static final Af DEFAULT = new Af("fast", 1f);
    }

    /** Optical character: aperture blade count, wide-open vignetting (EV at the corner), sharpness. */
    public record Optics(int blades, float vignettingEv, float sharpness) {
        public static final Codec<Optics> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("blades", 7).forGetter(Optics::blades),
                Codec.FLOAT.optionalFieldOf("vignetting_ev", 1f).forGetter(Optics::vignettingEv),
                Codec.FLOAT.optionalFieldOf("sharpness", 1f).forGetter(Optics::sharpness)
        ).apply(i, Optics::new));
        public static final Optics DEFAULT = new Optics(7, 1f, 1f);
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
            ResourceLocation.CODEC.fieldOf("model").forGetter(LensSpec::model)
    ).apply(i, LensSpec::new));

    public int blades() {
        return optics.blades();
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
