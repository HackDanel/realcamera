package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.item.BatteryItem;
import dev.hackdanel.realcamera.item.CameraBodyItem;
import dev.hackdanel.realcamera.item.InkCartridgeItem;
import dev.hackdanel.realcamera.item.LensItem;
import dev.hackdanel.realcamera.item.MemoryCardItem;
import dev.hackdanel.realcamera.item.PhotoFrameItem;
import dev.hackdanel.realcamera.item.PhotoPaperItem;
import dev.hackdanel.realcamera.item.PhotoPrintItem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class RCItems {
    public static final DeferredRegister.Items REGISTER = DeferredRegister.createItems(RealCamera.MODID);

    public static final DeferredItem<CameraBodyItem> CAMERA_BODY = REGISTER.register("camera_body",
            () -> new CameraBodyItem(new Item.Properties()));
    public static final DeferredItem<LensItem> LENS = REGISTER.register("lens", () -> new LensItem(new Item.Properties()));
    public static final DeferredItem<dev.hackdanel.realcamera.item.OpticalAccessoryItem> MOUNT_ADAPTER = REGISTER.register(
            "mount_adapter", () -> new dev.hackdanel.realcamera.item.OpticalAccessoryItem(new Item.Properties(), false));
    public static final DeferredItem<dev.hackdanel.realcamera.item.OpticalAccessoryItem> TELECONVERTER = REGISTER.register(
            "teleconverter", () -> new dev.hackdanel.realcamera.item.OpticalAccessoryItem(new Item.Properties(), true));
    public static final DeferredItem<dev.hackdanel.realcamera.item.AccessoryItem> FLASH = REGISTER.register("flash",
            () -> new dev.hackdanel.realcamera.item.AccessoryItem(new Item.Properties(), "flash_type"));
    public static final DeferredItem<dev.hackdanel.realcamera.item.AccessoryItem> FILTER = REGISTER.register("filter",
            () -> new dev.hackdanel.realcamera.item.AccessoryItem(new Item.Properties(), "filter_type"));
    public static final DeferredItem<dev.hackdanel.realcamera.item.AccessoryItem> BATTERY_GRIP = REGISTER.register("battery_grip",
            () -> new dev.hackdanel.realcamera.item.AccessoryItem(new Item.Properties(), "grip_type"));
    public static final DeferredItem<dev.hackdanel.realcamera.item.CleaningKitItem> CLEANING_KIT = REGISTER.register("cleaning_kit",
            () -> new dev.hackdanel.realcamera.item.CleaningKitItem(new Item.Properties().durability(16)));
    public static final DeferredItem<dev.hackdanel.realcamera.item.CameraBagItem> CAMERA_BAG = REGISTER.register("camera_bag",
            () -> new dev.hackdanel.realcamera.item.CameraBagItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<dev.hackdanel.realcamera.item.TripodItem> TRIPOD = REGISTER.register("tripod",
            () -> new dev.hackdanel.realcamera.item.TripodItem(new Item.Properties()));
    public static final DeferredItem<BatteryItem> BATTERY = REGISTER.register("battery",
            () -> new BatteryItem(new Item.Properties()));
    public static final DeferredItem<MemoryCardItem> MEMORY_CARD = REGISTER.register("memory_card",
            () -> new MemoryCardItem(new Item.Properties()));
    public static final DeferredItem<PhotoPrintItem> PHOTO_PRINT = REGISTER.register("photo_print",
            () -> new PhotoPrintItem(new Item.Properties()));
    public static final DeferredItem<PhotoPaperItem> PHOTO_PAPER = REGISTER.register("photo_paper",
            () -> new PhotoPaperItem(new Item.Properties()));
    public static final DeferredItem<PhotoFrameItem> PHOTO_FRAME = REGISTER.register("photo_frame",
            () -> new PhotoFrameItem(new Item.Properties()));

    public static final DeferredItem<InkCartridgeItem> INK_CYAN = REGISTER.register("ink_cartridge_cyan",
            () -> new InkCartridgeItem(new Item.Properties(), PrintData.INK_C, 0x00B7EB));
    public static final DeferredItem<InkCartridgeItem> INK_MAGENTA = REGISTER.register("ink_cartridge_magenta",
            () -> new InkCartridgeItem(new Item.Properties(), PrintData.INK_M, 0xFF00A0));
    public static final DeferredItem<InkCartridgeItem> INK_YELLOW = REGISTER.register("ink_cartridge_yellow",
            () -> new InkCartridgeItem(new Item.Properties(), PrintData.INK_Y, 0xFFE600));
    public static final DeferredItem<InkCartridgeItem> INK_BLACK = REGISTER.register("ink_cartridge_black",
            () -> new InkCartridgeItem(new Item.Properties(), PrintData.INK_K, 0x303030));

    /** Intermediate parts made from vanilla resources (plan §8.1). */
    public static final List<DeferredItem<Item>> MATERIALS = new ArrayList<>();
    public static final DeferredItem<Item> OPTICAL_GLASS = material("optical_glass");
    public static final DeferredItem<Item> LENS_ELEMENT = material("lens_element");
    public static final DeferredItem<Item> ED_LENS_ELEMENT = material("ed_lens_element");
    public static final DeferredItem<Item> SILICON_WAFER = material("silicon_wafer");
    public static final DeferredItem<Item> CMOS_SENSOR = material("cmos_sensor");
    public static final DeferredItem<Item> BSI_CMOS_SENSOR = material("bsi_cmos_sensor");
    public static final DeferredItem<Item> PROCESSOR = material("processor");
    public static final DeferredItem<Item> CIRCUIT_BOARD = material("circuit_board");
    public static final DeferredItem<Item> DISPLAY_PANEL = material("display_panel");
    public static final DeferredItem<Item> SHUTTER_UNIT = material("shutter_unit");
    public static final DeferredItem<Item> MAGNESIUM_ALLOY = material("magnesium_alloy");
    public static final DeferredItem<Item> PLASTIC = material("plastic");
    public static final DeferredItem<Item> APERTURE_BLADES = material("aperture_blades");
    public static final DeferredItem<Item> AF_MOTOR = material("af_motor");
    public static final DeferredItem<Item> STABILIZER = material("stabilizer");
    public static final DeferredItem<Item> BATTERY_CELL = material("battery_cell");
    public static final DeferredItem<Item> FLASH_MEMORY = material("flash_memory");

    public static final DeferredItem<BlockItem> ASSEMBLY_STATION = REGISTER.registerSimpleBlockItem(RCBlocks.ASSEMBLY_STATION);
    public static final DeferredItem<BlockItem> BATTERY_CHARGER = REGISTER.registerSimpleBlockItem(RCBlocks.BATTERY_CHARGER);
    public static final DeferredItem<BlockItem> PHOTO_PRINTER = REGISTER.registerSimpleBlockItem(RCBlocks.PHOTO_PRINTER);
    public static final DeferredItem<BlockItem> COMPUTER = REGISTER.registerSimpleBlockItem(RCBlocks.COMPUTER);
    public static final DeferredItem<BlockItem> TABLE_FRAME = REGISTER.registerSimpleBlockItem(RCBlocks.TABLE_FRAME);
    public static final DeferredItem<BlockItem> PHOTO_STAND = REGISTER.register("photo_stand",
            () -> new net.minecraft.world.item.DoubleHighBlockItem(RCBlocks.PHOTO_STAND.get(), new Item.Properties()));
    public static final DeferredItem<dev.hackdanel.realcamera.item.FilmRollItem> FILM_ROLL = REGISTER.register("film_roll",
            () -> new dev.hackdanel.realcamera.item.FilmRollItem(new Item.Properties()));
    public static final DeferredItem<Item> CHEMISTRY_C41 = REGISTER.registerSimpleItem("chemistry_c41",
            new Item.Properties().durability(8));
    public static final DeferredItem<Item> CHEMISTRY_E6 = REGISTER.registerSimpleItem("chemistry_e6",
            new Item.Properties().durability(8));
    public static final DeferredItem<Item> CHEMISTRY_BW = REGISTER.registerSimpleItem("chemistry_bw",
            new Item.Properties().durability(8));
    public static final DeferredItem<Item> CHANGING_BAG = REGISTER.registerSimpleItem("changing_bag",
            new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> FILM_SCANNER = REGISTER.registerSimpleItem("film_scanner",
            new Item.Properties().stacksTo(1));
    public static final DeferredItem<BlockItem> DEVELOPING_TANK = REGISTER.registerSimpleBlockItem(RCBlocks.DEVELOPING_TANK);
    public static final DeferredItem<BlockItem> ENLARGER = REGISTER.registerSimpleBlockItem(RCBlocks.ENLARGER);
    public static final DeferredItem<BlockItem> SAFELIGHT = REGISTER.registerSimpleBlockItem(RCBlocks.SAFELIGHT);
    public static final DeferredItem<Item> CFEXPRESS_READER = REGISTER.registerSimpleItem("cfexpress_reader",
            new Item.Properties().stacksTo(1));
    public static final DeferredItem<dev.hackdanel.realcamera.item.PhotoAlbumItem> PHOTO_ALBUM = REGISTER.register("photo_album",
            () -> new dev.hackdanel.realcamera.item.PhotoAlbumItem(new Item.Properties()));

    private static DeferredItem<Item> material(String name) {
        DeferredItem<Item> item = REGISTER.registerSimpleItem(name);
        MATERIALS.add(item);
        return item;
    }

    private RCItems() {}
}
