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

    /**
     * Every instance, so a backend's shutdown can empty them all. Weak, because only the static
     * ones in {@code Images} and {@code Sounds} live for the process; a test's do not.
     */
    private static final java.util.Set<SharedLoads<?>> ALL = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    private final ConcurrentHashMap<String, CompletableFuture<T>> pending = new ConcurrentHashMap<>();

    /** An empty set of loads. */
    public SharedLoads() {
        ALL.add(this);
    }

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

    /**
     * Forgets the loads of every instance; called when the runtime is uninstalled. Each future
     * completes on the UI thread of the runtime it was started under, so one still in flight would
     * never complete for a backend started after it, and every ask for its key would wait forever.
     * The landed ones go too: a new backend is a new start, and a load costs one more read.
     */
    public static void forgetAll() {
        synchronized (ALL) {
            for (SharedLoads<?> loads : ALL) {
                loads.clear();
            }
        }
    }
}
