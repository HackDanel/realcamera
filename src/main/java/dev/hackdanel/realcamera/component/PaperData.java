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

    /** Paper sizes, smallest first. */
    public static final String[] SIZES = {"10x15", "a5", "a4", "a3"};
    public static final String[] FINISHES = {"glossy", "matte"};

    /**
     * Largest side (blocks) of a wall frame a print on this paper may hang in: 10x15 and A5 only 1x1, A4 up to 2x2,
     * A3 any frame. An unknown size (none recorded) is not limited.
     */
    public static int maxFrameSide(String size) {
        return switch (size) {
            case "10x15", "a5" -> 1;
            case "a4" -> 2;
            default -> Integer.MAX_VALUE;
        };
    }

    /** Whether a print on this paper may hang in a wall frame of width x height blocks. */
    public static boolean fitsFrame(String size, int width, int height) {
        return Math.max(width, height) <= maxFrameSide(size);
    }

    /** The smallest paper whose print may hang in a wall frame of width x height blocks. */
    public static String smallestFor(int width, int height) {
        for (String s : SIZES) {
            if (fitsFrame(s, width, height)) return s;
        }
        return SIZES[SIZES.length - 1];
    }

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
