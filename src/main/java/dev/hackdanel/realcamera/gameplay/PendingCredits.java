package dev.hackdanel.realcamera.gameplay;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Photos that became visible while their photographer was offline (a roll developed by a friend): the
 * encyclopedia and advancements learn of them at the next login.
 */
public class PendingCredits extends SavedData {
    public static final String NAME = "realcamera_pending_credits";
    private final Map<UUID, List<UUID>> pending = new HashMap<>();

    public static PendingCredits get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(PendingCredits::new, PendingCredits::load, null), NAME);
    }

    public static PendingCredits load(CompoundTag tag, HolderLookup.Provider registries) {
        PendingCredits c = new PendingCredits();
        for (String key : tag.getAllKeys()) {
            List<UUID> ids = new ArrayList<>();
            for (Tag t : tag.getList(key, Tag.TAG_INT_ARRAY)) ids.add(NbtUtils.loadUUID(t));
            c.pending.put(UUID.fromString(key), ids);
        }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        pending.forEach((player, ids) -> {
            ListTag list = new ListTag();
            for (UUID id : ids) list.add(NbtUtils.createUUID(id));
            tag.put(player.toString(), list);
        });
        return tag;
    }

    public void add(UUID player, UUID photo) {
        pending.computeIfAbsent(player, k -> new ArrayList<>()).add(photo);
        setDirty();
    }

    public List<UUID> take(UUID player) {
        List<UUID> ids = pending.remove(player);
        if (ids != null) setDirty();
        return ids == null ? List.of() : ids;
    }
}
