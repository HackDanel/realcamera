package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hackdanel.realcamera.optics.Optics;
import org.junit.jupiter.api.Test;

class LensCharacterTest {
    private static BokehKernel.Shape shape(double spherical, double apodization, double obstruction) {
        return new BokehKernel.Shape(9, true, ImageParams.BOKEH_SMOOTH, 0, spherical, apodization, obstruction);
    }

    @Test
    void underCorrectedLensIsSoftBehindAndNervousInFront() {
        BokehKernel.Shape s = shape(0.8, 0, 0);
        assertTrue(BokehKernel.profile(s, 0.05, false) > BokehKernel.profile(s, 0.95, false), "soft-edged behind");
        assertTrue(BokehKernel.profile(s, 0.95, true) > 1.5 * BokehKernel.profile(s, 0.05, true), "bright rim in front");
        // over-corrected: the other way round
        BokehKernel.Shape o = shape(-0.8, 0, 0);
        assertTrue(BokehKernel.profile(o, 0.95, false) > BokehKernel.profile(o, 0.05, false));
    }

    @Test
    void sphericalAberrationFadesStoppingDown() {
        var lens = new ImageParams.Lens(0, 2, 1, 0, 0, 1, 9, true, 0).withCharacter(new ImageParams.Character(0.8, 0, 0));
        assertEquals(0.8, BokehKernel.Shape.of(lens, 0).spherical(), 1e-9);
        assertEquals(0, BokehKernel.Shape.of(lens, 2).spherical(), 1e-9);
    }

    @Test
    void apodisedDiscsFadeToTheirEdge() {
        BokehKernel.Shape s = shape(0, 1, 0);
        float last = Float.MAX_VALUE;
        for (double e = 0; e <= 1; e += 0.1) {
            float v = BokehKernel.profile(s, e, false);
            assertTrue(v <= last + 1e-6, "falls at " + e);
            last = v;
        }
        assertTrue(BokehKernel.profile(s, 1, false) < 0.15 * BokehKernel.profile(s, 0, false));
    }

    @Test
    void mirrorLensDrawsRings() {
        int w = 96, h = 96, c = 48;
        float[] img = new float[w * h * 3];
        for (int y = c - 1; y <= c; y++) {
            for (int x = c - 1; x <= c; x++) {
                int j = (y * w + x) * 3;
                img[j] = img[j + 1] = img[j + 2] = 30f; // a bright lamp far behind the focus plane
            }
        }
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, 1000f);
        var lens = new ImageParams.Lens(0, 8, 1, 0, 0, 1, 9, true, 0).withCharacter(new ImageParams.Character(0, 0, 0.4));
        ImageParams base = ImageParams.neutral();
        ImageParams p = new ImageParams(base.sensor(), new ImageParams.Focus(500, 8, 10, 9), lens, base.motion(),
                base.sun(), base.colour(), base.extras(), base.seed());
        float[] out = ImagePipeline.depthOfField(img, depth, w, h, p);
        float r = (float) (Optics.blurCircleMm(500, 8, 10, 1000) * w / 9 * 0.5);
        assertTrue(r > 10, "disc radius " + r);
        float centre = out[(c * w + c) * 3], ring = out[(c * w + c + (int) (r * 0.75)) * 3];
        assertTrue(ring > 3 * centre, "dark centre: centre " + centre + " ring " + ring);
    }

    @Test
    void axialColourRimsTheBokehDiscs() {
        // a lamp in front of the focus plane: red and blue blur more there, a purple rim around the green disc
        int w = 120, h = 120, c = 60;
        float[] img = new float[w * h * 3];
        int j0 = (c * w + c) * 3;
        img[j0] = img[j0 + 1] = img[j0 + 2] = 200f;
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, 1.2f);
        ImageParams base = ImageParams.neutral();
        var lens = new ImageParams.Lens(0, 1.8, 1, 0, 0, 1, 9, true, 0).withLoca(0.3);
        ImageParams p = new ImageParams(base.sensor(), new ImageParams.Focus(50, 1.8, 2, 3.6), lens, base.motion(),
                base.sun(), base.colour(), base.extras(), base.seed());
        float[] out = ImagePipeline.depthOfField(img, depth, w, h, p);
        float green = (float) (Optics.blurCircleMm(50, 1.8, 2, 1.2) * w / 3.6 * 0.5);
        int rim = (int) Math.ceil(green + 1.5);
        int j = (c * w + c + rim) * 3;
        assertTrue(out[j] > out[j + 1] * 2 && out[j + 2] > out[j + 1] * 2,
                "purple rim at " + rim + " px: " + out[j] + " " + out[j + 1] + " " + out[j + 2]);
    }

    @Test
    void fieldCurvatureFocusesTheCornersCloser() {
        assertEquals(3, Optics.curvedFocusM(50, 3, 0), 1e-9);
        double edge = Optics.curvedFocusM(50, 3, 0.1);
        assertTrue(edge < 3 && edge > 2, "edge focus " + edge);
        assertTrue(Optics.curvedFocusM(50, 1e6, 0.1) < 100, "infinity focus comes in at the edge");

        // a flat wall in focus: sharp in the middle, blurred at the corners of a curved-field lens
        int w = 200, h = 120;
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, 3f);
        var flat = new ImageParams.Focus(50, 2, 3, 36);
        var curved = flat.withFieldCurvature(0.15);
        float[] a = ImagePipeline.signedBlurRadius(depth, w, flat, w * 0.3f, 0);
        float[] b = ImagePipeline.signedBlurRadius(depth, w, curved, w * 0.3f, 0);
        int centre = (h / 2) * w + w / 2, corner = 0;
        assertEquals(0, a[corner], 1e-3, "flat field: corner sharp");
        assertTrue(Math.abs(b[centre]) < 0.05f, "curved: centre sharp " + b[centre]);
        // the image of the wall lies 0.15 mm in front of the corners' plane of best focus: a disc of 0.15 / N mm
        double expected = -0.15 / 2 * (w / 36.0) * 0.5;
        assertEquals(expected, b[corner], Math.abs(expected) * 0.15, "curved: the wall lies behind the corners' focus");
    }
}
