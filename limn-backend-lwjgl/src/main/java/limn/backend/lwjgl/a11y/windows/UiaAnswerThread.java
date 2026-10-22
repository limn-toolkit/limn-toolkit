package limn.backend.lwjgl.a11y.windows;

import limn.backend.lwjgl.internal.NativeLibraries;
import limn.concurrent.internal.Threads;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * exception nothing catches, and the process dies: five runs of five, a 32-bit client as well as
 * a native one. The same call made from a thread of the multithreaded apartment answered in three
 * runs of three and the client read the whole tree; so did a user-interface thread that had
 * joined the multithreaded apartment itself (two of two), which is how the apartment was told
 * from everything else. An x64 JDK on the same machine never failed (three of three), which is
 * why no earlier run saw it, and a system file dialog on a thread of its own in the same ARM64
 * process read whole: it is this call, made on that thread, inside that message.
 *
 * <p>So when both hold, the call is made here, on a daemon thread of the multithreaded
 * apartment, while the window's thread waits. The provider minds no thread: UI Automation calls
 * its members from threads of its own already. The wait does not pump, since nothing in that one
 * call sends to the window, and it is bounded anyway: a wait that cannot end is a frozen window,
 * and a window that answers nothing falls back to its default provider.
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
                    task.run();
                }));
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
        Future<Long> answer = Pool.ANSWERS.submit(call::getAsLong);
        try {
            return answer.get(boundMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            // Not cancelled: an interrupt does not reach a thread inside the platform. What it
            // answers when it returns is dropped, and the provider reference it handed over with it.
            LOG.log(System.Logger.Level.WARNING, "UI Automation did not take the window's provider"
                    + " within " + boundMillis + " ms; the window answers as if it had none");
            UiaTrace.note("provider hand-over outlived its " + boundMillis + " ms bound; answered 0");
            return 0;
        } catch (ExecutionException failed) {
            LOG.log(System.Logger.Level.WARNING, "handing the window's provider over failed",
                    failed.getCause());
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return 0;
        }
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
