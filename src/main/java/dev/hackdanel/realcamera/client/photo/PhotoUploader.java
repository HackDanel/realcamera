package dev.hackdanel.realcamera.client.photo;

import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends developed photos to the server one at a time (begin -> accepted -> chunks -> stored), so a
 * burst simply queues up behind the shot being written, like a camera's card buffer.
 */
public final class PhotoUploader {
    /** cache: keep the uploaded bytes as the local copy (not for film: the server changes it when developing). */
    private record Job(int id, java.util.function.IntFunction<CustomPacketPayload> begin, byte[] jpeg,
                       java.util.function.Consumer<UUID> onStored, java.util.function.Consumer<String> onRejected,
                       boolean cache) {}

    private static final Deque<Job> queue = new ArrayDeque<>();
    private static Job current;
    private static int nextId = 1;

    private PhotoUploader() {}

    public static void upload(dev.hackdanel.realcamera.camera.CameraRef hand, PhotoMeta meta, byte[] jpeg, int quality,
                              int format, boolean film, int shotNonce, java.util.function.Consumer<UUID> onStored) {
        queue.addLast(new Job(nextId++, id -> new Payloads.UploadBegin(id, hand, meta, jpeg.length, quality, format, shotNonce),
                jpeg, onStored, key -> {}, !film));
        pump();
    }

    /** An edited picture saved from the computer as a new photo on the card in it. */
    public static void uploadEdit(net.minecraft.core.BlockPos computer, UUID source, int slot, byte[] jpeg,
                                  java.util.function.Consumer<UUID> onStored, java.util.function.Consumer<String> onRejected) {
        queue.addLast(new Job(nextId++, id -> new Payloads.EditUploadBegin(id, computer, slot, source, jpeg.length),
                jpeg, onStored, onRejected, true));
        pump();
    }

    private static void pump() {
        if (current != null || queue.isEmpty()) {
            return;
        }
        current = queue.pollFirst();
        PacketDistributor.sendToServer(current.begin.apply(current.id));
    }

    public static void onStatus(Payloads.UploadStatus msg) {
        Job job = current;
        if (job == null || job.id != msg.requestId()) return;
        Minecraft mc = Minecraft.getInstance();
        switch (msg.status()) {
            case Payloads.UPLOAD_ACCEPTED -> {
                UUID photoId = msg.photoId().orElseThrow();
                byte[] data = job.jpeg;
                for (int i = 0, off = 0; off < data.length; i++, off += Payloads.UPLOAD_CHUNK) {
                    int len = Math.min(Payloads.UPLOAD_CHUNK, data.length - off);
                    PacketDistributor.sendToServer(new Payloads.UploadChunk(photoId, i,
                            java.util.Arrays.copyOfRange(data, off, off + len)));
                }
            }
            case Payloads.UPLOAD_STORED -> {
                current = null;
                msg.photoId().ifPresent(id -> {
                    if (job.cache) PhotoCache.putLocal(id, job.jpeg);
                    job.onStored.accept(id);
                });
                if (mc.player != null && queue.isEmpty()) {
                    mc.player.displayClientMessage(Component.translatable("message.realcamera.saved"), true);
                }
                pump();
            }
            default -> {
                current = null;
                job.onRejected.accept(msg.message());
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.translatable(msg.message()), true);
                }
                pump();
            }
        }
    }

    public static boolean busy() {
        return current != null || !queue.isEmpty();
    }

    public static int pending() {
        return queue.size() + (current == null ? 0 : 1);
    }

    public static void clear() {
        queue.clear();
        current = null;
    }
}
