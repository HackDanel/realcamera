package dev.hackdanel.realcamera.client.photo;

import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.neoforged.neoforge.network.PacketDistributor;

/** Last known contents of memory cards, as reported by the server. */
public final class CardListings {
    private static final Map<UUID, List<PhotoMeta>> listings = new HashMap<>();
    private static final Map<UUID, Long> requested = new HashMap<>();

    private CardListings() {}

    /** Returns the cached list (possibly null) and asks the server for a fresh one at most every 2 s. */
    public static List<PhotoMeta> get(UUID card) {
        long now = System.currentTimeMillis();
        Long last = requested.get(card);
        if (last == null || now - last > 2000) {
            requested.put(card, now);
            PacketDistributor.sendToServer(new Payloads.RequestCard(card));
        }
        return listings.get(card);
    }

    public static void refresh(UUID card) {
        requested.remove(card);
    }

    public static void onContents(Payloads.CardContents msg) {
        listings.put(msg.cardId(), List.copyOf(msg.photos()));
    }

    public static void clear() {
        listings.clear();
        requested.clear();
    }
}
