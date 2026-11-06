package dev.hackdanel.realcamera.network;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.config.ServerConfig;
import dev.hackdanel.realcamera.photo.CardStorage;
import dev.hackdanel.realcamera.photo.PhotoIndex;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.photo.PhotoStorage;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCSounds;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import dev.hackdanel.realcamera.spec.MemoryCardTypeSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server half of the photo pipeline: validates and stores uploads, streams photos to clients
 * with a per-player bandwidth budget, answers card listings and deletions.
 */
@EventBusSubscriber(modid = RealCamera.MODID)
public final class ServerPhotoService {
    private static final long SESSION_TIMEOUT_MS = 60_000;
    /** Bytes per player per tick sent to clients (about 2.5 MB/s). */
    private static final int DOWNLOAD_BUDGET_PER_TICK = 128 * 1024;

    /** Updates the usage counters mirrored on the card item once a photo is stored or removed. */
    @FunctionalInterface
    public interface CardMirror {
        void adjust(long bytes, int count);
    }

    private record Session(UUID photoId, int requestId, UUID cardId, PhotoMeta meta, int totalBytes, long cardBytes,
                           ByteArrayOutputStream data, long started, CardMirror mirror, Optional<UUID> uploader,
                           boolean latent) {}

    /** A camera the player may operate: in a hand, or mounted on a tripod within reach. */
    public record Handle(ItemStack stack, Runnable commit) {}

    public static Optional<Handle> resolve(ServerPlayer player, dev.hackdanel.realcamera.camera.CameraRef ref) {
        if (!ref.isTripod()) {
            ItemStack stack = player.getItemInHand(ref.hand());
            return CameraHelper.isCamera(stack) ? Optional.of(new Handle(stack, () -> {})) : Optional.empty();
        }
        if (player.level().getEntity(ref.tripodId()) instanceof dev.hackdanel.realcamera.entity.TripodEntity tripod
                && tripod.distanceToSqr(player) < 32 * 32 && CameraHelper.isCamera(tripod.camera())) {
            ItemStack stack = tripod.camera().copy();
            return Optional.of(new Handle(stack, () -> tripod.setCamera(stack)));
        }
        return Optional.empty();
    }

    public static void aimTripod(ServerPlayer player, Payloads.TripodAim msg) {
        if (player.level().getEntity(msg.entityId()) instanceof dev.hackdanel.realcamera.entity.TripodEntity tripod
                && tripod.distanceToSqr(player) < 16 * 16) {
            tripod.aim(msg.yaw(), msg.pitch());
        }
    }

    private static final Map<UUID, Session> uploads = new HashMap<>();

    /** Frame analyses made at the release, waiting for their picture (keyed by player, then shot nonce). */
    private record PendingShot(PhotoMeta.Analysis analysis, long time, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
                               java.util.function.Predicate<Vec3> boltInFrame, float shutterS) {}

    private static final Map<UUID, java.util.LinkedHashMap<Integer, PendingShot>> pendingShots = new HashMap<>();

    /**
     * A lightning bolt struck: shots whose shutter is still open (long and bulb exposures, the way lightning is really
     * photographed) and that frame it record it.
     */
    public static void lightningStruck(net.minecraft.server.level.ServerLevel level, Vec3 pos) {
        long now = Util.getMillis();
        for (var map : pendingShots.values()) {
            for (var e : map.entrySet()) {
                PendingShot p = e.getValue();
                double openMs = p.shutterS() < 0 ? 300_000 : p.shutterS() * 1000 + 250;
                if (!p.dim().equals(level.dimension()) || now - p.time() > openMs || !p.boltInFrame().test(pos)
                        || p.analysis().phenomena().contains("lightning")) continue;
                List<String> ph = new java.util.ArrayList<>(p.analysis().phenomena());
                ph.add("lightning");
                e.setValue(new PendingShot(p.analysis().withPhenomena(ph), p.time(), p.dim(), p.boltInFrame(), p.shutterS()));
            }
        }
    }

    private static Optional<PhotoMeta.Analysis> takeAnalysis(ServerPlayer player, int nonce, PhotoMeta.Exif exif) {
        var map = pendingShots.get(player.getUUID());
        PendingShot p = map == null ? null : map.remove(nonce);
        return p == null ? Optional.empty()
                : Optional.of(dev.hackdanel.realcamera.analysis.FrameAnalyzer.complete(p.analysis(), exif));
    }

