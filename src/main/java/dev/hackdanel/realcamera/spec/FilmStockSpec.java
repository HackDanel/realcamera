package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A film emulsion (Kodak Portra 400, Ilford HP5…). process: c41 (colour negative), e6 (slide), bw.
 * Latitude: how many stops of over/under exposure still give a good negative (slide film has little).
 */
public record FilmStockSpec(String name, String process, int iso, int frames135, int frames120, float latitudeOver,
                            float latitudeUnder, Look look, Grain grain) {

    /** Colour rendering of the developed film (applied instead of a camera colour profile). */
    public record Look(float contrast, float saturation, boolean monochrome, float tintR, float tintG, float tintB,
                       float shadowLift) {
        public static final Codec<Look> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.optionalFieldOf("contrast", 1f).forGetter(Look::contrast),
                Codec.FLOAT.optionalFieldOf("saturation", 1f).forGetter(Look::saturation),
                Codec.BOOL.optionalFieldOf("monochrome", false).forGetter(Look::monochrome),
                Codec.FLOAT.optionalFieldOf("tint_r", 1f).forGetter(Look::tintR),
                Codec.FLOAT.optionalFieldOf("tint_g", 1f).forGetter(Look::tintG),
                Codec.FLOAT.optionalFieldOf("tint_b", 1f).forGetter(Look::tintB),
                Codec.FLOAT.optionalFieldOf("shadow_lift", 0f).forGetter(Look::shadowLift)
        ).apply(i, Look::new));
    }

    /**
     * @param amount   grain strength 0..1
     * @param size     grain clump size in pixels of a 2560 px scan
     * @param halation red glow around bright lights (CineStill without the anti-halation layer)
     */
    public record Grain(float amount, float size, float halation) {
        public static final Codec<Grain> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.FLOAT.fieldOf("amount").forGetter(Grain::amount),
                Codec.FLOAT.optionalFieldOf("size", 1.2f).forGetter(Grain::size),
                Codec.FLOAT.optionalFieldOf("halation", 0f).forGetter(Grain::halation)
        ).apply(i, Grain::new));
    }

    public static final Codec<FilmStockSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(FilmStockSpec::name),
            Codec.STRING.fieldOf("process").forGetter(FilmStockSpec::process),
            Codec.INT.fieldOf("iso").forGetter(FilmStockSpec::iso),
            Codec.INT.optionalFieldOf("frames_135", 36).forGetter(FilmStockSpec::frames135),
            Codec.INT.optionalFieldOf("frames_120", 12).forGetter(FilmStockSpec::frames120),
            Codec.FLOAT.optionalFieldOf("latitude_over", 3f).forGetter(FilmStockSpec::latitudeOver),
            Codec.FLOAT.optionalFieldOf("latitude_under", 1.5f).forGetter(FilmStockSpec::latitudeUnder),
            Look.CODEC.optionalFieldOf("look", new Look(1, 1, false, 1, 1, 1, 0)).forGetter(FilmStockSpec::look),
            Grain.CODEC.fieldOf("grain").forGetter(FilmStockSpec::grain)
    ).apply(i, FilmStockSpec::new));

    public boolean monochrome() {
        return process.equals("bw") || look.monochrome();
    }

    public int frames(String format) {
        return "120".equals(format) ? frames120 : frames135;
    }
}
