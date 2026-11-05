package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.block.AssemblyStationBlock;
import dev.hackdanel.realcamera.block.ChargerBlock;
import dev.hackdanel.realcamera.block.PrinterBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RCBlocks {
    public static final DeferredRegister.Blocks REGISTER = DeferredRegister.createBlocks(RealCamera.MODID);

    public static final DeferredBlock<AssemblyStationBlock> ASSEMBLY_STATION = REGISTER.register("assembly_station",
            () -> new AssemblyStationBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD)
                    .strength(2.5f).sound(SoundType.WOOD)));
    public static final DeferredBlock<ChargerBlock> BATTERY_CHARGER = REGISTER.register("battery_charger",
            () -> new ChargerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK)
                    .strength(1.5f).sound(SoundType.METAL).noOcclusion()
                    .lightLevel(s -> s.getValue(ChargerBlock.CHARGING) ? 3 : 0)));
    public static final DeferredBlock<PrinterBlock> PHOTO_PRINTER = REGISTER.register("photo_printer",
            () -> new PrinterBlock(BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ)
                    .strength(2f).sound(SoundType.METAL).noOcclusion()));

    public static final DeferredBlock<dev.hackdanel.realcamera.block.ComputerBlock> COMPUTER = REGISTER.register("computer",
            () -> new dev.hackdanel.realcamera.block.ComputerBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY)
                    .strength(2f).sound(SoundType.METAL).noOcclusion().lightLevel(s -> 4)));
    public static final DeferredBlock<dev.hackdanel.realcamera.block.TableFrameBlock> TABLE_FRAME = REGISTER.register("table_frame",
            () -> new dev.hackdanel.realcamera.block.TableFrameBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD)
                    .strength(0.5f).sound(SoundType.WOOD).noOcclusion()));
    public static final DeferredBlock<dev.hackdanel.realcamera.block.PhotoStandBlock> PHOTO_STAND = REGISTER.register("photo_stand",
            () -> new dev.hackdanel.realcamera.block.PhotoStandBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD)
                    .strength(1f).sound(SoundType.WOOD).noOcclusion()));

    public static final DeferredBlock<dev.hackdanel.realcamera.block.DevelopingTankBlock> DEVELOPING_TANK = REGISTER.register(
            "developing_tank", () -> new dev.hackdanel.realcamera.block.DevelopingTankBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK).strength(1f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredBlock<dev.hackdanel.realcamera.block.EnlargerBlock> ENLARGER = REGISTER.register(
            "enlarger", () -> new dev.hackdanel.realcamera.block.EnlargerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY).strength(2f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredBlock<dev.hackdanel.realcamera.block.SafelightBlock> SAFELIGHT = REGISTER.register(
            "safelight", () -> new dev.hackdanel.realcamera.block.SafelightBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED).strength(0.5f).sound(SoundType.GLASS).noOcclusion().lightLevel(s -> 7)));

    public static final DeferredBlock<dev.hackdanel.realcamera.block.EditorialDeskBlock> EDITORIAL_DESK = REGISTER.register(
            "editorial_desk", () -> new dev.hackdanel.realcamera.block.EditorialDeskBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD).strength(2f).sound(SoundType.WOOD).noOcclusion()));

    private RCBlocks() {}
}
