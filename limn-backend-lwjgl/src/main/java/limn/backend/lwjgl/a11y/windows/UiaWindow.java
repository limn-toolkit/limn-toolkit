package limn.backend.lwjgl.a11y.windows;

import limn.backend.lwjgl.internal.NativeLibraries;
import org.lwjgl.system.Callback;
import org.lwjgl.system.JNI;
import org.lwjgl.system.SharedLibrary;

/**
 * The one message that tells UI Automation this window has a provider, and the subclass that
 * answers it.
 *
 * <p>A provider is not registered anywhere: a client sends {@code WM_GETOBJECT} to a window and
 * whoever answers it owns that window's accessibility. So this puts a window procedure of its own
 * in front of the one the toolkit's backend installed, answers that one message, and passes
 * everything else straight through to what was there before.
 *
 * <p><b>Answered synchronously, on the thread that owns the window.</b> The message arrives inside
 * the backend's own message pump, so the tree handed over is the one already published and nothing
 * walks here — which is the whole reason §1.1 makes the tree a snapshot.
 *
 * <p>The three constants below come from {@code winuser.h} and {@code uiautomationcoreapi.h},
 * which need a Windows SDK the guest does not carry. Unlike the identifiers and the slot orders,
 * they are visible the moment they are wrong: a window that answers nothing has no accessibility
 * at all, which is what the live run checks.
 */
final class UiaWindow {

    /** {@code GWLP_WNDPROC}: which of a window's pointers to replace. */
    private static final int GWLP_WNDPROC = -4;

    /** {@code WM_GETOBJECT}: a client asking what this window offers. */
    private static final int WM_GETOBJECT = 0x003D;
    private static final int WM_DESTROY = 0x0002;

    /** {@code UiaRootObjectId}: which object it is asking for, of the several a window can offer. */
    private static final int UIA_ROOT_OBJECT_ID = -25;

    /**
     * Where to write what arrives, or {@code null} for nowhere.
     *
     * <p>This message is the one link in the chain that cannot be observed from the outside: if it
     * does not arrive, or the answer is refused, a client simply falls back to the window's default
     * provider and everything looks almost right. So the live probe sets this and reads the log.
     *
     * <p><b>The in-process sink, and not the only one.</b> It is here because this is where it has
     * been since the bridge was written and where its tests read it; the file a guest run names
     * with {@code -Dlimn.a11y.uia.trace} is {@link UiaTrace#file}, and every line reaches both.
     */
    static volatile java.util.function.Consumer<String> trace;

    /** Writes to whatever is listening; the bridge's own notes go through here too. */
    static void say(String what) {
        UiaTrace.note(what);
    }

    private static final SharedLibrary USER32 =
            NativeLibraries.optional(UiaWindow.class, "limn.backend.lwjgl.a11y.windows", "user32");
    private static final long SET_WINDOW_LONG_PTR =
            NativeLibraries.address(USER32, "SetWindowLongPtrW");
    private static final long CALL_WINDOW_PROC = NativeLibraries.address(USER32, "CallWindowProcW");

    private final long hwnd;
    private final long previous;
    private final Callback.Descriptor unusedButKept;
    private final UiaCom.WndProc procedure;
    private final long closure;

    private UiaWindow(long hwnd, long previous, UiaCom.WndProc procedure, long closure) {
        this.hwnd = hwnd;
        this.previous = previous;
        this.procedure = procedure;
        this.closure = closure;
        this.unusedButKept = UiaCom.WndProc.DESCRIPTOR;
    }



    /** @return whether a window procedure can be replaced on this machine */
    static boolean isAvailable() {
        return SET_WINDOW_LONG_PTR != 0L && CALL_WINDOW_PROC != 0L;
    }

    /**
     * Puts a procedure in front of the window's own, answering {@code WM_GETOBJECT} and nothing
     * else.
     *
     * @param hwnd   the window
     * @param bridge what to answer with
     * @return the attachment, to be undone with {@link #detach()}, or {@code null} where the
     *         platform has no window procedures to replace
     */
    static UiaWindow attach(long hwnd, UiaBridge bridge) {
        if (!isAvailable() || hwnd == 0) {
            return null;
        }
        long[] previous = new long[1];
        UiaCom.WndProc procedure = (window, message, wparam, lparam) -> {
            if (message == WM_GETOBJECT) {
                say("WM_GETOBJECT lparam=" + lparam + " (as int " + (int) lparam + ")");
                // Whatever it asks for -- the UI Automation root, or MSAA's OBJID_CLIENT, which
                // is what a reader sends first when a window comes to the foreground -- a client
                // is entering through this window, and the gate opens for it (§13.5). Measured:
                // NVDA announces the foreground by MSAA and looks inside by UI Automation only
                // after a focus event it can hear, which a closed gate never raises. This is
                // what breaks that circle.
                bridge.noteAsked();
            }
            if (message == WM_DESTROY) {
                // The documented teardown for a window that had a provider: hand UI Automation
                // NULL for this HWND so its cache lets the provider go. The bridge does the same
                // when the scene detaches it while the window is alive (the usual order); this
                // is for a window destroyed under a still-attached bridge.
                say("WM_DESTROY: withdrawing the window's provider");
                Uia.returnRawElementProvider(window, 0, 0, 0);
            }
            if (message == WM_GETOBJECT && (int) lparam == UIA_ROOT_OBJECT_ID) {
                long answer = bridge.answerGetObject(wparam, lparam);
                say("  answered " + answer);
                if (answer != 0) {
                    return answer;
                }
                // Nothing published yet: fall through, so the window answers what it would have
                // answered without this bridge rather than claiming an empty tree.
            }
            // The procedure to call is CallWindowProcW's FIRST argument and not one of the four
            // it forwards. Passing the window handle there jumps into a handle, which is an
            // access violation in the message pump and the first thing the live run found.
            return JNI.invokePPPPP(previous[0], window, message, wparam, lparam, CALL_WINDOW_PROC);
        };
        long closure = procedure.address();
        previous[0] = JNI.invokePPP(hwnd, GWLP_WNDPROC, closure, SET_WINDOW_LONG_PTR);
        say("subclassed hwnd=" + Long.toHexString(hwnd) + " previous=" + Long.toHexString(previous[0]));
        if (previous[0] == 0) {
            Callback.free(closure);
            return null;
        }
        return new UiaWindow(hwnd, previous[0], procedure, closure);
    }

    /** Puts the window's own procedure back and frees this one. */
    void detach() {
        JNI.invokePPP(hwnd, GWLP_WNDPROC, previous, SET_WINDOW_LONG_PTR);
        Callback.free(closure);
    }
}
