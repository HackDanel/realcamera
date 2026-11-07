package dev.hackdanel.realcamera.spec;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

/**
 * Step-up ring: screws onto a lens with an {@code inMm} filter thread and takes a filter of {@code outMm} or more
 * (issue #7). On a camera it rides on the filter: the filter stack carries the ring's key in its
 * {@code step_up_ring} component, so taking the filter off brings the ring with it.
 */
public record StepUpRingSpec(String name, int inMm, int outMm, ResourceLocation model) {
    public static final Codec<StepUpRingSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(StepUpRingSpec::name),
            Codec.INT.fieldOf("in_mm").forGetter(StepUpRingSpec::inMm),
            Codec.INT.fieldOf("out_mm").forGetter(StepUpRingSpec::outMm),
            ResourceLocation.CODEC.fieldOf("model").forGetter(StepUpRingSpec::model)
    ).apply(i, StepUpRingSpec::new));

    /** Screws onto a lens with this filter thread (mm; 0 = no thread). */
    public boolean fits(int lensThreadMm) {
        return lensThreadMm > 0 && inMm == lensThreadMm;
    }

    /** A filter of this diameter screws onto the ring. */
    public boolean takes(int filterMm) {
        return filterMm >= outMm;
    }
}
