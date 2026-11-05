package dev.hackdanel.realcamera.optics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpticsTest {
    @Test
    void fieldOfView50mmFullFrame() {
        // 24 mm sensor height, 50 mm lens -> 26.99 degrees vertical
        assertEquals(26.99, Optics.verticalFovDeg(24, 50), 0.01);
    }

    @Test
    void depthOfFieldMatchesStandardCalculator() {
        // 50 mm f/1.8 focused at 3 m, CoC 0.03 mm -> about 2.82 m to 3.20 m
        double[] dof = Optics.depthOfFieldM(50, 1.8, 3, 0.03);
        assertEquals(2.82, dof[0], 0.01);
        assertEquals(3.20, dof[1], 0.01);
        // the blur circle at the DoF limits equals the CoC
        assertEquals(0.03, Optics.blurCircleMm(50, 1.8, 3, dof[0]), 0.0005);
        assertEquals(0.03, Optics.blurCircleMm(50, 1.8, 3, dof[1]), 0.0005);
    }

    @Test
    void inFocusObjectHasNoBlur() {
        assertEquals(0, Optics.blurCircleMm(85, 1.8, 2, 2), 1e-9);
    }

    @Test
    void beyondHyperfocalIsInfinite() {
        double h = Optics.hyperfocalMm(28, 8, 0.03) / 1000; // ~3.3 m
        assertTrue(Double.isInfinite(Optics.depthOfFieldM(28, 8, h + 0.1, 0.03)[1]));
    }

    @Test
    void handheldRule() {
        assertEquals(1 / 50.0, Optics.handheldLimit(50, 0), 1e-9);
        assertEquals(8 / 50.0, Optics.handheldLimit(50, 3), 1e-9);
    }
}
