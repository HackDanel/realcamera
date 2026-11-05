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

    private RCBlocks() {}
}
