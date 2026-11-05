package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Prints kept in a photo album, in page order (two per spread). */
public record AlbumData(List<PrintData> prints) {
    public static final int CAPACITY = 48;
    public static final AlbumData EMPTY = new AlbumData(List.of());

    public static final Codec<AlbumData> CODEC = PrintData.CODEC.listOf().xmap(AlbumData::new, AlbumData::prints);
    public static final StreamCodec<ByteBuf, AlbumData> STREAM_CODEC = PrintData.STREAM_CODEC
            .apply(ByteBufCodecs.list(CAPACITY)).map(AlbumData::new, AlbumData::prints);

    public AlbumData {
        prints = List.copyOf(prints);
    }

    public AlbumData with(int index, PrintData p) {
        List<PrintData> l = new ArrayList<>(prints);
        l.set(index, p);
        return new AlbumData(l);
    }

    public AlbumData insert(int index, PrintData p) {
        List<PrintData> l = new ArrayList<>(prints);
        l.add(Math.max(0, Math.min(l.size(), index)), p);
        return new AlbumData(l);
    }

    public AlbumData remove(int index) {
        List<PrintData> l = new ArrayList<>(prints);
        l.remove(index);
        return new AlbumData(l);
    }
}
