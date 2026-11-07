package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** The viewfinder's per-pixel look must be what the photo pipeline does to the same pixel. */
class LiveViewTest {
    private static ImageParams params(double exposureEv, double dr, double isoGain, ImageParams.Colour colour,
                                      ImageParams.Extras extras) {
        return new ImageParams(new ImageParams.Sensor(exposureEv, isoGain, 0, 0, 1, dr, 1, 0.5),
                new ImageParams.Focus(24, 1.8, 3, 36, 0),
                new ImageParams.Lens(1.2, 1.4, 0.95, 0, 0, 0, 9, true, 0), ImageParams.STILL, ImageParams.NO_SUN,
                colour, extras, 1);
    }

    /** The pipeline's per-pixel steps in its order (developSensor without spatial effects, correctLens, finish). */
    private static float[] pipeline(float[] img, int w, int h, ImageParams p, double relight, boolean correct) {
        for (int i = 0; i < img.length; i++) img[i] *= relight;
        ImagePipeline.filterTransmission(img, p.extras());
        ImagePipeline.expose(img, p.sensor().exposureEv());
        ImagePipeline.graduatedNd(img, w, h, p.extras().gndStops());
        double vig = ImagePipeline.vignetteEv(p);
        if (vig > 0.01) ImagePipeline.vignette(img, w, h, vig);
        float sat = (float) p.sensor().saturation();
        for (int i = 0; i < img.length; i++) img[i] = Math.min(img[i], sat);
        if (correct && vig * 0.5 > 0.01) ImagePipeline.vignette(img, w, h, -vig * 0.5);
        return ImagePipeline.finish(img, p);
    }

    private static void check(ImageParams p, double relight, boolean correct) {
        int w = 24, h = 16;
        SplittableRandom rnd = new SplittableRandom(7);
        float[] img = new float[w * h * 3];
        for (int i = 0; i < img.length; i++) img[i] = (float) Math.pow(rnd.nextDouble(), 2.2);
        float[] in = img.clone();
        float[] expected = pipeline(img, w, h, p, relight, correct);
        LiveView view = LiveView.of(p, relight, correct);
        double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0, norm = cx * cx + cy * cy;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int j = (y * w + x) * 3;
                float[] px = {in[j], in[j + 1], in[j + 2]};
                view.apply(px, ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / norm, (y + 0.5) / h);
                for (int c = 0; c < 3; c++) {
                    assertEquals(expected[j + c], px[c], 1e-4, "pixel " + x + "," + y + " channel " + c);
                }
            }
        }
    }

    @Test
    void neutralProfileMatchesThePipeline() {
        check(params(0, 12, 1, new ImageParams.Colour(1, 1, 1, 1, 1, false, 1, 1, 1, 0), ImageParams.NO_EXTRAS), 1, true);
    }

    @Test
    void dimSceneProfileFilterAndGndMatchThePipeline() {
        var colour = new ImageParams.Colour(1.15, 1, 0.8, 1.3, 1.2, false, 1.05, 1, 0.95, 0.03);
        var extras = new ImageParams.Extras(0, 1, false, 1, 0.8, 0.5, 0, 0, new float[0]).withFilterEffects(0, 2, 0);
        check(params(0.7, 11, 8, colour, extras), 9.5, true);
        check(params(-1.3, 14, 2, colour, extras), 1, false);
    }

    @Test
    void monochromeMatchesThePipeline() {
        check(params(1.5, 13, 1, new ImageParams.Colour(1, 1, 1, 1.4, 0, true, 1.1, 1, 0.9, 0), ImageParams.NO_EXTRAS), 3, true);
    }

    @Test
    void greyFollowsExposure() {
        var neutral = new ImageParams.Colour(1, 1, 1, 1, 1, false, 1, 1, 1, 0);
        LiveView view = LiveView.of(params(0, 12, 1, neutral, ImageParams.NO_EXTRAS), 4, true);
        // a scene the game drew 2 stops too dark comes out at middle grey after 4x brightening
        assertEquals(0.18, view.grey(0.045), 0.005);
        assertTrue(view.grey(0.3) > view.grey(0.2));
    }
}
