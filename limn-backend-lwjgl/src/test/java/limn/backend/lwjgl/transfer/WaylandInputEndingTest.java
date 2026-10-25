package limn.backend.lwjgl.transfer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The order the Wayland clipboard ends its seat's input objects in, through a fake: what a close
 * sends, and what the seat losing its pointer or keyboard sends. A compositor is what would object
 * to a wrong order, and none runs here; the order is the one thing to get right without it.
 *
 * <p>Before, the close ended none of them: the pinch and the gestures manager (ADR 079 stage 2)
 * lived until GLFW closed the display, and so did the pointer and the keyboard, which the
 * compositor went on sending every motion and key to after the dispatch thread had stopped.
 */
class WaylandInputEndingTest {

    private static final long PINCH = 0x10;
    private static final long GESTURES = 0x20;
    private static final long POINTER = 0x30;
    private static final long KEYBOARD = 0x40;

    /** Every call, in order, as text. */
    private static final class Recorder implements WaylandClipboard.Ending {
        final List<String> calls = new ArrayList<>();

        @Override
        public void request(long proxy, int opcode) {
            calls.add("request 0x" + Long.toHexString(proxy) + " opcode " + opcode);
        }

        @Override
        public void forget(long proxy) {
            calls.add("forget 0x" + Long.toHexString(proxy));
        }
    }

    @Test
    void aCloseEndsThePinchThenItsManagerThenThePointerThenTheKeyboard() {
        Recorder ending = new Recorder();
        WaylandClipboard.endInput(PINCH, GESTURES, POINTER, KEYBOARD, ending);
        assertEquals(List.of(
                "request 0x10 opcode 0",  // zwp_pointer_gesture_pinch_v1.destroy
                "forget 0x20",            // zwp_pointer_gestures_v1: no destructor at version 1
                "request 0x30 opcode 1",  // wl_pointer.release, after the pinch made for it
                "request 0x40 opcode 0"), // wl_keyboard.release
                ending.calls);
    }

    @Test
    void theSeatLosingItsPointerEndsThePinchFirstAndKeepsTheManager() {
        Recorder ending = new Recorder();
        WaylandClipboard.endInput(PINCH, 0, POINTER, 0, ending);
        assertEquals(List.of("request 0x10 opcode 0", "request 0x30 opcode 1"), ending.calls);
    }

    @Test
    void whatWasNeverMadeIsNotEnded() {
        Recorder ending = new Recorder();
        // A compositor without pointer gestures, and a seat that never had a keyboard.
        WaylandClipboard.endInput(0, 0, POINTER, 0, ending);
        WaylandClipboard.endInput(0, 0, 0, KEYBOARD, ending);
        WaylandClipboard.endInput(0, 0, 0, 0, ending);
        assertEquals(List.of("request 0x30 opcode 1", "request 0x40 opcode 0"), ending.calls);
    }
}
