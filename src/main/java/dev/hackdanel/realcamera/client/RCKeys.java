package dev.hackdanel.realcamera.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Camera controls (plan §3.1). Active only while holding a camera, so they never steal vanilla keys. */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class RCKeys {
    public static final String CATEGORY = "key.categories.realcamera";

    /** Active while a camera is in either hand and no screen is open. Conflicts with nothing. */
    public static final IKeyConflictContext CAMERA_IN_HAND = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            Minecraft mc = Minecraft.getInstance();
            return mc.screen == null && mc.player != null && CameraHelper.handHoldingCamera(mc.player).isPresent();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return false;
        }
    };

    public static final KeyMapping PREV_PARAM = key("prev_param", GLFW.GLFW_KEY_Z);
    public static final KeyMapping NEXT_PARAM = key("next_param", GLFW.GLFW_KEY_X);
    public static final KeyMapping MODE = key("mode", GLFW.GLFW_KEY_M);
    public static final KeyMapping FOCUS_MODE = key("focus_mode", GLFW.GLFW_KEY_F);
    public static final KeyMapping DISP = key("disp", GLFW.GLFW_KEY_TAB);
    public static final KeyMapping PLAYBACK = key("playback", GLFW.GLFW_KEY_P);
    public static final KeyMapping MENU = key("menu", GLFW.GLFW_KEY_G);
    public static final KeyMapping MAGNIFY = key("magnify", GLFW.GLFW_KEY_H);
    public static final KeyMapping DRIVE = key("drive", GLFW.GLFW_KEY_B);
    public static final KeyMapping FLASH = key("flash", GLFW.GLFW_KEY_V);
    public static final KeyMapping AF_AREA = key("af_area", GLFW.GLFW_KEY_N);
    public static final KeyMapping AF_LEFT = key("af_left", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping AF_RIGHT = key("af_right", GLFW.GLFW_KEY_RIGHT);
    public static final KeyMapping AF_UP = key("af_up", GLFW.GLFW_KEY_UP);
    public static final KeyMapping AF_DOWN = key("af_down", GLFW.GLFW_KEY_DOWN);

    /** The photo encyclopedia: works anywhere in game. */
    public static final KeyMapping JOURNAL = new KeyMapping("key.realcamera.journal",
            net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, CATEGORY);

    /** Keys that only do something with the camera at the eye (drained otherwise). */
    public static final KeyMapping[] VIEWFINDER_KEYS = {PREV_PARAM, NEXT_PARAM, MODE, FOCUS_MODE, DISP, MAGNIFY, DRIVE, FLASH,
            AF_AREA, AF_LEFT, AF_RIGHT, AF_UP, AF_DOWN};

    private RCKeys() {}

    @SubscribeEvent
    static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (JOURNAL.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(new dev.hackdanel.realcamera.client.screen.JournalScreen());
            }
        }
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.realcamera." + name, CAMERA_IN_HAND, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) {
        event.register(JOURNAL);
        event.register(PREV_PARAM);
        event.register(NEXT_PARAM);
        event.register(MODE);
        event.register(FOCUS_MODE);
        event.register(DISP);
        event.register(PLAYBACK);
        event.register(MENU);
        event.register(MAGNIFY);
        event.register(DRIVE);
        event.register(FLASH);
        event.register(AF_AREA);
        event.register(AF_LEFT);
        event.register(AF_RIGHT);
        event.register(AF_UP);
        event.register(AF_DOWN);
    }

    public static void drain(KeyMapping key) {
        while (key.consumeClick()) {
            // discard
        }
    }
}
