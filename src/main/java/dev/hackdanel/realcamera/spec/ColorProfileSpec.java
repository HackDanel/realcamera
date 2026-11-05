package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * In-camera colour rendering ("Creative Look", "Picture Style", film simulation...). Applied after
 * white balance: contrast S-curve, saturation, optional monochrome mix, and a gentle per-channel tint.
 */
public record ColorProfileSpec(String name, float contrast, float saturation, boolean monochrome,
                               float tintR, float tintG, float tintB, float shadowLift) {
    public static final Codec<ColorProfileSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(ColorProfileSpec::name),
            Codec.FLOAT.optionalFieldOf("contrast", 1f).forGetter(ColorProfileSpec::contrast),
            Codec.FLOAT.optionalFieldOf("saturation", 1f).forGetter(ColorProfileSpec::saturation),
            Codec.BOOL.optionalFieldOf("monochrome", false).forGetter(ColorProfileSpec::monochrome),
            Codec.FLOAT.optionalFieldOf("tint_r", 1f).forGetter(ColorProfileSpec::tintR),
            Codec.FLOAT.optionalFieldOf("tint_g", 1f).forGetter(ColorProfileSpec::tintG),
            Codec.FLOAT.optionalFieldOf("tint_b", 1f).forGetter(ColorProfileSpec::tintB),
            Codec.FLOAT.optionalFieldOf("shadow_lift", 0f).forGetter(ColorProfileSpec::shadowLift)
    ).apply(i, ColorProfileSpec::new));
}
