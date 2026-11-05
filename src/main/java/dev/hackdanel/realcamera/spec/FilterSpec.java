package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Screw-in filter. kind: "nd" (neutral density, ndStops of light), "cpl" (circular polariser: darker,
 * more saturated sky, about 1.5 stops), "uv" (protection only) or "color" (black-and-white contrast
 * filter: per-channel weights used when shooting monochrome). diameterMm must be at least the lens
 * thread (a step-up ring is assumed for larger filters).
 */
public record FilterSpec(String name, String kind, float ndStops, float r, float g, float b, int diameterMm,
                         ResourceLocation model) {
    public static final Codec<FilterSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(FilterSpec::name),
            Codec.STRING.fieldOf("kind").forGetter(FilterSpec::kind),
            Codec.FLOAT.optionalFieldOf("nd_stops", 0f).forGetter(FilterSpec::ndStops),
            Codec.FLOAT.optionalFieldOf("r", 1f).forGetter(FilterSpec::r),
            Codec.FLOAT.optionalFieldOf("g", 1f).forGetter(FilterSpec::g),
            Codec.FLOAT.optionalFieldOf("b", 1f).forGetter(FilterSpec::b),
            Codec.INT.fieldOf("diameter_mm").forGetter(FilterSpec::diameterMm),
            ResourceLocation.CODEC.fieldOf("model").forGetter(FilterSpec::model)
    ).apply(i, FilterSpec::new));

    /** Light the filter takes away, in EV. */
    public float lightLossEv() {
        return switch (kind) {
            case "cpl" -> 1.5f;
            case "color" -> 1f;
            case "uv" -> 0f;
            default -> ndStops;
        };
    }
}
