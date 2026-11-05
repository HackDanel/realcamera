package dev.hackdanel.realcamera.camera;

import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.item.CameraBodyItem;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import dev.hackdanel.realcamera.spec.MemoryCardTypeSpec;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Convenience accessors for the camera body item and the parts inside it. */
public final class CameraHelper {
    /** Approximate stored bytes per megapixel for a JPEG Fine shot (real cameras: ~0.35-0.5 MB/MP). */
    public static final double JPEG_FINE_BYTES_PER_MP = 400_000;
    public static final double JPEG_NORMAL_BYTES_PER_MP = 220_000;

    private CameraHelper() {}

    public static boolean isCamera(ItemStack stack) {
        return stack.getItem() instanceof CameraBodyItem;
    }

    public static Optional<InteractionHand> handHoldingCamera(Player player) {
        if (isCamera(player.getMainHandItem())) return Optional.of(InteractionHand.MAIN_HAND);
        if (isCamera(player.getOffhandItem())) return Optional.of(InteractionHand.OFF_HAND);
        return Optional.empty();
    }

    public static CameraParts parts(ItemStack camera) {
        return camera.getOrDefault(RCComponents.CAMERA_PARTS.get(), CameraParts.EMPTY);
    }

    public static void setParts(ItemStack camera, CameraParts parts) {
        camera.set(RCComponents.CAMERA_PARTS.get(), parts);
    }

    public static CameraSettings settings(ItemStack camera) {
        return camera.getOrDefault(RCComponents.CAMERA_SETTINGS.get(), CameraSettings.DEFAULT);
    }

    public static BatteryCharge charge(ItemStack camera) {
        return parts(camera).battery().get(RCComponents.CHARGE.get());
    }

    public static boolean hasPower(ItemStack camera) {
        BatteryCharge c = charge(camera);
        return c != null && c.charge() > 0;
    }

    /** Remove {@code amount} charge units from the inserted battery. Returns false if it was already empty. */
    public static boolean drain(ItemStack camera, int amount) {
        CameraParts parts = parts(camera);
        ItemStack battery = parts.battery().copy();
        BatteryCharge c = battery.get(RCComponents.CHARGE.get());
        if (c == null || c.charge() <= 0) {
            return false;
        }
        battery.set(RCComponents.CHARGE.get(), c.withCharge(c.charge() - amount));
        setParts(camera, parts.with(CameraParts.SLOT_BATTERY, battery));
        return true;
    }

    /** Compressed RAW is roughly 1.2 MB per megapixel on current bodies. */
    public static final double RAW_BYTES_PER_MP = 1_200_000;

    public static long bytesPerShot(CameraBodySpec body, int quality) {
        return bytesPerShot(body, quality, CameraSettings.FORMAT_JPEG);
    }

    public static long bytesPerShot(CameraBodySpec body, int quality, int format) {
        double mp = body.sensor().megapixels();
        double jpeg = mp * (quality == CameraSettings.QUALITY_NORMAL ? JPEG_NORMAL_BYTES_PER_MP : JPEG_FINE_BYTES_PER_MP);
        double raw = mp * RAW_BYTES_PER_MP;
        return Math.round(switch (format) {
            case CameraSettings.FORMAT_RAW -> raw;
            case CameraSettings.FORMAT_RAW_JPEG -> raw + jpeg;
            default -> jpeg;
        });
    }

    /** Shots that still fit on the inserted card, or -1 if there is no usable card. */
    public static long shotsLeft(HolderLookup.Provider r, ItemStack camera) {
        ItemStack card = parts(camera).card();
        Optional<MemoryCardTypeSpec> type = Specs.card(r, card);
        Optional<CameraBodySpec> body = Specs.body(r, camera);
        if (card.isEmpty() || type.isEmpty() || body.isEmpty()) {
            return -1;
        }
        CardData data = card.getOrDefault(RCComponents.CARD.get(), CardData.EMPTY);
        long free = type.get().capacityBytes() - data.usedBytes();
        CameraSettings s = settings(camera);
        return Math.max(0, free / bytesPerShot(body.get(), s.quality(), s.fileFormat()));
    }

    public static Optional<LensSpec> mountedLens(HolderLookup.Provider r, ItemStack camera) {
        return Specs.lens(r, parts(camera).lens());
    }

    public static int shutterCount(ItemStack camera) {
        return camera.getOrDefault(RCComponents.SHUTTER_COUNT.get(), 0);
    }
}
