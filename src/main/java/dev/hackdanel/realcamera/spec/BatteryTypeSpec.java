package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/** Battery model. Capacity is measured in shots (CIPA-like), the unit everything else drains in. */
public record BatteryTypeSpec(String name, int capacity, ResourceLocation model) {
    public static final Codec<BatteryTypeSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(BatteryTypeSpec::name),
            Codec.INT.fieldOf("capacity").forGetter(BatteryTypeSpec::capacity),
            ResourceLocation.CODEC.fieldOf("model").forGetter(BatteryTypeSpec::model)
    ).apply(i, BatteryTypeSpec::new));
}
