package limn.backend.lwjgl;

import limn.backend.lwjgl.a11y.ClosureArgs;
import limn.backend.lwjgl.internal.ObjC;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Makes macOS's Quit command for the application (Cmd-Q, the application menu's Quit item, the
 * Dock's Quit, a quit Apple event) the application's own {@code requestQuit()}.
 *
 * <p>Every one of those reaches {@code -[NSApplication terminate:]}, which asks the application
 * delegate's {@code -applicationShouldTerminate:}. GLFW's delegate answers it by sending a close
 * request to every window, newest first, and cancelling: measured on 2026-09-29, a window that
 * refused left the others already closed. This answers {@code NSTerminateCancel} at once instead and
 * posts the request to the UI thread, where every window is asked in order and none closes until
 * all agree. Answering {@code NSTerminateLater} was measured too, and hangs: AppKit then runs a
 * nested run loop inside {@code terminate:} until a reply that only the loop it blocks could send.
 *
 * <p><b>How, without touching a class GLFW owns.</b> GLFW's delegate object is moved into a
 * subclass of its class made for the process, overriding that one method, as
 * {@link MacInputContextGate} moves a window's view; GLFW's class is left as it was, and every other
 * method of its delegate is still its own.
 *
 * <p>UI thread only, which on macOS is the main thread AppKit calls the method on.
 */
final class MacQuitCommand {

    private static final System.Logger LOG = System.getLogger(MacQuitCommand.class.getName());

    private static final String CLASS_NAME = "LimnApplicationDelegate";

    /** {@code NSTerminateCancel}. */
    private static final long TERMINATE_CANCEL = 0;

    /** The closure behind the override, for the life of the process. */
    private static ShouldTerminate override;

    /** Where the command goes; the backend of the moment, since a process may make several. */
    private static volatile Runnable quit;

    private MacQuitCommand() {
    }

    /**
     * Routes the Quit command to {@code onQuit}, called on the UI thread after the event that
     * carried it: installs the override once for the process, and on later calls only changes
     * where the command goes.
     *
     * @param onQuit what the command does, typically posting the backend's {@code requestQuit()}
     * @return whether the command reaches {@code onQuit}; {@code false} when GLFW made no
     *         delegate or its class could not be subclassed, which is logged, and GLFW keeps
     *         answering the command as before
     */
    static boolean route(Runnable onQuit) {
        quit = onQuit;
        if (override != null) {
            return true;
        }
        try {
            long app = ObjC.msg(ObjC.cls("NSApplication"), "sharedApplication");
            long delegate = app == NULL ? NULL : ObjC.msg(app, "delegate");
            if (delegate == NULL) {
                LOG.log(Level.WARNING, "GLFW set no application delegate; Cmd-Q stays GLFW's");
                return false;
            }
            long glfwClass = ObjCRuntime.object_getClass(delegate);
            long selector = ObjC.sel("applicationShouldTerminate:");
            long method = ObjCRuntime.class_getInstanceMethod(glfwClass, selector);
            String encoding = method == NULL ? null : ObjCRuntime.method_getTypeEncoding(method);
            if (encoding == null) {
                LOG.log(Level.WARNING, "the application delegate has no applicationShouldTerminate:; "
                        + "Cmd-Q stays AppKit's");
                return false;
            }
            long made = ObjCRuntime.objc_allocateClassPair(glfwClass, CLASS_NAME, 0);
            if (made == NULL) {
                LOG.log(Level.WARNING, "could not make " + CLASS_NAME + "; Cmd-Q stays GLFW's");
                return false;
            }
            ShouldTerminate answer = new ShouldTerminate() {
                @Override
                public long invoke(long self, long cmd, long sender) {
                    Runnable target = quit;
                    if (target != null) {
                        try {
                            target.run();
                        } catch (Throwable error) {
                            LOG.log(Level.ERROR, "the Quit command could not be delivered", error);
                        }
                    }
                    return TERMINATE_CANCEL;
                }
            };
            ObjCRuntime.class_addMethod(made, selector, answer.address(), encoding);
            ObjCRuntime.objc_registerClassPair(made);
            ObjCRuntime.object_setClass(delegate, made);
            override = answer;
            return true;
        } catch (RuntimeException | LinkageError error) {
            LOG.log(Level.WARNING, "could not take the Quit command; Cmd-Q stays GLFW's", error);
            return false;
        }
    }

    /** Where the command goes once the backend that routed it has closed: nowhere. */
    static void forget(Runnable onQuit) {
        if (quit == onQuit) {
            quit = null;
        }
    }

    /** {@code (id self, SEL _cmd, id sender) -> NSApplicationTerminateReply}. */
    @FunctionalInterface
    private interface ShouldTerminateI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(ShouldTerminateI.class,
                MethodHandles.lookup(), APIUtil.apiCreateCIF(LibFFI.ffi_type_uint64,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            MemoryUtil.memPutLong(ret, invoke(ClosureArgs.pointer(args, 0), ClosureArgs.pointer(args, 1),
                    ClosureArgs.pointer(args, 2)));
        }

        long invoke(long self, long cmd, long sender);
    }

    private abstract static class ShouldTerminate extends Callback implements ShouldTerminateI {
        ShouldTerminate() {
            super(ShouldTerminateI.DESCRIPTOR);
        }
    }
}
