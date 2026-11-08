package dev.hackdanel.realcamera.client.editor;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.photo.LocalOriginals;
import dev.hackdanel.realcamera.client.photo.PhotoUploader;
import dev.hackdanel.realcamera.config.ServerConfig;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.processing.FocusStack;
import dev.hackdanel.realcamera.processing.ImagePipeline;
import dev.hackdanel.realcamera.processing.JpegCodec;
import dev.hackdanel.realcamera.processing.PhotoEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Focus stacking on the computer: the selected frames (a focus shift series, or any frames of one scene) are opened
 * like the editor does (the photographer's full-size original if this computer has it, else the in-game copy),
 * developed with default settings, aligned to the first one and merged ({@link FocusStack}). The result is saved
 * as a new photo on the card, like an edit of the first frame.
 */
public final class StackJob {
    public static final int MAX_FRAMES = 20;

    private StackJob() {}

    /** status receives translation keys (stacking, saved, failed). */
    public static void start(List<PhotoMeta> frames, BlockPos computer, int slot, Consumer<String> status) {
        List<PhotoMeta> list = frames.size() > MAX_FRAMES ? frames.subList(0, MAX_FRAMES) : List.copyOf(frames);
        status.accept("gui.realcamera.computer.stacking");
        Thread t = new Thread(() -> run(list, computer, slot, status), "RealCamera focus stack");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static void run(List<PhotoMeta> frames, BlockPos computer, int slot, Consumer<String> status) {
        try {
            // open every frame; the sessions load in the background
            List<EditSession> sessions = new ArrayList<>();
            Minecraft.getInstance().executeBlocking(() -> {
                for (PhotoMeta m : frames) sessions.add(new EditSession(m, computer, slot));
            });
            List<int[]> rgbs = new ArrayList<>();
            List<int[]> sizes = new ArrayList<>();
            int[] dims = null;
            for (int i = 0; i < sessions.size(); i++) {
                EditSession s = sessions.get(i);
                long until = System.currentTimeMillis() + 60_000;
                while (s.state() == EditSession.State.LOADING && System.currentTimeMillis() < until) Thread.sleep(50);
                PhotoEditor.Result r = s.state() == EditSession.State.READY ? s.developDefault() : null;
                sessions.set(i, null); // let its source go
                if (r == null) {
                    fail(status, "gui.realcamera.computer.stack_failed");
                    return;
                }
                int[] d = {r.w(), r.h()};
                if (dims == null || d[0] < dims[0]) dims = d;
                rgbs.add(r.rgb());
                sizes.add(d);
            }
            // a mix of full-size originals and in-game copies: bring all to the smallest
            int w = dims[0], h = dims[1];
            FocusStack.Fuser fuser = new FocusStack.Fuser(w, h);
            float[] refLum = null;
            for (int i = 0; i < rgbs.size(); i++) {
                int[] d = sizes.get(i);
                int[] rgb = rgbs.get(i);
                rgbs.set(i, null);
                if (d[0] != w) {
                    int[] size = new int[2];
                    rgb = ImagePipeline.downscale(rgb, d[0], d[1], Math.max(w, h), size);
                    if (size[0] != w || size[1] != h) {
                        fail(status, "gui.realcamera.computer.stack_size");
                        return;
                    }
                }
                float[] lin = ImagePipeline.toLinear(rgb);
                float[] lum = FocusStack.luminance(lin, w, h);
                if (refLum == null) {
                    refLum = lum;
                    fuser.add(lin);
                } else {
                    FocusStack.Alignment al = FocusStack.align(refLum, lum, w, h);
                    RealCamera.LOGGER.debug("Focus stack frame {}: {}", i, al);
                    fuser.add(FocusStack.warp(lin, w, h, al));
                }
            }
            int[] result = ImagePipeline.toSrgb(fuser.result());
            byte[] fullJpeg = JpegCodec.encode(result, w, h, 0.93f);
            int[] size = new int[2];
            int[] small = ImagePipeline.downscale(result, w, h, ServerConfig.MAX_UPLOAD_RESOLUTION.get(), size);
            byte[] upload = JpegCodec.encode(small, size[0], size[1], 0.85f);
            Minecraft.getInstance().execute(() -> PhotoUploader.uploadEdit(computer, frames.get(0).id(), slot, upload, id -> {
                Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("realcamera_cache").resolve("edits");
                try {
                    Files.createDirectories(dir);
                    Path file = dir.resolve(id + ".jpg");
                    Files.write(file, fullJpeg);
                    LocalOriginals.put(id, file, null);
                } catch (java.io.IOException e) {
                    RealCamera.LOGGER.warn("Could not keep the stacked original", e);
                }
                status.accept("gui.realcamera.computer.stacked");
            }, status));
        } catch (Exception e) {
            RealCamera.LOGGER.error("Focus stacking failed", e);
            fail(status, "gui.realcamera.computer.stack_failed");
        }
    }

    private static void fail(Consumer<String> status, String key) {
        Minecraft.getInstance().execute(() -> status.accept(key));
    }
}
