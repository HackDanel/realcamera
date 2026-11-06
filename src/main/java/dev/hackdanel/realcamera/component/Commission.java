package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A newspaper's photo order (plan §7.4): what must be in the print (creature type, biome, phenomenon) and how
 * good it must be. Carried by the cost item of the editor's trade; a mixin on ItemCost#test checks prints
 * against it instead of comparing components.
 */
public record Commission(Optional<String> entity, Optional<String> biome, Optional<String> phenomenon, int minStars,
                         int tier) {
    public static final Codec<Commission> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("entity").forGetter(Commission::entity),
            Codec.STRING.optionalFieldOf("biome").forGetter(Commission::biome),
            Codec.STRING.optionalFieldOf("phenomenon").forGetter(Commission::phenomenon),
            Codec.INT.optionalFieldOf("min_stars", 1).forGetter(Commission::minStars),
            Codec.INT.optionalFieldOf("tier", 1).forGetter(Commission::tier)
    ).apply(i, Commission::new));
    public static final StreamCodec<ByteBuf, Commission> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);

    /**
     * The player trading with a newspaper editor right now (set around the vanilla trade checks by mixins), so an
     * order also knows who is selling. Null on the client's auto-fill.
     */
    public static final ThreadLocal<net.minecraft.world.entity.player.Player> TRADER = new ThreadLocal<>();

    /** Does this print's photo fulfil the order? A print with missing inks is not fit to publish. */
    public boolean matches(PrintData print) {
        PhotoMeta meta = print.photo();
        if (meta.analysis().isEmpty() || print.missingInks() != 0) return false;
        PhotoMeta.Analysis a = meta.analysis().get();
        if (a.stars() < minStars) return false;
        if (entity.isPresent() && !a.shows(entity.get())) return false;
        if (biome.isPresent() && !meta.sceneBiome().equals(biome.get())) return false;
        return phenomenon.isEmpty() || a.phenomena().contains(phenomenon.get());
    }
}
