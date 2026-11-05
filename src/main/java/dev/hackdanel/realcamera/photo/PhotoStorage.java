package dev.hackdanel.realcamera.photo;

import dev.hackdanel.realcamera.RealCamera;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Photo files on disk (world/realcamera/photos/ab/abcd....jpg) plus the two SavedData indexes.
 * Single-player uses the same code through the integrated server.
 */
public final class PhotoStorage {
    private PhotoStorage() {}

    public static PhotoIndex index(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(PhotoIndex.factory(), PhotoIndex.NAME);
    }

    public static CardStorage cards(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CardStorage.factory(), CardStorage.NAME);
    }

    public static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("realcamera").resolve("photos");
    }

    public static Path file(MinecraftServer server, UUID id) {
        String s = id.toString();
        return root(server).resolve(s.substring(0, 2)).resolve(s + ".jpg");
    }

    public static void write(MinecraftServer server, UUID id, byte[] data) throws IOException {
        Path path = file(server, id);
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public static byte[] read(MinecraftServer server, UUID id) throws IOException {
        return Files.readAllBytes(file(server, id));
    }

    public static void delete(MinecraftServer server, UUID id) {
        try {
            Files.deleteIfExists(file(server, id));
        } catch (IOException e) {
            RealCamera.LOGGER.warn("Could not delete photo {}", id, e);
        }
    }

    /**
     * Reads only the JPEG header. Returns {width, height} or null if the data is not a JPEG.
     * Never decodes the pixels, so oversized or malicious files cannot exhaust memory.
     */
    public static int[] jpegSize(byte[] data) {
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return null;
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("jpeg");
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new int[]{reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
