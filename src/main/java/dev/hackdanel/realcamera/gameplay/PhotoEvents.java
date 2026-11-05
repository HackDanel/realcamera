package dev.hackdanel.realcamera.gameplay;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Where finished photos and other photographic deeds meet the encyclopedia and the advancements. */
@EventBusSubscriber(modid = RealCamera.MODID)
public final class PhotoEvents {
    private PhotoEvents() {}

    /** A photo became visible to its author (digital: when stored; film: when developed). */
    public static void photoStored(ServerPlayer player, PhotoMeta meta) {
        Journal before = player.getData(Journal.TYPE);
        List<String> fresh = new ArrayList<>();
        Journal after = before.record(meta, fresh);
        player.setData(Journal.TYPE, after);
        sync(player);
        for (String key : fresh) {
            player.displayClientMessage(Component.translatable("message.realcamera.journal.new_page", pageName(key))
                    .withStyle(ChatFormatting.GOLD), false);
        }
        RCTriggers.PHOTO.get().trigger(player, meta);
        event(player, "journal_creatures", after.count(Journal.CREATURE));
        event(player, "journal_biomes", after.count(Journal.BIOME));
        event(player, "journal_phenomena", after.count(Journal.PHENOMENON));
    }

    /** A counted deed ("frames_hung", "prints" …): increments the counter and reports the new total. */
    public static void count(ServerPlayer player, String counter) {
        Journal j = player.getData(Journal.TYPE);
        int value = j.counter(counter) + 1;
        player.setData(Journal.TYPE, j.withCounter(counter, value));
        event(player, counter, value);
    }

    public static void event(ServerPlayer player, String name, int value) {
        RCTriggers.EVENT.get().trigger(player, name, value);
    }

    public static Component pageName(String key) {
        int slash = key.indexOf('/');
        String kind = key.substring(0, slash), id = key.substring(slash + 1);
        return switch (kind) {
            case "creature" -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .get(net.minecraft.resources.ResourceLocation.parse(id)).getDescription();
            case "biome" -> Component.translatable("biome." + id.replace(':', '.'));
            case "dimension" -> Component.translatable("realcamera.dimension." + id.replace(':', '.'));
            default -> Component.translatable("realcamera.phenomenon." + id);
        };
    }

    public static void sync(ServerPlayer player) {
        List<String> dims = player.server.levelKeys().stream().map(k -> k.location().toString()).toList();
        PacketDistributor.sendToPlayer(player, new Payloads.JournalSync(player.getData(Journal.TYPE), dims));
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sync(p);
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) sync(p);
    }
}
