package dev.hackdanel.realcamera.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.network.ServerPhotoService;
import dev.hackdanel.realcamera.photo.PhotoIndex;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.photo.PhotoStorage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /photo list [player] [page] | info <id> | delete <id> | purge <player> | stats
 * Permission level 2 (operators). Deleting replaces the picture everywhere with a placeholder.
 */
@EventBusSubscriber(modid = RealCamera.MODID)
public final class PhotoCommand {
    private static final int PAGE = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final SimpleCommandExceptionType NOT_FOUND =
            new SimpleCommandExceptionType(Component.translatable("commands.realcamera.photo.not_found"));

    private PhotoCommand() {}

    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("photo").requires(s -> s.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(c -> list(c, null, 1))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PhotoCommand::suggestAuthors)
                                .executes(c -> list(c, StringArgumentType.getString(c, "player"), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(c -> list(c, StringArgumentType.getString(c, "player"),
                                                IntegerArgumentType.getInteger(c, "page"))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(PhotoCommand::suggestIds)
                                .executes(c -> info(c, StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("id", StringArgumentType.word()).suggests(PhotoCommand::suggestIds)
                                .executes(c -> delete(c, StringArgumentType.getString(c, "id")))))
                .then(Commands.literal("purge")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(PhotoCommand::suggestAuthors)
                                .executes(c -> purge(c, StringArgumentType.getString(c, "player")))))
                .then(Commands.literal("stats").executes(PhotoCommand::stats)));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestAuthors(
            CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(PhotoStorage.index(c.getSource().getServer()).all().stream()
                .map(e -> e.meta().authorName()).distinct(), b);
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestIds(
            CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(PhotoStorage.index(c.getSource().getServer()).all().stream()
                .filter(e -> !e.revoked()).map(e -> e.meta().id().toString().substring(0, 8)), b);
    }

    /** Accepts a full UUID or a unique prefix (the list shows the first 8 characters). */
    private static PhotoIndex.Entry find(MinecraftServer server, String id) throws CommandSyntaxException {
        PhotoIndex index = PhotoStorage.index(server);
        PhotoIndex.Entry match = null;
        for (PhotoIndex.Entry e : index.all()) {
            if (e.meta().id().toString().startsWith(id.toLowerCase(Locale.ROOT))) {
                if (match != null) throw NOT_FOUND.create();
                match = e;
            }
        }
        if (match == null) throw NOT_FOUND.create();
        return match;
    }

    private static int list(CommandContext<CommandSourceStack> c, String player, int page) {
        List<PhotoIndex.Entry> entries = new ArrayList<>();
        for (PhotoIndex.Entry e : PhotoStorage.index(c.getSource().getServer()).all()) {
            if (!e.revoked() && (player == null || e.meta().authorName().equalsIgnoreCase(player))) entries.add(e);
        }
        entries.sort(Comparator.comparingLong((PhotoIndex.Entry e) -> e.meta().createdMillis()).reversed());
        int pages = Math.max(1, (entries.size() + PAGE - 1) / PAGE);
        int p = Math.min(page, pages);
        c.getSource().sendSuccess(() -> Component.translatable("commands.realcamera.photo.list.header",
                entries.size(), p, pages).withStyle(ChatFormatting.GOLD), false);
        for (PhotoIndex.Entry e : entries.subList((p - 1) * PAGE, Math.min(entries.size(), p * PAGE))) {
            String shortId = e.meta().id().toString().substring(0, 8);
            MutableComponent line = Component.literal(shortId).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/photo info " + shortId))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(e.meta().id().toString()))));
            line.append(Component.literal(String.format(Locale.ROOT, "  %s  %s  %d KB  %s",
                    DATE.format(Instant.ofEpochMilli(e.meta().createdMillis())), e.meta().authorName(),
                    e.fileBytes() / 1024, e.meta().exif().camera())).withStyle(ChatFormatting.GRAY));
            c.getSource().sendSuccess(() -> line, false);
        }
        return entries.size();
    }

    private static int info(CommandContext<CommandSourceStack> c, String id) throws CommandSyntaxException {
        PhotoIndex.Entry e = find(c.getSource().getServer(), id);
        PhotoMeta m = e.meta();
        PhotoMeta.Exif x = m.exif();
        c.getSource().sendSuccess(() -> Component.literal(m.id().toString()).withStyle(ChatFormatting.GOLD), false);
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s, %s\n%s + %s\nf/%.1f  %s s  ISO %d  %.0f mm\n%d×%d, %d KB, prints: %d\n%s %s (%d %d %d)",
                m.authorName(), DATE.format(Instant.ofEpochMilli(m.createdMillis())), x.camera(), x.lens(),
                x.fNumber(), shutterLabel(x.shutter()), x.iso(), x.focal(), m.width(), m.height(), e.fileBytes() / 1024,
                e.prints(), m.place().dimension(), m.place().biome(), m.place().x(), m.place().y(), m.place().z())), false);
        return 1;
    }

    private static String shutterLabel(double t) {
        return t >= 0.3 ? String.format(Locale.ROOT, "%.1f", t) : "1/" + Math.round(1 / t);
    }

    private static void revoke(MinecraftServer server, PhotoIndex.Entry e) {
        UUID id = e.meta().id();
        PhotoStorage.index(server).put(e.revoke());
        PhotoStorage.cards(server).removeEverywhere(id);
        PhotoStorage.delete(server, id);
        ServerPhotoService.broadcastGone(server, id);
    }

    private static int delete(CommandContext<CommandSourceStack> c, String id) throws CommandSyntaxException {
        PhotoIndex.Entry e = find(c.getSource().getServer(), id);
        revoke(c.getSource().getServer(), e);
        c.getSource().sendSuccess(() -> Component.translatable("commands.realcamera.photo.deleted",
                e.meta().id().toString()), true);
        return 1;
    }

    private static int purge(CommandContext<CommandSourceStack> c, String player) {
        MinecraftServer server = c.getSource().getServer();
        List<PhotoIndex.Entry> victims = PhotoStorage.index(server).all().stream()
                .filter(e -> !e.revoked() && e.meta().authorName().equalsIgnoreCase(player)).toList();
        victims.forEach(e -> revoke(server, e));
        c.getSource().sendSuccess(() -> Component.translatable("commands.realcamera.photo.purged", victims.size(), player), true);
        return victims.size();
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        PhotoIndex index = PhotoStorage.index(c.getSource().getServer());
        long count = index.all().stream().filter(e -> !e.revoked()).count();
        long bytes = index.all().stream().mapToLong(PhotoIndex.Entry::fileBytes).sum();
        long authors = index.all().stream().filter(e -> !e.revoked()).map(e -> e.meta().authorId()).distinct().count();
        c.getSource().sendSuccess(() -> Component.translatable("commands.realcamera.photo.stats", count,
                String.format(Locale.ROOT, "%.1f", bytes / 1e6), authors), false);
        return (int) count;
    }
}
