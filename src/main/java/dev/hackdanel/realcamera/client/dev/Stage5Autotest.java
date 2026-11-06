package dev.hackdanel.realcamera.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.block.DisplayBlock;
import dev.hackdanel.realcamera.block.PhotoStandBlock;
import dev.hackdanel.realcamera.block.TableFrameBlock;
import dev.hackdanel.realcamera.blockentity.ComputerBlockEntity;
import dev.hackdanel.realcamera.blockentity.DisplayBlockEntity;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.client.editor.EditSession;
import dev.hackdanel.realcamera.client.photo.CardListings;
import dev.hackdanel.realcamera.client.screen.AlbumScreen;
import dev.hackdanel.realcamera.client.screen.ComputerScreen;
import dev.hackdanel.realcamera.client.screen.EditorScreen;
import dev.hackdanel.realcamera.component.AlbumData;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.item.PhotoAlbumItem;
import dev.hackdanel.realcamera.item.RCStacks;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.photo.PhotoIndex;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.photo.PhotoStorage;
import dev.hackdanel.realcamera.processing.EditSettings;
import dev.hackdanel.realcamera.processing.RawFile;
import dev.hackdanel.realcamera.registry.RCBlocks;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.registry.RCRegistries;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Stage 5 end-to-end run (-Drealcamera.autotest=stage5): film cameras, latent frames, rewinding, the
 * developing tank (push, wrong chemistry, darkness), contact sheet, enlarger under a safelight, film
 * scanner, light leak from opening the back.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Stage5Autotest {
    private static final boolean ENABLED = "stage5".equals(System.getProperty("realcamera.autotest"));

    private record Step(String label, Runnable action, int delay, BooleanSupplier until, int timeout) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static boolean worldRequested, scripted;
    private static int wait, polled;
    private static String pendingScreenshot;
    private static BlockPos origin, computer;
    private static UUID cardA, cardB;
    private static EditSession session;
    private static String editStatus = "";

    private Stage5Autotest() {}

    static void log(String msg, Object... args) {
        RealCamera.LOGGER.info("[autotest] " + msg, args);
    }

    private static void step(String label, Runnable r, int waitTicks) {
        steps.add(new Step(label, r, waitTicks, null, 0));
    }

    private static void until(String label, BooleanSupplier cond, int timeout) {
        steps.add(new Step(label, () -> {}, 0, cond, timeout));
    }

    private static void check(String label, BooleanSupplier cond, int timeout) {
        steps.add(new Step(label, () -> {}, 0, () -> {
            if (cond.getAsBoolean()) {
                log("PASS {}", label);
                return true;
            }
            return false;
        }, timeout));
    }

    private static void server(Consumer<ServerPlayer> action) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().get(0)));
    }

    private static void cmd(ServerPlayer p, String command) {
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
    }

    private static <T> ResourceKey<T> key(ResourceKey<net.minecraft.core.Registry<T>> reg, String id) {
        return ResourceKey.create(reg, RealCamera.id(id));
    }

    private static void screenshot(String name) {
        step("screenshot " + name, () -> pendingScreenshot = name, 3);
    }

    private static void use(BlockPos pos, int slot) {
        Minecraft mc = Minecraft.getInstance();
        mc.player.getInventory().selected = slot;
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false));
    }

    private static List<PhotoMeta> listing(UUID card) {
        List<PhotoMeta> l = card == null ? null : CardListings.get(card);
        return l == null ? List.of() : l;
    }

    private static File newestIn(File dir, String suffix) {
        File best = null;
        File[] days = dir.listFiles();
        if (days == null) return null;
        for (File d : days) {
            File[] f = d.listFiles((x, n) -> n.endsWith(suffix));
            if (f == null) continue;
            for (File file : f) {
                if (best == null || file.lastModified() > best.lastModified()) best = file;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the script

    private static UUID rollId, rollId2;
    private static BlockPos darkroom;

    private static ItemStack cameraWithFilm(String body, String stock) {
        Minecraft mc = Minecraft.getInstance();
        var regs = mc.level.registryAccess();
        var ref = regs.lookupOrThrow(RCRegistries.CAMERA_BODY).getOrThrow(key(RCRegistries.CAMERA_BODY, body));
        ItemStack cam = RCStacks.kit(regs, ref).orElseThrow();
        CameraHelper.setParts(cam, CameraHelper.parts(cam).with(dev.hackdanel.realcamera.component.CameraParts.SLOT_CARD,
                RCStacks.film(key(RCRegistries.FILM_STOCK, stock), "135")));
        cam.set(RCComponents.CAMERA_SETTINGS.get(), CameraSettings.DEFAULT.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(5.6)).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(5f));
        return cam;
    }

    private static void give(int slot, java.util.function.Supplier<ItemStack> stack) {
        step("give " + slot, () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = stack.get();
            mc.player.getInventory().setItem(slot, s);
            mc.gameMode.handleCreativeModeItemAdd(s, 36 + slot);
        }, 10);
    }

    private static dev.hackdanel.realcamera.component.FilmRoll roll(int slot) {
        return CameraHelper.parts(Minecraft.getInstance().player.getInventory().getItem(slot))
                .card().get(RCComponents.FILM.get());
    }

    private static void shootFrames(int n) {
        step("raise", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder", Viewfinder::isActive, 60);
        step("iso", () -> {
            var stock = Viewfinder.filmStock();
            var exp = Viewfinder.exposure();
            log(stock != null && exp != null && exp.iso() == stock.iso() ? "PASS film ISO locked to {}" : "FAIL ISO {} for film {}",
                    exp == null ? -1 : exp.iso(), stock == null ? "-" : stock.name());
        }, 1);
        for (int i = 0; i < n; i++) {
            step("shoot " + i, Viewfinder::debugPressShutter, 5);
            until("started " + i, PhotoCapture::busy, 80);
            until("stored " + i, () -> !PhotoCapture.busy(), 1200);
            step("wait", () -> {}, 15);
        }
        step("lower", () -> Minecraft.getInstance().options.keyUse.setDown(false), 10);
    }

    /** A sealed stone room (dark inside) east of the start, with the blocks the lab needs. */
    private static void buildDarkroom(ServerPlayer p) {
        darkroom = origin.offset(6, 0, 0);
        BlockPos a = darkroom.offset(-2, -1, -2), b = darkroom.offset(2, 3, 2);
        cmd(p, String.format("fill %d %d %d %d %d %d minecraft:stone hollow", a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
        p.serverLevel().setBlockAndUpdate(darkroom, RCBlocks.DEVELOPING_TANK.get().defaultBlockState());
        p.serverLevel().setBlockAndUpdate(darkroom.offset(1, 0, 0), RCBlocks.ENLARGER.get().defaultBlockState());
        p.serverLevel().setBlockAndUpdate(darkroom.offset(-1, 2, -1), RCBlocks.SAFELIGHT.get().defaultBlockState());
    }

    private static void script() {
        step("scene", () -> server(p -> {
            origin = p.blockPosition();
            cmd(p, "time set 6000");
            cmd(p, "weather clear");
            cmd(p, "fill ~-8 ~-1 ~-4 ~12 ~-1 ~30 minecraft:grass_block");
            cmd(p, "fill ~-8 ~ ~-4 ~12 ~10 ~30 minecraft:air");
            cmd(p, "fill ~-1 ~ ~9 ~1 ~2 ~9 minecraft:white_concrete");
            cmd(p, "fill ~2 ~ ~10 ~2 ~3 ~10 minecraft:yellow_wool");
            cmd(p, "fill ~-3 ~ ~12 ~-3 ~4 ~12 minecraft:blue_wool");
            cmd(p, "summon minecraft:villager ~0.5 ~ ~5 {NoAI:1b,Rotation:[180f,0f]}");
            cmd(p, "tp @s ~ ~ ~ 0 0");
            buildDarkroom(p);
        }), 60);

        // 1) Nikon FM2 + Portra 400: box speed fixed, three frames, nothing visible yet
        give(0, () -> cameraWithFilm("nikon_fm2", "kodak_portra_400"));
        shootFrames(3);
        check("three frames counted on the roll", () -> roll(0) != null && roll(0).shot() == 3, 100);
        step("roll id", () -> rollId = roll(0).id().orElse(null), 1);
        step("ask listing", () -> CardListings.refresh(rollId), 40);
        check("undeveloped frames stay hidden", () -> CardListings.get(rollId) != null && CardListings.get(rollId).isEmpty(), 60);

        // 2) rewind and develop in the dark (C-41)
        step("rewind", () -> PacketDistributor.sendToServer(new Payloads.RewindFilm(
                dev.hackdanel.realcamera.camera.CameraRef.hand(InteractionHand.MAIN_HAND))), 10);
        check("rewound", () -> roll(0).state() == dev.hackdanel.realcamera.component.FilmRoll.EXPOSED, 40);
        step("tank", () -> server(p -> {
            var be = (dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity) p.serverLevel().getBlockEntity(darkroom);
            ItemStack cam = p.getInventory().getItem(0);
            be.items().setStackInSlot(0, CameraHelper.parts(cam).card().copy());
            be.items().setStackInSlot(1, new ItemStack(RCItems.CHEMISTRY_C41.get()));
            // the safelight is on in this darkroom, so the reel is loaded in a changing bag
            be.items().setStackInSlot(2, new ItemStack(RCItems.CHANGING_BAG.get()));
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity.BUTTON_PUSH); // +1
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity.BUTTON_START);
        }), 10);
        until("developed", () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            return PhotoStorage.index(server).all().stream().filter(e -> !e.latent()).count() >= 3;
        }, 600);
        step("tank out", () -> server(p -> {
            var be = (dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity) p.serverLevel().getBlockEntity(darkroom);
            ItemStack out = be.items().getStackInSlot(3);
            var r = out.get(RCComponents.FILM.get());
            log(r != null && r.state() == dev.hackdanel.realcamera.component.FilmRoll.DEVELOPED && r.push() == 1
                    ? "PASS developed in the dark, pushed +1" : "FAIL tank output {}", out);
            p.getInventory().setItem(1, out.copy());
        }), 10);
        step("refresh", () -> CardListings.refresh(rollId), 5);
        check("developed frames visible", () -> CardListings.get(rollId) != null && CardListings.get(rollId).size() == 3, 100);
        step("contact sheet", () -> Minecraft.getInstance().setScreen(new dev.hackdanel.realcamera.client.screen.ContactSheetScreen(
                rollId, net.minecraft.network.chat.Component.literal("Kodak Portra 400"))), 50);
        screenshot("s5_01_contact_sheet");
        step("close", () -> Minecraft.getInstance().setScreen(null), 5);

        // 3) enlarger under the safelight: a B&W print on RC paper, grade 4, +1 stop
        step("enlarge", () -> server(p -> {
            var be = (dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity) p.serverLevel().getBlockEntity(darkroom.offset(1, 0, 0));
            log(dev.hackdanel.realcamera.gameplay.Darkroom.dark(p.serverLevel(), darkroom.offset(1, 0, 0), true)
                    ? "PASS safelit darkroom counts as dark" : "FAIL darkroom too bright");
            log(!dev.hackdanel.realcamera.gameplay.Darkroom.dark(p.serverLevel(), darkroom, false)
                    ? "PASS the safelight still fogs film" : "FAIL film safe under the safelight");
            be.items().setStackInSlot(0, p.getInventory().getItem(1).copy());
            be.items().setStackInSlot(1, RCStacks.paper("a4", "rc_bw", 4));
            be.items().setStackInSlot(dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity.SLOT_CHEMISTRY,
                    new ItemStack(RCItems.CHEMISTRY_BW.get()));
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity.B_HARDER);
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity.B_HARDER);
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity.B_LIGHTER);
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity.B_LIGHTER);
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity.B_EXPOSE);
        }), 100);
        step("print out", () -> server(p -> {
            var be = (dev.hackdanel.realcamera.blockentity.EnlargerBlockEntity) p.serverLevel().getBlockEntity(darkroom.offset(1, 0, 0));
            ItemStack out = be.items().getStackInSlot(2);
            PrintData pd = out.get(RCComponents.PRINT.get());
            log(pd != null && pd.finish().equals("rc_bw") && pd.photo().editedFrom().isPresent()
                    ? "PASS darkroom print on RC paper" : "FAIL enlarger output {}", out);
            if (pd != null) p.getInventory().setItem(2, out.copy());
        }), 30);
        step("view print", () -> {
            ItemStack s = Minecraft.getInstance().player.getInventory().getItem(2);
            PrintData pd = s.get(RCComponents.PRINT.get());
            if (pd != null) Minecraft.getInstance().setScreen(new dev.hackdanel.realcamera.client.screen.PrintViewerScreen(pd));
        }, 60);
        screenshot("s5_02_bw_print");
        step("close", () -> Minecraft.getInstance().setScreen(null), 5);

        // 4) film scanner in the computer: copy a frame onto an SD card
        step("computer", () -> server(p -> {
            computer = origin.offset(-2, 0, 1);
            p.serverLevel().setBlockAndUpdate(computer, RCBlocks.COMPUTER.get().defaultBlockState()
                    .setValue(DisplayBlock.FACING, Direction.EAST));
            var be = (ComputerBlockEntity) p.serverLevel().getBlockEntity(computer);
            var tank = (dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity) p.serverLevel().getBlockEntity(darkroom);
            be.items().setStackInSlot(ComputerBlockEntity.SLOT_CARD_A, p.getInventory().getItem(1).copy());
            be.items().setStackInSlot(ComputerBlockEntity.SLOT_CARD_B, RCStacks.card(key(RCRegistries.MEMORY_CARD_TYPE, "sd_uhs2_128")));
            be.items().setStackInSlot(ComputerBlockEntity.SLOT_SCANNER, new ItemStack(RCItems.FILM_SCANNER.get()));
        }), 10);
        step("open computer", () -> use(computer, 6), 10);
        until("computer screen", () -> Minecraft.getInstance().screen instanceof ComputerScreen, 60);
        step("wait thumbs", () -> {}, 40);
        screenshot("s5_03_scanner");
        step("scan", () -> PacketDistributor.sendToServer(new Payloads.ComputerAction(computer, Payloads.PC_COPY, 0,
                listing(rollId).get(0).id(), 0)), 10);
        step("card B id", () -> server(p -> cardB = ((ComputerBlockEntity) p.serverLevel().getBlockEntity(computer))
                .cardIdOrNull(ComputerBlockEntity.SLOT_CARD_B)), 5);
        check("scanned frame on the SD card", () -> cardB != null && listing(cardB).size() == 1, 80);
        check("film frames can't be deleted", () -> {
            PacketDistributor.sendToServer(new Payloads.ComputerAction(computer, Payloads.PC_DELETE, 0, listing(rollId).get(0).id(), 0));
            return true;
        }, 5);
        step("refresh roll", () -> CardListings.refresh(rollId), 30);
        check("still 3 frames on the film", () -> listing(rollId).size() == 3, 60);
        step("close", () -> Minecraft.getInstance().player.closeContainer(), 10);

        // 5) Leica M6 + Tri-X: back opened in daylight without rewinding -> light leak
        give(0, () -> cameraWithFilm("leica_m6", "kodak_tri_x_400"));
        shootFrames(2);
        step("open back", () -> server(p -> {
            ItemStack cam = p.getInventory().getItem(0);
            ItemStack r = CameraHelper.parts(cam).card().copy();
            dev.hackdanel.realcamera.menu.CameraPartsMenu.openBack(p, r);
            var fr = r.get(RCComponents.FILM.get());
            log(fr.fogged() && fr.state() == dev.hackdanel.realcamera.component.FilmRoll.EXPOSED
                    ? "PASS opening the back in daylight fogs the film" : "FAIL roll {}", fr);
            rollId2 = fr.id().orElse(null);
            var be = (dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity) p.serverLevel().getBlockEntity(darkroom);
            be.items().setStackInSlot(3, ItemStack.EMPTY);
            be.items().setStackInSlot(0, r);
            be.items().setStackInSlot(1, new ItemStack(RCItems.CHEMISTRY_C41.get()));
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity.BUTTON_START);
        }), 10);
        step("wrong chemistry check", () -> server(p -> {
            var be = (dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity) p.serverLevel().getBlockEntity(darkroom);
            boolean refused = !be.items().getStackInSlot(0).isEmpty() && be.items().getStackInSlot(3).isEmpty();
            log(refused ? "PASS C-41 refused for B&W film" : "FAIL tank accepted wrong chemistry");
            be.items().setStackInSlot(1, new ItemStack(RCItems.CHEMISTRY_BW.get()));
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity.BUTTON_PUSH); // push back to 0? cycles
        }), 5);
        step("set normal", () -> server(p -> {
            var be = (dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity) p.serverLevel().getBlockEntity(darkroom);
            for (int i = 0; i < 4; i++) {
                be.handleButton(p, dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity.BUTTON_PUSH);
            }
            be.handleButton(p, dev.hackdanel.realcamera.blockentity.DevelopingTankBlockEntity.BUTTON_START);
        }), 160);
        step("refresh 2", () -> CardListings.refresh(rollId2), 20);
        check("fogged roll developed", () -> listing(rollId2).size() == 2, 100);
        step("sheet 2", () -> Minecraft.getInstance().setScreen(new dev.hackdanel.realcamera.client.screen.ContactSheetScreen(
                rollId2, net.minecraft.network.chat.Component.literal("Kodak Tri-X 400 (fogged)"))), 50);
        screenshot("s5_04_fogged_trix");
        step("close", () -> Minecraft.getInstance().setScreen(null), 5);
        step("done", () -> {
            log("done, stopping");
            Minecraft.getInstance().stop();
        }, 60);
    }

    // ------------------------------------------------------------------ driver

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (!worldRequested && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            worldRequested = true;
            String name = "rc_stage5_" + System.currentTimeMillis();
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            mc.createWorldOpenFlows().createFreshLevel(name, new LevelSettings(name, GameType.CREATIVE, false,
                            Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(20261105L, false, false), WorldPresets::createNormalWorldDimensions, new TitleScreen());
            return;
        }
        if (mc.level == null || mc.player == null || (mc.screen != null && !scripted)) return;
        if (!scripted) {
            if (++polled < 200) return;
            scripted = true;
            polled = 0;
            script();
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Step s = steps.peekFirst();
        if (s == null) return;
        if (s.until() != null) {
            boolean ok;
            try {
                ok = s.until().getAsBoolean();
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok) {
                steps.pollFirst();
                polled = 0;
            } else if (++polled > s.timeout()) {
                log("FAIL timeout waiting for {}", s.label());
                steps.pollFirst();
                polled = 0;
            }
            return;
        }
        steps.pollFirst();
        try {
            s.action().run();
        } catch (RuntimeException e) {
            log("FAIL step {} threw {}", s.label(), e.toString());
        }
        wait = s.delay();
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
