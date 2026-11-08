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
     * @param spherical  spherical aberration, already faded by stopping down (+: soft discs behind the focus plane,
     *                   bright-rimmed ones in front of it)
     * @param apodization 0..1: discs fade softly to their edge
     * @param obstruction 0..0.6: dark centre of a mirror lens's discs, fraction of the radius
     */
    record Shape(int blades, boolean round, int style, double stopsDown, double spherical, double apodization,
                 double obstruction) {
        Shape(int blades, boolean round, int style, double stopsDown) {
            this(blades, round, style, stopsDown, 0, 0, 0);
        }

        static Shape of(ImageParams p) {
            return of(p.lens(), ImagePipeline.stopsDown(p));
        }

        static Shape of(ImageParams.Lens l, double down) {
            ImageParams.Character c = l.character();
            return new Shape(Math.max(5, l.blades()), l.rounded() || down < 0.17, l.bokeh(), down,
                    c.spherical() * Math.max(0, 1 - down / 2), c.apodization() * Math.max(0.4, 1 - down / 3),
                    c.obstruction());
        }
    }

    /** Distance from the centre to the disc edge in direction theta (radius r). */
    static double edge(Shape s, double theta, double r) {
        return s.round() ? r : ImagePipeline.polygonRadius(theta, s.blades(), r);
    }

    /**
     * Relative brightness at e (0 centre .. 1 edge) of the disc. {@code near}: the point lies in front of the focus
     * plane (spherical aberration draws the two sides differently).
     */
    static float profile(Shape s, double e, boolean near) {
        if (s.obstruction() > 0 && e < s.obstruction()) {
            return 0; // the secondary mirror's shadow
        }
        double v = switch (s.style()) {
            // soap bubble: a bright thin outline with a dim centre
            case ImageParams.BOKEH_BUBBLE -> 0.3 + 2.6 * Math.pow(e, 7);
            // onion rings from aspherical elements
            case ImageParams.BOKEH_BUSY -> (0.8 + 0.4 * e) * (1 + 0.3 * Math.sin(e * 22));
            default -> 0.8 + 0.4 * e;
        };
        if (s.spherical() != 0) {
            // under-corrected: light piles up at the rim of the discs in front of the focus and in the middle of
            // those behind it
            double k = (near ? -1 : 1) * s.spherical() * 0.7;
            v *= Math.max(0.05, 1 + k * (0.6 - 1.2 * e));
        }
        if (s.apodization() > 0) {
            // the filter darkens towards the rim of the aperture: a gaussian-like disc instead of a bright edge
            double a = s.apodization();
            v = (v + (1 - v) * a) * ((1 - a) + a * Math.exp(-2.5 * e * e));
        }
        return (float) Math.max(0, v);
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
