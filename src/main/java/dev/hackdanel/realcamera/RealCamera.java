package dev.hackdanel.realcamera;

import com.mojang.logging.LogUtils;
import dev.hackdanel.realcamera.config.ClientConfig;
import dev.hackdanel.realcamera.config.ServerConfig;
import dev.hackdanel.realcamera.registry.RCBlockEntities;
import dev.hackdanel.realcamera.registry.RCBlocks;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCCreativeTab;
import dev.hackdanel.realcamera.registry.RCEntities;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.registry.RCMenus;
import dev.hackdanel.realcamera.registry.RCRecipes;
import dev.hackdanel.realcamera.registry.RCSounds;
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
        RCComponents.REGISTER.register(modBus);
        RCBlocks.REGISTER.register(modBus);
        RCItems.REGISTER.register(modBus);
        RCBlockEntities.REGISTER.register(modBus);
        RCMenus.REGISTER.register(modBus);
        RCEntities.REGISTER.register(modBus);
        RCRecipes.TYPES.register(modBus);
        RCRecipes.SERIALIZERS.register(modBus);
        RCSounds.REGISTER.register(modBus);
        RCCreativeTab.REGISTER.register(modBus);
        dev.hackdanel.realcamera.gameplay.AddItemsLootModifier.REGISTER.register(modBus);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
