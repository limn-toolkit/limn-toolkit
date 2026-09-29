package limn.backend.lwjgl;

import limn.backend.lwjgl.a11y.ClosureArgs;
import limn.backend.lwjgl.internal.ObjC;
import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The appearance, reduced motion and increased contrast a Mac's user chose, read from AppKit and
 * followed as they change.
 *
 * <p>Read on the guest on 2026-09-29 (macOS 27): the application's effective appearance is Aqua or
 * DarkAqua, and the two accessibility flags are {@code NSWorkspace}'s. Key-value observing of
 * {@code NSApp.effectiveAppearance} fired for every change of all three, from the panels and from
 * System Events; the workspace's accessibility-options notification fired for the two flags. Both
 * are observed: the notification is what AppKit documents for the flags, and the observation is
 * what catches an appearance set by any route.
 *
 * <p>Both arrive on the main thread, which on macOS is the UI thread, inside the event poll; the
 * change is handed on to run after it.
 */
final class MacSystemPreferences {

    private static final System.Logger LOG = System.getLogger(MacSystemPreferences.class.getName());

    private static final String CLASS_NAME = "LimnSystemPreferencesObserver";

    /** {@code NSKeyValueObservingOptionNew}. */
    private static final long OBSERVE_NEW = 1;

    /** The observer object and its two methods, for the life of the process. */
    private static long observer = NULL;
    private static KeyValueChanged kvo;
    private static Notified notified;

    /** Where a change goes: the backend of the moment. */
    private static volatile Runnable changed;

    private MacSystemPreferences() {
    }

    /** @return what AppKit says now. Main thread */
    static SystemPreferences read() {
        long app = ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication");
        String name = string(ObjC.msg(ObjC.msg(app, "effectiveAppearance"), "name"));
        ColorScheme scheme = name.contains("Dark") ? ColorScheme.DARK : ColorScheme.LIGHT;
        long workspace = ObjC.msg(ObjC.cls("NSWorkspace"), "sharedWorkspace");
        boolean reduceMotion = (ObjC.msg(workspace, "accessibilityDisplayShouldReduceMotion") & 0xff) != 0;
        boolean increaseContrast =
                (ObjC.msg(workspace, "accessibilityDisplayShouldIncreaseContrast") & 0xff) != 0;
        return new SystemPreferences(scheme, reduceMotion, increaseContrast);
    }

    /**
     * Follows changes: {@code onChange} runs on the main thread after each. Installs the observer
     * once for the process; a later call only changes where the change goes.
     *
     * @return whether changes are followed; {@code false} is logged, and the first reading stands
     */
    static boolean follow(Runnable onChange) {
        changed = onChange;
        if (observer != NULL) {
            return true;
        }
        try {
            long made = ObjCRuntime.objc_allocateClassPair(ObjC.cls("NSObject"), CLASS_NAME, 0);
            if (made == NULL) {
                LOG.log(Level.WARNING, "could not make " + CLASS_NAME + "; system preferences are "
                        + "read once and not followed");
                return false;
            }
            kvo = new KeyValueChanged() {
                @Override
                public void invoke(long self, long cmd, long keyPath, long object, long change, long context) {
                    deliver();
                }
            };
            notified = new Notified() {
                @Override
                public void invoke(long self, long cmd, long notification) {
                    deliver();
                }
            };
            ObjCRuntime.class_addMethod(made, ObjC.sel("observeValueForKeyPath:ofObject:change:context:"),
                    kvo.address(), "v@:@@@^v");
            ObjCRuntime.class_addMethod(made, ObjC.sel("limnDisplayOptionsChanged:"),
                    notified.address(), "v@:@");
            ObjCRuntime.objc_registerClassPair(made);
            long created = ObjC.msg(ObjC.msg(made, "alloc"), "init");
            long app = ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long keyPath = ObjC.msg(ObjC.cls("NSString"), "stringWithUTF8String:",
                        MemoryUtil.memAddress(stack.UTF8("effectiveAppearance")));
                ObjC.msg(app, "addObserver:forKeyPath:options:context:", created, keyPath, OBSERVE_NEW, NULL);
            }
            SharedLibrary appKit = APIUtil.apiCreateLibrary("/System/Library/Frameworks/AppKit.framework/AppKit");
            long symbol = appKit.getFunctionAddress("NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification");
            if (symbol != NULL) {
                long name = MemoryUtil.memGetAddress(symbol);
                long workspace = ObjC.msg(ObjC.cls("NSWorkspace"), "sharedWorkspace");
                long center = ObjC.msg(workspace, "notificationCenter");
                ObjC.msg(center, "addObserver:selector:name:object:", created,
                        ObjC.sel("limnDisplayOptionsChanged:"), name, NULL);
            }
            observer = created; // kept for the life of the process
            return true;
        } catch (RuntimeException | LinkageError error) {
            LOG.log(Level.WARNING, "could not follow the system preferences; they are read once", error);
            return false;
        }
    }

    /** Where changes go once the backend that followed them has closed: nowhere. */
    static void forget(Runnable onChange) {
        if (changed == onChange) {
            changed = null;
        }
    }

    private static void deliver() {
        Runnable target = changed;
        if (target == null) {
            return;
        }
        try {
            target.run();
        } catch (Throwable error) {
            LOG.log(Level.ERROR, "a system preference change could not be delivered", error);
        }
    }

    private static String string(long nsString) {
        if (nsString == NULL) {
            return "";
        }
        long utf8 = ObjC.msg(nsString, "UTF8String");
        return utf8 == NULL ? "" : MemoryUtil.memUTF8(utf8);
    }

    /** {@code (id self, SEL _cmd, NSString *keyPath, id object, NSDictionary *change, void *context) -> void}. */
    private interface KeyValueChangedI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(KeyValueChangedI.class,
                MethodHandles.lookup(), APIUtil.apiCreateCIF(LibFFI.ffi_type_void,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            invoke(ClosureArgs.pointer(args, 0), ClosureArgs.pointer(args, 1), ClosureArgs.pointer(args, 2),
                    ClosureArgs.pointer(args, 3), ClosureArgs.pointer(args, 4), ClosureArgs.pointer(args, 5));
        }

        void invoke(long self, long cmd, long keyPath, long object, long change, long context);
    }

    private abstract static class KeyValueChanged extends Callback implements KeyValueChangedI {
        KeyValueChanged() {
            super(KeyValueChangedI.DESCRIPTOR);
        }
    }

    /** {@code (id self, SEL _cmd, NSNotification *notification) -> void}. */
    private interface NotifiedI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(NotifiedI.class,
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

        void invoke(long self, long cmd, long notification);
    }

    private abstract static class Notified extends Callback implements NotifiedI {
        Notified() {
            super(NotifiedI.DESCRIPTOR);
        }
    }
}
