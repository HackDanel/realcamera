package dev.hackdanel.realcamera.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-only settings (realcamera-client.toml). */
public final class ClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue AUTO_EXPORT = BUILDER
            .comment("Save every photo as a full-resolution JPEG with EXIF into screenshots/realcamera/")
            .define("autoExport", true);

    public static final ModConfigSpec.IntValue TEXTURE_CACHE_MB = BUILDER
            .comment("VRAM budget for photo textures shown in frames and in hand, in megabytes")
            .defineInRange("textureCacheMb", 256, 32, 4096);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {}
}
