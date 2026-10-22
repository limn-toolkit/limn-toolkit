package limn.backend.lwjgl.a11y.windows;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When a window's provider is handed over from another thread, and what the window's thread gets
 * back when it is.
 *
 * <p>The crash itself is a guest's to produce: it needs an ARM64 JDK on Windows and a client in
 * another process. What is checked here is the decision the guest's runs drew — a single-threaded
 * apartment inside a message another thread is still blocked on, and nothing else — and that the
 * hand-over answers with the call's own result, from another thread, and never leaves the
 * window's thread waiting past its bound.
 */
class UiaAnswerThreadTest {

    @Test
    void handsOverOnlyFromASingleThreadedApartmentInsideABlockedSend() {
        int send = UiaAnswerThread.ISMEX_SEND;
        // The two states every crash on the guest had: OLE's apartment, and a plain one.
        assertTrue(UiaAnswerThread.mustHandOver(send, UiaAnswerThread.APTTYPE_MAINSTA));
        assertTrue(UiaAnswerThread.mustHandOver(send, UiaAnswerThread.APTTYPE_STA));
        // The multithreaded apartment answered on the guest; so does a thread COM never touched.
        assertFalse(UiaAnswerThread.mustHandOver(send, 1));
        assertFalse(UiaAnswerThread.mustHandOver(send, UiaAnswerThread.NO_APARTMENT));
        // A message this thread sent itself, or one already replied to, blocks nobody.
        assertFalse(UiaAnswerThread.mustHandOver(0, UiaAnswerThread.APTTYPE_MAINSTA));
        assertFalse(UiaAnswerThread.mustHandOver(send | UiaAnswerThread.ISMEX_REPLIED,
                UiaAnswerThread.APTTYPE_MAINSTA));
    }

    @Test
    void theCallIsMadeOnAnotherThreadAndItsAnswerReturned() {
        AtomicReference<String> where = new AtomicReference<>();
        long answer = UiaAnswerThread.away(() -> {
            where.set(Thread.currentThread().getName());
            return 65_536;
        }, UiaAnswerThread.BOUND_MILLIS);
        assertEquals(65_536, answer);
        assertNotEquals(Thread.currentThread().getName(), where.get());
        assertTrue(where.get().startsWith("limn-a11y-uia-answer"), where.get());
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals(where.get())).allMatch(Thread::isDaemon));
    }

    @Test
    void aCallThatOutlivesTheBoundAnswersNothingAndDoesNotHoldTheNextOne() throws InterruptedException {
        CountDownLatch stuck = new CountDownLatch(1);
        long started = System.nanoTime();
        long late = UiaAnswerThread.away(() -> {
            try {
                stuck.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 65_536;
        }, 50);
        long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(0, late);
        assertTrue(waitedMillis < UiaAnswerThread.BOUND_MILLIS, waitedMillis + " ms");
        // The stuck call keeps its thread; the next window's question gets a thread of its own.
        assertEquals(7, UiaAnswerThread.away(() -> 7, UiaAnswerThread.BOUND_MILLIS));
        stuck.countDown();
    }

    @Test
    void aCallThatFailsAnswersNothing() {
        assertEquals(0, UiaAnswerThread.away(() -> {
            throw new IllegalStateException("refused");
        }, UiaAnswerThread.BOUND_MILLIS));
    }

    @Test
    void offWindowsNothingIsHandedOver() {
        // No user32 and no ole32 here: the state reads as "not inside a send, no apartment", so a
        // caller on this machine always makes the call itself.
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            assertEquals(0, UiaAnswerThread.inSendFlags());
            assertEquals(UiaAnswerThread.NO_APARTMENT, UiaAnswerThread.apartmentType());
            AtomicReference<String> where = new AtomicReference<>();
            UiaAnswerThread.answer(() -> {
                where.set(Thread.currentThread().getName());
                return 0;
            });
            assertEquals(Thread.currentThread().getName(), where.get());
        }
    }
}
