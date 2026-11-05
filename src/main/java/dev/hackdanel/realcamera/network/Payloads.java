package dev.hackdanel.realcamera.network;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraRef;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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

    public record UpdateSettings(CameraRef ref, CameraSettings settings) implements CustomPacketPayload {
        public static final Type<UpdateSettings> TYPE = Payloads.newType("update_settings");
        public static final StreamCodec<ByteBuf, UpdateSettings> CODEC = StreamCodec.composite(
                CameraRef.STREAM_CODEC, UpdateSettings::ref, CameraSettings.STREAM_CODEC, UpdateSettings::settings,
                UpdateSettings::new);

        @Override
        public Type<UpdateSettings> type() {
            return TYPE;
        }
    }

    /**
     * The shutter fired: drain battery, count the actuation (mechanical/EFCS) and play the sound to
     * everyone else (not for the silent electronic shutter).
     */
    public record Shutter(CameraRef ref, int shutterType, boolean flash) implements CustomPacketPayload {
        public static final Type<Shutter> TYPE = Payloads.newType("shutter");
        public static final StreamCodec<ByteBuf, Shutter> CODEC = StreamCodec.composite(
                CameraRef.STREAM_CODEC, Shutter::ref, ByteBufCodecs.VAR_INT, Shutter::shutterType, ByteBufCodecs.BOOL,
                Shutter::flash, Shutter::new);

        @Override
        public Type<Shutter> type() {
            return TYPE;
        }
    }

    /** quality/format decide how much card space the shot takes (the in-game copy is always a small JPEG). */
    public record UploadBegin(int requestId, CameraRef ref, PhotoMeta meta, int totalBytes, int quality, int format)
            implements CustomPacketPayload {
        public static final Type<UploadBegin> TYPE = Payloads.newType("upload_begin");
        public static final StreamCodec<ByteBuf, UploadBegin> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, UploadBegin::requestId,
                CameraRef.STREAM_CODEC, UploadBegin::ref,
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

    /** Delete a photo from the card inside the referenced camera. */
    public record DeletePhoto(CameraRef ref, UUID photoId) implements CustomPacketPayload {
        public static final Type<DeletePhoto> TYPE = Payloads.newType("delete_photo");
        public static final StreamCodec<ByteBuf, DeletePhoto> CODEC = StreamCodec.composite(
                CameraRef.STREAM_CODEC, DeletePhoto::ref, UUIDUtil.STREAM_CODEC, DeletePhoto::photoId, DeletePhoto::new);

        @Override
        public Type<DeletePhoto> type() {
            return TYPE;
        }
    }

    /** The photographer looking through a tripod pans/tilts its head. */
    public record TripodAim(int entityId, float yaw, float pitch) implements CustomPacketPayload {
        public static final Type<TripodAim> TYPE = Payloads.newType("tripod_aim");
        public static final StreamCodec<ByteBuf, TripodAim> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TripodAim::entityId, ByteBufCodecs.FLOAT, TripodAim::yaw,
                ByteBufCodecs.FLOAT, TripodAim::pitch, TripodAim::new);

        @Override
        public Type<TripodAim> type() {
            return TYPE;
        }
    }

    /** Saving an edited picture from the computer: a new photo on the card in the given slot. */
    public record EditUploadBegin(int requestId, BlockPos pos, int slot, UUID source, int totalBytes)
            implements CustomPacketPayload {
        public static final Type<EditUploadBegin> TYPE = Payloads.newType("edit_upload_begin");
        public static final StreamCodec<ByteBuf, EditUploadBegin> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, EditUploadBegin::requestId,
                BlockPos.STREAM_CODEC, EditUploadBegin::pos,
                ByteBufCodecs.VAR_INT, EditUploadBegin::slot,
                UUIDUtil.STREAM_CODEC, EditUploadBegin::source,
                ByteBufCodecs.VAR_INT, EditUploadBegin::totalBytes,
                EditUploadBegin::new);

        @Override
        public Type<EditUploadBegin> type() {
            return TYPE;
        }
    }

    public static final int PC_DELETE = 0, PC_RATE = 1, PC_COPY = 2, PC_MOVE = 3, PC_FORMAT = 4;

    /** Catalogue operations on the computer: delete, rate, copy/move to the other card, format. */
    public record ComputerAction(BlockPos pos, int action, int slot, UUID photo, int value) implements CustomPacketPayload {
        public static final Type<ComputerAction> TYPE = Payloads.newType("computer_action");
        public static final StreamCodec<ByteBuf, ComputerAction> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ComputerAction::pos,
                ByteBufCodecs.VAR_INT, ComputerAction::action,
                ByteBufCodecs.VAR_INT, ComputerAction::slot,
                UUIDUtil.STREAM_CODEC, ComputerAction::photo,
                ByteBufCodecs.VAR_INT, ComputerAction::value,
                ComputerAction::new);

        @Override
        public Type<ComputerAction> type() {
            return TYPE;
        }
    }

    public static final int ALBUM_INSERT = 0, ALBUM_TAKE = 1, ALBUM_CAPTION = 2, ALBUM_TAKE_FROM_LECTERN = 3;

    /**
     * Album page edits. hand >= 0: the album in that hand; otherwise the album on the lectern at pos.
     * INSERT puts the print from the other hand (or the first print in the inventory) at index; TAKE
     * removes the print at index into the inventory; CAPTION sets the print's caption.
     */
    public record AlbumAction(int hand, BlockPos pos, int action, int index, String text) implements CustomPacketPayload {
        public static final Type<AlbumAction> TYPE = Payloads.newType("album_action");
        public static final StreamCodec<ByteBuf, AlbumAction> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AlbumAction::hand,
                BlockPos.STREAM_CODEC, AlbumAction::pos,
                ByteBufCodecs.VAR_INT, AlbumAction::action,
                ByteBufCodecs.VAR_INT, AlbumAction::index,
                ByteBufCodecs.stringUtf8(64), AlbumAction::text,
                AlbumAction::new);

        @Override
        public Type<AlbumAction> type() {
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

    /** ratings: 0..5 stars per photo, same order as photos. */
    public record CardContents(UUID cardId, List<PhotoMeta> photos, List<Integer> ratings) implements CustomPacketPayload {
        public static final Type<CardContents> TYPE = Payloads.newType("card_contents");
        public static final StreamCodec<ByteBuf, CardContents> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, CardContents::cardId,
                PhotoMeta.STREAM_CODEC.apply(ByteBufCodecs.list(100_000)), CardContents::photos,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(100_000)), CardContents::ratings,
                CardContents::new);

        @Override
        public Type<CardContents> type() {
            return TYPE;
        }
    }

    /** Open the album screen for an album lying on a lectern (read-only for everyone, take button). */
    public record OpenLecternAlbum(BlockPos pos, List<dev.hackdanel.realcamera.component.PrintData> prints)
            implements CustomPacketPayload {
        public static final Type<OpenLecternAlbum> TYPE = Payloads.newType("open_lectern_album");
        public static final StreamCodec<ByteBuf, OpenLecternAlbum> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, OpenLecternAlbum::pos,
                dev.hackdanel.realcamera.component.PrintData.STREAM_CODEC.apply(ByteBufCodecs.list(256)),
                OpenLecternAlbum::prints,
                OpenLecternAlbum::new);

        @Override
        public Type<OpenLecternAlbum> type() {
            return TYPE;
        }
    }

}
