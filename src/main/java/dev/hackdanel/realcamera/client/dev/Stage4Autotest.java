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
 * Stage 4 end-to-end run (-Drealcamera.autotest=stage4): RAW+JPEG shot, computer catalogue, the editor
 * (RAW development, crop, save as copy, export), ratings, copy between cards, format, table frame,
 * photo stand, album and lectern, block drops.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Stage4Autotest {
    private static final boolean ENABLED = "stage4".equals(System.getProperty("realcamera.autotest"));

    private record Step(String label, Runnable action, int delay, BooleanSupplier until, int timeout) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static boolean worldRequested, scripted;
    private static int wait, polled;
    private static String pendingScreenshot;
    private static BlockPos origin, computer;
    private static UUID cardA, cardB;
    private static EditSession session;
    private static String editStatus = "";

    private Stage4Autotest() {}

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

    private static void script() {
        step("scene", () -> server(p -> {
            origin = p.blockPosition();
            cmd(p, "time set 6000");
            cmd(p, "weather clear");
            cmd(p, "fill ~-8 ~-1 ~-4 ~8 ~-1 ~30 minecraft:grass_block");
            cmd(p, "fill ~-8 ~ ~-4 ~8 ~10 ~30 minecraft:air");
            cmd(p, "fill ~-1 ~ ~7 ~1 ~2 ~7 minecraft:white_concrete");
            cmd(p, "fill ~2 ~ ~9 ~2 ~3 ~9 minecraft:yellow_wool");
            cmd(p, "fill ~-3 ~ ~12 ~-3 ~4 ~12 minecraft:blue_wool");
            cmd(p, "summon minecraft:villager ~0.5 ~ ~5 {NoAI:1b,Rotation:[180f,0f]}");
            cmd(p, "tp @s ~ ~ ~ 0 0");
        }), 60);

        // 1) RAW + JPEG, deliberately over-exposed by 2 EV
        step("camera", () -> {
            Minecraft mc = Minecraft.getInstance();
            var regs = mc.level.registryAccess();
            var body = regs.lookupOrThrow(RCRegistries.CAMERA_BODY).getOrThrow(key(RCRegistries.CAMERA_BODY, "sony_a7iv"));
            ItemStack cam = RCStacks.kit(regs, body).orElseThrow();
            cam.set(RCComponents.CAMERA_SETTINGS.get(), CameraSettings.DEFAULT.withMode(CameraSettings.MODE_A)
                    .withApertureIndex(ExposureScales.nearestAperture(5.6)).withFocal(35).withEvComp(6)
                    .withFileFormat(CameraSettings.FORMAT_RAW_JPEG));
            mc.player.getInventory().setItem(0, cam);
            mc.gameMode.handleCreativeModeItemAdd(cam, 36);
        }, 10);
        step("raise", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder", Viewfinder::isActive, 60);
        step("shoot", Viewfinder::debugPressShutter, 5);
        until("started", PhotoCapture::busy, 80);
        until("stored", () -> !PhotoCapture.busy(), 1200);
        step("lower", () -> Minecraft.getInstance().options.keyUse.setDown(false), 40);
        step("raw check", () -> {
            File png = newestIn(new File(Minecraft.getInstance().gameDirectory, "screenshots/realcamera"), ".DNG");
            try {
                RawFile.Info info = png == null ? null : RawFile.readInfo(png.toPath());
                boolean ok = info != null && info.version >= 2 && info.white > 1.5 && info.profiles.length > 0;
                log(ok ? "PASS DNG RAW {} (white {}, {} profiles)" : "FAIL RAW sidecar {}", png == null ? "-" : png.getName(),
                        info == null ? 0 : info.white, info == null ? 0 : info.profiles.length);
            } catch (IOException e) {
                log("FAIL RAW unreadable: {}", e.toString());
            }
        }, 1);

        // 2) computer with the card from the camera
        step("computer", () -> server(p -> {
            computer = origin.offset(2, 0, 1);
            p.serverLevel().setBlockAndUpdate(computer, RCBlocks.COMPUTER.get().defaultBlockState()
                    .setValue(DisplayBlock.FACING, Direction.WEST));
            var be = (ComputerBlockEntity) p.serverLevel().getBlockEntity(computer);
            ItemStack card = CameraHelper.parts(p.getInventory().getItem(0)).card().copy();
            be.items().setStackInSlot(ComputerBlockEntity.SLOT_CARD_A, card);
            ItemStack second = RCStacks.card(key(RCRegistries.MEMORY_CARD_TYPE, "sd_uhs2_128"));
            be.items().setStackInSlot(ComputerBlockEntity.SLOT_CARD_B, second);
            cardA = card.get(RCComponents.CARD.get()).id().orElse(null);
        }), 10);
        step("open computer", () -> use(computer, 1), 10);
        until("computer screen", () -> Minecraft.getInstance().screen instanceof ComputerScreen, 60);
        step("wait", () -> {}, 20);
        screenshot("s4_01_needs_reader");
        step("reader", () -> server(p -> {
            var be = (ComputerBlockEntity) p.serverLevel().getBlockEntity(computer);
            be.items().setStackInSlot(ComputerBlockEntity.SLOT_READER, new ItemStack(RCItems.CFEXPRESS_READER.get()));
        }), 10);
        check("catalogue lists the shot", () -> listing(cardA).size() == 1, 100);
        step("wait thumbs", () -> {}, 40);
        screenshot("s4_02_catalogue");

        // 3) editor on the RAW: pull the over-exposure back, lift shadows, crop 3:2
        step("open editor", () -> {
            Minecraft mc = Minecraft.getInstance();
            PhotoMeta meta = listing(cardA).get(0);
            session = new EditSession(meta, computer, 0);
            mc.setScreen(new EditorScreen(mc.screen, session));
        }, 5);
        check("editor loaded the RAW original", () -> session.state() == EditSession.State.READY && session.raw(), 200);
        step("wait preview", () -> {}, 30);
        screenshot("s4_03_editor_as_shot");
        step("edit", () -> {
            EditSettings s = session.settings;
            session.checkpoint();
            s.exposure = -1.7;
            s.highlights = -40;
            s.shadows = 30;
            s.vibrance = 25;
            s.contrast = 15;
            s.cropX = 0.1;
            s.cropY = 0.12;
            s.cropW = 0.8;
            s.cropH = 0.8;
            s.straighten = 3;
            if (Minecraft.getInstance().screen instanceof EditorScreen editor) editor.refresh();
        }, 40);
        screenshot("s4_04_editor_edited");
        step("crop tab", () -> {
            if (Minecraft.getInstance().screen instanceof EditorScreen editor) editor.showTab(5);
        }, 30);
        screenshot("s4_05_editor_crop");
        step("save copy", () -> session.saveCopy(k -> editStatus = k), 5);
        check("edited copy stored on the card", () -> listing(cardA).size() == 2
                && listing(cardA).stream().anyMatch(m -> m.editedFrom().isPresent()), 400);
        step("export", () -> session.export(k -> editStatus = k, p -> log("exported {}", p.getFileName())), 5);
        check("exported full-size JPEG", () -> "gui.realcamera.editor.exported".equals(editStatus), 400);
        step("back", () -> Minecraft.getInstance().screen.onClose(), 10);
        until("computer again", () -> Minecraft.getInstance().screen instanceof ComputerScreen, 40);

        // 4) rating, copy to card B, format card B
        step("rate", () -> PacketDistributor.sendToServer(new Payloads.ComputerAction(computer, Payloads.PC_RATE, 0,
                listing(cardA).get(0).id(), 4)), 10);
        step("refresh", () -> CardListings.refresh(cardA), 5);
        check("rating stored", () -> CardListings.rating(listing(cardA).get(0).id()) == 4, 60);
        step("copy", () -> PacketDistributor.sendToServer(new Payloads.ComputerAction(computer, Payloads.PC_COPY, 0,
                listing(cardA).get(1).id(), 0)), 10);
        step("card B id", () -> server(p -> {
            var be = (ComputerBlockEntity) p.serverLevel().getBlockEntity(computer);
            cardB = be.cardIdOrNull(ComputerBlockEntity.SLOT_CARD_B);
        }), 5);
        check("copied to card B", () -> cardB != null && listing(cardB).size() == 1, 80);
        screenshot("s4_06_catalogue_rated");
        step("format B", () -> PacketDistributor.sendToServer(new Payloads.ComputerAction(computer, Payloads.PC_FORMAT, 1,
                new UUID(0, 0), 0)), 10);
        step("refresh B", () -> CardListings.refresh(cardB), 5);
        check("card B formatted, file kept for card A", () -> listing(cardB).isEmpty() && listing(cardA).size() == 2, 80);
        step("close", () -> Minecraft.getInstance().player.closeContainer(), 10);

        // 5) prints of both photos -> table frame, photo stand
        step("prints", () -> server(p -> {
            PhotoIndex index = PhotoStorage.index(p.server);
            List<UUID> photos = PhotoStorage.cards(p.server).photos(cardA);
            for (int i = 0; i < photos.size(); i++) {
                ItemStack print = new ItemStack(RCItems.PHOTO_PRINT.get());
                print.set(RCComponents.PRINT.get(), new PrintData(index.get(photos.get(i)).meta(), i == 0 ? "a3" : "10x15",
                        "glossy", 0, ""));
                p.getInventory().setItem(3 + i, print);
            }
            BlockPos frame = origin.offset(-1, 0, 3), stand = origin.offset(1, 0, 4);
            // turned between the cardinal directions (drawn by DisplayRenderer), plus an empty one at 45 degrees
            p.serverLevel().setBlockAndUpdate(frame, RCBlocks.TABLE_FRAME.get().defaultBlockState()
                    .setValue(DisplayBlock.FACING, Direction.NORTH).setValue(TableFrameBlock.TURN, 1));
            p.serverLevel().setBlockAndUpdate(frame.west(), RCBlocks.TABLE_FRAME.get().defaultBlockState()
                    .setValue(DisplayBlock.FACING, Direction.NORTH).setValue(TableFrameBlock.TURN, 2));
            p.serverLevel().setBlockAndUpdate(stand, RCBlocks.PHOTO_STAND.get().defaultBlockState()
                    .setValue(DisplayBlock.FACING, Direction.NORTH).setValue(PhotoStandBlock.HALF, DoubleBlockHalf.LOWER));
            p.serverLevel().setBlockAndUpdate(stand.above(), RCBlocks.PHOTO_STAND.get().defaultBlockState()
                    .setValue(DisplayBlock.FACING, Direction.NORTH).setValue(PhotoStandBlock.HALF, DoubleBlockHalf.UPPER));
            cmd(p, "kill @e[type=minecraft:villager]");
            cmd(p, "tp @s " + origin.getX() + ".5 " + origin.getY() + " " + (origin.getZ() + 0.5) + " 0 20");
        }), 20);
        step("frame print", () -> use(origin.offset(-1, 0, 3), 4), 10);
        step("stand print", () -> use(origin.offset(1, 1, 4), 3), 20);
        check("displays hold the prints", () -> {
            var level = Minecraft.getInstance().level;
            return level.getBlockEntity(origin.offset(-1, 0, 3)) instanceof DisplayBlockEntity f && !f.print().isEmpty()
                    && level.getBlockEntity(origin.offset(1, 0, 4)) instanceof DisplayBlockEntity s && !s.print().isEmpty();
        }, 60);
        step("wait textures", () -> {}, 40);
        screenshot("s4_07_displays");
        step("portrait check", () -> {
            var st = Minecraft.getInstance().level.getBlockState(origin.offset(-1, 0, 3));
            log("table frame portrait={} (photo is landscape)", st.getValue(TableFrameBlock.PORTRAIT));
        }, 1);

        // 6) album: insert two prints, caption, open
        step("album", () -> server(p -> {
            p.getInventory().setItem(2, new ItemStack(RCItems.PHOTO_ALBUM.get()));
            PhotoIndex index = PhotoStorage.index(p.server);
            for (UUID id : PhotoStorage.cards(p.server).photos(cardA)) {
                ItemStack print = new ItemStack(RCItems.PHOTO_PRINT.get());
                print.set(RCComponents.PRINT.get(), new PrintData(index.get(id).meta(), "10x15", "matte", 0, ""));
                p.getInventory().add(print);
            }
            p.getInventory().selected = 2;
        }), 10);
        step("select album", () -> Minecraft.getInstance().player.getInventory().selected = 2, 5);
        step("insert 1", () -> PacketDistributor.sendToServer(new Payloads.AlbumAction(0, BlockPos.ZERO, Payloads.ALBUM_INSERT, 0, "")), 5);
        step("insert 2", () -> PacketDistributor.sendToServer(new Payloads.AlbumAction(0, BlockPos.ZERO, Payloads.ALBUM_INSERT, 1, "")), 5);
        step("caption", () -> PacketDistributor.sendToServer(new Payloads.AlbumAction(0, BlockPos.ZERO, Payloads.ALBUM_CAPTION, 0,
                "Первый снимок этапа 4")), 10);
        check("album holds 2 prints with a caption", () -> {
            AlbumData d = PhotoAlbumItem.data(Minecraft.getInstance().player.getInventory().getItem(2));
            return d.prints().size() == 2 && !d.prints().get(0).caption().isEmpty();
        }, 60);
        step("open album", () -> Minecraft.getInstance().setScreen(AlbumScreen.inHand(InteractionHand.MAIN_HAND)), 30);
        screenshot("s4_08_album");
        step("close album", () -> Minecraft.getInstance().setScreen(null), 5);

        // 7) lectern: place the album, open it with an empty hand, take it back
        step("lectern", () -> server(p -> p.serverLevel().setBlockAndUpdate(origin.offset(0, 0, 2),
                Blocks.LECTERN.defaultBlockState())), 10);
        step("put album", () -> use(origin.offset(0, 0, 2), 2), 10);
        check("album on the lectern", () -> Minecraft.getInstance().level.getBlockState(origin.offset(0, 0, 2))
                .getValue(net.minecraft.world.level.block.LecternBlock.HAS_BOOK), 40);
        step("read lectern", () -> use(origin.offset(0, 0, 2), 6), 20);
        check("lectern opens the album screen", () -> Minecraft.getInstance().screen instanceof AlbumScreen, 40);
        screenshot("s4_09_lectern");
        step("take", () -> {
            PacketDistributor.sendToServer(new Payloads.AlbumAction(-1, origin.offset(0, 0, 2), Payloads.ALBUM_TAKE_FROM_LECTERN, 0, ""));
            Minecraft.getInstance().setScreen(null);
        }, 10);
        check("album taken back", () -> Minecraft.getInstance().player.getInventory().items.stream()
                .anyMatch(s -> s.is(RCItems.PHOTO_ALBUM.get())), 40);

        // 8) breaking blocks drops them (and the print)
        step("break", () -> server(p -> {
            BlockPos f = origin.offset(-1, 0, 3), s = origin.offset(1, 0, 4);
            cmd(p, "kill @e[type=minecraft:item]");
            cmd(p, "tp @s ~ ~ ~-12"); // out of pickup range
            cmd(p, "setblock " + f.getX() + " " + f.getY() + " " + f.getZ() + " air destroy");
            cmd(p, "setblock " + s.getX() + " " + (s.getY() + 1) + " " + s.getZ() + " air destroy");
            cmd(p, "setblock " + computer.getX() + " " + computer.getY() + " " + computer.getZ() + " air destroy");
        }), 20);
        step("drops", () -> server(p -> {
            List<ItemEntity> items = p.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(8));
            long frames = items.stream().filter(e -> e.getItem().is(RCItems.TABLE_FRAME.get())).count();
            long stands = items.stream().filter(e -> e.getItem().is(RCItems.PHOTO_STAND.get())).count();
            long prints = items.stream().filter(e -> e.getItem().is(RCItems.PHOTO_PRINT.get())).count();
            long computers = items.stream().filter(e -> e.getItem().is(RCItems.COMPUTER.get())).count();
            long cards = items.stream().filter(e -> e.getItem().is(RCItems.MEMORY_CARD.get())).count();
            boolean ok = frames == 1 && stands == 1 && prints == 2 && computers == 1 && cards == 2;
            log(ok ? "PASS drops: frame, stand, 2 prints, computer, 2 cards"
                    : "FAIL drops: frames {} stands {} prints {} computers {} cards {}", frames, stands, prints, computers, cards);
        }), 10);
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
            String name = "rc_stage4_" + System.currentTimeMillis();
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
