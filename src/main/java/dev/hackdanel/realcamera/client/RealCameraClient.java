package dev.hackdanel.realcamera.client;

import dev.hackdanel.realcamera.ClientBridge;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.client.photo.CardListings;
import dev.hackdanel.realcamera.client.photo.PhotoCache;
import dev.hackdanel.realcamera.client.photo.PhotoUploader;
import dev.hackdanel.realcamera.client.render.PhotoFrameRenderer;
import dev.hackdanel.realcamera.client.render.PrintItemRenderer;
import dev.hackdanel.realcamera.client.screen.AssemblyStationScreen;
import dev.hackdanel.realcamera.client.screen.CameraPartsScreen;
import dev.hackdanel.realcamera.client.screen.ChargerScreen;
import dev.hackdanel.realcamera.client.screen.PrinterScreen;
import dev.hackdanel.realcamera.registry.RCEntities;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.registry.RCMenus;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/** Client entry point. Never loaded on a dedicated server. */
@Mod(value = RealCamera.MODID, dist = Dist.CLIENT)
public final class RealCameraClient {
    public RealCameraClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        ClientBridge.Holder.instance = new ClientBridgeImpl();
        dev.hackdanel.realcamera.entity.TripodEntity.lookThrough = dev.hackdanel.realcamera.client.camera.TripodView::enter;
        modBus.addListener(RealCameraClient::screens);
        modBus.addListener(RealCameraClient::renderers);
        modBus.addListener(RealCameraClient::extensions);
        NeoForge.EVENT_BUS.addListener(RealCameraClient::onLogout);
    }

    private static void screens(RegisterMenuScreensEvent event) {
        event.register(RCMenus.CAMERA_PARTS.get(), CameraPartsScreen::new);
        event.register(RCMenus.ASSEMBLY_STATION.get(), AssemblyStationScreen::new);
        event.register(RCMenus.CHARGER.get(), ChargerScreen::new);
        event.register(RCMenus.PRINTER.get(), PrinterScreen::new);
        event.register(RCMenus.CAMERA_BAG.get(), dev.hackdanel.realcamera.client.screen.CameraBagScreen::new);
        event.register(RCMenus.COMPUTER.get(), dev.hackdanel.realcamera.client.screen.ComputerScreen::new);
    }

    private static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(RCEntities.PHOTO_FRAME.get(), PhotoFrameRenderer::new);
        event.registerEntityRenderer(RCEntities.TRIPOD.get(), dev.hackdanel.realcamera.client.render.TripodRenderer::new);
        event.registerBlockEntityRenderer(dev.hackdanel.realcamera.registry.RCBlockEntities.DISPLAY.get(),
                dev.hackdanel.realcamera.client.render.DisplayRenderer::new);
    }

    private static void extensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private PrintItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) renderer = new PrintItemRenderer();
                return renderer;
            }
        }, RCItems.PHOTO_PRINT.get());
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        PhotoCache.clear();
        CardListings.clear();
        PhotoUploader.clear();
    }
}
