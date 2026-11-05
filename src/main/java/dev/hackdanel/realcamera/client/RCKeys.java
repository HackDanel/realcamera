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

    private RCKeys() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.realcamera." + name, CAMERA_IN_HAND, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) {
        event.register(PREV_PARAM);
        event.register(NEXT_PARAM);
        event.register(MODE);
        event.register(FOCUS_MODE);
        event.register(DISP);
        event.register(PLAYBACK);
        event.register(MENU);
    }

    public static void drain(KeyMapping key) {
        while (key.consumeClick()) {
            // discard
        }
    }
}
