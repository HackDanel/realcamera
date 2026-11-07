package dev.hackdanel.realcamera.item;

import net.minecraft.locale.Language;
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

    /** A mount's name; APS-C bodies and lenses use its APS-C name where it has one (Canon EF-S, RF-S). */
    public static Component mount(ResourceKey<?> mount, boolean apsc) {
        String apscKey = key("mount", mount) + ".apsc";
        return apsc && Language.getInstance().has(apscKey) ? Component.translatable(apscKey) : name("mount", mount);
    }
}
