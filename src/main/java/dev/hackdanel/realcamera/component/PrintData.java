package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A physical print of a photo.
 *
 * @param missingInks bit mask (1=C, 2=M, 4=Y, 8=K) of cartridges that were empty when printed
 */
public record PrintData(PhotoMeta photo, String paperSize, String finish, int missingInks, String caption) {
    public static final Codec<PrintData> CODEC = RecordCodecBuilder.create(i -> i.group(
            PhotoMeta.CODEC.fieldOf("photo").forGetter(PrintData::photo),
            Codec.STRING.fieldOf("paper").forGetter(PrintData::paperSize),
            Codec.STRING.fieldOf("finish").forGetter(PrintData::finish),
            Codec.INT.optionalFieldOf("missing_inks", 0).forGetter(PrintData::missingInks),
            Codec.STRING.optionalFieldOf("caption", "").forGetter(PrintData::caption)
    ).apply(i, PrintData::new));

    public static final StreamCodec<ByteBuf, PrintData> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    public static final int INK_C = 1, INK_M = 2, INK_Y = 4, INK_K = 8;
}
