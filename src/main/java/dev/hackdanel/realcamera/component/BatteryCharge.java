package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/** Battery state. Capacity is copied from the battery type so the item bar works without registries. */
public record BatteryCharge(int charge, int capacity) {
    public static final Codec<BatteryCharge> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("charge").forGetter(BatteryCharge::charge),
            Codec.INT.fieldOf("capacity").forGetter(BatteryCharge::capacity)
    ).apply(i, BatteryCharge::new));

    public static final StreamCodec<ByteBuf, BatteryCharge> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BatteryCharge::charge,
            ByteBufCodecs.VAR_INT, BatteryCharge::capacity,
            BatteryCharge::new);

    public BatteryCharge withCharge(int c) {
        return new BatteryCharge(Mth.clamp(c, 0, capacity), capacity);
    }

    public float fraction() {
        return capacity <= 0 ? 0 : (float) charge / capacity;
    }

    public int percent() {
        return Math.round(fraction() * 100);
    }
}
