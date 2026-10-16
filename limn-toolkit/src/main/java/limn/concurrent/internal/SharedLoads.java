package limn.concurrent.internal;

import limn.concurrent.Ui;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/**
 * Loads shared by key, kept within a budget: a key asked for twice is loaded once, by whoever
 * asked first, and every asker is handed the same future.
 *
 * <p>Not API. It is what {@code Images} and {@code Sounds} share, the two places that turn a path
 * or a resource name into something decoded on the worker pool and handed back on the UI thread.
 *
 * <p>A load that failed is forgotten, so the next ask tries again: a file that was not there may
 * be there now. One that landed is kept for as long as what is kept fits the budget, and past it
 * the one asked for longest ago is let go first, a path before a resource: an application's own
 * resources are few and it comes back to them, where the files of a disk have no end. One that
 * was pinned is kept whatever the budget, and counts nothing toward it, until it was unpinned as
 * many times as it was pinned: two parts of an application that pin one picture each keep it
 * pinned until both let it go.
 *
 * <p>Letting go is not forgetting. What was let go is still known for as long as anybody holds
 * it, and an ask for its key hands the same object back, so a picture on screen is never decoded
 * a second time beside itself.
 *
 * @param <T> what a load produces
 */
public final class SharedLoads<T> {

    /** Where a key comes from, which decides what is let go first. */
    public enum Source {
        /** A path on a disk: let go first. */
        FILE,
        /** A resource of the application's own: let go only when no path is left to let go. */
        RESOURCE
    }

    /**
     * Every instance, so a backend's shutdown can empty them all. Weak, because only the static
     * ones in {@code Images} and {@code Sounds} live for the process; a test's do not.
     */
    private static final java.util.Set<SharedLoads<?>> ALL = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** One key: its load while it runs or is kept, and what it produced for as long as that lives. */
    private final class Entry {
        final Source source;
        /** The load in flight or kept; null once let go. */
        CompletableFuture<T> future;
        /** What the load produced, known past being let go for as long as somebody holds it. */
        WeakReference<T> landed;
        long bytes;
        long used;
        boolean pinned;
        /** How many pinned asks for this key are not unpinned yet; a part follows its load's instead. */
        int pins;

        Entry(Source source) {
            this.source = source;
        }

        boolean kept() {
            return future != null && landed != null;
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final ToLongFunction<T> size;
    private long budget;
    private long held;
    private long clock;

    /**
     * An empty set of loads.
     *
     * @param size   how many bytes one result keeps on the heap
     * @param budget the most bytes of unpinned results kept; see {@link #setBudget}
     */
    public SharedLoads(ToLongFunction<T> size, long budget) {
        this.size = size;
        this.budget = budget;
        ALL.add(this);
    }

    /**
     * The future for {@code key}: the one already in flight or kept, one made of a result that was
     * let go and is still held by somebody, or a new one running {@code loader} on the worker pool.
     *
     * @param key    what identifies the load; two asks with equal keys share one future
     * @param source where the key comes from
     * @param pinned whether the result is kept whatever the budget, from this ask on, until as
     *               many {@link #unpin}s as pinned asks
     * @param loader the work, run at most once per key while its result is known and nothing failed
     * @return the shared future, already running or already complete, completing on the UI thread
     * @throws IllegalStateException if a load has to start and no backend is running
     */
    public CompletableFuture<T> load(String key, Source source, boolean pinned, Supplier<T> loader) {
        CompletableFuture<T> future;
        synchronized (this) {
            Entry entry = entries.get(key);
            if (entry == null) {
                entry = new Entry(source);
                entries.put(key, entry);
            }
            entry.used = ++clock;
            if (pinned && entry.pins++ == 0) {
                pin(entry);
                pinParts(key, true);
            }
            if (entry.future != null) {
                return entry.future;
            }
            T alive = entry.landed == null ? null : entry.landed.get();
            if (alive != null) {
                // Let go and still held by somebody: kept again, the same object.
                entry.future = CompletableFuture.completedFuture(alive);
                if (!entry.pinned) {
                    held += entry.bytes;
                }
                trim();
                return entry.future;
            }
            entry.landed = null;
            future = Ui.async(loader).toCompletableFuture();
            entry.future = future;
        }
        // Outside the lock: a future already complete runs this on the spot.
        future.whenComplete((value, error) -> landed(key, future, value, error));
        return future;
    }

    private synchronized void landed(String key, CompletableFuture<T> future, T value, Throwable error) {
        Entry entry = entries.get(key);
        if (entry == null || entry.future != future) {
            return; // forgotten meanwhile
        }
        if (error != null || value == null) {
            entries.remove(key); // failures are retryable
            return;
        }
        entry.landed = new WeakReference<>(value);
        entry.bytes = Math.max(0, size.applyAsLong(value));
        if (!entry.pinned) {
            held += entry.bytes;
        }
        trim();
    }

    /**
     * Takes back one pinned ask for {@code key}. When none is left, it stops being kept whatever
     * the budget: it counts toward it from now on, and is let go when its turn comes. Nothing
     * happens for a key that is not pinned.
     *
     * @param key the key a load was pinned by
     */
    public synchronized void unpin(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.pins == 0 || --entry.pins > 0) {
            return;
        }
        unpin(entry);
        pinParts(key, false);
        trim();
    }

