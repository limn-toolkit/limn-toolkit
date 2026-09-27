package limn.backend.lwjgl;

import limn.backend.lwjgl.a11y.ClosureArgs;
import limn.backend.lwjgl.internal.ObjC;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * macOS native fullscreen as AppKit gives it, for a window GLFW made: sending
 * {@code toggleFullScreen:}, hearing the four notifications that frame each transition, reading the
 * style mask that says where the window is, and lending a window that GLFW made unable to go
 * fullscreen the collection behaviour it needs for the time it spends there. What a window does
 * with all of it is {@link NativeFullscreen}.
 *
 * <p><b>Why notifications and not GLFW.</b> GLFW knows nothing of native fullscreen: it reports
 * the resize the transition makes and nothing else, so a window could not tell a transition from a
 * drag, nor when one ends. AppKit posts {@code NSWindowWill…} and {@code NSWindowDid…EnterFullScreen}
 * and {@code …ExitFullScreen} for every transition, the person's as well as the program's, and the
 * notification centre delivers them to any observer without touching GLFW's window delegate. The
 * observer is one object of a class made here for the process, whose one method hands each
 * notification to the window it names.
 *
 * <p><b>Why a toggle is sent later and not now.</b> {@code toggleFullScreen:} can run a nested
 * event loop, as Chromium found when a window was closed from inside it, and from the middle of an
 * application's own call that would deliver input to the scene while the application's handler is
 * still on the stack. So the toggle is performed on the main thread's run loop, where GLFW's event
 * pump reaches it on its next turn, exactly as input is reached; by the observer rather than the
 * window, so that a window destroyed before that turn is not sent a toggle, since the observer
 * checks that the window is still one it observes. The turn it waits for is also what AppKit needs
 * after GLFW has taken a window out of a fullscreen on the monitor, which it does by rewriting the
 * style mask, the level and the frame.
 *
 * <p><b>Why the collection behaviour is lent.</b> GLFW gives a window that is not resizable
 * {@code NSWindowCollectionBehaviorFullScreenNone}, which makes AppKit ignore
 * {@code toggleFullScreen:} and disables the green button's fullscreen, and a resizable one
 * {@code NSWindowCollectionBehaviorFullScreenPrimary}. The window about to enter is given
 * {@code …FullScreenPrimary}, which is also what lets the person leave with the green button, and
 * given back what it had once it is out again, as Chromium does; a window left with it would
 * offer a fullscreen its application never asked for.
 *
 * <p>UI thread only, which on macOS is the main thread AppKit posts on. Nothing here may be
 * touched off macOS.
 */
final class MacFullscreen {

    private static final System.Logger LOG = System.getLogger(MacFullscreen.class.getName());

    /** A transition's boundary, as AppKit posts it. */
    enum Event {
        /** {@code NSWindowWillEnterFullScreenNotification}. */
        WILL_ENTER,
        /** {@code NSWindowDidEnterFullScreenNotification}. */
        DID_ENTER,
        /** {@code NSWindowWillExitFullScreenNotification}. */
        WILL_EXIT,
        /** {@code NSWindowDidExitFullScreenNotification}. */
        DID_EXIT
    }

    // ---- NSWindow.h, verbatim --------------------------------------------------------------------
    /** {@code NSWindowStyleMaskFullScreen}. */
    static final long STYLE_MASK_FULL_SCREEN = 1L << 14;
    /** {@code NSWindowStyleMaskResizable}. */
    static final long STYLE_MASK_RESIZABLE = 1L << 3;
    /** {@code NSWindowCollectionBehaviorFullScreenPrimary}. */
    static final long BEHAVIOR_FULL_SCREEN_PRIMARY = 1L << 7;
    /** {@code NSWindowCollectionBehaviorFullScreenAuxiliary}, which AppKit refuses beside the primary. */
    static final long BEHAVIOR_FULL_SCREEN_AUXILIARY = 1L << 8;
    /** {@code NSWindowCollectionBehaviorFullScreenNone}. */
    static final long BEHAVIOR_FULL_SCREEN_NONE = 1L << 9;

    private static final String CLASS_NAME = "LimnFullscreenObserver";
    private static final String NOTIFY = "limnFullscreenNotification:";
    private static final String TOGGLE = "limnToggleFullScreen:";

    /** The observer every window's notifications are delivered to; {@code NULL} until the first. */
    private static long observer = NULL;
    /** Whether making the observer failed; toggles are then sent directly and nothing is heard. */
    private static boolean unavailable;
    /** The closures behind the observer's two methods, for the life of the process. */
    private static ObjectMethod notified;
    private static ObjectMethod toggler;
    /** The notification names delivered, and the event each is: AppKit's four, and a test's. */
    private static final Map<Long, Event> names = new HashMap<>();
    /** AppKit's four names, which every window is observed for. */
    private static final List<Long> appKitNames = new ArrayList<>();
    /** The windows observed, by {@code NSWindow}, and who hears about each. */
    private static final Map<Long, Consumer<Event>> windows = new HashMap<>();