    private static void analyse(ServerPlayer player, Payloads.Shutter msg, ItemStack camera) {
        try {
            var chain = dev.hackdanel.realcamera.camera.OpticalChain.resolve(player.registryAccess(), camera);
            if (chain.isEmpty()) return;
            net.minecraft.world.entity.Entity viewer = msg.ref().isTripod()
                    ? player.serverLevel().getEntity(msg.ref().tripodId()) : player;
            if (viewer == null) return;
            PhotoMeta.Analysis a = dev.hackdanel.realcamera.analysis.FrameAnalyzer.analyze(player.serverLevel(), viewer,
                    player, chain.get(), CameraHelper.settings(camera), msg.info(),
                    shutterType(player, camera, msg) == dev.hackdanel.realcamera.component.CameraSettings.SHUTTER_ELECTRONIC,
                    msg.flash() && (chain.get().body().flash().builtInGn() > 0 || !CameraHelper.parts(camera).flash().isEmpty()),
                    msg.ref().isTripod());
            var map = pendingShots.computeIfAbsent(player.getUUID(), k -> new java.util.LinkedHashMap<>());
            long now = Util.getMillis();
            map.values().removeIf(p -> now - p.time() > 300_000);
            while (map.size() > 64) map.remove(map.keySet().iterator().next());
            map.put(msg.info().nonce(), new PendingShot(a, now, player.level().dimension(),
                    dev.hackdanel.realcamera.analysis.FrameAnalyzer.lightningTest(viewer, chain.get(), CameraHelper.settings(camera),
                            msg.info()), msg.info().shutter()));
        } catch (RuntimeException e) {
            RealCamera.LOGGER.warn("Frame analysis failed", e);
        }
    }
    private static final Map<UUID, Deque<Long>> uploadTimes = new HashMap<>();
    private static final Map<UUID, Deque<PhotoData>> downloadQueues = new HashMap<>();

    private record PhotoData(UUID id, byte[] data, int[] nextIndex) {}

    private ServerPhotoService() {}

    // ------------------------------------------------------------------ settings & shutter

    public static void updateSettings(ServerPlayer player, Payloads.UpdateSettings msg) {
        resolve(player, msg.ref()).ifPresent(h -> {
            h.stack().set(RCComponents.CAMERA_SETTINGS.get(), msg.settings());
            h.commit().run();
        });
    }

    public static void shutter(ServerPlayer player, Payloads.Shutter msg) {
        Optional<Handle> handle = resolve(player, msg.ref());
        if (handle.isEmpty() || !CameraHelper.canFire(player.registryAccess(), handle.get().stack())) {
            return;
        }
        shutter(player, msg, handle.get().stack());
        handle.get().commit().run();
    }

    /** The shutter type the body really has (a client cannot claim a silent shutter on a DSLR). */
    private static int shutterType(ServerPlayer player, ItemStack camera, Payloads.Shutter msg) {
        return Specs.body(player.registryAccess(), camera).map(b -> b.coerceShutterType(msg.shutterType()))
                .orElse(msg.shutterType());
    }

    private static void shutter(ServerPlayer player, Payloads.Shutter msg, ItemStack camera) {
        analyse(player, msg, camera);
        Optional<CameraBodySpec> body = Specs.body(player.registryAccess(), camera);
        // battery_drain = battery capacity / the body's CIPA shots: fractional, so shot counts differ per body
        int drain = dev.hackdanel.realcamera.gameplay.WearEvents.units(player, body.map(CameraBodySpec::batteryDrain).orElse(1f));
        if (drain > 0) CameraHelper.drain(camera, dev.hackdanel.realcamera.gameplay.WearEvents.coldDrain(player, drain));
        if (msg.flash()) {
            boolean hotShoe = !CameraHelper.parts(camera).flash().isEmpty();
            if (hotShoe) CameraHelper.drainFlash(camera); else CameraHelper.drain(camera, 1);
            // everyone around sees the pop
            Vec3 eye = player.getEyePosition().add(player.getLookAngle().scale(0.6));
            player.serverLevel().sendParticles(net.minecraft.core.particles.ParticleTypes.FLASH, eye.x, eye.y, eye.z, 1, 0, 0, 0, 0);
        }
        if (shutterType(player, camera, msg) == dev.hackdanel.realcamera.component.CameraSettings.SHUTTER_ELECTRONIC) {
            return; // silent and wear-free
        }
        camera.set(RCComponents.SHUTTER_COUNT.get(), CameraHelper.shutterCount(camera) + 1);
        dev.hackdanel.realcamera.gameplay.PhotoEvents.event(player, "shutter_count", CameraHelper.shutterCount(camera));
        body.ifPresent(b -> dev.hackdanel.realcamera.gameplay.WearEvents.shutterWear(player, camera, b.shutter().ratedLife()));
        boolean efcs = shutterType(player, camera, msg) == dev.hackdanel.realcamera.component.CameraSettings.SHUTTER_EFCS;
        // Heard by everyone around except the photographer, who already played it locally.
        player.level().playSound(player, player.getX(), player.getEyeY(), player.getZ(),
                RCSounds.shutter(body.map(b -> b.shutter().sound()).orElse("mirrorless")), SoundSource.PLAYERS,
                efcs ? 0.5f : 0.8f, efcs ? 1.15f : 1f);
    }

