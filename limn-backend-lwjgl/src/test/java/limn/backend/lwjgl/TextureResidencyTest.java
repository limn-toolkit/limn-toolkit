package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The rules an image texture cache drops textures by, without a context: the age rule it always
 * had, and the budget the owner chose on 2026-09-27, 256 MiB by default and the application's to
 * move, least recently drawn first and never what the frame just ended drew. Keys are strings
 * compared by identity, as images are; a value is the name the test deletes it by. Frames here
 * are what {@link TextureResidency#beginFrame} separates: everything between two calls is one
 * frame.
 */
class TextureResidencyTest {

    private static final long NO_BUDGET = Long.MAX_VALUE;

    private final TextureResidency<String, String> residency = new TextureResidency<>();

    @Test
    void aTexturesBytesCountEveryLevelOfItsMipmapChain() {
        assertEquals(4, TextureResidency.mipmappedRgba8Bytes(1, 1));
        assertEquals(8 * 4 * 4 + 4 * 2 * 4 + 2 * 1 * 4 + 4, TextureResidency.mipmappedRgba8Bytes(8, 4));
        assertEquals(5 * 4 + 2 * 4 + 4, TextureResidency.mipmappedRgba8Bytes(5, 1),
                "an odd size halves down, and a side at one pixel stays there");
        assertEquals(11_058_620, TextureResidency.mipmappedRgba8Bytes(1920, 1080),
                "a photograph: four thirds of its 8,294,400 bytes, less what the halving rounds off");
    }

    @Test
    void anEntryDrawnInEachFrameStaysAndOneUndrawnFor300FramesGoesWhateverTheBudget() {
        residency.admit("kept", "kept", 100);
        residency.admit("aged", "aged", 100);
        for (int frame = 0; frame < TextureResidency.EVICT_AFTER_FRAMES; frame++) {
            assertEquals(List.of(), residency.beginFrame(NO_BUDGET), "frame " + frame);
            residency.drawn("kept");
        }
        assertEquals(List.of("aged"), residency.beginFrame(NO_BUDGET));
        assertEquals(1, residency.count());
        assertEquals(100, residency.bytes());
        assertNull(residency.peek("aged"));
    }

    /**
     * Over the budget, what goes first is what was drawn longest ago, not what was uploaded first:
     * the first picture here was drawn again after the others and outlives them.
     */
    @Test
    void overTheBudgetTheLeastRecentlyDrawnGoFirstUntilItFits() {
        residency.admit("first", "first", 100);
        residency.beginFrame(NO_BUDGET);
        residency.admit("second", "second", 100);
        residency.beginFrame(NO_BUDGET);
        residency.admit("third", "third", 100);
        residency.beginFrame(NO_BUDGET);
        residency.drawn("first");
        residency.beginFrame(NO_BUDGET);
        residency.admit("fourth", "fourth", 100);

        assertEquals(List.of("second", "third"), residency.beginFrame(250),
                "400 bytes against 250: the two drawn longest ago go, and then it fits");
        assertEquals(200, residency.bytes());
        assertEquals("first", residency.peek("first"));
        assertEquals("fourth", residency.peek("fourth"));
    }

    @Test
    void entriesLastDrawnInTheSameFrameGoInTheOrderTheyWereUploaded() {
        String a = "a";
        String b = "b";
        String c = "c";
        residency.admit(c, c, 10);
        residency.admit(a, a, 10);
        residency.admit(b, b, 10);
        residency.beginFrame(NO_BUDGET);
        residency.beginFrame(NO_BUDGET);
        assertEquals(List.of(c, a), residency.beginFrame(10));
    }

    /**
     * What the frame that just ended drew is never dropped, however far over the budget: dropping
     * it would upload it again in the next frame, every frame, for a screen that has not changed.
     */
    @Test
    void nothingTheFrameThatJustEndedDrewIsDroppedHoweverFarOverTheBudget() {
        residency.admit("left", "left", 100);
        residency.admit("right", "right", 100);
        assertEquals(List.of(), residency.beginFrame(0), "both drawn: 200 bytes kept against 0");
        assertEquals(200, residency.bytes());

        residency.drawn("right");
        assertEquals(List.of("left"), residency.beginFrame(0), "the one not drawn goes");
        assertEquals(List.of(), residency.beginFrame(NO_BUDGET), "under no budget, nothing more");
        assertEquals("right", residency.peek("right"));
    }

    /**
     * A picture larger than the whole budget still draws: it is kept for as long as every frame
     * draws it, it pushes out whatever else the frame did not draw, and it goes at the first
     * boundary after a frame that did not draw it.
     */
    @Test
    void aTextureLargerThanTheBudgetIsKeptWhileDrawnAndGoesAfterTheFirstFrameThatDidNotDrawIt() {
        long budget = 1000;
        residency.admit("icon", "icon", 10);
        residency.beginFrame(budget);
        residency.admit("poster", "poster", 5000);
        assertEquals(List.of("icon"), residency.beginFrame(budget),
                "the poster alone is over: what the last frame did not draw makes way");
        for (int frame = 0; frame < 5; frame++) {
            residency.drawn("poster");
            assertEquals(List.of(), residency.beginFrame(budget), "drawn, kept: frame " + frame);
        }
        assertEquals(List.of("poster"), residency.beginFrame(budget), "not drawn, gone");
        assertEquals(0, residency.bytes());
        assertEquals(0, residency.count());
    }

    @Test
    void underTheBudgetOnlyAgeDropsAnything() {
        residency.admit("a", "a", 100);
        residency.admit("b", "b", 100);
        for (int frame = 0; frame < 10; frame++) {
            assertEquals(List.of(), residency.beginFrame(200));
        }
        assertEquals(2, residency.count());
    }

    @Test
    void peekingDoesNotCountAsDrawingAndClearingHandsBackEverything() {
        residency.admit("a", "a", 100);
        residency.admit("b", "b", 100);
        residency.beginFrame(NO_BUDGET);
        residency.drawn("b");
        assertSame("a", residency.peek("a"));
        assertEquals(List.of("a"), residency.beginFrame(100), "a peek is not a draw");

        residency.admit("c", "c", 7);
        assertEquals(List.of("b", "c"), residency.clear().stream().sorted().toList());
        assertEquals(0, residency.count());
        assertEquals(0, residency.bytes());
    }
}
