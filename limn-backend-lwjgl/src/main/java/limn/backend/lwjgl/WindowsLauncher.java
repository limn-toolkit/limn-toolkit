package limn.backend.lwjgl;

import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.WindowsLibrary;

import java.nio.file.Path;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Hands an address or a file to Windows through {@code ShellExecuteW}, owned by the window that
 * asked. The call can wait on the program it starts, so it is made on a thread of its own, which
 * joins COM first: a protocol handler may be a COM object.
 */
final class WindowsLauncher {

    private static final System.Logger LOG = System.getLogger(WindowsLauncher.class.getName());

    // shellapi.h, winuser.h and objbase.h.
    private static final int SW_SHOWNORMAL = 1;
    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int COINIT_DISABLE_OLE1DDE = 0x4;
    /** ShellExecute answers above this for success, and an error code at or below it. */
    private static final long SUCCESS_ABOVE = 32;

    private static SharedLibrary shell32;
    private static SharedLibrary ole32;

    private WindowsLauncher() {
    }

    /** Opens an address or a path with the verb {@code open}. */
    static boolean open(long ownerWindow, String target) {
        return execute(ownerWindow, target, null);
    }

    /** Shows a file selected in its folder, by the file manager's own command line. */
    static boolean showInFolder(long ownerWindow, Path file) {
        String arguments = selectArguments(file);
        return arguments != null && execute(ownerWindow, "explorer.exe", arguments);
    }

    /**
     * The file manager's arguments for one file selected, or null for a path that cannot be
     * quoted: a quote in it would end the quoting, and no Windows file name can hold one.
     */
    static String selectArguments(Path file) {
        String path = file.toString();
        return path.indexOf('"') >= 0 ? null : "/select,\"" + path + "\"";
    }

    private static synchronized boolean execute(long ownerWindow, String file, String parameters) {
        if (Pointer.POINTER_SIZE != 8) {
            return false;
        }
        if (shell32 == null) {
            // By bare name, so that LoadLibrary answers from the system directory.
            shell32 = new WindowsLibrary("shell32.dll");
            ole32 = new WindowsLibrary("ole32.dll");
        }
        long shellExecute = shell32.getFunctionAddress("ShellExecuteW");
        long coInitializeEx = ole32.getFunctionAddress("CoInitializeEx");
        if (shellExecute == NULL) {
            return false;
        }
        if (coInitializeEx != NULL) {
            // S_FALSE and RPC_E_CHANGED_MODE both leave COM usable on this thread.
            JNI.invokePI(NULL, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE, coInitializeEx);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long result = JNI.invokePPPPPP(ownerWindow, utf16(stack, "open"), utf16(stack, file),
                    utf16(stack, parameters), NULL, SW_SHOWNORMAL, shellExecute);
            if (result <= SUCCESS_ABOVE) {
                LOG.log(System.Logger.Level.WARNING, "ShellExecuteW answered {0} for {1}", result, file);
                return false;
            }
            return true;
        }
    }

    private static long utf16(MemoryStack stack, String text) {
        return text == null ? NULL : MemoryUtil.memAddress(stack.UTF16(text));
    }
}
