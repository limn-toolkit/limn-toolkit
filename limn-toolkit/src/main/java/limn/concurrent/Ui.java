package limn.concurrent;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Static facade over the toolkit's {@link UiRuntime}, Limn's first-class
 * async API. The backend installs the runtime at startup; application code
 * then uses:
 *
 * <pre>{@code
 * button.onAction(() ->
 *     Ui.async(() -> repository.load())          // worker pool
 *       .thenAccept(data -> label.setText(data)) // back on the UI thread
 * );
 * }</pre>
 *
 * <p>Thread confinement is a hard rule: every widget mutation calls
 * {@link #checkUiThread()} and throws {@link IllegalStateException} when
 * violated.
 */
public final class Ui {

    private static volatile UiRuntime runtime;

    private Ui() {
    }

    /**
     * Installs the process-wide runtime. Called by the backend once at startup.
     *
     * @throws IllegalStateException if a different runtime is already installed
     */
    public static synchronized void install(UiRuntime candidate) {
        Objects.requireNonNull(candidate, "candidate");
        UiRuntime current = runtime;
        if (current != null && current != candidate) {
            throw new IllegalStateException("A UiRuntime is already installed; close the previous Backend first.");
        }
        runtime = candidate;
    }

    /** Uninstalls {@code candidate} if it is the installed runtime (backend shutdown). */
    public static synchronized void uninstall(UiRuntime candidate) {
        if (runtime == candidate) {
            runtime = null;
        }
    }

    /** @return whether a runtime is installed (i.e. a Backend is running) */
    public static boolean isInstalled() {
        return runtime != null;
    }

    /**
     * Runs {@code action} on the UI thread on the next frame. Any-thread safe.
     *
     * <p><b>A task that mutates widget or scene state is responsible for
     * invalidating what it touched</b>: {@link limn.scene.Widget#invalidate()},
     * {@link limn.scene.Widget#markNeedsLayout()} or
     * {@link limn.scene.Scene#requestRender()}. Running a task buys no frame of
     * its own, so a mutation nothing invalidates stays unpainted until
     * something else asks for one. Every widget setter and every tree change
     * invalidates already; a task that writes a field behind a setter's back,
     * or that changes what a custom {@code onPaint} reads, does not.
     */
    public static void post(Runnable action) {
        require().post(action);
    }

    /**
     * Runs {@code action} on the UI thread after {@code delayMillis}.
     * Any-thread safe.
     *
     * <p><b>A task that mutates widget or scene state is responsible for
     * invalidating what it touched</b>: see {@link #post}. Firing buys no
     * frame of its own, which is what makes a timer the cheap way to watch
     * something that rarely changes: a poll that re-reads a value and finds it
     * unchanged costs a wake-up and nothing else, where a
     * {@link limn.scene.Scene#addTicker ticker} asks for a frame every frame it
     * stays registered.
     */
    public static void postDelayed(Runnable action, long delayMillis) {
        require().postDelayed(action, delayMillis);
    }

    /**
     * Runs {@code work} on the worker pool; the returned stage completes on
     * the UI thread (including its default async executor).
     *
     * <p><b>A dependent that mutates widget or scene state is responsible for
     * invalidating what it touched</b>: see {@link #post}. Completing on the
     * UI thread buys no frame; {@code label.setText(data)} asks for the frame
     * that shows it, a field written directly does not.
     */
    public static <T> CompletionStage<T> async(Supplier<T> work) {
        return require().async(work);
    }

    /**
     * Describes background work with a lifecycle: cancellable, able to report
     * progress, and able to decline delivery when whoever asked has gone away.
     * Nothing runs until {@link Work#start()}:
     *
     * <pre>{@code
     * job = Ui.work(progress -> repository.search(term))
     *         .onSuccess(results::setItems)
     *         .deliverIf(results::isAttached)
     *         .start();
     * }</pre>
     *
     * <p>Reach for this over {@link #async} whenever the request can be
     * superseded: cancelling the previous job means the view shows the answer
     * to the question last <em>asked</em>, rather than whichever answer
     * happened to finish last.
     */
    public static <T> Work<T> work(Work.Body<T> body) {
        return require().work(body);
    }

    /** @return {@code true} iff called on the UI thread (false when no runtime is installed) */
    public static boolean isUiThread() {
        UiRuntime current = runtime;
        return current != null && current.isUiThread();
    }

    /**
     * @throws IllegalStateException if not on the UI thread (or no runtime installed)
     */
    public static void checkUiThread() {
        require().checkUiThread();
    }

    /** @return an executor that posts to the UI thread */
    public static Executor executor() {
        return require().uiExecutor();
    }

    private static UiRuntime require() {
        UiRuntime current = runtime;
        if (current == null) {
            throw new IllegalStateException(
                    "No UiRuntime installed: start a Backend (e.g. new LwjglBackend()) before using Ui.");
        }
        return current;
    }
}