    // ------------------------------------------------------------------ upload

    public static void uploadBegin(ServerPlayer player, Payloads.UploadBegin msg) {
        Consumer<String> reject = key -> PacketDistributor.sendToPlayer(player,
                new Payloads.UploadStatus(msg.requestId(), Payloads.UPLOAD_REJECTED, Optional.empty(), key));
        MinecraftServer server = player.server;
        Optional<Handle> handle = resolve(player, msg.ref());
        if (handle.isEmpty()) {
            reject.accept("message.realcamera.upload.no_camera");
            return;
        }
        ItemStack camera = handle.get().stack();
        CameraParts parts = CameraHelper.parts(camera);
        if (CameraHelper.isFilm(player.registryAccess(), camera)) {
            filmUploadBegin(player, msg, handle.get(), reject);
            return;
        }
        ItemStack card = parts.card();
        Optional<MemoryCardTypeSpec> cardType = Specs.card(player.registryAccess(), card);
        Optional<CameraBodySpec> body = Specs.body(player.registryAccess(), camera);
        if (card.isEmpty() || cardType.isEmpty() || body.isEmpty()) {
            reject.accept("message.realcamera.no_card");
            return;
        }
        if (msg.totalBytes() <= 0 || msg.totalBytes() > ServerConfig.MAX_FILE_KB.get() * 1024) {
            reject.accept("message.realcamera.upload.too_big");
            return;
        }
        CardData data = card.getOrDefault(RCComponents.CARD.get(), CardData.EMPTY);
        long cardBytes = CameraHelper.bytesPerShot(body.get(), msg.quality(), msg.format());
        if (data.usedBytes() + cardBytes > cardType.get().capacityBytes()) {
            reject.accept("message.realcamera.card_full");
            return;
        }
        PhotoIndex index = PhotoStorage.index(server);
        UUID author = player.getUUID();
        int maxPhotos = index.quota(author).orElse(ServerConfig.MAX_PHOTOS_PER_PLAYER.get());
        if (index.countFor(author) >= maxPhotos
                || index.bytesFor(author) + msg.totalBytes() > ServerConfig.MAX_STORAGE_MB_PER_PLAYER.get() * 1_000_000L) {
            reject.accept("message.realcamera.upload.quota");
            return;
        }
        long now = Util.getMillis();
        Deque<Long> times = uploadTimes.computeIfAbsent(author, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
            times.pollFirst();
        }
        if (times.size() >= ServerConfig.UPLOADS_PER_MINUTE.get()) {
            reject.accept("message.realcamera.upload.rate");
            return;
        }
        if (uploads.containsKey(author)) {
            reject.accept("message.realcamera.upload.busy");
            return;
        }
        times.addLast(now);

        UUID cardId = data.id().orElseGet(UUID::randomUUID);
        if (data.id().isEmpty()) {
            ItemStack newCard = card.copy();
            newCard.set(RCComponents.CARD.get(), new CardData(Optional.of(cardId), data.usedBytes(), data.photoCount()));
            CameraHelper.setParts(camera, parts.with(CameraParts.SLOT_CARD, newCard));
            handle.get().commit().run();
        }
        UUID photoId = UUID.randomUUID();
        // Trust nothing identity-related from the client: author, time and place come from the server.
        PhotoMeta.Place place = new PhotoMeta.Place(player.level().dimension().location().toString(),
                player.level().getBiome(player.blockPosition()).unwrapKey().map(k -> k.location().toString()).orElse("?"),
                player.getBlockX(), player.getBlockY(), player.getBlockZ(), player.level().getDayTime() / 24000L);
        PhotoMeta meta = new PhotoMeta(photoId, author, player.getGameProfile().getName(), System.currentTimeMillis(),
                msg.meta().width(), msg.meta().height(), msg.meta().exif(), place)
                .withAnalysis(takeAnalysis(player, msg.shotNonce(), msg.meta().exif()));
        uploads.put(author, new Session(photoId, msg.requestId(), cardId, meta, msg.totalBytes(), cardBytes,
                new ByteArrayOutputStream(msg.totalBytes()), now,
                (bytes, count) -> adjustCardMirror(player, cardId, bytes, count, msg.ref()), Optional.empty(), false));
        PacketDistributor.sendToPlayer(player, new Payloads.UploadStatus(msg.requestId(), Payloads.UPLOAD_ACCEPTED,
                Optional.of(photoId), ""));
    }