    private MacFullscreen() {
    }

    /**
     * Starts delivering a window's four transition notifications.
     *
     * @param nsWindow the window
     * @param listener told of each, on the main thread, from inside the event pump
     * @return whether the window is observed; {@code false} where the observer could not be made,
     *         which leaves {@link NativeFullscreen} to learn of every change from the style mask
     */
    static boolean observe(long nsWindow, Consumer<Event> listener) {
        if (nsWindow == NULL || !ensureObserver()) {
            return false;
        }
        windows.put(nsWindow, listener);
        long center = ObjC.msg(ObjC.cls("NSNotificationCenter"), "defaultCenter");
        for (long name : appKitNames) {
            ObjC.msg(center, "addObserver:selector:name:object:", observer, ObjC.sel(NOTIFY), name,
                    nsWindow);
        }
        return true;
    }

    /**
     * Stops delivering a window's notifications and forgets it, before the window is destroyed; a
     * toggle already waiting for the run loop is then not sent to it.
     */
    static void forget(long nsWindow) {
        if (windows.remove(nsWindow) == null || observer == NULL) {
            return;
        }
        long center = ObjC.msg(ObjC.cls("NSNotificationCenter"), "defaultCenter");
        ObjC.msg(center, "removeObserver:name:object:", observer, NULL, nsWindow);
    }

    /**
     * Sends {@code toggleFullScreen:} to the window on the run loop's next turn; see the class
     * comment for why not now.
     */
    static void toggleSoon(long nsWindow) {
        if (observer == NULL) {
            ObjC.msgVoid(nsWindow, "toggleFullScreen:", NULL);
            return;
        }
        // The window rides as the argument, which the perform retains until it has run: its
        // address cannot be reused by another window before the observer looks it up.
        ObjC.msg(observer, "performSelectorOnMainThread:withObject:waitUntilDone:",
                ObjC.sel(TOGGLE), nsWindow, 0L);
    }

    /** @return whether the window's style mask holds {@code NSWindowStyleMaskFullScreen} */
    static boolean isFullscreen(long nsWindow) {
        return (ObjC.msg(nsWindow, "styleMask") & STYLE_MASK_FULL_SCREEN) != 0;
    }

    /**
     * Gives the window the collection behaviour native fullscreen needs, when it lacks it.
     *
     * @return the behaviour it had, to give back with {@link #restoreCollectionBehavior} once the
     *         window is out of fullscreen again, or {@code -1} when it needed no change
     */
    static long allowFullscreen(long nsWindow) {
        long behavior = ObjC.msg(nsWindow, "collectionBehavior");
        long wanted = withFullscreenPrimary(behavior);
        if (wanted == behavior) {
            return -1;
        }
        ObjC.msgVoid(nsWindow, "setCollectionBehavior:", wanted);
        return behavior;
    }

    /** Gives back what {@link #allowFullscreen} replaced. */
    static void restoreCollectionBehavior(long nsWindow, long behavior) {
        ObjC.msgVoid(nsWindow, "setCollectionBehavior:", behavior);
    }

    /**
     * Lends a window that is not resizable the resizable style, which AppKit needs to stretch it
     * over its fullscreen Space: with the collection behaviour alone, a window GLFW made fixed got
     * a Space of its own and stayed at its windowed size in the middle of it (measured on a
     * MacBook Pro, 2026-09-27: 420 × 200 in a 1728 × 1084 Space). SDL lends it the same way.
     *
     * @return whether the style was lent, so that {@link #restoreResizable} takes it back
     */
    static boolean allowResize(long nsWindow) {
        long mask = ObjC.msg(nsWindow, "styleMask");
        if ((mask & STYLE_MASK_RESIZABLE) != 0) {
            return false;
        }
        ObjC.msgVoid(nsWindow, "setStyleMask:", mask | STYLE_MASK_RESIZABLE);
        return true;
    }

    /** Takes back the resizable style {@link #allowResize} lent, once the window is out again. */
    static void restoreResizable(long nsWindow) {
        long mask = ObjC.msg(nsWindow, "styleMask");
        ObjC.msgVoid(nsWindow, "setStyleMask:", mask & ~STYLE_MASK_RESIZABLE);
    }

    /**
     * @param behavior a window's collection behaviour
     * @return the same with {@code …FullScreenPrimary}, and without the two AppKit refuses beside
     *         it
     */
    static long withFullscreenPrimary(long behavior) {
        return (behavior & ~(BEHAVIOR_FULL_SCREEN_NONE | BEHAVIOR_FULL_SCREEN_AUXILIARY))
                | BEHAVIOR_FULL_SCREEN_PRIMARY;
    }

