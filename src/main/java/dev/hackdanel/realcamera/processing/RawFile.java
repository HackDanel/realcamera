package dev.hackdanel.realcamera.processing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * The mod's RAW format: a linear DNG of the sensor data (before white balance, tone curve and colour profile;
 * 65535 = sensor saturation, see {@link DngFile}) that Lightroom and other raw converters develop like a real
 * camera's file, and a JSON sidecar with what the in-game editor needs. Files from before 0.8.7 are 16-bit
 * linear PNGs with the same sidecar and are still read.
 */
public final class RawFile {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Sidecar contents (Gson maps fields by name). */
    public static final class Info {
        public int version = 2;
        public String camera = "", lens = "";
        public double exposure_ev, shutter_s, f_number, focal_mm;
        public int iso;
        public double dynamic_range_ev = 12;
        /** Linear value of a full-scale (65535) sample. */
        public double white = 4;
        public double[] white_balance = {1, 1, 1};
        public String profile = "";
        /** Colour profiles the camera offers (registry ids), selectable when developing. */
        public String[] profiles = {};
        /** Colour profile as shot: contrast, saturation, monochrome (0/1), tint r, g, b, shadow lift. */
        public double[] profile_params = {1, 1, 0, 1, 1, 1, 0};
        public double vignetting_ev, distortion, ca_frac;

        public ImageParams.Colour asShot() {
            double[] p = profile_params == null || profile_params.length < 7 ? new double[]{1, 1, 0, 1, 1, 1, 0}
                    : profile_params;
            double[] wb = white_balance == null || white_balance.length < 3 ? new double[]{1, 1, 1} : white_balance;
            return new ImageParams.Colour(wb[0], wb[1], wb[2], p[0], p[1], p[2] > 0.5, p[3], p[4], p[5], p[6]);
        }
    }

    private RawFile() {}

    public static Path sidecar(Path png) {
        String name = png.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return png.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".json");
    }

    /** Writes the RAW as a linear DNG plus the JSON sidecar; EXIF from {@code exif} (or the sidecar's fields). */
    public static void write(Path dng, float[] linear, int w, int h, Info info, ExifWriter.Data exif) throws IOException {
        double scale = 65535 / info.white;
        short[] samples = new short[w * h * 3];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) Math.max(0, Math.min(65535, Math.round(linear[i] * scale)));
        }
        double[] wb = info.white_balance == null || info.white_balance.length < 3 ? new double[]{1, 1, 1}
                : info.white_balance;
        double[] neutral = {1 / Math.max(1e-6, wb[0]), 1 / Math.max(1e-6, wb[1]), 1 / Math.max(1e-6, wb[2])};
        double max = Math.max(neutral[0], Math.max(neutral[1], neutral[2]));
        for (int c = 0; c < 3; c++) neutral[c] /= max;
        DngFile.write(dng, samples, w, h, info.camera.isEmpty() ? "RealCamera" : info.camera, neutral,
                baselineExposure(info.white), exif);
        Files.writeString(sidecar(dng), GSON.toJson(info));
    }

    public static void write(Path dng, float[] linear, int w, int h, Info info) throws IOException {
        write(dng, linear, w, h, info, new ExifWriter.Data("RealCamera", info.camera, "", info.lens, "",
                "", info.shutter_s, info.f_number, info.iso, info.focal_mm, 0, 2, 0, 5, false, w, h));
    }

    /**
     * Default rendering offset for raw converters: middle grey (0.18) sits log2(white / 0.18) stops under
     * clipping, about 3.4 at 12 EV of range; real cameras keep it ~3 stops under and add the rest here, so
     * Lightroom opens the file as bright as the camera's JPEG.
     */
    static double baselineExposure(double white) {
        return Math.log(white / 0.18) / Math.log(2) - 3.0;
    }

    public static Info readInfo(Path png) throws IOException {
        com.google.gson.JsonObject json;
        try {
            json = com.google.gson.JsonParser.parseString(Files.readString(sidecar(png))).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("bad sidecar", e);
        }
        Info info = GSON.fromJson(json, Info.class);
        if (!json.has("version")) {
            info.version = 1; // stage-2 files: an 8-bit frame before any processing
        }
        return info;
    }

    /** Linear floats (scaled by info.white) and {width, height}: DNG, 16-bit PNG, or 8-bit legacy PNG (sRGB). */
    public static float[] readPixels(Path png, Info info, int[] size) throws IOException {
        if (DngFile.is(png)) {
            short[] samples = DngFile.read(png, size);
            float scale = (float) (info.white / 65535);
            float[] out = new float[samples.length];
            for (int i = 0; i < out.length; i++) out[i] = (samples[i] & 0xFFFF) * scale;
            return out;
        }
        BufferedImage img = ImageIO.read(png.toFile());
        if (img == null) throw new IOException("not an image: " + png);
        int w = img.getWidth(), h = img.getHeight();
        size[0] = w;
        size[1] = h;
        Raster r = img.getRaster();
        float[] out = new float[w * h * 3];
        if (r.getTransferType() == DataBuffer.TYPE_USHORT && r.getNumBands() >= 3 && info.version >= 2) {
            float scale = (float) (info.white / 65535);
            int[] px = new int[r.getNumBands()];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    r.getPixel(x, y, px);
                    int j = (y * w + x) * 3;
                    out[j] = px[0] * scale;
                    out[j + 1] = px[1] * scale;
                    out[j + 2] = px[2] * scale;
                }
            }
            return out;
        }
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        return ImagePipeline.toLinear(rgb);
    }
}
