package dev.hackdanel.realcamera.client.photo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.hackdanel.realcamera.RealCamera;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;

/**
 * Where the full-size originals of this player's own photos are on this computer (the server only has
 * the small in-game copy): photo id -> exported JPEG and/or RAW. Kept in realcamera_cache/originals.json.
 */
public final class LocalOriginals {
    public record Files2(@Nullable String jpeg, @Nullable String raw) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Map<String, Files2> map;

    private LocalOriginals() {}

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("realcamera_cache").resolve("originals.json");
    }

    private static synchronized Map<String, Files2> map() {
        if (map == null) {
            map = new LinkedHashMap<>();
            Path f = file();
            if (Files.isRegularFile(f)) {
                try {
                    Map<String, Files2> loaded = GSON.fromJson(Files.readString(f),
                            new TypeToken<LinkedHashMap<String, Files2>>() {}.getType());
                    if (loaded != null) map.putAll(loaded);
                } catch (IOException | RuntimeException e) {
                    RealCamera.LOGGER.warn("Could not read {}", f, e);
                }
            }
        }
        return map;
    }

    public static synchronized void put(UUID photo, @Nullable Path jpeg, @Nullable Path raw) {
        if (jpeg == null && raw == null) return;
        map().put(photo.toString(), new Files2(jpeg == null ? null : jpeg.toAbsolutePath().toString(),
                raw == null ? null : raw.toAbsolutePath().toString()));
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(map));
        } catch (IOException e) {
            RealCamera.LOGGER.warn("Could not write {}", f, e);
        }
    }

    /** Existing full-size JPEG of the photo, if this computer has it. */
    @Nullable
    public static synchronized Path jpeg(UUID photo) {
        Files2 f = map().get(photo.toString());
        return f == null || f.jpeg() == null ? null : existing(Path.of(f.jpeg()));
    }

    @Nullable
    public static synchronized Path raw(UUID photo) {
        Files2 f = map().get(photo.toString());
        return f == null || f.raw() == null ? null : existing(Path.of(f.raw()));
    }

    @Nullable
    private static Path existing(Path p) {
        return Files.isRegularFile(p) ? p : null;
    }
}
