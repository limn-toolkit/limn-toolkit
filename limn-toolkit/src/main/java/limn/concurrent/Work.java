package limn.concurrent;

import java.lang.System.Logger.Level;
import java.lang.ref.Cleaner;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import limn.backend.CrashPhase;
import limn.backend.Crashes;

/**
 * A description of background work, built up and then started once. The body
 * runs on the worker pool; {@link #onSuccess}, {@link #onFailure} and
 * {@link #onProgress} run on the UI thread, so those handlers may touch widgets
 * directly. {@link #onDiscarded} is the one exception and runs on the worker
 * pool (it disposes of a value the UI never sees, and disposing is allowed to
 * block), so a widget touched from there throws.
 *
 * <pre>{@code
 * job = Ui.work(progress -> repository.load(id))
 *         .onProgress(bar::setValue)
 *         .onSuccess(list::setItems)
 *         .onFailure(error -> status.setText(error.getMessage()))
 *         .deliverIf(view::isShowing)
 *         .start();
 * }</pre>
 *
 * <p>This is what {@code Ui.async} is not: the returned {@link Job} can be
 * cancelled, and a cancelled job delivers nothing, so a view that starts one
 * request per keystroke and cancels the previous one shows the answer to the
 * question last asked, rather than whichever answer arrived last.
 *
 * <p>Each setter replaces the handler it names rather than adding to it, and
 * {@code null} clears it. Register everything before {@link #start()}: the
 * handlers are read once, when the job starts, and a setter called afterwards
 * has no effect on the job already running.
 *
 * <p>One job runs <b>at most one</b> terminal callback: {@link #onSuccess} or
 * {@link #onFailure}, never both and never twice. Whatever progress deliveries
 * survived coalescing precede it, and a cancelled job runs none of them.
 *
 * @param <T> the type the body produces
 */
public final class Work<T> {

    /**
     * The background body. Runs on the worker pool, never on the UI thread,
     * and must not touch widget or scene state; it hands its result back
     * through {@link Work#onSuccess}, which does run on the UI thread.
     *
     * @param <T> the type the body produces
     */
    @FunctionalInterface
    public interface Body<T> {
        /**
         * @param progress the job's own handle: cancellation and reporting
         * @return the value handed to the success callback; may be {@code null}
         * @throws Exception any failure, checked or not; it is routed to the
         *                   failure callback on the UI thread rather than
         *                   killing the worker thread
         */
        T run(Progress progress) throws Exception;
    }

    private static final System.Logger LOG = System.getLogger(Work.class.getName());

    /**
     * Watches for descriptions collected without ever being started. Its thread is a daemon and
     * is created with this class, i.e. on the first {@code Ui.work} of the process.
     */
    private static final Cleaner CLEANER = Cleaner.create();

    /**
     * How many unstarted descriptions {@link StartState} has reported. Package-private because
     * the warning is the guarantee and {@code System.Logger} offers nowhere to observe one from
     * a test; without a counter the diagnostic could rot and no run would notice.
     */
    static final AtomicLong neverStartedCount = new AtomicLong();

    private final UiRuntime runtime;
    private final Body<T> body;
    private final StartState startState = new StartState(
            // Only when someone is listening: this is per description, and a description is built
            // per keystroke by a search-as-you-type. A Throwable is cheap and is not free.
            LOG.isLoggable(Level.DEBUG) ? new Throwable("this Work was built here") : null);
    private final Cleaner.Cleanable cleanable = CLEANER.register(this, startState);

    private Consumer<T> onSuccess;
    private Consumer<Throwable> onFailure;
    private DoubleConsumer onProgress;
    private Consumer<T> onDiscarded;
    private BooleanSupplier alive;

