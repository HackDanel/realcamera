package dev.hackdanel.realcamera.client.camera;

import dev.hackdanel.realcamera.ClientBridge;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.camera.CameraRef;
import dev.hackdanel.realcamera.camera.OpticalChain;
import dev.hackdanel.realcamera.client.RCKeys;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.client.hud.FrameSampler;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.optics.Exposure;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.optics.Optics;
import dev.hackdanel.realcamera.processing.ImageParams;
import dev.hackdanel.realcamera.registry.RCSounds;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.LensSpec;
import dev.hackdanel.realcamera.spec.SensorSpec;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The camera at the eye: field of view from the optical chain, live metering and exposure program,
 * autofocus with areas and subject detection, focus peaking and magnifier, drive modes (single,
 * bursts, self-timer), shutter types and bulb, and the shutter itself.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Viewfinder {
    public static final int AF_IDLE = 0, AF_SEARCHING = 1, AF_LOCKED = 2, AF_FAILED = 3;
    public static final int PEAK_W = 64, PEAK_H = 36;
    private static final float MAGNIFY = 5f;

    private static boolean active;
    private static CameraRef ref = CameraRef.hand(InteractionHand.MAIN_HAND);
    private static CameraSettings settings = CameraSettings.DEFAULT;
    private static int settingsDirtyTicks = -1;
    private static OpticalChain chain;
    private static String chainProblem;

    private static double meteredEv = 12;
    private static boolean blockLit;
    private static Exposure.Result exposure;

    private static float focusDistance = 3f;
    private static float focusStart, focusTarget;
    private static int afState = AF_IDLE;
    private static long afStartMs, afDurationMs;
    private static boolean shootWhenFocused;
    /** Low contrast where the AF measures: the lens sweeps its whole range looking for detail, then gives up. */
    private static boolean hunting;
    /** Focus was set with the AF-ON button: the shutter button fires at that distance without refocusing. */
    private static boolean buttonLock;
    /** Where the AF measured last (frame coordinates) and how large the patch was (frame half-heights). */
    private static double afU, afV, afPatch = 0.06;
    private static final RandomSource AF_ERROR = RandomSource.create();
    /** Below this local contrast phase detection finds nothing to lock on; below GOOD it is slow and less precise. */
    private static final double AF_CONTRAST_MIN = 0.007, AF_CONTRAST_GOOD = 0.013;
    @Nullable
    private static SubjectDetector.Subject subject;
    private static boolean attackWasDown;
    private static int tick;

    private static boolean magnified;
    private static float centreDistance = 3f;
    private static boolean[] peaking = new boolean[PEAK_W * PEAK_H];

    // drive
    private static long nextBurstMs;
    // the camera's own buffer: frames waiting to be written to the card (drains at the card's write speed)
    private static double bufferFill;
    private static long bufferAtMs;
    private static long timerFireMs = -1;
    private static long bulbStartMs = -1;

    // flash
    private static long flashReadyMs;
    /** Guide number (ISO 100) of the light the flash gives at the power TTL chose for the composed exposure. */
    private static float emittedGn;

    // camera motion (deg/s), smoothed, for shake/pan blur and rolling shutter
    private static float lastYaw, lastPitch;
    private static double yawRate, pitchRate;
    private static final RandomSource SHAKE = RandomSource.create();

    private Viewfinder() {}

    // ------------------------------------------------------------------ public state for HUD / capture

    public static boolean isActive() {
        return active && chain != null;
    }

    public static CameraSettings settings() {
        return settings;
    }

    public static OpticalChain chain() {
        return chain;
    }

    public static CameraBodySpec body() {
        return chain.body();
    }

    public static LensSpec lens() {
        return chain.lens();
    }

    public static SensorSpec sensor() {
        return chain.sensor();
    }

    public static CameraRef ref() {
        return ref;
    }

    public static Exposure.Result exposure() {
        return exposure;
    }

    public static double meteredEv() {
        return meteredEv;
    }

    /** The camera meters through the lens, so a filter's light loss is already in the reading. */
    /**
     * What reaches the sensor: the filter's loss, the glass's own (T-stop vs f-stop) and, close up, the extension's
     * (TTL metering sees them all; without a meter the photographer has to allow for them).
     */
    public static double meteredThroughFilter() {
        return meteredEv - lightLossEv();
    }

    /** Light lost between the scene and the sensor, EV. */
    public static double lightLossEv() {
        return filterLossEv() + (chain == null ? 0 : chain.bellowsLossEv(focal(), focusDistance) + chain.transmissionLossEv());
    }

    public static double filterLossEv() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        return dev.hackdanel.realcamera.camera.Specs.filter(mc.level.registryAccess(), CameraHelper.parts(camera()).filter())
                .map(f -> (double) f.lightLossEv()).orElse(0.0);
    }

    /** Guide number of the flash that would fire now (hot shoe, else built-in), or 0. */
    public static float flashGn() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || chain == null) return 0;
        var parts = CameraHelper.parts(camera());
        var hotShoe = dev.hackdanel.realcamera.camera.Specs.flash(mc.level.registryAccess(), parts.flash());
        if (hotShoe.isPresent()) {
            var c = parts.flash().get(dev.hackdanel.realcamera.registry.RCComponents.CHARGE.get());
            return c != null && c.charge() > 0 ? hotShoe.get().gn() : 0;
        }
        return body().flash().builtInGn();
    }

    public static boolean flashReady() {
        return System.currentTimeMillis() >= flashReadyMs;
    }

    public static boolean blockLit() {
        return blockLit;
    }

    public static float focusDistance() {
        return focusDistance;
    }

    /**
     * Focus distance right now, for the live depth-of-field preview: follows the AF motor between ticks and the
     * focus ring without waiting for the next tick.
     */
    public static float liveFocusDistance() {
        if (chain == null) return focusDistance;
        if (settings.focusMode() == CameraSettings.FOCUS_MF || !chain.afAvailable()) {
            return settings.focusDistance();
        }
        return afState == AF_SEARCHING ? focusAt(System.currentTimeMillis()) : focusDistance;
    }

    /** Focus locked with AF-ON (the shutter button will not refocus). */
    public static boolean afButtonLock() {
        return buttonLock && afState == AF_LOCKED;
    }

    public static int afState() {
        return afState;
    }

    @Nullable
    public static SubjectDetector.Subject subject() {
        return subject;
    }

    public static boolean magnified() {
        return magnified;
    }

    public static boolean[] peaking() {
        return peaking;
    }

    /** Distance of whatever is in the middle of the frame (rangefinder patch). */
    public static float subjectDistanceForPatch() {
        return centreDistance;
    }

    /** Seconds left on the self-timer, or -1. */
    public static double timerLeft() {
        return timerFireMs < 0 ? -1 : Math.max(0, (timerFireMs - System.currentTimeMillis()) / 1000.0);
    }

    public static boolean bulbOpen() {
        return bulbStartMs >= 0;
    }

    public static boolean afAvailable() {
        return chain != null && chain.afAvailable();
    }

    /** Effective focal length in mm (teleconverter included). */
    public static float focal() {
        return chain == null ? 50 : chain.focal(settings.focal());
    }

    public static ItemStack camera() {
        Player p = Minecraft.getInstance().player;
        if (p == null) return ItemStack.EMPTY;
        if (ref.isTripod()) {
            return Minecraft.getInstance().level != null
                    && Minecraft.getInstance().level.getEntity(ref.tripodId()) instanceof dev.hackdanel.realcamera.entity.TripodEntity t
                    ? t.camera() : ItemStack.EMPTY;
        }
        return p.getItemInHand(ref.hand());
    }

    /**
     * Vertical FOV the photo is rendered with: the (effective) sensor's, capped and remapped later for
     * fisheyes, a little wider for a shifted tilt-shift lens.
     */
    public static double sensorVfov() {
        return chain.renderVfovDeg(focal(), settings, focusDistance);
    }

    /** Parameters the dials can select: tilt and shift only on a tilt-shift lens. */
    private static int nextParam(int from, int dir) {
        int p = from;
        for (int i = 0; i < CameraSettings.PARAM_COUNT; i++) {
            p = Math.floorMod(p + dir, CameraSettings.PARAM_COUNT);
            boolean movement = p == CameraSettings.PARAM_TILT || p == CameraSettings.PARAM_SHIFT;
            if (!movement || chain.tiltShift().isPresent()) return p;
        }
        return CameraSettings.PARAM_SHUTTER;
    }

    /** Rangefinders show more than the frame; the bright frame lines mark the picture. */
    public static double viewfinderOverscan() {
        return "rangefinder".equals(body().viewfinder()) ? 1.35 : 1.0;
    }

    /**
     * Vertical FOV to render with on a screen of the given aspect so the sensor frame fits inside.
     * Wider screens get side bars; narrower ones top/bottom bars.
     */
    public static double renderVfov(double screenAspect) {
        double sensorAspect = sensor().aspect();
        double tanV = Math.tan(Math.toRadians(sensorVfov()) / 2) * viewfinderOverscan();
        if (magnified) {
            tanV /= MAGNIFY;
        }
        if (screenAspect >= sensorAspect) {
            return Math.toDegrees(2 * Math.atan(tanV));
        }
        double tanH = tanV * sensorAspect;
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
        // angular velocity of the view (deg/s), used for blur and rolling shutter
        float yaw = player.getYRot(), pitch = player.getXRot();
        yawRate = Mth.lerp(0.5, yawRate, Mth.wrapDegrees(yaw - lastYaw) * 20);
        pitchRate = Mth.lerp(0.5, pitchRate, (pitch - lastPitch) * 20);
        lastYaw = yaw;
        lastPitch = pitch;
        // the sensor warms while it is powered (live view) and during long exposures
        boolean liveView = active && chain != null && !"ovf".equals(body().viewfinder()) && !"rangefinder".equals(body().viewfinder());
        SensorHeat.tick(mc.level, player.blockPosition(), liveView, PhotoCapture.capturing());

        // Looking through the camera only makes sense from the photographer's eye (first person),
        // or through a tripod-mounted camera.
        var tripod = TripodView.tripod();
        boolean handUse = player.isUsingItem() && CameraHelper.isCamera(player.getUseItem());
        if (handUse) {
            lowerFromTick = -1;
        } else if (lastHandUse && lowerFromTick < 0) {
            lowerFromTick = player.tickCount; // lowering the camera: the raise animation runs backwards
        }
        lastHandUse = handUse;
        // the camera comes up to the eye first (RAISE_TICKS), then the viewfinder takes over
        boolean using = tripod != null || (handUse && mc.options.getCameraType().isFirstPerson()
                && player.getTicksUsingItem() >= RAISE_TICKS);
        CameraRef wanted = tripod != null ? CameraRef.tripod(tripod.getId()) : CameraRef.hand(player.getUsedItemHand());
        if (using && (!active || !wanted.equals(ref))) {
            if (active) deactivate();
            activate(wanted);
        } else if (!using && active) {
            deactivate();
        }
        tickTimer(mc);
        boolean holding = CameraHelper.handHoldingCamera(player).isPresent() || tripod != null;
        if (holding) {
            // P is also vanilla "social interactions": the camera's playback button wins while holding it.
            while (mc.options.keySocialInteractions.consumeClick()) {
                // swallowed
            }
        }
        if (holding && mc.screen == null) {
            CameraRef r = tripod != null ? CameraRef.tripod(tripod.getId())
                    : CameraRef.hand(CameraHelper.handHoldingCamera(player).get());
            while (RCKeys.PLAYBACK.consumeClick()) {
                if (!active) ref = r;
                if (CameraHelper.isFilm(mc.level.registryAccess(), camera())) {
                    player.displayClientMessage(Component.translatable("message.realcamera.film_no_playback"), true);
                } else {
                    ClientBridge.get().openPlayback();
                }
            }
            while (RCKeys.MENU.consumeClick()) {
                if (!active) {
                    ref = r;
                    settings = CameraHelper.settings(camera());
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
            for (var k : RCKeys.VIEWFINDER_KEYS) RCKeys.drain(k);
        }
        if (settingsDirtyTicks >= 0 && --settingsDirtyTicks < 0) {
            sendSettings();
        }
    }

    /** Ticks it takes to bring the camera up to the eye (or down again). */
    public static final int RAISE_TICKS = 5;
    private static boolean lastHandUse;
    private static int lowerFromTick = -1;

    /**
     * How far the hand-held camera is raised to the eye (0 held normally, 1 at the eye), for the first-person
     * animation: up while the use key is held, down again after it is released.
     */
    public static float raiseProgress(Player player, ItemStack stack, float partialTick) {
        if (player.isUsingItem() && CameraHelper.isCamera(player.getUseItem()) && CameraHelper.isCamera(stack)) {
            return Math.min(1, (player.getTicksUsingItem() + partialTick) / RAISE_TICKS);
        }
        if (lowerFromTick >= 0 && CameraHelper.isCamera(stack)) {
            float t = 1 - (player.tickCount - lowerFromTick + partialTick) / RAISE_TICKS;
            if (t > 0) return t;
            lowerFromTick = -1;
        }
        return 0;
    }

    private static void activate(CameraRef r) {
        ref = r;
        settings = CameraHelper.settings(camera());
        focusDistance = settings.focusDistance();
        afState = AF_IDLE;
        hunting = false;
        buttonLock = false;
        subject = null;
        magnified = false;
        bulbStartMs = -1;
        active = true;
        attackWasDown = true; // ignore a click held from before raising the camera
        refreshChain();
    }

    private static void deactivate() {
        shootWhenFocused = false;
        afState = AF_IDLE;
        hunting = false;
        magnified = false;
        if (bulbStartMs >= 0) {
            PhotoCapture.stopBulb();
            bulbStartMs = -1;
        }
        settings = settings.withFocusDistance(storedFocus());
        focusDistance = settings.focusDistance();
        sendSettings();
        active = false;
    }

    /** The next exposure mode the body has (film bodies may only have M, or P/S/M …). */
    public static int nextMode(CameraBodySpec body, int mode) {
        var modes = body.exposureModes();
        int i = modes.indexOf(body.coerceMode(mode));
        return modes.get((i + 1) % modes.size());
    }

    /** The next shutter type the body has. */
    public static int nextShutterType(CameraBodySpec body, int type) {
        var types = body.shutterTypes();
        int i = types.indexOf(body.coerceShutterType(type));
        return types.get((i + 1) % types.size());
    }

    private static void refreshChain() {
        Minecraft mc = Minecraft.getInstance();
        Optional<OpticalChain> c = OpticalChain.resolve(mc.level.registryAccess(), camera());
        chain = c.orElse(null);
        if (chain != null) {
            CameraBodySpec b = chain.body();
            int mode = b.coerceMode(settings.mode()), type = b.coerceShutterType(settings.shutterType());
            if (mode != settings.mode() || type != settings.shutterType()) {
                update(settings.withMode(mode).withShutterType(type));
            }
        }
    }

    private static void tickActive(Minecraft mc, Player player) {
        refreshChain();
        if (chain == null) {
            return;
        }
        // F is also vanilla "swap hands": never let it move the camera while shooting.
        while (mc.options.keySwapOffhand.consumeClick()) {
            // swallowed
        }
        handleKeys(mc);
        if (tick % 4 == 0) {
            meter(mc);
        }
        exposure = Exposure.solve(settings.mode(), meteredThroughFilter(), settings.evComp() / 3.0, settings.apertureIndex(),
                Math.min(settings.shutterIndex(), ExposureScales.BULB_INDEX - 1), settings.isoIndex(), limits());
        updateFocus(mc);
        boolean manual = settings.focusMode() == CameraSettings.FOCUS_MF || !chain.afAvailable();
        if (manual && settings.peaking() && tick % 4 == 0) {
            updatePeaking(mc);
        }
        if ("rangefinder".equals(body().viewfinder()) && tick % 2 == 0) {
            CameraRays r = rays(mc, 256);
            Vec3 dir = r.direction(0, 0);
            double d = r.planeDistance(r.cast(dir), dir);
            centreDistance = (float) (Double.isInfinite(d) ? 1000 : d);
        }

        boolean attackDown = mc.options.keyAttack.isDown();
        if (attackDown && !attackWasDown) {
            pressShutter(mc, player);
        }
        if (!attackDown && attackWasDown && bulbStartMs >= 0) {
            PhotoCapture.stopBulb();
            bulbStartMs = -1;
        }
        // continuous drive: keep firing while held
        if (attackDown && isBurst() && afState != AF_SEARCHING && System.currentTimeMillis() >= nextBurstMs
                && !shootWhenFocused && attackWasDown && PhotoCapture.canAccept()) {
            fire(mc);
        }
        attackWasDown = attackDown;
        // vanilla would otherwise queue an attack when the camera is lowered
        while (mc.options.keyAttack.consumeClick()) {
            // swallowed
        }
    }

    private static boolean isBurst() {
        int d = settings.drive();
        return d == CameraSettings.DRIVE_CONT_LO || d == CameraSettings.DRIVE_CONT_HI || d == CameraSettings.DRIVE_CONT_MAX;
    }

    private static boolean electronic() {
        return body().coerceShutterType(settings.shutterType()) == CameraSettings.SHUTTER_ELECTRONIC;
    }

    /** Whether the light meter works: none on a 500C/M, a battery meter needs charge. */
    public static boolean meterWorks() {
        String m = body().meter();
        if ("none".equals(m)) return false;
        return !"battery".equals(m) || CameraHelper.hasPower(camera());
    }

    /** Fastest shutter speed (s) a flash syncs at with the current shutter type, or 0 for "any" (leaf). */
    private static double flashSyncS() {
        if (electronic()) return body().shutter().electronicFlashSync();
        return body().shutter().leaf() ? 0 : body().flash().sync();
    }

    /** A flash will fire with this exposure: wanted by the flash mode, present, and the shutter type allows it. */
    private static boolean flashWanted() {
        int mode = settings.flashMode();
        boolean wanted = mode == CameraSettings.FLASH_FILL || mode == CameraSettings.FLASH_REAR
                || (mode == CameraSettings.FLASH_AUTO && meteredEv < 8);
        return wanted && flashGn() > 0 && (!electronic() || body().shutter().electronicFlashSync() > 0);
    }

    /** Frames per second the card takes: its write speed over the file size (film: no buffer at all). */
    private static double writeRate() {
        if (body().isFilm()) return 1000;
        var card = dev.hackdanel.realcamera.camera.Specs.card(Minecraft.getInstance().level.registryAccess(),
                CameraHelper.parts(camera()).card()).orElse(null);
        double mbS = card == null ? 90 : card.writeMbS();
        double mp = sensor().megapixels();
        double mb = switch (settings.fileFormat()) {
            case CameraSettings.FORMAT_RAW -> mp * 1.2;
            case CameraSettings.FORMAT_RAW_JPEG -> mp * 1.6;
            default -> mp * 0.4;
        };
        return mbS / Math.max(1, mb);
    }

    /** Frames in the camera's buffer right now. */
    public static double cameraBuffer() {
        long now = System.currentTimeMillis();
        if (chain != null && bufferFill > 0) {
            bufferFill = Math.max(0, bufferFill - (now - bufferAtMs) / 1000.0 * writeRate());
        }
        bufferAtMs = now;
        return bufferFill;
    }

    /** Frames per second for the current drive mode and shutter type. */
    public static double burstFps() {
        double max = electronic() ? body().shutter().burstElec() : body().shutter().burstMech();
        return switch (settings.drive()) {
            case CameraSettings.DRIVE_CONT_LO -> Math.min(3, max);
            case CameraSettings.DRIVE_CONT_HI -> Math.min(6, max);
            default -> max;
        };
    }

    /** The film loaded in a film camera, or null. */
    @javax.annotation.Nullable
    public static dev.hackdanel.realcamera.spec.FilmStockSpec filmStock() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !body().isFilm()) return null;
        return dev.hackdanel.realcamera.camera.Specs.film(mc.level.registryAccess(), CameraHelper.parts(camera()).card()).orElse(null);
    }

    public static Exposure.Limits limits() {
        float f = focal();
        int wide = ExposureScales.nearestAperture(chain.maxApertureAt(f));
        int narrow = ExposureScales.nearestAperture(chain.minAperture());
        var sh = body().shutter();
        float fastest = electronic() ? sh.elecMin() : sh.mechMin();
        int fast = ExposureScales.nearestShutter(fastest);
        if (flashWanted() && settings.mode() != CameraSettings.MODE_S && settings.mode() != CameraSettings.MODE_M
                && flashSyncS() > 0 && !flashHss()) {
            // with the flash on, automatic modes never pick a speed above the sync speed
            fast = Math.max(fast, ExposureScales.nearestShutter((float) flashSyncS()));
        }
        int slow = ExposureScales.nearestShutter(sh.max());
        int isoMin = ExposureScales.nearestIso(sensor().isoMin());
        int isoMax = ExposureScales.nearestIso(sensor().isoMax());
        int autoMax = Math.min(isoMax, ExposureScales.nearestIso(Exposure.AUTO_ISO_MAX_DEFAULT));
        var stock = filmStock();
        int filmBox = -1;
        if (stock != null) {
            // film: rated at its box speed, or deliberately at another speed (one stop under to three over) to be
            // pulled or pushed in development
            filmBox = ExposureScales.nearestIso(stock.iso());
            isoMin = ExposureScales.nearestIso(stock.iso() / 2.0);
            isoMax = ExposureScales.nearestIso(stock.iso() * 8.0);
            autoMax = filmBox;
        }
        double handheld = Optics.handheldLimit(Optics.focal35(f, sensor().cropFactor()), 0);
        int autoMin = stock != null ? filmBox : Math.max(isoMin, ExposureScales.nearestIso(sensor().baseIso()));
        return new Exposure.Limits(wide, narrow, fast, slow, isoMin, isoMax, autoMax, handheld, autoMin);
    }

    // ------------------------------------------------------------------ metering

    /** How brightly the game lit the metered area (0..1) and the colour temperature of its light (K). */
    private static double meteredGameLight = 1, meteredK = 5500;
    /** Metering weights of the 9 x 6 grid (top-down) and the log-average reflectance of the metered surfaces. */
    private static float[] meterWeights = new float[54];
    private static double meteredAlbedo = -1;

    private static void meter(Minecraft mc) {
        double aspect = sensor().aspect();
        CameraRays rays = new CameraRays(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(),
                sensorVfov(), aspect, 128);
        double day = SceneMeter.daylightEv(mc.level, 1f);
        double ambient = SceneMeter.ambientEv(mc.level);
        int gx = 9, gy = 6;
        double sum = 0, wsum = 0, lightSum = 0, miredSum = 0, albedoSum = 0, albedoW = 0;
        float[] weights = new float[gx * gy];
        int blockVotes = 0;
        double skyGame = SceneMeter.gameLight(mc.level, BlockPos.containing(mc.gameRenderer.getMainCamera().getPosition()).atY(
                mc.level.getMaxBuildHeight()));
        double su = settings.afArea() == CameraSettings.AF_POINT ? settings.afX() : 0;
        double sv = settings.afArea() == CameraSettings.AF_POINT ? settings.afY() : 0;
        for (int y = 0; y < gy; y++) {
            double v = 1 - (y + 0.5) * 2 / gy;
            for (int x = 0; x < gx; x++) {
                double u = (x + 0.5) * 2 / gx - 1;
                double r2 = u * u + v * v;
                double w = switch (settings.metering()) {
                    case CameraSettings.METER_CENTER -> Math.exp(-r2 * 2.5);
                    case CameraSettings.METER_SPOT -> ((u - su) * (u - su) + (v - sv) * (v - sv)) < 0.05 ? 1 : 0;
                    default -> 1;
                };
                if (w <= 0) continue;
                weights[y * gx + x] = (float) w;
                Vec3 dir = rays.direction(u, v);
                var hit = rays.cast(dir);
                // a reflected-light meter: what the surface reflects, not only the light falling on it
                double refl = SceneMeter.reflectanceEv(mc.level, hit);
                double ev = SceneMeter.sampleEv(mc.level, hit, dir, day, ambient) + refl;
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                    albedoSum += Math.log(0.3 * Math.pow(2, refl)) * w;
                    albedoW += w;
                }
                sum += ev * w;
                wsum += w;
                boolean miss = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
                BlockPos lit = miss ? null : hit instanceof net.minecraft.world.phys.BlockHitResult bhr
                        ? bhr.getBlockPos().relative(bhr.getDirection()) : BlockPos.containing(hit.getLocation());
                lightSum += Math.log(miss ? skyGame : SceneMeter.gameLight(mc.level, lit)) * w;
                double fire = miss ? 0 : SceneMeter.fireShare(mc.level, lit, day);
                int skyLevel = miss ? 15 : mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, lit);
                double k = Math.exp(Math.log(SceneMeter.daylightK(mc.level, 1f, skyLevel)) * (1 - fire)
                        + Math.log(SceneMeter.FIRE_K) * fire);
                miredSum += 1e6 / k * w;
                if (fire > 0.5) {
                    blockVotes++;
                }
            }
        }
        if (wsum == 0) {
            Vec3 dir = rays.direction(su, sv);
            sum = SceneMeter.sampleEv(mc.level, rays.cast(dir), dir, day, ambient);
            lightSum = Math.log(skyGame);
            miredSum = 1e6 / 5500;
            wsum = 1;
        }
        meteredEv = Mth.lerp(0.5, meteredEv, sum / wsum);
        meteredGameLight = Math.exp(lightSum / wsum);
        meteredK = 1e6 / (miredSum / wsum);
        meterWeights = weights;
        meteredAlbedo = albedoW > 0 ? Math.exp(albedoSum / albedoW) : -1;
        blockLit = blockVotes > gx * gy / 3;
    }

    // ------------------------------------------------------------------ focus

    private static CameraRays rays(Minecraft mc, double maxDist) {
        return new CameraRays(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(), sensorVfov(),
                sensor().aspect(), maxDist).outline();
    }

    /** Distance the AF system would choose now, and the subject it locked onto (if any). */
    private static double afTargetDistance(Minecraft mc) {
        var cam = mc.gameRenderer.getMainCamera();
        int area = settings.afArea();
        double cu = area == CameraSettings.AF_POINT ? settings.afX() : 0;
        double cv = area == CameraSettings.AF_POINT ? settings.afY() : 0;
        subject = null;
        afU = cu;
        afV = cv;
        afPatch = area == CameraSettings.AF_POINT ? 0.06 : 0.12;
        if (settings.subjectDetect()) {
            double extent = switch (area) {
                case CameraSettings.AF_POINT -> 0.25;
                case CameraSettings.AF_ZONE -> 0.45;
                default -> 1;
            };
            SubjectDetector.Subject s = SubjectDetector.detect(mc.level, mc.getCameraEntity(), cam, sensorVfov(),
                    sensor().aspect(), body().af().subjectDetect(), extent, cu, cv);
            if (s != null && (area != CameraSettings.AF_POINT || Math.hypot(s.cx() - cu, s.cy() - cv) < 0.35)) {
                subject = s;
                afU = s.cx();
                afV = s.cy();
                return Math.max(chain.lens().minFocusM(), s.distance());
            }
        }
        CameraRays rays = rays(mc, 512);
        if (area == CameraSettings.AF_POINT) {
            Vec3 dir = rays.direction(cu, cv);
            double d = rays.planeDistance(rays.cast(dir), dir);
            return Double.isInfinite(d) ? 1000 : d;
        }
        // wide/zone without a subject: closest-subject priority over a grid, weighted to the centre
        double span = area == CameraSettings.AF_ZONE ? 0.35 : 0.7;
        double best = 1000, bestScore = Double.MAX_VALUE;
        for (int y = -2; y <= 2; y++) {
            for (int x = -3; x <= 3; x++) {
                double u = x / 3.0 * span, v = y / 2.0 * span;
                Vec3 dir = rays.direction(u, v);
                double d = rays.planeDistance(rays.cast(dir), dir);
                if (!Double.isInfinite(d)) {
                    double score = d * (1 + 0.6 * (u * u + v * v)); // prefer the middle a little
                    if (score < bestScore) {
                        bestScore = score;
                        best = d;
                        afU = u;
                        afV = v;
                    }
                }
            }
        }
        return best;
    }

    private static void startAf(Minecraft mc) {
        hunting = false;
        double target = Math.max(chain.lens().minFocusM(), afTargetDistance(mc));
        // too dark for the AF sensor: digital bodies light close subjects with their AF-assist lamp (slower, up to about 5 m)
        double marginEv = meteredEv - chain.afLowLightEv(focal());
        boolean dark = marginEv < 0;
        boolean assist = dark && !body().isFilm() && target <= 5.0;
        if (dark && !assist) {
            afState = AF_FAILED;
            afStartMs = System.currentTimeMillis();
            afDurationMs = 600;
            return;
        }
        focusStart = focusDistance;
        afStartMs = System.currentTimeMillis();
        afState = AF_SEARCHING;
        // a recognised subject always has detail; otherwise the AF needs some contrast where it measures
        double contrast = subject != null || assist ? -1 : FrameSampler.contrastAt(afU, afV, afPatch);
        if (contrast >= 0 && contrast < AF_CONTRAST_MIN) {
            // nothing to lock on (clear sky, a blank wall): the lens runs through its whole range and gives up
            hunting = true;
            focusTarget = focusStart;
            afDurationMs = (long) Math.min(3500, 1300 / Math.max(0.15, chain.afSpeed()));
            return;
        }
        boolean weak = contrast >= 0 && contrast < AF_CONTRAST_GOOD;
        focusTarget = (float) withAfError(target, weak, marginEv, assist);
        double travel = Math.abs(Math.log(focusTarget / Math.max(0.1f, focusStart)));
        afDurationMs = (long) ((60 + 90 * Math.min(3, travel)) / Math.max(0.15, chain.afSpeed())
                * (assist ? 2.5 : 1) * (weak ? 1.8 : 1));
    }

    /**
     * Phase-detect AF does not land exactly on the subject: the error is a fraction of the depth of focus at full
     * aperture (so it rarely shows, except wide open), larger near the low-light limit, with the assist lamp and on
     * weak detail. Worked out in dioptres, where the depth of field is symmetric (1/near - 1/far = 2/H).
     */
    private static double withAfError(double target, boolean weak, double marginEv, boolean assist) {
        double f = focal();
        double coc = Optics.FF_COC_MM / sensor().cropFactor();
        double invHyperfocal = chain.maxApertureAt(focal()) * coc / (f * f) * 1000; // 1/m
        double sigma = 0.2;
        if (marginEv < 2) sigma *= 1 + (2 - Math.max(0, marginEv)) * 0.5;
        if (assist) sigma *= 1.5;
        if (weak) sigma *= 1.6;
        double inv = 1 / target + AF_ERROR.nextGaussian() * sigma * invHyperfocal;
        return Math.max(chain.lens().minFocusM(), inv <= 0.001 ? 1000 : 1 / inv);
    }

    /** Where the focus is at {@code now} while the AF motor runs (a hunt goes to the near end, out to infinity and back). */
    private static float focusAt(long now) {
        float t = Mth.clamp((float) (now - afStartMs) / Math.max(1, afDurationMs), 0f, 1f);
        double from = Math.log(Math.max(0.05, focusStart));
        if (!hunting) {
            return (float) Math.exp(Mth.lerp(t, from, Math.log(focusTarget)));
        }
        double near = Math.log(chain.lens().minFocusM()), far = Math.log(300);
        double x;
        if (t < 0.3f) {
            x = Mth.lerp(t / 0.3, from, near);
        } else if (t < 0.8f) {
            x = Mth.lerp((t - 0.3) / 0.5, near, far);
        } else {
            x = Mth.lerp((t - 0.8) / 0.2, far, from);
        }
        return (float) Math.exp(x);
    }

    private static void updateFocus(Minecraft mc) {
        long now = System.currentTimeMillis();
        boolean mf = settings.focusMode() == CameraSettings.FOCUS_MF || !chain.afAvailable();
        if (mf) {
            afState = AF_IDLE;
            hunting = false;
            focusDistance = settings.focusDistance();
            if (tick % 4 == 0 && settings.subjectDetect()) {
                subject = SubjectDetector.detect(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(),
                        sensorVfov(), sensor().aspect(), body().af().subjectDetect(), 1, 0, 0);
            }
            if (shootWhenFocused) {
                shootWhenFocused = false;
                release(mc);
            }
            return;
        }
        if (afState == AF_SEARCHING) {
            focusDistance = focusAt(now);
            if (now - afStartMs >= afDurationMs) {
                if (hunting) {
                    hunting = false;
                    focusDistance = focusStart;
                    afState = AF_FAILED;
                    afStartMs = now;
                    afDurationMs = 600;
                } else {
                    focusDistance = focusTarget;
                    afState = AF_LOCKED;
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(RCSounds.AF_CONFIRM.get(), 1f, 0.6f));
                }
            }
        } else if (afState == AF_FAILED && now - afStartMs > afDurationMs) {
            afState = AF_IDLE;
            shootWhenFocused = false;
        } else if (settings.focusMode() == CameraSettings.FOCUS_AFC && afState == AF_LOCKED && tick % 2 == 0
                && (!buttonLock || RCKeys.AF_ON.isDown())) {
            // continuous AF follows the tracked subject, or whatever the AF area sees (with AF-ON: while it is held)
            double target;
            if (subject != null && (settings.afArea() == CameraSettings.AF_TRACKING || settings.subjectDetect())) {
                SubjectDetector.Subject s = SubjectDetector.track(mc.level, mc.getCameraEntity(),
                        mc.gameRenderer.getMainCamera(), sensorVfov(), sensor().aspect(), subject.entity(), subject.eye());
                subject = s;
                target = s != null ? s.distance() : afTargetDistance(mc);
            } else {
                target = afTargetDistance(mc);
            }
            if (subject == null) {
                double c = FrameSampler.contrastAt(afU, afV, afPatch);
                if (c >= 0 && c < AF_CONTRAST_MIN) {
                    target = focusDistance; // nothing to follow: hold the last distance, as real AF-C does
                }
            }
            target = Math.max(chain.lens().minFocusM(), target);
            // the focus motor has a top speed: AF-S takes ~90 ms per e-fold of distance at speed 1 (see startAf)
            double cur = Math.log(focusDistance), goal = Math.log(target);
            double maxStep = 0.1 / 0.09 * chain.afSpeed();
            focusDistance = (float) Math.exp(cur + Mth.clamp((goal - cur) * 0.6, -maxStep, maxStep));
        } else if (afState == AF_IDLE && tick % 4 == 0 && settings.subjectDetect()) {
            // show what the camera recognises even before focusing (real cameras do)
            subject = SubjectDetector.detect(mc.level, mc.getCameraEntity(), mc.gameRenderer.getMainCamera(),
                    sensorVfov(), sensor().aspect(), body().af().subjectDetect(), 1, 0, 0);
        }
        if (shootWhenFocused && afState == AF_LOCKED) {
            shootWhenFocused = false;
            release(mc);
        }
    }

    /** Peaking: cells whose ray-cast depth is inside the depth of field (drawn as highlights by the HUD). */
    private static void updatePeaking(Minecraft mc) {
        CameraRays rays = rays(mc, 256);
        double coc = Optics.FF_COC_MM / sensor().cropFactor();
        double f = focal(), n = exposure == null ? 4 : exposure.fNumber();
        boolean[] next = new boolean[PEAK_W * PEAK_H];
        float[] depth = rays.depthGrid(PEAK_W, PEAK_H);
        for (int y = 0; y < PEAK_H; y++) {
            for (int x = 0; x < PEAK_W; x++) {
                float d = depth[y * PEAK_W + x];
                if (d > 5000) continue;
                boolean sharp = Optics.blurCircleMm(f, n, focusDistance, d) < coc * 1.5;
                // only edges: neighbouring depth differs (silhouettes) or texture contrast later in the HUD
                boolean edge = x > 0 && y > 0 && x < PEAK_W - 1 && y < PEAK_H - 1
                        && (Math.abs(depth[y * PEAK_W + x + 1] - d) > d * 0.08 || Math.abs(depth[(y + 1) * PEAK_W + x] - d) > d * 0.08
                        || Math.abs(depth[y * PEAK_W + x - 1] - d) > d * 0.08 || Math.abs(depth[(y - 1) * PEAK_W + x] - d) > d * 0.08);
                next[y * PEAK_W + x] = sharp && edge;
            }
        }
        peaking = next;
    }

    // ------------------------------------------------------------------ shutter

    private static void pressShutter(Minecraft mc, Player player) {
        ItemStack cam = camera();
        if (!CameraHelper.canFire(mc.level.registryAccess(), cam)) {
            player.displayClientMessage(Component.translatable("message.realcamera.battery_empty"), true);
            return;
        }
        boolean film = body().isFilm();
        long left = CameraHelper.shotsLeft(mc.level.registryAccess(), cam);
        if (left < 0) {
            player.displayClientMessage(Component.translatable(film ? "message.realcamera.no_film" : "message.realcamera.no_card"), true);
            return;
        }
        if (left == 0) {
            player.displayClientMessage(Component.translatable(film ? "message.realcamera.film_end" : "message.realcamera.card_full"), true);
            return;
        }
        if (!PhotoCapture.canAccept() || cameraBuffer() > body().shutter().bufferShots() - 1) {
            player.displayClientMessage(Component.translatable("message.realcamera.buffer_busy"), true);
            return;
        }
        magnified = false; // half-press leaves magnified view, as on real cameras
        if (timerFireMs >= 0) {
            timerFireMs = -1; // pressing again cancels the self-timer
            armed = null;
            return;
        }
        boolean mf = settings.focusMode() == CameraSettings.FOCUS_MF || !chain.afAvailable();
        if (mf || (settings.focusMode() == CameraSettings.FOCUS_AFC && afState == AF_LOCKED)
                || (buttonLock && afState != AF_SEARCHING)) {
            // back-button focus: the shutter button never focuses, it fires wherever AF-ON left the focus
            release(mc);
        } else if (buttonLock) {
            shootWhenFocused = true; // AF-ON is still focusing: fire when it is done
        } else {
            shootWhenFocused = true;
            startAf(mc);
        }
    }

    /** Focus is done: start the timer (with the shot already composed) or fire now. */
    private static void release(Minecraft mc) {
        int drive = settings.drive();
        if (drive == CameraSettings.DRIVE_TIMER_2 || drive == CameraSettings.DRIVE_TIMER_10) {
            armed = prepare(mc);
            if (armed != null) {
                timerFireMs = System.currentTimeMillis() + (drive == CameraSettings.DRIVE_TIMER_2 ? 2000 : 10000);
            }
            return;
        }
        fire(mc);
    }

    /** A composed exposure waiting to be released (now, or when the self-timer runs out). */
    private record Release(PhotoCapture.Shot shot, int shutterType, float flashGn, boolean bulb, String soundSet) {}

    @Nullable
    private static Release armed;

    /** The self-timer keeps running after the photographer walks away from a tripod (selfies). */
    private static void tickTimer(Minecraft mc) {
        if (armed == null || timerFireMs < 0) {
            return;
        }
        if (System.currentTimeMillis() >= timerFireMs) {
            Release r = armed;
            armed = null;
            timerFireMs = -1;
            if (PhotoCapture.canAccept()) {
                dispatch(mc, r);
            }
        } else if (tick % 20 == 0) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 2f, 0.5f));
        }
    }

    private static void fire(Minecraft mc) {
        Release r = prepare(mc);
        if (r != null) {
            dispatch(mc, r);
        }
    }

    @Nullable
    private static Release prepare(Minecraft mc) {
        if (exposure == null || !PhotoCapture.canAccept()) {
            return null;
        }
        var wear = CameraHelper.wear(camera());
        if (wear.shutterJammed()) {
            mc.player.displayClientMessage(Component.translatable("message.realcamera.shutter_jammed"), true);
            return null;
        }
        boolean bulb = settings.mode() == CameraSettings.MODE_M && settings.shutterIndex() >= ExposureScales.BULB_INDEX;
        double seconds = bulb ? -1 : exposure.seconds();
        int type = electronic() ? CameraSettings.SHUTTER_ELECTRONIC : settings.shutterType();
        ImageParams.Extras extras = extras(mc, seconds < 0 ? 1 : seconds);
        var cam = mc.gameRenderer.getMainCamera();
        PhotoCapture.Shot shot = new PhotoCapture.Shot(ref, chain, settings, exposure, focal(), focusDistance, meteredEv,
                blockLit, CameraHelper.shutterCount(camera()) + 1, camera().getHoverName().getString(),
                CameraHelper.parts(camera()).lens().getHoverName().getString(), seconds, motion(seconds),
                SunTracker.sun(mc.level, mc.getCameraEntity(), cam, sensorVfov(), sensor().aspect()), extras,
                SHAKE.nextInt(), meteredGameLight, meteredK, SensorHeat.sensorC(),
                new PhotoCapture.Metering(meterWeights.clone(), meteredAlbedo));
        return new Release(shot, type, extras.flashAt1m() > 0 ? emittedGn : 0, bulb, body().shutter().sound());
    }

    private static void dispatch(Minecraft mc, Release r) {
        int type = r.shutterType();
        if (type != CameraSettings.SHUTTER_ELECTRONIC) {
            float volume = type == CameraSettings.SHUTTER_EFCS ? 0.6f : 1f;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(RCSounds.shutter(r.soundSet()),
                    type == CameraSettings.SHUTTER_EFCS ? 1.15f : 1f, 0.9f * volume));
        }
        var shot = r.shot();
        PacketDistributor.sendToServer(new Payloads.Shutter(shot.ref(), type, r.flashGn(), new Payloads.ShotInfo(shot.nonce(),
                shot.focal(), (float) shot.exposure().fNumber(), (float) shot.seconds(), (int) shot.exposure().iso(),
                shot.focusDistance(), (float) printedError(shot),
                (float) shot.motion().subjectBlur())));
        bufferFill = cameraBuffer() + 1;
        if (isBurst()) {
            // full speed until the buffer is full, then as fast as the card takes the files
            double cap = Math.max(1, body().shutter().bufferShots());
            double waitS = Math.max(1 / Math.max(0.5, burstFps()), bufferFill >= cap ? (bufferFill - cap + 1) / writeRate() : 0);
            nextBurstMs = System.currentTimeMillis() + (long) (1000 * waitS);
        }
        if (r.bulb()) {
            bulbStartMs = System.currentTimeMillis();
        }
        PhotoCapture.request(r.shot());
    }

    /**
     * The exposure error the finished picture will show (what the frame analysis scores): film rated off its box
     * speed and long exposures (reciprocity) count, and a negative forgives what its latitude covers.
     */
    private static double printedError(PhotoCapture.Shot shot) {
        var stock = filmStock();
        if (stock == null) return shot.exposure().delta();
        double ev = shot.exposure().delta() - Math.log(shot.exposure().iso() / stock.iso()) / Math.log(2)
                - stock.reciprocityLossEv(shot.seconds() < 0 ? 1 : shot.seconds());
        return stock.printedEv(ev);
    }

    /** Flash (TTL power, sync), filters and wear for this exposure. */
    private static ImageParams.Extras extras(Minecraft mc, double seconds) {
        var parts = CameraHelper.parts(camera());
        var regs = mc.level.registryAccess();
        var wear = CameraHelper.wear(camera());
        // filter
        var filter = dev.hackdanel.realcamera.camera.Specs.filter(regs, parts.filter()).orElse(null);
        boolean cracked = parts.filter().getOrDefault(dev.hackdanel.realcamera.registry.RCComponents.DAMAGE.get(), 0f) > 0;
        boolean cpl = filter != null && "cpl".equals(filter.kind());
        double fr = 1, fg = 1, fb = 1;
        if (filter != null && "color".equals(filter.kind())) {
            fr = filter.r();
            fg = filter.g();
            fb = filter.b();
        }
        double fog = Math.max(0, Math.min(1, (wear.moisture() - 40) / 60.0)) + (cracked ? 0.25 : 0);
        double decenter = parts.lens().getOrDefault(dev.hackdanel.realcamera.registry.RCComponents.DAMAGE.get(), 0f);
        float[] dust = new float[wear.dust().size() * 3];
        for (int i = 0; i < wear.dust().size(); i++) {
            var d = wear.dust().get(i);
            dust[i * 3] = d.x();
            dust[i * 3 + 1] = d.y();
            dust[i * 3 + 2] = d.r();
        }
        // flash
        double flashAt1m = 0, flashTop = 1;
        float gn = flashGn();
        emittedGn = 0;
        if (flashWanted() && flashReady()) {
            double iso = exposure.iso(), n = exposure.fNumber();
            double gnIso = gn * Math.sqrt(iso / 100);
            double d = Math.max(0.5, focusDistance);
            double sync = flashSyncS();
            boolean leaf = sync <= 0;
            boolean hss = !parts.flash().isEmpty() && dev.hackdanel.realcamera.camera.Specs.flash(regs, parts.flash())
                    .map(f -> f.hss()).orElse(false);
            // The flash light passes the filter and the lens like the ambient light does. High-speed sync pulses the
            // flash for the whole travel of the slit: entering it costs about 1.7 EV, and every stop faster another.
            double efficiency = Math.pow(2, -lightLossEv());
            if (seconds < sync && !leaf && hss) {
                efficiency *= 0.3 * seconds / sync;
            }
            double full = Math.pow(gnIso / (n * d), 2) * efficiency; // exposure at the subject at full power
            double target = Math.pow(2, settings.flashComp() / 3.0) * (meteredEv > 10 ? 0.5 : 1.0); // fill vs main light
            // TTL meters the pre-flash through the lens: it raises the power for all of the above, up to full
            double power = Math.max(1 / 128.0, Math.min(1, target / full));
            flashAt1m = power * Math.pow(gnIso / n, 2) * efficiency;
            emittedGn = (float) (gn * Math.sqrt(power));
            if (seconds < sync && !leaf && !hss) {
                flashTop = seconds / sync; // the second curtain shades the rest of the frame
            }
            double recycle = dev.hackdanel.realcamera.camera.Specs.flash(regs, parts.flash()).map(f -> f.recycleS()).orElse(3f);
            flashReadyMs = System.currentTimeMillis() + (long) (recycle * 1000 * Math.max(0.1, power));
        }
        var extras = new ImageParams.Extras(flashAt1m, flashTop, cpl, fr, fg, fb, Math.min(1, fog), decenter, dust,
                settings.flashMode() == CameraSettings.FLASH_REAR);
        if (filter != null) {
            extras = extras.withFilterEffects("mist".equals(filter.kind()) ? filter.mist() : 0,
                    "gnd".equals(filter.kind()) ? filter.gndStops() : 0, "star".equals(filter.kind()) ? filter.starPoints() : 0);
        }
        return extras;
    }

    /**
     * Movement during the exposure, as fractions of the image width: hand shake left over after stabilisation
     * (1/focal rule), the pan the player is making, the camera's own velocity (riding, walking) and, for moving
     * subjects, the exposure time (the capture finds the movers). Long exposures record the pan, the ride and the
     * subjects for real (frame accumulation), so only the shake is added for them.
     */
    private static ImageParams.Motion motion(double seconds) {
        Minecraft mc = Minecraft.getInstance();
        double t = seconds < 0 ? 1 : seconds;
        double hfov = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(sensorVfov()) / 2) * sensor().aspect()));
        double focal35 = Optics.focal35(focal(), sensor().cropFactor());
        double stab = settings.stabilization() ? chain.stabilizationStops() : 0;
        double limit = Optics.handheldLimit(focal35, stab);
        // on a tripod there is no hand shake at all
        double shake = ref.isTripod() ? 0 : (1.0 / 1500) * (t / limit) * (0.5 + SHAKE.nextDouble());
        double angle = SHAKE.nextDouble() * Math.PI;
        double bx = shake * Math.cos(angle) * 0.7, by = shake * Math.sin(angle);
        boolean accumulated = seconds < 0 || seconds >= PhotoCapture.LONG_EXPOSURE_S;
        if (!accumulated) {
            // the world moves across the frame against the turn: turning right moves it left, tilting down moves it up
            bx -= yawRate / hfov * t;
            by -= pitchRate / hfov * t;
        }
        double skew = 0, skewY = 0, readout = 0;
        if (electronic()) {
            // rows are read top to bottom: a pan shears the frame, a tilt stretches or squeezes it
            readout = body().shutter().readoutMs() / 1000.0;
            skew = yawRate / hfov * readout;
            skewY = pitchRate / hfov * readout;
        }
        // the camera's own velocity along the frame axes (m/s); a tripod stands still
        var cam = mc.gameRenderer.getMainCamera();
        Vec3 fw = new Vec3(cam.getLookVector()), up = new Vec3(cam.getUpVector()), right = new Vec3(cam.getLeftVector()).scale(-1);
        Vec3 vCam = ref.isTripod() || mc.getCameraEntity() == null ? Vec3.ZERO : velocity(mc.getCameraEntity());
        double localT = accumulated ? 0 : t;
        // how much the main subject smears: what the photographer judges sharpness by (a pan that follows a runner
        // keeps it sharp while the background streaks)
        double subjX = bx, subjY = by;
        if (subject != null && !accumulated && subject.entity() != null) {
            var e = subject.entity();
            Vec3 rel = e.getBoundingBox().getCenter().subtract(cam.getPosition());
            double z = rel.dot(fw);
            if (z > 0.1) {
                Vec3 u = velocity(e).subtract(vCam);
                double a = 1 / (2 * Math.tan(Math.toRadians(hfov) / 2)); // focal length in image widths
                double xr = rel.dot(right) / z, yr = rel.dot(up) / z;
                subjX += a * (u.dot(right) - xr * u.dot(fw)) / z * t;
                subjY += -a * (u.dot(up) - yr * u.dot(fw)) / z * t;
            }
        }
        return new ImageParams.Motion(bx, by, skew, skewY, localT, vCam.dot(right), vCam.dot(up), vCam.dot(fw), readout,
                Math.hypot(subjX, subjY), null);
    }

    /** Velocity of an entity, m/s (from its movement over the last tick: also right for mobs moved by the server). */
    public static Vec3 velocity(net.minecraft.world.entity.Entity e) {
        return new Vec3(e.getX() - e.xo, e.getY() - e.yo, e.getZ() - e.zo).scale(20);
    }

    // ------------------------------------------------------------------ dials

    private static boolean flashHss() {
        var parts = CameraHelper.parts(camera());
        return !parts.flash().isEmpty() && dev.hackdanel.realcamera.camera.Specs.flash(Minecraft.getInstance().level
                .registryAccess(), parts.flash()).map(f -> f.hss()).orElse(false);
    }

    private static void handleKeys(Minecraft mc) {
        while (RCKeys.NEXT_PARAM.consumeClick()) {
            update(settings.withSelectedParam(nextParam(settings.selectedParam(), 1)));
        }
        while (RCKeys.PREV_PARAM.consumeClick()) {
            update(settings.withSelectedParam(nextParam(settings.selectedParam(), -1)));
        }
        while (RCKeys.MODE.consumeClick()) {
            update(settings.withMode(nextMode(body(), settings.mode())));
        }
        while (RCKeys.FOCUS_MODE.consumeClick()) {
            int m = (settings.focusMode() + 1) % CameraSettings.FOCUS_COUNT;
            update(settings.withFocusMode(m).withFocusDistance(focusDistance));
            afState = AF_IDLE;
            hunting = false;
            buttonLock = false;
        }
        while (RCKeys.AF_ON.consumeClick()) {
            if (settings.focusMode() != CameraSettings.FOCUS_MF && chain.afAvailable() && afState != AF_SEARCHING) {
                buttonLock = true;
                startAf(mc);
            }
        }
        while (RCKeys.DISP.consumeClick()) {
            update(settings.withDisp((settings.disp() + 1) % CameraSettings.DISP_COUNT));
        }
        while (RCKeys.MAGNIFY.consumeClick()) {
            if (settings.focusMode() == CameraSettings.FOCUS_MF || !chain.afAvailable()) {
                magnified = !magnified;
            }
        }
        while (RCKeys.FLASH.consumeClick()) {
            update(settings.withFlashMode((settings.flashMode() + 1) % CameraSettings.FLASH_MODE_COUNT));
        }
        while (RCKeys.DRIVE.consumeClick()) {
            update(settings.withDrive((settings.drive() + 1) % CameraSettings.DRIVE_COUNT));
        }
        while (RCKeys.AF_AREA.consumeClick()) {
            update(settings.withAfArea((settings.afArea() + 1) % CameraSettings.AF_AREA_COUNT));
            afState = AF_IDLE;
            hunting = false;
            buttonLock = false;
        }
        float step = 0.08f;
        float dx = 0, dy = 0;
        while (RCKeys.AF_LEFT.consumeClick()) dx -= step;
        while (RCKeys.AF_RIGHT.consumeClick()) dx += step;
        while (RCKeys.AF_UP.consumeClick()) dy += step;
        while (RCKeys.AF_DOWN.consumeClick()) dy -= step;
        if ((dx != 0 || dy != 0) && settings.afArea() == CameraSettings.AF_POINT) {
            update(settings.withAfX(Mth.clamp(settings.afX() + dx, -0.9f, 0.9f))
                    .withAfY(Mth.clamp(settings.afY() + dy, -0.85f, 0.85f)));
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
            if (settings.focusMode() == CameraSettings.FOCUS_MF || !chain.afAvailable()) {
                float d = (float) Mth.clamp(settings.focusDistance() * Math.pow(magnified ? 1.03 : 1.12, steps),
                        chain.lens().minFocusM(), 1000);
                update(settings.withFocusDistance(d));
            }
        } else if (Screen.hasControlDown() || !chain.isZoom()) {
            adjustSelected(steps);
        } else {
            float f = Mth.clamp(focal() * (float) Math.pow(1.06, steps), chain.focalMin(), chain.focalMax());
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
                    int base = mode == CameraSettings.MODE_M ? settings.shutterIndex() : exposure.shutterIdx();
                    // wheel up = faster shutter; past 30" in M comes BULB
                    int max = mode == CameraSettings.MODE_M ? ExposureScales.BULB_INDEX : lim.shutterSlowIdx();
                    int next = Mth.clamp(base - steps, lim.shutterFastIdx(), max);
                    if (next > lim.shutterSlowIdx() && next < ExposureScales.BULB_INDEX) {
                        next = steps < 0 ? ExposureScales.BULB_INDEX : lim.shutterSlowIdx();
                    }
                    update(settings.withShutter(next));
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
            case CameraSettings.PARAM_TILT -> chain.tiltShift().ifPresent(t ->
                    update(settings.withTilt(Mth.clamp(Math.round((settings.tilt() + steps * 0.5f) * 2) / 2f, -t.tiltDeg(), t.tiltDeg()))));
            case CameraSettings.PARAM_SHIFT -> chain.tiltShift().ifPresent(t ->
                    update(settings.withShift(Mth.clamp(Math.round((settings.shift() + steps * 0.5f) * 2) / 2f, -t.shiftMm(), t.shiftMm()))));
            default -> {}
        }
    }

    /** Change settings locally right away; the server copy is updated a few ticks later. */
    public static void update(CameraSettings next) {
        settings = next;
        settingsDirtyTicks = 4;
    }

    /** Used by the camera menu screen when the camera is not raised. */
    public static void setSettingsAndSend(CameraRef r, CameraSettings next) {
        ref = r;
        settings = next;
        sendSettings();
    }

    /** The focus distance to store: the ring's in manual focus (ahead of the per-tick copy), else where AF left it. */
    private static float storedFocus() {
        boolean mf = chain != null && (settings.focusMode() == CameraSettings.FOCUS_MF || !chain.afAvailable());
        return mf ? settings.focusDistance() : focusDistance;
    }

    private static void sendSettings() {
        settingsDirtyTicks = -1;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ItemStack cam = camera();
        if (!CameraHelper.isCamera(cam)) return;
        // only a raised camera has a live focus distance of its own; the menu edits the stored one
        CameraSettings toSend = active ? settings.withFocusDistance(storedFocus()) : settings;
        // predict the server's change on the client copy too: raising the camera again before the server's slot
        // update arrives would otherwise read the old settings back (the focus point and distance "jumping back")
        cam.set(dev.hackdanel.realcamera.registry.RCComponents.CAMERA_SETTINGS.get(), toSend);
        PacketDistributor.sendToServer(new Payloads.UpdateSettings(ref, toSend));
    }

    /** Dev/autotest hook: behaves like a click on the shutter button. */
    public static void debugPressShutter() {
        Minecraft mc = Minecraft.getInstance();
        if (isActive() && mc.player != null) {
            pressShutter(mc, mc.player);
        }
    }

    // ------------------------------------------------------------------ view

    /** A shifted lens frames higher (or lower): the view is rendered pitched by atan(shift / f). */
    @SubscribeEvent
    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!isActive() || PhotoCapture.capturing() || chain == null) {
            return;
        }
        double pitch = chain.shiftPitchDeg(focal(), settings);
        if (pitch != 0) {
            event.setPitch((float) (event.getPitch() - pitch));
        }
    }

    @SubscribeEvent
    static void onFov(ViewportEvent.ComputeFov event) {
        if (!isActive() || !event.usedConfiguredFov()) {
            return;
        }
        var window = Minecraft.getInstance().getWindow();
        double aspect = (double) window.getWidth() / Math.max(1, window.getHeight());
        if (PhotoCapture.capturing()) {
            // the photo covers exactly the sensor frame, never the rangefinder overscan or the magnifier
            event.setFOV(sensorVfov());
            return;
        }
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
            double scale = Math.max(0.03, renderVfov(16.0 / 9) / 70.0);
            event.setMouseSensitivity(event.getMouseSensitivity() * Math.pow(scale, 0.8));
        }
    }

    /** No block selection box in the viewfinder or in photos (its line width also breaks at capture size). */
    @SubscribeEvent
    static void onHighlight(RenderHighlightEvent.Block event) {
        if (isActive() || PhotoCapture.capturing()) {
            event.setCanceled(true);
        }
    }

    /**
     * No hand while looking through the camera. While it is being raised the camera's own first-person transform
     * ({@code CameraItemExtensions}) brings it up to the eye.
     */
    @SubscribeEvent
    static void onRenderHand(RenderHandEvent event) {
        Player p = Minecraft.getInstance().player;
        if (isActive() || PhotoCapture.capturing()) {
            event.setCanceled(true);
        }
    }
}
