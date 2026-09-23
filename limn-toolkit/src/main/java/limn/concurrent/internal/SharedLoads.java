package limn.concurrent.internal;

import limn.concurrent.Ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Loads keyed by name, each made at most once at a time and shared by everyone who asks for it
 * while it is in flight or once it has landed.
 *
 * <p>This is what {@code Images.loadShared} and {@code Sounds.loadShared} are: two callers
 * asking for the same file get the same future, a load that failed is forgotten so the next
 * ask retries it, and {@link #clear()} forgets the successful ones too, for the moment the files
 * changed on disk. The two had the same forty lines with the element type swapped; this is
 * those lines once, with the type as a parameter.
 *
 * <p>The load runs on the {@link Ui} worker pool and the future completes on the UI thread,
 * which is {@link Ui#async}'s contract and needs a running backend. Any thread may ask; the
 * map is concurrent, and {@code computeIfAbsent} is what makes "at most once at a time" hold
 * without a lock of this class's own.
 *
 * @param <T> what a load produces
 */
public final class SharedLoads<T> {

    private final ConcurrentHashMap<String, CompletableFuture<T>> pending = new ConcurrentHashMap<>();

    /**
     * The future for {@code key}: the one already in flight or landed, or a new one running
     * {@code loader} on the worker pool.
     *
     * @param key    what identifies the load; two asks with equal keys share one future
     * @param loader the work, run at most once per key while nothing has failed
     * @return the shared future, already running, completing on the UI thread
     * @throws IllegalStateException if no backend is running
     */
    public CompletableFuture<T> load(String key, Supplier<T> loader) {
        return pending.computeIfAbsent(key, k -> {
            CompletableFuture<T> future = Ui.async(loader).toCompletableFuture();
            future.whenComplete((value, error) -> {
                if (error != null) {
                    pending.remove(k, future); // failures are retryable
                }
            });
            return future;
        });
    }

    /** Forgets every load, so the next ask for any key runs its loader again. Any thread. */
    public void clear() {
        pending.clear();
    }
}
