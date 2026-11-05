package dev.hackdanel.realcamera.network;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

/** All RealCamera network messages. */
public final class Payloads {
    /** Client -> server chunk size; vanilla caps serverbound custom payloads at 32 KiB. */
    public static final int UPLOAD_CHUNK = 30_000;
    /** Server -> client chunk size. */
    public static final int DOWNLOAD_CHUNK = 256 * 1024;

    private static final StreamCodec<ByteBuf, InteractionHand> HAND = ByteBufCodecs.BOOL.map(
            b -> b ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, h -> h == InteractionHand.OFF_HAND);

    private Payloads() {}

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> newType(String name) {
        return new CustomPacketPayload.Type<>(RealCamera.id(name));
    }

    // ---------- client -> server ----------

    public record UpdateSettings(InteractionHand hand, CameraSettings settings) implements CustomPacketPayload {
        public static final Type<UpdateSettings> TYPE = Payloads.newType("update_settings");
        public static final StreamCodec<ByteBuf, UpdateSettings> CODEC = StreamCodec.composite(
                HAND, UpdateSettings::hand, CameraSettings.STREAM_CODEC, UpdateSettings::settings, UpdateSettings::new);

        @Override
        public Type<UpdateSettings> type() {
            return TYPE;
        }
    }

    /**
     * The shutter fired: drain battery, count the actuation (mechanical/EFCS) and play the sound to
     * everyone else (not for the silent electronic shutter).
     */
    public record Shutter(InteractionHand hand, int shutterType) implements CustomPacketPayload {
        public static final Type<Shutter> TYPE = Payloads.newType("shutter");
        public static final StreamCodec<ByteBuf, Shutter> CODEC = StreamCodec.composite(
                HAND, Shutter::hand, ByteBufCodecs.VAR_INT, Shutter::shutterType, Shutter::new);

        @Override
        public Type<Shutter> type() {
            return TYPE;
        }
    }

    /** quality/format decide how much card space the shot takes (the in-game copy is always a small JPEG). */
    public record UploadBegin(int requestId, InteractionHand hand, PhotoMeta meta, int totalBytes, int quality, int format)
            implements CustomPacketPayload {
        public static final Type<UploadBegin> TYPE = Payloads.newType("upload_begin");
        public static final StreamCodec<ByteBuf, UploadBegin> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, UploadBegin::requestId,
                HAND, UploadBegin::hand,
                PhotoMeta.STREAM_CODEC, UploadBegin::meta,
                ByteBufCodecs.VAR_INT, UploadBegin::totalBytes,
                ByteBufCodecs.VAR_INT, UploadBegin::quality,
                ByteBufCodecs.VAR_INT, UploadBegin::format,
                UploadBegin::new);

        @Override
        public Type<UploadBegin> type() {
            return TYPE;
        }
    }

    public record UploadChunk(UUID photoId, int index, byte[] data) implements CustomPacketPayload {
        public static final Type<UploadChunk> TYPE = Payloads.newType("upload_chunk");
        public static final StreamCodec<ByteBuf, UploadChunk> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, UploadChunk::photoId,
                ByteBufCodecs.VAR_INT, UploadChunk::index,
                ByteBufCodecs.byteArray(UPLOAD_CHUNK), UploadChunk::data,
                UploadChunk::new);

        @Override
        public Type<UploadChunk> type() {
            return TYPE;
        }
    }

    public record RequestPhoto(UUID id) implements CustomPacketPayload {
        public static final Type<RequestPhoto> TYPE = Payloads.newType("request_photo");
        public static final StreamCodec<ByteBuf, RequestPhoto> CODEC = UUIDUtil.STREAM_CODEC.map(RequestPhoto::new, RequestPhoto::id);

        @Override
        public Type<RequestPhoto> type() {
            return TYPE;
        }
    }

    public record RequestCard(UUID cardId) implements CustomPacketPayload {
        public static final Type<RequestCard> TYPE = Payloads.newType("request_card");
        public static final StreamCodec<ByteBuf, RequestCard> CODEC = UUIDUtil.STREAM_CODEC.map(RequestCard::new, RequestCard::cardId);

        @Override
        public Type<RequestCard> type() {
            return TYPE;
        }
    }

    /** Delete a photo from the card inside the camera held in {@code hand}. */
    public record DeletePhoto(InteractionHand hand, UUID photoId) implements CustomPacketPayload {
        public static final Type<DeletePhoto> TYPE = Payloads.newType("delete_photo");
        public static final StreamCodec<ByteBuf, DeletePhoto> CODEC = StreamCodec.composite(
                HAND, DeletePhoto::hand, UUIDUtil.STREAM_CODEC, DeletePhoto::photoId, DeletePhoto::new);

        @Override
        public Type<DeletePhoto> type() {
            return TYPE;
        }
    }

    // ---------- server -> client ----------

    public static final int UPLOAD_ACCEPTED = 0, UPLOAD_STORED = 1, UPLOAD_REJECTED = 2;

    public record UploadStatus(int requestId, int status, Optional<UUID> photoId, String message)
            implements CustomPacketPayload {
        public static final Type<UploadStatus> TYPE = Payloads.newType("upload_status");
        public static final StreamCodec<ByteBuf, UploadStatus> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, UploadStatus::requestId,
                ByteBufCodecs.VAR_INT, UploadStatus::status,
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), UploadStatus::photoId,
                ByteBufCodecs.STRING_UTF8, UploadStatus::message,
                UploadStatus::new);

        @Override
        public Type<UploadStatus> type() {
            return TYPE;
        }
    }

    public record PhotoData(UUID id, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<PhotoData> TYPE = Payloads.newType("photo_data");
        public static final StreamCodec<ByteBuf, PhotoData> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, PhotoData::id,
                ByteBufCodecs.VAR_INT, PhotoData::index,
                ByteBufCodecs.VAR_INT, PhotoData::total,
                ByteBufCodecs.byteArray(DOWNLOAD_CHUNK), PhotoData::data,
                PhotoData::new);

        @Override
        public Type<PhotoData> type() {
            return TYPE;
        }
    }

    /** The photo does not exist or was removed by an admin; clients show a placeholder. */
    public record PhotoGone(UUID id) implements CustomPacketPayload {
        public static final Type<PhotoGone> TYPE = Payloads.newType("photo_gone");
        public static final StreamCodec<ByteBuf, PhotoGone> CODEC = UUIDUtil.STREAM_CODEC.map(PhotoGone::new, PhotoGone::id);

        @Override
        public Type<PhotoGone> type() {
            return TYPE;
        }
    }

    /** Admin /photo view: open the full-screen viewer for any stored photo. */
    public record ViewPhoto(PhotoMeta meta) implements CustomPacketPayload {
        public static final Type<ViewPhoto> TYPE = Payloads.newType("view_photo");
        public static final StreamCodec<ByteBuf, ViewPhoto> CODEC = PhotoMeta.STREAM_CODEC.map(ViewPhoto::new, ViewPhoto::meta);

        @Override
        public Type<ViewPhoto> type() {
            return TYPE;
        }
    }

    public record CardContents(UUID cardId, List<PhotoMeta> photos) implements CustomPacketPayload {
        public static final Type<CardContents> TYPE = Payloads.newType("card_contents");
        public static final StreamCodec<ByteBuf, CardContents> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, CardContents::cardId,
                PhotoMeta.STREAM_CODEC.apply(ByteBufCodecs.list(100_000)), CardContents::photos,
                CardContents::new);

        @Override
        public Type<CardContents> type() {
            return TYPE;
        }
    }

}
