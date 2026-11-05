package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.registry.RCRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * Mount adapter (lens side "from", body side "to"). Only physically possible adapters should exist:
 * the lens mount's flange distance must be longer than the body's. af: "full", "slow" or "none"
 * (manual focus only); electronic: whether aperture control and lens data pass through.
 */
public record AdapterSpec(String name, ResourceKey<MountSpec> from, ResourceKey<MountSpec> to, String af,
                          float afSpeed, boolean electronic, float lengthMm, ResourceLocation model) {
    public static final Codec<AdapterSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(AdapterSpec::name),
            ResourceKey.codec(RCRegistries.MOUNT).fieldOf("from").forGetter(AdapterSpec::from),
            ResourceKey.codec(RCRegistries.MOUNT).fieldOf("to").forGetter(AdapterSpec::to),
            Codec.STRING.optionalFieldOf("af", "slow").forGetter(AdapterSpec::af),
            Codec.FLOAT.optionalFieldOf("af_speed", 0.7f).forGetter(AdapterSpec::afSpeed),
            Codec.BOOL.optionalFieldOf("electronic", true).forGetter(AdapterSpec::electronic),
            Codec.FLOAT.fieldOf("length_mm").forGetter(AdapterSpec::lengthMm),
            ResourceLocation.CODEC.fieldOf("model").forGetter(AdapterSpec::model)
    ).apply(i, AdapterSpec::new));

    public boolean allowsAf() {
        return !"none".equals(af);
    }
}
