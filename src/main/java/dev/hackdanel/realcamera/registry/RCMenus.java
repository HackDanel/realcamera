package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.menu.AssemblyStationMenu;
import dev.hackdanel.realcamera.menu.CameraPartsMenu;
import dev.hackdanel.realcamera.menu.ChargerMenu;
import dev.hackdanel.realcamera.menu.PrinterMenu;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RCMenus {
    public static final DeferredRegister<MenuType<?>> REGISTER = DeferredRegister.create(Registries.MENU, RealCamera.MODID);

    public static final Supplier<MenuType<CameraPartsMenu>> CAMERA_PARTS = REGISTER.register("camera_parts",
            () -> IMenuTypeExtension.create(CameraPartsMenu::new));
    public static final Supplier<MenuType<AssemblyStationMenu>> ASSEMBLY_STATION = REGISTER.register("assembly_station",
            () -> IMenuTypeExtension.create(AssemblyStationMenu::new));
    public static final Supplier<MenuType<ChargerMenu>> CHARGER = REGISTER.register("battery_charger",
            () -> IMenuTypeExtension.create(ChargerMenu::new));
    public static final Supplier<MenuType<dev.hackdanel.realcamera.menu.CameraBagMenu>> CAMERA_BAG = REGISTER.register("camera_bag",
            () -> IMenuTypeExtension.create(dev.hackdanel.realcamera.menu.CameraBagMenu::new));
    public static final Supplier<MenuType<PrinterMenu>> PRINTER = REGISTER.register("photo_printer",
            () -> IMenuTypeExtension.create(PrinterMenu::new));

    public static final Supplier<MenuType<dev.hackdanel.realcamera.menu.ComputerMenu>> COMPUTER = REGISTER.register("computer",
            () -> IMenuTypeExtension.create(dev.hackdanel.realcamera.menu.ComputerMenu::new));

    private RCMenus() {}
}
