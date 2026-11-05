package dev.hackdanel.realcamera.client.photo;

import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends developed photos to the server one at a time (begin -> accepted -> chunks -> stored), so a
 * burst simply queues up behind the shot being written, like a camera's card buffer.
 */
public final class PhotoUploader {
    private record Job(int id, InteractionHand hand, PhotoMeta meta, byte[] jpeg, int quality, int format) {}

    private static final Deque<Job> queue = new ArrayDeque<>();
    private static Job current;
    private static int nextId = 1;

    private PhotoUploader() {}

    public static void upload(InteractionHand hand, PhotoMeta meta, byte[] jpeg, int quality, int format) {
        queue.addLast(new Job(nextId++, hand, meta, jpeg, quality, format));
        pump();
    }

    private static void pump() {
        if (current != null || queue.isEmpty()) {
            return;
        }
        current = queue.pollFirst();
        PacketDistributor.sendToServer(new Payloads.UploadBegin(current.id, current.hand, current.meta,
                current.jpeg.length, current.quality, current.format));
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
                msg.photoId().ifPresent(id -> PhotoCache.putLocal(id, job.jpeg));
                if (mc.player != null && queue.isEmpty()) {
                    mc.player.displayClientMessage(Component.translatable("message.realcamera.saved"), true);
                }
                pump();
            }
            default -> {
                current = null;
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
