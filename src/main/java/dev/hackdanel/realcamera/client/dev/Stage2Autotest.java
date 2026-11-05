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
 * Stage 2 end-to-end run (-Drealcamera.autotest=stage2): optics, adapters, viewfinder styles, long
 * exposures, bursts, flare, subject detection. Each scenario sets up a camera, raises it, shoots
 * and screenshots the viewfinder; photos land in screenshots/realcamera/ with readable names in the log.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Stage2Autotest {
    private static final boolean ENABLED = "stage2".equals(System.getProperty("realcamera.autotest"));

    /** A step runs once, then waits; a "until" step is polled every tick until true (with a timeout). */
    private record Step(String label, Runnable action, int delay, BooleanSupplier until, int timeout) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static boolean worldRequested, scripted;
    private static int wait, polled;
    private static String pendingScreenshot;
    private static BlockPos origin;
    private static int photosBefore;

    private Stage2Autotest() {}

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
        step("camera " + body + " + " + lens, () -> giveCamera(body, lens, adapter, tc, settings), 10);
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
        step("shoot " + name, () -> {
            photosBefore = exported();
            log("shooting {}", name);
            Viewfinder.debugPressShutter();
        }, 5);
        until("started " + name, PhotoCapture::busy, 80);
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
                File[] f = d.listFiles((x, n) -> n.endsWith(".JPG") || n.endsWith(".PNG"));
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
                File[] f = d.listFiles((x, n) -> n.endsWith(".JPG") || n.endsWith(".PNG"));
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
            // lamps far behind the subject for bokeh at night
            for (int x = -6; x <= 6; x += 2) {
                cmd(p, "setblock ~" + x + " ~" + (2 + Math.abs(x) % 3) + " ~36 minecraft:sea_lantern");
            }
            cmd(p, "summon minecraft:villager ~1.5 ~ ~5 {NoAI:1b,Rotation:[180f,0f]}");
            cmd(p, "tp @s ~ ~ ~ 0 5");
        }), 60);

        // 1) EVF basics with the kit zoom, electronic shutter, burst
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(4)).withFocal(28));
        raise();
        screenshot("s2_01_evf_wide");
        shoot("s2_01_wide_28mm");
        lower();

        // 2) subject detection on the villager, eye AF
        camera("sony_a7iv", "sony_fe_85_f18", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(1.8)).withAfArea(CameraSettings.AF_WIDE));
        look(-17, 6);
        raise();
        step("half-press for AF", Viewfinder::debugPressShutter, 30);
        step("subject check", () -> log(Viewfinder.subject() != null ? "PASS subject detected: {}" : "FAIL no subject detected",
                Viewfinder.subject() == null ? "-" : Viewfinder.subject().entity().getType().toShortString()), 1);
        screenshot("s2_02_eye_af");
        until("stored", () -> !PhotoCapture.busy(), 1200);
        lower();

        // 3) manual focus with peaking and magnifier
        look(0, 5);
        camera("sony_a7iv", "sony_fe_85_f18", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(1.8)).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(5.5f));
        raise();
        step("wait peaking", () -> {}, 12);
        screenshot("s2_03_peaking");
        step("magnify", () -> net.minecraft.client.KeyMapping.click(dev.hackdanel.realcamera.client.RCKeys.MAGNIFY.getKey()), 6);
        step("magnify check", () -> log(Viewfinder.magnified() ? "PASS magnifier on" : "FAIL magnifier off"), 1);
        screenshot("s2_03b_magnified");
        lower();

        // 4) burst, electronic shutter, while panning (rolling shutter)
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_S)
                .withShutterIndex(ExposureScales.nearestShutter(1 / 500.0)).withDrive(CameraSettings.DRIVE_CONT_MAX)
                .withShutterType(CameraSettings.SHUTTER_ELECTRONIC).withFocusMode(CameraSettings.FOCUS_MF).withFocusDistance(8f));
        raise();
        step("start pan", () -> PANNING = 6f, 1);
        burst("s2_04_electronic_burst", 20);
        step("stop pan", () -> PANNING = 0, 1);
        lower();

        // 5) adapters: Canon EF on Sony via MC-11 (slow AF), Nikon F via dumb adapter (MF only)
        look(0, 5);
        camera("sony_a7iv", "canon_ef_50_f18_stm", "sigma_mc11_ef_e", null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(2.8)));
        raise();
        shoot("s2_05_adapted_canon");
        screenshot("s2_05_adapted_canon_hud");
        lower();
        camera("sony_a7iv", "nikon_afs_50_f18g", "f_to_e", null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(2.8)).withFocusDistance(6f));
        raise();
        screenshot("s2_06_adapted_nikon_mf");
        step("af check", () -> log(Viewfinder.afAvailable() ? "FAIL dumb adapter allowed AF" : "PASS dumb adapter forces MF"), 1);
        lower();

        // 6) teleconverter on the 200-600: 1200 mm
        camera("sony_a7iv", "sony_fe_200_600_g", null, "sony_sel20tc", s -> s.withMode(CameraSettings.MODE_P).withFocal(1200));
        look(-8, 2);
        raise();
        step("tc check", () -> log(Math.abs(Viewfinder.focal() - 1200) < 1 ? "PASS 600 mm x2 = {} mm" : "FAIL focal {}",
                Viewfinder.focal()), 1);
        screenshot("s2_07_1200mm");
        shoot("s2_07_1200mm");
        lower();

        // 7) APS-C lens on full frame: crop auto, then crop off (image circle)
        look(0, 5);
        camera("sony_a7iv", "sony_e_pz_16_50", null, null, s -> s.withMode(CameraSettings.MODE_P).withFocal(16)
                .withLensCorrection(false));
        raise();
        shoot("s2_08_apsc_crop");
        lower();
        camera("sony_a7iv", "sony_e_pz_16_50", null, null, s -> s.withMode(CameraSettings.MODE_P).withFocal(16)
                .withCropMode(CameraSettings.CROP_OFF).withLensCorrection(false));
        raise();
        shoot("s2_09_apsc_no_crop");
        lower();

        // 8) Nikon D750 optical viewfinder, Leica M11 rangefinder
        camera("nikon_d750", "nikon_afs_50_f18g", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(2.8)));
        raise();
        screenshot("s2_10_ovf");
        shoot("s2_10_d750");
        lower();
        camera("leica_m11", "leica_summicron_m_50_f2", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(2)).withFocusDistance(4f).withFocusMode(CameraSettings.FOCUS_MF));
        raise();
        screenshot("s2_11_rangefinder");
        shoot("s2_11_m11_raw_jpeg");
        lower();

        // 9) sun flare: low morning sun in the east, stopped down
        step("morning", () -> server(p -> cmd(p, "time set 3000")), 40);
        look(-90, -40);
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(11)).withFocal(28).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(10f).withFileFormat(CameraSettings.FORMAT_RAW_JPEG));
        raise();
        screenshot("s2_12_sun_evf");
        step("sun check", () -> {
            var mc = Minecraft.getInstance();
            var sun = dev.hackdanel.realcamera.client.camera.SunTracker.sun(mc.level, mc.getCameraEntity(),
                    mc.gameRenderer.getMainCamera(), Viewfinder.sensorVfov(), Viewfinder.sensor().aspect());
            log(sun.strength() > 0 ? "PASS sun in frame at {}, {} (strength {})" : "FAIL sun not in frame",
                    String.format("%.2f", sun.x()), String.format("%.2f", sun.y()), String.format("%.2f", sun.strength()));
        }, 1);
        shoot("s2_12_sun_flare");
        lower();

        // 10) night: bokeh of lanterns, long handheld exposure, star trails
        step("night", () -> server(p -> cmd(p, "time set 18000")), 40);
        look(0, 3);
        camera("sony_a7iv", "sony_fe_85_f18", null, null, s -> s.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(1.8)).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(6f));
        raise();
        shoot("s2_13_night_bokeh");
        lower();
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_M)
                .withShutterIndex(ExposureScales.nearestShutter(2)).withApertureIndex(ExposureScales.nearestAperture(4))
                .withIsoIndex(ExposureScales.nearestIso(1600)).withFocal(28).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(10f));
        raise();
        step("pan", () -> PANNING = 4f, 1);
        shoot("s2_14_long_pan_2s");
        step("stop", () -> PANNING = 0, 1);
        lower();
        look(30, -70);
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_M)
                .withShutterIndex(ExposureScales.nearestShutter(8)).withApertureIndex(ExposureScales.nearestAperture(2.8 * 1.25))
                .withIsoIndex(ExposureScales.nearestIso(3200)).withFocal(28).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(1000f).withStabilization(true));
        raise();
        shoot("s2_15_star_trails_8s");
        lower();
        // bulb: hold the shutter for 3 seconds
        look(0, 3);
        camera("sony_a7iv", "sony_fe_28_70_oss", null, null, s -> s.withMode(CameraSettings.MODE_M)
                .withShutterIndex(ExposureScales.BULB_INDEX).withApertureIndex(ExposureScales.nearestAperture(8))
                .withIsoIndex(ExposureScales.nearestIso(800)).withFocal(35).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(10f));
        raise();
        step("bulb open", () -> {
            photosBefore = exported();
            Viewfinder.debugPressShutter();
            Minecraft.getInstance().options.keyAttack.setDown(true);
        }, 60);
        step("bulb close", () -> Minecraft.getInstance().options.keyAttack.setDown(false), 5);
        until("bulb stored", () -> !PhotoCapture.busy(), 1200);
        step("bulb check", () -> log(exported() > photosBefore ? "PASS bulb -> {}" : "FAIL bulb exported nothing", newest()), 2);
        lower();
        step("done", () -> {
            log("done, stopping");
            Minecraft.getInstance().stop();
        }, 100);
    }

    /** Degrees per tick the autotest turns the player (simulated pan). */
    private static float PANNING;

    // ------------------------------------------------------------------ driver

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (!worldRequested && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            worldRequested = true;
            String name = "rc_stage2_" + System.currentTimeMillis();
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
