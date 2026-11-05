package dev.hackdanel.realcamera.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Memory card state carried on the item. The photo list itself lives on the server (CardStorage),
 * keyed by {@link #id}; the item only mirrors usage so the client can show "shots left" offline.
 */
public record CardData(Optional<UUID> id, long usedBytes, int photoCount) {
    public static final CardData EMPTY = new CardData(Optional.empty(), 0, 0);

    public static final Codec<CardData> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.optionalFieldOf("id").forGetter(CardData::id),
            Codec.LONG.fieldOf("used_bytes").forGetter(CardData::usedBytes),
            Codec.INT.fieldOf("photo_count").forGetter(CardData::photoCount)
    ).apply(i, CardData::new));

    public static final StreamCodec<ByteBuf, CardData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), CardData::id,
            ByteBufCodecs.VAR_LONG, CardData::usedBytes,
            ByteBufCodecs.VAR_INT, CardData::photoCount,
            CardData::new);
}
