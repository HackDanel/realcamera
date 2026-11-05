package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Photo paper sheet: size (10x15, a5, a4, a3) and finish (glossy, matte). */
public record PaperData(String size, String finish) {
    public static final Codec<PaperData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("size").forGetter(PaperData::size),
            Codec.STRING.fieldOf("finish").forGetter(PaperData::finish)
    ).apply(i, PaperData::new));

    public static final StreamCodec<ByteBuf, PaperData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PaperData::size,
            ByteBufCodecs.STRING_UTF8, PaperData::finish,
            PaperData::new);

    public static final String[] SIZES = {"10x15", "a5", "a4", "a3"};
    public static final String[] FINISHES = {"glossy", "matte"};

    /** Ink units a print of this size consumes at 100 % coverage of one channel. */
    public int inkCost() {
        return switch (size) {
            case "a5" -> 2;
            case "a4" -> 4;
            case "a3" -> 8;
            default -> 1;
        };
    }
}
