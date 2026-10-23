package limn.backend.lwjgl.transfer;

import limn.backend.spi.WindowInput;
import limn.concurrent.UiRuntime;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.SharedLibrary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Supplier;

import static org.lwjgl.system.MemoryUtil.memGetAddress;
import static org.lwjgl.system.MemoryUtil.memGetDouble;
import static org.lwjgl.system.MemoryUtil.memGetInt;
import static org.lwjgl.system.MemoryUtil.memPutAddress;
import static org.lwjgl.system.MemoryUtil.memPutByte;
import static org.lwjgl.system.MemoryUtil.memPutInt;

/**
 * A pinch on a trackpad under X11: XInput 2.4's gesture events, selected on this program's windows
 * from the clipboard's own connection and read on its thread, as its drags are. GLFW selects none
 * of them, so they reach no one else.
 *
 * <p>The server sends them only to a client that said it speaks 2.4, which this connection does
 * first. An X server older than 21.1, or an input driver older than xf86-input-libinput 1.2, sends
 * none, and the windows have no pinch there; the wheel with Control still zooms. Measured on Ubuntu
 * 24.04 (2026-10-22): selection from a second connection on GLFW's window is accepted, and a pinch
 * arrives at the window under the pointer as a begin, updates whose {@code scale} is the one since
 * the begin, and an end.
 *
 * <p>Every call into Xlib is made on the clipboard's thread, through {@link X11Clipboard#post}.
 * {@code -Dlimn.pinch.trace=true} prints each gesture event as it is read, as the Wayland side
 * prints its own.
 */
final class X11Pinch {

    private static final System.Logger LOG = System.getLogger(X11Pinch.class.getName());
    private static final boolean TRACE = Boolean.getBoolean("limn.pinch.trace");

    private static final int GENERIC_EVENT = 35;
    private static final int PINCH_BEGIN = 27;
    private static final int PINCH_UPDATE = 28;
    private static final int PINCH_END = 29;
    /** {@code XIAllMasterDevices}. */
    private static final int ALL_MASTER_DEVICES = 1;
    /** {@code XIMaskLen(XI_LASTEVENT)} for XInput 2.4. */
    private static final int MASK_BYTES = 5;

    /** One of this program's windows. */
    private record Entry(Supplier<WindowInput> input, DoubleUnaryOperator toLogical, UiRuntime ui) {
    }

    private final X11Clipboard x;
    private final Map<Long, Entry> windows = new ConcurrentHashMap<>();

    // The X thread's.
    private boolean prepared;
    private @Nullable SharedLibrary xi;
    private @Nullable SharedLibrary x11;
    private int opcode = -1;
    private @Nullable Entry over;
    private double lastScale;
    private boolean starting;

    X11Pinch(X11Clipboard x) {
        this.x = x;
    }

    /**
     * Sends the pinches over a window to its input. Any thread.
     *
     * @return what forgets the window
     */
    Runnable register(long xwindow, Supplier<WindowInput> input, DoubleUnaryOperator toLogical, UiRuntime ui) {
        windows.put(xwindow, new Entry(input, toLogical, ui));
        x.post(() -> {
            prepare();
            if (opcode >= 0) {
                select(xwindow);
            }
        });
        return () -> windows.remove(xwindow);
    }

