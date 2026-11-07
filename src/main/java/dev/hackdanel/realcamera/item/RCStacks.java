package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.component.PaperData;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.registry.RCRegistries;
import dev.hackdanel.realcamera.spec.BatteryTypeSpec;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import dev.hackdanel.realcamera.spec.MemoryCardTypeSpec;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;

/** Factories for catalog item stacks. */
public final class RCStacks {
    private RCStacks() {}

    public static ItemStack body(ResourceKey<CameraBodySpec> key) {
        ItemStack s = new ItemStack(RCItems.CAMERA_BODY.get());
        s.set(RCComponents.BODY.get(), key);
        return s;
    }

    public static ItemStack lens(ResourceKey<LensSpec> key) {
        ItemStack s = new ItemStack(RCItems.LENS.get());
        s.set(RCComponents.LENS.get(), key);
        return s;
    }

    public static ItemStack adapter(ResourceKey<dev.hackdanel.realcamera.spec.AdapterSpec> key) {
        ItemStack s = new ItemStack(RCItems.MOUNT_ADAPTER.get());
        s.set(RCComponents.ADAPTER.get(), key);
        return s;
    }

    public static ItemStack teleconverter(ResourceKey<dev.hackdanel.realcamera.spec.TeleconverterSpec> key) {
        ItemStack s = new ItemStack(RCItems.TELECONVERTER.get());
        s.set(RCComponents.TELECONVERTER.get(), key);
        return s;
    }

    public static ItemStack flash(ResourceKey<dev.hackdanel.realcamera.spec.FlashSpec> key, int capacity) {
        ItemStack s = new ItemStack(RCItems.FLASH.get());
        s.set(RCComponents.FLASH_TYPE.get(), key);
        s.set(RCComponents.CHARGE.get(), new BatteryCharge(capacity, capacity));
        return s;
    }

    public static ItemStack filter(ResourceKey<dev.hackdanel.realcamera.spec.FilterSpec> key) {
        ItemStack s = new ItemStack(RCItems.FILTER.get());
        s.set(RCComponents.FILTER_TYPE.get(), key);
        return s;
    }

    public static ItemStack stepUpRing(ResourceKey<dev.hackdanel.realcamera.spec.StepUpRingSpec> key) {
        ItemStack s = new ItemStack(RCItems.STEP_UP_RING.get());
        s.set(RCComponents.STEP_UP_RING.get(), key);
        return s;
    }

    public static ItemStack grip(ResourceKey<dev.hackdanel.realcamera.spec.GripSpec> key, int capacity) {
        ItemStack s = new ItemStack(RCItems.BATTERY_GRIP.get());
        s.set(RCComponents.GRIP_TYPE.get(), key);
        s.set(RCComponents.CHARGE.get(), new BatteryCharge(capacity, capacity));
        return s;
    }

    public static ItemStack battery(ResourceKey<BatteryTypeSpec> key, BatteryTypeSpec spec, boolean full) {
        ItemStack s = new ItemStack(RCItems.BATTERY.get());
        s.set(RCComponents.BATTERY_TYPE.get(), key);
        s.set(RCComponents.CHARGE.get(), new BatteryCharge(full ? spec.capacity() : 0, spec.capacity()));
        return s;
    }

    public static ItemStack card(ResourceKey<MemoryCardTypeSpec> key) {
        ItemStack s = new ItemStack(RCItems.MEMORY_CARD.get());
        s.set(RCComponents.CARD_TYPE.get(), key);
        s.set(RCComponents.CARD.get(), CardData.EMPTY);
        return s;
    }

    public static ItemStack film(ResourceKey<dev.hackdanel.realcamera.spec.FilmStockSpec> key, String format) {
        ItemStack s = new ItemStack(RCItems.FILM_ROLL.get());
        s.set(RCComponents.FILM_STOCK.get(), key);
        s.set(RCComponents.FILM.get(), dev.hackdanel.realcamera.component.FilmRoll.fresh(format));
        return s;
    }

    public static ItemStack paper(String size, String finish, int count) {
        ItemStack s = new ItemStack(RCItems.PHOTO_PAPER.get(), count);
        s.set(RCComponents.PAPER.get(), new PaperData(size, finish));
        return s;
    }

    /**
     * How well a lens suits as the kit lens of a body: an everyday zoom made for the sensor size, with autofocus if the
     * body has it; no fisheye, tilt-shift or macro specialities.
     */
    static int kitScore(CameraBodySpec body, LensSpec lens) {
        int score = 0;
        boolean crop = body.sensor().cropFactor() > 1.2f;
        if (lens.imageCircle().equals(crop ? "apsc" : body.sensor().widthMm() > 40 ? "mf" : "ff")) score += 40;
        if (lens.optics().fisheye() || lens.optics().tiltShift().isPresent()) score -= 100;
        if (lens.optics().maxMagnification() >= 0.5f) score -= 20;
        if (!"none".equals(lens.af().motor()) == body.af().speed() > 0) score += 20;
        if (lens.isZoom()) score += 15;
        float equiv = lens.focalMin() * body.sensor().cropFactor();
        score -= (int) Math.abs(equiv - 28) / 4;
        return score;
    }

    /**
     * A ready-to-shoot camera: the body with the first matching lens, a charged battery and a
     * compatible card. Used by the creative tab and the dev autotest.
     */
    public static Optional<ItemStack> kit(HolderLookup.Provider registries, Holder.Reference<CameraBodySpec> body) {
        CameraBodySpec spec = body.value();
        var lenses = registries.lookupOrThrow(RCRegistries.LENS);
        var batteries = registries.lookupOrThrow(RCRegistries.BATTERY_TYPE);
        var cards = registries.lookupOrThrow(RCRegistries.MEMORY_CARD_TYPE);
        Optional<Holder.Reference<LensSpec>> lens = spec.fixedLens().isPresent() ? lenses.get(spec.fixedLens().get())
                : lenses.listElements().filter(l -> l.value().mount().equals(spec.mount()))
                .max(java.util.Comparator.comparingInt(l -> kitScore(spec, l.value())));
        Optional<Holder.Reference<BatteryTypeSpec>> battery = batteries.get(spec.batteryType());
        Optional<Holder.Reference<MemoryCardTypeSpec>> card = cards.listElements()
                .filter(c -> spec.cardForms().contains(c.value().form())).findFirst();
        ItemStack medium;
        if (spec.isFilm()) {
            var stock = registries.lookupOrThrow(RCRegistries.FILM_STOCK).listElements().findFirst();
            if (stock.isEmpty()) return Optional.empty();
            medium = film(stock.get().key(), spec.film().get().format());
        } else {
            if (card.isEmpty()) return Optional.empty();
            medium = card(card.get().key());
        }
        if (lens.isEmpty() || battery.isEmpty()) {
            return Optional.empty();
        }
        ItemStack camera = body(body.key());
        camera.set(RCComponents.CAMERA_PARTS.get(), CameraParts.EMPTY
                .with(CameraParts.SLOT_LENS, lens(lens.get().key()))
                .with(CameraParts.SLOT_BATTERY, battery(battery.get().key(), battery.get().value(), true))
                .with(CameraParts.SLOT_CARD, medium));
        return Optional.of(camera);
    }
}
