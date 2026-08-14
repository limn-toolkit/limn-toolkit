package limn.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The crash registry's install/dispatch/report contract. */
class CrashesTest {

    private CrashHandler installed;

    @AfterEach
    void uninstall() {
        if (installed != null) {
            Crashes.uninstall(installed);
        }
    }

    private void install(CrashHandler handler) {
        installed = handler;
        Crashes.install(handler);
    }

    @Test
    void defaultHandlerLogsAndContinues() {
        assertTrue(Crashes.dispatch(CrashPhase.FRAME,
                new RuntimeException("boom (expected, must be logged not thrown)")));
    }

    @Test
    void dispatchRoutesToTheInstalledHandlerAndHonorsItsVerdict() {
        AtomicReference<CrashPhase> seenPhase = new AtomicReference<>();
        AtomicReference<Throwable> seenError = new AtomicReference<>();
        install((phase, error) -> {
            seenPhase.set(phase);
            seenError.set(error);
            return false;
        });
        RuntimeException boom = new RuntimeException("boom");
        assertFalse(Crashes.dispatch(CrashPhase.EVENT_POLL, boom));
        assertEquals(CrashPhase.EVENT_POLL, seenPhase.get());
        assertSame(boom, seenError.get());
    }

    @Test
    void reportIsANoOpWithTheDefaultHandlerButReachesACustomOne() {
        // Default: contained sites already log; report must not double-log or throw.
        assertDoesNotThrow(() -> Crashes.report(CrashPhase.TASK, new RuntimeException("boom")));

        AtomicInteger calls = new AtomicInteger();
        install((phase, error) -> {
            calls.incrementAndGet();
            return false; // ignored for report()
        });
        Crashes.report(CrashPhase.TASK, new RuntimeException("boom"));
        assertEquals(1, calls.get());
    }

    @Test
    void aThrowingHandlerIsContainedAndTreatedAsContinue() {
        install((phase, error) -> {
            throw new IllegalStateException("handler bug (expected, must be contained)");
        });
        assertTrue(Crashes.dispatch(CrashPhase.FRAME, new RuntimeException("boom")));
        assertDoesNotThrow(() -> Crashes.report(CrashPhase.TASK, new RuntimeException("boom")));
    }

    @Test
    void uninstallOnlyRemovesTheCurrentHandler() {
        AtomicInteger calls = new AtomicInteger();
        CrashHandler first = (phase, error) -> {
            calls.incrementAndGet();
            return true;
        };
        install(first);
        Crashes.uninstall((phase, error) -> true); // someone else's handler: no effect
        Crashes.dispatch(CrashPhase.FRAME, new RuntimeException("boom"));
        assertEquals(1, calls.get());
    }

    @Test
    void shutdownRequestedCarriesTheOriginalCrash() {
        RuntimeException boom = new RuntimeException("boom");
        assertSame(boom, Crashes.shutdownRequested(boom).getCause());
    }
}
