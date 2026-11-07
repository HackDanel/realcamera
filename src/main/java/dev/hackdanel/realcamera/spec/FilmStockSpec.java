package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A film emulsion (Kodak Portra 400, Ilford HP5…). process: c41 (colour negative), e6 (slide), bw.
 * Latitude: how many stops of over/under exposure still give a good negative (slide film has little).
 */
public record FilmStockSpec(String name, String process, int iso, int frames135, int frames120, float latitudeOver,
                            float latitudeUnder, Look look, Grain grain, float reciprocity,
                            java.util.Optional<java.util.List<Float>> reciprocityTint) {
    public FilmStockSpec(String name, String process, int iso, int frames135, int frames120, float latitudeOver,
                         float latitudeUnder, Look look, Grain grain, float reciprocity) {
        this(name, process, iso, frames135, frames120, latitudeOver, latitudeUnder, look, grain, reciprocity,
                java.util.Optional.empty());
    }

    /**
     * Colour shift of a long exposure: the three layers of a colour film fail reciprocity by different amounts, so
     * beyond 1 s the picture drifts (most stocks towards green, which is why data sheets ask for magenta CC filters:
     * Velvia 50 needs CC 12.5M at 32 s). Per-channel factor for every stop beyond 1 s; colour negative is partly
     * corrected in printing. Black-and-white film has none.
     */
    public double[] reciprocityTint(double seconds) {
        if (seconds <= 1 || monochrome()) return new double[]{1, 1, 1};
        double stops = Math.min(7, Math.log(seconds) / Math.log(2));
        float[] perStop = reciprocityTint.filter(l -> l.size() == 3)
                .map(l -> new float[]{l.get(0), l.get(1), l.get(2)})
                .orElse("e6".equals(process) ? new float[]{1f, 1.02f, 1f} : new float[]{0.995f, 1.012f, 0.99f});
        return new double[]{Math.pow(perStop[0], stops), Math.pow(perStop[1], stops), Math.pow(perStop[2], stops)};
    }
    /**
     * Reciprocity failure (Schwarzschild exponent p): beyond 1 s the film acts as if exposed for t^p seconds, i.e.
     * loses (1 - p) * log2(t) EV. Tri-X about 0.6 (10 s metered needs about 50 s), modern colour about 0.9.
     */
    public double reciprocityLossEv(double seconds) {
        return seconds <= 1 ? 0 : (1 - reciprocity) * Math.log(seconds) / Math.log(2);
    }

    /**
     * Exposure error the finished picture shows for a frame exposed {@code ev} off: a negative is printed back to
     * normal, so over within the latitude is nearly invisible and under within it prints thin; beyond the latitude
     * the error shows in full. Slide film shows every error.
     */
    public double printedEv(double ev) {
        if (!negative()) return ev;
        if (ev >= 0) return ev <= latitudeOver ? ev * 0.15 : latitudeOver * 0.15 + (ev - latitudeOver);
        return ev >= -latitudeUnder ? ev * 0.35 : -latitudeUnder * 0.35 + (ev + latitudeUnder);
    }

    /** Negative film is printed back to normal within its latitude; slide film shows every exposure error. */
    public boolean negative() {
        return !"e6".equals(process);
    }


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
            Grain.CODEC.fieldOf("grain").forGetter(FilmStockSpec::grain),
            Codec.FLOAT.optionalFieldOf("reciprocity", 0.85f).forGetter(FilmStockSpec::reciprocity),
            Codec.FLOAT.listOf().optionalFieldOf("reciprocity_tint").forGetter(FilmStockSpec::reciprocityTint)
    ).apply(i, FilmStockSpec::new));

    public boolean monochrome() {
        return process.equals("bw") || look.monochrome();
    }

    public int frames(String format) {
        return "120".equals(format) ? frames120 : frames135;
    }
}
