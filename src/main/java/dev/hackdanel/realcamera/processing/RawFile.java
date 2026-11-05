package dev.hackdanel.realcamera.processing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * The mod's "RAW" format: a 16-bit linear PNG of the sensor data (before white balance, tone curve and
 * colour profile; 65535 = sensor saturation) and a JSON sidecar with what the editor needs to develop it.
 * Opened in an ordinary viewer the PNG looks dark and flat, like an undeveloped RAW.
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

    public static void write(Path png, float[] linear, int w, int h, Info info) throws IOException {
        WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT, w, h, 3, new Point());
        double scale = 65535 / info.white;
        int[] px = new int[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int j = (y * w + x) * 3;
                for (int c = 0; c < 3; c++) {
                    px[c] = (int) Math.max(0, Math.min(65535, Math.round(linear[j + c] * scale)));
                }
                raster.setPixel(x, y, px);
            }
        }
        ComponentColorModel cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB), false,
                false, Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        BufferedImage img = new BufferedImage(cm, raster, false, null);
        Files.createDirectories(png.toAbsolutePath().getParent());
        if (!ImageIO.write(img, "png", png.toFile())) {
            throw new IOException("no PNG writer for 16-bit images");
        }
        Files.writeString(sidecar(png), GSON.toJson(info));
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

    /** Linear floats (scaled by info.white) and {width, height}; 8-bit legacy files are treated as sRGB. */
    public static float[] readPixels(Path png, Info info, int[] size) throws IOException {
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
