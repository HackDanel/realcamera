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
        ResourceLocation model,
        java.util.Optional<Film> film,
        java.util.Optional<ResourceKey<LensSpec>> fixedLens
) {
    /** Compact cameras with a built-in lens (X100VI, Q3, T2): the lens is part of the body and cannot be removed. */
    public boolean hasFixedLens() {
        return fixedLens.isPresent();
    }

    /**
     * Film body: format ("135" or "120"), whether the shutter is mechanical (works without a battery; then the
     * battery only powers the light meter), the exposure modes the body has ("auto", "p", "a", "s", "m") and its
     * light meter: "battery" (needs charge), "selenium" (no battery, Zenit-E) or "none" (Hasselblad 500C/M).
     */
    public record Film(String format, boolean mechanical, List<String> modes, String meter) {
        public static final List<String> ALL_MODES = List.of("auto", "p", "a", "s", "m");
        public static final Codec<Film> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("format", "135").forGetter(Film::format),
                Codec.BOOL.optionalFieldOf("mechanical", false).forGetter(Film::mechanical),
                Codec.STRING.listOf().optionalFieldOf("modes", ALL_MODES).forGetter(Film::modes),
                Codec.STRING.optionalFieldOf("meter", "battery").forGetter(Film::meter)
        ).apply(i, Film::new));
    }

    private static final String[] MODE_NAMES = {"auto", "p", "a", "s", "m"};

    /** Exposure modes this body offers (CameraSettings.MODE_* values, in order). Digital bodies have them all. */
    public List<Integer> exposureModes() {
        List<Integer> out = new java.util.ArrayList<>();
        for (int m = 0; m < MODE_NAMES.length; m++) {
            if (film.isEmpty() || film.get().modes().contains(MODE_NAMES[m])) out.add(m);
        }
        return out.isEmpty() ? List.of(MODE_NAMES.length - 1) : out;
    }

    /** The mode to use when {@code mode} is not available on this body: the next one that is. */
    public int coerceMode(int mode) {
        List<Integer> modes = exposureModes();
        if (modes.contains(mode)) return mode;
        for (int m : modes) if (m > mode) return m;
        return modes.get(0);
    }

    /** Light meter kind: digital bodies always meter through the sensor. */
    public String meter() {
        return film.map(Film::meter).orElse("sensor");
    }

    /**
     * Shutter types (CameraSettings.SHUTTER_MECH, _EFCS, _ELECTRONIC) this body can use. DSLRs and film bodies
     * have no electronic shutter for stills here; EFCS needs a focal-plane shutter with the efcs flag; R8/R50
     * style bodies have no fully mechanical mode.
     */
    public List<Integer> shutterTypes() {
        List<Integer> out = new java.util.ArrayList<>();
        Shutter sh = shutter;
        if (sh.hasMechanical() && sh.fullMech()) out.add(0);
        if (sh.hasMechanical() && sh.efcs() && !sh.leaf()) out.add(1);
        boolean electronic = !isFilm() && !"dslr".equals(info.type()) && sh.elecMin() > 0;
        if (electronic || out.isEmpty()) out.add(2);
        return out;
    }

    public int coerceShutterType(int type) {
        List<Integer> types = shutterTypes();
        if (types.contains(type)) return type;
        for (int t : types) if (t > type) return t;
        return types.get(0);
    }

    /** Storage built into the body (Hasselblad X2D's SSD): the card slot is filled by the body and locked. */
    public boolean internalStorage() {
        return !cardForms.isEmpty() && cardForms.stream().allMatch(f -> f.startsWith("internal"));
    }

    public boolean isFilm() {
        return film.isPresent();
    }

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
                          float burstMech, float burstElec, int bufferShots, boolean efcs, boolean leaf,
                          boolean fullMech) {
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
                Codec.BOOL.optionalFieldOf("efcs", true).forGetter(Shutter::efcs),
                Codec.BOOL.optionalFieldOf("leaf", false).forGetter(Shutter::leaf),
                Codec.BOOL.optionalFieldOf("full_mech", true).forGetter(Shutter::fullMech)
        ).apply(i, Shutter::new));

        public boolean hasMechanical() {
            return mechMin > 0;
        }

        /**
         * Fastest flash sync with the electronic shutter (s), or 0 if a flash cannot fire with it: only sensors
         * read out fast enough (stacked, ≤ 8 ms) expose the whole frame at once for the flash pulse.
         */
        public float electronicFlashSync() {
            if (readoutMs <= 0 || readoutMs > 8) return 0;
            return Math.max(readoutMs * 1.2f / 1000f, 1 / 250f);
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
            Codec.STRING.listOf().optionalFieldOf("card_forms", List.of()).forGetter(CameraBodySpec::cardForms),
            Codec.INT.optionalFieldOf("weather_sealing", 0).forGetter(CameraBodySpec::weatherSealing),
            ResourceKey.codec(RCRegistries.COLOR_PROFILE).listOf().fieldOf("color_profiles").forGetter(CameraBodySpec::colorProfiles),
            Flash.CODEC.optionalFieldOf("flash", Flash.DEFAULT).forGetter(CameraBodySpec::flash),
            ResourceLocation.CODEC.fieldOf("model").forGetter(CameraBodySpec::model),
            Film.CODEC.optionalFieldOf("film").forGetter(CameraBodySpec::film),
            ResourceKey.codec(RCRegistries.LENS).optionalFieldOf("fixed_lens").forGetter(CameraBodySpec::fixedLens)
    ).apply(i, CameraBodySpec::new));
}
