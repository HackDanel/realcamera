package dev.hackdanel.realcamera.client.dev;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.item.RCStacks;
import dev.hackdanel.realcamera.menu.CameraPartsMenu;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
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
 * Holding the camera and filters (-Drealcamera.autotest=pose): the camera on the strap and the two-handed pose seen
 * from the front, the raise to the eye in first person, and photos through the new filters (Black Mist, graduated
 * ND, star), and a filter on a step-up ring put together in the parts menu (issue #7). Look at
 * screenshots/autotest/pose_*.png, ring_parts_menu.png and the photos afterwards.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class PoseAutotest {
    private static final boolean ENABLED = "pose".equals(System.getProperty("realcamera.autotest"));

    private record Step(String label, Runnable action, int delay, BooleanSupplier until, int timeout) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static boolean worldRequested, scripted;
    private static volatile String pendingScreenshot;
    private static int wait, polled;

    private PoseAutotest() {}

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
        Minecraft mc = Minecraft.getInstance();
        var regs = mc.level.registryAccess();
        var body = regs.lookupOrThrow(RCRegistries.CAMERA_BODY)
                .getOrThrow(ResourceKey.create(RCRegistries.CAMERA_BODY, RealCamera.id("sony_a7iv")));
        ItemStack cam = RCStacks.kit(regs, body).orElseThrow();
        CameraParts parts = cam.get(RCComponents.CAMERA_PARTS.get());
        cam.set(RCComponents.CAMERA_PARTS.get(), parts.with(CameraParts.SLOT_LENS,
                RCStacks.lens(ResourceKey.create(RCRegistries.LENS, RealCamera.id(lens)))));
        cam.set(RCComponents.CAMERA_SETTINGS.get(), CameraSettings.DEFAULT.withMode(CameraSettings.MODE_A)
                .withApertureIndex(ExposureScales.nearestAperture(fNumber)).withFocusMode(CameraSettings.FOCUS_MF)
                .withFocusDistance(focusM));
        return cam;
    }

    private static void shoot(String label, String lens, double fNumber, float focusM) {
        step("give " + label, () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = camera(lens, fNumber, focusM);
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

    private static void filter(String key) {
        step("filter " + key, () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = mc.player.getInventory().getItem(0).copy();
            CameraParts parts = s.get(RCComponents.CAMERA_PARTS.get());
            s.set(RCComponents.CAMERA_PARTS.get(), parts.with(CameraParts.SLOT_FILTER, key.isEmpty() ? ItemStack.EMPTY
                    : RCStacks.filter(ResourceKey.create(RCRegistries.FILTER_TYPE, RealCamera.id(key)))));
            mc.player.getInventory().setItem(0, s);
            mc.gameMode.handleCreativeModeItemAdd(s, 36);
        }, 10);
    }

    private static void click(int slot, int button) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slot, button,
                net.minecraft.world.inventory.ClickType.PICKUP, mc.player);
    }

    private static void openParts() {
        step("open parts", () -> server(p -> p.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inv, pl) ->
                new CameraPartsMenu(id, inv, InteractionHand.MAIN_HAND), p.getMainHandItem().getHoverName()),
                buf -> buf.writeEnum(InteractionHand.MAIN_HAND))), 10);
        until("parts menu open", () -> Minecraft.getInstance().player.containerMenu instanceof CameraPartsMenu, 60);
    }

    private static ItemStack partsFilter() {
        return Minecraft.getInstance().player.containerMenu.getSlot(CameraParts.SLOT_FILTER).getItem();
    }

    /**
     * Issue #7: a 67 mm graduated ND on the 55 mm thread of the 28-70 through a 55→67 step-up ring, put together in
     * the camera's parts menu by clicks (ring into the filter slot, filter onto it), a photo through it, then the
     * filter unscrewed again with nothing lost. Menu slots: 0-7 parts, 8-34 main inventory (9-35), 35-43 hotbar.
     */
    private static void stepUpRing() {
        var ringKey = ResourceKey.create(RCRegistries.STEP_UP_RING, RealCamera.id("step_up_55_67"));
        var filterKey = ResourceKey.create(RCRegistries.FILTER_TYPE, RealCamera.id("gnd8_67"));
        step("ring kit", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            ItemStack cam = camera("sony_fe_28_70_oss", 8, 10f);
            server(p -> {
                p.getInventory().selected = 0;
                p.getInventory().setItem(0, cam);
                p.getInventory().setItem(9, RCStacks.stepUpRing(ringKey));
                p.getInventory().setItem(10, RCStacks.filter(filterKey));
                p.getInventory().setItem(11, ItemStack.EMPTY);
            });
        }, 20);
        openParts();
        step("ring into the filter slot", () -> {
            var menu = (CameraPartsMenu) Minecraft.getInstance().player.containerMenu;
            boolean refused = !menu.accepts(CameraParts.SLOT_FILTER, RCStacks.filter(filterKey));
            log(refused ? "PASS bare 67 mm filter refused on a 55 mm thread" : "FAIL bare 67 mm filter fits a 55 mm thread");
            click(8, 0);
            click(CameraParts.SLOT_FILTER, 0);
        }, 10);
        step("filter onto the ring", () -> {
            click(9, 0);
            click(CameraParts.SLOT_FILTER, 0);
        }, 10);
        step("ring menu screenshot", () -> pendingScreenshot = "ring_parts_menu", 5);
        step("joined", () -> {
            ItemStack f = partsFilter();
            boolean ok = f.is(RCItems.FILTER.get()) && ringKey.equals(f.get(RCComponents.STEP_UP_RING.get()))
                    && Minecraft.getInstance().player.containerMenu.getCarried().isEmpty();
            log(ok ? "PASS filter screwed onto the step-up ring in the filter slot" : "FAIL filter slot holds {}", f);
            Minecraft.getInstance().player.closeContainer();
        }, 20);
        until("camera has the ringed filter", () -> {
            ItemStack f = Minecraft.getInstance().player.getInventory().getItem(0)
                    .getOrDefault(RCComponents.CAMERA_PARTS.get(), CameraParts.EMPTY).filter();
            return filterKey.equals(f.get(RCComponents.FILTER_TYPE.get())) && ringKey.equals(f.get(RCComponents.STEP_UP_RING.get()));
        }, 60);
        shootHeld("gnd8_67 on step-up ring");
        openParts();
        step("unscrew", () -> {
            click(CameraParts.SLOT_FILTER, 1); // the bare filter no longer fits: the ring stays, the filter comes off
            click(10, 0);
        }, 10);
        step("nothing lost", () -> {
            ItemStack slot = partsFilter();
            ItemStack off = Minecraft.getInstance().player.containerMenu.getSlot(10).getItem();
            boolean ok = ringKey.equals(slot.get(RCComponents.STEP_UP_RING.get())) && slot.is(RCItems.STEP_UP_RING.get())
                    && filterKey.equals(off.get(RCComponents.FILTER_TYPE.get())) && !off.has(RCComponents.STEP_UP_RING.get());
            log(ok ? "PASS unscrewed: ring stays on the lens, filter back in the inventory" : "FAIL unscrew: slot {} inventory {}",
                    slot, off);
            Minecraft.getInstance().player.closeContainer();
        }, 20);
    }

    private static void shootHeld(String label) {
        step("raise " + label, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder " + label, Viewfinder::isActive, 60);
        step("settle " + label, () -> {}, 20);
        step("shoot " + label, Viewfinder::debugPressShutter, 5);
        until("started " + label, PhotoCapture::busy, 80);
        until("stored " + label, () -> !PhotoCapture.busy(), 1200);
        step("lower " + label, () -> {
            Minecraft.getInstance().options.keyUse.setDown(false);
            log("PASS shot {}", label);
        }, 20);
    }

    private static void script() {
        step("scene", () -> server(p -> {
            BlockPos o = p.blockPosition();
            cmd(p, "time set 1000");
            cmd(p, "weather clear");
            cmd(p, "fill ~-16 ~-3 ~-6 ~16 ~-1 ~60 minecraft:dirt");
            cmd(p, "fill ~-16 ~-1 ~-6 ~16 ~-1 ~60 minecraft:grass_block");
            cmd(p, "fill ~-16 ~ ~-6 ~16 ~20 ~60 minecraft:air");
            cmd(p, "fill ~-3 ~ ~20 ~3 ~6 ~22 minecraft:white_concrete");
            cmd(p, String.format("tp @s %d.5 %d %d.5 0 0", o.getX(), o.getY(), o.getZ()));
        }), 80);
        // on the strap: camera in slot 1, empty hand
        step("give strap", () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = camera("sony_fe_24_70_gm2", 5.6, 10f);
            mc.player.getInventory().setItem(1, s);
            mc.gameMode.handleCreativeModeItemAdd(s, 37);
            mc.player.getInventory().setItem(0, ItemStack.EMPTY);
            mc.gameMode.handleCreativeModeItemAdd(ItemStack.EMPTY, 36);
            mc.player.getInventory().selected = 0;
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        }, 40);
        step("strap screenshot", () -> pendingScreenshot = "pose_strap", 10);
        step("give held", () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = mc.player.getInventory().getItem(1);
            mc.player.getInventory().setItem(0, s);
            mc.gameMode.handleCreativeModeItemAdd(s, 36);
            mc.player.getInventory().setItem(1, ItemStack.EMPTY);
            mc.gameMode.handleCreativeModeItemAdd(ItemStack.EMPTY, 37);
        }, 20);
        step("held screenshot", () -> pendingScreenshot = "pose_held", 5);
        step("raise 3rd", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 20);
        step("raised screenshot", () -> pendingScreenshot = "pose_raised_front", 5);
        step("side view", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            mc.player.setYRot(90);
            mc.player.yRotO = 90;
        }, 10);
        step("raised back screenshot", () -> pendingScreenshot = "pose_raised_back", 5);
        step("lower 3rd", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUse.setDown(false);
            mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            mc.player.setYRot(0);
            mc.player.yRotO = 0;
        }, 20);
        step("held 1st screenshot", () -> pendingScreenshot = "pose_first_held", 5);
        step("raise 1st", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 2);
        step("mid-raise screenshot", () -> pendingScreenshot = "pose_first_raising", 20);
        step("lower 1st", () -> Minecraft.getInstance().options.keyUse.setDown(false), 2);
        step("mid-lower screenshot", () -> pendingScreenshot = "pose_first_lowering", 20);
        // filters: landscape with the sky (GND, Black Mist), plain for comparison
        shootHeld("no filter");
        filter("gnd8_82");
        shootHeld("gnd8");
        filter("mist4_82");
        shootHeld("mist4");
        stepUpRing();
        step("night", () -> server(p -> {
            cmd(p, "time set 18000");
            for (int i = -3; i <= 3; i++) cmd(p, String.format("setblock ~%d ~3 ~19 minecraft:sea_lantern", i * 2));
        }), 60);
        filter("star6_82");
        shootHeld("star6");
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
            String name = "rc_pose_" + System.currentTimeMillis();
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
