package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.component.FrameData;
import dev.hackdanel.realcamera.component.PaperData;
import dev.hackdanel.realcamera.item.RCStacks;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RCCreativeTab {
    public static final DeferredRegister<CreativeModeTab> REGISTER = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RealCamera.MODID);

    public static final Supplier<CreativeModeTab> TAB = REGISTER.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.realcamera"))
            .icon(() -> new ItemStack(RCItems.CAMERA_BODY.get()))
            .displayItems((params, out) -> {
                HolderLookup.Provider r = params.holders();
                r.lookup(RCRegistries.CAMERA_BODY).ifPresent(bodies -> bodies.listElements().forEach(b -> {
                    RCStacks.kit(r, b).ifPresent(out::accept);
                    out.accept(RCStacks.body(b.key()));
                }));
                r.lookup(RCRegistries.LENS).ifPresent(l -> l.listElements().forEach(h -> out.accept(RCStacks.lens(h.key()))));
                r.lookup(RCRegistries.MOUNT_ADAPTER).ifPresent(l -> l.listElements().forEach(h -> out.accept(RCStacks.adapter(h.key()))));
                r.lookup(RCRegistries.TELECONVERTER).ifPresent(l -> l.listElements().forEach(h -> out.accept(RCStacks.teleconverter(h.key()))));
                r.lookup(RCRegistries.FLASH_TYPE).ifPresent(l -> l.listElements()
                        .forEach(h -> out.accept(RCStacks.flash(h.key(), h.value().capacity()))));
                r.lookup(RCRegistries.FILTER_TYPE).ifPresent(l -> l.listElements().forEach(h -> out.accept(RCStacks.filter(h.key()))));
                r.lookup(RCRegistries.GRIP_TYPE).ifPresent(l -> l.listElements().forEach(h -> {
                    int cap = r.lookup(RCRegistries.BATTERY_TYPE).flatMap(b -> b.get(h.value().batteryType()))
                            .map(b -> b.value().capacity()).orElse(500);
                    out.accept(RCStacks.grip(h.key(), cap));
                }));
                out.accept(RCItems.TRIPOD.get());
                out.accept(RCItems.CAMERA_BAG.get());
                out.accept(RCItems.CLEANING_KIT.get());
                r.lookup(RCRegistries.BATTERY_TYPE).ifPresent(l -> l.listElements()
                        .forEach(h -> out.accept(RCStacks.battery(h.key(), h.value(), true))));
                r.lookup(RCRegistries.MEMORY_CARD_TYPE).ifPresent(l -> l.listElements()
                        .forEach(h -> out.accept(RCStacks.card(h.key()))));
                out.accept(RCItems.ASSEMBLY_STATION.get());
                out.accept(RCItems.BATTERY_CHARGER.get());
                out.accept(RCItems.PHOTO_PRINTER.get());
                out.accept(RCItems.INK_CYAN.get());
                out.accept(RCItems.INK_MAGENTA.get());
                out.accept(RCItems.INK_YELLOW.get());
                out.accept(RCItems.INK_BLACK.get());
                for (String size : PaperData.SIZES) {
                    for (String finish : PaperData.FINISHES) {
                        out.accept(RCStacks.paper(size, finish, 1));
                    }
                }
                for (String material : FrameData.MATERIALS) {
                    for (int[] size : FrameData.SIZES) {
                        ItemStack frame = new ItemStack(RCItems.PHOTO_FRAME.get());
                        frame.set(RCComponents.FRAME.get(), new FrameData(size[0], size[1], material));
                        out.accept(frame);
                    }
                }
                RCItems.MATERIALS.forEach(m -> out.accept(m.get()));
            })
            .build());

    private RCCreativeTab() {}
}
