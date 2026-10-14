package limn.graphics;

import limn.animation.ImageAnimation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An animation read as it plays: the frame asked for and a few after it are in memory, the rest
 * are decoded in order when their turn comes, a turn starts the file again unless its frames were
 * kept somewhere, and a file that breaks part way is the frames before the break.
 */
class StreamedFramesTest {

    /** Ten frames, each a pixel that says which it is; counts what is decoded and how often it starts again. */
    private static final class Counting implements StreamedFrames.Source {
        int at;
        int decoded;
        int rewinds;
        int breaksAt = Integer.MAX_VALUE;

        @Override
        public Image next() {
            if (at >= 10 || at >= breaksAt) {
                return null;
            }
            decoded++;
            return new Image(1, 1, new byte[] {(byte) at++, 0, 0, (byte) 0xFF});
        }

        @Override
        public void rewind() {
            at = 0;
            rewinds++;
        }

        @Override
        public int position() {
            return at;
        }
    }

    private static int[] times() {
        int[] millis = new int[10];
        java.util.Arrays.fill(millis, 100);
        return millis;
    }

    private static int which(Image frame) {
        return frame.pixels()[0];
    }

    /** With no runtime installed the work is done on the spot, which is what makes these steps exact. */
    private static StreamedFrames frames(Counting source, StreamedFrames.Store store) {
        Image first = source.next();
        source.rewind();
        source.rewinds = 0;
        source.decoded = 0;
        return new StreamedFrames(source, store, first, times(), 0, 3);
    }

    @Test
    void theFrameAskedForAndAFewAfterItAreReadyAndTheRestAreNotDecoded() {
        Counting source = new Counting();
        StreamedFrames frames = frames(source, null);
        assertEquals(0, which(frames.ready(0)), "the first is always there");
        assertEquals(4, source.decoded, "and asking for it decodes the three after it, and the first on the way");
        assertEquals(1, which(frames.ready(1)));
        assertEquals(5, source.decoded, "one frame's work a frame");
        for (int i = 2; i < 10; i++) {
            assertEquals(i, which(frames.ready(i)), "frame " + i);
        }
        assertEquals(1, source.rewinds, "the end of a turn looks ahead into the next, which starts the file again, once");
    }

    @Test
    void aTurnStartsTheFileAgainUnlessItsFramesWereKept() {
        Counting source = new Counting();
        StreamedFrames frames = frames(source, null);
        for (int turn = 0; turn < 3; turn++) {
            for (int i = 0; i < 10; i++) {
                assertEquals(i, which(frames.ready(i)));
            }
        }
        assertTrue(source.rewinds >= 2, "three turns read the file three times: " + source.rewinds);

        Counting kept = new Counting();
        Map<Integer, Image> store = new HashMap<>();
        StreamedFrames remembered = frames(kept, new StreamedFrames.Store() {
            @Override
            public Image peek(int index) {
                return store.get(index);
            }

            @Override
            public void put(int index, Image frame) {
                store.put(index, frame);
            }
        });
        for (int i = 0; i < 10; i++) {
            remembered.ready(i);
        }
        int afterOneTurn = kept.decoded;
        Image seventh = remembered.ready(7);
        for (int turn = 0; turn < 3; turn++) {
            for (int i = 0; i < 10; i++) {
                assertNotNull(remembered.ready(i));
            }
        }
        assertEquals(afterOneTurn, kept.decoded, "kept where they outlive the window, nothing is decoded twice");
        assertSame(seventh, remembered.ready(7), "and a frame is the same object each time");
    }

    @Test
    void aFrameFarAheadIsDecodedWhenAskedForOutrightAndOneBehindStartsAgain() {
        Counting source = new Counting();
        StreamedFrames frames = frames(source, null);
        assertEquals(8, which(frames.frame(8)), "decoded here, with the frames before it");
        assertEquals(9, source.decoded);
        assertEquals(2, which(frames.frame(2)));
        assertEquals(1, source.rewinds, "behind the reader's place: from the first again");
    }

    @Test
    void aFileThatBreaksPartWayIsTheFramesBeforeTheBreak() {
        Counting source = new Counting();
        StreamedFrames frames = frames(source, null);
        source.breaksAt = 5;
        for (int i = 0; i < 5; i++) {
            assertEquals(i, which(frames.ready(i)), "frame " + i);
        }
        assertNull(frames.ready(5), "never ready, and asking throws nothing");
        assertNull(frames.ready(6));
        assertThrows(limn.io.MalformedContentException.class, () -> frames.frame(6),
                "asked for outright, a frame that cannot be read is a damaged file");
    }

    /** The clockwork does not run ahead of the decoder: the frame before stays until the next is there. */
    @Test
    void anAnimationWaitsOnTheFrameBeforeForOneThatIsNotReady() {
        boolean[] arrived = {false};
        Image first = new Image(1, 1, new byte[] {0, 0, 0, (byte) 0xFF});
        Image second = new Image(1, 1, new byte[] {1, 0, 0, (byte) 0xFF});
        Image image = new Image(first, new Image.Frames() {
            @Override
            public int count() {
                return 2;
            }

            @Override
            public Image frame(int index) {
                return index == 0 ? first : second;
            }

            @Override
            public int millis(int index) {
                return 100;
            }

            @Override
            public int loopCount() {
                return 0;
            }

            @Override
            public Image ready(int index) {
                return index == 0 ? first : arrived[0] ? second : null;
            }
        });
        ImageAnimation animation = new ImageAnimation(image);
        animation.advance(0.15);
        animation.advance(0.15);
        assertEquals(0, animation.frameIndex(), "held on the frame before, however late");
        assertSame(first, animation.frame());
        arrived[0] = true;
        animation.advance(0.001);
        assertEquals(1, animation.frameIndex(), "and on as soon as it is there, owing none of the wait");
        assertSame(second, animation.frame());
        animation.advance(0.05);
        assertEquals(1, animation.frameIndex());
    }
}
