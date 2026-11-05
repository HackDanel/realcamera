package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RCSounds {
    public static final DeferredRegister<SoundEvent> REGISTER = DeferredRegister.create(Registries.SOUND_EVENT, RealCamera.MODID);

    public static final Supplier<SoundEvent> SHUTTER_MIRRORLESS = register("shutter.mirrorless");
    public static final Supplier<SoundEvent> AF_CONFIRM = register("af_confirm");

    private static Supplier<SoundEvent> register(String name) {
        return REGISTER.register(name, () -> SoundEvent.createVariableRangeEvent(RealCamera.id(name)));
    }

    private RCSounds() {}
}
