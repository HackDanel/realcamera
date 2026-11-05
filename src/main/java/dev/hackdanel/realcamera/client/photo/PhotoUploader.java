package dev.hackdanel.realcamera.client.photo;

import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

/** Sends developed photos to the server: begin -> accepted -> chunks -> stored. */
public final class PhotoUploader {
    private record Pending(byte[] jpeg, UUID[] assigned) {}

    private static final Map<Integer, Pending> pending = new HashMap<>();
    private static int nextId = 1;

    private PhotoUploader() {}

    public static void upload(InteractionHand hand, PhotoMeta meta, byte[] jpeg, int quality) {
        int id = nextId++;
        pending.put(id, new Pending(jpeg, new UUID[1]));
        PacketDistributor.sendToServer(new Payloads.UploadBegin(id, hand, meta, jpeg.length, quality));
    }

    public static void onStatus(Payloads.UploadStatus msg) {
        Pending p = pending.get(msg.requestId());
        if (p == null) return;
        Minecraft mc = Minecraft.getInstance();
        switch (msg.status()) {
            case Payloads.UPLOAD_ACCEPTED -> {
                UUID photoId = msg.photoId().orElseThrow();
                p.assigned()[0] = photoId;
                byte[] data = p.jpeg();
                for (int i = 0, off = 0; off < data.length; i++, off += Payloads.UPLOAD_CHUNK) {
                    int len = Math.min(Payloads.UPLOAD_CHUNK, data.length - off);
                    byte[] chunk = java.util.Arrays.copyOfRange(data, off, off + len);
                    PacketDistributor.sendToServer(new Payloads.UploadChunk(photoId, i, chunk));
                }
            }
            case Payloads.UPLOAD_STORED -> {
                pending.remove(msg.requestId());
                msg.photoId().ifPresent(id -> PhotoCache.putLocal(id, p.jpeg()));
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable("message.realcamera.saved"), true);
                }
            }
            default -> {
                pending.remove(msg.requestId());
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable(msg.message()), true);
                }
            }
        }
    }

    public static boolean busy() {
        return !pending.isEmpty();
    }

    public static void clear() {
        pending.clear();
    }
}
