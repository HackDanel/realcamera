package dev.hackdanel.realcamera.photo;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.processing.EditSettings;
import dev.hackdanel.realcamera.processing.FilmLook;
import dev.hackdanel.realcamera.processing.ImageParams;
import dev.hackdanel.realcamera.processing.ImagePipeline;
import dev.hackdanel.realcamera.processing.JpegCodec;
import dev.hackdanel.realcamera.processing.PhotoEditor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;

/**
 * Server-side darkroom work on stored frames: developing a roll (push/pull, light leaks) and enlarging a
 * frame onto paper (exposure, contrast grade, paper tone, fogged paper). Image work runs on the IO pool.
 */
public final class FilmLab {
    public static final int FOG_NONE = 0, FOG_BACK_OPENED = 1, FOG_ALL = 2;

    private FilmLab() {}

    /**
     * Develops every latent frame on the roll. FOG_BACK_OPENED burns the last few frames and leaks light
     * into the rest; FOG_ALL (loaded into the tank in the light) burns them all.
     */
    public static void developRoll(MinecraftServer server, UUID rollId, int push, int fog, boolean monochrome, Runnable done) {
        List<UUID> frames = new ArrayList<>(PhotoStorage.cards(server).photos(rollId));
        Util.ioPool().execute(() -> {
            List<Object[]> results = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                UUID id = frames.get(i);
                try {
                    byte[] jpeg = PhotoStorage.read(server, id);
                    int[] size = new int[2];
                    int[] rgb = JpegCodec.decode(jpeg, size);
                    long seed = id.getLeastSignificantBits();
                    rgb = FilmLook.push(rgb, size[0], size[1], push, seed);
                    double severity = switch (fog) {
                        case FOG_ALL -> 1;
                        case FOG_BACK_OPENED -> i >= frames.size() - 4 ? 1 : 0.25 + 0.15 * ((seed >>> 8) & 3);
                        default -> 0;
                    };
                    rgb = FilmLook.lightLeak(rgb, size[0], size[1], severity, seed);
                    if (monochrome && severity > 0) {
                        // black-and-white film records the leak as plain density, not colour
                        for (int k = 0; k < rgb.length; k++) {
                            int p = rgb[k];
                            int l = (int) (0.2126 * ((p >> 16) & 255) + 0.7152 * ((p >> 8) & 255) + 0.0722 * (p & 255));
                            rgb[k] = 0xFF000000 | (l << 16) | (l << 8) | l;
                        }
                    }
                    byte[] out = JpegCodec.encode(rgb, size[0], size[1], 0.88f);
                    PhotoStorage.write(server, id, out);
                    results.add(new Object[]{id, (long) out.length, size[0], size[1]});
                } catch (IOException | RuntimeException e) {
                    RealCamera.LOGGER.warn("Developing frame {} failed", id, e);
                    results.add(new Object[]{id, -1L, 0, 0});
                }
            }
            server.execute(() -> {
                PhotoIndex index = PhotoStorage.index(server);
                for (Object[] r : results) {
                    PhotoIndex.Entry e = index.get((UUID) r[0]);
                    if (e == null) continue;
                    long bytes = (long) r[1];
                    PhotoIndex.Entry done2 = bytes < 0 ? e.developed(e.fileBytes(), e.meta().width(), e.meta().height())
                            : e.developed(bytes, (int) r[2], (int) r[3]);
                    index.put(done2);
                    // the frame is visible now: the photographer's encyclopedia and advancements learn of it
                    var author = server.getPlayerList().getPlayer(done2.meta().authorId());
                    if (author != null && fog != FOG_ALL) {
                        dev.hackdanel.realcamera.gameplay.PhotoEvents.photoStored(author, done2.meta());
                    }
                }
                done.run();
            });
        });
    }

    /**
     * Enlarging one frame: a new stored photo (derived from the frame) the print will show.
     *
     * @param exposureStops lighter (+) / darker (-) print
     * @param grade         contrast grade 0..5 for black-and-white paper (2 = normal)
     * @param paper         rc_bw, fiber_bw or ra4
     */
    public static void enlarge(MinecraftServer server, UUID frame, UUID owner, double exposureStops, int grade,
                               String paper, boolean fogged, Consumer<Optional<PhotoMeta>> done) {
        PhotoIndex.Entry src = PhotoStorage.index(server).get(frame);
        if (src == null || src.revoked() || src.latent()) {
            done.accept(Optional.empty());
            return;
        }
        Util.ioPool().execute(() -> {
            try {
                byte[] jpeg = PhotoStorage.read(server, frame);
                int[] size = new int[2];
                int[] rgb = JpegCodec.decode(jpeg, size);
                int[] printed = print(rgb, size[0], size[1], exposureStops, grade, paper, fogged);
                byte[] out = JpegCodec.encode(printed, size[0], size[1], 0.9f);
                UUID id = UUID.randomUUID();
                PhotoStorage.write(server, id, out);
                server.execute(() -> {
                    PhotoMeta meta = src.meta().editedCopy(id, size[0], size[1]);
                    PhotoStorage.index(server).put(new PhotoIndex.Entry(meta, out.length, 0, 0, false, 0,
                            Optional.of(owner), false));
                    done.accept(Optional.of(meta));
                });
            } catch (IOException | RuntimeException e) {
                RealCamera.LOGGER.warn("Enlarging {} failed", frame, e);
                server.execute(() -> done.accept(Optional.empty()));
            }
        });
    }

    /** The darkroom print look (pure image work, see {@link #enlarge}). */
    public static int[] print(int[] rgb, int w, int h, double exposureStops, int grade, String paper, boolean fogged) {
        PhotoEditor.Source src = new PhotoEditor.Source(ImagePipeline.toLinear(rgb), w, h, false, 12,
                new ImageParams.Colour(1, 1, 1, 1, 1, false, 1, 1, 1, 0), 0, 0, 0);
        EditSettings e = new EditSettings();
        boolean bw = !paper.equals("ra4");
        e.exposure = Math.max(-1.5, Math.min(1.5, exposureStops * 0.5));
        e.monochrome = bw;
        e.contrast = bw ? (grade - 2) * 22 : 5;
        if (paper.equals("fiber_bw")) {
            e.temperature = 12; // warm-tone fibre paper
            e.blacks = -10;
        }
        int[] out = PhotoEditor.apply(src, e, null).rgb();
        if (fogged) {
            // the whole sheet saw light: a heavy grey veil, little picture left
            for (int i = 0; i < out.length; i++) {
                int p = out[i];
                int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                r = (int) (r * 0.25 + 40);
                g = (int) (g * 0.25 + 38);
                b = (int) (b * 0.25 + 36);
                out[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }
}