    Work(UiRuntime runtime, Body<T> body) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.body = Objects.requireNonNull(body, "body");
    }

    /**
     * Whether {@link #start()} has been called, and (because the cleaner can still read it after
     * the {@link Work} is gone) the report that it never was.
     *
     * <p><b>It must not reference its {@code Work}.</b> A cleaning action that can reach the
     * object it guards keeps that object reachable, the reference is never enqueued, and the
     * warning this exists for is the one thing that could never fire.
     */
    private static final class StartState implements Runnable {

        private final AtomicBoolean started = new AtomicBoolean();
        private final Throwable site;

        StartState(Throwable site) {
            this.site = site;
        }

        @Override
        public void run() {
            if (started.get()) {
                return;
            }
            neverStartedCount.incrementAndGet();
            LOG.log(Level.WARNING, () -> "A Work was built, configured and then dropped without"
                    + " start(), so its body never ran and none of its callbacks will: the chain"
                    + " that built it is missing its final .start(). Log " + Work.class.getName()
                    + " at DEBUG to capture where it was built.", site);
        }
    }

    /**
     * Receives the body's return value on the UI thread. Not called at all if
     * the job was cancelled, if {@link #deliverIf} answered {@code false}, or
     * if the body threw.
     *
     * @param handler the receiver, or {@code null} to drop the result
     * @return this, for chaining
     */
    public Work<T> onSuccess(Consumer<T> handler) {
        this.onSuccess = handler;
        return this;
    }

    /**
     * Receives whatever the body threw, on the UI thread, unwrapped: the
     * throwable the body threw, not a wrapper around it.
     *
     * <p>With no handler registered, a failure is logged at ERROR and reported
     * to the process crash handler as a task crash rather than being swallowed.
     * A job that was cancelled reports nothing at all, including its failure,
     * on the grounds that a body usually fails <em>because</em> it was
     * cancelled; that case is logged at DEBUG.
     *
     * @param handler the receiver, or {@code null} for the log-and-report
     *                default
     * @return this, for chaining
     */
    public Work<T> onFailure(Consumer<Throwable> handler) {
        this.onFailure = handler;
        return this;
    }

    /**
     * Receives values passed to {@link Progress#report}, on the UI thread,
     * clamped to 0..1 and coalesced; see {@link Progress#report} for what
     * that skips and what it guarantees.
     *
     * <p>A delivery is a <em>level</em>, not an event. Most reported values are
     * never delivered, and the body has already moved past the one that is, so
     * a handler must use the number it is handed rather than count deliveries,
     * accumulate them, or treat one as "a step finished".
     *
     * @param handler the receiver, or {@code null} to ignore reports
     * @return this, for chaining
     */
    public Work<T> onProgress(DoubleConsumer handler) {
        this.onProgress = handler;
        return this;
    }

    /**
     * Disposes of a result that will never be delivered, the leak guard for a
     * body that returns something holding an open resource.
     *
     * <p>Called when the body produced a value and the job was cancelled, or
     * {@link #deliverIf} answered {@code false}. It runs on the worker pool,
     * not on the UI thread, because it is cleanup of a value the UI never sees
     * and it is allowed to block; the one exception is a pool already shut
     * down, where the disposal runs on the calling thread rather than leaking.
     * Without a handler the value is dropped silently, and a handler that
     * throws is logged and does not propagate.
     *
     * <p>It is not called when the body throws, because there is no value. It
     * <b>is</b> called for a value the body produced and the job then withdrew,
     * whether or not an {@link #onSuccess} was ever registered, which is what
     * lets a facade attach the disposer on its caller's behalf and have it hold
     * for every caller. What is not disposed is a value that was delivered
     * normally with no success handler to take it: nothing was withdrawn there,
     * and a body whose result nobody wants should not be returning a resource.
     *
     * @param dispose the disposer, or {@code null} to drop silently
     * @return this, for chaining
     */
    public Work<T> onDiscarded(Consumer<T> dispose) {
        this.onDiscarded = dispose;
        return this;
    }

    /**
     * Guards every delivery: asked on the UI thread immediately before each
     * success, failure and progress callback, and {@code false} drops that
     * delivery exactly as cancellation would; a dropped result goes to
     * {@link #onDiscarded}.
     *
     * <p>This is how "the requester has gone away" is expressed without the
     * job knowing what a requester is: pass a predicate over whatever owns the
     * request, such as a widget's attached-to-a-scene state. It is asked once
     * per delivery, so it must be cheap and must not block. A predicate that
     * throws is logged and read as {@code false}, so a broken guard drops and
     * disposes rather than delivering into a half-torn-down view.
     *
     * <p>A {@code true} covers that one delivery and nothing after it. Work a
     * handler posts for a later frame is unguarded and has to ask again.
     *
     * @param alive asked on the UI thread, or {@code null} to always deliver
     * @return this, for chaining
     */
    public Work<T> deliverIf(BooleanSupplier alive) {
        this.alive = alive;
        return this;
    }

    /**
     * Submits the body to the worker pool and returns its handle. Safe to call
     * from any thread.
     *
     * <p>Jobs are not ordered against each other: two started in sequence
     * complete in whichever order the pool and the work give them, so a view
     * that must show the answer to the request last <em>asked</em> holds the
     * earlier {@link Job} and cancels it rather than relying on arrival order.
     *
     * <p>Forgetting this call is the one silent failure the builder has, so a description that is
     * garbage-collected without it logs one WARNING naming the omission; at DEBUG the message
     * carries the stack that built it.
     *
     * @return the handle, already submitted; it may have run by the time this
     *         returns
     * @throws IllegalStateException if this description was already started; a
     *                               second run is a second description
     */
    public Job start() {
        if (!startState.started.compareAndSet(false, true)) {
            throw new IllegalStateException(
                    "This Work has already been started: build a new one for a second run.");
        }
        // Runs the action, which now finds the flag set and returns, and unregisters with it: a
        // started description must not stay on the cleaner's list for the life of the process.
        cleanable.clean();
        Run<T> run = new Run<>(runtime, body, onSuccess, onFailure, onProgress, onDiscarded, alive);
        run.submit();
        return run;
    }

    /** One started job: the body's runnable, the {@link Progress} it sees, and the {@link Job} the caller holds. */
    private static final class Run<T> implements Job, Progress, Runnable {

        private final UiRuntime runtime;
        private final Body<T> body;
        private final Consumer<T> onSuccess;
        private final Consumer<Throwable> onFailure;
        private final DoubleConsumer onProgress;
        private final Consumer<T> onDiscarded;
        private final BooleanSupplier alive;

        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicLong latestProgress = new AtomicLong(Double.doubleToRawLongBits(Double.NaN));
        private final AtomicBoolean progressQueued = new AtomicBoolean();

        /** Allocated once so {@link #report} costs nothing per call; posted at most once at a time. */
        private final Runnable progressDelivery = this::deliverProgress;

        /** UI thread only: what the progress handler last saw, so a redundant delivery says nothing twice. */
        private long deliveredProgress = Double.doubleToRawLongBits(Double.NaN);

        private volatile boolean done;

        Run(UiRuntime runtime,
            Body<T> body,
            Consumer<T> onSuccess,
            Consumer<Throwable> onFailure,
            DoubleConsumer onProgress,
            Consumer<T> onDiscarded,
            BooleanSupplier alive) {
            this.runtime = runtime;
            this.body = body;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
            this.onProgress = onProgress;
            this.onDiscarded = onDiscarded;
            this.alive = alive;
        }

        void submit() {
            runtime.workerPool().execute(this);
        }

        // ------------------------------------------------------------- Job

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return done;
        }

        // -------------------------------------------------------- Progress

        @Override
        public void report(double fraction) {
            if (onProgress == null) {
                // Nobody is listening: a body reporting per loop iteration would otherwise
                // queue a runnable a frame for the whole run, for nothing.
                return;
            }
            double clamped = Double.isNaN(fraction) ? 0 : Math.min(1, Math.max(0, fraction));
            latestProgress.set(Double.doubleToRawLongBits(clamped));
            if (progressQueued.compareAndSet(false, true)) {
                runtime.post(progressDelivery);
            }
        }

        // ---------------------------------------------------- worker thread

        @Override
        public void run() {
            if (cancelled.get()) {
                done = true;
                return;
            }
            T value = null;
            Throwable failure = null;
            try {
                value = body.run(this);
            } catch (Throwable error) {
                failure = error;
            }
            done = true;
            if (failure != null) {
                Throwable thrown = failure;
                runtime.post(() -> deliverFailure(thrown));
                return;
            }
            T result = value;
            if (cancelled.get()) {
                // Already known undeliverable: dispose here rather than round-tripping
                // through the UI thread only to be handed straight back to this pool.
                dispose(result);
                return;
            }
            runtime.post(() -> deliverSuccess(result));
        }

        // -------------------------------------------------------- UI thread

        private void deliverSuccess(T value) {
            if (cancelled.get() || !isAlive()) {
                discard(value);
                return;
            }
            if (onSuccess != null) {
                onSuccess.accept(value);
            }
        }

        private void deliverFailure(Throwable error) {
            if (cancelled.get() || !isAlive()) {
                LOG.log(Level.DEBUG, "background work failed after it was withdrawn; not delivered", error);
                return;
            }
            if (onFailure != null) {
                onFailure.accept(error);
                return;
            }
            LOG.log(Level.ERROR, "background work failed and no onFailure handler was registered", error);
            Crashes.report(CrashPhase.TASK, error);
        }

        private void deliverProgress() {
            // Clear before reading: a report racing this one then finds the slot free
            // and posts again. Reading first and clearing afterwards would let the
            // body's final value be written into a slot nobody comes back for.
            progressQueued.set(false);
            long bits = latestProgress.get();
            if (cancelled.get() || !isAlive()) {
                return;
            }
            if (bits == deliveredProgress) {
                return;
            }
            deliveredProgress = bits;
            onProgress.accept(Double.longBitsToDouble(bits));
        }

        private boolean isAlive() {
            if (alive == null) {
                return true;
            }
            try {
                return alive.getAsBoolean();
            } catch (Throwable error) {
                LOG.log(Level.ERROR, "deliverIf predicate threw; treating the requester as gone", error);
                return false;
            }
        }

        /** UI thread: a delivery was dropped, so the value goes back to the pool to be closed. */
        private void discard(T value) {
            if (onDiscarded == null) {
                return;
            }
            try {
                runtime.workerPool().execute(() -> dispose(value));
            } catch (RejectedExecutionException poolGone) {
                dispose(value);
            }
        }

        private void dispose(T value) {
            if (onDiscarded == null) {
                return;
            }
            try {
                onDiscarded.accept(value);
            } catch (Throwable error) {
                LOG.log(Level.ERROR, "onDiscarded threw while disposing an undelivered result", error);
            }
        }
    }
}
