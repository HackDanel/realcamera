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
import dev.hackdanel.realcamera.client.screen.JournalScreen;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.client.screen.ComputerScreen;
import dev.hackdanel.realcamera.client.screen.EditorScreen;
import dev.hackdanel.realcamera.component.AlbumData;
import dev.hackdanel.realcamera.component.CameraParts;
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
 * Stage 7 end-to-end run (-Drealcamera.autotest=stage7): server frame analysis (subject, focus, stars), the photo
 * encyclopedia, advancements, a sunset phenomenon, the newspaper editor's order fulfilled with a print.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Stage7Autotest {
    private static final boolean ENABLED = "stage7".equals(System.getProperty("realcamera.autotest"));

    private record Step(String label, Runnable action, int delay, BooleanSupplier until, int timeout) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static boolean worldRequested, scripted;
    private static int wait, polled;
    private static String pendingScreenshot;
    private static BlockPos origin, computer;
    private static UUID cardA, cardB;
    private static EditSession session;
    private static String editStatus = "";

    private Stage7Autotest() {}

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

    private static ItemStack kit(String body, CameraSettings settings) {
        Minecraft mc = Minecraft.getInstance();
        var regs = mc.level.registryAccess();
        var ref = regs.lookupOrThrow(RCRegistries.CAMERA_BODY).getOrThrow(key(RCRegistries.CAMERA_BODY, body));
        ItemStack cam = RCStacks.kit(regs, ref).orElseThrow();
        cam.set(RCComponents.CAMERA_SETTINGS.get(), settings);
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

    private static void shoot(String label) {
        step("raise " + label, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder " + label, Viewfinder::isActive, 60);
        step("settle " + label, () -> {}, 30);
        step("shoot " + label, Viewfinder::debugPressShutter, 5);
        until("started " + label, PhotoCapture::busy, 80);
        until("stored " + label, () -> !PhotoCapture.busy(), 1200);
        step("lower " + label, () -> Minecraft.getInstance().options.keyUse.setDown(false), 40);
    }

    private static UUID cardId() {
        ItemStack cam = Minecraft.getInstance().player.getInventory().getItem(0);
        var card = CameraHelper.parts(cam).card().get(RCComponents.CARD.get());
        return card == null ? null : card.id().orElse(null);
    }

    /** The newest photo on the camera's card, as listed by the server (with its analysis). */
    private static PhotoMeta newest() {
        List<PhotoMeta> l = listing(cardId());
        return l.isEmpty() ? null : l.stream().max(java.util.Comparator.comparingLong(PhotoMeta::createdMillis)).orElse(null);
    }

    private static boolean advancement(String id) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        var holder = server.getAdvancements().get(RealCamera.id(id));
        return holder != null && p.getAdvancements().getOrStartProgress(holder).isDone();
    }

    private static PhotoMeta cowPhoto;
    private static net.minecraft.world.entity.npc.Villager editor;

    private static void script() {
        step("scene", () -> server(p -> {
            origin = p.blockPosition();
            cmd(p, String.format("tp @s %d.5 %d %d.5 0 0", origin.getX(), origin.getY(), origin.getZ()));
            cmd(p, "time set 6000");
            cmd(p, "weather clear");
            cmd(p, "fill ~-12 ~-1 ~-12 ~12 ~-1 ~30 minecraft:grass_block");
            cmd(p, "fill ~-12 ~ ~-12 ~12 ~20 ~30 minecraft:air");
            cmd(p, "summon minecraft:cow ~0.6 ~ ~5 {NoAI:1b,Rotation:[90f,0f]}");
            cmd(p, "setblock ~4 ~ ~-3 realcamera:editorial_desk");
        }), 60);

        // 1) a cow, sharp, filling a good part of the frame
        give(0, () -> kit("sony_a7iv", CameraSettings.DEFAULT.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(5.6)).withFocusMode(CameraSettings.FOCUS_MF).withFocusDistance(4.4f)));
        shoot("cow");
        step("listing", () -> CardListings.refresh(cardId()), 40);
        check("photo carries a server analysis", () -> newest() != null && newest().analysis().isPresent(), 100);
        step("cow analysis", () -> {
            cowPhoto = newest();
            var a = cowPhoto.analysis().get();
            log("analysis: subjects {}, sky {}, quality {}, stars {}, phenomena {}", a.subjects(), a.sky(), a.quality(),
                    a.stars(), a.phenomena());
            log(a.shows("minecraft:cow") ? "PASS the server saw a sharp cow" : "FAIL no sharp cow in {}", a.subjects());
            log(a.stars() >= 2 ? "PASS a well-made cow photo gets {} stars" : "FAIL only {} stars", a.stars());
        }, 1);
        check("encyclopedia page for the cow", () -> JournalScreen.journal().pages().containsKey("creature/minecraft:cow"), 100);
        check("advancement: first photo", () -> advancement("root"), 40);

        // 2) the same cow out of focus: fewer stars, no encyclopedia credit for the creature
        give(0, () -> kit("sony_a7iv", CameraSettings.DEFAULT.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(1.8)).withFocusMode(CameraSettings.FOCUS_MF).withFocusDistance(0.6f)));
        shoot("blurred cow");
        step("listing 2", () -> CardListings.refresh(cardId()), 40);
        check("blurred cow analysed", () -> newest() != null && newest().analysis().isPresent()
                && newest().createdMillis() > cowPhoto.createdMillis(), 100);
        step("blur analysis", () -> {
            var a = newest().analysis().get();
            log(!a.shows("minecraft:cow") && a.stars() < cowPhoto.stars()
                    ? "PASS out of focus: cow not sharp, {} stars" : "FAIL blurred cow judged sharp ({} stars) {}", a.stars(), a.subjects());
        }, 1);

        // 3) sunset towards the west
        step("sunset", () -> server(p -> {
            cmd(p, "time set 12100");
            p.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 90f, -8f);
        }), 40);
        give(0, () -> kit("sony_a7iv", CameraSettings.DEFAULT.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(8)).withFocusMode(CameraSettings.FOCUS_MF).withFocusDistance(50f)));
        shoot("sunset");
        step("listing 3", () -> CardListings.refresh(cardId()), 40);
        check("sunset recognised", () -> newest() != null && newest().analysis().map(a -> a.phenomena().contains("sunset")).orElse(false), 100);
        check("advancement: golden hour", () -> advancement("sunset"), 40);
        check("encyclopedia page for the sunset", () -> JournalScreen.journal().pages().containsKey("phenomenon/sunset"), 60);
        step("day", () -> server(p -> {
            cmd(p, "time set 6000");
            p.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0f, 0f);
        }), 20);

        // 4) the encyclopedia screen
        step("journal", () -> Minecraft.getInstance().setScreen(new JournalScreen()), 60);
        screenshot("s7_01_journal");
        step("close", () -> Minecraft.getInstance().setScreen(null), 5);

        // 5) the newspaper editor: an order for a cow photo, paid for with a print of it
        step("editor", () -> server(p -> {
            var level = p.serverLevel();
            editor = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
            editor.moveTo(origin.getX() + 2.5, origin.getY(), origin.getZ() + 2.5, 200f, 0f);
            editor.setNoAi(true);
            editor.setVillagerData(editor.getVillagerData().setProfession(
                    dev.hackdanel.realcamera.gameplay.NewspaperEditor.EDITOR.get()).setLevel(1));
            level.addFreshEntity(editor);
            int orders = (int) editor.getOffers().stream().filter(o -> o.getItemCostA().itemStack()
                    .has(RCComponents.COMMISSION.get())).count();
            log(orders >= 1 ? "PASS the editor offers {} photo orders" : "FAIL editor orders: {}", orders);
            var cow = new dev.hackdanel.realcamera.component.Commission(java.util.Optional.of("minecraft:cow"),
                    java.util.Optional.empty(), java.util.Optional.empty(), 1, 1);
            var pig = new dev.hackdanel.realcamera.component.Commission(java.util.Optional.of("minecraft:pig"),
                    java.util.Optional.empty(), java.util.Optional.empty(), 1, 1);
            editor.getOffers().clear();
            editor.getOffers().add(dev.hackdanel.realcamera.gameplay.NewspaperEditor.offer(cow, 5, 2));
            PhotoMeta meta = PhotoStorage.index(p.server).get(cowPhoto.id()).meta();
            ItemStack print = new ItemStack(RCItems.PHOTO_PRINT.get());
            print.set(RCComponents.PRINT.get(), new PrintData(meta, "10x15", "glossy", 0, ""));
            boolean cowOk = editor.getOffers().get(0).satisfiedBy(print, ItemStack.EMPTY);
            boolean pigOk = dev.hackdanel.realcamera.gameplay.NewspaperEditor.offer(pig, 5, 2).satisfiedBy(print, ItemStack.EMPTY);
            log(cowOk && !pigOk ? "PASS a cow print fulfils the cow order, not the pig order"
                    : "FAIL order matching cow={} pig={}", cowOk, pigOk);
            p.getInventory().setItem(5, print);
        }), 20);
        step("talk", () -> {
            Minecraft mc = Minecraft.getInstance();
            var v = mc.level.getEntity(editor.getId());
            mc.gameMode.interact(mc.player, v, InteractionHand.MAIN_HAND);
        }, 40);
        check("trade screen open", () -> Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.inventory.MerchantScreen, 60);
        screenshot("s7_02_editor_trades");
        step("select order", () -> {
            Minecraft mc = Minecraft.getInstance();
            var menu = (net.minecraft.world.inventory.MerchantMenu) mc.player.containerMenu;
            menu.setSelectionHint(0);
            menu.tryMoveItems(0);
            mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(0));
        }, 20);
        step("take", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, 2, 0,
                    net.minecraft.world.inventory.ClickType.QUICK_MOVE, mc.player);
        }, 20);
        check("paid in emeralds", () -> Minecraft.getInstance().player.getInventory().countItem(net.minecraft.world.item.Items.EMERALD) >= 5, 60);
        check("advancement: published", () -> advancement("newspaper"), 60);
        step("close trade", () -> Minecraft.getInstance().player.closeContainer(), 10);

        // 6) prints and frames count towards advancements
        step("print event", () -> server(p -> dev.hackdanel.realcamera.gameplay.PhotoEvents.count(p, "prints")), 10);
        check("advancement: hard copy", () -> advancement("first_print"), 40);
        step("advancements screen", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new net.minecraft.client.gui.screens.advancements.AdvancementsScreen(mc.getConnection().getAdvancements()));
        }, 60);
        screenshot("s7_03_advancements");
        step("done", () -> {
            Minecraft.getInstance().setScreen(null);
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
            String name = "rc_stage7_" + System.currentTimeMillis();
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
