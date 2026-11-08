package dev.hackdanel.realcamera.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.blockentity.PrinterBlockEntity;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.component.FrameData;
import dev.hackdanel.realcamera.entity.PhotoFrameEntity;
import dev.hackdanel.realcamera.item.RCStacks;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.registry.RCBlocks;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.registry.RCRegistries;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Unattended end-to-end check of stage 1, enabled with -Drealcamera.autotest=stage1
 * (./gradlew runClientAutotest). Builds a test scene, shoots with an 85 mm f/1.8 focused on a
 * subject 3 m away, prints the photo on a real printer block, hangs it in a frame and saves
 * screenshots to <run dir>/screenshots/autotest/. Logs "[autotest] PASS/FAIL ..." lines.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Stage1Autotest {
    private static final boolean ENABLED = "stage1".equals(System.getProperty("realcamera.autotest"));
    private static boolean worldRequested;
    private static int step, wait;
    private static String pendingScreenshot;
    private static BlockPos origin;
    private static BlockPos printerPos;
    private static boolean paperRefused;

    private Stage1Autotest() {}

    private static void log(String msg, Object... args) {
        RealCamera.LOGGER.info("[autotest] " + msg, args);
    }

    private static void server(Consumer<ServerPlayer> action) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().get(0)));
    }

    private static void cmd(ServerPlayer p, String command) {
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (!worldRequested && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            worldRequested = true;
            String name = "rc_stage1_" + System.currentTimeMillis();
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            mc.createWorldOpenFlows().createFreshLevel(name, new LevelSettings(name, GameType.CREATIVE, false,
                            Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(20261105L, false, false), WorldPresets::createNormalWorldDimensions, new TitleScreen());
            return;
        }
        // the test opens its own screens from step 18 on; before that any screen means "still loading"
        if (mc.level == null || mc.player == null || (mc.screen != null && step < 18)) return;
        if (wait > 0) {
            wait--;
            return;
        }
        switch (step++) {
            case 0 -> wait = 200; // let chunks load
            case 1 -> server(p -> {
                origin = p.blockPosition();
                cmd(p, "time set 6000");
                cmd(p, "weather clear");
                // test scene, player looks south (+z)
                cmd(p, "fill ~-6 ~-1 ~-2 ~6 ~-1 ~34 minecraft:grass_block");
                cmd(p, "fill ~-6 ~ ~-2 ~6 ~8 ~34 minecraft:air");
                cmd(p, "fill ~ ~ ~6 ~ ~1 ~6 minecraft:red_wool");
                cmd(p, "fill ~2 ~ ~14 ~2 ~3 ~14 minecraft:yellow_wool");
                cmd(p, "fill ~-3 ~ ~30 ~-3 ~5 ~30 minecraft:blue_wool");
                cmd(p, "fill ~4 ~ ~22 ~5 ~2 ~23 minecraft:oak_leaves");
                cmd(p, "tp @s ~ ~ ~ 0 8");
                // ready-to-shoot camera with the 85 mm f/1.8 instead of the kit zoom
                var regs = p.server.registryAccess();
                var body = regs.lookupOrThrow(RCRegistries.CAMERA_BODY).getOrThrow(
                        ResourceKey.create(RCRegistries.CAMERA_BODY, RealCamera.id("sony_a7iv")));
                ItemStack cam = RCStacks.kit(regs, body).orElseThrow();
                CameraParts parts = CameraHelper.parts(cam);
                CameraHelper.setParts(cam, parts.with(CameraParts.SLOT_LENS,
                        RCStacks.lens(ResourceKey.create(RCRegistries.LENS, RealCamera.id("sony_fe_85_f18")))));
                p.getInventory().clearContent();
                p.getInventory().setItem(0, cam);
                p.getInventory().selected = 0;
                log("scene built at {}", origin);
            });
            case 2 -> wait = 40;
            case 3 -> {
                mc.options.keyUse.setDown(true); // "hold right click"
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                wait = 20;
            }
            case 4 -> {
                if (!Viewfinder.isActive()) {
                    log("FAIL viewfinder did not activate");
                    finish(mc);
                    return;
                }
                CameraSettings s = Viewfinder.settings().withMode(CameraSettings.MODE_A)
                        .withAperture(ExposureScales.nearestAperture(1.8)).withFocusMode(CameraSettings.FOCUS_AFS);
                Viewfinder.update(s);
                wait = 30;
            }
            case 5 -> {
                pendingScreenshot = "1_viewfinder";
                wait = 5;
            }
            case 6 -> {
                Viewfinder.debugPressShutter();
                wait = 10;
            }
            case 7 -> {
                if (PhotoCapture.busy()) {
                    step--; // wait for develop + upload
                    wait = 5;
                    return;
                }
                log("PASS photo developed and stored; exposure delta {}", Viewfinder.exposure() == null ? "?" :
                        String.format("%.2f", Viewfinder.exposure().delta()));
                mc.options.keyUse.setDown(false);
                wait = 10;
            }
            case 8 -> server(p -> {
                // print it on a real printer block
                printerPos = origin.offset(2, 0, 1);
                p.serverLevel().setBlockAndUpdate(printerPos, RCBlocks.PHOTO_PRINTER.get().defaultBlockState()
                        .setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, Direction.NORTH));
                PrinterBlockEntity printer = (PrinterBlockEntity) p.serverLevel().getBlockEntity(printerPos);
                ItemStack cam = p.getMainHandItem();
                printer.items().setStackInSlot(PrinterBlockEntity.SLOT_CARD, CameraHelper.parts(cam).card().copy());
                printer.items().setStackInSlot(PrinterBlockEntity.SLOT_PAPER, RCStacks.paper("a4", "glossy", 4));
                // no cartridges yet: the printer must refuse (issue #32)
                printer.handleButton(PrinterBlockEntity.BUTTON_PRINT);
                log(printer.printing() ? "FAIL printer started without ink" : "PASS printer refuses to print without ink");
                printer.items().setStackInSlot(2, new ItemStack(RCItems.INK_CYAN.get()));
                printer.items().setStackInSlot(3, new ItemStack(RCItems.INK_MAGENTA.get()));
                printer.items().setStackInSlot(4, new ItemStack(RCItems.INK_YELLOW.get()));
                printer.items().setStackInSlot(5, new ItemStack(RCItems.INK_BLACK.get()));
                printer.handleButton(PrinterBlockEntity.BUTTON_PRINT);
                log("printing started, card id {}", printer.cardId());
            });
            case 9 -> wait = PrinterBlockEntity.PRINT_TICKS + 20;
            case 10 -> server(p -> {
                PrinterBlockEntity printer = (PrinterBlockEntity) p.serverLevel().getBlockEntity(printerPos);
                ItemStack print = printer.items().getStackInSlot(PrinterBlockEntity.SLOT_OUTPUT).copy();
                if (print.isEmpty()) {
                    log("FAIL printer produced nothing");
                    return;
                }
                log("PASS printed: {}", print.getHoverName().getString());
                printer.items().setStackInSlot(PrinterBlockEntity.SLOT_OUTPUT, ItemStack.EMPTY);
                ItemStack camera = p.getInventory().getItem(0).copy();
                // wall and a 2x2 frame facing the player
                cmd(p, "fill ~-5 ~ ~9 ~6 ~3 ~9 minecraft:stone_bricks");
                PhotoFrameEntity frame = new PhotoFrameEntity(p.serverLevel(), origin.offset(0, 1, 8), Direction.NORTH,
                        new FrameData(2, 2, "dark_oak"));
                frame.interact(fakeHolding(p, print), InteractionHand.MAIN_HAND);
                if (frame.survives()) {
                    p.serverLevel().addFreshEntity(frame);
                    log("PASS frame placed with print: {}", !frame.print().isEmpty());
                } else {
                    log("FAIL frame does not survive at {}", origin.offset(0, 1, 8));
                }
                ItemStack small = print.copy();
                PhotoFrameEntity frame2 = new PhotoFrameEntity(p.serverLevel(), origin.offset(-2, 2, 8), Direction.NORTH,
                        new FrameData(1, 1, "gold"));
                frame2.interact(fakeHolding(p, small), InteractionHand.MAIN_HAND);
                if (frame2.survives()) p.serverLevel().addFreshEntity(frame2);
                // paper size limit (#13): an A3 print fills a 4x3 frame
                PhotoFrameEntity big = new PhotoFrameEntity(p.serverLevel(), origin.offset(4, 1, 8), Direction.NORTH,
                        new FrameData(4, 3, "black_metal"));
                big.interact(fakeHolding(p, onPaper(print, "a3")), InteractionHand.MAIN_HAND);
                if (big.survives()) p.serverLevel().addFreshEntity(big);
                log(!big.print().isEmpty() ? "PASS A3 print hangs in a 4x3 frame" : "FAIL A3 print refused by a 4x3 frame");
                PhotoFrameEntity small2 = new PhotoFrameEntity(p.serverLevel(), origin.offset(-3, 1, 8), Direction.NORTH,
                        new FrameData(2, 2, "oak"));
                if (small2.survives()) p.serverLevel().addFreshEntity(small2);
                p.getInventory().setItem(0, camera);
                p.getInventory().setItem(1, print.copy());
            });
            case 11 -> {
                mc.player.getInventory().selected = 1; // the selected slot is client-authoritative
                wait = 80; // download + texture
            }
            case 12 -> {
                if (!paperRefused) {
                    // a 10x15 print into the empty 2x2 frame: refused with a message on the action bar (#13)
                    paperRefused = true;
                    server(p -> p.serverLevel().getEntitiesOfClass(PhotoFrameEntity.class,
                            new net.minecraft.world.phys.AABB(origin.offset(-3, 1, 8)).inflate(0.5),
                            f -> f.getPos().equals(origin.offset(-3, 1, 8))).forEach(f -> {
                        ItemStack before = p.getMainHandItem().copy();
                        f.interact(fakeHolding(p, onPaper(p.getInventory().getItem(1), "10x15")), InteractionHand.MAIN_HAND);
                        boolean kept = p.getMainHandItem().getCount() == 1;
                        log(f.print().isEmpty() && kept ? "PASS 10x15 print refused by a 2x2 frame"
                                : "FAIL 10x15 print in a 2x2 frame: hung={} kept={}", !f.print().isEmpty(), kept);
                        p.setItemInHand(InteractionHand.MAIN_HAND, before);
                    }));
                    step--;
                    wait = 10;
                    return;
                }
                pendingScreenshot = "2_frame_and_print_in_hand";
                wait = 5;
            }
            case 13 -> {
                mc.player.getInventory().selected = 0;
                wait = 10;
            }
            case 14 -> {
                pendingScreenshot = "3_camera_in_hand";
                wait = 5;
            }
            case 15 -> {
                mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
                mc.options.keyUse.setDown(true);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                wait = 20;
            }
            case 16 -> {
                pendingScreenshot = "4_third_person_pose";
                wait = 5;
            }
            case 17 -> {
                mc.options.keyUse.setDown(false);
                mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
                checkExport(mc);
                wait = 20;
            }
            case 18 -> {
                mc.setScreen(new dev.hackdanel.realcamera.client.screen.PlaybackScreen(dev.hackdanel.realcamera.camera.CameraRef.hand(InteractionHand.MAIN_HAND)));
                wait = 40;
            }
            case 19 -> {
                pendingScreenshot = "5_playback";
                wait = 5;
            }
            case 20 -> {
                mc.setScreen(new dev.hackdanel.realcamera.client.screen.CameraMenuScreen(dev.hackdanel.realcamera.camera.CameraRef.hand(InteractionHand.MAIN_HAND)));
                wait = 10;
            }
            case 21 -> {
                pendingScreenshot = "6_camera_menu";
                wait = 5;
            }
            case 22 -> {
                mc.setScreen(null);
                server(p -> p.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inv, pl) ->
                        new dev.hackdanel.realcamera.menu.CameraPartsMenu(id, inv, InteractionHand.MAIN_HAND),
                        p.getMainHandItem().getHoverName()), buf -> buf.writeEnum(InteractionHand.MAIN_HAND)));
                wait = 10;
            }
            case 23 -> {
                pendingScreenshot = "7_camera_parts";
                wait = 5;
            }
            case 24 -> {
                mc.player.closeContainer();
                server(p -> {
                    // workshop with every part needed for an α7 IV
                    BlockPos station = origin.offset(-2, 0, 1);
                    p.serverLevel().setBlockAndUpdate(station, RCBlocks.ASSEMBLY_STATION.get().defaultBlockState());
                    var be = (dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity) p.serverLevel().getBlockEntity(station);
                    be.items().setStackInSlot(0, new ItemStack(RCItems.BSI_CMOS_SENSOR.get()));
                    be.items().setStackInSlot(1, new ItemStack(RCItems.PROCESSOR.get(), 2));
                    be.items().setStackInSlot(2, new ItemStack(RCItems.MAGNESIUM_ALLOY.get(), 3));
                    be.items().setStackInSlot(3, new ItemStack(RCItems.SHUTTER_UNIT.get()));
                    be.items().setStackInSlot(4, new ItemStack(RCItems.DISPLAY_PANEL.get(), 2));
                    be.items().setStackInSlot(5, new ItemStack(RCItems.STABILIZER.get()));
                    // charger powered by a redstone block, with an empty battery
                    BlockPos charger = origin.offset(-3, 0, 2);
                    p.serverLevel().setBlockAndUpdate(charger, RCBlocks.BATTERY_CHARGER.get().defaultBlockState());
                    p.serverLevel().setBlockAndUpdate(charger.below(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState());
                    var ch = (dev.hackdanel.realcamera.blockentity.ChargerBlockEntity) p.serverLevel().getBlockEntity(charger);
                    var bt = p.server.registryAccess().lookupOrThrow(RCRegistries.BATTERY_TYPE)
                            .getOrThrow(ResourceKey.create(RCRegistries.BATTERY_TYPE, RealCamera.id("np_fz100")));
                    ch.items().setStackInSlot(0, RCStacks.battery(bt.key(), bt.value(), false));
                    p.openMenu(be, station);
                });
                wait = 20;
            }
            case 25 -> {
                pendingScreenshot = "8_workshop";
                wait = 420; // a7 IV assembly time
            }
            case 26 -> server(p -> {
                var be = (dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity) p.serverLevel()
                        .getBlockEntity(origin.offset(-2, 0, 1));
                ItemStack out = be.items().getStackInSlot(dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity.OUTPUT);
                log(out.isEmpty() ? "FAIL workshop produced nothing" : "PASS workshop assembled: {}", out.getHoverName().getString());
                var ch = (dev.hackdanel.realcamera.blockentity.ChargerBlockEntity) p.serverLevel().getBlockEntity(origin.offset(-3, 0, 2));
                var charge = ch.items().getStackInSlot(0).get(dev.hackdanel.realcamera.registry.RCComponents.CHARGE.get());
                log(charge != null && charge.charge() > 0 ? "PASS charger charged battery to {}%" : "FAIL charger did not charge ({})",
                        charge == null ? "none" : charge.percent());
                p.openMenu(ch, origin.offset(-3, 0, 2));
            });
            case 27 -> wait = 10;
            case 28 -> {
                pendingScreenshot = "9_charger";
                wait = 5;
            }
            case 29 -> {
                mc.player.closeContainer();
                server(p -> p.openMenu((dev.hackdanel.realcamera.blockentity.PrinterBlockEntity) p.serverLevel()
                        .getBlockEntity(printerPos), printerPos));
                wait = 40;
            }
            case 30 -> {
                pendingScreenshot = "10_printer";
                wait = 5;
            }
            case 31 -> {
                mc.player.closeContainer();
                server(p -> {
                    int slot = 9;
                    for (var m : RCItems.MATERIALS) p.getInventory().setItem(slot++, new ItemStack(m.get()));
                    p.getInventory().setItem(slot++, new ItemStack(RCItems.ASSEMBLY_STATION.get()));
                    p.getInventory().setItem(slot++, new ItemStack(RCItems.BATTERY_CHARGER.get()));
                    p.getInventory().setItem(slot++, new ItemStack(RCItems.PHOTO_PRINTER.get()));
                    p.getInventory().setItem(slot++, new ItemStack(RCItems.INK_CYAN.get()));
                    p.getInventory().setItem(slot++, RCStacks.paper("10x15", "glossy", 16));
                    ItemStack frame = new ItemStack(RCItems.PHOTO_FRAME.get());
                    frame.set(dev.hackdanel.realcamera.registry.RCComponents.FRAME.get(), new FrameData(2, 2, "oak"));
                    p.getInventory().setItem(slot++, frame);
                    p.getInventory().setItem(slot++, RCStacks.lens(ResourceKey.create(RCRegistries.LENS, RealCamera.id("sony_fe_28_70_oss"))));
                });
                wait = 10;
            }
            case 32 -> {
                mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
                wait = 10;
            }
            case 33 -> {
                pendingScreenshot = "11_inventory";
                wait = 5;
            }
            case 34 -> {
                mc.setScreen(null);
                server(p -> {
                    // step-up rings all share one recipe: the workshop must let the player pick one
                    BlockPos station = origin.offset(-2, 0, 1);
                    var be = (dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity) p.serverLevel().getBlockEntity(station);
                    for (int s = 0; s <= dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity.OUTPUT; s++) {
                        be.items().setStackInSlot(s, ItemStack.EMPTY);
                    }
                    be.items().setStackInSlot(0, new ItemStack(net.minecraft.world.item.Items.IRON_NUGGET, 6));
                    be.items().setStackInSlot(1, new ItemStack(net.minecraft.world.item.Items.BLACK_DYE, 2));
                    p.openMenu(be, station);
                });
                wait = 20;
            }
            case 35 -> {
                if (mc.screen instanceof dev.hackdanel.realcamera.client.screen.AssemblyStationScreen s) {
                    int n = s.getMenu().choiceCount();
                    log(n > 1 ? "PASS workshop offers {} recipes for nuggets + dye" : "FAIL workshop offers {} recipe(s)", n);
                    s.mouseClicked(s.getGuiLeft() + dev.hackdanel.realcamera.menu.AssemblyStationMenu.CHOICE_X + 2 * 18 + 8,
                            s.getGuiTop() + dev.hackdanel.realcamera.menu.AssemblyStationMenu.CHOICE_Y + 8, 0);
                } else {
                    log("FAIL workshop screen not open: {}", mc.screen);
                }
                wait = 10;
            }
            case 36 -> {
                pendingScreenshot = "8b_workshop_choice";
                wait = 60; // ring assembly time 40
            }
            case 37 -> {
                mc.player.closeContainer();
                server(p -> {
                    var be = (dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity) p.serverLevel()
                            .getBlockEntity(origin.offset(-2, 0, 1));
                    ItemStack out = be.items().getStackInSlot(dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity.OUTPUT);
                    var all = dev.hackdanel.realcamera.recipe.AssemblyChoices.find(p.server.getRecipeManager(),
                            new dev.hackdanel.realcamera.recipe.AssemblyInput(java.util.List.of(
                                    new ItemStack(net.minecraft.world.item.Items.IRON_NUGGET, 6),
                                    new ItemStack(net.minecraft.world.item.Items.BLACK_DYE, 2))), p.level());
                    ItemStack want = all.size() > 2 ? all.get(2).value().getResultItem(p.registryAccess()) : ItemStack.EMPTY;
                    boolean ok = !out.isEmpty() && ItemStack.isSameItemSameComponents(out, want);
                    log(ok ? "PASS workshop made the picked recipe: {}" : "FAIL workshop made {} instead of the picked {}",
                            out.getHoverName().getString(), want.getHoverName().getString());
                });
                wait = 5;
            }
            default -> finish(mc);
        }
    }

    /** A copy of the print as if printed on another paper size. */
    private static ItemStack onPaper(ItemStack print, String size) {
        ItemStack s = print.copy();
        var d = s.get(dev.hackdanel.realcamera.registry.RCComponents.PRINT.get());
        s.set(dev.hackdanel.realcamera.registry.RCComponents.PRINT.get(),
                new dev.hackdanel.realcamera.component.PrintData(d.photo(), size, d.finish(), d.missingInks(), d.caption()));
        return s;
    }

    /** interact() takes the print from the player's hand; give it a copy to take from. */
    private static ServerPlayer fakeHolding(ServerPlayer p, ItemStack print) {
        p.setItemInHand(InteractionHand.MAIN_HAND, print.copy());
        return p;
    }

    private static void checkExport(Minecraft mc) {
        File dir = new File(mc.gameDirectory, "screenshots/realcamera");
        File[] days = dir.listFiles();
        int count = 0;
        if (days != null) {
            for (File d : days) {
                File[] files = d.listFiles((f, n) -> n.endsWith(".JPG"));
                if (files != null) count += files.length;
            }
        }
        log(count > 0 ? "PASS exported JPEG files: {}" : "FAIL no exported JPEG in {}", count > 0 ? count : dir);
    }

    private static void finish(Minecraft mc) {
        log("done, stopping");
        mc.stop();
    }

    @SubscribeEvent
    static void onFramePost(RenderFrameEvent.Post event) {
        if (!ENABLED || pendingScreenshot == null) return;
        String name = pendingScreenshot;
        pendingScreenshot = null;
        Minecraft mc = Minecraft.getInstance();
        NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        File dir = new File(mc.gameDirectory, "screenshots/autotest");
        Util.ioPool().execute(() -> {
            try (img) {
                dir.mkdirs();
                img.writeToFile(new File(dir, name + ".png"));
                log("screenshot {}", name);
            } catch (IOException e) {
                RealCamera.LOGGER.error("screenshot failed", e);
            }
        });
    }
}
