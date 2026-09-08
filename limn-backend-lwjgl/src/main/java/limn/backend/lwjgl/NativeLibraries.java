package limn.backend.lwjgl;

import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;

/**
 * A platform library this backend can do without, and the functions in it it can do without too.
 *
 * <p>The accessibility bridges open {@code uiautomationcore}, {@code oleaut32} and {@code user32}
 * by name and resolve a dozen entry points against them, and on every machine that is not
 * Windows each of those is simply absent. Absent is an answer, not an error: the bridge for that
 * platform declines to open, and the window it would have served opens anyway. Three classes
 * wrote the same try-and-null pair for it; this is the pair once.
 *
 * <p>Internal to the backend; nothing above it names this type.
 */
public final class NativeLibraries {

    private NativeLibraries() {
    }

    /**
     * Opens a library by its short name, or answers {@code null} where there is none.
     *
     * @param owner  the class asking, whose class loader the search starts from
     * @param module the LWJGL module name the library is looked up under
     * @param name   the library's short name, {@code user32} and the like
     * @return the library, or null when it is not on this machine or will not load
     */
    public static SharedLibrary optional(Class<?> owner, String module, String name) {
        try {
            return Library.loadNative(owner, module, name);
        } catch (Throwable absent) {
            // Not this platform, or a platform without the library: both are "not here", and
            // neither is the caller's business to complain about.
            return null;
        }
    }

    /**
     * The address of a function in a library, or {@code 0} when the library did not open or does
     * not export it.
     *
     * @param library  what {@link #optional} answered, null included
     * @param function the function's name
     * @return the address, or zero
     */
    public static long address(SharedLibrary library, String function) {
        if (library == null) {
            return 0L;
        }
        try {
            return APIUtil.apiGetFunctionAddress(library, function);
        } catch (Throwable missing) {
            return 0L;
        }
    }
}
