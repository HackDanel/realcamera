package dev.hackdanel.realcamera.client;

import dev.hackdanel.realcamera.ClientBridge;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.photo.CardListings;
import dev.hackdanel.realcamera.client.photo.PhotoCache;
import dev.hackdanel.realcamera.client.photo.PhotoUploader;
import dev.hackdanel.realcamera.client.screen.CameraMenuScreen;
import dev.hackdanel.realcamera.client.screen.PlaybackScreen;
import dev.hackdanel.realcamera.client.screen.PrintViewerScreen;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.registry.RCComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

final class ClientBridgeImpl implements ClientBridge {
    @Override
    public void openPrintViewer(ItemStack print) {
        PrintData data = print.get(RCComponents.PRINT.get());
        if (data != null) {
            Minecraft.getInstance().setScreen(new PrintViewerScreen(data));
        }
    }

    @Override
    public void openCameraMenu() {
        Minecraft.getInstance().setScreen(new CameraMenuScreen(Viewfinder.hand()));
    }

    @Override
    public void openPlayback() {
        PlaybackScreen.open(Minecraft.getInstance());
    }

    @Override
    public void handlePayload(CustomPacketPayload payload) {
        switch (payload) {
            case Payloads.UploadStatus s -> PhotoUploader.onStatus(s);
            case Payloads.PhotoData d -> PhotoCache.onData(d);
            case Payloads.PhotoGone g -> PhotoCache.onGone(g.id());
            case Payloads.CardContents c -> CardListings.onContents(c);
            default -> {}
        }
    }
}
