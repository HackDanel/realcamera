package dev.hackdanel.realcamera.client.editor;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.photo.LocalOriginals;
import dev.hackdanel.realcamera.client.photo.PhotoCache;
import dev.hackdanel.realcamera.client.photo.PhotoUploader;
import dev.hackdanel.realcamera.config.ServerConfig;
import dev.hackdanel.realcamera.optics.Optics;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.processing.EditSettings;
import dev.hackdanel.realcamera.processing.ExifWriter;
import dev.hackdanel.realcamera.processing.ImageParams;
import dev.hackdanel.realcamera.processing.ImagePipeline;
import dev.hackdanel.realcamera.processing.JpegCodec;
import dev.hackdanel.realcamera.processing.PhotoEditor;
import dev.hackdanel.realcamera.processing.RawFile;
import dev.hackdanel.realcamera.registry.RCRegistries;
import dev.hackdanel.realcamera.spec.ColorProfileSpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * One photo open in the computer's editor: the source (the photographer's full-size RAW or JPEG if this
 * computer has it, otherwise the in-game copy), the settings with undo/redo, background rendering of
 * previews, and saving (new photo on the card) / exporting (full-size JPEG with EXIF on disk).
 */
public final class EditSession {
    public enum State { LOADING, READY, FAILED }

    public record Profile(String id, String name, ImageParams.Colour colour) {}

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RealCamera editor");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    public static final int PREVIEW_SIDE = 960;
    private static final int HISTORY = 64;

    public final PhotoMeta meta;
    public final BlockPos computer;
    public final int slot;
    public EditSettings settings = new EditSettings();
    private final Deque<EditSettings> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private volatile State state = State.LOADING;
    @Nullable
    private PhotoEditor.Source full, preview;
    private String sourceLabel = "";
    private final List<Profile> profiles = new ArrayList<>();
    private boolean busy;

    public EditSession(PhotoMeta meta, BlockPos computer, int slot) {
        this.meta = meta;
        this.computer = computer;
        this.slot = slot;
        load();
    }

    public State state() {
        return state;
    }

    public boolean raw() {
        return full != null && full.raw();
    }

    public String sourceLabel() {
        return sourceLabel;
    }

    public List<Profile> profiles() {
        return profiles;
    }

    @Nullable
    public PhotoEditor.Source preview() {
        return preview;
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        Path raw = LocalOriginals.raw(meta.id());
        Path jpeg = LocalOriginals.jpeg(meta.id());
        if (raw != null) {
            WORKER.execute(() -> {
                try {
                    RawFile.Info info = RawFile.readInfo(raw);
                    int[] size = new int[2];
                    float[] px = RawFile.readPixels(raw, info, size);
                    boolean isRaw = info.version >= 2;
                    PhotoEditor.Source src = new PhotoEditor.Source(px, size[0], size[1], isRaw, info.dynamic_range_ev,
                            info.asShot(), info.vignetting_ev, info.distortion, info.ca_frac);
                    String label = (isRaw ? "RAW" : "PNG") + " · " + size[0] + "×" + size[1];
                    Minecraft.getInstance().execute(() -> {
                        loadProfiles(info);
                        ready(src, label);
                    });
                } catch (IOException | RuntimeException e) {
                    RealCamera.LOGGER.warn("Could not open RAW {}", raw, e);
                    Minecraft.getInstance().execute(() -> loadJpeg(jpeg));
                }
            });
        } else {
            loadJpeg(jpeg);
        }
    }

    private void loadJpeg(@Nullable Path local) {
        if (local != null) {
            WORKER.execute(() -> {
                try {
                    decodeJpeg(Files.readAllBytes(local), true);
                } catch (IOException | RuntimeException e) {
                    Minecraft.getInstance().execute(() -> loadJpeg(null));
                }
            });
            return;
        }
        PhotoCache.loadBytes(meta.id()).thenAccept(bytes -> {
            if (bytes == null) {
                state = State.FAILED;
                return;
            }
            WORKER.execute(() -> {
                try {
                    decodeJpeg(bytes, false);
                } catch (IOException | RuntimeException e) {
                    RealCamera.LOGGER.warn("Could not decode photo {}", meta.id(), e);
                    state = State.FAILED;
                }
            });
        });
    }