    /** Asks the server for XInput 2.4, once; without it this connection hears no gesture. */
    private void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        try {
            xi = APIUtil.apiCreateLibrary("libXi.so.6");
            x11 = APIUtil.apiCreateLibrary("libX11.so.6");
        } catch (RuntimeException | LinkageError absent) {
            LOG.log(System.Logger.Level.DEBUG, "no libXi: a pinch is not read on X11", absent);
            return;
        }
        long display = x.display();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long codes = stack.ncalloc(4, 3, 4);
            if (Ffi.call(x11.getFunctionAddress("XQueryExtension"), "ppppp>i", display,
                    org.lwjgl.system.MemoryUtil.memAddress(stack.ASCII("XInputExtension")),
                    codes, codes + 4, codes + 8) == 0) {
                return;
            }
            long version = stack.ncalloc(4, 2, 4);
            memPutInt(version, 2);
            memPutInt(version + 4, 4);
            long status = Ffi.call(xi.getFunctionAddress("XIQueryVersion"), "ppp>i", display, version, version + 4);
            boolean gestures = status == 0 && (memGetInt(version) > 2
                    || (memGetInt(version) == 2 && memGetInt(version + 4) >= 4));
            if (gestures) {
                opcode = memGetInt(codes);
            } else {
                LOG.log(System.Logger.Level.DEBUG, "the X server speaks XInput " + memGetInt(version) + "."
                        + memGetInt(version + 4) + ", before gestures: a pinch is not read");
            }
        }
    }

    private void select(long xwindow) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long bits = stack.ncalloc(1, 8, 1);
            int pinch = (1 << PINCH_BEGIN) | (1 << PINCH_UPDATE) | (1 << PINCH_END);
            for (int i = 0; i < MASK_BYTES; i++) {
                memPutByte(bits + i, (byte) (pinch >>> (8 * i)));
            }
            long mask = stack.ncalloc(8, 2, 8);
            memPutInt(mask, ALL_MASTER_DEVICES);
            memPutInt(mask + 4, MASK_BYTES);
            memPutAddress(mask + 8, bits);
            Ffi.call(xi.getFunctionAddress("XISelectEvents"), "pppi>i", x.display(), xwindow, mask, 1);
        }
    }

    /**
     * An event of the clipboard's connection, on its thread.
     *
     * @return whether it was a gesture of ours
     */
    boolean event(long event) {
        if (opcode < 0 || memGetInt(event) != GENERIC_EVENT || memGetInt(event + 32) != opcode) {
            return false;
        }
        int type = memGetInt(event + 36);
        if (type < PINCH_BEGIN || type > PINCH_END) {
            return false;
        }
        long display = x.display();
        if (Ffi.call(x11.getFunctionAddress("XGetEventData"), "pp>i", display, event) == 0) {
            return true;
        }
        try {
            long data = memGetAddress(event + 48);
            // XIGesturePinchEvent: the event window at 72, its point at 104 and 112, the scale
            // since the begin at 152.
            long on = memGetAddress(data + 72);
            if (TRACE) {
                System.err.println("limn.pinch(x11): " + (type == PINCH_BEGIN ? "begin" : type == PINCH_UPDATE
                        ? "update" : "end") + " on 0x" + Long.toHexString(on) + " scale " + memGetDouble(data + 152)
                        + (windows.containsKey(on) ? "" : ", not a window of ours"));
            }
            if (type == PINCH_BEGIN) {
                over = windows.get(on);
                lastScale = 1;
                starting = true;
            } else if (type == PINCH_UPDATE) {
                pinched(memGetDouble(data + 152), memGetDouble(data + 104), memGetDouble(data + 112));
            } else {
                over = null;
            }
        } finally {
            Ffi.call(x11.getFunctionAddress("XFreeEventData"), "pp>v", display, event);
        }
        return true;
    }

    private void pinched(double scale, double eventX, double eventY) {
        Entry to = over;
        if (to == null || !(scale > 0) || !(lastScale > 0)) {
            return;
        }
        float factor = (float) (scale / lastScale);
        lastScale = scale;
        boolean began = starting;
        starting = false;
        float px = (float) to.toLogical().applyAsDouble(eventX);
        float py = (float) to.toLogical().applyAsDouble(eventY);
        to.ui().post(() -> {
            WindowInput in = to.input().get();
            if (in != null) {
                in.zoomed(factor, px, py, began);
                // Arrived outside the window library's batch, which is what drains a scene's
                // input: ended here, or the last updates of a pinch waited for the next event.
                in.inputBatchEnded();
            }
        });
    }
}
