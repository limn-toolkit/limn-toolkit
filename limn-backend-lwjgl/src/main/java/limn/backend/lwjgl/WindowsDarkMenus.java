package limn.backend.lwjgl;

import limn.backend.lwjgl.transfer.Ffi;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Tells Windows that this process accepts dark menus, so that a menu the system draws for it,
 * the tray's among them, is dark when the user's applications are.
 *
 * <p>Windows draws a classic popup menu light whatever the user chose, unless the process says
 * otherwise, and the only way to say it is a function the theme library exports by number and
 * does not document: ordinal 135, which from Windows 10's build 18362 on takes an application
 * mode, 1 for "follow the system", and ordinal 136, which makes the menus' themes be read again.
 * Before that build the same number is another function with another argument, so the guard is
 * the build and not whether the export is there. Read on a Windows 11 guest on 2026-10-11 with
 * applications set to dark and the system told of it: without the call the tray's menu is light,
 * with it dark.
 *
 * <p>{@code -Dlimn.windows.darkMenus=false} leaves the system's default, for an application
 * some other native part of which the declaration changes in a way it does not want.
 */
final class WindowsDarkMenus {

    /** The first build where ordinal 135 takes an application mode. */
    static final int FIRST_BUILD = 18362;
    private static final int ALLOW_DARK = 1;
    private static final System.Logger LOG = System.getLogger(WindowsDarkMenus.class.getName());

    private static long flush = NULL;

    private WindowsDarkMenus() {
    }

    /**
     * @param build    the system's build number
     * @param property the value of {@code limn.windows.darkMenus}, or null
     * @return whether the declaration is made
     */
    static boolean wanted(int build, String property) {
        return build >= FIRST_BUILD && !"false".equalsIgnoreCase(property);
    }

    /** Makes the declaration once, when the backend starts; does nothing where it should not. */
    static synchronized void declare() {
        try {
            if (!wanted(build(), System.getProperty("limn.windows.darkMenus"))) {
                return;
            }
            SharedLibrary kernel32 = APIUtil.apiCreateLibrary("kernel32");
            long loadLibrary = kernel32.getFunctionAddress("LoadLibraryW");
            long getProcAddress = kernel32.getFunctionAddress("GetProcAddress");
            long theme;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                theme = Ffi.call(loadLibrary, "p>p", MemoryUtil.memAddress(stack.UTF16("uxtheme.dll")));
            }
            if (theme == NULL) {
                return;
            }
            long setMode = Ffi.call(getProcAddress, "pp>p", theme, 135);
            long flushThemes = Ffi.call(getProcAddress, "pp>p", theme, 136);
            if (setMode == NULL || flushThemes == NULL) {
                return;
            }
            Ffi.call(setMode, "i>i", ALLOW_DARK);
            Ffi.call(flushThemes, ">v");
            flush = flushThemes;
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "dark menus were not declared", failed);
        }
    }

    /** Has the menus' themes read again, after the user went from light to dark or back. */
    static synchronized void flush() {
        if (flush != NULL) {
            Ffi.call(flush, ">v");
        }
    }

    /** @return the system's build number, or 0 when it cannot be read */
    static int build() {
        try {
            SharedLibrary ntdll = APIUtil.apiCreateLibrary("ntdll");
            long rtlGetVersion = ntdll.getFunctionAddress("RtlGetVersion");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long info = stack.ncalloc(4, 284, 1);          // RTL_OSVERSIONINFOEXW
                MemoryUtil.memPutInt(info, 284);
                Ffi.call(rtlGetVersion, "p>i", info);
                return MemoryUtil.memGetInt(info + 12);        // dwBuildNumber
            }
        } catch (RuntimeException | LinkageError unavailable) {
            return 0;
        }
    }
}
