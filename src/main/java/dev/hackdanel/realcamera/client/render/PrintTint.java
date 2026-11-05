package dev.hackdanel.realcamera.client.render;

import dev.hackdanel.realcamera.component.PrintData;

/** Colour cast of a print made with empty cartridges (vertex colour multiplier). */
public final class PrintTint {
    private PrintTint() {}

    public static int color(int missing) {
        float r = 1, g = 1, b = 1;
        // Without cyan ink nothing absorbs red: everything shifts towards red, i.e. G and B lose weight.
        if ((missing & PrintData.INK_C) != 0) { g *= 0.85f; b *= 0.85f; }
        if ((missing & PrintData.INK_M) != 0) { r *= 0.85f; b *= 0.85f; }
        if ((missing & PrintData.INK_Y) != 0) { r *= 0.85f; g *= 0.85f; }
        if ((missing & PrintData.INK_K) != 0) { r = 0.5f + r * 0.5f; g = 0.5f + g * 0.5f; b = 0.5f + b * 0.5f; }
        return 0xFF000000 | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
