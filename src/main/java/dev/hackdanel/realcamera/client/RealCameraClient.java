package dev.hackdanel.realcamera.client;

import dev.hackdanel.realcamera.RealCamera;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client entry point. Never loaded on a dedicated server. */
@Mod(value = RealCamera.MODID, dist = Dist.CLIENT)
public final class RealCameraClient {
    public RealCameraClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
