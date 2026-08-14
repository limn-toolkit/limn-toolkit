package limn.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests of the static {@link Ui} facade lifecycle and delegation. */
class UiTest {

    private final AtomicLong clock = new AtomicLong();
    private ExecutorService workers;
    private UiRuntime runtime;

    private UiRuntime newRuntime() {
        workers = Executors.newFixedThreadPool(1);
        UiRuntime created = new UiRuntime(clock::get, () -> { }, workers);
        created.bindToCurrentThread();
        return created;
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            Ui.uninstall(runtime);
            runtime = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }

    @Test
    void usingUiWithoutABackendFailsWithActionableMessage() {
        assertFalse(Ui.isInstalled());
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> Ui.post(() -> { }));
        assertTrue(error.getMessage().contains("Backend"), error.getMessage());
        assertFalse(Ui.isUiThread(), "no runtime installed: nothing is the UI thread");
    }

    @Test
    void installedRuntimeReceivesDelegatedCalls() {
        runtime = newRuntime();
        Ui.install(runtime);

        AtomicReference<Thread> ranOn = new AtomicReference<>();
        Ui.post(() -> ranOn.set(Thread.currentThread()));
        assertEquals(1, runtime.drain());
        assertSame(Thread.currentThread(), ranOn.get());
        assertTrue(Ui.isUiThread());
        Ui.checkUiThread();
        assertSame(runtime.uiExecutor(), Ui.executor());
    }

    @Test
    void workIsStartedThroughTheInstalledRuntimeAndDeliversOnTheUiThread() {
        runtime = newRuntime();
        Ui.install(runtime);

        AtomicReference<Thread> deliveredOn = new AtomicReference<>();
        AtomicReference<String> received = new AtomicReference<>();
        Job job = Ui.work(progress -> "value")
                .onSuccess(value -> {
                    received.set(value);
                    deliveredOn.set(Thread.currentThread());
                })
                .start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (deliveredOn.get() == null) {
            assertTrue(System.nanoTime() < deadline, "the result never reached the UI thread");
            runtime.drain();
            Thread.onSpinWait();
        }
        assertEquals("value", received.get());
        assertSame(Thread.currentThread(), deliveredOn.get());
        assertTrue(job.isDone());
    }

    @Test
    void workWithoutABackendFailsBeforeAnythingIsSubmitted() {
        assertFalse(Ui.isInstalled());
        assertThrows(IllegalStateException.class, () -> Ui.work(progress -> "value"));
    }

    @Test
    void secondInstallOfDifferentRuntimeIsRejected() {
        runtime = newRuntime();
        Ui.install(runtime);
        Ui.install(runtime); // same instance: idempotent

        UiRuntime other = new UiRuntime(clock::get, () -> { }, workers);
        assertThrows(IllegalStateException.class, () -> Ui.install(other));
    }

    @Test
    void uninstallOnlyRemovesTheMatchingRuntime() {
        runtime = newRuntime();
        Ui.install(runtime);

        UiRuntime other = new UiRuntime(clock::get, () -> { }, workers);
        Ui.uninstall(other);
        assertTrue(Ui.isInstalled(), "uninstalling a different runtime must be a no-op");

        Ui.uninstall(runtime);
        assertFalse(Ui.isInstalled());
    }
}
