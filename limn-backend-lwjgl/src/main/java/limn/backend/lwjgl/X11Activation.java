package limn.backend.lwjgl;

import limn.backend.lwjgl.transfer.Ffi;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.linux.X11;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Bringing a window in front under X11 with the proof a window manager asks for: the time of
 * the user's action.
 *
 * <p>A window manager that guards against windows taking the focus compares the time a request
 * to activate carries with the time the user last acted in the window that is active. A request
 * with no time, which is what the windowing library sends, loses on such a desktop, and the
 * window is marked as wanting attention instead. So where the user did act, in a panel's icon
 * or in a window of ours, the request is sent with that action's time: the one a panel's token
 * names, or the server's own time now when the token has none.
 */
final class X11Activation {

    private static final System.Logger LOG = System.getLogger(X11Activation.class.getName());
    /** A startup identifier ends with the time of the action it stands for. */
    private static final Pattern TIME = Pattern.compile("_TIME(\\d+)$");
    private static final int CLIENT_MESSAGE = 33;
    private static final int PROPERTY_NOTIFY = 28;
    private static final long PROPERTY_CHANGE_MASK = 1L << 22;
    private static final long SUBSTRUCTURE = 1L << 20 | 1L << 19;   // redirect and notify
    private static final int PROP_MODE_REPLACE = 0;
    private static final int PROP_MODE_APPEND = 2;
    private static final long XA_CARDINAL = 6;
    private static final long XA_STRING = 31;

    private X11Activation() {
    }

    private static final java.util.concurrent.atomic.AtomicInteger TOKENS = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * A startup identifier for a program this one starts because the user asked: a name of this
     * process's and the server's time now, which is what a window manager reads from it to let
     * the program's window come in front. Any thread; it opens a connection of its own.
     *
     * @return the identifier, or {@code ""} when the server's time could not be read
     */
    static String startupToken() {
        try {
            long time = serverTime(X11.getLibrary());
            return time == 0 ? "" : "limn-" + ProcessHandle.current().pid() + "-" + TOKENS.incrementAndGet()
                    + "_TIME" + time;
        } catch (RuntimeException | LinkageError failed) {
            return "";
        }
    }

    /**
     * @param token a panel's token, or null
     * @return the time it names, or 0 when it names none
     */
    static long timeOf(String token) {
        if (token == null) {
            return 0;
        }
        Matcher matcher = TIME.matcher(token);
        try {
            return matcher.find() ? Long.parseLong(matcher.group(1)) & 0xFFFFFFFFL : 0;
        } catch (NumberFormatException tooLong) {
            return 0;
        }
    }

    /**
     * Asks the window manager to make a window the active one, as of a time. UI thread.
     *
     * @param display the X display the window is on
     * @param window  the window
     * @param token   the token of the user's action, or null when only the fact of it is known
     * @return whether the request was sent
     */
    static boolean activate(long display, long window, String token) {
        if (display == NULL || window == NULL) {
            return false;
        }
        try {
            SharedLibrary x = X11.getLibrary();
            long time = timeOf(token);
            if (time == 0) {
                time = serverTime(x);
            }
            if (time == 0) {
                return false;
            }
            long root = Ffi.call(x.getFunctionAddress("XDefaultRootWindow"), "p>p", display);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // The window's own record of when the user last acted in it, which the manager
                // reads beside the request.
                long userTime = atom(x, display, stack, "_NET_WM_USER_TIME");
                long value = stack.ncalloc(8, 1, 8);
                MemoryUtil.memPutLong(value, time);
                Ffi.call(x.getFunctionAddress("XChangeProperty"), "ppppiipi>i", display, window, userTime,
                        XA_CARDINAL, 32, PROP_MODE_REPLACE, value, 1);

                long event = stack.ncalloc(8, 192, 1);           // an XEvent, as a client message
                MemoryUtil.memPutInt(event, CLIENT_MESSAGE);
                MemoryUtil.memPutInt(event + 16, 1);              // send_event
                MemoryUtil.memPutAddress(event + 24, display);
                MemoryUtil.memPutLong(event + 32, window);
                MemoryUtil.memPutLong(event + 40, atom(x, display, stack, "_NET_ACTIVE_WINDOW"));
                MemoryUtil.memPutInt(event + 48, 32);
                MemoryUtil.memPutLong(event + 56, 1);             // from an application
                MemoryUtil.memPutLong(event + 64, time);
                Ffi.call(x.getFunctionAddress("XSendEvent"), "ppipp>i", display, root, 0, SUBSTRUCTURE, event);
                Ffi.call(x.getFunctionAddress("XFlush"), "p>i", display);
            }
            return true;
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "the window could not be activated with a time", failed);
            return false;
        }
    }

    private static long atom(SharedLibrary x, long display, MemoryStack stack, String name) {
        return Ffi.call(x.getFunctionAddress("XInternAtom"), "ppi>p", display,
                MemoryUtil.memAddress(stack.ASCII(name)), 0);
    }

    /**
     * The server's time now, which no call answers: read off the event the server sends when a
     * property changes, on a connection of this method's own so that no event of the windowing
     * library's is read in its place.
     */
    static long serverTime(SharedLibrary x) {
        long display = Ffi.call(x.getFunctionAddress("XOpenDisplay"), "p>p", NULL);
        if (display == NULL) {
            return 0;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long root = Ffi.call(x.getFunctionAddress("XDefaultRootWindow"), "p>p", display);
            long window = Ffi.call(x.getFunctionAddress("XCreateSimpleWindow"), "ppiiiiipp>p", display, root,
                    -10, -10, 1, 1, 0, 0, 0);
            Ffi.call(x.getFunctionAddress("XSelectInput"), "ppp>i", display, window, PROPERTY_CHANGE_MASK);
            long property = atom(x, display, stack, "_LIMN_TIME");
            Ffi.call(x.getFunctionAddress("XChangeProperty"), "ppppiipi>i", display, window, property,
                    XA_STRING, 8, PROP_MODE_APPEND, stack.ncalloc(1, 1, 1), 0);
            long event = stack.ncalloc(8, 192, 1);
            Ffi.call(x.getFunctionAddress("XWindowEvent"), "pppp>i", display, window, PROPERTY_CHANGE_MASK, event);
            long time = MemoryUtil.memGetInt(event) == PROPERTY_NOTIFY ? MemoryUtil.memGetLong(event + 48) : 0;
            Ffi.call(x.getFunctionAddress("XDestroyWindow"), "pp>i", display, window);
            return time & 0xFFFFFFFFL;
        } finally {
            Ffi.call(x.getFunctionAddress("XCloseDisplay"), "p>i", display);
        }
    }
}
