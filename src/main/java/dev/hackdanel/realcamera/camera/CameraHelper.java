package dev.hackdanel.realcamera.camera;

import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.component.CameraWear;
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

    public static BatteryCharge gripCharge(ItemStack camera) {
        return parts(camera).grip().get(RCComponents.CHARGE.get());
    }

    /** Film camera with a fully mechanical shutter: fires without a battery (the battery only runs the meter). */
    public static boolean mechanical(HolderLookup.Provider r, ItemStack camera) {
        return Specs.body(r, camera).flatMap(CameraBodySpec::film).map(CameraBodySpec.Film::mechanical).orElse(false);
    }

    public static boolean isFilm(HolderLookup.Provider r, ItemStack camera) {
        return Specs.body(r, camera).map(CameraBodySpec::isFilm).orElse(false);
    }

    /** The shutter can be released: powered, or a mechanical film body. */
    public static boolean canFire(HolderLookup.Provider r, ItemStack camera) {
        return !wear(camera).dead() && (hasPower(camera) || mechanical(r, camera));
    }

    /** The memory card inside a digital camera (read over USB by the computer and printer), or null. */
    @javax.annotation.Nullable
    public static ItemStack usbCard(ItemStack stack) {
        if (!isCamera(stack)) return null;
        ItemStack card = parts(stack).card();
        return card.getItem() instanceof dev.hackdanel.realcamera.item.MemoryCardItem ? card : null;
    }

    public static boolean hasPower(ItemStack camera) {
        if (wear(camera).dead()) {
            return false;
        }
        BatteryCharge c = charge(camera), g = gripCharge(camera);
        return (c != null && c.charge() > 0) || (g != null && g.charge() > 0);
    }

    public static CameraWear wear(ItemStack camera) {
        return camera.getOrDefault(RCComponents.WEAR.get(), CameraWear.NEW);
    }

    /**
     * Remove {@code amount} charge units: the battery grip is used first, then the body battery.
     * Returns false if there was no charge at all.
     */
    public static boolean drain(ItemStack camera, int amount) {
        CameraParts parts = parts(camera);
        for (int slot : new int[]{CameraParts.SLOT_GRIP, CameraParts.SLOT_BATTERY}) {
            ItemStack power = parts.get(slot).copy();
            BatteryCharge c = power.get(RCComponents.CHARGE.get());
            if (c != null && c.charge() > 0) {
                power.set(RCComponents.CHARGE.get(), c.withCharge(c.charge() - amount));
                setParts(camera, parts.with(slot, power));
                return true;
            }
        }
        return false;
    }

    /** Use one charge of the hot-shoe flash's batteries; false if it is flat. */
    public static boolean drainFlash(ItemStack camera) {
        CameraParts parts = parts(camera);
        ItemStack flash = parts.flash().copy();
        BatteryCharge c = flash.get(RCComponents.CHARGE.get());
        if (c == null || c.charge() <= 0) {
            return false;
        }
        flash.set(RCComponents.CHARGE.get(), c.withCharge(c.charge() - 1));
        setParts(camera, parts.with(CameraParts.SLOT_FLASH, flash));
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
        if (isFilm(r, camera)) {
            if (!(card.getItem() instanceof dev.hackdanel.realcamera.item.FilmRollItem)) return -1;
            var roll = dev.hackdanel.realcamera.item.FilmRollItem.roll(card);
            if (roll.state() > dev.hackdanel.realcamera.component.FilmRoll.LOADED) return -1;
            return Specs.film(r, card).map(f -> Math.max(0, f.frames(roll.format()) - roll.shot())).orElse(-1);
        }
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
