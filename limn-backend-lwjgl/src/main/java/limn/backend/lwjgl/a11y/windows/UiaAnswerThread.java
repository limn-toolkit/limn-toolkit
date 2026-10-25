package limn.backend.lwjgl.a11y.windows;

import limn.backend.lwjgl.internal.NativeLibraries;
import limn.concurrent.internal.Threads;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Where a window's provider is handed to UI Automation when the window's own thread may not hand
 * it over.
 *
 * <p>A client in another process asks a window for its provider with {@code SendMessage}, so
 * {@code WM_GETOBJECT} is input-synchronous: the client's thread is blocked until the window
 * procedure returns, and COM refuses any call that would leave a single-threaded apartment while
 * it is ({@code RPC_E_CANTCALLOUT_ININPUTSYNCCALL}). The user-interface thread is such an
 * apartment as soon as OLE is initialized on it, which the system's drag and drop does for every
 * window.
 *
 * <p><b>Measured on Windows 11 ARM64 on 2026-10-22, with a client that walks every top-level
 * window of the desktop.</b> Under an ARM64 JDK, {@code UiaReturnRawElementProvider} called on
 * that thread makes such a call before it asks the provider anything, the refusal is raised as an
 * exception, and the process dies: five runs of five, a 32-bit client as well as a native one. The
 * system would catch that exception itself; an ARM64 Java VM that carries OpenJDK's change
 * JDK-8348862 ends the process at its first raise instead, from a vectored handler that runs before
 * any handler on the stack (measured with a native program for the same refusal in a system dialog,
 * see {@code UiaClientWarmUp}; this one was not re-run natively). The same call made from a thread of the multithreaded apartment answered in three
 * runs of three and the client read the whole tree; so did a user-interface thread that had
 * joined the multithreaded apartment itself (two of two), which is how the apartment was told
 * from everything else. An x64 JDK on the same machine never failed (three of three), which is
 * why no earlier run saw it. A system file dialog on a thread of its own in the same ARM64 process
 * read whole too, but only because the client had read this window first: read before anything
 * else of the process, the dialog's own answer died the same way, which {@code UiaClientWarmUp}
 * answers for the system's dialogs.
 *
 * <p>So when both hold, the call is made here, on a daemon thread of the multithreaded
 * apartment, while the window's thread waits. The provider minds no thread: UI Automation calls
 * its members from threads of its own already. The wait does not pump, since nothing in that one
 * call sends to the window, and it is bounded anyway: a wait that cannot end is a frozen window,
 * and a window that answers nothing falls back to its default provider.
 *
 * <p><b>A call that outlives the bound</b> goes on, since an interrupt does not reach a thread
 * inside the platform, and what it answers when it returns is a key made for a client that has
 * been told no already. Nothing can be done with that key but say so ({@link #landedLate}). It
 * cannot be returned, since the message was answered; it may not be kept for the next ask, since
 * {@code LresultFromObject}'s documentation says a key returned again hands that client an
 * error; and no call releases it: the one that consumes a key, {@code ObjectFromLresult}, is
 * documented as Active Accessibility's internal function that neither a client nor a server is to
 * call, and the layout of UI Automation's own key is not documented at all. What the key holds of
 * the provider is let
 * go when the provider is disconnected: the bridge disconnects its root at every rebind and
 * detach, and {@code UiaDisconnectProvider} is documented to leave nothing of a provider held,
 * so that the code behind it may be unloaded once it returns. And a call the window's thread
 * stopped waiting for before it began is never made, so no key is made for nobody that way.
 *
 * <p><b>A provider call that waits on the window's thread</b> waits out the bound, since that
 * thread waits here without pumping. Two waits could: a message sent to the window, which no run
 * on the guest saw this call make, and a guard the window's thread holds. It holds one only
 * inside a bridge's whole-registry empty, whose withdraw and disconnects pump sent messages and
 * so can let a {@code WM_GETOBJECT} in. The bridge answers its own window's with nothing there,
 * as the platform's rule for {@code UiaDisconnectProvider} asks of a provider being disconnected
 * ({@code UiaBridge#answerGetObject}); and a hand-over into another window's bridge that this
 * call reaches from here while that bridge empties does not wait for its guard but answers no
 * element ({@link #isAnswerThread}).
 */
final class UiaAnswerThread {

    private UiaAnswerThread() {
    }

    private static final System.Logger LOG = System.getLogger(UiaAnswerThread.class.getName());

    /** {@code ISMEX_SEND}: the message was sent by another thread, which is blocked on it. */
    static final int ISMEX_SEND = 0x1;

    /** {@code ISMEX_REPLIED}: that thread was already answered with {@code ReplyMessage}. */
    static final int ISMEX_REPLIED = 0x8;

    /** {@code APTTYPE_STA}: a single-threaded apartment. */
    static final int APTTYPE_STA = 0;

    /** {@code APTTYPE_MAINSTA}: the process's first single-threaded apartment, OLE's own. */
    static final int APTTYPE_MAINSTA = 3;

    /** What {@link #apartmentType()} answers for a thread COM was never initialized on. */
    static final int NO_APARTMENT = -1;

    /** {@code COINIT_MULTITHREADED}. */
    private static final int COINIT_MULTITHREADED = 0x0;

    /**
     * How long the window's thread waits. On the guest the call took under a millisecond once the
     * root element existed and 50 to 170&nbsp;ms the first time; the bound is for a call that never
     * returns, not for a slow one.
     */
    static final long BOUND_MILLIS = 2_000;

    private static final SharedLibrary USER32 =
            NativeLibraries.optional(UiaAnswerThread.class, "limn.backend.lwjgl.a11y.windows", "user32");
    private static final SharedLibrary OLE32 =
            NativeLibraries.optional(UiaAnswerThread.class, "limn.backend.lwjgl.a11y.windows", "ole32");
    private static final long IN_SEND_MESSAGE_EX = NativeLibraries.address(USER32, "InSendMessageEx");
    private static final long CO_GET_APARTMENT_TYPE = NativeLibraries.address(OLE32, "CoGetApartmentType");
    private static final long CO_INITIALIZE_EX = NativeLibraries.address(OLE32, "CoInitializeEx");

    /** Started the first time a window needs it, so a process nobody reads starts none. */
    private static final class Pool {
        private static final ThreadFactory DAEMONS = Threads.daemonFactory("limn-a11y-uia-answer");

        /**
         * Cached and not single: a call that outlived its bound keeps its thread, and the next
         * window's question must not queue behind it.
         */
        static final ExecutorService ANSWERS = Executors.newCachedThreadPool(
                task -> DAEMONS.newThread(() -> {
                    joinTheMultithreadedApartment();
                    ANSWERING.set(Boolean.TRUE);
                    task.run();
                }));
    }

    /** Set on each answer thread for its whole life; unset everywhere else. */
    private static final ThreadLocal<Boolean> ANSWERING = new ThreadLocal<>();

    /**
     * Whether the calling thread is an answer thread: one whose call a window's thread may be
     * waiting on, unpumped, while that thread holds a bridge's guard (see the class's note).
     *
     * @return true on a thread of this class's pool
     */
    static boolean isAnswerThread() {
        return ANSWERING.get() != null;
    }

    /**
     * Makes the call here or on the answer thread, whichever this thread's state allows.
     *
     * @param call {@code UiaReturnRawElementProvider} with a provider
     * @return what it answered, or {@code 0} when it could not be had in time
     */
    static long answer(LongSupplier call) {
        int sent = inSendFlags();
        int apartment = apartmentType();
        if (!mustHandOver(sent, apartment)) {
            return call.getAsLong();
        }
        if (UiaTrace.on()) {
            UiaTrace.note("handing the provider over from limn-a11y-uia-answer: this thread is a"
                    + " single-threaded apartment (type " + apartment + ") inside another thread's"
                    + " SendMessage (flags 0x" + Integer.toHexString(sent) + ')');
        }
        return away(call, BOUND_MILLIS);
    }

    /**
     * Whether a call that may leave the apartment has to be made elsewhere.
     *
     * @param inSendFlags what {@code InSendMessageEx(NULL)} answered
     * @param apartment   what {@link #apartmentType()} answered
     * @return true for a single-threaded apartment inside a message another thread is still
     *         blocked on
     */
    static boolean mustHandOver(int inSendFlags, int apartment) {
        boolean blockedSender = (inSendFlags & ISMEX_SEND) != 0 && (inSendFlags & ISMEX_REPLIED) == 0;
        return blockedSender && (apartment == APTTYPE_STA || apartment == APTTYPE_MAINSTA);
    }

    /**
     * Runs a call on the answer thread and waits for it, at most {@code boundMillis}.
     *
     * @param call        what to run
     * @param boundMillis how long to wait
     * @return what it answered, or {@code 0} when it failed or did not finish in time
     */
    static long away(LongSupplier call, long boundMillis) {
        return away(call, boundMillis, Pool.ANSWERS, UiaAnswerThread::landedLate);
    }

    /**
     * {@link #away(LongSupplier, long)} on any executor, with what is done with a late answer
     * given; for tests.
     *
     * <p>Once the bound has passed the call is abandoned: not started at all if it has not begun,
     * and otherwise left to finish, its answer then handed to {@code late} on the thread that
     * made it (or on this one, when it landed between the bound and the hand-over).
     *
     * @param call        what to run
     * @param boundMillis how long to wait
     * @param executor    where to run it
     * @param late        given the answer of a call that finished after the bound
     * @return what it answered, or {@code 0} when it failed or did not finish in time
     */
    static long away(LongSupplier call, long boundMillis, Executor executor, LongConsumer late) {
        AtomicBoolean abandoned = new AtomicBoolean();
        CompletableFuture<Long> answer = new CompletableFuture<>();
        executor.execute(() -> {
            if (abandoned.get()) {
                // The window's thread stopped waiting before this began: nothing is made for nobody.
                answer.cancel(false);
                return;
            }
            try {
                answer.complete(call.getAsLong());
            } catch (Throwable failed) {
                answer.completeExceptionally(failed);
            }
        });
        try {
            return answer.get(boundMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException tooLate) {
            abandon(abandoned, answer, late);
            LOG.log(System.Logger.Level.WARNING, "UI Automation did not take the window's provider"
                    + " within " + boundMillis + " ms; the window answers as if it had none");
            UiaTrace.note("provider hand-over outlived its " + boundMillis + " ms bound; answered 0");
            return 0;
        } catch (ExecutionException failed) {
            LOG.log(System.Logger.Level.WARNING, "handing the window's provider over failed",
                    failed.getCause());
            return 0;
        } catch (InterruptedException interrupted) {
            abandon(abandoned, answer, late);
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    /**
     * Stops waiting for a call: it is not started if it has not begun, and what it answers if it
     * has goes to {@code late} when it lands. A call that failed, or was never started, hands
     * nothing over.
     */
    private static void abandon(AtomicBoolean abandoned, CompletableFuture<Long> answer, LongConsumer late) {
        abandoned.set(true);
        answer.thenAccept(late::accept);
    }

    /**
     * What a hand-over that outlived its bound answered, once it lands: said, and nothing more,
     * because nothing more can be done with it (see the class's note). A key of {@code 0} is a
     * call that refused, and holds nothing.
     *
     * @param key what {@code UiaReturnRawElementProvider} answered
     */
    private static void landedLate(long key) {
        if (key == 0) {
            UiaTrace.note("provider hand-over answered 0 after its bound; it made no key");
            return;
        }
        String line = "provider hand-over returned key 0x" + Long.toHexString(key) + " after its bound;"
                + " the window had answered without it, so no client will claim it, and what it holds"
                + " of the provider is let go when the provider is disconnected";
        LOG.log(System.Logger.Level.WARNING, line);
        UiaTrace.note(line);
    }

    /** @return {@code InSendMessageEx(NULL)}, or {@code 0} where there is no such function */
    static int inSendFlags() {
        return IN_SEND_MESSAGE_EX == 0 ? 0 : (int) JNI.invokePP(MemoryUtil.NULL, IN_SEND_MESSAGE_EX);
    }

    /**
     * @return this thread's {@code APTTYPE}, or {@link #NO_APARTMENT} for a thread COM was never
     *         initialized on, which is not single-threaded either
     */
    static int apartmentType() {
        if (CO_GET_APARTMENT_TYPE == 0) {
            return NO_APARTMENT;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long type = stack.nmalloc(4, 8);
            int hr = JNI.invokePPI(type, type + 4, CO_GET_APARTMENT_TYPE);
            return hr < 0 ? NO_APARTMENT : MemoryUtil.memGetInt(type);
        }
    }

    private static void joinTheMultithreadedApartment() {
        if (CO_INITIALIZE_EX != 0) {
            JNI.invokePI(MemoryUtil.NULL, COINIT_MULTITHREADED, CO_INITIALIZE_EX);
        }
    }
}
