package limn.desktop;

import limn.backend.spi.TrayHost;
import limn.backend.spi.TrayPeer;
import limn.backend.spi.TrayPicture;
import limn.backend.spi.TrayState;
import limn.graphics.BitmapIcon;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A tray plays an animated icon in each of its pictures (ADR 065 section 10): its peer receives
 * every frame at its time, with the dot on every frame, only the picture shown moves, and a loop
 * held up goes on at the frame that is due when it is let go.
 */
class TrayAnimationTest {

    /** A peer that remembers each state. */
    private static final class Recording implements TrayPeer {
        final List<TrayState> states = new ArrayList<>();

        @Override
        public void update(TrayState state) {
            states.add(state);
        }

        @Override
        public void hide() {
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void dispose() {
        }

        TrayState last() {
            return states.get(states.size() - 1);
        }
    }

    private final AtomicLong nanos = new AtomicLong();
    private HeadlessUi ui;
    private Recording peer;
    private Tray tray;

    /** {@code count} frames of 100 ms; {@code turns} 0 for no end. */
    private static Image animation(int count, int turns) {
        List<Image> frames = new ArrayList<>();
        int[] millis = new int[count];
        for (int f = 0; f < count; f++) {
            byte[] rgba = new byte[4 * 4 * 4];
            for (int o = 0; o < rgba.length; o += 4) {
                rgba[o] = (byte) (f * 10);
                rgba[o + 3] = (byte) 0xFF;
            }
            frames.add(new Image(4, 4, rgba));
            millis[f] = 100;
        }
        return Images.animation(frames, millis, turns);
    }

    @BeforeEach
    void setUp() {
        ui = new HeadlessUi(nanos::get);
        peer = new Recording();
        TrayHost[] held = new TrayHost[1];
        tray = Tray.over("orders", host -> {
            held[0] = host;
            return peer;
        }, nanos::get);
    }

    @AfterEach
    void tearDown() {
        tray.dispose();
        ui.close();
    }

    private void after(int millis) {
        nanos.addAndGet(millis * 1_000_000L);
        ui.runtime().drain();
    }

    private int frameShown() {
        TrayPicture picture = peer.last().picture();
        assertNotNull(picture);
        return picture.frame();
    }

    @Test
    void thePeerReceivesEveryFrameAtItsTime() {
        tray.setIcon(BitmapIcon.picture(animation(24, 0)));
        assertEquals(0, frameShown());
        List<Integer> seen = new ArrayList<>();
        for (int step = 0; step < 30; step++) {
            after(100);
            seen.add(frameShown());
        }
        for (int step = 0; step < 30; step++) {
            assertEquals((step + 1) % 24, seen.get(step), "every frame of 24, in turn: " + seen);
        }
        assertEquals(31, peer.states.size(), "one state a frame and no more");
    }

    @Test
    void theDotIsOnEveryFrame() {
        tray.setIcon(BitmapIcon.picture(animation(3, 0))).setAttention(true);
        after(100);
        assertEquals(1, frameShown());
        assertNotNull(peer.last().picture().badge());
        Image drawn = peer.last().picture().render(16, null, false);
        assertNotEquals(0, drawn.pixels()[(15) * 4 + 3], "the dot's corner is painted on this frame too");
    }

    @Test
    void anIconRestsOnItsLastFrameWhenItsTurnsAreDone() {
        tray.setIcon(BitmapIcon.picture(animation(3, 1)));
        after(100);
        after(100);
        assertEquals(2, frameShown());
        int sent = peer.states.size();
        after(1000);
        assertEquals(sent, peer.states.size(), "nothing more is sent once it rests");
        assertEquals(-1, ui.runtime().nanosUntilNextDeadline(), "and nothing waits");
    }

    @Test
    void aLoopHeldUpGoesOnAtTheFrameDueWhenItIsLetGo() {
        tray.setIcon(BitmapIcon.picture(animation(10, 0)));
        // The loop held for 450 ms, as macOS holds it with a menu open: one wake, late.
        after(450);
        assertEquals(4, frameShown(), "the frame due at 450 ms, and not the next after the last");
        assertEquals(2, peer.states.size(), "the frames it missed are not walked through");
        after(50);
        assertEquals(5, frameShown());
    }

    @Test
    void onlyThePictureShownMoves() {
        tray.setIcon(BitmapIcon.picture(animation(10, 0)))
                .setAttentionIcon(BitmapIcon.picture(animation(10, 0)));
        after(200);
        assertEquals(2, frameShown());
        tray.setAttention(true);
        assertEquals(0, frameShown(), "the attention icon starts where it was, its first frame");
        after(300);
        assertEquals(3, frameShown());
        tray.setAttention(false);
        assertEquals(2, frameShown(), "the icon waited where nobody saw it");
    }

    @Test
    void aStillIconAsksForNothing() {
        tray.setIcon(BitmapIcon.picture(animation(3, 0).frame(1)));
        assertEquals(-1, ui.runtime().nanosUntilNextDeadline());
        assertEquals(0, frameShown());
    }

    @Test
    void aMonochromeIconPlaysBesideTheIcon() {
        tray.setIcon(BitmapIcon.picture(animation(4, 0)))
                .setMonochromeIcon(BitmapIcon.mask(animation(4, 0)));
        after(100);
        assertEquals(1, frameShown());
        assertEquals(1, peer.last().monochrome().frame());
    }

    @Test
    void disposingStopsTheClock() {
        tray.setIcon(BitmapIcon.picture(animation(4, 0)));
        tray.dispose();
        assertEquals(-1, ui.runtime().nanosUntilNextDeadline());
    }
}
