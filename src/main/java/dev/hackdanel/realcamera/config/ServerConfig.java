package dev.hackdanel.realcamera.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-world server settings (serverconfig/realcamera-server.toml), synced to clients. */
public final class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("limits");
    }

    public static final ModConfigSpec.IntValue MAX_PHOTOS_PER_PLAYER = BUILDER
            .defineInRange("maxPhotosPerPlayer", 500, 0, 1_000_000);
    public static final ModConfigSpec.IntValue MAX_STORAGE_MB_PER_PLAYER = BUILDER
            .defineInRange("maxStorageMbPerPlayer", 200, 0, 1_000_000);
    public static final ModConfigSpec.IntValue MAX_UPLOAD_RESOLUTION = BUILDER
            .comment("Longest side in pixels of the in-game copy stored on the server")
            .defineInRange("maxUploadResolution", 1280, 256, 4096);
    public static final ModConfigSpec.IntValue MAX_FILE_KB = BUILDER
            .defineInRange("maxFileKb", 800, 16, 16384);
    public static final ModConfigSpec.IntValue UPLOADS_PER_MINUTE = BUILDER
            .defineInRange("uploadsPerMinute", 120, 1, 1000);

    static {
        BUILDER.pop().push("privacy");
    }

    public static final ModConfigSpec.BooleanValue STORE_RAW_ON_SERVER = BUILDER
            .define("storeRawOnServer", false);
    public static final ModConfigSpec.BooleanValue SHOW_COORDINATES_TO_OTHERS = BUILDER
            .define("showCoordinatesToOthers", false);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ServerConfig() {}
}
