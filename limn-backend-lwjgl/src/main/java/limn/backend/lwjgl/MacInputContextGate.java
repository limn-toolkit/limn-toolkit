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
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Turns a window's input method off and on on macOS the way AppKit does for a control that is not
 * text: the view answers no input context, and keystrokes go past the input method untouched.
 *
 * <p><b>Why not GLFW's own switch.</b> {@code glfwSetInputMode(GLFW_IME, …)} is implemented on
 * macOS by selecting a different keyboard input source for the whole system: off selects an
 * ASCII-capable layout, on selects the preferred language's. Measured on a guest with the Japanese
 * input method chosen, opening a window with a text field focused switched the system to the Latin
 * layout within half a second, every move of the focus to a button did it again, a move back to
 * the field did not restore Japanese, and the change outlived the application. A person typing
 * Japanese, Chinese or Korean had to choose their input method again after every click on a
 * button. A native application never changes the input source; it simply has no input context
 * while a control that takes no text is first responder.
 *
 * <p><b>How, without touching a class GLFW owns.</b> One subclass of GLFW's content view is made
 * for the process, with {@code -inputContext} answering nil for a view whose input method is off
 * and the inherited context otherwise; each window's view is moved into that class, as AppKit's
 * own key-value observing moves an object into a subclass, and GLFW's class is left as it was.
 * {@code -interpretKeyEvents:} asks the view for its context on every key, so a switch takes
 * effect on the next keystroke. With no context, AppKit's key bindings hand the typed text to
 * {@code -insertText:}, the responder's method, which GLFW's view does not implement: the
 * subclass forwards it to {@code -insertText:replacementRange:}, where GLFW reports characters, or
 * a list's type-ahead would hear the keys and never the letters. Both encodings are read from the
 * running AppKit, as the accessibility bridge reads every one it installs.
 *
 * <p>UI thread only, which on macOS is the main thread AppKit calls the method on.
 */
final class MacInputContextGate {

    private static final System.Logger LOG = System.getLogger(MacInputContextGate.class.getName());

    private static final String CLASS_NAME = "LimnInputGatedContentView";

    /** Our subclass of GLFW's content view; {@code NULL} until the first window. */
    private static long gatedClass = NULL;
    /** GLFW's content view class, which {@link #gatedClass} extends. */
    private static long glfwClass = NULL;
    /** The {@code -inputContext} the gated class inherits, called for a view whose method is on. */
    private static long inheritedInputContext = NULL;
    /** The closures behind the gated methods, for the life of the process. */
    private static InputContextGetter getter;
    private static TextInserter inserter;
    /** {@code NSNotFound}: the replacement range that means "at the selection". */
    private static final long NOT_FOUND = Long.MAX_VALUE;
    /** Whether making the class failed; every window then leaves its input method on. */
    private static boolean unavailable;

    /** The views whose input method is off. */
    private static final Set<Long> off = new HashSet<>();

    private MacInputContextGate() {
    }

    /**
     * Moves a window's content view into the gated class, with its input method off: nothing is
     * focused yet, and a scene turns it on when a text widget takes the focus.
     *
     * @param view the window's content view
     * @return whether the view is gated; {@code false} leaves its input method on, and nothing
     *         changes the system's input source either way
     */
    static boolean install(long view) {
        if (view == NULL || unavailable || !ObjC.isAvailable()) {
            return false;
        }
        long viewClass = ObjCRuntime.object_getClass(view);
        if (gatedClass == NULL && !makeClass(viewClass)) {
            unavailable = true;
            return false;
        }
        if (viewClass != glfwClass) {
            // Every GLFW window's view is of one class; a view of another is not ours to move.
            LOG.log(Level.WARNING, "a window's content view is not of the class the input gate "
                    + "extends; its input method stays on");
            return false;
        }
        ObjCRuntime.object_setClass(view, gatedClass);
        off.add(view);
        return true;
    }

    /**
     * @param view    a view {@link #install} gated
     * @param enabled whether its input method takes the keys
     */
    static void setEnabled(long view, boolean enabled) {
        if (enabled) {
            off.remove(view);
        } else {
            off.add(view);
        }
    }

    /** Forgets a view whose window is being destroyed. */
    static void forget(long view) {
        off.remove(view);
    }

    private static boolean makeClass(long viewClass) {
        long selector = ObjC.sel("inputContext");
        long method = ObjCRuntime.class_getInstanceMethod(viewClass, selector);
        String encoding = method == NULL ? null : ObjCRuntime.method_getTypeEncoding(method);
        long insertSelector = ObjC.sel("insertText:");
        long insertMethod = ObjCRuntime.class_getInstanceMethod(viewClass, insertSelector);
        String insertEncoding = insertMethod == NULL ? null
                : ObjCRuntime.method_getTypeEncoding(insertMethod);
        long replacing = ObjC.sel("insertText:replacementRange:");
        if (encoding == null || insertEncoding == null
                || ObjCRuntime.class_getInstanceMethod(viewClass, replacing) == NULL) {
            LOG.log(Level.WARNING, "the window's content view lacks -inputContext, -insertText: or "
                    + "-insertText:replacementRange:; input methods stay on for every window");
            return false;
        }
        long made = ObjCRuntime.objc_allocateClassPair(viewClass, CLASS_NAME, 0);
        if (made == NULL) {
            LOG.log(Level.WARNING, "could not make " + CLASS_NAME + "; input methods stay on for "
                    + "every window");
            return false;
        }
        inheritedInputContext = ObjCRuntime.method_getImplementation(method);
        getter = new InputContextGetter() {
            @Override
            public long invoke(long self, long cmd) {
                return off.contains(self) ? NULL : JNI.invokePPP(self, cmd, inheritedInputContext);
            }
        };
        inserter = new TextInserter() {
            @Override
            public void invoke(long self, long cmd, long text) {
                JNI.invokePPPPPV(self, replacing, text, NOT_FOUND, 0L, ObjC.msgSend());
            }
        };
        ObjCRuntime.class_addMethod(made, selector, getter.address(), encoding);
        ObjCRuntime.class_addMethod(made, insertSelector, inserter.address(), insertEncoding);
        ObjCRuntime.objc_registerClassPair(made);
        gatedClass = made;
        glfwClass = viewClass;
        return true;
    }

    /** {@code (id self, SEL _cmd) -> id}. */
    private interface InputContextGetterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(InputContextGetterI.class,
                MethodHandles.lookup(), APIUtil.apiCreateCIF(LibFFI.ffi_type_pointer,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            APIUtil.apiClosureRetP(ret, invoke(ClosureArgs.pointer(args, 0), ClosureArgs.pointer(args, 1)));
        }

        long invoke(long self, long cmd);
    }

    private abstract static class InputContextGetter extends Callback implements InputContextGetterI {
        InputContextGetter() {
            super(InputContextGetterI.DESCRIPTOR);
        }
    }

    /** {@code (id self, SEL _cmd, id text) -> void}. */
    private interface TextInserterI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(TextInserterI.class,
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

        void invoke(long self, long cmd, long text);
    }

    private abstract static class TextInserter extends Callback implements TextInserterI {
        TextInserter() {
            super(TextInserterI.DESCRIPTOR);
        }
    }
}
