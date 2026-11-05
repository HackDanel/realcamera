package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity;
import dev.hackdanel.realcamera.blockentity.ChargerBlockEntity;
import dev.hackdanel.realcamera.blockentity.PrinterBlockEntity;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RCBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> REGISTER =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RealCamera.MODID);

    public static final Supplier<BlockEntityType<AssemblyStationBlockEntity>> ASSEMBLY_STATION = REGISTER.register(
            "assembly_station", () -> BlockEntityType.Builder.of(AssemblyStationBlockEntity::new,
                    RCBlocks.ASSEMBLY_STATION.get()).build(null));
    public static final Supplier<BlockEntityType<ChargerBlockEntity>> CHARGER = REGISTER.register(
            "battery_charger", () -> BlockEntityType.Builder.of(ChargerBlockEntity::new,
                    RCBlocks.BATTERY_CHARGER.get()).build(null));
    public static final Supplier<BlockEntityType<PrinterBlockEntity>> PRINTER = REGISTER.register(
            "photo_printer", () -> BlockEntityType.Builder.of(PrinterBlockEntity::new,
                    RCBlocks.PHOTO_PRINTER.get()).build(null));

    public static final Supplier<BlockEntityType<dev.hackdanel.realcamera.blockentity.ComputerBlockEntity>> COMPUTER = REGISTER.register(
            "computer", () -> BlockEntityType.Builder.of(dev.hackdanel.realcamera.blockentity.ComputerBlockEntity::new,
                    RCBlocks.COMPUTER.get()).build(null));
    public static final Supplier<BlockEntityType<dev.hackdanel.realcamera.blockentity.DisplayBlockEntity>> DISPLAY = REGISTER.register(
            "photo_display", () -> BlockEntityType.Builder.of(dev.hackdanel.realcamera.blockentity.DisplayBlockEntity::new,
                    RCBlocks.TABLE_FRAME.get(), RCBlocks.PHOTO_STAND.get()).build(null));

    public static final Supplier<BlockEntityType<dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity>> DEVELOPING_TANK =
            REGISTER.register("developing_tank", () -> BlockEntityType.Builder.of(
                    dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity::new, RCBlocks.DEVELOPING_TANK.get()).build(null));
    public static final Supplier<BlockEntityType<dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity>> ENLARGER =
            REGISTER.register("enlarger", () -> BlockEntityType.Builder.of(
                    dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity::new, RCBlocks.ENLARGER.get()).build(null));

    private RCBlockEntities() {}
}
