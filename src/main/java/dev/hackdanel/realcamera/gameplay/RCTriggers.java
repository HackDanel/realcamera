package dev.hackdanel.realcamera.gameplay;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Advancement triggers (plan §7.3): realcamera:photo_taken (conditions on what the server saw in the frame and
 * how it was taken) and realcamera:event (named deeds with a count: prints, frames_hung, film_developed …).
 */
public final class RCTriggers {
    private RCTriggers() {}

    public static final DeferredRegister<CriterionTrigger<?>> REGISTER = DeferredRegister.create(Registries.TRIGGER_TYPE, RealCamera.MODID);
    public static final Supplier<PhotoTrigger> PHOTO = REGISTER.register("photo_taken", PhotoTrigger::new);
    public static final Supplier<EventTrigger> EVENT = REGISTER.register("event", EventTrigger::new);

    // ------------------------------------------------------------------ photo_taken

    public static final class PhotoTrigger extends SimpleCriterionTrigger<PhotoTrigger.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, PhotoMeta meta) {
            trigger(player, i -> i.matches(meta));
        }

        /** Exposure and distance limits (0 = no limit). */
        public record Shot(float minShutter, float minDistance, float maxDistance, float maxAperture, float minMagnification,
                           float minSky) {
            public static final Codec<Shot> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.FLOAT.optionalFieldOf("min_shutter", 0f).forGetter(Shot::minShutter),
                    Codec.FLOAT.optionalFieldOf("min_distance", 0f).forGetter(Shot::minDistance),
                    Codec.FLOAT.optionalFieldOf("max_distance", 0f).forGetter(Shot::maxDistance),
                    Codec.FLOAT.optionalFieldOf("max_aperture", 0f).forGetter(Shot::maxAperture),
                    Codec.FLOAT.optionalFieldOf("min_magnification", 0f).forGetter(Shot::minMagnification),
                    Codec.FLOAT.optionalFieldOf("min_sky", 0f).forGetter(Shot::minSky)
            ).apply(i, Shot::new));
            public static final Shot ANY = new Shot(0, 0, 0, 0, 0, 0);
        }

        public record Instance(Optional<ContextAwarePredicate> player, Optional<String> entity, int minStars,
                               Optional<String> phenomenon, Optional<String> biome, Optional<String> dimension,
                               Optional<String> flag, Shot shot) implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.STRING.optionalFieldOf("entity").forGetter(Instance::entity),
                    Codec.INT.optionalFieldOf("min_stars", 0).forGetter(Instance::minStars),
                    Codec.STRING.optionalFieldOf("phenomenon").forGetter(Instance::phenomenon),
                    Codec.STRING.optionalFieldOf("biome").forGetter(Instance::biome),
                    Codec.STRING.optionalFieldOf("dimension").forGetter(Instance::dimension),
                    Codec.STRING.optionalFieldOf("flag").forGetter(Instance::flag),
                    Shot.CODEC.optionalFieldOf("shot", Shot.ANY).forGetter(Instance::shot)
            ).apply(i, Instance::new));

            public boolean matches(PhotoMeta meta) {
                return PhotoConditions.matches(meta, entity, minStars, phenomenon, biome, dimension, flag, shot);
            }
        }
    }

    // ------------------------------------------------------------------ event

    public static final class EventTrigger extends SimpleCriterionTrigger<EventTrigger.Instance> {
        @Override
        public Codec<Instance> codec() {
            return Instance.CODEC;
        }

        public void trigger(ServerPlayer player, String event, int value) {
            trigger(player, i -> i.event().equals(event) && value >= i.min());
        }

        public record Instance(Optional<ContextAwarePredicate> player, String event, int min)
                implements SimpleCriterionTrigger.SimpleInstance {
            public static final Codec<Instance> CODEC = RecordCodecBuilder.create(i -> i.group(
                    EntityPredicate.ADVANCEMENT_CODEC.optionalFieldOf("player").forGetter(Instance::player),
                    Codec.STRING.fieldOf("event").forGetter(Instance::event),
                    Codec.INT.optionalFieldOf("min", 1).forGetter(Instance::min)
            ).apply(i, Instance::new));
        }
    }
}
