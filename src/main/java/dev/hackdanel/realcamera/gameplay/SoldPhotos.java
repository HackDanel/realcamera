package dev.hackdanel.realcamera.gameplay;

import dev.hackdanel.realcamera.photo.PhotoIndex;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.photo.PhotoStorage;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Photos already sold to a newspaper (by their original shot: prints, darkroom enlargements and edits of one
 * frame are the same picture). A newspaper prints a scoop once.
 */
public class SoldPhotos extends SavedData {
    public static final String NAME = "realcamera_sold_photos";
    private final Set<UUID> sold = new HashSet<>();

    public static Factory<SoldPhotos> factory() {
        return new Factory<>(SoldPhotos::new, SoldPhotos::load, null);
    }

    public static SoldPhotos get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), NAME);
    }

    public static SoldPhotos load(CompoundTag tag, HolderLookup.Provider registries) {
        SoldPhotos s = new SoldPhotos();
        for (Tag t : tag.getList("sold", Tag.TAG_INT_ARRAY)) {
            s.sold.add(NbtUtils.loadUUID(t));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID id : sold) list.add(NbtUtils.createUUID(id));
        tag.put("sold", list);
        return tag;
    }

    /** The original shot a photo comes from (following edits and enlargements back). */
    public static UUID original(MinecraftServer server, PhotoMeta meta) {
        PhotoIndex index = PhotoStorage.index(server);
        PhotoMeta m = meta;
        for (int i = 0; i < 16 && m.editedFrom().isPresent(); i++) {
            PhotoIndex.Entry parent = index.get(m.editedFrom().get());
            if (parent == null) return m.editedFrom().get();
            m = parent.meta();
        }
        return m.id();
    }

    public boolean isSold(MinecraftServer server, PhotoMeta meta) {
        return sold.contains(original(server, meta));
    }

    public void markSold(MinecraftServer server, PhotoMeta meta) {
        if (sold.add(original(server, meta))) setDirty();
    }
}
