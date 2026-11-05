package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.registry.RCRegistries;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Vertical battery grip: holds a second battery's worth of charge for the listed bodies. */
public record GripSpec(String name, List<ResourceKey<CameraBodySpec>> bodies, ResourceKey<BatteryTypeSpec> batteryType,
                       ResourceLocation model) {
    public static final Codec<GripSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(GripSpec::name),
            ResourceKey.codec(RCRegistries.CAMERA_BODY).listOf().fieldOf("bodies").forGetter(GripSpec::bodies),
            ResourceKey.codec(RCRegistries.BATTERY_TYPE).fieldOf("battery_type").forGetter(GripSpec::batteryType),
            ResourceLocation.CODEC.fieldOf("model").forGetter(GripSpec::model)
    ).apply(i, GripSpec::new));
}
