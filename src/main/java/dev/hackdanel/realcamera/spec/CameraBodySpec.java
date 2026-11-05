package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hackdanel.realcamera.registry.RCRegistries;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Everything that defines a camera body model. Loaded from data/<ns>/realcamera/camera_body/*.json. */
public record CameraBodySpec(
        Info info,
        ResourceKey<MountSpec> mount,
        SensorSpec sensor,
        String viewfinder,
        Shutter shutter,
        Af af,
        float ibisStops,
        ResourceKey<BatteryTypeSpec> batteryType,
        float batteryDrain,
        List<String> cardForms,
        int weatherSealing,
        List<ResourceKey<ColorProfileSpec>> colorProfiles,
        Flash flash,
        ResourceLocation model
) {
    /** Built-in flash guide number (0 = none) and the fastest shutter speed a flash syncs at (s). */
    public record Flash(float builtInGn, float sync) {
        public static final Codec<Flash> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.optionalFieldOf("built_in_gn", 0f).forGetter(Flash::builtInGn),
                Codec.FLOAT.optionalFieldOf("sync", 1 / 250f).forGetter(Flash::sync)
        ).apply(i, Flash::new));
        public static final Flash DEFAULT = new Flash(0, 1 / 250f);
    }

    /** Identity: brand, EXIF Make/Model written into exported JPEGs, tier and body type. */
    public record Info(String brand, String exifMake, String exifModel, String tier, String type) {
        public static final Codec<Info> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("brand").forGetter(Info::brand),
                Codec.STRING.fieldOf("exif_make").forGetter(Info::exifMake),
                Codec.STRING.fieldOf("exif_model").forGetter(Info::exifModel),
                Codec.STRING.fieldOf("tier").forGetter(Info::tier),
                Codec.STRING.fieldOf("type").forGetter(Info::type)
        ).apply(i, Info::new));
    }

    /**
     * Shutter: fastest mechanical / electronic speed and slowest timed speed (s), rated life, sound set,
     * sensor read-out time of the electronic shutter (rolling-shutter skew), burst rates and buffer depth.
     * mechMin = 0 means the body has no mechanical shutter (Z8/Z9).
     */
    public record Shutter(float mechMin, float elecMin, float max, int ratedLife, String sound, float readoutMs,
                          float burstMech, float burstElec, int bufferShots, boolean efcs) {
        public static final Codec<Shutter> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("mech_min").forGetter(Shutter::mechMin),
                Codec.FLOAT.fieldOf("elec_min").forGetter(Shutter::elecMin),
                Codec.FLOAT.optionalFieldOf("max", 30f).forGetter(Shutter::max),
                Codec.INT.fieldOf("rated_life").forGetter(Shutter::ratedLife),
                Codec.STRING.optionalFieldOf("sound", "mirrorless").forGetter(Shutter::sound),
                Codec.FLOAT.optionalFieldOf("readout_ms", 30f).forGetter(Shutter::readoutMs),
                Codec.FLOAT.optionalFieldOf("burst_mech", 5f).forGetter(Shutter::burstMech),
                Codec.FLOAT.optionalFieldOf("burst_elec", 5f).forGetter(Shutter::burstElec),
                Codec.INT.optionalFieldOf("buffer_shots", 30).forGetter(Shutter::bufferShots),
                Codec.BOOL.optionalFieldOf("efcs", true).forGetter(Shutter::efcs)
        ).apply(i, Shutter::new));

        public boolean hasMechanical() {
            return mechMin > 0;
        }
    }

    /** Autofocus: speed multiplier, lowest scene EV it still locks at, subject detection class. */
    public record Af(float speed, float lowLightEv, String subjectDetect) {
        public static final Codec<Af> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.optionalFieldOf("speed", 1f).forGetter(Af::speed),
                Codec.FLOAT.optionalFieldOf("low_light_ev", -3f).forGetter(Af::lowLightEv),
                Codec.STRING.optionalFieldOf("subject_detect", "none").forGetter(Af::subjectDetect)
        ).apply(i, Af::new));
    }

    public static final Codec<CameraBodySpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Info.CODEC.fieldOf("info").forGetter(CameraBodySpec::info),
            ResourceKey.codec(RCRegistries.MOUNT).fieldOf("mount").forGetter(CameraBodySpec::mount),
            SensorSpec.CODEC.fieldOf("sensor").forGetter(CameraBodySpec::sensor),
            Codec.STRING.fieldOf("viewfinder").forGetter(CameraBodySpec::viewfinder),
            Shutter.CODEC.fieldOf("shutter").forGetter(CameraBodySpec::shutter),
            Af.CODEC.fieldOf("af").forGetter(CameraBodySpec::af),
            Codec.FLOAT.optionalFieldOf("ibis_stops", 0f).forGetter(CameraBodySpec::ibisStops),
            ResourceKey.codec(RCRegistries.BATTERY_TYPE).fieldOf("battery_type").forGetter(CameraBodySpec::batteryType),
            Codec.FLOAT.optionalFieldOf("battery_drain", 1f).forGetter(CameraBodySpec::batteryDrain),
            Codec.STRING.listOf().fieldOf("card_forms").forGetter(CameraBodySpec::cardForms),
            Codec.INT.optionalFieldOf("weather_sealing", 0).forGetter(CameraBodySpec::weatherSealing),
            ResourceKey.codec(RCRegistries.COLOR_PROFILE).listOf().fieldOf("color_profiles").forGetter(CameraBodySpec::colorProfiles),
            Flash.CODEC.optionalFieldOf("flash", Flash.DEFAULT).forGetter(CameraBodySpec::flash),
            ResourceLocation.CODEC.fieldOf("model").forGetter(CameraBodySpec::model)
    ).apply(i, CameraBodySpec::new));
}