    /** Checks shared by camera and editor uploads; returns the rejection key or null. */
    private static String quotaProblem(ServerPlayer player, int totalBytes) {
        if (totalBytes <= 0 || totalBytes > ServerConfig.MAX_FILE_KB.get() * 1024) {
            return "message.realcamera.upload.too_big";
        }
        PhotoIndex index = PhotoStorage.index(player.server);
        UUID who = player.getUUID();
        int maxPhotos = index.quota(who).orElse(ServerConfig.MAX_PHOTOS_PER_PLAYER.get());
        if (index.countFor(who) >= maxPhotos
                || index.bytesFor(who) + totalBytes > ServerConfig.MAX_STORAGE_MB_PER_PLAYER.get() * 1_000_000L) {
            return "message.realcamera.upload.quota";
        }
        long now = Util.getMillis();
        Deque<Long> times = uploadTimes.computeIfAbsent(who, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
            times.pollFirst();
        }
        if (times.size() >= ServerConfig.UPLOADS_PER_MINUTE.get()) {
            return "message.realcamera.upload.rate";
        }
        if (uploads.containsKey(who)) {
            return "message.realcamera.upload.busy";
        }
        times.addLast(now);
        return null;
    }

    /** An edited picture from the computer's editor, stored as a new photo next to its source. */
    public static void editUploadBegin(ServerPlayer player, Payloads.EditUploadBegin msg) {
        Consumer<String> reject = key -> PacketDistributor.sendToPlayer(player,
                new Payloads.UploadStatus(msg.requestId(), Payloads.UPLOAD_REJECTED, Optional.empty(), key));
        if (!(player.containerMenu instanceof dev.hackdanel.realcamera.menu.ComputerMenu menu)
                || !menu.pos().equals(msg.pos()) || menu.blockEntity() == null || msg.slot() < 0 || msg.slot() > 1) {
            reject.accept("message.realcamera.computer.closed");
            return;
        }
        var computer = menu.blockEntity();
        UUID cardId = computer.ensureCardId(msg.slot());
        if (computer.isFilm(msg.slot())) {
            reject.accept("message.realcamera.computer.film_readonly");
            return;
        }
        if (cardId == null || !computer.readable(msg.slot())) {
            reject.accept("message.realcamera.no_card");
            return;
        }
        MinecraftServer server = player.server;
        PhotoIndex.Entry source = PhotoStorage.index(server).get(msg.source());
        boolean onCards = PhotoStorage.cards(server).contains(cardId, msg.source())
                || (computer.cardIdOrNull(1 - msg.slot()) != null
                && PhotoStorage.cards(server).contains(computer.cardIdOrNull(1 - msg.slot()), msg.source()));
        if (source == null || source.revoked() || !onCards) {
            reject.accept("message.realcamera.upload.invalid");
            return;
        }
        long cardBytes = source.cardBytes();
        if (computer.freeBytes(msg.slot()) < cardBytes) {
            reject.accept("message.realcamera.card_full");
            return;
        }
        String problem = quotaProblem(player, msg.totalBytes());
        if (problem != null) {
            reject.accept(problem);
            return;
        }
        UUID photoId = UUID.randomUUID();
        PhotoMeta meta = source.meta().editedCopy(photoId, source.meta().width(), source.meta().height());
        int slot = msg.slot();
        uploads.put(player.getUUID(), new Session(photoId, msg.requestId(), cardId, meta, msg.totalBytes(), cardBytes,
                new ByteArrayOutputStream(msg.totalBytes()), Util.getMillis(),
                (bytes, count) -> computer.adjustCard(slot, bytes, count),
                player.getUUID().equals(meta.authorId()) ? Optional.empty() : Optional.of(player.getUUID()), false));
        dev.hackdanel.realcamera.gameplay.PhotoEvents.count(player, "edits");
        PacketDistributor.sendToPlayer(player, new Payloads.UploadStatus(msg.requestId(), Payloads.UPLOAD_ACCEPTED,
                Optional.of(photoId), ""));
    }

