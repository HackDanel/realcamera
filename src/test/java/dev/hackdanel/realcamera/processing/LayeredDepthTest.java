package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LayeredDepthTest {
    private static final int W = 8, H = 4, GW = 4, GH = 2;

    private static float[] fill(int n, float v) {
        float[] a = new float[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    @Test
    void theBottomOfAPondIsSeenAtItsApparentDepth() {
        // water surface 4 m away, the bottom 2 m below it along the view (6 m)
        float[] full = fill(W * H, 4), opaque = fill(W * H, 6);
        float[] out = LayeredDepth.combine(full, opaque, full.clone(), fill(GW * GH, LayeredDepth.WATER), GW, GH, W, H, false);
        assertEquals(4 + 2 / 1.33, out[5], 1e-3);
        // looking out from under water: no correction
        out = LayeredDepth.combine(full, opaque, full.clone(), fill(GW * GH, LayeredDepth.WATER), GW, GH, W, H, true);
        assertEquals(6, out[5], 1e-6);
    }

    @Test
    void stainedGlassIsSeenThrough() {
        float[] full = fill(W * H, 3), opaque = fill(W * H, 20);
        float[] out = LayeredDepth.combine(full, opaque, full.clone(), fill(GW * GH, LayeredDepth.CLEAR), GW, GH, W, H, false);
        assertEquals(20, out[0], 1e-6);
    }

    @Test
    void particlesKeepTheirDepth() {
        // smoke 0.5 m in front of the lens, before a window
        float[] opaque = fill(W * H, 20), surface = fill(W * H, 3), full = fill(W * H, 0.5f);
        float[] out = LayeredDepth.combine(full, opaque, surface, fill(GW * GH, LayeredDepth.CLEAR), GW, GH, W, H, false);
        assertEquals(0.5f, out[0], 1e-6);
        // a solid particle (drawn with the translucent blocks) where no translucent block is
        out = LayeredDepth.combine(surface.clone(), opaque, surface, fill(GW * GH, LayeredDepth.NONE), GW, GH, W, H, false);
        assertEquals(3, out[0], 1e-6);
    }

    @Test
    void smokeInFrontOfTheLensDoesNotFailTheDepthCheck() {
        // campfire smoke (billboards 0.6 m away) over the whole frame, the ground 6 m away where the rays hit it
        float[] opaque = fill(W * H, 6), full = fill(W * H, 0.6f), rays = fill(GW * GH, 6);
        float[] kind = fill(GW * GH, LayeredDepth.NONE);
        assertEquals(false, DepthUpsampler.agrees(full, W, H, rays, null, GW, GH));
        float[] seen = LayeredDepth.rayView(opaque, opaque.clone(), kind, GW, GH, W, H);
        assertEquals(true, DepthUpsampler.agrees(seen, W, H, rays, null, GW, GH));
        // and the photo still gets the smoke's own depth
        assertEquals(0.6f, LayeredDepth.combine(full, opaque, opaque.clone(), kind, GW, GH, W, H, false)[9], 1e-6);
    }

    @Test
    void theRaysStopAtWaterButPassStainedGlass() {
        float[] opaque = fill(W * H, 6), surface = fill(W * H, 4);
        float[] water = LayeredDepth.rayView(opaque, surface, fill(GW * GH, LayeredDepth.WATER), GW, GH, W, H);
        assertEquals(4, water[0], 1e-6);
        // stained glass has no visual shape: its cell kind is whatever lies behind it
        float[] glass = LayeredDepth.rayView(opaque, surface, fill(GW * GH, LayeredDepth.NONE), GW, GH, W, H);
        assertEquals(6, glass[0], 1e-6);
    }

    @Test
    void withoutTranslucentSurfacesNothingChanges() {
        float[] full = new float[W * H];
        for (int i = 0; i < full.length; i++) full[i] = 1 + i * 0.5f;
        float[] out = LayeredDepth.combine(full, full.clone(), full.clone(), fill(GW * GH, LayeredDepth.NONE), GW, GH, W, H, false);
        assertArrayEquals(full, out, 1e-6f);
    }

    @Test
    void stainedGlassFrameKeepsTheGlassDepth() {
        float[] opaque = fill(W * H, 8f), surface = fill(W * H, 3f);
        float[] kind = fill(GW * GH, LayeredDepth.GLASS);
        float[] out = LayeredDepth.combine(surface.clone(), opaque, surface, kind, GW, GH, W, H, false, i -> i == 5);
        assertEquals(3f, out[5], 1e-6);  // the frame: the glass itself
        assertEquals(8f, out[6], 1e-6);  // the clear middle: what lies behind
    }

    @Test
    void glassFrameIsTheOuterSixteenthOfAFace() {
        org.junit.jupiter.api.Assertions.assertTrue(LayeredDepth.onGlassFrame(4.0, 10.02, 7.5));   // near the bottom edge
        org.junit.jupiter.api.Assertions.assertTrue(LayeredDepth.onGlassFrame(4.97, 10.5, 7.0));   // face z = 7
        org.junit.jupiter.api.Assertions.assertFalse(LayeredDepth.onGlassFrame(4.0, 10.5, 7.5));   // middle of face x = 4
        org.junit.jupiter.api.Assertions.assertFalse(LayeredDepth.onGlassFrame(4.5, 10.5, 7.45)); // not on a face (pane)
    }
}
