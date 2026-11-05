package dev.hackdanel.realcamera.photo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Everything known about one stored photo. Shared by the server index, card listings and prints. */
public record PhotoMeta(UUID id, UUID authorId, String authorName, long createdMillis, int width, int height,
                        Exif exif, Place place) {

    public record Exif(String camera, String lens, float fNumber, double shutter, int iso, float focal,
                       int focal35, int mode, int evComp, int whiteBalance) {
        public static final Codec<Exif> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("camera").forGetter(Exif::camera),
                Codec.STRING.fieldOf("lens").forGetter(Exif::lens),
                Codec.FLOAT.fieldOf("f").forGetter(Exif::fNumber),
                Codec.DOUBLE.fieldOf("t").forGetter(Exif::shutter),
                Codec.INT.fieldOf("iso").forGetter(Exif::iso),
                Codec.FLOAT.fieldOf("focal").forGetter(Exif::focal),
                Codec.INT.fieldOf("focal35").forGetter(Exif::focal35),
                Codec.INT.fieldOf("mode").forGetter(Exif::mode),
                Codec.INT.fieldOf("ev_comp").forGetter(Exif::evComp),
                Codec.INT.fieldOf("wb").forGetter(Exif::whiteBalance)
        ).apply(i, Exif::new));
    }

    public record Place(String dimension, String biome, int x, int y, int z, long gameDay) {
        public static final Codec<Place> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("dimension").forGetter(Place::dimension),
                Codec.STRING.fieldOf("biome").forGetter(Place::biome),
                Codec.INT.fieldOf("x").forGetter(Place::x),
                Codec.INT.fieldOf("y").forGetter(Place::y),
                Codec.INT.fieldOf("z").forGetter(Place::z),
                Codec.LONG.fieldOf("game_day").forGetter(Place::gameDay)
        ).apply(i, Place::new));

        public Place hideCoordinates() {
            return new Place(dimension, biome, 0, Integer.MIN_VALUE, 0, gameDay);
        }

        public boolean hasCoordinates() {
            return y != Integer.MIN_VALUE;
        }
    }

    public static final Codec<PhotoMeta> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(PhotoMeta::id),
            UUIDUtil.CODEC.fieldOf("author_id").forGetter(PhotoMeta::authorId),
            Codec.STRING.fieldOf("author").forGetter(PhotoMeta::authorName),
            Codec.LONG.fieldOf("created").forGetter(PhotoMeta::createdMillis),
            Codec.INT.fieldOf("width").forGetter(PhotoMeta::width),
            Codec.INT.fieldOf("height").forGetter(PhotoMeta::height),
            Exif.CODEC.fieldOf("exif").forGetter(PhotoMeta::exif),
            Place.CODEC.fieldOf("place").forGetter(PhotoMeta::place)
    ).apply(i, PhotoMeta::new));

    public static final StreamCodec<ByteBuf, PhotoMeta> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    public PhotoMeta withId(UUID newId) {
        return new PhotoMeta(newId, authorId, authorName, createdMillis, width, height, exif, place);
    }

    public PhotoMeta withSize(int w, int h) {
        return new PhotoMeta(id, authorId, authorName, createdMillis, w, h, exif, place);
    }

    public PhotoMeta withPlace(Place p) {
        return new PhotoMeta(id, authorId, authorName, createdMillis, width, height, exif, p);
    }
}
