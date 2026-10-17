package limn.backend.lwjgl;

import limn.scene.SystemPreferences;
import limn.scene.SystemPreferences.ColorScheme;
import limn.scene.internal.SystemPreferencesAccess;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.User32;
import org.lwjgl.system.windows.WNDCLASSEX;
import org.lwjgl.system.windows.WindowProc;
import org.lwjgl.system.windows.WindowsLibrary;

import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The appearance, reduced motion and high contrast a Windows user chose, read from the registry
 * and {@code SystemParametersInfo}, and followed through {@code WM_SETTINGCHANGE}.
 *
 * <p>Read on the guest on 2026-09-29 (Windows 11): the apps' theme is {@code AppsUseLightTheme}
 * under the user's {@code Themes\Personalize} key, 0 for dark; animation is
 * {@code SPI_GETCLIENTAREAANIMATION}; high contrast is {@code HCF_HIGHCONTRASTON} in
 * {@code SPI_GETHIGHCONTRAST}, and turning it on changed {@code SystemUsesLightTheme} but left
 * {@code AppsUseLightTheme} light, so contrast is read on its own. A change is broadcast as
 * {@code WM_SETTINGCHANGE} ({@code ImmersiveColorSet} for the theme, wParam {@code 0x1043} and
 * {@code 0x43} for the two others), which reaches top-level windows only: a message-only window
 * hears no broadcast. So the backend keeps one hidden top-level window of its own for it, rather
 * than subclassing GLFW's, whose procedure the accessibility bridge already chains.
 *
 * <p>UI thread only: the window's messages are dispatched by the event poll.
 */
final class WindowsSystemPreferences {

    private static final System.Logger LOG = System.getLogger(WindowsSystemPreferences.class.getName());

    private static final int WM_SETTINGCHANGE = 0x001A;
    private static final int WM_THEMECHANGED = 0x031A;
    private static final int SPI_GETHIGHCONTRAST = 0x0042;
    private static final int SPI_GETCLIENTAREAANIMATION = 0x1042;
    /** "Underline access keys when available": TRUE keeps them shown, FALSE waits for Alt. */
    private static final int SPI_GETKEYBOARDCUES = 0x100A;
    private static final int HCF_HIGHCONTRASTON = 0x1;
    /** {@code HIGHCONTRASTW}: cbSize, dwFlags, then the scheme's name, pointer-aligned. */
    private static final int HIGHCONTRAST_SIZE = 16;
    /** {@code HKEY_CURRENT_USER}, sign-extended as the headers define it on 64-bit Windows. */
    private static final long HKEY_CURRENT_USER = 0xFFFFFFFF80000001L;
    private static final int RRF_RT_REG_DWORD = 0x10;
    private static final String PERSONALIZE =
            "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final int WS_POPUP = 0x80000000;
    private static final int WS_EX_TOOLWINDOW = 0x00000080;
    private static final String CLASS_NAME = "LimnSystemPreferences";

    private static final SharedLibrary USER32 = User32.getLibrary();
    private static final long SYSTEM_PARAMETERS_INFO = USER32.getFunctionAddress("SystemParametersInfoW");
    private static final long REG_GET_VALUE = regGetValue();

    /**
     * The class's procedure, registered once and kept for the life of the process: a second backend
     * finds the class registered, and a procedure freed with the first would be called for its window.
     */
    private static WindowProc procedure;

    /** Where a broadcast goes: the backend of the moment. */
    private static volatile Runnable changed;

    private long hwnd = NULL;

    private WindowsSystemPreferences() {
    }

    private static long regGetValue() {
        try {
            SharedLibrary advapi = APIUtil.apiCreateLibrary("advapi32");
            return advapi.getFunctionAddress("RegGetValueW");
        } catch (RuntimeException | LinkageError unavailable) {
            return NULL;
        }
    }

    /** @return what Windows says now */
    static SystemPreferences read() {
        return SystemPreferencesAccess.of(scheme(), !clientAreaAnimation(), highContrast(), keyboardCues());
    }

    private static ColorScheme scheme() {
        if (REG_GET_VALUE == NULL) {
            return ColorScheme.NO_PREFERENCE;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.ints(0);
            IntBuffer size = stack.ints(4);
            int status = JNI.invokePPPPPPPI(HKEY_CURRENT_USER, MemoryUtil.memAddress(stack.UTF16(PERSONALIZE)),
                    MemoryUtil.memAddress(stack.UTF16("AppsUseLightTheme")), RRF_RT_REG_DWORD, NULL,
                    MemoryUtil.memAddress(value), MemoryUtil.memAddress(size), REG_GET_VALUE);
            if (status != 0) {
                return ColorScheme.NO_PREFERENCE; // no value: the user never chose
            }
            return value.get(0) == 0 ? ColorScheme.DARK : ColorScheme.LIGHT;
        }
    }