    /**
     * A film frame: no card space, but a frame of the roll; stored hidden ("latent") until the roll is
     * developed in the tank.
     */
    private static void filmUploadBegin(ServerPlayer player, Payloads.UploadBegin msg, Handle handle, Consumer<String> reject) {
        ItemStack camera = handle.stack();
        CameraParts parts = CameraHelper.parts(camera);
        ItemStack rollStack = parts.card();
        if (!(rollStack.getItem() instanceof dev.hackdanel.realcamera.item.FilmRollItem)) {
            reject.accept("message.realcamera.no_film");
            return;
        }
        var roll = dev.hackdanel.realcamera.item.FilmRollItem.roll(rollStack);
        int frames = Specs.film(player.registryAccess(), rollStack).map(f -> f.frames(roll.format())).orElse(0);
        if (roll.state() > dev.hackdanel.realcamera.component.FilmRoll.LOADED || roll.shot() >= frames) {
            reject.accept("message.realcamera.film_end");
            return;
        }
        String problem = quotaProblem(player, msg.totalBytes());
        if (problem != null) {
            reject.accept(problem);
            return;
        }
        UUID rollId = roll.id().orElseGet(UUID::randomUUID);
        ItemStack loaded = rollStack.copy();
        loaded.set(RCComponents.FILM.get(), roll.withId(rollId).withState(dev.hackdanel.realcamera.component.FilmRoll.LOADED));
        CameraHelper.setParts(camera, parts.with(CameraParts.SLOT_CARD, loaded));
        handle.commit().run();
        UUID photoId = UUID.randomUUID();
        PhotoMeta.Place place = new PhotoMeta.Place(player.level().dimension().location().toString(),
                player.level().getBiome(player.blockPosition()).unwrapKey().map(k -> k.location().toString()).orElse("?"),
                player.getBlockX(), player.getBlockY(), player.getBlockZ(), player.level().getDayTime() / 24000L);
        PhotoMeta meta = new PhotoMeta(photoId, player.getUUID(), player.getGameProfile().getName(), System.currentTimeMillis(),
                msg.meta().width(), msg.meta().height(), msg.meta().exif(), place)
                .withAnalysis(takeAnalysis(player, msg.shotNonce(), msg.meta().exif()));
        dev.hackdanel.realcamera.camera.CameraRef ref = msg.ref();
        uploads.put(player.getUUID(), new Session(photoId, msg.requestId(), rollId, meta, msg.totalBytes(), 0,
                new ByteArrayOutputStream(msg.totalBytes()), Util.getMillis(),
                (bytes, count) -> advanceFilm(player, ref, rollId, count), Optional.empty(), true));
        PacketDistributor.sendToPlayer(player, new Payloads.UploadStatus(msg.requestId(), Payloads.UPLOAD_ACCEPTED,
                Optional.of(photoId), ""));
    }

