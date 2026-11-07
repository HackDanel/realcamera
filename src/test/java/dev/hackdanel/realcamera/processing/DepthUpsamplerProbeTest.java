package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The live preview's sparse depth probe must not reject a pack's depth buffer just because the view is all grass. */
class DepthUpsamplerProbeTest {
    @Test
    void grassInFrontPassesTheSparseProbe() {
        int gw = 5, gh = 4;
        float[] rays = new float[gw * gh], gpu = new float[gw * gh];
        for (int i = 0; i < rays.length; i++) {
            rays[i] = 3 + i * 0.4f;           // the ground the rays hit
            gpu[i] = (3 + i * 0.4f) * 0.3f;   // grass blades much nearer, at varied distances
        }
        assertFalse(DepthUpsampler.agrees(gpu, gw, gh, rays, null, gw, gh, false));
        assertTrue(DepthUpsampler.agrees(gpu, gw, gh, rays, null, gw, gh, true));
    }

    @Test
    void oneFixedLayerStillFails() {
        int gw = 5, gh = 4;
        float[] rays = new float[gw * gh], gpu = new float[gw * gh];
        for (int i = 0; i < rays.length; i++) {
            rays[i] = 5 + i;
            gpu[i] = 0.5f; // a pack writing a constant depth
        }
        assertFalse(DepthUpsampler.agrees(gpu, gw, gh, rays, null, gw, gh, true));
    }
}
