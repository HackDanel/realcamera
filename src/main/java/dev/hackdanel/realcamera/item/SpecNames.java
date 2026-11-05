package dev.hackdanel.realcamera.item;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;

/** Display names for catalog entries: lang key "realcamera.<registry>.<namespace>.<path>". */
public final class SpecNames {
    private SpecNames() {}

    public static String key(String registry, ResourceKey<?> key) {
        return "realcamera." + registry + "." + key.location().getNamespace() + "." + key.location().getPath();
    }

    public static Component name(String registry, ResourceKey<?> key) {
        return Component.translatable(key(registry, key));
    }
}
