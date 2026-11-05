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

    public static ItemStack paper(String size, String finish, int count) {
        ItemStack s = new ItemStack(RCItems.PHOTO_PAPER.get(), count);
        s.set(RCComponents.PAPER.get(), new PaperData(size, finish));
        return s;
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
        Optional<Holder.Reference<LensSpec>> lens = lenses.listElements()
                .filter(l -> l.value().mount().equals(spec.mount())).findFirst();
        Optional<Holder.Reference<BatteryTypeSpec>> battery = batteries.get(spec.batteryType());
        Optional<Holder.Reference<MemoryCardTypeSpec>> card = cards.listElements()
                .filter(c -> spec.cardForms().contains(c.value().form())).findFirst();
        if (lens.isEmpty() || battery.isEmpty() || card.isEmpty()) {
            return Optional.empty();
        }
        ItemStack camera = body(body.key());
        camera.set(RCComponents.CAMERA_PARTS.get(), new CameraParts(lens(lens.get().key()),
                battery(battery.get().key(), battery.get().value(), true), card(card.get().key())));
        return Optional.of(camera);
    }
}
