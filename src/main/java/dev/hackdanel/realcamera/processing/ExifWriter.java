package dev.hackdanel.realcamera.processing;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal EXIF (APP1) writer: enough tags for photo software to show camera, lens and exposure.
 * Little-endian TIFF with IFD0 and an Exif sub-IFD.
 */
public final class ExifWriter {
    public record Data(String make, String model, String lensMake, String lensModel, String artist, String dateTime,
                       double exposureTime, double fNumber, int iso, double focalMm, int focal35, int program,
                       double exposureBiasEv, int metering, boolean manualWhiteBalance, int width, int height) {}

    static final short BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, UNDEFINED = 7, SRATIONAL = 10;

    record Entry(int tag, short type, int count, byte[] value) {}

    private ExifWriter() {}

    /** Returns the JPEG with an EXIF APP1 segment inserted right after SOI. */
    public static byte[] insert(byte[] jpeg, Data d) {
        byte[] tiff = tiff(d);
        int segLen = 2 + 6 + tiff.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + segLen + 2);
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(0xE1);
        out.write((segLen >> 8) & 0xFF);
        out.write(segLen & 0xFF);
        out.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static byte[] tiff(Data d) {
        List<Entry> ifd0 = new ArrayList<>();
        ifd0.add(ascii(0x010F, d.make()));
        ifd0.add(ascii(0x0110, d.model()));
        ifd0.add(shortVal(0x0112, 1));
        ifd0.add(ascii(0x0131, "RealCamera"));
        ifd0.add(ascii(0x0132, d.dateTime()));
        ifd0.add(ascii(0x013B, d.artist()));
        // 0x8769 Exif IFD pointer, value patched below
        ifd0.add(new Entry(0x8769, LONG, 1, new byte[4]));

        List<Entry> exif = exifEntries(d);

        ifd0.sort(Comparator.comparingInt(Entry::tag));

        int ifd0Offset = 8;
        int ifd0Size = ifdSize(ifd0);
        int exifOffset = ifd0Offset + ifd0Size;
        // patch the pointer
        for (int i = 0; i < ifd0.size(); i++) {
            if (ifd0.get(i).tag() == 0x8769) {
                ifd0.set(i, longVal(0x8769, exifOffset));
            }
        }
        int exifSize = ifdSize(exif);
        ByteBuffer buf = ByteBuffer.allocate(exifOffset + exifSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd0Offset);
        writeIfd(buf, ifd0, ifd0Offset, 0);
        writeIfd(buf, exif, exifOffset, 0);
        return buf.array();
    }

    /** The Exif sub-IFD entries (exposure, lens, date), sorted by tag. */
    static List<Entry> exifEntries(Data d) {
        List<Entry> exif = new ArrayList<>();
        exif.add(rational(0x829A, d.exposureTime()));
        exif.add(rational(0x829D, d.fNumber()));
        exif.add(shortVal(0x8822, d.program()));
        exif.add(shortVal(0x8827, Math.min(65535, d.iso())));
        exif.add(new Entry(0x9000, UNDEFINED, 4, "0232".getBytes(StandardCharsets.US_ASCII)));
        exif.add(ascii(0x9003, d.dateTime()));
        exif.add(srational(0x9204, d.exposureBiasEv()));
        exif.add(shortVal(0x9207, d.metering()));
        exif.add(shortVal(0x9209, 0x10)); // flash did not fire, compulsory suppression
        exif.add(rational(0x920A, d.focalMm()));
        exif.add(longVal(0xA002, d.width()));
        exif.add(longVal(0xA003, d.height()));
        exif.add(shortVal(0xA403, d.manualWhiteBalance() ? 1 : 0));
        exif.add(shortVal(0xA405, d.focal35()));
        exif.add(ascii(0xA433, d.lensMake()));
        exif.add(ascii(0xA434, d.lensModel()));
        exif.sort(Comparator.comparingInt(Entry::tag));
        return exif;
    }

    /** IFD byte size including its out-of-line data area. */
    static int ifdSize(List<Entry> entries) {
        int size = 2 + entries.size() * 12 + 4;
        for (Entry e : entries) {
            if (e.value().length > 4) size += (e.value().length + 1) & ~1;
        }
        return size;
    }

    static void writeIfd(ByteBuffer buf, List<Entry> entries, int offset, int next) {
        buf.position(offset);
        buf.putShort((short) entries.size());
        int dataOffset = offset + 2 + entries.size() * 12 + 4;
        for (Entry e : entries) {
            buf.putShort((short) e.tag()).putShort(e.type()).putInt(e.count());
            if (e.value().length <= 4) {
                byte[] v = new byte[4];
                System.arraycopy(e.value(), 0, v, 0, e.value().length);
                buf.put(v);
            } else {
                buf.putInt(dataOffset);
                int pos = buf.position();
                buf.position(dataOffset);
                buf.put(e.value());
                dataOffset += (e.value().length + 1) & ~1;
                buf.position(pos);
            }
        }
        buf.putInt(next);
    }

    static Entry ascii(int tag, String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.US_ASCII);
        byte[] z = new byte[b.length + 1];
        System.arraycopy(b, 0, z, 0, b.length);
        return new Entry(tag, ASCII, z.length, z);
    }

    static Entry shortVal(int tag, int v) {
        return new Entry(tag, SHORT, 1, ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) v).array());
    }

    static Entry longVal(int tag, int v) {
        return new Entry(tag, LONG, 1, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array());
    }

    static Entry rational(int tag, double v) {
        long num, den;
        if (v > 0 && v < 1) {
            den = Math.round(1 / v);
            num = 1;
            if (Math.abs(1.0 / den - v) / v > 0.02) { // not a clean 1/n
                num = Math.round(v * 10000);
                den = 10000;
            }
        } else {
            num = Math.round(v * 10);
            den = 10;
        }
        return new Entry(tag, RATIONAL, 1, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) num).putInt((int) den).array());
    }

    static Entry srational(int tag, double v) {
        return new Entry(tag, SRATIONAL, 1, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) Math.round(v * 3)).putInt(3).array());
    }
}
