package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DepthUpsamplerTest {
    private static final int W = 80, H = 40, GW = 20, GH = 10;

    private static float[] grid(float ground) {
        float[] g = new float[GW * GH];
        for (int y = 0; y < GH; y++) {
            for (int x = 0; x < GW; x++) g[y * GW + x] = y < 3 ? 10_000 : ground; // sky on top, ground below
        }
        return g;
    }

    private static float[] full(float ground, float sky) {
        float[] f = new float[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) f[y * W + x] = y < 12 ? sky : ground;
        }
        return f;
    }

    @Test
    void grassInFrontOfTheGroundIsAccepted() {
        float[] gpu = full(8, 10_000);
        for (int i = W * 20; i < W * 30; i += 3) gpu[i] = 2; // blades the rays went through
        assertTrue(DepthUpsampler.agrees(gpu, W, H, grid(8), null, GW, GH));
    }

    @Test
    void depthBehindSolidBlocksIsRejected() {
        assertFalse(DepthUpsampler.agrees(full(40, 10_000), W, H, grid(8), null, GW, GH));
    }

    @Test
    void unknownPixelsAreSkippedAndFilled() {
        float[] gpu = full(8, -1); // a shader pack left the sky at 0
        assertTrue(DepthUpsampler.agrees(gpu, W, H, grid(8), null, GW, GH));
        float[] fallback = new float[W * H];
        Arrays.fill(fallback, 10_000);
        float[] filled = DepthUpsampler.fillUnknown(gpu, () -> fallback);
        assertEquals(10_000, filled[0]);
        assertEquals(8, filled[W * H - 1]);
    }

    @Test
    void aFixedDepthLayerIsTakenFromTheRays() {
        float[] fallback = new float[W * H];
        for (int i = 0; i < fallback.length; i++) fallback[i] = i < W * 12 ? 10_000 : 8;
        // the night sky at 0.6 m
        float[] gpu = full(8, 0.6f);
        assertFalse(DepthUpsampler.agrees(gpu, W, H, grid(8), null, GW, GH));
        assertTrue(DepthUpsampler.dropFixedLayer(gpu, W, H, grid(8), GW, GH) > 0);
        assertTrue(DepthUpsampler.agrees(gpu, W, H, grid(8), null, GW, GH));
        DepthUpsampler.fillUnknown(gpu, () -> fallback);
        assertEquals(10_000, gpu[0]);
        assertEquals(8, gpu[W * H - 1]);
        // the whole frame at 0.6 m
        float[] all = full(0.6f, 0.6f);
        assertTrue(DepthUpsampler.dropFixedLayer(all, W, H, grid(8), GW, GH) > 0);
        DepthUpsampler.fillUnknown(all, () -> fallback);
        assertEquals(8, all[W * H - 1]);
        // a wall facing the lens is a fixed depth too, but the rays agree with it
        float[] wall = full(8, 10_000);
        assertEquals(0, DepthUpsampler.dropFixedLayer(wall, W, H, grid(8), GW, GH));
    }

    @Test
    void aFrameMuchNearerThanTheRaysIsRejected() {
        float[] gpu = full(8, 10_000);
        for (int i = W * 12; i < gpu.length; i++) gpu[i] = 0.5f + (i % 7) * 0.1f; // varied, so not one layer
        assertFalse(DepthUpsampler.agrees(gpu, W, H, grid(8), null, GW, GH));
    }
}
