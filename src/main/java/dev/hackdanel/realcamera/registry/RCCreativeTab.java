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
                    var kit = RCStacks.kit(r, b);
                    kit.ifPresent(out::accept);
                    // a body with a built-in lens is never a bare body: only its kit is listed
                    if (kit.isEmpty() || !b.value().hasFixedLens()) out.accept(RCStacks.body(b.key()));
                }));
                r.lookup(RCRegistries.LENS).ifPresent(l -> l.listElements()
                        .filter(h -> !h.value().mount().location().getPath().equals("fixed"))
                        .forEach(h -> out.accept(RCStacks.lens(h.key()))));
                r.lookup(RCRegistries.MOUNT_ADAPTER).ifPresent(l -> l.listElements().forEach(h -> out.accept(RCStacks.adapter(h.key()))));
                r.lookup(RCRegistries.TELECONVERTER).ifPresent(l -> l.listElements().forEach(h -> out.accept(RCStacks.teleconverter(h.key()))));
                r.lookup(RCRegistries.FLASH_TYPE).ifPresent(l -> l.listElements()
                        .forEach(h -> out.accept(RCStacks.flash(h.key(), h.value().capacity()))));
                r.lookup(RCRegistries.FILTER_TYPE).ifPresent(l -> l.listElements().forEach(h -> out.accept(RCStacks.filter(h.key()))));
                r.lookup(RCRegistries.STEP_UP_RING).ifPresent(l -> l.listElements().forEach(h -> out.accept(RCStacks.stepUpRing(h.key()))));
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
                        .filter(h -> !h.value().form().startsWith("internal")) // comes built into its body
                        .forEach(h -> out.accept(RCStacks.card(h.key()))));
                out.accept(RCItems.ASSEMBLY_STATION.get());
                out.accept(RCItems.BATTERY_CHARGER.get());
                out.accept(RCItems.PHOTO_PRINTER.get());
                out.accept(RCItems.COMPUTER.get());
                out.accept(RCItems.CFEXPRESS_READER.get());
                out.accept(RCItems.PHOTO_ALBUM.get());
                out.accept(RCItems.TABLE_FRAME.get());
                out.accept(RCItems.PHOTO_STAND.get());
                r.lookup(RCRegistries.FILM_STOCK).ifPresent(l -> l.listElements().forEach(h -> {
                    out.accept(RCStacks.film(h.key(), "135"));
                    out.accept(RCStacks.film(h.key(), "120"));
                }));
                out.accept(RCItems.DEVELOPING_TANK.get());
                out.accept(RCItems.CHEMISTRY_C41.get());
                out.accept(RCItems.CHEMISTRY_E6.get());
                out.accept(RCItems.CHEMISTRY_BW.get());
                out.accept(RCItems.CHANGING_BAG.get());
                out.accept(RCItems.FIELD_JOURNAL.get());
                out.accept(RCItems.EDITORIAL_DESK.get());
                out.accept(RCItems.ENLARGER.get());
                out.accept(RCItems.SAFELIGHT.get());
                out.accept(RCItems.FILM_SCANNER.get());
                for (String size : PaperData.SIZES) {
                    for (String finish : dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity.DARKROOM_PAPERS) {
                        out.accept(RCStacks.paper(size, finish, 1));
                    }
                }
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
