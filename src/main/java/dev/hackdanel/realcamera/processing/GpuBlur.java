package dev.hackdanel.realcamera.processing;

/**
 * The expensive part of the depth of field ({@link LensBlur}: every layer convolved with its aperture disc and laid
 * over the ones behind it) can run on the graphics card. The client registers a {@link Backend}; when there is none,
 * or it fails, {@link LensBlur} does the same work in Java, which stays the reference (and what the tests check).
 * The backend must do exactly what {@link LensBlur}'s background and foreground loops do. Pure Java, no game classes.
 */
public final class GpuBlur {
    /** Radius ratio between neighbouring layers ({@link LensBlur#STEP}). */
    public static final double STEP = LensBlur.STEP;
    /** Largest disc radius convolved at full layer resolution ({@link LensBlur#MAX_TAP_RADIUS}). */
    public static final double MAX_TAP_RADIUS = LensBlur.MAX_TAP_RADIUS;
    /** Tile size of the cat's-eye kernel variants ({@link LensBlur}). */
    public static final int TILE = 16;

    /**
     * One frame's layers to blur.
     *
     * @param img     linear RGB, 3 floats per pixel, rows top-down
     * @param signed  signed blur radius per pixel (+ nearer than the focus plane)
     * @param farUsed layers behind the focus plane that hold anything (index = layer)
     * @param nearUsed layers in front of it
     */
    public record Job(float[] img, float[] signed, int w, int h, int levels, boolean[] farUsed, boolean[] nearUsed,
                      int blades, boolean round, int style, double stopsDown, double spherical, double apodization,
                      double obstruction) {}

    /**
     * The composited layers, premultiplied RGBA per pixel: the background (far layers, farthest first, each over the
     * ones behind it), the foreground (near layers, nearest the focus plane first) and what the foreground held under
     * each near pixel before its own farther layer was laid on ({@code behind}, may be null).
     */
    public record Result(float[] bg, float[] fg, float[] behind, boolean anyNear) {}

    /**
     * The discs of one layer, exactly as {@link LensBlur} convolves it: the layer's resolution, and per 16-cell tile
     * the kernel variant it uses (cat's eye / swirl by where the tile lies; variant 0 is the plain disc). Taps are
     * (dx, dy, weight) triplets, gather offsets: out(x) = sum w * in(x - offset).
     */
    public record LayerKernels(int scale, int lw, int lh, int tw, int th, float[] taps, int[] start, int[] count,
                               int[] tileVariant) {}

    /** The kernels of layer {@code k} of a job ({@code near}: in front of the focus plane). */
    public static LayerKernels kernels(Job job, int k, boolean near) {
        BokehKernel.Shape shape = new BokehKernel.Shape(job.blades(), job.round(), job.style(), job.stopsDown(),
                job.spherical(), job.apodization(), job.obstruction());
        int w = job.w(), h = job.h();
        double r = LensBlur.layerRadius(k);
        int scale = Math.max(1, (int) Math.ceil(r / LensBlur.MAX_TAP_RADIUS));
        int lw = (w + scale - 1) / scale, lh = (h + scale - 1) / scale;
        int tw = (lw + TILE - 1) / TILE, th = (lh + TILE - 1) / TILE;
        java.util.List<BokehKernel.Kernel> variants = new java.util.ArrayList<>();
        variants.add(BokehKernel.kernel(shape, r / scale, near));
        int[] index = new int[16 * 6];
        java.util.Arrays.fill(index, -1);
        int[] tileVariant = new int[tw * th];
        boolean shaped = Math.max(0, 1 - shape.stopsDown() / 2) > 0.02;
        double hcx = w / 2.0, hcy = h / 2.0, halfDiag = Math.hypot(hcx, hcy);
        for (int ty = 0; ty < th && shaped; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                double ox = ((tx + 0.5) * TILE * scale) - hcx, oy = ((ty + 0.5) * TILE * scale) - hcy;
                double dist = Math.min(1, Math.hypot(ox, oy) / halfDiag);
                int db = (int) Math.min(5, Math.floor(dist * 6));
                if (db == 0) continue;
                int ab = (int) Math.floorMod((long) Math.floor((Math.atan2(oy, ox) + Math.PI) / (2 * Math.PI) * 16 + 0.5), 16L);
                int key = db * 16 + ab;
                if (index[key] < 0) {
                    double ang = ab * 2 * Math.PI / 16 - Math.PI, d = (db + 0.5) / 6;
                    index[key] = variants.size();
                    variants.add(BokehKernel.kernel(shape, r / scale, near, d, -Math.cos(ang), -Math.sin(ang)));
                }
                tileVariant[ty * tw + tx] = index[key];
            }
        }
        int total = 0;
        for (BokehKernel.Kernel v : variants) total += v.w().length;
        float[] taps = new float[total * 3];
        int[] start = new int[variants.size()], count = new int[variants.size()];
        int t = 0;
        for (int i = 0; i < variants.size(); i++) {
            BokehKernel.Kernel v = variants.get(i);
            start[i] = t;
            count[i] = v.w().length;
            for (int j = 0; j < v.w().length; j++, t++) {
                taps[t * 3] = v.dx()[j];
                taps[t * 3 + 1] = v.dy()[j];
                taps[t * 3 + 2] = v.w()[j];
            }
        }
        return new LayerKernels(scale, lw, lh, tw, th, taps, start, count, tileVariant);
    }

    /** Runs a job somewhere else (the graphics card); returns null if it cannot. */
    public interface Backend {
        Result layers(Job job);
    }

    private static volatile Backend backend;

    private GpuBlur() {}

    public static void setBackend(Backend b) {
        backend = b;
    }

    /**
     * Cost of each path, ms per megapixel-layer (exponential average), learnt from the photos developed so far: an
     * integrated graphics chip can be slower than a many-core CPU, a discrete card many times faster.
     */
    private static double gpuCost = Double.NaN, cpuCost = Double.NaN;

    private static double units(Job job) {
        int used = 0;
        for (boolean b : job.farUsed()) if (b) used++;
        for (boolean b : job.nearUsed()) if (b) used++;
        return job.w() * (double) job.h() / 1e6 * Math.max(1, used);
    }

    private static synchronized double learn(double old, double sample) {
        return Double.isNaN(old) ? sample : old * 0.7 + sample * 0.3;
    }

    /** Java did the layers of this job in {@code ms}. */
    static void cpuTime(Job job, double ms) {
        cpuCost = learn(cpuCost, ms / units(job));
    }

    /**
     * The job's layers from the backend, or null (no backend, it failed, or Java is faster here): then Java does
     * them. The first job tries the card, the next one Java; after that the faster one, unless the card is not
     * clearly faster (its work also takes render-thread time from the game).
     */
    static Result tryLayers(Job job) {
        Backend b = backend;
        if (b == null || Boolean.getBoolean("realcamera.cpuDevelop")) return null;
        boolean gpuKnown = !Double.isNaN(gpuCost), cpuKnown = !Double.isNaN(cpuCost);
        if (gpuKnown && (!cpuKnown || gpuCost * 1.5 > cpuCost)) return null;
        try {
            long t0 = System.nanoTime();
            Result r = b.layers(job);
            int n = job.w() * job.h() * 4;
            if (r == null || r.bg() == null || r.bg().length != n || (r.anyNear() && (r.fg() == null || r.fg().length != n))) {
                return null;
            }
            gpuCost = learn(gpuCost, (System.nanoTime() - t0) / 1e6 / units(job));
            return r;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
