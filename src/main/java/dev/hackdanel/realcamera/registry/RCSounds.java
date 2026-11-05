package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RCSounds {
    public static final DeferredRegister<SoundEvent> REGISTER = DeferredRegister.create(Registries.SOUND_EVENT, RealCamera.MODID);

    public static final Supplier<SoundEvent> SHUTTER_MIRRORLESS = register("shutter.mirrorless");
    public static final Supplier<SoundEvent> SHUTTER_DSLR = register("shutter.dslr");
    public static final Supplier<SoundEvent> SHUTTER_LEAF = register("shutter.leaf");
    public static final Supplier<SoundEvent> AF_CONFIRM = register("af_confirm");

    /** Shutter sound set named in the body spec ("mirrorless", "dslr", "leaf"). */
    public static SoundEvent shutter(String set) {
        return switch (set) {
            case "dslr" -> SHUTTER_DSLR.get();
            case "leaf" -> SHUTTER_LEAF.get();
            default -> SHUTTER_MIRRORLESS.get();
        };
    }

    private static Supplier<SoundEvent> register(String name) {
        return REGISTER.register(name, () -> SoundEvent.createVariableRangeEvent(RealCamera.id(name)));
    }

    private RCSounds() {}
}
