package dev.hackdanel.realcamera.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.item.RCStacks;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCRegistries;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
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
 * Stage 3 end-to-end run (-Drealcamera.autotest=stage3): flash, filters, tripod and self-timer,
 * battery grip, wear (moisture, dust, knocked lens, jammed shutter, water) and repairs.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Stage3Autotest {
    private static final boolean ENABLED = "stage3".equals(System.getProperty("realcamera.autotest"));

    /** A step runs once, then waits; a "until" step is polled every tick until true (with a timeout). */
    private record Step(String label, Runnable action, int delay, BooleanSupplier until, int timeout) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static boolean worldRequested, scripted;
    private static int wait, polled;
    private static String pendingScreenshot;
    private static BlockPos origin;
    private static int photosBefore;
    private static long flashFrom;

    private Stage3Autotest() {}

    static void log(String msg, Object... args) {
        RealCamera.LOGGER.info("[autotest] " + msg, args);
    }

    // ------------------------------------------------------------------ script helpers

    private static void step(String label, Runnable r, int waitTicks) {
        steps.add(new Step(label, r, waitTicks, null, 0));
    }

    private static void until(String label, BooleanSupplier cond, int timeout) {
        steps.add(new Step(label, () -> {}, 0, cond, timeout));
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

    /** Give a ready camera: body, lens, optional adapter/teleconverter, full battery, big card. */
    private static void camera(String body, String lens, String adapter, String tc, UnaryOperator<CameraSettings> settings) {
        camera(body, lens, adapter, tc, settings, c -> c);
    }

    /** As above, then {@code extra} may add a flash/filter/grip or wear to the stack. */
    private static void camera(String body, String lens, String adapter, String tc, UnaryOperator<CameraSettings> settings,
                               UnaryOperator<ItemStack> extra) {
        step("camera " + body + " + " + lens, () -> {
            giveCamera(body, lens, adapter, tc, settings);
            Minecraft mc = Minecraft.getInstance();
            ItemStack cam = extra.apply(mc.player.getInventory().getItem(0).copy());
            mc.player.getInventory().setItem(0, cam);
            mc.gameMode.handleCreativeModeItemAdd(cam, 36);
        }, 10);
    }

    private static ItemStack withPart(ItemStack cam, int slot, ItemStack part) {
        CameraHelper.setParts(cam, CameraHelper.parts(cam).with(slot, part));
        return cam;
    }

    private static ItemStack flashOn(ItemStack cam, String flash) {
        return withPart(cam, CameraParts.SLOT_FLASH, RCStacks.flash(key(RCRegistries.FLASH_TYPE, flash), 300));
    }

    private static ItemStack filterOn(ItemStack cam, String filter) {
        return withPart(cam, CameraParts.SLOT_FILTER, RCStacks.filter(key(RCRegistries.FILTER_TYPE, filter)));
    }

    private static ItemStack wear(ItemStack cam, dev.hackdanel.realcamera.component.CameraWear wear) {
        cam.set(RCComponents.WEAR.get(), wear);
        return cam;
    }

    /** Build the camera on the client and put it in slot 0 the way the creative inventory does. */
    private static void giveCamera(String body, String lens, String adapter, String tc, UnaryOperator<CameraSettings> settings) {
        Minecraft mc = Minecraft.getInstance();
        var regs = mc.level.registryAccess();
        var bodyRef = regs.lookupOrThrow(RCRegistries.CAMERA_BODY).getOrThrow(key(RCRegistries.CAMERA_BODY, body));
        ItemStack cam = RCStacks.kit(regs, bodyRef).orElseThrow();
        CameraParts parts = CameraHelper.parts(cam)
                .with(CameraParts.SLOT_LENS, RCStacks.lens(key(RCRegistries.LENS, lens)))
                .with(CameraParts.SLOT_ADAPTER, adapter == null ? ItemStack.EMPTY : RCStacks.adapter(key(RCRegistries.MOUNT_ADAPTER, adapter)))
                .with(CameraParts.SLOT_TC, tc == null ? ItemStack.EMPTY : RCStacks.teleconverter(key(RCRegistries.TELECONVERTER, tc)));
        CameraHelper.setParts(cam, parts);
        cam.set(RCComponents.CAMERA_SETTINGS.get(), settings.apply(CameraSettings.DEFAULT));
        mc.player.getInventory().setItem(0, cam);
        mc.gameMode.handleCreativeModeItemAdd(cam, 36); // hotbar slot 0 in the inventory menu
    }

    private static void raise() {
        step("raise", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder active", Viewfinder::isActive, 60);
        step("camera check", () -> log("camera ready: {} + {} at {} mm", Viewfinder.camera().getHoverName().getString(),
                Viewfinder.chain().exifLensModel(), Viewfinder.focal()), 1);
    }

    private static void lower() {
        step("lower", () -> Minecraft.getInstance().options.keyUse.setDown(false), 10);
    }

    private static void screenshot(String name) {
        step("screenshot " + name, () -> pendingScreenshot = name, 3);
    }

    private static void shoot(String name) {
        shoot(name, null);
    }

    /** As above; {@code flashShot} names a screenshot of the view right after the shot's own flash is shown. */
    private static void shoot(String name, String flashShot) {
        step("shoot " + name, () -> {
            photosBefore = exported();
            flashFrom = System.nanoTime();
            log("shooting {}", name);
            Viewfinder.debugPressShutter();
        }, 5);
        until("started " + name, PhotoCapture::busy, 80);
        if (flashShot != null) {
            until("flash seen " + name, () -> {
                var s = dev.hackdanel.realcamera.client.render.FlashGlow.debugLastShown();
                if (s == null || s.nanos() < flashFrom) return false;
                pendingScreenshot = flashShot;
                return true;
            }, 200);
        }
        until("stored " + name, () -> !PhotoCapture.busy(), 1200);
        step("check " + name, () -> {
            int n = exported();
            log(n > photosBefore ? "PASS {} -> {}" : "FAIL {} exported nothing", name, newest());
        }, 2);
    }

    /** Hold the shutter in a continuous drive mode for the given ticks. */
    private static void burst(String name, int ticks) {
        step("burst " + name, () -> {
            photosBefore = exported();
            Viewfinder.debugPressShutter();
            Minecraft.getInstance().options.keyAttack.setDown(true);
        }, ticks);
        step("release", () -> Minecraft.getInstance().options.keyAttack.setDown(false), 2);
        until("burst stored", () -> !PhotoCapture.busy(), 2400);
        step("check burst", () -> {
            int n = exported() - photosBefore;
            log(n >= 3 ? "PASS burst {} wrote {} photos" : "FAIL burst {} wrote only {} photos", name, n);
        }, 2);
    }

    private static String newest() {
        File dir = new File(Minecraft.getInstance().gameDirectory, "screenshots/realcamera");
        File best = null;
        File[] days = dir.listFiles();
        if (days != null) {
            for (File d : days) {
                File[] f = d.listFiles((x, n) -> n.endsWith(".JPG") || n.endsWith(".DNG"));
                if (f == null) continue;
                for (File file : f) {
                    if (best == null || file.lastModified() > best.lastModified()) best = file;
                }
            }
        }
        return best == null ? "-" : best.getName();
    }

    private static int exported() {
        File dir = new File(Minecraft.getInstance().gameDirectory, "screenshots/realcamera");
        int count = 0;
        File[] days = dir.listFiles();
        if (days != null) {
            for (File d : days) {
                File[] f = d.listFiles((x, n) -> n.endsWith(".JPG") || n.endsWith(".DNG"));
                if (f != null) count += f.length;
            }
        }
        return count;
    }

    private static void look(float yaw, float pitch) {
        step("look", () -> {
            Minecraft.getInstance().player.setYRot(yaw);
            Minecraft.getInstance().player.setXRot(pitch);
        }, 5);
    }

    // ------------------------------------------------------------------ the script

    private static void script() {
        step("scene", () -> server(p -> {
            origin = p.blockPosition();
            cmd(p, "time set 6000");
            cmd(p, "weather clear");
            cmd(p, "fill ~-8 ~-1 ~-2 ~8 ~-1 ~40 minecraft:grass_block");
            cmd(p, "fill ~-8 ~ ~-2 ~8 ~10 ~40 minecraft:air");
            cmd(p, "fill ~ ~ ~6 ~ ~1 ~6 minecraft:red_wool");
            cmd(p, "fill ~2 ~ ~14 ~2 ~3 ~14 minecraft:yellow_wool");
            cmd(p, "fill ~-3 ~ ~30 ~-3 ~5 ~30 minecraft:blue_wool");
            cmd(p, "summon minecraft:villager ~1.5 ~ ~5 {NoAI:1b,Rotation:[180f,0f]}");
            cmd(p, "tp @s ~ ~ ~ 0 5");
        }), 60);

        // 1) polariser and red filter in black and white (daylight)
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(8)).withFocal(28), c -> filterOn(c, "cpl_82"));
        look(0, -10);
        raise();
        shoot("s3_01_polariser");
        lower();
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(8)).withFocal(28).withProfile(4), c -> filterOn(c, "red_82"));
        raise();
        shoot("s3_02_red_filter_bw");
        lower();

        // 2) ND1000 in daylight: the camera picks a long exposure by itself
        look(0, 5);
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(11)).withFocal(35).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(8f).withIsoIndex(ExposureScales.nearestIso(100)), c -> filterOn(c, "nd1000_82"));
        raise();
        step("nd check", () -> log(Viewfinder.exposure().seconds() >= 0.25 ? "PASS ND1000 -> {} s" : "FAIL ND1000 only {} s",
                String.format("%.2f", Viewfinder.exposure().seconds())), 1);
        shoot("s3_03_nd1000");
        lower();

        // 3) wear: fog, dust at f/16, knocked lens
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(16)).withFocal(35),
                c -> {
                    var w = dev.hackdanel.realcamera.component.CameraWear.NEW.withMoisture(70)
                            .withDust(new dev.hackdanel.realcamera.component.CameraWear.Dust(-0.4f, 0.4f, 0.02f))
                            .withDust(new dev.hackdanel.realcamera.component.CameraWear.Dust(0.3f, 0.5f, 0.015f));
                    ItemStack lens = CameraHelper.parts(c).lens().copy();
                    lens.set(RCComponents.DAMAGE.get(), 0.8f);
                    return withPart(wear(c, w), CameraParts.SLOT_LENS, lens);
                });
        look(0, -15);
        raise();
        shoot("s3_04_fog_dust_knocked");
        lower();

        // 4) jammed shutter: refuses in every mode, the electronic shutter too (the camera shows an error)
        look(0, 5);
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_P),
                c -> wear(c, dev.hackdanel.realcamera.component.CameraWear.NEW.withJammed(true)));
        raise();
        step("jammed shot", () -> {
            photosBefore = exported();
            Viewfinder.debugPressShutter();
        }, 40);
        step("jammed check", () -> log(exported() == photosBefore ? "PASS jammed shutter refused" : "FAIL jammed shutter fired"), 1);
        lower();
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_P)
                .withShutterType(CameraSettings.SHUTTER_ELECTRONIC),
                c -> wear(c, dev.hackdanel.realcamera.component.CameraWear.NEW.withJammed(true)));
        raise();
        step("jammed electronic", () -> {
            photosBefore = exported();
            Viewfinder.debugPressShutter();
        }, 40);
        step("jammed electronic check", () -> log(exported() == photosBefore
                ? "PASS jammed shutter refused with the electronic shutter too" : "FAIL jammed camera fired electronically"), 1);
        lower();

        // 5) repair the jammed camera in the workshop
        step("repair", () -> server(p -> {
            BlockPos station = origin.offset(-3, 0, 1);
            p.serverLevel().setBlockAndUpdate(station, dev.hackdanel.realcamera.registry.RCBlocks.ASSEMBLY_STATION.get().defaultBlockState());
            var be = (dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity) p.serverLevel().getBlockEntity(station);
            ItemStack broken = p.getInventory().getItem(0).copy();
            be.items().setStackInSlot(0, broken);
            be.items().setStackInSlot(1, new ItemStack(dev.hackdanel.realcamera.registry.RCItems.SHUTTER_UNIT.get()));
        }), 230);
        step("repair check", () -> server(p -> {
            var be = (dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity) p.serverLevel()
                    .getBlockEntity(origin.offset(-3, 0, 1));
            ItemStack out = be.items().getStackInSlot(dev.hackdanel.realcamera.blockentity.AssemblyStationBlockEntity.OUTPUT);
            boolean ok = !out.isEmpty() && !CameraHelper.wear(out).shutterJammed() && CameraHelper.shutterCount(out) == 0
                    && out.has(RCComponents.CAMERA_PARTS.get());
            log(ok ? "PASS repaired camera keeps its parts, shutter fixed" : "FAIL repair produced {}", out);
        }), 5);

        // 6) battery grip is used first
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_P),
                c -> withPart(c, CameraParts.SLOT_GRIP, RCStacks.grip(key(RCRegistries.GRIP_TYPE, "sony_vg_c4em"), 580)));
        raise();
        shoot("s3_06_with_grip");
        step("grip check", () -> {
            var cam = Viewfinder.camera();
            var bat = CameraHelper.charge(cam);
            var grip = CameraHelper.gripCharge(cam);
            log(bat.charge() == bat.capacity() && grip.charge() < grip.capacity()
                    ? "PASS grip drained first (battery {} grip {})" : "FAIL battery {} grip {}", bat.charge(), grip.charge());
        }, 1);
        lower();

        // 7) tripod self-timer selfie: walk into the frame after pressing
        step("tripod", () -> server(p -> {
            cmd(p, "kill @e[type=minecraft:villager]");
            var t = new dev.hackdanel.realcamera.entity.TripodEntity(p.serverLevel(), net.minecraft.world.phys.Vec3.atBottomCenterOf(origin), 0);
            p.serverLevel().addFreshEntity(t);
            TRIPOD_ID = t.getId();
        }), 10);
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_P).withFocal(35)
                .withDrive(CameraSettings.DRIVE_TIMER_10).withFocusMode(CameraSettings.FOCUS_MF).withFocusDistance(4f));
        step("mount", () -> {
            Minecraft mc = Minecraft.getInstance();
            var t = mc.level.getEntity(TRIPOD_ID);
            mc.gameMode.interact(mc.player, t, InteractionHand.MAIN_HAND);
        }, 20);
        step("look through", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 1; // empty hand
            var t = mc.level.getEntity(TRIPOD_ID);
            mc.gameMode.interact(mc.player, t, InteractionHand.MAIN_HAND);
        }, 20);
        until("tripod view", Viewfinder::isActive, 60);
        screenshot("s3_07_tripod_view");
        step("start timer", () -> {
            photosBefore = exported();
            Viewfinder.debugPressShutter();
        }, 5);
        step("step out", () -> {
            dev.hackdanel.realcamera.client.camera.TripodView.exit();
            server(p -> cmd(p, "tp @s ~ ~ ~4 180 0"));
        }, 260);
        until("selfie stored", () -> !PhotoCapture.busy(), 600);
        step("selfie check", () -> log(exported() > photosBefore ? "PASS selfie -> {}" : "FAIL selfie not taken", newest()), 5);
        // 7b) the head tilted up and down: the view, the photo and the model seen from the side all agree (issue #11)
        tripodTilt("up", -40);
        tripodTilt("down", 40);

        // 8) night: flash on a subject, built-in flash above sync speed (curtain shadow)
        step("night", () -> server(p -> {
            cmd(p, "time set 18000");
            cmd(p, "tp @s " + origin.getX() + " " + origin.getY() + " " + origin.getZ() + " 0 5");
            cmd(p, "summon minecraft:villager " + (origin.getX() + 1.5) + " " + origin.getY() + " " + (origin.getZ() + 4)
                    + " {NoAI:1b,Rotation:[180f,0f]}");
        }), 40);
        look(-18, 6);
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_M)
                .withShutterIndex(ExposureScales.nearestShutter(1 / 125.0)).withApertureIndex(ExposureScales.nearestAperture(5.6))
                .withIsoIndex(ExposureScales.nearestIso(400)).withFocal(35).withFlashMode(CameraSettings.FLASH_FILL),
                c -> flashOn(c, "godox_v1"));
        raise();
        // the photographer's own flash lights the scene, but leaves neither a glare nor an afterimage (issue #9)
        shoot("s3_08_flash_night", "s3_08d_own_flash");
        flashCheck("own", true);
        lower();
        camera("nikon_d750", "nikon_afs_50_f18g", null, null, s -> s.withMode(CameraSettings.MODE_M)
                .withShutterIndex(ExposureScales.nearestShutter(1 / 1000.0)).withApertureIndex(ExposureScales.nearestAperture(2.8))
                .withIsoIndex(ExposureScales.nearestIso(800)).withFlashMode(CameraSettings.FLASH_FILL));
        raise();
        shoot("s3_09_builtin_above_sync");
        lower();
        // 8b) someone else's flash 6 m ahead, pointing back at this player: lit scene, glare, then an afterimage
        screenshot("s3_08a_before_remote_flash");
        step("remote flash", () -> {
            Minecraft mc = Minecraft.getInstance();
            var eye = mc.player.getEyePosition();
            var look = mc.player.getLookAngle();
            var pos = eye.add(look.scale(6));
            flashFrom = System.nanoTime();
            dev.hackdanel.realcamera.client.render.FlashGlow.onPop(new dev.hackdanel.realcamera.network.Payloads.FlashPop(
                    -1, pos.x, pos.y, pos.z, (float) -look.x, (float) -look.y, (float) -look.z, 40));
            pendingScreenshot = "s3_08b_remote_flash";
        }, 30);
        flashCheck("remote", false);
        screenshot("s3_08c_afterimage");

        // 9) water kills an unsealed body
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s);
        step("dive", () -> server(p -> {
            cmd(p, "fill ~-1 ~-1 ~-1 ~1 ~2 ~1 minecraft:water");
            cmd(p, "tp @s ~ ~ ~");
        }), 220);
        step("water check", () -> {
            var w = CameraHelper.wear(Minecraft.getInstance().player.getInventory().getItem(0));
            log(w.dead() ? "PASS camera died in water (moisture {})" : "FAIL camera survived water (moisture {})", w.moisture());
            server(p -> cmd(p, "fill ~-1 ~-1 ~-1 ~1 ~2 ~1 minecraft:air"));
        }, 20);

        // 10) camera bag screen
        step("bag", () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack bag = new ItemStack(dev.hackdanel.realcamera.registry.RCItems.CAMERA_BAG.get());
            mc.player.getInventory().setItem(2, bag);
            mc.gameMode.handleCreativeModeItemAdd(bag, 38);
            mc.player.getInventory().selected = 2;
        }, 10);
        step("open bag", () -> Minecraft.getInstance().gameMode.useItem(Minecraft.getInstance().player, InteractionHand.MAIN_HAND), 15);
        screenshot("s3_10_bag");
        step("close", () -> Minecraft.getInstance().player.closeContainer(), 5);
        step("done", () -> {
            log("done, stopping");
            Minecraft.getInstance().stop();
        }, 100);
    }

    /** How the last flash looked to this player: their own one only lights the scene, someone else's glares. */
    private static void flashCheck(String name, boolean own) {
        step("flash check " + name, () -> {
            var s = dev.hackdanel.realcamera.client.render.FlashGlow.debugLastShown();
            boolean ok = s != null && s.nanos() >= flashFrom && s.own() == own
                    && (own ? s.glare() == 0 && s.afterimage() == 0 : s.glare() > 0 && s.afterimage() > 0);
            log((ok ? "PASS" : "FAIL") + " {} flash as seen: {}", name, s);
        }, 2);
    }

    /** Look through the tripod, tilt the head to {@code pitch}, shoot (self-timer), then look at it from the side. */
    private static void tripodTilt(String name, float pitch) {
        step("tilt " + name, () -> server(p -> cmd(p, "tp @s " + (origin.getX() + 0.5) + " " + origin.getY() + " "
                + (origin.getZ() - 0.5) + " 0 0")), 10);
        step("look through " + name, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 1;
            mc.gameMode.interact(mc.player, mc.level.getEntity(TRIPOD_ID), InteractionHand.MAIN_HAND);
        }, 10);
        step("aim " + name, () -> Minecraft.getInstance().player.setXRot(pitch), 20);
        until("tripod view " + name, Viewfinder::isActive, 60);
        screenshot("s3_07b_tripod_" + name + "_view");
        step("shoot " + name, () -> {
            photosBefore = exported();
            Viewfinder.debugPressShutter();
        }, 230);
        until("stored " + name, () -> !PhotoCapture.busy(), 600);
        step("check " + name, () -> {
            var t = Minecraft.getInstance().level.getEntity(TRIPOD_ID);
            log(exported() > photosBefore && Math.abs(t.getXRot() - pitch) < 1
                    ? "PASS tripod {} -> {} (head pitch {})" : "FAIL tripod {}: {} (head pitch {})", name, newest(), t.getXRot());
            dev.hackdanel.realcamera.client.camera.TripodView.exit();
            server(p -> cmd(p, "tp @s " + (origin.getX() + 3.5) + " " + origin.getY() + " " + (origin.getZ() + 0.5) + " 90 10"));
        }, 30);
        screenshot("s3_07c_tripod_" + name + "_side");
    }

    private static int TRIPOD_ID;

    /** Degrees per tick the autotest turns the player (simulated pan). */
    private static float PANNING;

    // ------------------------------------------------------------------ driver

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (!worldRequested && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            worldRequested = true;
            String name = "rc_stage3_" + System.currentTimeMillis();
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
            if (++polled < 200) return; // chunks
            scripted = true;
            polled = 0;
            script();
        }
        if (PANNING != 0) {
            mc.player.setYRot(mc.player.getYRot() + PANNING);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Step s = steps.peekFirst();
        if (s == null) return;
        if (s.until() != null) {
            if (s.until().getAsBoolean()) {
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
