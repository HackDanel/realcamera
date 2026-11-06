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
                        Exif exif, Place place, java.util.Optional<UUID> editedFrom, java.util.Optional<Analysis> analysis) {

    public PhotoMeta(UUID id, UUID authorId, String authorName, long createdMillis, int width, int height, Exif exif,
                     Place place) {
        this(id, authorId, authorName, createdMillis, width, height, exif, place, java.util.Optional.empty(),
                java.util.Optional.empty());
    }

    /**
     * One thing the server saw in the frame: entity type id, share of the frame its box covers (0..1), whether it
     * was inside the depth of field, distance (m) and its centre in the frame (-1..1, y up).
     */
    public record Subject(String type, float area, boolean inFocus, float distance, float x, float y) {
        public static final Codec<Subject> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("type").forGetter(Subject::type),
                Codec.FLOAT.fieldOf("area").forGetter(Subject::area),
                Codec.BOOL.fieldOf("in_focus").forGetter(Subject::inFocus),
                Codec.FLOAT.fieldOf("distance").forGetter(Subject::distance),
                Codec.FLOAT.optionalFieldOf("x", 0f).forGetter(Subject::x),
                Codec.FLOAT.optionalFieldOf("y", 0f).forGetter(Subject::y)
        ).apply(i, Subject::new));
    }

    /**
     * What the server worked out about the frame from its own world state (plan §7.1): subjects, structure at the
     * focus point, share of sky, sun/moon in the frame, time of day, weather (0 clear, 1 rain, 2 thunder), natural
     * phenomena, how the picture was taken (FLAG_* bits), reproduction ratio, quality 0..100 and 1..3 stars.
     */
    public record Analysis(java.util.List<Subject> subjects, String structure, float sky, boolean sun, boolean moon,
                           int moonPhase, int timeOfDay, int weather, java.util.List<String> phenomena, int flags,
                           float magnification, int quality, int stars, String biome) {
        public Analysis(java.util.List<Subject> subjects, String structure, float sky, boolean sun, boolean moon,
                        int moonPhase, int timeOfDay, int weather, java.util.List<String> phenomena, int flags,
                        float magnification, int quality, int stars) {
            this(subjects, structure, sky, sun, moon, moonPhase, timeOfDay, weather, phenomena, flags, magnification,
                    quality, stars, "");
        }

        public static final Codec<Analysis> CODEC = RecordCodecBuilder.create(i -> i.group(
                Subject.CODEC.listOf().fieldOf("subjects").forGetter(Analysis::subjects),
                Codec.STRING.optionalFieldOf("structure", "").forGetter(Analysis::structure),
                Codec.FLOAT.optionalFieldOf("sky", 0f).forGetter(Analysis::sky),
                Codec.BOOL.optionalFieldOf("sun", false).forGetter(Analysis::sun),
                Codec.BOOL.optionalFieldOf("moon", false).forGetter(Analysis::moon),
                Codec.INT.optionalFieldOf("moon_phase", 0).forGetter(Analysis::moonPhase),
                Codec.INT.optionalFieldOf("time", 6000).forGetter(Analysis::timeOfDay),
                Codec.INT.optionalFieldOf("weather", 0).forGetter(Analysis::weather),
                Codec.STRING.listOf().optionalFieldOf("phenomena", java.util.List.of()).forGetter(Analysis::phenomena),
                Codec.INT.optionalFieldOf("flags", 0).forGetter(Analysis::flags),
                Codec.FLOAT.optionalFieldOf("magnification", 0f).forGetter(Analysis::magnification),
                Codec.INT.optionalFieldOf("quality", 0).forGetter(Analysis::quality),
                Codec.INT.optionalFieldOf("stars", 1).forGetter(Analysis::stars),
                Codec.STRING.optionalFieldOf("biome", "").forGetter(Analysis::biome)
        ).apply(i, Analysis::new));

        public static final int FLAG_TRIPOD = 1, FLAG_FILM = 2, FLAG_FLASH = 4, FLAG_ELECTRONIC = 8, FLAG_FISHEYE = 16,
                FLAG_TILT_SHIFT = 32, FLAG_ADAPTER = 64, FLAG_MEDIUM_FORMAT = 128, FLAG_UNDERWATER = 256;

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }

        /** The most prominent subject that is sharp, if any. */
        public java.util.Optional<Subject> mainSubject() {
            return subjects.stream().filter(Subject::inFocus).max(java.util.Comparator.comparingDouble(Subject::area));
        }

        /** Whether a sharp, reasonably large subject of this entity type is in the picture. */
        public boolean shows(String entityType) {
            return subjects.stream().anyMatch(s -> s.type().equals(entityType) && s.inFocus() && s.area() >= 0.01f);
        }

        public Analysis withPhenomena(java.util.List<String> p) {
            return new Analysis(subjects, structure, sky, sun, moon, moonPhase, timeOfDay, weather, p, flags,
                    magnification, quality, stars, biome);
        }
    }

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
            Place.CODEC.fieldOf("place").forGetter(PhotoMeta::place),
            UUIDUtil.CODEC.optionalFieldOf("edited_from").forGetter(PhotoMeta::editedFrom),
            Analysis.CODEC.optionalFieldOf("analysis").forGetter(PhotoMeta::analysis)
    ).apply(i, PhotoMeta::new));

    public static final StreamCodec<ByteBuf, PhotoMeta> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    public PhotoMeta withId(UUID newId) {
        return new PhotoMeta(newId, authorId, authorName, createdMillis, width, height, exif, place, editedFrom, analysis);
    }

    public PhotoMeta withSize(int w, int h) {
        return new PhotoMeta(id, authorId, authorName, createdMillis, w, h, exif, place, editedFrom, analysis);
    }

    public PhotoMeta withPlace(Place p) {
        return new PhotoMeta(id, authorId, authorName, createdMillis, width, height, exif, p, editedFrom, analysis);
    }

    public PhotoMeta withAnalysis(java.util.Optional<Analysis> a) {
        return new PhotoMeta(id, authorId, authorName, createdMillis, width, height, exif, place, editedFrom, a);
    }

    public int stars() {
        return analysis.map(Analysis::stars).orElse(0);
    }

    /** A new photo made from this one in the editor. */
    /**
     * The biome the picture shows: where the lens was focused (with some sky or depth in the frame), as the server
     * worked it out. Empty for a shot of the ground at one's feet. Photos without an analysis use where they were taken.
     */
    public String sceneBiome() {
        String b = analysis.map(Analysis::biome).orElse("");
        if (b.equals(NO_BIOME)) return "";
        return b.isEmpty() ? place.biome() : b; // analysed before the focus-point biome existed: where it was taken
    }

    /** Analysis biome of a picture that shows no landscape (the ground at one's feet). */
    public static final String NO_BIOME = "-";

    public PhotoMeta editedCopy(UUID newId, int w, int h) {
        return new PhotoMeta(newId, authorId, authorName, createdMillis, w, h, exif, place, java.util.Optional.of(id), analysis);
    }
}
