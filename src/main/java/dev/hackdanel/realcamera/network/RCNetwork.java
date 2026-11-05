package dev.hackdanel.realcamera.network;

import dev.hackdanel.realcamera.ClientBridge;
import dev.hackdanel.realcamera.RealCamera;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = RealCamera.MODID)
public final class RCNetwork {
    public static final String VERSION = "4";

    private RCNetwork() {}

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(VERSION);
        r.playToServer(Payloads.UpdateSettings.TYPE, Payloads.UpdateSettings.CODEC,
                (p, ctx) -> ServerPhotoService.updateSettings((ServerPlayer) ctx.player(), p));
        r.playToServer(Payloads.Shutter.TYPE, Payloads.Shutter.CODEC,
                (p, ctx) -> ServerPhotoService.shutter((ServerPlayer) ctx.player(), p));
        r.playToServer(Payloads.UploadBegin.TYPE, Payloads.UploadBegin.CODEC,
                (p, ctx) -> ServerPhotoService.uploadBegin((ServerPlayer) ctx.player(), p));
        r.playToServer(Payloads.UploadChunk.TYPE, Payloads.UploadChunk.CODEC,
                (p, ctx) -> ServerPhotoService.uploadChunk((ServerPlayer) ctx.player(), p));
        r.playToServer(Payloads.RequestPhoto.TYPE, Payloads.RequestPhoto.CODEC,
                (p, ctx) -> ServerPhotoService.requestPhoto((ServerPlayer) ctx.player(), p));
        r.playToServer(Payloads.RequestCard.TYPE, Payloads.RequestCard.CODEC,
                (p, ctx) -> ServerPhotoService.requestCard((ServerPlayer) ctx.player(), p));
        r.playToServer(Payloads.DeletePhoto.TYPE, Payloads.DeletePhoto.CODEC,
                (p, ctx) -> ServerPhotoService.deletePhoto((ServerPlayer) ctx.player(), p));

        r.playToServer(Payloads.TripodAim.TYPE, Payloads.TripodAim.CODEC,
                (p, ctx) -> ServerPhotoService.aimTripod((ServerPlayer) ctx.player(), p));
        r.playToServer(Payloads.RewindFilm.TYPE, Payloads.RewindFilm.CODEC,
                (p, ctx) -> ServerPhotoService.rewind((ServerPlayer) ctx.player(), p.ref()));
        r.playToServer(Payloads.EditUploadBegin.TYPE, Payloads.EditUploadBegin.CODEC,
                (p, ctx) -> ServerPhotoService.editUploadBegin((ServerPlayer) ctx.player(), p));
        r.playToServer(Payloads.ComputerAction.TYPE, Payloads.ComputerAction.CODEC,
                (p, ctx) -> dev.hackdanel.realcamera.blockentity.ComputerBlockEntity.handleAction((ServerPlayer) ctx.player(), p));
        r.playToServer(Payloads.AlbumAction.TYPE, Payloads.AlbumAction.CODEC,
                (p, ctx) -> dev.hackdanel.realcamera.item.PhotoAlbumItem.handleAction((ServerPlayer) ctx.player(), p));
        r.playToClient(Payloads.OpenLecternAlbum.TYPE, Payloads.OpenLecternAlbum.CODEC, (p, ctx) -> ClientBridge.get().handlePayload(p));
        r.playToClient(Payloads.UploadStatus.TYPE, Payloads.UploadStatus.CODEC, (p, ctx) -> ClientBridge.get().handlePayload(p));
        r.playToClient(Payloads.PhotoData.TYPE, Payloads.PhotoData.CODEC, (p, ctx) -> ClientBridge.get().handlePayload(p));
        r.playToClient(Payloads.PhotoGone.TYPE, Payloads.PhotoGone.CODEC, (p, ctx) -> ClientBridge.get().handlePayload(p));
        r.playToClient(Payloads.ViewPhoto.TYPE, Payloads.ViewPhoto.CODEC, (p, ctx) -> ClientBridge.get().handlePayload(p));
        r.playToClient(Payloads.JournalSync.TYPE, Payloads.JournalSync.CODEC, (p, ctx) -> ClientBridge.get().handlePayload(p));
        r.playToClient(Payloads.CardContents.TYPE, Payloads.CardContents.CODEC, (p, ctx) -> ClientBridge.get().handlePayload(p));
    }
}
