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
     * (1 = 1:1 macro; 0 = derive from focal length and minimum focus distance); loca: axial colour (purple/green
     * bokeh fringing) relative to a typical lens of its speed (0 = apochromatic, -1 = typical); transmissionLossEv:
     * light the glass absorbs and reflects, the gap between the f-stop and the T-stop (-1 = typical: more for zooms;
     * an apodisation filter (STF/APD) loses much more).
     */
    public record Optics(int blades, boolean rounded, float vignettingEv, float sharpness, float distortionWide,
                         float distortionTele, float caPx, float flare, String bokeh, String projection,
                         java.util.Optional<TiltShift> tiltShift, float maxMagnification, float loca,
                         float transmissionLossEv) {
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
                Codec.FLOAT.optionalFieldOf("max_magnification", 0f).forGetter(Optics::maxMagnification),
                Codec.FLOAT.optionalFieldOf("loca", -1f).forGetter(Optics::loca),
                Codec.FLOAT.optionalFieldOf("transmission_loss_ev", -1f).forGetter(Optics::transmissionLossEv)
        ).apply(i, Optics::new));
        public static final Optics DEFAULT = new Optics(7, true, 1f, 1f, 0f, 0f, 1f, 0.5f, "smooth", "rectilinear",
                java.util.Optional.empty(), 0f, -1f, -1f);

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

    /**
     * Reproduction ratio at the closest focus distance, as the lens's spec sheet states it. Without a stated value
     * the thin-lens estimate, capped at 0.25: ordinary lenses focus closer by shortening their focal length
     * (internal focusing), not by reaching macro ratios.
     */
    public float maxMagnification() {
        if (optics.maxMagnification() > 0) return optics.maxMagnification();
        return (float) Math.min(0.25, dev.hackdanel.realcamera.optics.Optics.magnification(focalMax, minFocusM));
    }

    /** Reproduction ratio at focal length {@code focal} focused at {@code focusM} (from the sensor). */
    public double magnificationAt(double focal, double focusM) {
        double s = Math.max(focusM, minFocusM);
        return framingAt(focal, s) / (s * 1000);
    }

    /**
     * Focal length (mm) whose angle of view the lens has at focal length {@code focal} focused at {@code focusM}
     * (from the sensor): the frame covers sensor size x distance / framing at the subject. At infinity it is the
     * nominal focal length; at the closest focus the lens reaches its spec-sheet magnification, which a zoom is
     * assumed to state for its long end and to scale with the focal length at its other settings (the minimum focus
     * distance stays the same). A unit-focusing lens narrows its view close up, an internally focusing one shortens
     * its focal length and may frame wider than at infinity (focus breathing). Between the two the framing changes
     * geometrically with the focus distance in dioptres. Closer than the minimum focus the lens stays at its closest.
     * Real lenses breathe by a quarter at most: wider than that means the catalogue's single minimum focus distance
     * is the short end's (telephoto zooms focus closer at the wide end).
     */
    public double framingAt(double focal, double focusM) {
        double s = Math.max(focusM, minFocusM);
        double closest = Math.max(0.75 * focal, minFocusM * 1000 * maxMagnification() * focal / focalMax);
        double closeness = Math.max(0, Math.min(1, minFocusM / s));
        return focal * Math.pow(closest / focal, closeness);
    }

    /**
     * Axial colour as a focus shift (mm, image space) between green and red/blue at a focal length: an achromat's
     * secondary spectrum grows with the focal length (here with its square root: long lenses use more special glass)
     * and fast lenses show more of it.
     */
    public double locaMm(float focal) {
        double k = optics.loca() >= 0 ? optics.loca() : exifLensModel.toUpperCase(java.util.Locale.ROOT).contains("APO") ? 0.2 : 1;
        double speed = Mth.clamp(2.8 / maxApertureAt(focal), 0.5, 2.5);
        return k * 0.5 * speed * Math.sqrt(50 * clampFocal(focal)) / 1000;
    }

    /** Light lost in the glass (EV): T-stop minus f-stop. Typical coated prime 0.15 EV, zoom 0.3 EV. */
    public double transmissionLossEv() {
        if (optics.transmissionLossEv() >= 0) return optics.transmissionLossEv();
        return isZoom() ? 0.3 : 0.15;
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
        // the f-number grows with the focal length and is shown (and set) in 1/3-stop steps: f/3.5, 4, 4.5, 5, 5.6
        double t = Math.log(clampFocal(focal) / focalMin) / Math.log(focalMax / focalMin);
        double av = Math.log(apertureWide) + (Math.log(apertureTele) - Math.log(apertureWide)) * t;
        double thirds = Math.ceil(av / Math.log(2) * 6 - 1e-6) / 6; // 1/3 stop = 1/6 of log2(N)
        float n = (float) Math.pow(2, thirds);
        return Mth.clamp(n, apertureWide, apertureTele);
    }
}
