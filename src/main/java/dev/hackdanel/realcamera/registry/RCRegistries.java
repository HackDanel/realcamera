package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.spec.AdapterSpec;
import dev.hackdanel.realcamera.spec.BatteryTypeSpec;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.ColorProfileSpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import dev.hackdanel.realcamera.spec.MemoryCardTypeSpec;
import dev.hackdanel.realcamera.spec.MountSpec;
import dev.hackdanel.realcamera.spec.TeleconverterSpec;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/**
 * Datapack registries holding the equipment catalog. Synced to clients, so adding a camera is just
 * a JSON file under data/<namespace>/realcamera/<registry>/.
 */
@EventBusSubscriber(modid = RealCamera.MODID)
public final class RCRegistries {
    public static final ResourceKey<Registry<MountSpec>> MOUNT = key("mount");
    public static final ResourceKey<Registry<CameraBodySpec>> CAMERA_BODY = key("camera_body");
    public static final ResourceKey<Registry<LensSpec>> LENS = key("lens");
    public static final ResourceKey<Registry<BatteryTypeSpec>> BATTERY_TYPE = key("battery_type");
    public static final ResourceKey<Registry<MemoryCardTypeSpec>> MEMORY_CARD_TYPE = key("memory_card_type");
    public static final ResourceKey<Registry<ColorProfileSpec>> COLOR_PROFILE = key("color_profile");
    public static final ResourceKey<Registry<AdapterSpec>> MOUNT_ADAPTER = key("mount_adapter");
    public static final ResourceKey<Registry<TeleconverterSpec>> TELECONVERTER = key("teleconverter");
    public static final ResourceKey<Registry<dev.hackdanel.realcamera.spec.FlashSpec>> FLASH_TYPE = key("flash_type");
    public static final ResourceKey<Registry<dev.hackdanel.realcamera.spec.FilterSpec>> FILTER_TYPE = key("filter_type");
    public static final ResourceKey<Registry<dev.hackdanel.realcamera.spec.GripSpec>> GRIP_TYPE = key("grip_type");
    public static final ResourceKey<Registry<dev.hackdanel.realcamera.spec.StepUpRingSpec>> STEP_UP_RING = key("step_up_ring");
    public static final ResourceKey<Registry<dev.hackdanel.realcamera.spec.FilmStockSpec>> FILM_STOCK = key("film_stock");

    private RCRegistries() {}

    private static <T> ResourceKey<Registry<T>> key(String path) {
        return ResourceKey.createRegistryKey(RealCamera.id(path));
    }

    @SubscribeEvent
    static void register(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(MOUNT, MountSpec.CODEC, MountSpec.CODEC);
        event.dataPackRegistry(CAMERA_BODY, CameraBodySpec.CODEC, CameraBodySpec.CODEC);
        event.dataPackRegistry(LENS, LensSpec.CODEC, LensSpec.CODEC);
        event.dataPackRegistry(BATTERY_TYPE, BatteryTypeSpec.CODEC, BatteryTypeSpec.CODEC);
        event.dataPackRegistry(MEMORY_CARD_TYPE, MemoryCardTypeSpec.CODEC, MemoryCardTypeSpec.CODEC);
        event.dataPackRegistry(COLOR_PROFILE, ColorProfileSpec.CODEC, ColorProfileSpec.CODEC);
        event.dataPackRegistry(MOUNT_ADAPTER, AdapterSpec.CODEC, AdapterSpec.CODEC);
        event.dataPackRegistry(TELECONVERTER, TeleconverterSpec.CODEC, TeleconverterSpec.CODEC);
        event.dataPackRegistry(FLASH_TYPE, dev.hackdanel.realcamera.spec.FlashSpec.CODEC, dev.hackdanel.realcamera.spec.FlashSpec.CODEC);
        event.dataPackRegistry(FILTER_TYPE, dev.hackdanel.realcamera.spec.FilterSpec.CODEC, dev.hackdanel.realcamera.spec.FilterSpec.CODEC);
        event.dataPackRegistry(GRIP_TYPE, dev.hackdanel.realcamera.spec.GripSpec.CODEC, dev.hackdanel.realcamera.spec.GripSpec.CODEC);
        event.dataPackRegistry(STEP_UP_RING, dev.hackdanel.realcamera.spec.StepUpRingSpec.CODEC, dev.hackdanel.realcamera.spec.StepUpRingSpec.CODEC);
        event.dataPackRegistry(FILM_STOCK, dev.hackdanel.realcamera.spec.FilmStockSpec.CODEC, dev.hackdanel.realcamera.spec.FilmStockSpec.CODEC);
    }

    public static <T> Optional<T> get(RegistryAccess access, ResourceKey<Registry<T>> registry, ResourceKey<T> key) {
        return access.registry(registry).flatMap(r -> Optional.ofNullable(r.get(key)));
    }
}
