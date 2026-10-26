package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.lwjgl.transfer.Ffi;
import limn.concurrent.internal.Threads;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.util.ArrayDeque;

/**
 * Reuses live, pumping STA threads on Windows ARM64. The 2026-10-26 diagnostic reproduced
 * RPC_E_DISCONNECTED during apartment teardown after a file dialog, even with no reader running.
 * Keeping the apartment alive prevented that failure. Idle workers pump COM messages and are
 * reused; overlapping dialogs get separate workers, so one window does not block another's chooser.
 * Threads are daemons retained until process exit, bounded by the peak concurrent dialog count.
 */
final class WindowsFileDialogThread {
    static final boolean PERSISTENT = Platform.current().isWindows()
            && "aarch64".equals(System.getProperty("os.arch"));
    private static final int WAKE = 0x8000 + 0x4c;
    private static final ArrayDeque<Worker> IDLE = new ArrayDeque<>();
    private static final ThreadLocal<Boolean> INITIALIZED = ThreadLocal.withInitial(() -> false);

    private static final class Native {
        static final SharedLibrary USER = APIUtil.apiCreateLibrary("user32");
        static final SharedLibrary KERNEL = APIUtil.apiCreateLibrary("kernel32");
        static final long POST = USER.getFunctionAddress("PostThreadMessageW");
        static final long GET = USER.getFunctionAddress("GetMessageW");
        static final long TRANSLATE = USER.getFunctionAddress("TranslateMessage");
        static final long DISPATCH = USER.getFunctionAddress("DispatchMessageW");
    }

    private WindowsFileDialogThread() { }

    static void execute(Runnable task) {
        if (!PERSISTENT) {
            Threads.daemon("limn-file-dialog", task);
            return;
        }
        synchronized (IDLE) {
            Worker worker = IDLE.pollFirst();
            if (worker == null) {
                Worker created = new Worker(task);
                Threads.daemon("limn-file-dialog", created::run);
            } else {
                worker.task = task;
                if (Ffi.call(Native.POST, "iipp>i", worker.threadId, WAKE, 0, 0) == 0) {
                    worker.task = null;
                    IDLE.addFirst(worker);
                    throw new IllegalStateException("could not wake the file dialog thread");
                }
            }
        }
    }

    static void initialize(long coInitializeEx) {
        if (PERSISTENT && INITIALIZED.get()) {
            return;
        }
        int result = JNI.invokePPI(0L, 2L, coInitializeEx);
        if (result < 0) {
            throw new IllegalStateException("CoInitializeEx failed: 0x" + Integer.toHexString(result));
        }
        if (PERSISTENT) {
            INITIALIZED.set(true);
        }
    }

    private static final class Worker {
        private Runnable task;
        private int threadId;

        Worker(Runnable first) { task = first; }

        void run() {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long msg = stack.ncalloc(8, 1, 48);
                // Establish the thread's queue before publishing it as idle (PostThreadMessage).
                Ffi.call(Native.USER.getFunctionAddress("PeekMessageW"), "ppiii>i", msg, 0, 0, 0, 0);
                threadId = JNI.invokeI(Native.KERNEL.getFunctionAddress("GetCurrentThreadId"));
                perform();
                for (;;) {
                    int result = JNI.invokePPI(msg, 0L, 0, 0, Native.GET);
                    if (result > 0) {
                        if (MemoryUtil.memGetAddress(msg) == 0 && MemoryUtil.memGetInt(msg + 8) == WAKE) {
                            perform();
                        } else {
                            JNI.invokePI(msg, Native.TRANSLATE);
                            JNI.invokePP(msg, Native.DISPATCH);
                        }
                    } else if (result < 0) {
                        // Do not spin or tear down the apartment on a transient GetMessage failure.
                        java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
                    }
                    // WM_QUIT is intentionally consumed: this apartment lives until process exit.
                }
            }
        }

        private void perform() {
            Runnable next;
            synchronized (IDLE) {
                next = task;
                task = null;
            }
            if (next == null) {
                return;
            }
            try {
                next.run();
            } catch (Throwable failure) {
                System.getLogger(WindowsFileDialogThread.class.getName()).log(System.Logger.Level.WARNING,
                        "the file dialog task failed", failure);
            } finally {
                synchronized (IDLE) {
                    IDLE.addLast(this);
                }
            }
        }
    }
}
