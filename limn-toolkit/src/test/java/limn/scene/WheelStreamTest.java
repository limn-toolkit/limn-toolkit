package limn.scene;

import limn.input.Keys;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A key a widget acts on ends the wheel stream still arriving, so a trackpad's inertia does not
 * carry off what the key just revealed; the whole of that rule, as it reads at the scene. What
 * it must not do is end a stream begun after the key went down just because the key is held:
 * the platform repeats a held key every 30 ms or so, and when each repeat ended the stream, a
 * wheel turned under a held key delivered its first event and lost every one after it.
 */
class WheelStreamTest extends SceneTestBase {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    private long now = TimeUnit.SECONDS.toNanos(1);

    /** Counts the wheel events it is given, and acts on the W key and on every letter. */
    private static final class Pad extends Widget<Pad> {
        int wheels;
        int keys;
        int letters;

        Pad() {
            setFocusable(true);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (event.isPressed() && event.key() == Keys.W) {
                keys++;
                event.consume();
            }
        }

        @Override
        protected void onCharTyped(CharEvent event) {
            letters++;
            event.consume();
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (event.type() == MouseEvent.Type.WHEEL) {
                wheels++;
                event.consume();
            }
        }
    }

    private Pad focusedPad() {
        Pad pad = new Pad();
        Scene scene = new Scene(pad, () -> now);
        scene.layoutPass(200, 200);
        scene.requestFocus(pad);
        return pad;
    }

    /**
     * A key held for two seconds, repeating every 33 ms, and a wheel turned under it from just
     * after the first press, one event every 40 ms. {@code typing} holds A instead, which the pad
     * does not act on as a key, and adds the letter each press types, which it does act on.
     *
     * @return how many wheel events were sent
     */
    private int holdAKeyAndTurnTheWheel(Pad pad, boolean typing) {
        long start = now;
        long nextKey = start;
        long nextWheel = start + 5 * MS;
        boolean first = true;
        int sent = 0;
        while (now - start < 2000 * MS) {
            if (nextKey <= nextWheel) {
                now = nextKey;
                drive(pad.scene()).keyEvent(typing ? Keys.A : Keys.W, true, !first, 0);
                if (typing) {
                    drive(pad.scene()).charTyped('a');
                }
                drive(pad.scene()).inputBatchEnded();
                first = false;
                nextKey += 33 * MS;
            } else {
                now = nextWheel;
                drive(pad.scene()).scroll(0, -1, 50, 50);
                sent++;
                nextWheel += 40 * MS;
            }
        }
        return sent;
    }

    @Test
    void aHeldKeysRepeatsDoNotEndAStreamBegunAfterItsPress() {
        Pad pad = focusedPad();

        int sent = holdAKeyAndTurnTheWheel(pad, false);

        assertTrue(pad.keys > 50, "the key was held and acted on: " + pad.keys);
        assertEquals(sent, pad.wheels, "every wheel event turned under the held key arrives");
    }

    @Test
    void theLettersAHeldKeyRepeatsDoNotEndAStreamEither() {
        Pad pad = focusedPad();

        int sent = holdAKeyAndTurnTheWheel(pad, true);

        assertEquals(0, pad.keys, "only the letters are acted on");
        assertTrue(pad.letters > 50, "the letters were typed and acted on: " + pad.letters);
        assertEquals(sent, pad.wheels, "every wheel event turned under the held key arrives");
    }

    @Test
    void aFreshPressStillEndsTheStreamInFlight() {
        // The rule's purpose, which the narrowing keeps: a flick's inertia still arriving when a
        // key is pressed is dropped until it pauses, and a gesture after the pause scrolls.
        Pad pad = focusedPad();
        for (int i = 0; i < 5; i++) {
            now += 16 * MS;
            drive(pad.scene()).scroll(0, -1, 50, 50);
        }
        drive(pad.scene()).press(Keys.W);
        for (int i = 0; i < 10; i++) {
            now += 16 * MS;
            drive(pad.scene()).scroll(0, -1, 50, 50);
        }
        assertEquals(5, pad.wheels, "the tail after the press is dropped");

        now += 200 * MS;
        drive(pad.scene()).scroll(0, -1, 50, 50);
        assertEquals(6, pad.wheels, "and a new gesture after a pause arrives");
    }

    @Test
    void aFreshLetterStillEndsTheStreamInFlight() {
        Pad pad = focusedPad();
        for (int i = 0; i < 5; i++) {
            now += 16 * MS;
            drive(pad.scene()).scroll(0, -1, 50, 50);
        }
        drive(pad.scene()).keyEvent(Keys.A, true, false, 0);
        drive(pad.scene()).charTyped('a');
        drive(pad.scene()).inputBatchEnded();
        now += 16 * MS;
        drive(pad.scene()).scroll(0, -1, 50, 50);
        assertEquals(5, pad.wheels, "a letter a widget acted on ends the stream like a key");
    }
}
