package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** A lens mount (bayonet). Flange distance decides which adapters are physically possible. */
public record MountSpec(String name, float flangeMm, float throatMm) {
    public static final Codec<MountSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(MountSpec::name),
            Codec.FLOAT.fieldOf("flange_mm").forGetter(MountSpec::flangeMm),
            Codec.FLOAT.fieldOf("throat_mm").forGetter(MountSpec::throatMm)
    ).apply(i, MountSpec::new));
}