    /**
     * Makes {@code object} deliver one more notification name as {@code event}: the seam a test uses
     * to deliver through the real observer a name no part of AppKit listens for, rather than posting
     * AppKit's own for a window that is not in transition.
     */
    static boolean observeAs(long object, long name, Event event, Consumer<Event> listener) {
        if (!ensureObserver()) {
            return false;
        }
        names.put(name, event);
        windows.put(object, listener);
        long center = ObjC.msg(ObjC.cls("NSNotificationCenter"), "defaultCenter");
        ObjC.msg(center, "addObserver:selector:name:object:", observer, ObjC.sel(NOTIFY), name, object);
        return true;
    }

    private static boolean ensureObserver() {
        if (observer != NULL) {
            return true;
        }
        if (unavailable || !ObjC.isAvailable()) {
            return false;
        }
        try {
            SharedLibrary appKit = APIUtil.apiCreateLibrary(
                    "/System/Library/Frameworks/AppKit.framework/AppKit");
            appKitNames.add(constant(appKit, "NSWindowWillEnterFullScreenNotification"));
            appKitNames.add(constant(appKit, "NSWindowDidEnterFullScreenNotification"));
            appKitNames.add(constant(appKit, "NSWindowWillExitFullScreenNotification"));
            appKitNames.add(constant(appKit, "NSWindowDidExitFullScreenNotification"));
            Event[] events = { Event.WILL_ENTER, Event.DID_ENTER, Event.WILL_EXIT, Event.DID_EXIT };
            for (int i = 0; i < events.length; i++) {
                names.put(appKitNames.get(i), events[i]);
            }
            long made = ObjCRuntime.objc_allocateClassPair(ObjC.cls("NSObject"), CLASS_NAME, 0);
            if (made == NULL) {
                throw new IllegalStateException("could not make " + CLASS_NAME);
            }
            notified = new ObjectMethod() {
                @Override
                public void invoke(long self, long cmd, long notification) {
                    deliver(notification);
                }
            };
            toggler = new ObjectMethod() {
                @Override
                public void invoke(long self, long cmd, long nsWindow) {
                    try {
                        if (windows.containsKey(nsWindow)) {
                            ObjC.msgVoid(nsWindow, "toggleFullScreen:", NULL);
                        }
                    } catch (Throwable error) {
                        LOG.log(Level.ERROR, "toggleFullScreen: could not be sent", error);
                    }
                }
            };
            ObjCRuntime.class_addMethod(made, ObjC.sel(NOTIFY), notified.address(), "v@:@");
            ObjCRuntime.class_addMethod(made, ObjC.sel(TOGGLE), toggler.address(), "v@:@");
            ObjCRuntime.objc_registerClassPair(made);
            observer = ObjC.msg(ObjC.msg(made, "alloc"), "init"); // kept for the life of the process
            return observer != NULL;
        } catch (RuntimeException | LinkageError error) {
            unavailable = true;
            names.clear();
            appKitNames.clear();
            LOG.log(Level.WARNING, "could not observe native fullscreen transitions; a window "
                    + "learns of them from its style mask alone", error);
            return false;
        }
    }

    private static long constant(SharedLibrary appKit, String symbol) {
        long address = appKit.getFunctionAddress(symbol);
        if (address == NULL) {
            throw new IllegalStateException("AppKit exports no " + symbol);
        }
        return org.lwjgl.system.MemoryUtil.memGetAddress(address);
    }

    /**
     * Hands a notification to the window it names. Called by AppKit, from inside the event pump:
     * nothing may escape, because an exception pending across AppKit's own frames surfaces
     * nowhere useful, and the listener only records.
     */
    private static void deliver(long notification) {
        try {
            Consumer<Event> listener = windows.get(ObjC.msg(notification, "object"));
            if (listener == null) {
                return;
            }
            long name = ObjC.msg(notification, "name");
            Event event = names.get(name);
            if (event == null) {
                for (Map.Entry<Long, Event> known : names.entrySet()) {
                    // A BOOL answer: only its low byte is defined.
                    if ((ObjC.msg(name, "isEqualToString:", known.getKey()) & 0xFF) != 0) {
                        event = known.getValue();
                        break;
                    }
                }
            }
            if (event != null) {
                listener.accept(event);
            }
        } catch (Throwable error) {
            LOG.log(Level.ERROR, "a native fullscreen notification could not be delivered", error);
        }
    }

    /** {@code (id self, SEL _cmd, id argument) -> void}: both of the observer's methods. */
    private interface ObjectMethodI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(ObjectMethodI.class,
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

        void invoke(long self, long cmd, long argument);
    }

    private abstract static class ObjectMethod extends Callback implements ObjectMethodI {
        ObjectMethod() {
            super(ObjectMethodI.DESCRIPTOR);
        }
    }
}