    private void pin(Entry entry) {
        if (!entry.pinned) {
            entry.pinned = true;
            if (entry.kept()) {
                held -= entry.bytes;
            }
        }
    }

    private void unpin(Entry entry) {
        if (entry.pinned) {
            entry.pinned = false;
            if (entry.kept()) {
                held += entry.bytes;
            }
        }
    }

    // ---- parts: what a load is made of, each kept and let go by itself

    private static String partKey(String key, int index) {
        return key + '#' + index;
    }

    private void pinParts(String key, boolean pinned) {
        String prefix = key + '#';
        for (Map.Entry<String, Entry> each : entries.entrySet()) {
            if (each.getKey().startsWith(prefix)) {
                if (pinned) {
                    pin(each.getValue());
                } else {
                    unpin(each.getValue());
                }
            }
        }
    }

    /**
     * Keeps one part of what {@code key} loaded: a frame of an animated picture. A part is an
     * entry of its own, counted, let go and kept as any other is, from the same source as the
     * load it is part of and pinned while that load is.
     *
     * @param key   the load the part belongs to
     * @param index which part
     * @param value the part
     */
    public synchronized void putPart(String key, int index, T value) {
        Entry whole = entries.get(key);
        String part = partKey(key, index);
        Entry entry = entries.get(part);
        if (entry == null) {
            entry = new Entry(whole == null ? Source.FILE : whole.source);
            entries.put(part, entry);
        } else if (entry.kept() && !entry.pinned) {
            held -= entry.bytes;
        }
        entry.pinned = whole != null && whole.pinned;
        entry.used = ++clock;
        entry.future = CompletableFuture.completedFuture(value);
        entry.landed = new WeakReference<>(value);
        entry.bytes = Math.max(0, size.applyAsLong(value));
        if (!entry.pinned) {
            held += entry.bytes;
        }
        trim();
    }

    /**
     * @param key   the load the part belongs to
     * @param index which part
     * @return the part, if it is kept or was let go and is still held by somebody, and it counts
     *         as asked for now; null if it has to be made again
     */
    public synchronized @org.jspecify.annotations.Nullable T peekPart(String key, int index) {
        Entry entry = entries.get(partKey(key, index));
        if (entry == null) {
            return null;
        }
        if (entry.future != null) {
            entry.used = ++clock;
            return entry.future.getNow(null);
        }
        T alive = entry.landed == null ? null : entry.landed.get();
        if (alive == null) {
            return null;
        }
        entry.used = ++clock;
        entry.future = CompletableFuture.completedFuture(alive);
        if (!entry.pinned) {
            held += entry.bytes;
        }
        trim();
        return alive;
    }

    /**
     * Moves the budget, letting go at once of what no longer fits.
     *
     * @param bytes the most bytes of unpinned results kept; 0 keeps none, and
     *              {@code Long.MAX_VALUE} lets none go
     * @throws IllegalArgumentException if {@code bytes} is negative
     */
    public synchronized void setBudget(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("a budget is at least 0 bytes, got " + bytes);
        }
        budget = bytes;
        trim();
    }

    /** @return the most bytes of unpinned results kept */
    public synchronized long budget() {
        return budget;
    }

    /** @return the bytes of unpinned results kept now */
    public synchronized long heldBytes() {
        return held;
    }

    /** Lets go of the longest unasked while over the budget, paths first, and forgets the dead. */
    private void trim() {
        List<Entry> candidates = null;
        for (Iterator<Entry> all = entries.values().iterator(); all.hasNext(); ) {
            Entry entry = all.next();
            if (entry.future == null && (entry.landed == null || entry.landed.get() == null)) {
                all.remove(); // let go, and nobody holds it any more
            } else if (held > budget && entry.kept() && !entry.pinned) {
                if (candidates == null) {
                    candidates = new ArrayList<>();
                }
                candidates.add(entry);
            }
        }
        if (candidates == null) {
            return;
        }
        candidates.sort((a, b) -> a.source != b.source
                ? Integer.compare(a.source.ordinal(), b.source.ordinal()) : Long.compare(a.used, b.used));
        for (Entry entry : candidates) {
            if (held <= budget) {
                break;
            }
            entry.future = null;
            held -= entry.bytes;
        }
    }

    /**
     * Forgets every load, pinned or not, so the next ask for any key runs its loader again. Any
     * thread.
     */
    public synchronized void clear() {
        entries.clear();
        held = 0;
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
