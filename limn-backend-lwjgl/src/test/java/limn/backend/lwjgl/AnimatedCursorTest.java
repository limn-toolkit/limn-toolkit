package limn.backend.lwjgl;

import limn.backend.ImageCursor;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An image cursor of an animated image plays (ADR 065 section 10): every frame is shown at its
 * time, and the cache keeps an animation of more frames than it holds cursors whole, made once,
 * where one entry a frame destroyed and made a native cursor at every swap.
 */
class AnimatedCursorTest {

    private final AtomicLong nanos = new AtomicLong();
    private HeadlessUi ui;

    /** Natives that count what is made and destroyed, each handle a new number. */
    private final List<Long> made = new ArrayList<>();
    private final Set<Long> destroyed = new HashSet<>();
    private final ImageCursors.Natives natives = new ImageCursors.Natives() {
        private long next = 1;

        @Override
        public long create(Image image, int hotspotX, int hotspotY) {
            made.add(next);
            return next++;
        }

        @Override
        public void destroy(long handle) {
            destroyed.add(handle);
        }
    };

    @BeforeEach
    void setUp() {
        ui = new HeadlessUi(nanos::get);
    }

    @AfterEach
    void tearDown() {
        ui.close();
    }

    private static Image animation(int count, int millis, int turns) {
        List<Image> frames = new ArrayList<>();
        int[] times = new int[count];
        for (int f = 0; f < count; f++) {
            frames.add(new Image(4, 4, new byte[64]));
            times[f] = millis;
        }
        return Images.animation(frames, times, turns);
    }

    private void after(int millis) {
        nanos.addAndGet(millis * 1_000_000L);
        ui.runtime().drain();
    }

    @Test
    void anAnimationOfMoreFramesThanTheCacheHoldsIsKeptWhole() {
        ImageCursor spinning = new ImageCursor(animation(24, 33, 0), 2, 2);
        ImageCursor[] shown = {spinning};
        ImageCursors cache = new ImageCursors(natives, cursor -> cursor.equals(shown[0]));
        for (int turn = 0; turn < 3; turn++) {
            for (int frame = 0; frame < 24; frame++) {
                cache.handle(spinning, frame);
            }
        }
        assertEquals(24, made.size(), "a native cursor a frame, made once in three turns");
        assertTrue(destroyed.isEmpty());
        assertEquals(1, cache.size(), "one cursor in the cache however many frames");
    }

    @Test
    void anAnimatedCursorCountsOnceAgainstTheCap() {
        ImageCursors cache = new ImageCursors(natives, cursor -> false);
        ImageCursor spinning = new ImageCursor(animation(24, 33, 0), 0, 0);
        for (int frame = 0; frame < 24; frame++) {
            cache.handle(spinning, frame);
        }
        for (int still = 0; still < ImageCursors.MAX - 1; still++) {
            cache.handle(new ImageCursor(new Image(2, 2, new byte[16]), 0, 0), 0);
        }
        assertTrue(destroyed.isEmpty(), "sixteen cursors fit, the animated one among them");
        cache.handle(new ImageCursor(new Image(2, 2, new byte[16]), 0, 0), 0);
        assertEquals(24, destroyed.size(), "the oldest, the animation, goes with every frame it had");
        cache.destroyAll();
        assertEquals(made.size(), destroyed.size(), "and the end destroys every one");
    }

    @Test
    void aCursorShownIsNeverEvicted() {
        ImageCursor first = new ImageCursor(new Image(2, 2, new byte[16]), 0, 0);
        ImageCursors cache = new ImageCursors(natives, cursor -> cursor.equals(first));
        long kept = cache.handle(first, 0);
        for (int other = 0; other < ImageCursors.MAX; other++) {
            cache.handle(new ImageCursor(new Image(2, 2, new byte[16]), 0, 0), 0);
        }
        assertTrue(!destroyed.contains(kept), "the one a window shows stays");
    }

    @Test
    void thePlayerShowsEveryFrameAtItsTime() {
        List<Integer> shown = new ArrayList<>();
        CursorPlayer player = new CursorPlayer(ui.runtime(), nanos::get, shown::add);
        player.play(new ImageCursor(animation(24, 33, 0), 0, 0));
        for (int step = 0; step < 30; step++) {
            after(33);
        }
        assertEquals(30, shown.size(), "a swap a frame: " + shown);
        for (int step = 0; step < 30; step++) {
            assertEquals((step + 1) % 24, shown.get(step));
        }
    }

    @Test
    void aPlayerHeldUpShowsTheFrameDueAndStopsWhenTheCursorChanges() {
        List<Integer> shown = new ArrayList<>();
        CursorPlayer player = new CursorPlayer(ui.runtime(), nanos::get, shown::add);
        player.play(new ImageCursor(animation(10, 100, 0), 0, 0));
        after(450);
        assertEquals(List.of(4), shown, "late, it goes to the frame due and skips the ones missed");
        player.play(new ImageCursor(new Image(2, 2, new byte[16]), 0, 0));
        after(1000);
        assertEquals(List.of(4), shown, "a still cursor plays nothing");
        assertEquals(-1, ui.runtime().nanosUntilNextDeadline());
    }

    @Test
    void aCursorRestsOnItsLastFrame() {
        List<Integer> shown = new ArrayList<>();
        CursorPlayer player = new CursorPlayer(ui.runtime(), nanos::get, shown::add);
        player.play(new ImageCursor(animation(3, 100, 1), 0, 0));
        after(100);
        after(100);
        after(1000);
        assertEquals(List.of(1, 2), shown);
        assertEquals(2, player.frame());
        assertEquals(-1, ui.runtime().nanosUntilNextDeadline());
    }
}
