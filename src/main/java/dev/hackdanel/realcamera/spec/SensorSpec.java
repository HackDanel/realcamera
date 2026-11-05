package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Image sensor. Size in millimetres drives field of view, crop factor and depth of field; the noise
 * coefficients drive the noise model (lower = cleaner).
 */
public record SensorSpec(float widthMm, float heightMm, float megapixels, int baseIso, int isoMin, int isoMax,
                         float dynamicRangeEv, float noiseLuma, float noiseChroma) {
    public static final Codec<SensorSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("width_mm").forGetter(SensorSpec::widthMm),
            Codec.FLOAT.fieldOf("height_mm").forGetter(SensorSpec::heightMm),
            Codec.FLOAT.fieldOf("megapixels").forGetter(SensorSpec::megapixels),
            Codec.INT.fieldOf("base_iso").forGetter(SensorSpec::baseIso),
            Codec.INT.fieldOf("iso_min").forGetter(SensorSpec::isoMin),
            Codec.INT.fieldOf("iso_max").forGetter(SensorSpec::isoMax),
            Codec.FLOAT.fieldOf("dynamic_range_ev").forGetter(SensorSpec::dynamicRangeEv),
            Codec.FLOAT.optionalFieldOf("noise_luma", 1f).forGetter(SensorSpec::noiseLuma),
            Codec.FLOAT.optionalFieldOf("noise_chroma", 1f).forGetter(SensorSpec::noiseChroma)
    ).apply(i, SensorSpec::new));

    public static final float FULL_FRAME_DIAGONAL_MM = 43.2666f;

    public float diagonalMm() {
        return (float) Math.hypot(widthMm, heightMm);
    }

    public float cropFactor() {
        return FULL_FRAME_DIAGONAL_MM / diagonalMm();
    }

    public float aspect() {
        return widthMm / heightMm;
    }

    /** Long side of the stored image, by sensor resolution class (see plan §3.4). */
    public int outputLongSide() {
        if (megapixels <= 16) return 1280;
        if (megapixels <= 26) return 1920;
        if (megapixels <= 45) return 2560;
        if (megapixels <= 61) return 3200;
        return 3840;
    }
}
