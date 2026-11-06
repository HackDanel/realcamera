package dev.hackdanel.realcamera.gameplay;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * The photo encyclopedia of one player (plan §7.2): one page per creature, biome, dimension and phenomenon,
 * holding the best photo so far. Page keys: "creature/minecraft:panda", "biome/minecraft:plains",
 * "dimension/minecraft:the_nether", "phenomenon/sunset". Counters keep tallies for advancements
 * ("frames_hung" …). Stored as a player attachment that survives death.
 */
public record Journal(Map<String, Page> pages, Map<String, Integer> counters) {
    public record Page(UUID photo, int quality, int stars, long day) {
        public static final Codec<Page> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("photo").forGetter(Page::photo),
                Codec.INT.fieldOf("quality").forGetter(Page::quality),
                Codec.INT.fieldOf("stars").forGetter(Page::stars),
                Codec.LONG.optionalFieldOf("day", 0L).forGetter(Page::day)
        ).apply(i, Page::new));
    }

    public static final Codec<Journal> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Page.CODEC).optionalFieldOf("pages", Map.of()).forGetter(Journal::pages),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("counters", Map.of()).forGetter(Journal::counters)
    ).apply(i, Journal::new));

    public static final StreamCodec<ByteBuf, Journal> STREAM_CODEC = ByteBufCodecs.fromCodec(CODEC);
    public static final Journal EMPTY = new Journal(Map.of(), Map.of());

    public static final String CREATURE = "creature/", BIOME = "biome/", DIMENSION = "dimension/", PHENOMENON = "phenomenon/";
    /** Phenomena the encyclopedia has pages for (FrameAnalyzer recognises them). */
    public static final List<String> PHENOMENA = List.of("sunrise", "sunset", "starry_night", "star_trails", "full_moon",
            "rain", "thunderstorm", "lightning", "snowfall", "end_sky", "underwater");

    public static final DeferredRegister<AttachmentType<?>> REGISTER =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, RealCamera.MODID);
    public static final Supplier<AttachmentType<Journal>> TYPE = REGISTER.register("journal",
            () -> AttachmentType.builder(() -> EMPTY).serialize(CODEC).copyOnDeath().build());

    public int count(String prefix) {
        return (int) pages.keySet().stream().filter(k -> k.startsWith(prefix)).count();
    }

    public int counter(String name) {
        return counters.getOrDefault(name, 0);
    }

    public Journal withCounter(String name, int value) {
        Map<String, Integer> c = new HashMap<>(counters);
        c.put(name, value);
        return new Journal(pages, c);
    }

    /** Pages a photo opens or improves (better quality replaces the old picture). */
    public static List<String> keysFor(PhotoMeta meta) {
        List<String> keys = new java.util.ArrayList<>();
        keys.add(DIMENSION + meta.place().dimension());
        if (!meta.sceneBiome().isEmpty()) keys.add(BIOME + meta.sceneBiome());
        meta.analysis().ifPresent(a -> {
            for (PhotoMeta.Subject s : a.subjects()) {
                if (s.inFocus() && s.area() >= 0.01f && !s.type().equals("minecraft:player")) {
                    keys.add(CREATURE + s.type());
                }
            }
            for (String p : a.phenomena()) keys.add(PHENOMENON + p);
        });
        return keys.stream().distinct().toList();
    }

    /** Returns the updated journal and which pages were new. */
    public Journal record(PhotoMeta meta, List<String> newPages) {
        Map<String, Page> p = new HashMap<>(pages);
        int quality = meta.analysis().map(PhotoMeta.Analysis::quality).orElse(0);
        int stars = meta.stars();
        for (String key : keysFor(meta)) {
            Page old = p.get(key);
            if (old == null) newPages.add(key);
            if (old == null || quality > old.quality()) {
                p.put(key, new Page(meta.id(), quality, stars, meta.place().gameDay()));
            }
        }
        return new Journal(p, counters);
    }
}
