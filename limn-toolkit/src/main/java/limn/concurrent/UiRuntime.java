package limn.concurrent;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The concurrency runtime behind {@link Ui}: a single UI thread fed by a
 * thread-safe (MPSC) task queue, plus a worker pool for the application's
 * heavy background work.
 *
 * <p>The owning backend binds the UI thread ({@link #bindToCurrentThread()}),
 * drains the queue once per frame ({@link #drain()}) and sleeps in the native
 * event wait between frames, never busy-waiting. Posting from any other
 * thread triggers the {@link Waker}, which the LWJGL backend maps to
 * {@code glfwPostEmptyEvent()} so the sleeping loop wakes up.
 *
 * <p>Drain semantics: {@link #drain()} runs only the tasks that were queued
 * when it started (a snapshot). Tasks posted while draining run on the next
 * frame, which keeps a self-reposting task from live-locking the loop. The
 * loop must therefore consult {@link #nanosUntilNextDeadline()} before
 * sleeping: {@code 0} means "don't sleep, there is pending work".
 *
 * <p>Every task is timed; tasks exceeding the slow-task budget (default 8 ms)
 * are logged as warnings, making "my click handler does blocking I/O"
 * contract violations visible during development.
 */
public final class UiRuntime implements AutoCloseable {

    /** Wakes the native event loop when work is posted from another thread. */
    @FunctionalInterface
    public interface Waker {
        void wake();
    }

    private static final System.Logger LOG = System.getLogger(UiRuntime.class.getName());

    private record DelayedTask(long deadlineNanos, long sequence, Runnable action)
            implements Comparable<DelayedTask> {
        @Override
        public int compareTo(DelayedTask other) {
            int byDeadline = Long.compare(deadlineNanos, other.deadlineNanos);
            return byDeadline != 0 ? byDeadline : Long.compare(sequence, other.sequence);
        }
    }

    private final ConcurrentLinkedQueue<Runnable> immediate = new ConcurrentLinkedQueue<>();

    /**
     * How many tasks {@link #immediate} holds. Kept beside the queue because
     * {@code ConcurrentLinkedQueue.size()} walks every node (its own Javadoc says so) and
     * {@link #drain} needs that number once per iteration of the event loop, which turned a burst
     * of queued work into a full traversal to count followed by another to run.
     *
     * <p>Maintained in {@link #enqueue} and {@link #drain} and nowhere else: an add that bypassed
     * the one and a poll that bypassed the other are how a counter beside a queue starts lying.
     */
    private final java.util.concurrent.atomic.AtomicInteger pending =
            new java.util.concurrent.atomic.AtomicInteger();
    private final PriorityQueue<DelayedTask> delayed = new PriorityQueue<>();
    private final Object delayedLock = new Object();
    private final AtomicLong delayedSequence = new AtomicLong();
    private final LongSupplier nanoClock;
    private final Waker waker;
    private final ExecutorService workers;
    private final boolean ownsWorkers;
    private final Executor uiExecutor = this::post;
    private volatile Thread uiThread;
    private volatile long slowTaskBudgetNanos = TimeUnit.MILLISECONDS.toNanos(8);

    /**
     * Creates a runtime with the real clock and an owned daemon worker pool.
     *
     * @param waker invoked (from arbitrary threads) whenever work is posted
     *              from outside the UI thread
     */
    public static UiRuntime create(Waker waker) {
        int poolSize = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "limn-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new UiRuntime(System::nanoTime, waker, Executors.newFixedThreadPool(poolSize, factory), true);
    }

    /**
     * Creates a runtime with an injectable clock and worker pool; the pool is
     * not shut down by {@link #close()}. Intended for tests.
     */
    public UiRuntime(LongSupplier nanoClock, Waker waker, ExecutorService workers) {
        this(nanoClock, waker, workers, false);
    }

    private UiRuntime(LongSupplier nanoClock, Waker waker, ExecutorService workers, boolean ownsWorkers) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.waker = Objects.requireNonNull(waker, "waker");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.ownsWorkers = ownsWorkers;
    }

    /** Declares the calling thread as the UI thread. Called once by the backend. */
    public void bindToCurrentThread() {
        this.uiThread = Thread.currentThread();
    }

    /** @return {@code true} iff called on the bound UI thread */
    public boolean isUiThread() {
        return Thread.currentThread() == uiThread;
    }

    /**
     * Enforces thread confinement: throws unless called on the UI thread.
     *
     * @throws IllegalStateException if called from any other thread, or before
     *                               a UI thread was bound
     */
    public void checkUiThread() {
        Thread bound = uiThread;
        if (Thread.currentThread() != bound) {
            String boundName = bound == null ? "<not bound yet>" : "'" + bound.getName() + "'";
            throw new IllegalStateException(
                    "Not on the Limn UI thread: widget and scene state may only be touched from the UI thread. "
                            + "Current thread: '" + Thread.currentThread().getName() + "', UI thread: " + boundName
                            + ". Hop over with Ui.post(...) or Ui.async(work).thenAccept(uiUpdate).");
        }
    }

    /**
     * Enqueues {@code action} to run on the UI thread on the next frame.
     * Safe to call from any thread; wakes the native loop when needed.
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
    public void post(Runnable action) {
        Objects.requireNonNull(action, "action");
        enqueue(action);
        if (!isUiThread()) {
            waker.wake();
        }
    }

    /** The one way a task joins {@link #immediate}, so {@link #pending} cannot drift from it. */
    private void enqueue(Runnable action) {
        immediate.add(action);
        pending.incrementAndGet();
    }

    /**
     * Enqueues {@code action} to run on the UI thread once {@code delayMillis}
     * have elapsed. Safe to call from any thread.
     *
     * <p><b>A task that mutates widget or scene state is responsible for
     * invalidating what it touched</b>: see {@link #post}. Firing buys no
     * frame of its own, which is what makes a timer the cheap way to watch
     * something that rarely changes: a poll that re-reads a value and finds it
     * unchanged costs a wake-up and nothing else, where a
     * {@link limn.scene.Scene#addTicker ticker} asks for a frame every frame it
     * stays registered.
     */
    public void postDelayed(Runnable action, long delayMillis) {
        Objects.requireNonNull(action, "action");
        long now = nanoClock.getAsLong();
        long deadline = now + TimeUnit.MILLISECONDS.toNanos(Math.max(0, delayMillis));
        if (deadline < now) {
            // A delay of a few centuries (Long.MAX_VALUE is the idiomatic way to write "never")
            // wraps the sum negative, and the queue orders by the deadline itself. The task would
            // then sit at the head with a deadline no clock reaches, and the drain, which stops at
            // the first task that is not due, would never look at the real deadlines behind it.
            deadline = Long.MAX_VALUE;
        }
        synchronized (delayedLock) {
            delayed.add(new DelayedTask(deadline, delayedSequence.getAndIncrement(), action));
        }
        if (!isUiThread()) {
            waker.wake();
        }
    }

    /**
     * Runs {@code work} on the worker pool and completes the returned future
     * on the UI thread. The future's default async executor is the UI thread
     * too, so {@code thenAccept}/{@code whenComplete} callbacks land on the UI
     * thread, the canonical "click → fetch → update label" path.
     *
     * <p>Precisely: dependents registered before completion (the normal case,
     * chaining right after this call) and all {@code *Async} dependents run on
     * the UI thread. A non-async dependent attached from a background thread
     * <em>after</em> the future already completed runs inline on the attaching
     * thread, and {@code cancel()} completes the future on the cancelling
     * thread; in those corners, use {@code thenAcceptAsync(fn)} (the default
     * executor is already the UI thread) or attach from the UI thread.
     *
     * <p><b>A dependent that mutates widget or scene state is responsible for
     * invalidating what it touched</b>: see {@link #post}. Completing on the
     * UI thread buys no frame; {@code label.setText(data)} asks for the frame
     * that shows it, a field written directly does not.
     */
    public <T> CompletableFuture<T> async(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        UiFuture<T> future = new UiFuture<>(this);
        workers.execute(() -> {
            try {
                T value = work.get();
                post(() -> future.complete(value));
            } catch (Throwable error) {
                post(() -> future.completeExceptionally(error));
            }
        });
        return future;
    }

    /**
     * Describes background work with a lifecycle: cancellable, able to report
     * progress, and able to decline delivery when whoever asked has gone away.
     * Nothing runs until {@link Work#start()}.
     *
     * <p>Reach for this over {@link #async} whenever the request can be
     * superseded: a cancelled job delivers nothing, so a view that starts one
     * per keystroke shows the answer to the question last <em>asked</em>,
     * where competing {@code async} calls show whichever answer happened to
     * finish last.
     */
    public <T> Work<T> work(Work.Body<T> body) {
        return new Work<>(this, body);
    }

    /** @return an {@link Executor} that posts to the UI thread ({@link #post}) */
    public Executor uiExecutor() {
        return uiExecutor;
    }

    /** @return the worker pool used by {@link #async} */
    public ExecutorService workerPool() {
        return workers;
    }

    /**
     * Runs due tasks on the UI thread: first promotes expired delayed tasks,
     * then runs the tasks queued at the moment this call started (snapshot;
     * see class docs). A task that throws is logged and does not abort the
     * drain, and a task over the slow-task budget is logged as a warning.
     *
     * <p>Draining schedules no repaint. Each task invalidates what it mutated
     * (see {@link #post}); one that threw did not finish doing so, which is
     * what {@link #drain(Runnable)} exists for.
     *
     * @return the number of tasks executed
     */
    public int drain() {
        return drain(NO_CRASH_HOOK);
    }

    private static final Runnable NO_CRASH_HOOK = () -> {
    };

    /**
     * {@link #drain()}, plus a hook for the state a crashed task left behind.
     *
     * @param onTaskCrash run on the UI thread, inside the drain, once per task
     *                    that threw, after the crash is logged and reported.
     *                    A task that throws part-way has applied part of its
     *                    mutation and invalidated none of it, and how much is
     *                    unknowable from here, so the caller that owns the
     *                    surfaces settles them: the LWJGL backend repaints
     *                    every window. Called on no other path; a task that
     *                    returns normally is trusted to have invalidated its
     *                    own work. Runs inside the drain, so it must not throw
     *                    (an exception from it aborts the remaining tasks).
     * @return the number of tasks executed, crashed ones included
     */
    public int drain(Runnable onTaskCrash) {
        checkUiThread();
        Objects.requireNonNull(onTaskCrash, "onTaskCrash");
        long now = nanoClock.getAsLong();
        synchronized (delayedLock) {
            while (!delayed.isEmpty() && delayed.peek().deadlineNanos() - now <= 0) {
                enqueue(delayed.poll().action());
            }
        }
        // Only what was queued when this drain began: a task posted BY a task runs on the next
        // frame, which is what stops one runaway poster from holding the loop forever.
        int snapshot = pending.get();
        int ran = 0;
        for (int i = 0; i < snapshot; i++) {
            Runnable task = immediate.poll();
            if (task == null) {
                break;
            }
            pending.decrementAndGet();
            runInstrumented(task, onTaskCrash);
            ran++;
        }
        return ran;
    }

    private void runInstrumented(Runnable task, Runnable onTaskCrash) {
        long start = nanoClock.getAsLong();
        try {
            task.run();
        } catch (Throwable error) {
            LOG.log(Level.ERROR, "UI task threw; the UI loop keeps running", error);
            limn.backend.Crashes.report(limn.backend.CrashPhase.TASK, error);
            onTaskCrash.run();
        }
        long elapsed = nanoClock.getAsLong() - start;
        if (elapsed > slowTaskBudgetNanos) {
            LOG.log(Level.WARNING,
                    "UI task {0} took {1} ms (budget: {2} ms); move heavy work to Ui.async(...)",
                    task.getClass().getName(),
                    TimeUnit.NANOSECONDS.toMillis(elapsed),
                    TimeUnit.NANOSECONDS.toMillis(slowTaskBudgetNanos));
        }
    }

    /** @return {@code true} if immediate tasks are queued right now */
    public boolean hasPendingTasks() {
        return !immediate.isEmpty();
    }

    /**
     * How long the native loop may sleep: {@code 0} if immediate work is
     * pending, {@code -1} if it may sleep indefinitely, otherwise the
     * nanoseconds until the earliest delayed task is due.
     */
    public long nanosUntilNextDeadline() {
        if (!immediate.isEmpty()) {
            return 0;
        }
        synchronized (delayedLock) {
            DelayedTask head = delayed.peek();
            if (head == null) {
                return -1;
            }
            return Math.max(0, head.deadlineNanos() - nanoClock.getAsLong());
        }
    }

    /** Budget above which a drained task is reported as slow. Default: 8 ms. */
    public void setSlowTaskBudgetMillis(long millis) {
        this.slowTaskBudgetNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, millis));
    }

    /** Shuts down the worker pool if this runtime created it. */
    @Override
    public void close() {
        if (!ownsWorkers) {
            return;
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
