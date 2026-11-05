package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/** Memory card model: form factor (sd / cfexpress_a / cfexpress_b), capacity and write speed. */
public record MemoryCardTypeSpec(String name, String form, int capacityGb, int writeMbS, ResourceLocation model) {
    public static final Codec<MemoryCardTypeSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(MemoryCardTypeSpec::name),
            Codec.STRING.fieldOf("form").forGetter(MemoryCardTypeSpec::form),
            Codec.INT.fieldOf("capacity_gb").forGetter(MemoryCardTypeSpec::capacityGb),
            Codec.INT.fieldOf("write_mb_s").forGetter(MemoryCardTypeSpec::writeMbS),
            ResourceLocation.CODEC.fieldOf("model").forGetter(MemoryCardTypeSpec::model)
    ).apply(i, MemoryCardTypeSpec::new));

    public long capacityBytes() {
        return capacityGb * 1_000_000_000L;
    }
}
