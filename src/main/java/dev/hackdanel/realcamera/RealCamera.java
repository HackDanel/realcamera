package dev.hackdanel.realcamera;

import com.mojang.logging.LogUtils;
import dev.hackdanel.realcamera.config.ClientConfig;
import dev.hackdanel.realcamera.config.ServerConfig;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(RealCamera.MODID)
public final class RealCamera {
    public static final String MODID = "realcamera";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RealCamera(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
