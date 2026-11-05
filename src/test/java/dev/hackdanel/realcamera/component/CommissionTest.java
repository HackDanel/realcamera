package dev.hackdanel.realcamera.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hackdanel.realcamera.gameplay.Journal;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Newspaper orders and encyclopedia pages from a photo's analysis. */
class CommissionTest {
    private static PrintData print(String biome, int stars, List<PhotoMeta.Subject> subjects, List<String> phenomena) {
        PhotoMeta.Analysis a = new PhotoMeta.Analysis(subjects, "", 0.3f, false, false, 0, 6000, 0, phenomena, 0, 0,
                stars * 30, stars);
        PhotoMeta meta = new PhotoMeta(UUID.randomUUID(), UUID.randomUUID(), "A", 0, 100, 100,
                new PhotoMeta.Exif("cam", "lens", 2.8f, 0.01, 100, 50, 50, 1, 0, 0),
                new PhotoMeta.Place("minecraft:overworld", biome, 0, 64, 0, 3)).withAnalysis(Optional.of(a));
        return new PrintData(meta, "10x15", "glossy", 0, "");
    }

    private static final PhotoMeta.Subject PANDA = new PhotoMeta.Subject("minecraft:panda", 0.2f, true, 5, 0.3f, 0.3f);
    private static final PhotoMeta.Subject BLURRY_PANDA = new PhotoMeta.Subject("minecraft:panda", 0.2f, false, 5, 0, 0);

    @Test
    void pandaInBambooJungle() {
        Commission c = new Commission(Optional.of("minecraft:panda"), Optional.of("minecraft:bamboo_jungle"), Optional.empty(), 2, 3);
        assertTrue(c.matches(print("minecraft:bamboo_jungle", 2, List.of(PANDA), List.of())));
        assertFalse(c.matches(print("minecraft:plains", 3, List.of(PANDA), List.of())), "wrong biome");
        assertFalse(c.matches(print("minecraft:bamboo_jungle", 1, List.of(PANDA), List.of())), "too few stars");
        assertFalse(c.matches(print("minecraft:bamboo_jungle", 3, List.of(BLURRY_PANDA), List.of())), "out of focus");
    }

    @Test
    void phenomenonOrder() {
        Commission c = new Commission(Optional.empty(), Optional.empty(), Optional.of("sunset"), 2, 3);
        assertTrue(c.matches(print("minecraft:plains", 2, List.of(), List.of("sunset"))));
        assertFalse(c.matches(print("minecraft:plains", 2, List.of(), List.of("rain"))));
    }

    @Test
    void journalOpensPagesAndKeepsTheBestPhoto() {
        List<String> fresh = new ArrayList<>();
        PrintData first = print("minecraft:bamboo_jungle", 1, List.of(PANDA), List.of("rain"));
        Journal j = Journal.EMPTY.record(first.photo(), fresh);
        assertEquals(List.of("dimension/minecraft:overworld", "biome/minecraft:bamboo_jungle", "creature/minecraft:panda",
                "phenomenon/rain"), fresh);
        fresh.clear();
        PrintData better = print("minecraft:bamboo_jungle", 3, List.of(PANDA), List.of());
        j = j.record(better.photo(), fresh);
        assertTrue(fresh.isEmpty());
        assertEquals(better.photo().id(), j.pages().get("creature/minecraft:panda").photo());
        assertEquals(first.photo().id(), j.pages().get("phenomenon/rain").photo());
        assertEquals(1, j.count(Journal.CREATURE));
    }
}
