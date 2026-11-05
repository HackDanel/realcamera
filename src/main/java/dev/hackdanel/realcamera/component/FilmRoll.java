package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * State of a roll of film. The exposed frames live on the server under {@link #id} (like a memory card),
 * hidden until the roll is developed.
 *
 * @param shot    frames exposed so far
 * @param push    development: -1 pull, 0 normal, +1/+2 push
 * @param fogged  the back was opened in the light before rewinding
 */
public record FilmRoll(Optional<UUID> id, String format, int state, int shot, int push, boolean fogged) {
    public static final int FRESH = 0, LOADED = 1, EXPOSED = 2, DEVELOPED = 3, RUINED = 4;

    public static final Codec<FilmRoll> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.optionalFieldOf("id").forGetter(FilmRoll::id),
            Codec.STRING.optionalFieldOf("format", "135").forGetter(FilmRoll::format),
            Codec.INT.fieldOf("state").forGetter(FilmRoll::state),
            Codec.INT.optionalFieldOf("shot", 0).forGetter(FilmRoll::shot),
            Codec.INT.optionalFieldOf("push", 0).forGetter(FilmRoll::push),
            Codec.BOOL.optionalFieldOf("fogged", false).forGetter(FilmRoll::fogged)
    ).apply(i, FilmRoll::new));

    public static final StreamCodec<ByteBuf, FilmRoll> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), FilmRoll::id,
            ByteBufCodecs.STRING_UTF8, FilmRoll::format,
            ByteBufCodecs.VAR_INT, FilmRoll::state,
            ByteBufCodecs.VAR_INT, FilmRoll::shot,
            ByteBufCodecs.VAR_INT, FilmRoll::push,
            ByteBufCodecs.BOOL, FilmRoll::fogged,
            FilmRoll::new);

    public static FilmRoll fresh(String format) {
        return new FilmRoll(Optional.empty(), format, FRESH, 0, 0, false);
    }

    public FilmRoll withState(int s) {
        return new FilmRoll(id, format, s, shot, push, fogged);
    }

    public FilmRoll withShot(int n) {
        return new FilmRoll(id, format, state, n, push, fogged);
    }

    public FilmRoll withId(UUID newId) {
        return new FilmRoll(Optional.of(newId), format, state, shot, push, fogged);
    }

    public FilmRoll developed(int pushStops) {
        return new FilmRoll(id, format, DEVELOPED, shot, pushStops, fogged);
    }

    public FilmRoll fog() {
        return new FilmRoll(id, format, state, shot, push, true);
    }
}
