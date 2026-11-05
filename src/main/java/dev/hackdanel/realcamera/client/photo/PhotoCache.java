package dev.hackdanel.realcamera.client.photo;

import com.mojang.blaze3d.platform.NativeImage;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.config.ClientConfig;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.processing.JpegCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client photo store: memory textures (LRU within a VRAM budget), a disk cache per server, and
 * on-demand downloads. Everything that shows a photo asks {@link #texture(UUID)} every frame.
 */
public final class PhotoCache {
    public enum State { LOADING, READY, GONE }

    public static final class Entry {
        volatile State state = State.LOADING;
        ResourceLocation texture;
        int width, height;
        long lastUsed;
        long bytes;

        public State state() {
            return state;
        }

        public ResourceLocation texture() {
            return texture;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }
    }

    private static final Map<UUID, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);
    private static final Map<UUID, Download> downloads = new HashMap<>();
    private static long vramBytes;

    private record Download(byte[][] parts, int[] received) {}

    /** Callers waiting for the JPEG bytes of a photo (the editor). */
    private static final Map<UUID, java.util.List<java.util.concurrent.CompletableFuture<byte[]>>> byteWaiters = new HashMap<>();

    /**
     * The in-game JPEG of a photo: from the disk cache, or downloaded from the server. Completes on the
     * client thread with null if the photo is gone.
     */
    public static java.util.concurrent.CompletableFuture<byte[]> loadBytes(UUID id) {
        java.util.concurrent.CompletableFuture<byte[]> f = new java.util.concurrent.CompletableFuture<>();
        Path file = diskFile(id);
        Util.ioPool().execute(() -> {
            byte[] data = null;
            if (file != null && Files.isRegularFile(file)) {
                try {
                    data = Files.readAllBytes(file);
                } catch (IOException ignored) {
                }
            }
            byte[] found = data;
            Minecraft.getInstance().execute(() -> {
                if (found != null) {
                    f.complete(found);
                    return;
                }
                boolean first = !byteWaiters.containsKey(id);
                byteWaiters.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(f);
                if (first && Minecraft.getInstance().getConnection() != null) {
                    PacketDistributor.sendToServer(new Payloads.RequestPhoto(id));
                }
            });
        });
        return f;
    }

    private static void completeWaiters(UUID id, byte[] data) {
        var list = byteWaiters.remove(id);
        if (list != null) list.forEach(f -> f.complete(data));
    }

    private PhotoCache() {}

    /** Returns the cache entry, starting a load if needed. Never null. */
    public static Entry get(UUID id) {
        Entry e = entries.get(id);
        if (e == null) {
            e = new Entry();
            entries.put(id, e);
            load(id, e);
        }
        e.lastUsed = Util.getMillis();
        return e;
    }

    private static void load(UUID id, Entry e) {
        Path file = diskFile(id);
        Util.ioPool().execute(() -> {
            byte[] data = null;
            if (file != null && Files.isRegularFile(file)) {
                try {
                    data = Files.readAllBytes(file);
                } catch (IOException ignored) {
                }
            }
            if (data != null) {
                decodeAndUpload(id, data);
            } else {
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().getConnection() != null) {
                        PacketDistributor.sendToServer(new Payloads.RequestPhoto(id));
                    }
                });
            }
        });
    }

    /** A freshly taken photo: we already have the bytes, skip the round trip. */
    public static void putLocal(UUID id, byte[] jpeg) {
        Entry e = entries.computeIfAbsent(id, k -> new Entry());
        e.lastUsed = Util.getMillis();
        writeDisk(id, jpeg);
        Util.ioPool().execute(() -> decodeAndUpload(id, jpeg));
    }

    public static void onData(Payloads.PhotoData msg) {
        if (msg.total() <= 0 || msg.total() > 512 || msg.index() < 0 || msg.index() >= msg.total()) {
            return;
        }
        Download d = downloads.computeIfAbsent(msg.id(), k -> new Download(new byte[msg.total()][], new int[]{0}));
        if (d.parts().length != msg.total() || d.parts()[msg.index()] != null) {
            return;
        }
        d.parts()[msg.index()] = msg.data();
        if (++d.received()[0] == msg.total()) {
            downloads.remove(msg.id());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] part : d.parts()) {
                out.writeBytes(part);
            }
            byte[] jpeg = out.toByteArray();
            writeDisk(msg.id(), jpeg);
            completeWaiters(msg.id(), jpeg);
            Util.ioPool().execute(() -> decodeAndUpload(msg.id(), jpeg));
        }
    }

    public static void onGone(UUID id) {
        downloads.remove(id);
        completeWaiters(id, null);
        Entry e = entries.computeIfAbsent(id, k -> new Entry());
        releaseTexture(e);
        e.state = State.GONE;
        Path file = diskFile(id);
        if (file != null) {
            Util.ioPool().execute(() -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static void decodeAndUpload(UUID id, byte[] jpeg) {
        NativeImage image;
        try {
            int[] size = new int[2];
            int[] rgb = JpegCodec.decode(jpeg, size);
            image = new NativeImage(size[0], size[1], false);
            for (int y = 0; y < size[1]; y++) {
                for (int x = 0; x < size[0]; x++) {
                    int p = rgb[y * size[0] + x];
                    // NativeImage stores ABGR
                    image.setPixelRGBA(x, y, 0xFF000000 | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF));
                }
            }
        } catch (IOException | RuntimeException ex) {
            RealCamera.LOGGER.warn("Could not decode photo {}", id, ex);
            Minecraft.getInstance().execute(() -> {
                Entry e = entries.get(id);
                if (e != null) e.state = State.GONE;
            });
            return;
        }
        Minecraft.getInstance().execute(() -> {
            Entry e = entries.computeIfAbsent(id, k -> new Entry());
            releaseTexture(e);
            DynamicTexture tex = new DynamicTexture(image);
            tex.setFilter(true, false);
            ResourceLocation loc = RealCamera.id("photo/" + id);
            Minecraft.getInstance().getTextureManager().register(loc, tex);
            e.texture = loc;
            e.width = image.getWidth();
            e.height = image.getHeight();
            e.bytes = (long) e.width * e.height * 4;
            e.state = State.READY;
            vramBytes += e.bytes;
            evict();
        });
    }

    private static void releaseTexture(Entry e) {
        if (e.texture != null) {
            Minecraft.getInstance().getTextureManager().release(e.texture);
            vramBytes -= e.bytes;
            e.texture = null;
            e.bytes = 0;
        }
    }

    /** Drop least-recently-used textures not drawn in the last few seconds until within budget. */
    private static void evict() {
        long budget = ClientConfig.TEXTURE_CACHE_MB.get() * 1024L * 1024L;
        long now = Util.getMillis();
        Iterator<Map.Entry<UUID, Entry>> it = entries.entrySet().iterator();
        while (vramBytes > budget && it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.state == State.READY && now - e.lastUsed > 3000) {
                releaseTexture(e);
                it.remove();
            }
        }
    }

    /** Forget everything in memory (on disconnect). The disk cache stays. */
    public static void clear() {
        entries.values().forEach(PhotoCache::releaseTexture);
        entries.clear();
        downloads.clear();
        byteWaiters.clear();
        vramBytes = 0;
    }

    private static void writeDisk(UUID id, byte[] data) {
        Path file = diskFile(id);
        if (file == null) return;
        Util.ioPool().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, data);
            } catch (IOException e) {
                RealCamera.LOGGER.debug("Photo disk cache write failed", e);
            }
        });
    }

    /** realcamera_cache/<hash of server address or world name>/<uuid>.jpg */
    private static Path diskFile(UUID id) {
        Minecraft mc = Minecraft.getInstance();
        String key;
        if (mc.getSingleplayerServer() != null) {
            key = "sp:" + mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize();
        } else {
            ServerData server = mc.getCurrentServer();
            if (server == null) return null;
            key = "mp:" + server.ip;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            String dir = HexFormat.of().formatHex(hash).substring(0, 16);
            return mc.gameDirectory.toPath().resolve("realcamera_cache").resolve(dir).resolve(id + ".jpg");
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }
}
