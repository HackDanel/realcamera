package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Hot-shoe flash. gn: guide number at ISO 100 in metres (distance x f-number for a correct exposure at
 * full power); recycleS: seconds to recharge after a full-power pop; hss: high-speed sync above the
 * body's sync speed; capacity: full-power pops per battery set.
 */
public record FlashSpec(String name, float gn, float recycleS, boolean hss, int capacity, ResourceLocation model) {
    public static final Codec<FlashSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(FlashSpec::name),
            Codec.FLOAT.fieldOf("gn").forGetter(FlashSpec::gn),
            Codec.FLOAT.optionalFieldOf("recycle_s", 2f).forGetter(FlashSpec::recycleS),
            Codec.BOOL.optionalFieldOf("hss", true).forGetter(FlashSpec::hss),
            Codec.INT.optionalFieldOf("capacity", 300).forGetter(FlashSpec::capacity),
            ResourceLocation.CODEC.fieldOf("model").forGetter(FlashSpec::model)
    ).apply(i, FlashSpec::new));
}
