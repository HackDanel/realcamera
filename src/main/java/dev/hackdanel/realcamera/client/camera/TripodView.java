package dev.hackdanel.realcamera.client.camera;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.entity.TripodEntity;
import dev.hackdanel.realcamera.network.Payloads;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Looking through a tripod-mounted camera: the game camera sits on the tripod head, the mouse pans
 * and tilts the head, walking is blocked; sneak to step back. A running self-timer keeps going after
 * stepping back, so the photographer can walk into the picture.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class TripodView {
    private static final double MAX_DISTANCE = 4;
    @Nullable
    private static TripodEntity tripod;
    private static int ticks;

    private TripodView() {}

    @Nullable
    public static TripodEntity tripod() {
        if (tripod != null && (tripod.isRemoved() || tripod.camera().isEmpty())) {
            exit();
        }
        return tripod;
    }

    public static void enter(TripodEntity t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.distanceTo(t) > MAX_DISTANCE) {
            return;
        }
        tripod = t;
        ticks = 0;
        // start from where the head points
        mc.player.setYRot(t.getYRot());
        mc.player.setXRot(t.getXRot());
        mc.setCameraEntity(t);
        mc.player.displayClientMessage(Component.translatable("message.realcamera.tripod_enter"), true);
    }

    public static void exit() {
        Minecraft mc = Minecraft.getInstance();
        tripod = null;
        if (mc.player != null && mc.getCameraEntity() != mc.player) {
            mc.setCameraEntity(mc.player);
        }
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        TripodEntity t = tripod();
        if (t == null || mc.player == null) {
            return;
        }
        if (mc.player.isShiftKeyDown() || mc.player.distanceTo(t) > MAX_DISTANCE + 2) {
            exit();
            return;
        }
        // the head follows the mouse (the player's own rotation is the input)
        float yaw = t.getYRot(), pitch = t.getXRot();
        t.aim(mc.player.getYRot(), mc.player.getXRot());
        ticks++;
        if (yaw != t.getYRot() || pitch != t.getXRot() || ticks % 20 == 0) {
            PacketDistributor.sendToServer(new Payloads.TripodAim(t.getId(), t.getYRot(), t.getXRot()));
        }
        if (mc.getCameraEntity() != t && !dev.hackdanel.realcamera.client.capture.PhotoCapture.capturing()) {
            mc.setCameraEntity(t);
        }
    }

    /**
     * The view turns with the mouse every frame, not with the head's rotation (updated 20 times a second, and
     * overwritten by the server's echo of it): panning on the tripod stays as smooth as looking around. Runs
     * before the viewfinder's shift-lens pitch, which is added on top.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGH)
    static void onCameraAngles(net.neoforged.neoforge.client.event.ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        TripodEntity t = tripod;
        if (t == null || mc.player == null || mc.getCameraEntity() != t) {
            return;
        }
        float pt = (float) event.getPartialTick();
        event.setYaw(mc.player.getViewYRot(pt));
        event.setPitch(Math.max(-89, Math.min(89, mc.player.getViewXRot(pt))));
    }

    /** Stand still while looking through the tripod (sneak still works, to leave). */
    @SubscribeEvent
    static void onInput(MovementInputUpdateEvent event) {
        if (tripod != null) {
            var input = event.getInput();
            input.forwardImpulse = 0;
            input.leftImpulse = 0;
            input.up = input.down = input.left = input.right = false;
            input.jumping = false;
        }
    }

    /** The photographer stands behind the camera: do not draw them into their own viewfinder. */
    @SubscribeEvent
    static void onRenderPlayer(net.neoforged.neoforge.client.event.RenderPlayerEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (tripod != null && event.getEntity() == mc.player) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        tripod = null;
    }
}
