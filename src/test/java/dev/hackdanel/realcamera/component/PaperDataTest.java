package dev.hackdanel.realcamera.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which wall frames a print may hang in, by its paper size (issue #13). */
class PaperDataTest {
    @Test
    void smallPapersOnlyFillSingleBlockFrames() {
        for (String paper : new String[] {"10x15", "a5"}) {
            assertTrue(PaperData.fitsFrame(paper, 1, 1), paper);
            assertFalse(PaperData.fitsFrame(paper, 2, 1), paper);
            assertFalse(PaperData.fitsFrame(paper, 1, 2), paper);
            assertFalse(PaperData.fitsFrame(paper, 2, 2), paper);
        }
    }

    @Test
    void a4FillsUpToTwoByTwo() {
        assertTrue(PaperData.fitsFrame("a4", 1, 1));
        assertTrue(PaperData.fitsFrame("a4", 2, 1));
        assertTrue(PaperData.fitsFrame("a4", 1, 2));
        assertTrue(PaperData.fitsFrame("a4", 2, 2));
        assertFalse(PaperData.fitsFrame("a4", 3, 2));
        assertFalse(PaperData.fitsFrame("a4", 2, 3));
    }

    @Test
    void a3AndUnknownPaperFillEveryFrame() {
        for (int[] size : FrameData.SIZES) {
            assertTrue(PaperData.fitsFrame("a3", size[0], size[1]));
            assertTrue(PaperData.fitsFrame("", size[0], size[1]));
        }
    }

    @Test
    void smallestPaperPerFrame() {
        assertEquals("10x15", PaperData.smallestFor(1, 1));
        assertEquals("a4", PaperData.smallestFor(2, 1));
        assertEquals("a4", PaperData.smallestFor(2, 2));
        assertEquals("a3", PaperData.smallestFor(3, 2));
        assertEquals("a3", PaperData.smallestFor(4, 3));
        for (int[] size : FrameData.SIZES) {
            assertTrue(PaperData.fitsFrame(PaperData.smallestFor(size[0], size[1]), size[0], size[1]));
        }
    }
}