    private void decodeJpeg(byte[] bytes, boolean fullSize) throws IOException {
        int[] size = new int[2];
        int[] rgb = JpegCodec.decode(bytes, size);
        Minecraft.getInstance().execute(() -> {
            double[] lens = lensProfile(size[0], size[1]);
            PhotoEditor.Source src = new PhotoEditor.Source(ImagePipeline.toLinear(rgb), size[0], size[1], false, 12,
                    new ImageParams.Colour(1, 1, 1, 1, 1, false, 1, 1, 1, 0), lens[0], lens[1], lens[2]);
            ready(src, "JPEG" + (fullSize ? "" : " (" + Component.translatable("gui.realcamera.editor.game_copy").getString()
                    + ")") + " · " + size[0] + "×" + size[1]);
        });
    }

    private void ready(PhotoEditor.Source src, String label) {
        full = src;
        preview = src.scaled(PREVIEW_SIDE);
        sourceLabel = label;
        state = State.READY;
    }

    /** Colour profiles of the camera that took a RAW, from the synced registry. */
    private void loadProfiles(RawFile.Info info) {
        var level = Minecraft.getInstance().level;
        if (level == null || info.profiles == null) return;
        var lookup = level.registryAccess().lookup(RCRegistries.COLOR_PROFILE);
        if (lookup.isEmpty()) return;
        ImageParams.Colour shot = info.asShot();
        for (String id : info.profiles) {
            ResourceLocation loc = ResourceLocation.tryParse(id);
            if (loc == null) continue;
            lookup.get().get(net.minecraft.resources.ResourceKey.create(RCRegistries.COLOR_PROFILE, loc)).ifPresent(h -> {
                ColorProfileSpec p = h.value();
                profiles.add(new Profile(id, p.name(), new ImageParams.Colour(shot.wbR(), shot.wbG(), shot.wbB(),
                        p.contrast(), p.saturation(), p.monochrome(), p.tintR(), p.tintG(), p.tintB(), p.shadowLift())));
            });
        }
    }

    /**
     * Lens character for profile correction of a JPEG: found by the lens name in the photo's data.
     * Returns {vignetting EV at this aperture, distortion k1, lateral CA as a fraction of the half-diagonal}.
     */
    private double[] lensProfile(int w, int h) {
        var level = Minecraft.getInstance().level;
        if (level == null) return new double[3];
        var lookup = level.registryAccess().lookup(RCRegistries.LENS);
        if (lookup.isEmpty()) return new double[3];
        String name = meta.exif().lens();
        for (var holder : lookup.get().listElements().toList()) {
            ResourceLocation id = holder.key().location();
            String display = Component.translatable("realcamera.lens." + id.getNamespace() + "." + id.getPath()).getString();
            LensSpec lens = holder.value();
            if (!name.startsWith(display) && !name.contains(lens.exifLensModel())) continue;
            float focal = meta.exif().focal();
            double maxAp = lens.maxApertureAt(focal);
            double stopsDown = Math.max(0, Math.log(meta.exif().fNumber() / maxAp) / Math.log(2));
            double vig = lens.optics().vignettingEv() * Math.max(0, 1 - stopsDown * 0.45);
            double ca = lens.optics().caPx() / Math.hypot(1920, 1280);
            return new double[]{vig, lens.distortionAt(focal), ca};
        }
        return new double[3];
    }

    // ------------------------------------------------------------------ history

    /** Call before a change that should be undoable (slider grabbed, button pressed). */
    public void checkpoint() {
        if (!undo.isEmpty() && undo.peekLast().equals(settings)) return;
        undo.addLast(settings.copy());
        while (undo.size() > HISTORY) undo.pollFirst();
        redo.clear();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.addLast(settings.copy());
        settings = undo.pollLast();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.addLast(settings.copy());
        settings = redo.pollLast();
    }

    @Nullable
    public ImageParams.Colour profileColour(String id) {
        for (Profile p : profiles) {
            if (p.id().equals(id)) return p.colour();
        }
        return null;
    }

    // ------------------------------------------------------------------ rendering

    public boolean busy() {
        return busy;
    }

    /** Renders the preview with the given settings on the worker; the callback runs on the client thread. */
    public void renderPreview(EditSettings s, Consumer<PhotoEditor.Result> done) {
        PhotoEditor.Source src = preview;
        if (src == null || busy) return;
        busy = true;
        ImageParams.Colour profile = profileColour(s.profile);
        WORKER.execute(() -> {
            PhotoEditor.Result r = null;
            try {
                r = PhotoEditor.apply(src, s, profile);
            } catch (RuntimeException e) {
                RealCamera.LOGGER.error("Editor preview failed", e);
            }
            PhotoEditor.Result result = r;
            Minecraft.getInstance().execute(() -> {
                busy = false;
                if (result != null) done.accept(result);
            });
        });
    }

