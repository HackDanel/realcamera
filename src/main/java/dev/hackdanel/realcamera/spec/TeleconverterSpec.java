package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.registry.RCRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Teleconverter: multiplies focal length and f-number by {@code factor}; only fits tc_compatible lenses of its mount. */
public record TeleconverterSpec(String name, String exifName, ResourceKey<MountSpec> mount, float factor, float afSpeed,
                                float sharpness, float lengthMm, ResourceLocation model) {
    public static final Codec<TeleconverterSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(TeleconverterSpec::name),
            Codec.STRING.fieldOf("exif_name").forGetter(TeleconverterSpec::exifName),
            ResourceKey.codec(RCRegistries.MOUNT).fieldOf("mount").forGetter(TeleconverterSpec::mount),
            Codec.FLOAT.fieldOf("factor").forGetter(TeleconverterSpec::factor),
            Codec.FLOAT.optionalFieldOf("af_speed", 0.8f).forGetter(TeleconverterSpec::afSpeed),
            Codec.FLOAT.optionalFieldOf("sharpness", 0.95f).forGetter(TeleconverterSpec::sharpness),
            Codec.FLOAT.fieldOf("length_mm").forGetter(TeleconverterSpec::lengthMm),
            ResourceLocation.CODEC.fieldOf("model").forGetter(TeleconverterSpec::model)
    ).apply(i, TeleconverterSpec::new));
}
