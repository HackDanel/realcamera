package dev.hackdanel.realcamera.client.camera;

import dev.hackdanel.realcamera.ClientBridge;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.client.RCKeys;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.optics.Exposure;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.optics.Optics;
import dev.hackdanel.realcamera.registry.RCSounds;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The camera at the eye: field of view from focal length and sensor, live metering and exposure
 * program, autofocus, dials on keys and the mouse wheel, and the shutter.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Viewfinder {
    public static final int AF_IDLE = 0, AF_SEARCHING = 1, AF_LOCKED = 2, AF_FAILED = 3;

    private static boolean active;
    private static InteractionHand hand = InteractionHand.MAIN_HAND;
    private static CameraSettings settings = CameraSettings.DEFAULT;
    private static int settingsDirtyTicks = -1;
    private static CameraBodySpec body;
    private static LensSpec lens;

    private static double meteredEv = 12;
    private static boolean blockLit;
    private static Exposure.Result exposure;

    private static float focusDistance = 3f;
    private static float focusStart, focusTarget;
    private static int afState = AF_IDLE;
    private static long afStartMs, afDurationMs;
    private static boolean shootWhenFocused;
    private static boolean attackWasDown;
    private static int tick;

    private Viewfinder() {}

    // ------------------------------------------------------------------ public state for HUD / capture

    public static boolean isActive() {
        return active && body != null && lens != null;
    }

    public static CameraSettings settings() {
        return settings;
    }

    public static CameraBodySpec body() {
        return body;
    }

    public static LensSpec lens() {
        return lens;
    }

    public static InteractionHand hand() {
        return hand;
    }

    public static Exposure.Result exposure() {
        return exposure;
    }

    public static double meteredEv() {
        return meteredEv;
    }

    public static boolean blockLit() {
        return blockLit;
    }

    public static float focusDistance() {
        return focusDistance;
    }

    public static int afState() {
        return afState;
    }

    /** Current real focal length in mm. */
    public static float focal() {
        if (lens == null) return 50;
        return settings.focal() <= 0 ? lens.focalMin() : lens.clampFocal(settings.focal());
    }

    public static ItemStack camera() {
        Player p = Minecraft.getInstance().player;
        return p == null ? ItemStack.EMPTY : p.getItemInHand(hand);
    }

    /** Vertical FOV of the sensor (what the photo covers). */
    public static double sensorVfov() {
        return Optics.verticalFovDeg(body.sensor().heightMm(), focal());
    }

    /**
     * Vertical FOV to render with on a screen of the given aspect so the sensor frame fits inside.
     * Wider screens get side bars; narrower ones top/bottom bars.
     */
    public static double renderVfov(double screenAspect) {
        double sensorAspect = body.sensor().aspect();
        double v = sensorVfov();
        if (screenAspect >= sensorAspect) {
            return v;
        }
        double tanH = Math.tan(Math.toRadians(v) / 2) * sensorAspect;
        return Math.toDegrees(2 * Math.atan(tanH / screenAspect));
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    static void onTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) {
            active = false;
            return;
        }
        tick++;
        // Looking through the camera only makes sense from the photographer's eye (first person).
        boolean using = player.isUsingItem() && CameraHelper.isCamera(player.getUseItem())
                && mc.options.getCameraType().isFirstPerson();
        if (using && !active) {
            activate(player.getUsedItemHand());
        } else if (!using && active) {
            deactivate();
        }
        boolean holding = CameraHelper.handHoldingCamera(player).isPresent();
        if (holding && mc.screen == null) {
            InteractionHand h = CameraHelper.handHoldingCamera(player).get();
            while (RCKeys.PLAYBACK.consumeClick()) {
                if (!active) hand = h;
                ClientBridge.get().openPlayback();
            }
            while (RCKeys.MENU.consumeClick()) {
                if (!active) {
                    hand = h;
                    settings = CameraHelper.settings(player.getItemInHand(h));
                }
                ClientBridge.get().openCameraMenu();
            }
        } else {
            RCKeys.drain(RCKeys.PLAYBACK);
            RCKeys.drain(RCKeys.MENU);
        }
        if (active) {
            tickActive(mc, player);
        } else {
            RCKeys.drain(RCKeys.PREV_PARAM);
            RCKeys.drain(RCKeys.NEXT_PARAM);
            RCKeys.drain(RCKeys.MODE);
            RCKeys.drain(RCKeys.FOCUS_MODE);
            RCKeys.drain(RCKeys.DISP);
        }
        if (settingsDirtyTicks >= 0 && --settingsDirtyTicks < 0) {
            sendSettings();
        }
    }

    private static void activate(InteractionHand h) {
        hand = h;
        ItemStack cam = camera();
        settings = CameraHelper.settings(cam);
        focusDistance = settings.focusDistance();
        afState = AF_IDLE;
        active = true;
        attackWasDown = true; // ignore a click held from before raising the camera
        refreshSpecs();
    }

    private static void deactivate() {
        active = false;
        shootWhenFocused = false;
        afState = AF_IDLE;
        settings = settings.withFocusDistance(focusDistance);
        sendSettings();
    }

    private static void refreshSpecs() {
        Minecraft mc = Minecraft.getInstance();
        var regs = mc.level.registryAccess();
        ItemStack cam = camera();
        body = Specs.body(regs, cam).orElse(null);
        lens = CameraHelper.mountedLens(regs, cam).orElse(null);
    }

    private static void tickActive(Minecraft mc, Player player) {
        refreshSpecs();
        if (body == null || lens == null) {
            return;
        }
        // F is also vanilla "swap hands": never let it move the camera while shooting.
        while (mc.options.keySwapOffhand.consumeClick()) {
            // swallowed
        }
        handleKeys();
        if (tick % 4 == 0) {
            meter(mc);
        }
        exposure = Exposure.solve(settings.mode(), meteredEv, settings.evComp() / 3.0, settings.apertureIndex(),
                settings.shutterIndex(), settings.isoIndex(), limits());
        updateFocus(mc);

        boolean attackDown = mc.options.keyAttack.isDown();
        if (attackDown && !attackWasDown) {
            pressShutter(mc, player);
        }
        attackWasDown = attackDown;
        // vanilla would otherwise queue an attack when the camera is lowered
        while (mc.options.keyAttack.consumeClick()) {
            // swallowed
        }
    }

    public static Exposure.Limits limits() {
        float f = focal();
        int wide = ExposureScales.nearestAperture(lens.maxApertureAt(f));
        int narrow = ExposureScales.nearestAperture(lens.apertureMin());
        int fast = ExposureScales.nearestShutter(Math.min(body.shutter().mechMin(), body.shutter().elecMin()));
        int slow = ExposureScales.nearestShutter(body.shutter().max());
        int isoMin = ExposureScales.nearestIso(body.sensor().isoMin());
        int isoMax = ExposureScales.nearestIso(body.sensor().isoMax());
        int autoMax = Math.min(isoMax, ExposureScales.nearestIso(Exposure.AUTO_ISO_MAX_DEFAULT));
        double handheld = Optics.handheldLimit(Optics.focal35(f, body.sensor().cropFactor()), 0);
        return new Exposure.Limits(wide, narrow, fast, slow, isoMin, isoMax, autoMax, handheld);
    }

    // ------------------------------------------------------------------ metering

    private static void meter(Minecraft mc) {
        double aspect = body.sensor().aspect();
        CameraRays rays = new CameraRays(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(),
                sensorVfov(), aspect, 128);
        float pt = 1f;
        double day = SceneMeter.daylightEv(mc.level, pt);
        double ambient = SceneMeter.ambientEv(mc.level);
        int gx = 9, gy = 6;
        double sum = 0, wsum = 0;
        int blockVotes = 0;
        for (int y = 0; y < gy; y++) {
            double v = 1 - (y + 0.5) * 2 / gy;
            for (int x = 0; x < gx; x++) {
                double u = (x + 0.5) * 2 / gx - 1;
                double r2 = u * u + v * v;
                double w = switch (settings.metering()) {
                    case CameraSettings.METER_CENTER -> Math.exp(-r2 * 2.5);
                    case CameraSettings.METER_SPOT -> r2 < 0.05 ? 1 : 0;
                    default -> 1;
                };
                if (w <= 0) continue;
                Vec3 dir = rays.direction(u, v);
                HitResult hit = rays.cast(dir);
                double ev = SceneMeter.sampleEv(mc.level, hit, dir, day, ambient);
                sum += ev * w;
                wsum += w;
                if (hit.getType() != HitResult.Type.MISS
                        && SceneMeter.blockLit(mc.level, net.minecraft.core.BlockPos.containing(hit.getLocation()), day)) {
                    blockVotes++;
                }
            }
        }
        if (settings.metering() == CameraSettings.METER_SPOT && wsum == 0) {
            Vec3 dir = rays.direction(0, 0);
            sum = SceneMeter.sampleEv(mc.level, rays.cast(dir), dir, day, ambient);
            wsum = 1;
        }
        double target = sum / wsum;
        // Matrix metering protects highlights a little when the sky dominates.
        meteredEv = Mth.lerp(0.5, meteredEv, target);
        blockLit = blockVotes > gx * gy / 3;
    }

    // ------------------------------------------------------------------ focus

    private static double afPointDistance(Minecraft mc) {
        CameraRays rays = new CameraRays(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(),
                sensorVfov(), body.sensor().aspect(), 512);
        Vec3 dir = rays.direction(0, 0);
        double d = rays.planeDistance(rays.cast(dir), dir);
        return Double.isInfinite(d) ? 1000 : d;
    }

    private static void startAf(Minecraft mc) {
        double target = Math.max(lens.minFocusM(), afPointDistance(mc));
        if (meteredEv < body.af().lowLightEv()) {
            afState = AF_FAILED;
            afStartMs = System.currentTimeMillis();
            afDurationMs = 600;
            return;
        }
        focusStart = focusDistance;
        focusTarget = (float) target;
        double travel = Math.abs(Math.log(focusTarget / Math.max(0.1f, focusStart)));
        double speed = body.af().speed() * lens.af().speed();
        afDurationMs = (long) ((60 + 90 * Math.min(3, travel)) / Math.max(0.2, speed));
        afStartMs = System.currentTimeMillis();
        afState = AF_SEARCHING;
    }

    private static void updateFocus(Minecraft mc) {
        long now = System.currentTimeMillis();
        int mode = settings.focusMode();
        if (mode == CameraSettings.FOCUS_MF) {
            afState = AF_IDLE;
            focusDistance = settings.focusDistance();
            return;
        }
        if (afState == AF_SEARCHING) {
            float t = Math.min(1f, (float) (now - afStartMs) / Math.max(1, afDurationMs));
            focusDistance = (float) Math.exp(Mth.lerp(t, Math.log(Math.max(0.05, focusStart)), Math.log(focusTarget)));
            if (t >= 1) {
                afState = AF_LOCKED;
                mc.getSoundManager().play(SimpleSoundInstance.forUI(RCSounds.AF_CONFIRM.get(), 1f, 0.6f));
            }
        } else if (afState == AF_FAILED && now - afStartMs > afDurationMs) {
            afState = AF_IDLE;
            shootWhenFocused = false;
        } else if (mode == CameraSettings.FOCUS_AFC && afState == AF_LOCKED && tick % 2 == 0) {
            // continuous AF follows whatever is under the AF point
            float target = (float) Math.max(lens.minFocusM(), afPointDistance(mc));
            focusDistance = (float) Math.exp(Mth.lerp(0.5, Math.log(focusDistance), Math.log(target)));
        }
        if (shootWhenFocused && afState == AF_LOCKED) {
            shootWhenFocused = false;
            fire(mc);
        }
    }

    // ------------------------------------------------------------------ shutter

    private static void pressShutter(Minecraft mc, Player player) {
        ItemStack cam = camera();
        if (!CameraHelper.hasPower(cam)) {
            player.displayClientMessage(Component.translatable("message.realcamera.battery_empty"), true);
            return;
        }
        long left = CameraHelper.shotsLeft(mc.level.registryAccess(), cam);
        if (left < 0) {
            player.displayClientMessage(Component.translatable("message.realcamera.no_card"), true);
            return;
        }
        if (left == 0) {
            player.displayClientMessage(Component.translatable("message.realcamera.card_full"), true);
            return;
        }
        if (PhotoCapture.busy()) {
            player.displayClientMessage(Component.translatable("message.realcamera.buffer_busy"), true);
            return;
        }
        if (settings.focusMode() == CameraSettings.FOCUS_MF) {
            fire(mc);
        } else if (settings.focusMode() == CameraSettings.FOCUS_AFC && afState == AF_LOCKED) {
            fire(mc);
        } else {
            shootWhenFocused = true;
            startAf(mc);
        }
    }

    private static void fire(Minecraft mc) {
        if (exposure == null || PhotoCapture.busy()) {
            return;
        }
        mc.getSoundManager().play(SimpleSoundInstance.forUI(RCSounds.SHUTTER_MIRRORLESS.get(), 1f, 0.9f));
        PacketDistributor.sendToServer(new Payloads.Shutter(hand));
        PhotoCapture.request(new PhotoCapture.Shot(hand, body, lens, settings, exposure, focal(), focusDistance,
                meteredEv, blockLit, CameraHelper.shutterCount(camera()) + 1,
                camera().getHoverName().getString(), lensName()));
    }

    private static String lensName() {
        ItemStack lensStack = CameraHelper.parts(camera()).lens();
        return lensStack.getHoverName().getString();
    }

    /** Dev/autotest hook: behaves like a click on the shutter button. */
    public static void debugPressShutter() {
        Minecraft mc = Minecraft.getInstance();
        if (isActive() && mc.player != null) {
            pressShutter(mc, mc.player);
        }
    }

    // ------------------------------------------------------------------ dials

    private static void handleKeys() {
        while (RCKeys.NEXT_PARAM.consumeClick()) {
            update(settings.withSelectedParam((settings.selectedParam() + 1) % CameraSettings.PARAM_COUNT));
        }
        while (RCKeys.PREV_PARAM.consumeClick()) {
            update(settings.withSelectedParam(Math.floorMod(settings.selectedParam() - 1, CameraSettings.PARAM_COUNT)));
        }
        while (RCKeys.MODE.consumeClick()) {
            update(settings.withMode((settings.mode() + 1) % CameraSettings.MODE_COUNT));
        }
        while (RCKeys.FOCUS_MODE.consumeClick()) {
            int m = (settings.focusMode() + 1) % CameraSettings.FOCUS_COUNT;
            update(settings.withFocusMode(m).withFocusDistance(focusDistance));
            afState = AF_IDLE;
        }
        while (RCKeys.DISP.consumeClick()) {
            update(settings.withDisp((settings.disp() + 1) % CameraSettings.DISP_COUNT));
        }
    }

    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (!isActive() || Minecraft.getInstance().screen != null) {
            return;
        }
        event.setCanceled(true);
        int steps = (int) Math.signum(event.getScrollDeltaY());
        if (steps == 0) return;
        if (Screen.hasAltDown()) {
            // manual focus: logarithmic steps between minimum focus and ~1 km
            if (settings.focusMode() == CameraSettings.FOCUS_MF) {
                float d = (float) Mth.clamp(settings.focusDistance() * Math.pow(1.12, steps), lens.minFocusM(), 1000);
                update(settings.withFocusDistance(d));
            }
        } else if (Screen.hasControlDown() || !lens.isZoom()) {
            adjustSelected(steps);
        } else {
            float f = lens.clampFocal(focal() * (float) Math.pow(1.06, steps));
            update(settings.withFocal(f));
        }
    }

    /** Wheel on the selected dial; what may change depends on the mode, like on a real body. */
    private static void adjustSelected(int steps) {
        int mode = settings.mode();
        Exposure.Limits lim = limits();
        switch (settings.selectedParam()) {
            case CameraSettings.PARAM_SHUTTER -> {
                if (mode == CameraSettings.MODE_S || mode == CameraSettings.MODE_M) {
                    // wheel up = faster shutter (smaller time)
                    int base = mode == CameraSettings.MODE_M ? settings.shutterIndex() : exposure.shutterIdx();
                    update(settings.withShutter(Mth.clamp(base - steps, lim.shutterFastIdx(), lim.shutterSlowIdx())));
                }
            }
            case CameraSettings.PARAM_APERTURE -> {
                if (mode == CameraSettings.MODE_A || mode == CameraSettings.MODE_M) {
                    int base = Mth.clamp(settings.apertureIndex(), lim.apertureWideIdx(), lim.apertureNarrowIdx());
                    update(settings.withAperture(Mth.clamp(base + steps, lim.apertureWideIdx(), lim.apertureNarrowIdx())));
                }
            }
            case CameraSettings.PARAM_ISO -> {
                if (mode != CameraSettings.MODE_AUTO) {
                    int iso = settings.isoIndex();
                    int next;
                    if (iso < 0) {
                        next = steps > 0 ? lim.isoMinIdx() : -1;
                    } else {
                        next = iso + steps;
                        if (next < lim.isoMinIdx()) next = -1;
                    }
                    update(settings.withIso(next < 0 ? -1 : Math.min(next, lim.isoMaxIdx())));
                }
            }
            case CameraSettings.PARAM_EV -> {
                if (mode != CameraSettings.MODE_AUTO && (mode != CameraSettings.MODE_M || settings.isoIndex() < 0)) {
                    update(settings.withEvComp(Mth.clamp(settings.evComp() + steps,
                            ExposureScales.EV_COMP_MIN, ExposureScales.EV_COMP_MAX)));
                }
            }
            case CameraSettings.PARAM_WB -> {
                if (mode != CameraSettings.MODE_AUTO) {
                    if (settings.whiteBalance() == CameraSettings.WB_KELVIN && Screen.hasShiftDown()) {
                        update(settings.withWhiteBalance(CameraSettings.WB_KELVIN,
                                Mth.clamp(settings.kelvin() + steps * 100, 2500, 10000)));
                    } else {
                        update(settings.withWhiteBalance(Math.floorMod(settings.whiteBalance() + steps,
                                CameraSettings.WB_COUNT), settings.kelvin()));
                    }
                }
            }
            default -> {}
        }
    }

    /** Change settings locally right away; the server copy is updated a few ticks later. */
    public static void update(CameraSettings next) {
        settings = next;
        settingsDirtyTicks = 4;
    }

    /** Used by the camera menu screen when the camera is not raised. */
    public static void setSettingsAndSend(InteractionHand h, CameraSettings next) {
        hand = h;
        settings = next;
        sendSettings();
    }

    private static void sendSettings() {
        settingsDirtyTicks = -1;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack cam = mc.player.getItemInHand(hand);
        if (!CameraHelper.isCamera(cam)) return;
        CameraSettings toSend = settings.withFocusDistance(focusDistance);
        PacketDistributor.sendToServer(new Payloads.UpdateSettings(hand, toSend));
    }

    // ------------------------------------------------------------------ view

    @SubscribeEvent
    static void onFov(ViewportEvent.ComputeFov event) {
        if (!isActive() || !event.usedConfiguredFov()) {
            return;
        }
        var window = Minecraft.getInstance().getWindow();
        double aspect = (double) window.getWidth() / Math.max(1, window.getHeight());
        event.setFOV(renderVfov(aspect));
    }

    @SubscribeEvent
    static void onFovModifier(ComputeFovModifierEvent event) {
        if (isActive()) {
            event.setNewFovModifier(1f);
        }
    }

    @SubscribeEvent
    static void onTurn(CalculatePlayerTurnEvent event) {
        if (isActive()) {
            // Slow the mouse down with the field of view so long lenses stay controllable.
            double scale = Math.max(0.05, renderVfov(16.0 / 9) / 70.0);
            event.setMouseSensitivity(event.getMouseSensitivity() * Math.pow(scale, 0.8));
        }
    }

    /** No block selection box in the viewfinder or in photos (its line width also breaks at capture size). */
    @SubscribeEvent
    static void onHighlight(net.neoforged.neoforge.client.event.RenderHighlightEvent.Block event) {
        if (isActive() || PhotoCapture.capturing()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onRenderHand(RenderHandEvent event) {
        if (isActive() || PhotoCapture.capturing()) {
            event.setCanceled(true);
        }
    }

}
