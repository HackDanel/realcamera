package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShadeMapTest {
    @Test
    void referenceLightStaysAsDrawn() {
        assertEquals(1f, ShadeMap.factor(14f, 0.9f, 14, 0.9), 1e-5);
    }

    @Test
    void openShadeIsDarkerThanTheGameDrawsIt() {
        // sky light 14: 2.6 EV of shade, the game draws it at 0.79 of full light
        float f = ShadeMap.factor(15f - 2.6f, 0.79f, 15, 1);
        assertEquals(Math.pow(2, -2.6) / 0.79, f, 1e-4);
        assertTrue(f < 0.3f);
    }

    @Test
    void skyAndExtremesAreBounded() {
        assertEquals(1f, ShadeMap.factor(Float.NaN, 0.5f, 10, 1));
        assertEquals(ShadeMap.MIN, ShadeMap.factor(-10f, 1f, 15, 1));
        assertEquals(ShadeMap.MAX, ShadeMap.factor(15f, 0.05f, 0, 1));
    }

    @Test
    void encodingRoundTrips() {
        for (float f : new float[]{ShadeMap.MIN, 0.25f, 1f, 2f, ShadeMap.MAX}) {
            double back = Math.pow(2, ShadeMap.encode(f) / 255.0 * 8 - 6);
            assertEquals(1, back / f, 0.02);
        }
    }
}
