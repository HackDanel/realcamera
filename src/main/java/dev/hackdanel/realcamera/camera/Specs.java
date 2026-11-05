package dev.hackdanel.realcamera.camera;

import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCRegistries;
import dev.hackdanel.realcamera.spec.AdapterSpec;
import dev.hackdanel.realcamera.spec.BatteryTypeSpec;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.ColorProfileSpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import dev.hackdanel.realcamera.spec.MemoryCardTypeSpec;
import dev.hackdanel.realcamera.spec.TeleconverterSpec;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;

/** Resolves spec records referenced by item components. */
public final class Specs {
    private Specs() {}

    public static <T> Optional<T> lookup(HolderLookup.Provider registries, ResourceKey<Registry<T>> registry,
                                         ResourceKey<T> key) {
        if (registries == null || key == null) {
            return Optional.empty();
        }
        return registries.lookup(registry).flatMap(r -> r.get(key)).map(h -> h.value());
    }

    public static Optional<CameraBodySpec> body(HolderLookup.Provider r, ItemStack stack) {
        return lookup(r, RCRegistries.CAMERA_BODY, stack.get(RCComponents.BODY.get()));
    }

    public static Optional<LensSpec> lens(HolderLookup.Provider r, ItemStack stack) {
        return lookup(r, RCRegistries.LENS, stack.get(RCComponents.LENS.get()));
    }

    public static Optional<BatteryTypeSpec> battery(HolderLookup.Provider r, ItemStack stack) {
        return lookup(r, RCRegistries.BATTERY_TYPE, stack.get(RCComponents.BATTERY_TYPE.get()));
    }

    public static Optional<MemoryCardTypeSpec> card(HolderLookup.Provider r, ItemStack stack) {
        return lookup(r, RCRegistries.MEMORY_CARD_TYPE, stack.get(RCComponents.CARD_TYPE.get()));
    }

    public static Optional<AdapterSpec> adapter(HolderLookup.Provider r, ItemStack stack) {
        return lookup(r, RCRegistries.MOUNT_ADAPTER, stack.get(RCComponents.ADAPTER.get()));
    }

    public static Optional<TeleconverterSpec> teleconverter(HolderLookup.Provider r, ItemStack stack) {
        return lookup(r, RCRegistries.TELECONVERTER, stack.get(RCComponents.TELECONVERTER.get()));
    }

    public static Optional<ColorProfileSpec> profile(HolderLookup.Provider r, CameraBodySpec body, int index) {
        if (body.colorProfiles().isEmpty()) {
            return Optional.empty();
        }
        int i = Math.floorMod(index, body.colorProfiles().size());
        return lookup(r, RCRegistries.COLOR_PROFILE, body.colorProfiles().get(i));
    }
}