    /**
     * @return whether the task bar and the rest of the system's own surfaces are light, which is
     *         a setting apart from the applications' one; light when the user never chose
     */
    static boolean systemSurfacesAreLight() {
        if (REG_GET_VALUE == NULL) {
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.ints(1);
            IntBuffer size = stack.ints(4);
            int status = JNI.invokePPPPPPPI(HKEY_CURRENT_USER, MemoryUtil.memAddress(stack.UTF16(PERSONALIZE)),
                    MemoryUtil.memAddress(stack.UTF16("SystemUsesLightTheme")), RRF_RT_REG_DWORD, NULL,
                    MemoryUtil.memAddress(value), MemoryUtil.memAddress(size), REG_GET_VALUE);
            return status != 0 || value.get(0) != 0;
        }
    }

    /** @return whether the user's applications are set to dark */
    static boolean applicationsAreDark() {
        return scheme() == ColorScheme.DARK;
    }

    /** @return whether a high-contrast scheme is on */
    static boolean highContrastOn() {
        return highContrast();
    }

    private static boolean clientAreaAnimation() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer on = stack.ints(1);
            int ok = JNI.invokePPPPI(SPI_GETCLIENTAREAANIMATION, 0L, MemoryUtil.memAddress(on), 0L,
                    SYSTEM_PARAMETERS_INFO);
            return ok == 0 || on.get(0) != 0;
        }
    }

    private static boolean keyboardCues() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer on = stack.ints(0);
            int ok = JNI.invokePPPPI(SPI_GETKEYBOARDCUES, 0L, MemoryUtil.memAddress(on), 0L,
                    SYSTEM_PARAMETERS_INFO);
            return ok != 0 && on.get(0) != 0;
        }
    }

    private static boolean highContrast() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer hc = stack.calloc(8, HIGHCONTRAST_SIZE);
            hc.putInt(0, HIGHCONTRAST_SIZE);
            int ok = JNI.invokePPPPI(SPI_GETHIGHCONTRAST, HIGHCONTRAST_SIZE, MemoryUtil.memAddress(hc), 0L,
                    SYSTEM_PARAMETERS_INFO);
            return ok != 0 && (hc.getInt(4) & HCF_HIGHCONTRASTON) != 0;
        }
    }

    /**
     * Makes the hidden window that hears the broadcasts.
     *
     * @param onChange what runs after a broadcast, on the UI thread, inside the poll
     * @return the follower, or {@code null} when the window could not be made, which is logged
     */
    static WindowsSystemPreferences follow(Runnable onChange) {
        changed = onChange;
        WindowsSystemPreferences follower = new WindowsSystemPreferences();
        return follower.open() ? follower : null;
    }

    private boolean open() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer className = stack.UTF16(CLASS_NAME);
            if (procedure == null) {
                procedure = WindowProc.create(WindowsSystemPreferences::procedure);
                WNDCLASSEX windowClass = WNDCLASSEX.calloc(stack)
                        .cbSize(WNDCLASSEX.SIZEOF)
                        .lpfnWndProc(procedure)
                        .hInstance(WindowsLibrary.HINSTANCE)
                        .lpszClassName(className);
                User32.RegisterClassEx(null, windowClass);
            }
            hwnd = User32.CreateWindowEx(null, WS_EX_TOOLWINDOW, className, stack.UTF16(""), WS_POPUP,
                    0, 0, 0, 0, NULL, NULL, WindowsLibrary.HINSTANCE, NULL);
        }
        if (hwnd == NULL) {
            LOG.log(Level.WARNING, "could not make the window that hears setting changes; system "
                    + "preferences are read once and not followed");
            return false;
        }
        return true;
    }

    private static long procedure(long window, int message, long wParam, long lParam) {
        if (message == WM_SETTINGCHANGE || message == WM_THEMECHANGED) {
            Runnable target = changed;
            if (target != null) {
                try {
                    target.run();
                } catch (Throwable error) {
                    LOG.log(Level.ERROR, "a system preference change could not be delivered", error);
                }
            }
        }
        return User32.DefWindowProc(window, message, wParam, lParam);
    }

    /** Destroys the hidden window, and sends no more broadcasts to this backend. UI thread only. */
    void close() {
        changed = null;
        if (hwnd != NULL) {
            User32.DestroyWindow(null, hwnd);
            hwnd = NULL;
        }
    }
}
