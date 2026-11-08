package dev.hackdanel.realcamera.processing;

/**
 * The shape and brightness profile of one out-of-focus point: the image of the aperture. Shared by the layered blur
 * ({@link LensBlur}, every pixel) and the bokeh highlights ({@code ImagePipeline.drawBokeh}, bright points), so a lamp
 * and the leaf next to it blur into the same disc.
 */
final class BokehKernel {
    private BokehKernel() {}

    /**
     * @param blades     aperture blade count (polygon corners)
     * @param round      a circle instead of a polygon (rounded blades, or wide open where the blades are retracted)
     * @param style      ImageParams.BOKEH_*
     * @param stopsDown  stops below the lens's widest aperture (lens character fades stopping down)
     */
    record Shape(int blades, boolean round, int style, double stopsDown) {
        static Shape of(ImageParams p) {
            double down = ImagePipeline.stopsDown(p);
            return new Shape(Math.max(5, p.lens().blades()), p.lens().rounded() || down < 0.17, p.lens().bokeh(), down);
        }
    }

    /** Distance from the centre to the disc edge in direction theta (radius r). */
    static double edge(Shape s, double theta, double r) {
        return s.round() ? r : ImagePipeline.polygonRadius(theta, s.blades(), r);
    }

    /**
     * Relative brightness at e (0 centre .. 1 edge) of the disc. {@code near}: the point lies in front of the focus
     * plane (reserved for lens character that differs on the two sides).
     */
    static float profile(Shape s, double e, boolean near) {
        return switch (s.style()) {
            // soap bubble: a bright thin outline with a dim centre
            case ImageParams.BOKEH_BUBBLE -> (float) (0.3 + 2.6 * Math.pow(e, 7));
            // onion rings from aspherical elements
            case ImageParams.BOKEH_BUSY -> (float) ((0.8 + 0.4 * e) * (1 + 0.3 * Math.sin(e * 22)));
            default -> (float) (0.8 + 0.4 * e);
        };
    }

    /** A discrete kernel: offsets and normalised weights. */
    record Kernel(int[] dx, int[] dy, float[] w, int reach) {}

    /**
     * The disc of radius r pixels as gather taps: out(x) = sum w * in(x - offset). In front of the focus plane the
     * aperture's image is inverted (the rays cross before the sensor), so a polygon points the other way.
     */
    static Kernel kernel(Shape s, double r, boolean near) {
        int reach = (int) Math.ceil(r + 0.5);
        int n = (2 * reach + 1) * (2 * reach + 1);
        int[] dx = new int[n], dy = new int[n];
        float[] w = new float[n];
        int k = 0;
        double sum = 0;
        for (int y = -reach; y <= reach; y++) {
            for (int x = -reach; x <= reach; x++) {
                // scatter offset o = (x, y); a near point's disc is rotated by 180°
                double ox = near ? -x : x, oy = near ? -y : y;
                double rr = Math.hypot(ox, oy);
                double edge = edge(s, Math.atan2(oy, ox), r);
                double cover = Math.min(1, Math.max(0, edge + 0.5 - rr)); // anti-aliased rim
                if (cover <= 0) continue;
                float wt = (float) (cover * profile(s, Math.min(1, rr / Math.max(0.5, edge)), near));
                if (wt <= 0) continue;
                dx[k] = x;
                dy[k] = y;
                w[k] = wt;
                sum += wt;
                k++;
            }
        }
        float inv = (float) (1 / Math.max(1e-9, sum));
        for (int i = 0; i < k; i++) w[i] *= inv;
        return new Kernel(java.util.Arrays.copyOf(dx, k), java.util.Arrays.copyOf(dy, k), java.util.Arrays.copyOf(w, k), reach);
    }
}