    public static void rewind(ServerPlayer player, dev.hackdanel.realcamera.camera.CameraRef ref) {
        resolve(player, ref).ifPresent(h -> {
            CameraParts parts = CameraHelper.parts(h.stack());
            ItemStack rollStack = parts.card();
            var roll = rollStack.get(RCComponents.FILM.get());
            if (roll == null || roll.state() != dev.hackdanel.realcamera.component.FilmRoll.LOADED || roll.shot() == 0) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.realcamera.nothing_to_rewind"), true);
                return;
            }
            ItemStack next = rollStack.copy();
            next.set(RCComponents.FILM.get(), roll.withState(dev.hackdanel.realcamera.component.FilmRoll.EXPOSED));
            CameraHelper.setParts(h.stack(), parts.with(CameraParts.SLOT_CARD, next));
            h.commit().run();
            player.level().playSound(null, player, net.minecraft.sounds.SoundEvents.CROSSBOW_LOADING_MIDDLE.value(),
                    SoundSource.PLAYERS, 0.6f, 1.6f);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.realcamera.rewound"), true);
        });
    }

    /** Counts a stored frame on the roll inside the camera it was shot with. */
    private static void advanceFilm(ServerPlayer player, dev.hackdanel.realcamera.camera.CameraRef ref, UUID rollId, int count) {
        resolve(player, ref).ifPresent(h -> {
            CameraParts parts = CameraHelper.parts(h.stack());
            ItemStack rollStack = parts.card();
            var roll = rollStack.get(RCComponents.FILM.get());
            if (roll == null || roll.id().isEmpty() || !roll.id().get().equals(rollId)) return;
            ItemStack next = rollStack.copy();
            next.set(RCComponents.FILM.get(), roll.withShot(roll.shot() + count));
            CameraHelper.setParts(h.stack(), parts.with(CameraParts.SLOT_CARD, next));
            h.commit().run();
        });
    }

    public static void uploadChunk(ServerPlayer player, Payloads.UploadChunk msg) {
        Session s = uploads.get(player.getUUID());
        if (s == null || !s.photoId().equals(msg.photoId())) {
            return;
        }
        if (s.data().size() + msg.data().length > s.totalBytes()) {
            uploads.remove(player.getUUID());
            PacketDistributor.sendToPlayer(player, new Payloads.UploadStatus(s.requestId(), Payloads.UPLOAD_REJECTED,
                    Optional.empty(), "message.realcamera.upload.too_big"));
            return;
        }
        s.data().writeBytes(msg.data());
        if (s.data().size() == s.totalBytes()) {
            uploads.remove(player.getUUID());
            finishUpload(player, s);
        }
    }

    private static void finishUpload(ServerPlayer player, Session s) {
        byte[] bytes = s.data().toByteArray();
        int[] size = PhotoStorage.jpegSize(bytes);
        int maxRes = ServerConfig.MAX_UPLOAD_RESOLUTION.get();
        if (size == null || size[0] > maxRes + 2 || size[1] > maxRes + 2 || size[0] < 16 || size[1] < 16) {
            PacketDistributor.sendToPlayer(player, new Payloads.UploadStatus(s.requestId(), Payloads.UPLOAD_REJECTED,
                    Optional.empty(), "message.realcamera.upload.invalid"));
            return;
        }
        MinecraftServer server = player.server;
        PhotoMeta meta = s.meta().withSize(size[0], size[1]);
        Util.ioPool().execute(() -> {
            try {
                PhotoStorage.write(server, s.photoId(), bytes);
            } catch (IOException e) {
                RealCamera.LOGGER.error("Failed to store photo {}", s.photoId(), e);
                server.execute(() -> PacketDistributor.sendToPlayer(player, new Payloads.UploadStatus(s.requestId(),
                        Payloads.UPLOAD_REJECTED, Optional.empty(), "message.realcamera.upload.io")));
                return;
            }
            server.execute(() -> {
                PhotoStorage.index(server).put(new PhotoIndex.Entry(meta, bytes.length, s.cardBytes(), 0, false, 0,
                        s.uploader(), s.latent()));
                PhotoStorage.cards(server).add(s.cardId(), s.photoId());
                s.mirror().adjust(s.cardBytes(), 1);
                if (!s.latent() && player.getUUID().equals(meta.authorId()) && meta.editedFrom().isEmpty()) {
                    dev.hackdanel.realcamera.gameplay.PhotoEvents.photoStored(player, meta);
                }
                PacketDistributor.sendToPlayer(player, new Payloads.UploadStatus(s.requestId(), Payloads.UPLOAD_STORED,
                        Optional.of(s.photoId()), ""));
                RealCamera.LOGGER.debug("Stored photo {} ({} bytes) for {}", s.photoId(), bytes.length,
                        player.getGameProfile().getName());
            });
        });
    }

    /** Same, but also looking in the camera the shot came from (it may sit on a tripod). */
    private static void adjustCardMirror(ServerPlayer player, UUID cardId, long bytes, int count,
                                         dev.hackdanel.realcamera.camera.CameraRef ref) {
        if (ref != null && ref.isTripod()) {
            Optional<Handle> h = resolve(player, ref);
            if (h.isPresent() && adjustInCamera(h.get().stack(), cardId, bytes, count)) {
                h.get().commit().run();
                return;
            }
        }
        adjustCardMirror(player, cardId, bytes, count);
    }

    /** Update the usage counters mirrored on the card item, wherever the player keeps it. */
    private static void adjustCardMirror(Player player, UUID cardId, long bytes, int count) {
        for (ItemStack stack : player.getInventory().items) {
            if (adjustInCamera(stack, cardId, bytes, count) || adjustCard(stack, cardId, bytes, count)) return;
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (adjustInCamera(stack, cardId, bytes, count) || adjustCard(stack, cardId, bytes, count)) return;
        }
    }

    private static boolean adjustInCamera(ItemStack camera, UUID cardId, long bytes, int count) {
        if (!CameraHelper.isCamera(camera)) return false;
        CameraParts parts = CameraHelper.parts(camera);
        ItemStack card = parts.card().copy();
        if (!adjustCard(card, cardId, bytes, count)) return false;
        CameraHelper.setParts(camera, parts.with(CameraParts.SLOT_CARD, card));
        return true;
    }

    public static boolean adjustCard(ItemStack card, UUID cardId, long bytes, int count) {
        CardData d = card.get(RCComponents.CARD.get());
        if (d == null || d.id().isEmpty() || !d.id().get().equals(cardId)) return false;
        card.set(RCComponents.CARD.get(), new CardData(d.id(), Math.max(0, d.usedBytes() + bytes),
                Math.max(0, d.photoCount() + count)));
        return true;
    }

    // ------------------------------------------------------------------ cards

    /** A player may list a card that is in their inventory (loose or in a camera) or in a printer they use. */
    private static boolean canAccessCard(ServerPlayer player, UUID cardId) {
        for (ItemStack stack : player.getInventory().items) {
            if (holdsCard(stack, cardId)) return true;
        }
        for (ItemStack stack : player.getInventory().offhand) {
            if (holdsCard(stack, cardId)) return true;
        }
        if (player.containerMenu instanceof dev.hackdanel.realcamera.menu.PrinterMenu printer
                && cardId.equals(printer.cardId())) {
            return true;
        }
        if (player.containerMenu instanceof dev.hackdanel.realcamera.menu.EnlargerMenu enlarger
                && cardId.equals(enlarger.rollId())) {
            return true;
        }
        if (player.containerMenu instanceof dev.hackdanel.realcamera.menu.ComputerMenu computer
                && (cardId.equals(computer.cardId(0)) || cardId.equals(computer.cardId(1)))) {
            return true;
        }
        for (var tripod : player.level().getEntitiesOfClass(dev.hackdanel.realcamera.entity.TripodEntity.class,
                player.getBoundingBox().inflate(16))) {
            if (holdsCard(tripod.camera(), cardId)) return true;
        }
        return false;
    }

    private static boolean holdsCard(ItemStack stack, UUID cardId) {
        ItemStack card = CameraHelper.isCamera(stack) ? CameraHelper.parts(stack).card() : stack;
        CardData d = card.get(RCComponents.CARD.get());
        if (d != null && d.id().map(cardId::equals).orElse(false)) return true;
        var roll = card.get(RCComponents.FILM.get());
        return roll != null && roll.id().map(cardId::equals).orElse(false);
    }

    public static void requestCard(ServerPlayer player, Payloads.RequestCard msg) {
        if (!canAccessCard(player, msg.cardId())) {
            return;
        }
        PacketDistributor.sendToPlayer(player, cardContents(player, msg.cardId()));
    }

    public static Payloads.CardContents cardContents(ServerPlayer player, UUID cardId) {
        MinecraftServer server = player.server;
        PhotoIndex index = PhotoStorage.index(server);
        List<PhotoMeta> list = new ArrayList<>();
        List<Integer> ratings = new ArrayList<>();
        for (UUID id : PhotoStorage.cards(server).photos(cardId)) {
            PhotoIndex.Entry e = index.get(id);
            if (e != null && !e.revoked() && !e.latent()) {
                list.add(visibleMeta(player, e.meta()));
                ratings.add(e.rating());
            }
        }
        return new Payloads.CardContents(cardId, list, ratings);
    }

    /**
     * Removes a photo from a card. The file itself goes only when nothing uses it any more (no other
     * card, no prints). Returns the card space it occupied, or -1 if it was not on the card.
     */
    public static long removeFromCard(MinecraftServer server, UUID cardId, UUID photoId) {
        CardStorage cards = PhotoStorage.cards(server);
        if (!cards.remove(cardId, photoId)) {
            return -1;
        }
        PhotoIndex index = PhotoStorage.index(server);
        PhotoIndex.Entry e = index.get(photoId);
        long cardBytes = e == null ? 0 : e.cardBytes();
        if (e != null && e.prints() == 0 && !cards.isReferenced(photoId)) {
            index.remove(photoId);
            Util.ioPool().execute(() -> PhotoStorage.delete(server, photoId));
        }
        return cardBytes;
    }

    /** Coordinates are private unless the server allows sharing them. */
    public static PhotoMeta visibleMeta(Player viewer, PhotoMeta meta) {
        if (ServerConfig.SHOW_COORDINATES_TO_OTHERS.get() || meta.authorId().equals(viewer.getUUID())) {
            return meta;
        }
        return meta.withPlace(meta.place().hideCoordinates());
    }

    public static void deletePhoto(ServerPlayer player, Payloads.DeletePhoto msg) {
        Optional<Handle> handle = resolve(player, msg.ref());
        if (handle.isEmpty()) {
            return;
        }
        ItemStack camera = handle.get().stack();
        CardData d = CameraHelper.parts(camera).card().get(RCComponents.CARD.get());
        if (d == null || d.id().isEmpty()) {
            return;
        }
        long cardBytes = removeFromCard(player.server, d.id().get(), msg.photoId());
        if (cardBytes < 0) {
            return;
        }
        adjustCardMirror(player, d.id().get(), -cardBytes, -1, msg.ref());
        PacketDistributor.sendToPlayer(player, cardContents(player, d.id().get()));
    }

    // ------------------------------------------------------------------ download

    public static void requestPhoto(ServerPlayer player, Payloads.RequestPhoto msg) {
        MinecraftServer server = player.server;
        PhotoIndex.Entry e = PhotoStorage.index(server).get(msg.id());
        if (e != null && e.latent()) {
            return; // undeveloped film: nobody may see it yet
        }
        if (e == null || e.revoked()) {
            PacketDistributor.sendToPlayer(player, new Payloads.PhotoGone(msg.id()));
            return;
        }
        Deque<PhotoData> queue = downloadQueues.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        if (queue.stream().anyMatch(d -> d.id().equals(msg.id())) || queue.size() > 256) {
            return;
        }
        UUID id = msg.id();
        UUID playerId = player.getUUID();
        Util.ioPool().execute(() -> {
            byte[] bytes;
            try {
                bytes = PhotoStorage.read(server, id);
            } catch (IOException ex) {
                bytes = null;
            }
            byte[] result = bytes;
            server.execute(() -> {
                ServerPlayer p = server.getPlayerList().getPlayer(playerId);
                if (p == null) return;
                if (result == null) {
                    PacketDistributor.sendToPlayer(p, new Payloads.PhotoGone(id));
                } else {
                    downloadQueues.computeIfAbsent(playerId, k -> new ArrayDeque<>())
                            .addLast(new PhotoData(id, result, new int[]{0}));
                }
            });
        });
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        downloadQueues.entrySet().removeIf(entry -> {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) return true;
            int budget = DOWNLOAD_BUDGET_PER_TICK;
            Deque<PhotoData> queue = entry.getValue();
            while (budget > 0 && !queue.isEmpty()) {
                PhotoData d = queue.peekFirst();
                int total = (d.data().length + Payloads.DOWNLOAD_CHUNK - 1) / Payloads.DOWNLOAD_CHUNK;
                int i = d.nextIndex()[0];
                int from = i * Payloads.DOWNLOAD_CHUNK;
                int to = Math.min(d.data().length, from + Payloads.DOWNLOAD_CHUNK);
                byte[] chunk = java.util.Arrays.copyOfRange(d.data(), from, to);
                PacketDistributor.sendToPlayer(player, new Payloads.PhotoData(d.id(), i, total, chunk));
                budget -= chunk.length;
                if (++d.nextIndex()[0] >= total) {
                    queue.pollFirst();
                }
            }
            return queue.isEmpty();
        });
        long now = Util.getMillis();
        uploads.values().removeIf(s -> now - s.started() > SESSION_TIMEOUT_MS);
    }

    /** Tell every client to drop a photo (admin delete). */
    public static void broadcastGone(MinecraftServer server, UUID id) {
        PacketDistributor.sendToAllPlayers(new Payloads.PhotoGone(id));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        uploads.remove(event.getEntity().getUUID());
        downloadQueues.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        uploads.clear();
        uploadTimes.clear();
        downloadQueues.clear();
    }

}