    /** The photo developed at full resolution with default settings (for focus stacking); null until loaded. */
    @Nullable
    public PhotoEditor.Result developDefault() {
        PhotoEditor.Source src = full;
        if (src == null) return null;
        EditSettings s = new EditSettings();
        return PhotoEditor.apply(src, s, profileColour(s.profile));
    }

    /** Full-resolution render, then upload as a new photo on the card in the computer. */
    public void saveCopy(Consumer<String> status) {
        PhotoEditor.Source src = full;
        if (src == null) return;
        EditSettings s = settings.copy();
        ImageParams.Colour profile = profileColour(s.profile);
        int maxUpload = ServerConfig.MAX_UPLOAD_RESOLUTION.get();
        status.accept("gui.realcamera.editor.saving");
        WORKER.execute(() -> {
            try {
                PhotoEditor.Result r = PhotoEditor.apply(src, s, profile);
                byte[] fullJpeg = JpegCodec.encode(r.rgb(), r.w(), r.h(), 0.93f);
                int[] size = new int[2];
                int[] small = ImagePipeline.downscale(r.rgb(), r.w(), r.h(), maxUpload, size);
                byte[] upload = JpegCodec.encode(small, size[0], size[1], 0.85f);
                Minecraft.getInstance().execute(() -> PhotoUploader.uploadEdit(computer, meta.id(), slot, upload, id -> {
                    // keep the full-size result so the copy can be edited again at full resolution
                    Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("realcamera_cache").resolve("edits");
                    try {
                        Files.createDirectories(dir);
                        Path file = dir.resolve(id + ".jpg");
                        Files.write(file, fullJpeg);
                        LocalOriginals.put(id, file, null);
                    } catch (IOException e) {
                        RealCamera.LOGGER.warn("Could not keep edited original", e);
                    }
                    status.accept("gui.realcamera.editor.saved");
                }, status));
            } catch (IOException | RuntimeException e) {
                RealCamera.LOGGER.error("Saving edit failed", e);
                Minecraft.getInstance().execute(() -> status.accept("gui.realcamera.editor.failed"));
            }
        });
    }

    /** Full-resolution JPEG with EXIF to screenshots/realcamera/export/<date>/. */
    public void export(Consumer<String> status, Consumer<Path> written) {
        PhotoEditor.Source src = full;
        if (src == null) return;
        EditSettings s = settings.copy();
        ImageParams.Colour profile = profileColour(s.profile);
        String artist = meta.authorName();
        status.accept("gui.realcamera.editor.exporting");
        WORKER.execute(() -> {
            try {
                PhotoEditor.Result r = PhotoEditor.apply(src, s, profile);
                byte[] jpeg = ExifWriter.insert(JpegCodec.encode(r.rgb(), r.w(), r.h(), 0.95f), exif(artist, r.w(), r.h()));
                LocalDateTime now = LocalDateTime.now();
                Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve("realcamera")
                        .resolve("export").resolve(now.format(DateTimeFormatter.ISO_LOCAL_DATE));
                Files.createDirectories(dir);
                String base = "EDIT_" + meta.id().toString().substring(0, 8);
                Path file = dir.resolve(base + ".JPG");
                for (int i = 1; Files.exists(file); i++) {
                    file = dir.resolve(base + "_" + i + ".JPG");
                }
                Files.write(file, jpeg);
                Path out = file;
                Minecraft.getInstance().execute(() -> {
                    status.accept("gui.realcamera.editor.exported");
                    written.accept(out);
                });
            } catch (IOException | RuntimeException e) {
                RealCamera.LOGGER.error("Export failed", e);
                Minecraft.getInstance().execute(() -> status.accept("gui.realcamera.editor.failed"));
            }
        });
    }

    private ExifWriter.Data exif(String artist, int w, int h) {
        PhotoMeta.Exif e = meta.exif();
        String camera = e.camera();
        int space = camera.indexOf(' ');
        String make = space < 0 ? camera : camera.substring(0, space);
        String model = space < 0 ? camera : camera.substring(space + 1);
        String date = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
                .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(meta.createdMillis()), ZoneId.systemDefault()));
        int program = switch (e.mode()) {
            case 4 -> 1; // M
            case 2 -> 3; // A
            case 3 -> 4; // S
            default -> 2;
        };
        return new ExifWriter.Data(make, model, make, e.lens(), artist, date, e.shutter(), e.fNumber(), e.iso(),
                e.focal(), e.focal35() > 0 ? e.focal35() : Optics.focal35(e.focal(), 1), program, e.evComp() / 3.0, 5,
                e.whiteBalance() != 0, w, h);
    }

    public static UUID none() {
        return new UUID(0, 0);
    }
}
