package dev.hackdanel.realcamera.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hackdanel.realcamera.spec.FilmStockSpec;
import org.junit.jupiter.api.Test;

/** Scoring and film behaviour changed after the outside review. */
class ReviewFixesTest {
    @Test
    void landscapeFocusedAtOnesFeetIsNotThreeStars() {
        int sharp = QualityScore.score(false, 0, 0, 0, false, 0, 0, 0, 0.4, false, true);
        int soft = QualityScore.score(false, 0, 0, 0, false, 0, 0, 0, 0.4, false, false);
        assertTrue(QualityScore.stars(sharp) == 3 && QualityScore.stars(soft) < 3, sharp + " vs " + soft);
    }

    @Test
    void aTinyCreatureDoesNotSpoilALandscape() {
        int empty = QualityScore.score(false, 0, 0, 0, false, 0, 0, 0, 0.4, false, true);
        int tinyCow = QualityScore.score(true, 0.005, 0.9, 0.9, true, 0, 0, 0, 0.4, false, true);
        assertEquals(empty, tinyCow);
    }

    private static FilmStockSpec stock(String process) {
        return new FilmStockSpec("Test 400", process, 400, 36, 12, 3, 1.5f, new FilmStockSpec.Look(1, 1, false, 1, 1, 1, 0),
                new FilmStockSpec.Grain(0.3f, 1, 0), 0.6f);
    }

    @Test
    void negativesForgiveOverexposureSlidesDoNot() {
        assertTrue(Math.abs(stock("c41").printedEv(2)) < 0.4);
        assertEquals(2, stock("e6").printedEv(2), 1e-9);
        assertTrue(stock("c41").printedEv(-1) > -0.5 && stock("c41").printedEv(-3) < -1.5);
    }

    @Test
    void reciprocityFailureOnLongExposures() {
        assertEquals(0, stock("bw").reciprocityLossEv(0.5), 1e-9);
        // p = 0.6: a metered 10 s exposure acts like 10^0.6 = 4 s, about 1.3 EV lost
        assertEquals(1.33, stock("bw").reciprocityLossEv(10), 0.02);
    }
}
