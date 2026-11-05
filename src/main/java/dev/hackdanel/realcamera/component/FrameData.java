package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Wall frame item variant: size in blocks and frame material. */
public record FrameData(int width, int height, String material) {
    public static final Codec<FrameData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("width").forGetter(FrameData::width),
            Codec.INT.fieldOf("height").forGetter(FrameData::height),
            Codec.STRING.fieldOf("material").forGetter(FrameData::material)
    ).apply(i, FrameData::new));

    public static final StreamCodec<ByteBuf, FrameData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FrameData::width,
            ByteBufCodecs.VAR_INT, FrameData::height,
            ByteBufCodecs.STRING_UTF8, FrameData::material,
            FrameData::new);

    public static final int[][] SIZES = {{1, 1}, {2, 1}, {1, 2}, {2, 2}, {3, 2}, {2, 3}, {4, 3}, {3, 4}};
    public static final String[] MATERIALS = {"oak", "spruce", "dark_oak", "birch", "cherry", "bamboo",
            "black_metal", "gold"};
}
