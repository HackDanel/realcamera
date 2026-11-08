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
        modBus.addListener(RealCameraClient::setup);
        modBus.addListener(dev.hackdanel.realcamera.client.render.NeckCameraLayer::addLayers);
        NeoForge.EVENT_BUS.addListener(RealCameraClient::onLogout);
        NeoForge.EVENT_BUS.addListener(dev.hackdanel.realcamera.client.render.CameraPose::onRenderPlayerPre);
        NeoForge.EVENT_BUS.addListener(dev.hackdanel.realcamera.client.render.CameraPose::onRenderPlayerPost);
    }

    private static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        // the photo's depth of field on the graphics card (Java does it when this fails)
        dev.hackdanel.realcamera.client.render.GpuLensBlur.register();
        // film roll icon: canister / developed strip / ruined strip
        event.enqueueWork(() -> net.minecraft.client.renderer.item.ItemProperties.register(
                dev.hackdanel.realcamera.registry.RCItems.FILM_ROLL.get(), RealCamera.id("state"),
                (stack, level, entity, seed) -> {
                    var roll = stack.get(dev.hackdanel.realcamera.registry.RCComponents.FILM.get());
                    if (roll == null) return 0;
                    return switch (roll.state()) {
                        case dev.hackdanel.realcamera.component.FilmRoll.DEVELOPED -> 0.3f;
                        case dev.hackdanel.realcamera.component.FilmRoll.RUINED -> 0.4f;
                        default -> 0f;
                    };
                }));
    }

    private static void screens(RegisterMenuScreensEvent event) {
        event.register(RCMenus.CAMERA_PARTS.get(), CameraPartsScreen::new);
        event.register(RCMenus.ASSEMBLY_STATION.get(), AssemblyStationScreen::new);
        event.register(RCMenus.CHARGER.get(), ChargerScreen::new);
        event.register(RCMenus.PRINTER.get(), PrinterScreen::new);
        event.register(RCMenus.CAMERA_BAG.get(), dev.hackdanel.realcamera.client.screen.CameraBagScreen::new);
        event.register(RCMenus.COMPUTER.get(), dev.hackdanel.realcamera.client.screen.ComputerScreen::new);
        event.register(RCMenus.DEVELOPING_TANK.get(), dev.hackdanel.realcamera.client.screen.DevelopingTankScreen::new);
        event.register(RCMenus.ENLARGER.get(), dev.hackdanel.realcamera.client.screen.EnlargerScreen::new);
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
        event.registerItem(dev.hackdanel.realcamera.client.render.CameraItemExtensions.INSTANCE, RCItems.CAMERA_BODY.get());
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        PhotoCache.clear();
        CardListings.clear();
        PhotoUploader.clear();
        dev.hackdanel.realcamera.client.render.NeckCameraLayer.clear();
    }
}
