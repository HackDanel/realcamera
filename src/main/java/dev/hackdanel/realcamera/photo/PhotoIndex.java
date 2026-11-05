package dev.hackdanel.realcamera.photo;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** Server-side index of every stored photo file (world/data/realcamera_photos.dat). */
public class PhotoIndex extends SavedData {
    public static final String NAME = "realcamera_photos";

    /**
     * @param fileBytes size of the stored in-game copy
     * @param cardBytes space the shot occupies on the memory card (realistic camera file size)
     */
    public record Entry(PhotoMeta meta, long fileBytes, long cardBytes, int prints, boolean revoked, int rating,
                        java.util.Optional<UUID> uploader) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                PhotoMeta.CODEC.fieldOf("meta").forGetter(Entry::meta),
                Codec.LONG.fieldOf("bytes").forGetter(Entry::fileBytes),
                Codec.LONG.optionalFieldOf("card_bytes", 0L).forGetter(Entry::cardBytes),
                Codec.INT.optionalFieldOf("prints", 0).forGetter(Entry::prints),
                Codec.BOOL.optionalFieldOf("revoked", false).forGetter(Entry::revoked),
                Codec.INT.optionalFieldOf("rating", 0).forGetter(Entry::rating),
                net.minecraft.core.UUIDUtil.CODEC.optionalFieldOf("uploader").forGetter(Entry::uploader)
        ).apply(i, Entry::new));

        public Entry(PhotoMeta meta, long fileBytes, long cardBytes, int prints, boolean revoked) {
            this(meta, fileBytes, cardBytes, prints, revoked, 0, java.util.Optional.empty());
        }

        public Entry withPrints(int p) {
            return new Entry(meta, fileBytes, cardBytes, p, revoked, rating, uploader);
        }

        public Entry withRating(int r) {
            return new Entry(meta, fileBytes, cardBytes, prints, revoked, r, uploader);
        }

        public Entry revoke() {
            return new Entry(meta, 0, cardBytes, prints, true, rating, uploader);
        }

        /** Who pays for the file in their quota: the uploader (an editor of someone else's photo) or the author. */
        public UUID owner() {
            return uploader.orElse(meta.authorId());
        }
    }

    public void remove(UUID id) {
        if (entries.remove(id) != null) setDirty();
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    /** Per-player photo count limits set with /photo quota (override the server config). */
    private final Map<UUID, Integer> quotas = new java.util.HashMap<>();

    public static Factory<PhotoIndex> factory() {
        return new Factory<>(PhotoIndex::new, PhotoIndex::load, null);
    }

    public static PhotoIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        PhotoIndex index = new PhotoIndex();
        for (Tag t : tag.getList("photos", Tag.TAG_COMPOUND)) {
            Entry.CODEC.parse(NbtOps.INSTANCE, t).result().ifPresent(e -> index.entries.put(e.meta().id(), e));
        }
        CompoundTag q = tag.getCompound("quotas");
        for (String key : q.getAllKeys()) {
            index.quotas.put(UUID.fromString(key), q.getInt(key));
        }
        return index;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry e : entries.values()) {
            Entry.CODEC.encodeStart(NbtOps.INSTANCE, e).result().ifPresent(list::add);
        }
        tag.put("photos", list);
        CompoundTag q = new CompoundTag();
        quotas.forEach((id, n) -> q.putInt(id.toString(), n));
        tag.put("quotas", q);
        return tag;
    }

    public Entry get(UUID id) {
        return entries.get(id);
    }

    public void put(Entry e) {
        entries.put(e.meta().id(), e);
        setDirty();
    }

    public Collection<Entry> all() {
        return entries.values();
    }

    public java.util.Optional<Integer> quota(UUID player) {
        return java.util.Optional.ofNullable(quotas.get(player));
    }

    public void setQuota(UUID player, Integer limit) {
        if (limit == null) quotas.remove(player); else quotas.put(player, limit);
        setDirty();
    }

    public int countFor(UUID author) {
        int n = 0;
        for (Entry e : entries.values()) {
            if (!e.revoked() && e.owner().equals(author)) n++;
        }
        return n;
    }

    public long bytesFor(UUID author) {
        long n = 0;
        for (Entry e : entries.values()) {
            if (!e.revoked() && e.owner().equals(author)) n += e.fileBytes();
        }
        return n;
    }
}
