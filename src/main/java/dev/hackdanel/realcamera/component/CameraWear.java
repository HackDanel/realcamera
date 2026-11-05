package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Wear and damage of a camera body.
 *
 * @param shutterJammed the mechanical shutter failed (past its rated life); needs a new shutter unit
 * @param moisture      0..100: >40 fogs the optics, 100 kills the electronics of unsealed bodies
 * @param dead          electronics destroyed by water (repair at the workshop)
 * @param dust          sensor dust spots: x, y in frame units (-1..1), radius as a fraction of the width
 */
public record CameraWear(boolean shutterJammed, float moisture, boolean dead, List<Dust> dust) {
    public record Dust(float x, float y, float r) {
        public static final Codec<Dust> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("x").forGetter(Dust::x),
                Codec.FLOAT.fieldOf("y").forGetter(Dust::y),
                Codec.FLOAT.fieldOf("r").forGetter(Dust::r)
        ).apply(i, Dust::new));
        public static final StreamCodec<ByteBuf, Dust> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Dust::x, ByteBufCodecs.FLOAT, Dust::y, ByteBufCodecs.FLOAT, Dust::r, Dust::new);
    }

    public static final CameraWear NEW = new CameraWear(false, 0, false, List.of());

    public static final Codec<CameraWear> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("shutter_jammed", false).forGetter(CameraWear::shutterJammed),
            Codec.FLOAT.optionalFieldOf("moisture", 0f).forGetter(CameraWear::moisture),
            Codec.BOOL.optionalFieldOf("dead", false).forGetter(CameraWear::dead),
            Dust.CODEC.listOf().optionalFieldOf("dust", List.of()).forGetter(CameraWear::dust)
    ).apply(i, CameraWear::new));

    public static final StreamCodec<ByteBuf, CameraWear> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, CameraWear::shutterJammed,
            ByteBufCodecs.FLOAT, CameraWear::moisture,
            ByteBufCodecs.BOOL, CameraWear::dead,
            Dust.STREAM_CODEC.apply(ByteBufCodecs.list(64)), CameraWear::dust,
            CameraWear::new);

    public CameraWear withMoisture(float m) {
        return new CameraWear(shutterJammed, Math.max(0, Math.min(100, m)), dead, dust);
    }

    public CameraWear withJammed(boolean j) {
        return new CameraWear(j, moisture, dead, dust);
    }

    public CameraWear withDead(boolean d) {
        return new CameraWear(shutterJammed, moisture, d, dust);
    }

    public CameraWear withDust(Dust d) {
        List<Dust> list = new ArrayList<>(dust);
        if (list.size() < 24) list.add(d);
        return new CameraWear(shutterJammed, moisture, dead, List.copyOf(list));
    }

    public CameraWear clean() {
        return new CameraWear(shutterJammed, Math.max(0, moisture - 50), dead, List.of());
    }

    /** True if the camera cannot take pictures at all. */
    public boolean broken() {
        return shutterJammed || dead;
    }
}
