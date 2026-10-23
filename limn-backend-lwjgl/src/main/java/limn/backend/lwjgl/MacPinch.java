package limn.backend.lwjgl;

import limn.backend.lwjgl.a11y.ClosureArgs;
import limn.backend.lwjgl.internal.ObjC;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.JNI;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A pinch on a trackpad, on macOS: AppKit sends it to the view under the pointer as
 * {@code -magnifyWithEvent:}, which GLFW's content view does not answer, so it went up the
 * responder chain and nowhere.
 *
 * <p>The method is added to the class {@link MacInputContextGate} moves every window's view into,
 * once for the process, and not to GLFW's own class; a window whose view the gate could not move
 * has no pinch, and the wheel with Command still zooms in it. Measured on this Mac (2026-10-22): a
 * pinch arrives as one stream, every 17 ms or so, of events whose {@code magnification} is the
 * change since the event before and whose {@code phase} is began, then changed, then ended, the
 * ended one carrying a change of its own; a two-finger scroll never arrives inside it.
 *
 * <p>Main thread only, which is where AppKit calls it.
 */
final class MacPinch {

    private static final System.Logger LOG = System.getLogger(MacPinch.class.getName());

    /** {@code NSEventPhaseBegan}. */
    private static final long PHASE_BEGAN = 0x1;

    /** What a window does with a pinch: the factor since the event before, and whether it began. */
    @FunctionalInterface
    interface Target {
        void pinched(float scale, boolean began);
    }

    private static final Map<Long, Target> targets = new HashMap<>();
    /** The class the method was added to, once; {@code NULL} before the first window. */
    private static long answering = NULL;
    /** The closure behind the method, for the life of the process. */
    private static Magnify magnify;

    private MacPinch() {
    }

    /**
     * Sends the pinches over a window's view to its target.
     *
     * @param view   a content view {@link MacInputContextGate} moved into its class
     * @param target what the window does with them
     */
    static void install(long view, Target target) {
        long viewClass = ObjCRuntime.object_getClass(view);
        if (answering == NULL) {
            long selector = ObjC.sel("magnifyWithEvent:");
            long inherited = ObjCRuntime.class_getInstanceMethod(viewClass, selector);
            String encoding = inherited == NULL ? "v24@0:8@16" : ObjCRuntime.method_getTypeEncoding(inherited);
            long magnification = ObjC.sel("magnification");
            long phase = ObjC.sel("phase");
            magnify = new Magnify() {
                @Override
                public void invoke(long self, long cmd, long event) {
                    Target to = targets.get(self);
                    if (to == null) {
                        return;
                    }
                    double change = JNI.invokePPD(event, magnification, ObjC.msgSend());
                    long now = JNI.invokePPP(event, phase, ObjC.msgSend());
                    if (change > -1) {
                        to.pinched((float) (1 + change), (now & PHASE_BEGAN) != 0);
                    }
                }
            };
            if (!ObjCRuntime.class_addMethod(viewClass, selector, magnify.address(), encoding)) {
                LOG.log(Level.WARNING, "the window's view already answers -magnifyWithEvent:; "
                        + "a pinch is left to it");
            }
            answering = viewClass;
        }
        if (viewClass == answering) {
            targets.put(view, target);
        }
    }

    /** Forgets a view whose window is being destroyed. */
    static void forget(long view) {
        targets.remove(view);
    }

    /** {@code (id self, SEL _cmd, NSEvent *event) -> void}. */
    @FunctionalInterface
    private interface MagnifyI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(MagnifyI.class,
                MethodHandles.lookup(), APIUtil.apiCreateCIF(LibFFI.ffi_type_void,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            invoke(ClosureArgs.pointer(args, 0), ClosureArgs.pointer(args, 1), ClosureArgs.pointer(args, 2));
        }

        void invoke(long self, long cmd, long event);
    }

    private abstract static class Magnify extends Callback implements MagnifyI {
        Magnify() {
            super(MagnifyI.DESCRIPTOR);
        }
    }
}
