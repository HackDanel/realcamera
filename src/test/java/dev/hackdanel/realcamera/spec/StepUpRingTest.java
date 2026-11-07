package dev.hackdanel.realcamera.spec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Issue #7: a filter screws onto its own thread only; a larger one needs a step-up ring from the lens's thread. */
class StepUpRingTest {
    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath("realcamera", "item/x");

    private static FilterSpec filter(int mm) {
        return new FilterSpec("f", "uv", 0, 1, 1, 1, mm, MODEL, 0, 0, 0);
    }

    private static StepUpRingSpec ring(int in, int out) {
        return new StepUpRingSpec("r", in, out, MODEL);
    }

    @Test
    void directOnlyOnTheSameThread() {
        assertTrue(filter(67).fits(67, null));
        assertFalse(filter(82).fits(67, null), "a larger filter needs a ring now");
        assertFalse(filter(67).fits(82, null), "a smaller filter never fits");
        assertFalse(filter(67).fits(0, null), "no thread, no filter");
    }

    @Test
    void throughAStepUpRing() {
        StepUpRingSpec r = ring(55, 67);
        assertTrue(filter(67).fits(55, r));
        assertTrue(filter(82).fits(55, r), "anything from the ring's output size up");
        assertFalse(filter(67).fits(58, r), "the ring's input must match the lens thread");
        assertFalse(filter(67).fits(67, r), "a ring on a lens it does not fit");
        assertFalse(ring(67, 77).takes(67), "a filter smaller than the ring's output");
        assertFalse(r.fits(0), "fisheyes and compacts take no ring");
    }
}
