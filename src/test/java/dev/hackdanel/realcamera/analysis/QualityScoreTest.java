package dev.hackdanel.realcamera.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QualityScoreTest {
    @Test
    void wellMadePortraitGetsThreeStars() {
        int q = QualityScore.score(true, 0.2, 0.33, 0.3, true, 0.0005, 0.1, 1, 0.1, false);
        assertEquals(3, QualityScore.stars(q), "q " + q);
    }

    @Test
    void outOfFocusSubjectLosesAStarOrTwo() {
        int sharp = QualityScore.score(true, 0.2, 0, 0, true, 0, 0, 0, 0, false);
        int soft = QualityScore.score(true, 0.2, 0, 0, false, 0, 0, 0, 0, false);
        assertTrue(soft < sharp - 10, sharp + " vs " + soft);
    }

    @Test
    void badExposureAndShakeMakeOneStar() {
        int q = QualityScore.score(true, 0.01, 0.95, 0.9, true, 0.02, 3.5, 8, 0, false);
        assertEquals(1, QualityScore.stars(q), "q " + q);
    }

    @Test
    void tinySubjectScoresLessThanAFilledFrame() {
        int tiny = QualityScore.score(true, 0.002, 0.33, 0.33, true, 0, 0, 0, 0, false);
        int good = QualityScore.score(true, 0.15, 0.33, 0.33, true, 0, 0, 0, 0, false);
        assertTrue(tiny < good - 10, tiny + " vs " + good);
    }

    @Test
    void sunsetLandscapeBeatsPlainGround() {
        int sunset = QualityScore.score(false, 0, 0, 0, false, 0, 0, 0, 0.4, true);
        int ground = QualityScore.score(false, 0, 0, 0, false, 0, 0, 0, 0, false);
        assertTrue(sunset > ground + 10, sunset + " vs " + ground);
    }

    @Test
    void thirdsBeatTheEdge() {
        assertTrue(QualityScore.composition(1 / 3.0, 1 / 3.0) > QualityScore.composition(0.95, 0.95));
    }
}
