package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.entity.PhotoFrameEntity;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RCEntities {
    public static final DeferredRegister<EntityType<?>> REGISTER = DeferredRegister.create(Registries.ENTITY_TYPE, RealCamera.MODID);

    public static final Supplier<EntityType<PhotoFrameEntity>> PHOTO_FRAME = REGISTER.register("photo_frame",
            () -> EntityType.Builder.<PhotoFrameEntity>of(PhotoFrameEntity::new, MobCategory.MISC)
                    .sized(0.5f, 0.5f).clientTrackingRange(10).updateInterval(Integer.MAX_VALUE)
                    .build("photo_frame"));

    private RCEntities() {}
}
