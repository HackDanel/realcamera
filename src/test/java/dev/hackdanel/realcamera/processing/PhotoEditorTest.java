package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhotoEditorTest {
    private static final ImageParams.Colour NEUTRAL = new ImageParams.Colour(1, 1, 1, 1, 1, false, 1, 1, 1, 0);

    private static int[] gradient(int w, int h) {
        int[] rgb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = x * 255 / (w - 1), g = y * 255 / (h - 1), b = (x + y) * 255 / (w + h - 2);
                rgb[y * w + x] = (r << 16) | (g << 8) | b;
            }
        }
        return rgb;
    }

    private static PhotoEditor.Source jpeg(int[] rgb, int w, int h) {
        return new PhotoEditor.Source(ImagePipeline.toLinear(rgb), w, h, false, 12, NEUTRAL, 0, 0, 0);
    }

    private static int channel(int p, int c) {
        return (p >> (16 - 8 * c)) & 255;
    }

    @Test
    void defaultSettingsLeaveAJpegUntouched() {
        int[] rgb = gradient(32, 24);
        PhotoEditor.Result r = PhotoEditor.apply(jpeg(rgb, 32, 24), new EditSettings(), null);
        assertEquals(32, r.w());
        assertEquals(24, r.h());
        for (int i = 0; i < rgb.length; i++) {
            for (int c = 0; c < 3; c++) {
                assertTrue(Math.abs(channel(rgb[i], c) - channel(r.rgb()[i], c)) <= 1, "pixel " + i);
            }
        }
    }

    @Test
    void defaultRawDevelopmentMatchesTheCameraJpeg() {
        int w = 16, h = 8;
        float[] sensor = new float[w * h * 3];
        for (int i = 0; i < sensor.length; i++) sensor[i] = (i % 37) / 20f; // 0 .. 1.8
        ImageParams.Colour colour = new ImageParams.Colour(1.1, 1, 0.9, 1.1, 1.05, false, 1, 1, 1, 0.02);
        ImageParams p = ImageParams.neutral();
        p = new ImageParams(new ImageParams.Sensor(0, 1, 0, 0, 1, 13), p.focus(), p.lens(), p.motion(), p.sun(), colour,
                p.extras(), 1);
        int[] camera = ImagePipeline.toSrgb(ImagePipeline.finish(sensor.clone(), p));
        PhotoEditor.Source raw = new PhotoEditor.Source(sensor, w, h, true, 13, colour, 0, 0, 0);
        int[] dev = PhotoEditor.apply(raw, new EditSettings(), null).rgb();
        for (int i = 0; i < camera.length; i++) {
            for (int c = 0; c < 3; c++) {
                assertTrue(Math.abs(channel(camera[i], c) - channel(dev[i], c)) <= 1, "pixel " + i);
            }
        }
    }

    @Test
    void rawRecoversHighlightsThatAJpegLost() {
        // two patches above JPEG white but below sensor saturation
        float[] sensor = {1.4f, 1.4f, 1.4f, 2.4f, 2.4f, 2.4f};
        PhotoEditor.Source raw = new PhotoEditor.Source(sensor, 2, 1, true, 14, NEUTRAL, 0, 0, 0);
        int[] cameraJpeg = ImagePipeline.toSrgb(ImagePipeline.finish(sensor.clone(),
                new ImageParams(new ImageParams.Sensor(0, 1, 0, 0, 1, 14), ImageParams.neutral().focus(),
                        ImageParams.neutral().lens(), ImageParams.STILL, ImageParams.NO_SUN, NEUTRAL, ImageParams.NO_EXTRAS, 1)));
        EditSettings pull = new EditSettings();
        pull.exposure = -1.5;
        int[] fromRaw = PhotoEditor.apply(raw, pull, null).rgb();
        int[] fromJpeg = PhotoEditor.apply(jpeg(cameraJpeg, 2, 1), pull, null).rgb();
        int rawDiff = channel(fromRaw[1], 1) - channel(fromRaw[0], 1);
        int jpegDiff = channel(fromJpeg[1], 1) - channel(fromJpeg[0], 1);
        assertTrue(rawDiff > 20, "RAW separates the highlights: " + rawDiff);
        assertTrue(rawDiff > jpegDiff * 2, "JPEG keeps them nearly merged: " + jpegDiff);
    }

    @Test
    void rotateTurnsClockwiseAndSwapsSize() {
        int w = 3, h = 2;
        int[] rgb = new int[w * h];
        rgb[0] = 0xFF0000; // top-left red
        EditSettings s = new EditSettings();
        s.rotate = 1;
        PhotoEditor.Result r = PhotoEditor.apply(jpeg(rgb, w, h), s, null);
        assertEquals(2, r.w());
        assertEquals(3, r.h());
        assertTrue(channel(r.rgb()[1], 0) > 250, "top-left moves to top-right");
    }

    @Test
    void cropKeepsTheSelectedPart() {
        int[] rgb = gradient(40, 20);
        EditSettings s = new EditSettings();
        s.cropX = 0.5;
        s.cropW = 0.5;
        s.cropY = 0;
        s.cropH = 0.5;
        PhotoEditor.Result r = PhotoEditor.apply(jpeg(rgb, 40, 20), s, null);
        assertEquals(20, r.w());
        assertEquals(10, r.h());
        assertTrue(channel(r.rgb()[0], 0) > 120, "starts in the right half (red grows to the right)");
    }

    @Test
    void splinePassesThroughItsPoints() {
        double[] c = {0.05, 0.3, 0.6, 0.8, 0.95};
        for (int i = 0; i < 5; i++) {
            assertEquals(c[i], PhotoEditor.spline(c, i * 0.25), 1e-9);
        }
        double prev = -1;
        for (int i = 0; i <= 100; i++) {
            double v = PhotoEditor.spline(c, i / 100.0);
            assertTrue(v >= prev - 1e-9, "monotone");
            prev = v;
        }
        double[] id = {0, 0.25, 0.5, 0.75, 1};
        assertEquals(0.37, PhotoEditor.spline(id, 0.37), 1e-9);
    }

    @Test
    void warmerTemperatureAddsRedAndRemovesBlue() {
        double[] m = PhotoEditor.temperatureTint(60, 0);
        assertTrue(m[0] > 1 && m[2] < 1);
        double[] tint = PhotoEditor.temperatureTint(0, 50);
        assertTrue(tint[1] < tint[0], "magenta tint lowers green");
    }

    @Test
    void hslDesaturatesOnlyItsBand() {
        int[] rgb = {0x20C020, 0xC02020};
        EditSettings s = new EditSettings();
        s.hslSat[3] = -100; // green
        PhotoEditor.Result r = PhotoEditor.apply(jpeg(rgb, 2, 1), s, null);
        int g = r.rgb()[0], red = r.rgb()[1];
        assertTrue(Math.abs(channel(g, 0) - channel(g, 1)) < 12, "green became grey");
        assertTrue(channel(red, 0) - channel(red, 1) > 120, "red untouched");
    }

    @Test
    void contrastAndShadowsMoveTonesTheRightWay() {
        EditSettings s = new EditSettings();
        s.contrast = 50;
        assertTrue(PhotoEditor.toneCurve(0.2, s) < 0.2);
        assertTrue(PhotoEditor.toneCurve(0.8, s) > 0.8);
        EditSettings sh = new EditSettings();
        sh.shadows = 80;
        assertTrue(PhotoEditor.toneCurve(0.15, sh) > 0.17);
        assertEquals(1.0, PhotoEditor.toneCurve(1.0, sh), 1e-6);
    }

    @Test
    void straightenZoomCoversTheFrame() {
        assertEquals(1.0, PhotoEditor.straightenZoom(300, 200, 0), 1e-9);
        assertTrue(PhotoEditor.straightenZoom(300, 200, 5) > 1.1);
    }

    @Test
    void rawFileRoundTrip(@TempDir Path dir) throws Exception {
        int w = 5, h = 3;
        float[] px = new float[w * h * 3];
        for (int i = 0; i < px.length; i++) px[i] = i * 0.07f;
        RawFile.Info info = new RawFile.Info();
        info.white = 4;
        info.camera = "SONY ILCE-7M4";
        Path png = dir.resolve("DSC00001.PNG");
        RawFile.write(png, px, w, h, info);
        assertTrue(Files.exists(dir.resolve("DSC00001.json")));
        RawFile.Info back = RawFile.readInfo(png);
        assertEquals("SONY ILCE-7M4", back.camera);
        int[] size = new int[2];
        float[] read = RawFile.readPixels(png, back, size);
        assertEquals(w, size[0]);
        assertEquals(h, size[1]);
        for (int i = 0; i < px.length; i++) {
            assertEquals(Math.min(4, px[i]), read[i], 1e-3, "sample " + i);
        }
    }
}
