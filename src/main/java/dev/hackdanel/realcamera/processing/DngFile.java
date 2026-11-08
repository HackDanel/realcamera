package dev.hackdanel.realcamera.processing;

import dev.hackdanel.realcamera.processing.ExifWriter.Entry;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Linear DNG (Adobe Digital Negative, PhotometricInterpretation LinearRaw): the sensor data as 16-bit RGB,
 * uncompressed, little-endian. Lightroom, Camera Raw, darktable and RawTherapee open it as a real RAW: white
 * balance in kelvin starting from "As Shot", exposure and highlight recovery working on the linear data.
 *
 * <p>The mod's sensor has sRGB primaries (the game renders sRGB) and is neutral under D65, so the colour
 * matrix is the standard XYZ(D65) to linear sRGB one. The white balance the camera chose goes into
 * AsShotNeutral (the sensor values of a neutral grey = 1 / multipliers).
 */
public final class DngFile {
    /** XYZ (D65) to linear sRGB, row-major. */
    private static final double[] XYZ_TO_SRGB = {
            3.2404542, -1.5371385, -0.4985314,
            -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252};
    private static final int PHOTOMETRIC_LINEAR_RAW = 34892;
    private static final int D65 = 21;

    private DngFile() {}

    /**
     * Writes {@code samples} (w*h*3 values 0..65535, black 0, white 65535). {@code baselineExposureEv} shifts the
     * default rendering so middle grey lands where a real camera's does.
     */
    public static void write(Path file, short[] samples, int w, int h, String uniqueModel, double[] asShotNeutral,
                             double baselineExposureEv, ExifWriter.Data exif) throws IOException {
        List<Entry> ifd0 = new ArrayList<>();
        ifd0.add(ExifWriter.longVal(0x00FE, 0)); // NewSubfileType: main image
        ifd0.add(ExifWriter.longVal(0x0100, w));
        ifd0.add(ExifWriter.longVal(0x0101, h));
        ifd0.add(shorts(0x0102, 16, 16, 16));
        ifd0.add(ExifWriter.shortVal(0x0103, 1)); // no compression
        ifd0.add(ExifWriter.shortVal(0x0106, PHOTOMETRIC_LINEAR_RAW));
        ifd0.add(ExifWriter.ascii(0x010F, exif.make()));
        ifd0.add(ExifWriter.ascii(0x0110, exif.model()));
        ifd0.add(ExifWriter.longVal(0x0111, 0)); // StripOffsets, patched below
        ifd0.add(ExifWriter.shortVal(0x0112, 1));
        ifd0.add(ExifWriter.shortVal(0x0115, 3));
        ifd0.add(ExifWriter.longVal(0x0116, h)); // one strip
        ifd0.add(ExifWriter.longVal(0x0117, w * h * 6));
        ifd0.add(ExifWriter.shortVal(0x011C, 1)); // chunky
        ifd0.add(ExifWriter.ascii(0x0131, "RealCamera"));
        ifd0.add(ExifWriter.ascii(0x0132, exif.dateTime()));
        ifd0.add(ExifWriter.ascii(0x013B, exif.artist()));
        ifd0.add(ExifWriter.longVal(0x8769, 0)); // Exif IFD, patched below
        ifd0.add(new Entry(0xC612, ExifWriter.BYTE, 4, new byte[]{1, 4, 0, 0})); // DNGVersion 1.4
        ifd0.add(new Entry(0xC613, ExifWriter.BYTE, 4, new byte[]{1, 1, 0, 0})); // DNGBackwardVersion 1.1
        ifd0.add(ExifWriter.ascii(0xC614, uniqueModel));
        ifd0.add(shorts(0xC61A, 0, 0, 0)); // BlackLevel
        ifd0.add(shorts(0xC61D, 65535, 65535, 65535)); // WhiteLevel
        ifd0.add(srationals(0xC621, XYZ_TO_SRGB)); // ColorMatrix1
        ifd0.add(rationals(0xC628, asShotNeutral)); // AsShotNeutral
        ifd0.add(srationals(0xC62A, baselineExposureEv)); // BaselineExposure
        ifd0.add(ExifWriter.shortVal(0xC65A, D65)); // CalibrationIlluminant1
        ifd0.sort(Comparator.comparingInt(Entry::tag));
        List<Entry> exifIfd = ExifWriter.exifEntries(exif);

        int ifd0Offset = 8;
        int exifOffset = ifd0Offset + ExifWriter.ifdSize(ifd0);
        int dataOffset = (exifOffset + ExifWriter.ifdSize(exifIfd) + 15) & ~15;
        patch(ifd0, 0x8769, exifOffset);
        patch(ifd0, 0x0111, dataOffset);

        ByteBuffer head = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN);
        head.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd0Offset);
        ExifWriter.writeIfd(head, ifd0, ifd0Offset, 0);
        ExifWriter.writeIfd(head, exifIfd, exifOffset, 0);

        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
            out.write(head.array());
            ByteBuffer row = ByteBuffer.allocate(w * 6).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < h; y++) {
                row.clear();
                row.asShortBuffer().put(samples, y * w * 3, w * 3);
                out.write(row.array());
            }
        }
    }

    /** True if the file starts like a TIFF/DNG (little-endian, as written here). */
    public static boolean is(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(4);
            ch.read(b, 0);
            return b.position() == 4 && b.get(0) == 'I' && b.get(1) == 'I' && b.get(2) == 42 && b.get(3) == 0;
        }
    }

    /** Reads a DNG written by {@link #write}: the 16-bit samples, size into {@code size}. */
    public static short[] read(Path file, int[] size) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
            if (m.getShort(0) != 0x4949 || m.getShort(2) != 42) throw new IOException("not a little-endian DNG");
            int ifd = m.getInt(4);
            int n = m.getShort(ifd) & 0xFFFF;
            int w = 0, h = 0, spp = 1, bits = 0, compression = 1, offset = -1;
            for (int i = 0; i < n; i++) {
                int e = ifd + 2 + i * 12;
                int tag = m.getShort(e) & 0xFFFF, type = m.getShort(e + 2), count = m.getInt(e + 4);
                int v = type == 3 ? m.getShort(e + 8) & 0xFFFF : m.getInt(e + 8);
                switch (tag) {
                    case 0x0100 -> w = v;
                    case 0x0101 -> h = v;
                    case 0x0102 -> bits = count > 2 ? m.getShort(m.getInt(e + 8)) & 0xFFFF : v;
                    case 0x0103 -> compression = v;
                    case 0x0111 -> offset = count == 1 ? v : -2;
                    case 0x0115 -> spp = v;
                    default -> {}
                }
            }
            if (w <= 0 || h <= 0 || spp != 3 || bits != 16 || compression != 1 || offset < 0
                    || (long) offset + (long) w * h * 6 > m.capacity()) {
                throw new IOException("unsupported DNG layout");
            }
            short[] out = new short[w * h * 3];
            m.position(offset);
            m.asShortBuffer().get(out);
            size[0] = w;
            size[1] = h;
            return out;
        }
    }

    private static void patch(List<Entry> ifd, int tag, int value) {
        for (int i = 0; i < ifd.size(); i++) {
            if (ifd.get(i).tag() == tag) ifd.set(i, ExifWriter.longVal(tag, value));
        }
    }

    private static Entry shorts(int tag, int... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int x : v) b.putShort((short) x);
        return new Entry(tag, ExifWriter.SHORT, v.length, b.array());
    }

    private static Entry rationals(int tag, double... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double x : v) b.putInt((int) Math.round(Math.max(0, x) * 1_000_000)).putInt(1_000_000);
        return new Entry(tag, ExifWriter.RATIONAL, v.length, b.array());
    }

    private static Entry srationals(int tag, double... v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double x : v) b.putInt((int) Math.round(x * 1_000_000)).putInt(1_000_000);
        return new Entry(tag, ExifWriter.SRATIONAL, v.length, b.array());
    }
}
