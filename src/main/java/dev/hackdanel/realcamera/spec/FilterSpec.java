package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Screw-in filter. kind:
 * <ul>
 * <li>"nd" — neutral density, {@code ndStops} of light;</li>
 * <li>"cpl" — circular polariser: darker, more saturated sky, about 1.5 stops;</li>
 * <li>"uv" — protection only;</li>
 * <li>"color" — coloured glass: per-channel transmission (contrast filters for black and white, 80A/85 colour
 *     conversion), {@code ndStops} of light (1 if not given);</li>
 * <li>"mist" — diffusion (Black Mist): highlights glow into their surroundings, shadows lift; {@code mist} is the
 *     strength (1/8 = 0.125, 1/4 = 0.25);</li>
 * <li>"gnd" — graduated ND, soft edge: the top half darkened by {@code gndStops}, for bright skies;</li>
 * <li>"star" — cross screen: bright points get {@code starPoints} rays.</li>
 * </ul>
 * diameterMm must match the lens thread, or the filter sits on a step-up ring ({@link StepUpRingSpec}) whose input
 * matches the thread and whose output is at most the filter's size; a lens without a thread (bulging fisheye front
 * element, compact cameras) takes none.
 */
public record FilterSpec(String name, String kind, float ndStops, float r, float g, float b, int diameterMm,
                         ResourceLocation model, float mist, float gndStops, int starPoints) {
    public static final Codec<FilterSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(FilterSpec::name),
            Codec.STRING.fieldOf("kind").forGetter(FilterSpec::kind),
            Codec.FLOAT.optionalFieldOf("nd_stops", 0f).forGetter(FilterSpec::ndStops),
            Codec.FLOAT.optionalFieldOf("r", 1f).forGetter(FilterSpec::r),
            Codec.FLOAT.optionalFieldOf("g", 1f).forGetter(FilterSpec::g),
            Codec.FLOAT.optionalFieldOf("b", 1f).forGetter(FilterSpec::b),
            Codec.INT.fieldOf("diameter_mm").forGetter(FilterSpec::diameterMm),
            ResourceLocation.CODEC.fieldOf("model").forGetter(FilterSpec::model),
            Codec.FLOAT.optionalFieldOf("mist", 0f).forGetter(FilterSpec::mist),
            Codec.FLOAT.optionalFieldOf("gnd_stops", 0f).forGetter(FilterSpec::gndStops),
            Codec.INT.optionalFieldOf("star_points", 0).forGetter(FilterSpec::starPoints)
    ).apply(i, FilterSpec::new));

    /** Light the filter takes away, in EV (what the meter sees through it). */
    public float lightLossEv() {
        return switch (kind) {
            case "cpl" -> 1.5f;
            case "color" -> ndStops > 0 ? ndStops : 1f;
            case "uv", "mist", "gnd", "star" -> 0f;
            default -> ndStops;
        };
    }

    /**
     * Fits a lens with this filter thread (mm; 0 = the lens takes no screw-in filter), screwed on directly
     * ({@code ring} null) or through the step-up ring it carries.
     */
    public boolean fits(int lensThreadMm, @org.jetbrains.annotations.Nullable StepUpRingSpec ring) {
        return ring == null ? lensThreadMm > 0 && diameterMm == lensThreadMm : ring.fits(lensThreadMm) && ring.takes(diameterMm);
    }
}
