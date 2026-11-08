package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LightSourcesTest {
    @Test
    void aLampIsNoBrighterThanWhiteInSunlightButFarBrighterIndoors() {
        assertTrue(LightSources.sourceLevel(15, 15) < 1, "clear noon: " + LightSources.sourceLevel(15, 15));
        assertEquals(1.76, LightSources.sourceLevel(15, 13), 0.05); // overcast
        assertEquals(7.0, LightSources.sourceLevel(15, 11), 0.1); // dusk, rain
        assertEquals(225, LightSources.sourceLevel(15, 6), 0.5); // torch-lit room
        assertEquals(LightSources.CAP, LightSources.sourceLevel(15, 2), 1e-3); // night
        assertFalse(LightSources.worthLooking(15, 1));
        assertTrue(LightSources.worthLooking(11, 1));
        // a night scene already brightened 16 times by the relight still gains
        assertTrue(LightSources.worthLooking(-2, 16));
    }

    @Test
    void dimSourcesAreDimmer() {
        assertTrue(LightSources.luminance(7) < LightSources.luminance(15) / 10);
        assertEquals(0, LightSources.luminance(0), 1e-9);
    }

    @Test
    void theFlameIsRaisedTheStickIsNot() {
        // a torch at dusk (EV 11) in a frame relit x2: a bright flame texel, a dark stick texel (one block, light 14),
        // and a bright wall beside it (no light source)
        float relight = 2;
        float[] drawn = {1.0f, 0.25f, 0.9f};
        LightSources.Found found = LightSources.share(new int[]{0, 0, -1}, new int[]{14}, drawn);
        float[] img = {1.0f * relight, 0.85f * relight, 0.5f * relight, 0.25f * relight, 0.15f * relight, 0.08f * relight,
                0.9f * relight, 0.9f * relight, 0.9f * relight};
        assertEquals(1, LightSources.restore(img, found, 11, relight));
        float level = LightSources.sourceLevel(14, 11);
        assertEquals(level, img[0], 1e-3, "the flame as bright as the source");
        assertEquals(0.85f / 1.0f, img[1] / img[0], 1e-4, "keeps its colour");
        assertEquals(0.25f * relight, img[3], 1e-6, "the stick as drawn");
        assertEquals(0.9f * relight, img[6], 1e-6, "the lit wall is no light source");
    }

    @Test
    void aPaleLampCountsAsAWhole() {
        // a sea lantern drawn no brighter than 0.55: its brightest texels are its light all the same
        LightSources.Found found = LightSources.share(new int[]{0, 0}, new int[]{15}, new float[]{0.55f, 0.5f});
        assertEquals(255, found.share()[0] & 255);
        float[] img = {0.5f, 0.55f, 0.52f, 0.45f, 0.5f, 0.47f};
        assertEquals(2, LightSources.restore(img, found, 4, 1));
        assertTrue(img[1] > 10, "night: far brighter than white " + img[1]);
        // a block drawn dim (fog, far away) is not drawn as a light
        assertEquals(null, LightSources.share(new int[]{0}, new int[]{15}, new float[]{0.2f}));
    }

    @Test
    void neverDarker() {
        // clear noon: the source is dimmer than the white the game drew; nothing changes
        float[] img = {1, 1, 1};
        LightSources.Found found = LightSources.share(new int[]{0}, new int[]{15}, new float[]{1});
        assertEquals(0, LightSources.restore(img, found, 15, 1));
        assertEquals(1, img[0], 1e-6);
    }

    @Test
    void drawnBrightness() {
        assertEquals(1, LightSources.drawnSrgb(0xFFD070), 1e-6);
        assertEquals(0.214, LightSources.drawnSrgb(0x804020), 0.002);
        assertEquals(0.9f, LightSources.drawnLinear(new float[]{0.9f, 0.6f, 0.2f}, 0), 1e-6);
    }
}
