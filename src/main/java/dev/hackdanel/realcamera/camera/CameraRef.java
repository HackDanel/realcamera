package dev.hackdanel.realcamera.camera;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.InteractionHand;

/** Which camera a message is about: the one in a hand, or the one mounted on a tripod entity. */
public record CameraRef(InteractionHand hand, int tripodId) {
    public static final int NONE = -1;

    public static final StreamCodec<ByteBuf, CameraRef> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL.map(b -> b ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    h -> h == InteractionHand.OFF_HAND), CameraRef::hand,
            ByteBufCodecs.VAR_INT.map(i -> i - 1, i -> i + 1), CameraRef::tripodId,
            CameraRef::new);

    public static CameraRef hand(InteractionHand hand) {
        return new CameraRef(hand, NONE);
    }

    public static CameraRef tripod(int entityId) {
        return new CameraRef(InteractionHand.MAIN_HAND, entityId);
    }

    public boolean isTripod() {
        return tripodId != NONE;
    }
}
