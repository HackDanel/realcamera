package dev.hackdanel.realcamera.photo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/** Which photos are on which memory card (world/data/realcamera_cards.dat). */
public class CardStorage extends SavedData {
    public static final String NAME = "realcamera_cards";

    private final Map<UUID, List<UUID>> cards = new HashMap<>();

    public static Factory<CardStorage> factory() {
        return new Factory<>(CardStorage::new, CardStorage::load, null);
    }

    public static CardStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        CardStorage s = new CardStorage();
        CompoundTag cardsTag = tag.getCompound("cards");
        for (String key : cardsTag.getAllKeys()) {
            List<UUID> photos = new ArrayList<>();
            for (Tag t : cardsTag.getList(key, Tag.TAG_INT_ARRAY)) {
                photos.add(NbtUtils.loadUUID(t));
            }
            s.cards.put(UUID.fromString(key), photos);
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag cardsTag = new CompoundTag();
        cards.forEach((id, photos) -> {
            ListTag list = new ListTag();
            photos.forEach(p -> list.add(NbtUtils.createUUID(p)));
            cardsTag.put(id.toString(), list);
        });
        tag.put("cards", cardsTag);
        return tag;
    }

    public List<UUID> photos(UUID card) {
        return cards.getOrDefault(card, List.of());
    }

    public void add(UUID card, UUID photo) {
        cards.computeIfAbsent(card, k -> new ArrayList<>()).add(photo);
        setDirty();
    }

    public boolean remove(UUID card, UUID photo) {
        List<UUID> list = cards.get(card);
        boolean removed = list != null && list.remove(photo);
        if (removed) setDirty();
        return removed;
    }

    public List<UUID> clear(UUID card) {
        List<UUID> removed = cards.remove(card);
        setDirty();
        return removed == null ? List.of() : removed;
    }

    /** Remove a photo from every card (admin delete). */
    public void removeEverywhere(UUID photo) {
        for (List<UUID> list : cards.values()) {
            if (list.remove(photo)) setDirty();
        }
    }
}
