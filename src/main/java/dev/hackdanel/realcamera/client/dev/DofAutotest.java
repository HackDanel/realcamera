package dev.hackdanel.realcamera.client.dev;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.item.RCStacks;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCRegistries;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
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

/**
 * Depth-of-field check (-Drealcamera.autotest=dof): a low-angle shot across a grass meadow at f/1.8 with a cow in
 * focus, then focused on the grass close by. Look at the photos and realcamera_debug/depth*.jpg afterwards.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class DofAutotest {
    private static final boolean ENABLED = "dof".equals(System.getProperty("realcamera.autotest"));

    private record Step(String label, Runnable action, int delay, BooleanSupplier until, int timeout) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static boolean worldRequested, scripted;
    private static volatile String pendingScreenshot;
    private static int wait, polled;

    private DofAutotest() {}

    static void log(String msg, Object... args) {
        RealCamera.LOGGER.info("[autotest] " + msg, args);
    }

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

    private static ItemStack camera(String lens, double fNumber, float focusM) {
        return camera("sony_a7iv", lens, null, fNumber, focusM);
    }

    private static ItemStack camera(String bodyKey, String lens, String adapter, double fNumber, float focusM) {
        Minecraft mc = Minecraft.getInstance();
        var regs = mc.level.registryAccess();
        var body = regs.lookupOrThrow(RCRegistries.CAMERA_BODY)
                .getOrThrow(ResourceKey.create(RCRegistries.CAMERA_BODY, RealCamera.id(bodyKey)));
        ItemStack cam = RCStacks.kit(regs, body).orElseThrow();
        CameraParts parts = cam.get(RCComponents.CAMERA_PARTS.get()).with(CameraParts.SLOT_LENS,
                RCStacks.lens(ResourceKey.create(RCRegistries.LENS, RealCamera.id(lens))));
        if (adapter != null) {
            parts = parts.with(CameraParts.SLOT_ADAPTER,
                    RCStacks.adapter(ResourceKey.create(RCRegistries.MOUNT_ADAPTER, RealCamera.id(adapter))));
        }
        cam.set(RCComponents.CAMERA_PARTS.get(), parts);
        cam.set(RCComponents.CAMERA_SETTINGS.get(), CameraSettings.DEFAULT.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(fNumber)).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(focusM));
        return cam;
    }

    private static void shoot(String label, String lens, double fNumber, float focusM) {
        shoot(label, "sony_a7iv", lens, null, fNumber, focusM);
    }

    private static void shoot(String label, String body, String lens, String adapter, double fNumber, float focusM) {
        step("give " + label, () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = camera(body, lens, adapter, fNumber, focusM);
            mc.player.getInventory().setItem(0, s);
            mc.gameMode.handleCreativeModeItemAdd(s, 36);
        }, 10);
        step("raise " + label, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder " + label, Viewfinder::isActive, 60);
        step("settle " + label, () -> {}, 30);
        step("viewfinder screenshot " + label, () -> pendingScreenshot = "vf_" + label.replace(' ', '_'), 5);
        step("shoot " + label, Viewfinder::debugPressShutter, 5);
        until("started " + label, PhotoCapture::busy, 80);
        until("stored " + label, () -> !PhotoCapture.busy(), 1200);
        step("lower " + label, () -> {
            Minecraft.getInstance().options.keyUse.setDown(false);
            log("PASS shot {}", label);
        }, 40);
    }

    private static String f4(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    /** Aim, log the contrast the AF sees, focus; {@code locks} null = only report. */
    private static void afCheck(String label, float pitch, Boolean locks) {
        step("aim " + label, () -> {
            var p = Minecraft.getInstance().player;
            p.setXRot(pitch);
            p.xRotO = pitch;
        }, 30);
        for (int i = 0; i < 3; i++) {
            step("contrast " + label, () -> log("contrast {} = {} (blocks 1/2/3/4: {} {} {} {})", label,
                    f4(dev.hackdanel.realcamera.client.hud.FrameSampler.contrastAt(0, 0, 0.06)),
                    f4(dev.hackdanel.realcamera.client.hud.FrameSampler.contrastAt(0, 0, 0.06, 1)),
                    f4(dev.hackdanel.realcamera.client.hud.FrameSampler.contrastAt(0, 0, 0.06, 2)),
                    f4(dev.hackdanel.realcamera.client.hud.FrameSampler.contrastAt(0, 0, 0.06, 3)),
                    f4(dev.hackdanel.realcamera.client.hud.FrameSampler.contrastAt(0, 0, 0.06, 4))), 5);
        }
        step("focus " + label, Viewfinder::debugPressShutter, 6);
        step("mid-focus screenshot " + label, () -> pendingScreenshot = "af_" + label, 2);
        until("af done " + label, () -> Viewfinder.afState() == Viewfinder.AF_LOCKED || Viewfinder.afState() == Viewfinder.AF_FAILED, 120);
        step("result " + label, () -> {
            boolean locked = Viewfinder.afState() == Viewfinder.AF_LOCKED;
            String what = locked ? "locked at " + Viewfinder.focusDistance() + " m" : "failed";
            if (locks == null) {
                log("INFO af {}: {}", label, what);
            } else {
                log(locked == locks ? "PASS af {}: {}" : "FAIL af {}: {}", label, what);
            }
        }, 2);
        until("stored " + label, () -> !PhotoCapture.busy(), 1200);
        step("after " + label, () -> {}, 20);
    }

    private static void script() {
        step("scene", () -> server(p -> {
            BlockPos o = p.blockPosition();
            cmd(p, "time set 6000");
            cmd(p, "weather clear");
            cmd(p, "fill ~-16 ~-3 ~-6 ~16 ~-1 ~40 minecraft:dirt");
            cmd(p, "fill ~-16 ~-1 ~-6 ~16 ~-1 ~40 minecraft:grass_block");
            cmd(p, "fill ~-16 ~ ~-6 ~16 ~20 ~40 minecraft:air");
            cmd(p, "fill ~-16 ~ ~-6 ~16 ~ ~40 minecraft:short_grass");
            for (int i = 0; i < 40; i++) {
                int x = (i * 7) % 29 - 14, z = (i * 13) % 40;
                String block = i % 5 == 0 ? "minecraft:poppy" : i % 5 == 1 ? "minecraft:dandelion" : "minecraft:tall_grass";
                cmd(p, String.format("setblock ~%d ~ ~%d %s", x, z, block));
            }
            cmd(p, "fill ~-1 ~ ~5 ~1 ~ ~7 minecraft:air");
            cmd(p, "summon minecraft:cow ~0.5 ~ ~6 {NoAI:1b,Rotation:[90f,0f]}");
            cmd(p, String.format("tp @s %d.5 %d %d.5 0 12", o.getX(), o.getY(), o.getZ()));
        }), 80);
        shoot("cow f1.8", "sony_fe_85_f18", 1.8, 6.0f);
        shoot("near grass f1.8", "sony_fe_50_f18", 1.8, 2.5f);
        shoot("cow f8", "sony_fe_85_f18", 8, 6.0f);
        // an SLR's optical finder: wide open until the depth-of-field preview is held; the depth-of-field scale
        step("give ovf", () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = camera("nikon_d750", "nikon_afs_24_120_f4", null, 11, 6.0f);
            mc.player.getInventory().setItem(0, s);
            mc.gameMode.handleCreativeModeItemAdd(s, 36);
        }, 10);
        step("raise ovf", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder ovf", Viewfinder::isActive, 60);
        step("settle ovf", Viewfinder::debugTouchFocusRing, 10);
        step("ovf open screenshot", () -> pendingScreenshot = "vf_ovf_open_scale", 10);
        step("dof preview", () -> dev.hackdanel.realcamera.client.RCKeys.DOF_PREVIEW.setDown(true), 20);
        step("ovf dof screenshot", () -> pendingScreenshot = "vf_ovf_dof", 10);
        step("lower ovf", () -> {
            dev.hackdanel.realcamera.client.RCKeys.DOF_PREVIEW.setDown(false);
            Minecraft.getInstance().options.keyUse.setDown(false);
            log("PASS ovf finder");
        }, 30);
        // focus shift series: five frames from 2 m outwards (2, 2.3, 2.7, 3.3, 4.3 m at f/2.8, step 10)
        step("give fs", () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = camera("sony_fe_50_f18", 2.8, 2.0f);
            s.set(RCComponents.CAMERA_SETTINGS.get(), s.get(RCComponents.CAMERA_SETTINGS.get()).withFocusShots(5).withFocusStep(10));
            mc.player.getInventory().setItem(0, s);
            mc.gameMode.handleCreativeModeItemAdd(s, 36);
            mc.player.setXRot(18);
            mc.player.xRotO = 18;
        }, 10);
        step("raise fs", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder fs", Viewfinder::isActive, 60);
        step("settle fs", () -> {}, 30);
        step("shoot fs", Viewfinder::debugPressShutter, 120);
        until("stored fs", () -> !PhotoCapture.busy() && "FS 5".equals(Viewfinder.focusShiftLabel()), 1200);
        step("lower fs", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUse.setDown(false);
            mc.player.setXRot(12);
            mc.player.xRotO = 12;
            log("PASS focus shift series");
        }, 40);
        // lens character: Helios swirl and soft background, STF apodisation
        shoot("helios f2", "sony_a7iv", "helios_44_2", "m42_to_e", 2, 6.0f);
        shoot("stf f2.8", "sony_fe_100_stf", 2.8, 6.0f);
        // autofocus: detail locks, a clear sky makes the lens hunt and give up
        step("wall", () -> server(p -> cmd(p, "fill ~-8 ~ ~14 ~8 ~8 ~14 minecraft:white_concrete")), 40);
        step("give af", () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = camera("sony_fe_50_f18", 1.8, 1000f);
            s.set(RCComponents.CAMERA_SETTINGS.get(), s.get(RCComponents.CAMERA_SETTINGS.get())
                    .withFocusMode(CameraSettings.FOCUS_AFS).withAfArea(CameraSettings.AF_POINT).withAfX(0).withAfY(0));
            mc.player.getInventory().setItem(0, s);
            mc.gameMode.handleCreativeModeItemAdd(s, 36);
        }, 10);
        step("raise af", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder af", Viewfinder::isActive, 60);
        afCheck("grass", 30, true);
        afCheck("wall", -4, false);
        step("stone wall", () -> server(p -> cmd(p, "fill ~-8 ~ ~14 ~8 ~8 ~14 minecraft:cobblestone")), 40);
        afCheck("stone", -4, true);
        afCheck("sky", -70, false);
        step("lower af", () -> Minecraft.getInstance().options.keyUse.setDown(false), 20);
        step("night", () -> server(p -> {
            cmd(p, "fill ~-8 ~ ~14 ~8 ~8 ~14 minecraft:air");
            cmd(p, "setblock ~2 ~ ~4 minecraft:torch");
            cmd(p, "setblock ~-2 ~ ~8 minecraft:torch");
            // lamps far behind the cow (bokeh discs) and one close to the lens (front bokeh)
            for (int i = 0; i < 7; i++) {
                cmd(p, String.format("setblock ~%d ~%d ~%d minecraft:glowstone", i * 2 - 6, 1 + i % 3, 18 + i * 3));
            }
            cmd(p, "setblock ~1 ~1 ~30 minecraft:sea_lantern");
            cmd(p, "setblock ~-1 ~2 ~34 minecraft:sea_lantern");
            cmd(p, "setblock ~0 ~1 ~40 minecraft:glowstone");
            cmd(p, "setblock ~1 ~ ~2 minecraft:lantern");
            // small flames far away for the mirror lens's ring bokeh
            for (int i = 0; i < 5; i++) {
                int x = i - 2, y = 1 + (i * 2) % 4, z = 64 + i * 3;
                cmd(p, String.format("fill ~%d ~-1 ~%d ~%d ~%d ~%d minecraft:stone", x, z, x, y - 1, z));
                cmd(p, String.format("setblock ~%d ~%d ~%d minecraft:torch", x, y, z));
            }
            cmd(p, "time set 18000");
        }), 60);
        step("level view", () -> {
            var pl = Minecraft.getInstance().player;
            pl.setXRot(6);
            pl.xRotO = 6;
        }, 10);
        shoot("night cow f1.8", "sony_fe_85_f18", 1.8, 6.0f);
        shoot("night f5.6", "sony_fe_85_f18", 5.6, 6.0f);
        shoot("night helios f2", "sony_a7iv", "helios_44_2", "m42_to_e", 2, 6.0f);
        shoot("night stf f2.8", "sony_fe_100_stf", 2.8, 6.0f);
        step("mirror view", () -> {
            var pl = Minecraft.getInstance().player;
            pl.setXRot(-1);
            pl.xRotO = -1;
        }, 10);
        shoot("night mirror f8", "nikon_z6iii", "nikon_reflex_500_f8", "nikon_ftz2", 8, 15.0f);
        // through water, stained glass and smoke (best with shaders off: a shader pack keeps its own depth)
        step("pond scene", () -> server(p -> {
            cmd(p, "time set 6000");
            cmd(p, "fill ~-8 ~ ~1 ~-4 ~1 ~7 minecraft:air");
            cmd(p, "fill ~-8 ~-4 ~1 ~-4 ~-4 ~7 minecraft:sand");
            for (int i = 0; i < 6; i++) {
                cmd(p, String.format("setblock ~%d ~-4 ~%d minecraft:%s", -8 + (i * 3) % 5, 1 + i, i % 2 == 0 ? "gold_block" : "lapis_block"));
            }
            cmd(p, "fill ~-8 ~-3 ~1 ~-4 ~-1 ~7 minecraft:water");
            cmd(p, "tp @s ~-6 ~ ~ 0 40");
        }), 60);
        // surface 2.7 m away along the view, the bottom 4.5 m further down: seen at 2.7 + 4.5 / 1.33 = 6.1 m
        shoot("pond f2.8", "sony_fe_50_f18", 2.8, 6.1f);
        step("glass scene", () -> server(p -> {
            cmd(p, "tp @s ~6 ~ ~ 0 8");
            cmd(p, "fill ~-3 ~ ~4 ~3 ~3 ~4 minecraft:light_blue_stained_glass");
        }), 40);
        step("glass view", () -> {
            var pl = Minecraft.getInstance().player;
            pl.setXRot(8);
            pl.xRotO = 8;
        }, 10);
        shoot("glass f1.8", "sony_fe_85_f18", 1.8, 6.0f);
        // campfire smoke right in front of the lens: billboards with their own depth, blurred into soft clouds
        step("smoke", () -> server(p -> cmd(p, "setblock ~0 ~ ~1 minecraft:campfire")), 100);
        shoot("smoke f1.8", "sony_fe_85_f18", 1.8, 6.0f);
        // rain (Fast/Fancy draw it without depth; LevelRendererWeatherMixin): the drops near the lens blur
        step("rain", () -> server(p -> {
            cmd(p, "setblock ~0 ~ ~1 minecraft:air");
            cmd(p, "fill ~-3 ~ ~4 ~3 ~3 ~4 minecraft:air");
            cmd(p, "fillbiome ~-16 ~-8 ~-16 ~16 ~8 ~16 minecraft:plains"); // the meadow is a savanna: no rain there
            cmd(p, "weather rain");
        }), 160);
        shoot("rain f1.8", "sony_fe_85_f18", 1.8, 6.0f);
        step("rain stop", () -> server(p -> cmd(p, "weather clear")), 5);
        step("done", () -> {
            log("done, stopping");
            Minecraft.getInstance().stop();
        }, 40);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (!worldRequested && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            worldRequested = true;
            String name = "rc_dof_" + System.currentTimeMillis();
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            mc.createWorldOpenFlows().createFreshLevel(name, new LevelSettings(name, GameType.CREATIVE, false,
                            Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(20261106L, false, false), WorldPresets::createNormalWorldDimensions, new TitleScreen());
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
    static void onFramePost(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        if (!ENABLED || pendingScreenshot == null) return;
        String name = pendingScreenshot;
        pendingScreenshot = null;
        Minecraft mc = Minecraft.getInstance();
        com.mojang.blaze3d.platform.NativeImage img = net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget());
        java.io.File dir = new java.io.File(mc.gameDirectory, "screenshots/autotest");
        net.minecraft.Util.ioPool().execute(() -> {
            try (img) {
                dir.mkdirs();
                img.writeToFile(new java.io.File(dir, name + ".png"));
                log("screenshot {}", name);
            } catch (java.io.IOException e) {
                RealCamera.LOGGER.error("screenshot failed", e);
            }
        });
    }
}
