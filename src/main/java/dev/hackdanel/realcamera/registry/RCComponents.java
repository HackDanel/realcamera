package dev.hackdanel.realcamera.registry;

import com.mojang.serialization.Codec;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.component.FrameData;
import dev.hackdanel.realcamera.component.PaperData;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.spec.AdapterSpec;
import dev.hackdanel.realcamera.spec.BatteryTypeSpec;
import dev.hackdanel.realcamera.spec.TeleconverterSpec;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import dev.hackdanel.realcamera.spec.MemoryCardTypeSpec;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RCComponents {
    public static final DeferredRegister.DataComponents REGISTER =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, RealCamera.MODID);

    public static final Supplier<DataComponentType<ResourceKey<CameraBodySpec>>> BODY = REGISTER.registerComponentType(
            "body", b -> b.persistent(ResourceKey.codec(RCRegistries.CAMERA_BODY))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.CAMERA_BODY)));
    public static final Supplier<DataComponentType<ResourceKey<LensSpec>>> LENS = REGISTER.registerComponentType(
            "lens", b -> b.persistent(ResourceKey.codec(RCRegistries.LENS))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.LENS)));
    public static final Supplier<DataComponentType<ResourceKey<BatteryTypeSpec>>> BATTERY_TYPE = REGISTER.registerComponentType(
            "battery_type", b -> b.persistent(ResourceKey.codec(RCRegistries.BATTERY_TYPE))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.BATTERY_TYPE)));
    public static final Supplier<DataComponentType<ResourceKey<MemoryCardTypeSpec>>> CARD_TYPE = REGISTER.registerComponentType(
            "card_type", b -> b.persistent(ResourceKey.codec(RCRegistries.MEMORY_CARD_TYPE))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.MEMORY_CARD_TYPE)));

    public static final Supplier<DataComponentType<ResourceKey<AdapterSpec>>> ADAPTER = REGISTER.registerComponentType(
            "adapter", b -> b.persistent(ResourceKey.codec(RCRegistries.MOUNT_ADAPTER))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.MOUNT_ADAPTER)));
    public static final Supplier<DataComponentType<ResourceKey<TeleconverterSpec>>> TELECONVERTER = REGISTER.registerComponentType(
            "teleconverter", b -> b.persistent(ResourceKey.codec(RCRegistries.TELECONVERTER))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.TELECONVERTER)));

    public static final Supplier<DataComponentType<ResourceKey<dev.hackdanel.realcamera.spec.FlashSpec>>> FLASH_TYPE = REGISTER.registerComponentType(
            "flash_type", b -> b.persistent(ResourceKey.codec(RCRegistries.FLASH_TYPE))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.FLASH_TYPE)));
    public static final Supplier<DataComponentType<ResourceKey<dev.hackdanel.realcamera.spec.FilterSpec>>> FILTER_TYPE = REGISTER.registerComponentType(
            "filter_type", b -> b.persistent(ResourceKey.codec(RCRegistries.FILTER_TYPE))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.FILTER_TYPE)));
    /** Which step-up ring a ring item is; on a filter, the ring screwed onto it. */
    public static final Supplier<DataComponentType<ResourceKey<dev.hackdanel.realcamera.spec.StepUpRingSpec>>> STEP_UP_RING = REGISTER.registerComponentType(
            "step_up_ring", b -> b.persistent(ResourceKey.codec(RCRegistries.STEP_UP_RING))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.STEP_UP_RING)));
    public static final Supplier<DataComponentType<ResourceKey<dev.hackdanel.realcamera.spec.GripSpec>>> GRIP_TYPE = REGISTER.registerComponentType(
            "grip_type", b -> b.persistent(ResourceKey.codec(RCRegistries.GRIP_TYPE))
                    .networkSynchronized(ResourceKey.streamCodec(RCRegistries.GRIP_TYPE)));
    public static final Supplier<DataComponentType<dev.hackdanel.realcamera.component.CameraWear>> WEAR = REGISTER.registerComponentType(
            "wear", b -> b.persistent(dev.hackdanel.realcamera.component.CameraWear.CODEC)
                    .networkSynchronized(dev.hackdanel.realcamera.component.CameraWear.STREAM_CODEC));
    public static final Supplier<DataComponentType<dev.hackdanel.realcamera.component.Commission>> COMMISSION = REGISTER.registerComponentType(
            "commission", b -> b.persistent(dev.hackdanel.realcamera.component.Commission.CODEC)
                    .networkSynchronized(dev.hackdanel.realcamera.component.Commission.STREAM_CODEC));
    /** Lens decentring after a fall (0 = fine .. 1 = badly knocked), cracked filter glass, etc. */
    public static final Supplier<DataComponentType<Float>> DAMAGE = REGISTER.registerComponentType(
            "damage", b -> b.persistent(Codec.FLOAT).networkSynchronized(ByteBufCodecs.FLOAT));

    public static final Supplier<DataComponentType<CameraParts>> CAMERA_PARTS = REGISTER.registerComponentType(
            "camera_parts", b -> b.persistent(CameraParts.CODEC).networkSynchronized(CameraParts.STREAM_CODEC).cacheEncoding());
    public static final Supplier<DataComponentType<CameraSettings>> CAMERA_SETTINGS = REGISTER.registerComponentType(
            "camera_settings", b -> b.persistent(CameraSettings.CODEC).networkSynchronized(CameraSettings.STREAM_CODEC));
    public static final Supplier<DataComponentType<Integer>> SHUTTER_COUNT = REGISTER.registerComponentType(
            "shutter_count", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final Supplier<DataComponentType<BatteryCharge>> CHARGE = REGISTER.registerComponentType(
            "charge", b -> b.persistent(BatteryCharge.CODEC).networkSynchronized(BatteryCharge.STREAM_CODEC));
    public static final Supplier<DataComponentType<CardData>> CARD = REGISTER.registerComponentType(
            "card", b -> b.persistent(CardData.CODEC).networkSynchronized(CardData.STREAM_CODEC));
    public static final Supplier<DataComponentType<PrintData>> PRINT = REGISTER.registerComponentType(
            "print", b -> b.persistent(PrintData.CODEC).networkSynchronized(PrintData.STREAM_CODEC).cacheEncoding());
    public static final Supplier<DataComponentType<PaperData>> PAPER = REGISTER.registerComponentType(
            "paper", b -> b.persistent(PaperData.CODEC).networkSynchronized(PaperData.STREAM_CODEC));
    public static final Supplier<DataComponentType<Integer>> INK = REGISTER.registerComponentType(
            "ink", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final Supplier<DataComponentType<FrameData>> FRAME = REGISTER.registerComponentType(
            "frame", b -> b.persistent(FrameData.CODEC).networkSynchronized(FrameData.STREAM_CODEC));

    public static final Supplier<DataComponentType<dev.hackdanel.realcamera.component.AlbumData>> ALBUM = REGISTER.registerComponentType(
            "album", b -> b.persistent(dev.hackdanel.realcamera.component.AlbumData.CODEC)
                    .networkSynchronized(dev.hackdanel.realcamera.component.AlbumData.STREAM_CODEC).cacheEncoding());

    public static final Supplier<DataComponentType<net.minecraft.resources.ResourceKey<dev.hackdanel.realcamera.spec.FilmStockSpec>>> FILM_STOCK =
            REGISTER.registerComponentType("film_stock", b -> b
                    .persistent(net.minecraft.resources.ResourceKey.codec(RCRegistries.FILM_STOCK))
                    .networkSynchronized(net.minecraft.resources.ResourceKey.streamCodec(RCRegistries.FILM_STOCK)));
    public static final Supplier<DataComponentType<dev.hackdanel.realcamera.component.FilmRoll>> FILM = REGISTER.registerComponentType(
            "film", b -> b.persistent(dev.hackdanel.realcamera.component.FilmRoll.CODEC)
                    .networkSynchronized(dev.hackdanel.realcamera.component.FilmRoll.STREAM_CODEC));

    private RCComponents() {}
}
