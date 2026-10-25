package limn.backend.lwjgl;

import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The window a print panel or a file panel was a sheet on is asked for again at each of the two
 * asks that give it the keyboard back, never kept from the first. An application that closes the
 * window on the answer, {@code document.print(window).thenAccept(r -> window.close())}, destroys
 * it between them, and GLFW releases the {@code NSWindow} as it does: the pointer kept for the
 * second ask, 400 ms on, was a freed object, and messaging it took the process down. No
 * {@code NSWindow} is made here; what is asked of a live one is handed in, and the window is what
 * {@link LwjglWindow#cocoaWindow} answers, {@code NULL} once it is destroyed.
 */
class MacKeyboardBackTest {

    private static final long NS_WINDOW = 0x7F00_1234_5000L;

    private final AtomicLong now = new AtomicLong(TimeUnit.SECONDS.toNanos(1));

    @Test
    void aWindowDestroyedBetweenTheAsksIsNotMessagedAgain() {
        try (HeadlessUi ui = new HeadlessUi(now::get)) {
            AtomicLong window = new AtomicLong(NS_WINDOW);
            List<Long> messaged = new ArrayList<>();
            MacFileDialogs.giveTheKeyboardBack(window::get, messaged::add);
            assertEquals(List.of(NS_WINDOW), messaged, "the live window, asked at once");
            window.set(0); // the application closed it on the answer
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
            ui.runtime().drain();
            assertEquals(List.of(NS_WINDOW), messaged, "the second ask finds it gone and messages nothing");
        }
    }

    @Test
    void aWindowStillThereIsAskedTwice() {
        try (HeadlessUi ui = new HeadlessUi(now::get)) {
            List<Long> messaged = new ArrayList<>();
            MacFileDialogs.giveTheKeyboardBack(() -> NS_WINDOW, messaged::add);
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(399));
            ui.runtime().drain();
            assertEquals(List.of(NS_WINDOW), messaged, "not before the sheet has left");
            now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
            ui.runtime().drain();
            assertEquals(List.of(NS_WINDOW, NS_WINDOW), messaged, "and once more after it");
        }
    }

    @Test
    void noWindowIsNeverAsked() {
        try (HeadlessUi ui = new HeadlessUi(now::get)) {
            List<Long> messaged = new ArrayList<>();
            MacFileDialogs.giveTheKeyboardBack(MacFileDialogs.NO_OWNER, messaged::add);
            now.addAndGet(TimeUnit.SECONDS.toNanos(1));
            ui.runtime().drain();
            assertEquals(List.of(), messaged, "a panel that was a window of its own has no owner to ask");
        }
    }
}
