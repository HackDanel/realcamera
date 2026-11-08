package dev.hackdanel.realcamera.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FocusStackTest {
    private static final int W = 240, H = 160;

    /** A blocky random texture (like Minecraft blocks), RGB. */
    private static float[] texture() {
        java.util.Random rnd = new java.util.Random(7);
        float[] cells = new float[(W / 6 + 1) * (H / 6 + 1)];
        for (int i = 0; i < cells.length; i++) cells[i] = 0.1f + 0.8f * rnd.nextFloat();
        float[] img = new float[W * H * 3];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float v = cells[(y / 6) * (W / 6 + 1) + x / 6];
                img[(y * W + x) * 3] = img[(y * W + x) * 3 + 1] = img[(y * W + x) * 3 + 2] = v;
            }
        }
        return img;
    }

    private static float[] boxBlur(float[] img, int r) {
        float[] out = new float[img.length];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                for (int c = 0; c < 3; c++) {
                    float s = 0;
                    int n = 0;
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dx = -r; dx <= r; dx++) {
                            int xx = Math.max(0, Math.min(W - 1, x + dx)), yy = Math.max(0, Math.min(H - 1, y + dy));
                            s += img[(yy * W + xx) * 3 + c];
                            n++;
                        }
                    }
                    out[(y * W + x) * 3 + c] = s / n;
                }
            }
        }
        return out;
    }

    /** Sharp where {@code leftSharp} says, blurred elsewhere. */
    private static float[] halfSharp(float[] sharp, float[] blurred, boolean leftSharp) {
        float[] out = new float[sharp.length];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                boolean s = (x < W / 2) == leftSharp;
                for (int c = 0; c < 3; c++) out[(y * W + x) * 3 + c] = (s ? sharp : blurred)[(y * W + x) * 3 + c];
            }
        }
        return out;
    }

    /** The frame a camera with this misalignment records of a scene (inverse of FocusStack.warp). */
    private static float[] misalign(float[] img, double s, double dx, double dy) {
        float[] out = new float[img.length];
        double cx = (W - 1) / 2.0, cy = (H - 1) / 2.0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double fx = Math.max(0, Math.min(W - 1.001, cx + (x - dx - cx) / s));
                double fy = Math.max(0, Math.min(H - 1.001, cy + (y - dy - cy) / s));
                int x0 = (int) fx, y0 = (int) fy;
                for (int c = 0; c < 3; c++) out[(y * W + x) * 3 + c] = img[(y0 * W + x0) * 3 + c];
            }
        }
        return out;
    }

    /** Mean absolute horizontal gradient of a column range (sharpness). */
    private static double detail(float[] img, int x0, int x1) {
        double s = 0;
        int n = 0;
        for (int y = 20; y < H - 20; y++) {
            for (int x = x0; x < x1 - 1; x++) {
                s += Math.abs(img[(y * W + x + 1) * 3] - img[(y * W + x) * 3]);
                n++;
            }
        }
        return s / n;
    }

    @Test
    void framesFocusedApartMergeIntoOneSharpPicture() {
        float[] sharp = texture(), blurred = boxBlur(sharp, 4);
        float[] a = halfSharp(sharp, blurred, true), b = halfSharp(sharp, blurred, false);
        FocusStack.Fuser fuser = new FocusStack.Fuser(W, H);
        fuser.add(a);
        fuser.add(b);
        float[] out = fuser.result();
        double refL = detail(sharp, 20, W / 2 - 10), refR = detail(sharp, W / 2 + 10, W - 20);
        assertTrue(detail(out, 20, W / 2 - 10) > 0.8 * refL, "left sharp");
        assertTrue(detail(out, W / 2 + 10, W - 20) > 0.8 * refR, "right sharp");
        assertTrue(detail(blurred, 20, W / 2 - 10) < 0.6 * refL, "the blur is real");
    }

    @Test
    void breathingAndShakeAreFoundAndUndone() {
        float[] sharp = texture(), blurred = boxBlur(sharp, 2);
        float[] ref = halfSharp(sharp, blurred, true);
        float[] frame = misalign(halfSharp(sharp, blurred, false), 1.012, 3.0, -2.0);
        FocusStack.Alignment al = FocusStack.align(FocusStack.luminance(ref, W, H), FocusStack.luminance(frame, W, H), W, H);
        assertEquals(1.012, al.scale(), 0.003, "scale " + al);
        assertEquals(3.0, al.dx(), 1.0, "dx " + al);
        assertEquals(-2.0, al.dy(), 1.0, "dy " + al);
        FocusStack.Fuser fuser = new FocusStack.Fuser(W, H);
        fuser.add(ref);
        fuser.add(FocusStack.warp(frame, W, H, al));
        float[] out = fuser.result();
        assertTrue(detail(out, W / 2 + 20, W - 30) > 0.7 * detail(sharp, W / 2 + 20, W - 30), "right side sharp after alignment");
    }
}
