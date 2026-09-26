package limn.backend.lwjgl;

import limn.i18n.I18n;
import limn.scene.LayoutDirection;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.windows.WindowsLibrary;

import java.io.File;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Windows' own error dialog for a machine with no OpenGL 3.3 driver, shown to a person who opened
 * the application from its icon, with a button that opens the fix.
 *
 * <p>{@link GraphicsProbe#contextAdvice} already puts the fix in the exception's message, and a
 * terminal shows it. An application started from Explorer, a shortcut or a jpackage launcher has
 * no terminal: {@code javaw.exe} and a GUI launcher have no console, the message goes nowhere, and
 * what the person sees is an icon that did nothing. So when {@link GraphicsProbe#lacksDriver} says
 * the machine has no driver and nothing would show the error, this shows a task dialog first: the
 * heading, what happened and where, then command links that open the Compatibility Pack in the
 * Microsoft Store and, where winget is present, install it in a command window the person can
 * watch. The exception is thrown afterwards exactly as before, whatever is clicked.
 *
 * <p><b>When.</b> Once per process; only on Windows, only for the three refusals, only when the
 * process has no console window and its window station is visible (a service or an SSH session's
 * session 0 has nobody in front of it); never when {@value #PROPERTY} is {@code false}, which the
 * build sets for its tests so that no test run can wait on a modal window nobody sees.
 *
 * <p><b>How.</b> {@code TaskDialogIndirect} lives only in version 6 of the common controls, and a
 * process gets version 6 only if its executable's manifest asks for it. When the loaded
 * {@code comctl32.dll} does not export it, this asks for version 6 itself through an activation
 * context built from a manifest written to a temporary file, and where even that fails it falls
 * back to {@code MessageBoxW} with Yes and No. The text is the process locale's, from
 * {@link DriverHelpStrings}, mirrored for a right-to-left language.
 *
 * <p>Nothing here may replace the failure it reports: every native step is guarded, and a dialog
 * that cannot be shown is logged and skipped.
 */
final class WindowsDriverDialog {

    private static final System.Logger LOG = System.getLogger(WindowsDriverDialog.class.getName());

    /** Set to {@code false} to never show the dialog; the error is thrown either way. */
    static final String PROPERTY = "limn.backend.errorDialog";

    /** The Compatibility Pack's page in the Store application. */
    static final String STORE_URI =
            "ms-windows-store://pdp/?ProductId=" + GraphicsProbe.COMPATIBILITY_PACK_STORE_ID;

    /** The same page on the web, for a Windows without the Store (LTSC, Server). */
    static final String STORE_WEB =
            "https://apps.microsoft.com/detail/" + GraphicsProbe.COMPATIBILITY_PACK_STORE_ID;

    /** What the winget button runs, and what its note shows. */
    static final String WINGET_COMMAND =
            "winget install --id " + GraphicsProbe.COMPATIBILITY_PACK_WINGET_ID + " --exact";

    /** The two command links' identifiers; anything else is Close or Escape. */
    static final int STORE_BUTTON = 100;
    static final int WINGET_BUTTON = 101;

    private static final AtomicBoolean OFFERED = new AtomicBoolean();

    private WindowsDriverDialog() {
    }

    /** What a person chose; {@link #CLOSE} includes the dialog that could not be shown. */
    enum Choice { STORE, WINGET, CLOSE }

    /**
     * The dialog's text, resolved: a record so the choice of words and buttons is testable on any
     * platform, and the native part below only copies it.
     *
     * @param title   the title bar, the application window's own title, or null for the
     *                executable's name
     * @param heading the main instruction
     * @param body    what happened and what to do
     * @param store   the Store link, caption and note separated by a newline
     * @param winget  the winget link the same way, or null where winget is absent
     * @param details GLFW's own description of the refusal, behind Windows' "See details"
     * @param askStore the question the Yes/No message box ends with
     * @param rtl     whether the language reads right to left
     */
    record Content(String title, String heading, String body, String store, String winget,
                   String details, String askStore, boolean rtl) {
    }

    /**
     * Shows the dialog if this process is one whose error nobody would otherwise see, then does
     * what was chosen. Returns normally in every case; the caller throws.
     *
     * @param windowTitle the title of the window that could not be created
     * @param failure     the exception message about to be thrown
     */
    static void offer(String windowTitle, String failure) {
        if ("false".equalsIgnoreCase(System.getProperty(PROPERTY))) {
            return;
        }
        if (!OFFERED.compareAndSet(false, true)) {
            return;
        }
        try {
            Win32 win32 = Win32.open();
            if (win32 == null) {
                return;
            }
            if (win32.hasConsole()) {
                LOG.log(Level.DEBUG, "no driver dialog: this process has a console, which shows the error");
                return;
            }
            if (!win32.stationIsVisible()) {
                LOG.log(Level.DEBUG, "no driver dialog: this window station is not interactive");
                return;
            }
            Content content = content(windowTitle, failure, wingetIsPresent());
            switch (win32.show(content)) {
                case STORE -> win32.openStore();
                case WINGET -> win32.runWinget();
                case CLOSE -> { }
            }
        } catch (Throwable dialogFailed) {
            // The window failure is what the caller reports; the dialog about it must not become
            // the error instead.
            LOG.log(Level.WARNING, "the driver dialog could not be shown", dialogFailed);
        }
    }

    /**
     * The dialog's text in the process locale.
     *
     * @param windowTitle the application window's title; blank means the executable's name
     * @param failure     GLFW's description of the refusal, for the details area
     * @param winget      whether to offer the winget link
     */
    static Content content(String windowTitle, String failure, boolean winget) {
        String title = windowTitle == null || windowTitle.isBlank() ? null : windowTitle;
        String store = DriverHelpStrings.STORE.get() + "\n" + DriverHelpStrings.STORE_NOTE.get();
        String wingetLink = winget
                ? DriverHelpStrings.WINGET.get() + "\n" + DriverHelpStrings.WINGET_NOTE.get()
                        + "\n" + WINGET_COMMAND
                : null;
        return new Content(title, DriverHelpStrings.HEADING.get(), DriverHelpStrings.BODY.get(),
                store, wingetLink, failure, DriverHelpStrings.ASK_STORE.get(),
                LayoutDirection.forLocale(I18n.locale()) == LayoutDirection.RTL);
    }

    /**
     * Whether {@code winget.exe} is on this machine. It is an app execution alias, a reparse point
     * in the user's {@code WindowsApps} directory that only {@code NOFOLLOW_LINKS} sees: read on
     * the Windows 11 guest on 2026-09-26, {@link File#exists} and a following {@link Files#exists}
     * both answered false for it while {@code where winget} found it, and the first dialog there
     * offered no winget link.
     */
    private static boolean wingetIsPresent() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank() && isThere(Path.of(dir.trim(), "winget.exe"))) {
                    return true;
                }
            }
        }
        String local = System.getenv("LOCALAPPDATA");
        return local != null && isThere(Path.of(local, "Microsoft", "WindowsApps", "winget.exe"));
    }

    private static boolean isThere(Path file) {
        try {
            return Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException unreadable) {
            // An invalid PATH entry is somebody else's mistake, not a reason to skip the dialog.
            return false;
        }
    }

    /**
     * Where each field of {@code TASKDIALOGCONFIG} and {@code TASKDIALOG_BUTTON} sits on 64-bit
     * Windows. {@code commctrl.h} declares both inside {@code pshpack1.h}, so they are packed to
     * one byte even where a pointer wants eight: the window handle is at 4, not 8, and the whole
     * is 160 bytes, not the 176 natural alignment would give. Read from mingw-w64's
     * {@code commctrl.h} (lines 5168&ndash;5298, pshpack1 to poppack) on 2026-09-26.
     */
    static final class Layout {
        static final int SIZE = 160;
        static final int CB_SIZE = 0;
        static final int HWND_PARENT = 4;
        static final int FLAGS = 20;
        static final int COMMON_BUTTONS = 24;
        static final int WINDOW_TITLE = 28;
        static final int MAIN_ICON = 36;
        static final int MAIN_INSTRUCTION = 44;
        static final int CONTENT = 52;
        static final int BUTTON_COUNT = 60;
        static final int BUTTONS = 64;
        static final int DEFAULT_BUTTON = 72;
        static final int EXPANDED_INFORMATION = 100;
        static final int CX_WIDTH = 156;

        /** {@code TASKDIALOG_BUTTON}: an int and a pointer, packed. */
        static final int BUTTON_SIZE = 12;
        static final int BUTTON_ID = 0;
        static final int BUTTON_TEXT = 4;

        private Layout() {
        }
    }

    // TASKDIALOG_FLAGS and friends, from the same header.
    static final int TDF_ALLOW_DIALOG_CANCELLATION = 0x8;
    static final int TDF_USE_COMMAND_LINKS = 0x10;
    static final int TDF_EXPAND_FOOTER_AREA = 0x40;
    static final int TDF_RTL_LAYOUT = 0x2000;
    static final int TDCBF_CLOSE_BUTTON = 0x20;
    /** {@code TD_ERROR_ICON}, {@code MAKEINTRESOURCEW(-2)}: the low word of -2. */
    static final long TD_ERROR_ICON = 0xFFFEL;

    /**
     * Fills a {@code TASKDIALOGCONFIG} at {@code config} and its buttons at {@code buttons}, from
     * addresses of UTF-16 strings already in memory ({@code NULL} for an absent one).
     *
     * @return the number of buttons written
     */
    static int writeConfig(long config, long buttons, long title, long heading, long body,
                           long store, long winget, long details, boolean rtl) {
        MemoryUtil.memSet(config, 0, Layout.SIZE);
        int count = 0;
        count = writeButton(buttons, count, STORE_BUTTON, store);
        if (winget != NULL) {
            count = writeButton(buttons, count, WINGET_BUTTON, winget);
        }
        MemoryUtil.memPutInt(config + Layout.CB_SIZE, Layout.SIZE);
        MemoryUtil.memPutInt(config + Layout.FLAGS, TDF_USE_COMMAND_LINKS
                | TDF_ALLOW_DIALOG_CANCELLATION | TDF_EXPAND_FOOTER_AREA
                | (rtl ? TDF_RTL_LAYOUT : 0));
        MemoryUtil.memPutInt(config + Layout.COMMON_BUTTONS, TDCBF_CLOSE_BUTTON);
        MemoryUtil.memPutAddressUnaligned(config + Layout.WINDOW_TITLE, title);
        MemoryUtil.memPutAddressUnaligned(config + Layout.MAIN_ICON, TD_ERROR_ICON);
        MemoryUtil.memPutAddressUnaligned(config + Layout.MAIN_INSTRUCTION, heading);
        MemoryUtil.memPutAddressUnaligned(config + Layout.CONTENT, body);
        MemoryUtil.memPutInt(config + Layout.BUTTON_COUNT, count);
        MemoryUtil.memPutAddressUnaligned(config + Layout.BUTTONS, buttons);
        MemoryUtil.memPutInt(config + Layout.DEFAULT_BUTTON, STORE_BUTTON);
        MemoryUtil.memPutAddressUnaligned(config + Layout.EXPANDED_INFORMATION, details);
        return count;
    }

    private static int writeButton(long buttons, int index, int id, long text) {
        long at = buttons + (long) index * Layout.BUTTON_SIZE;
        MemoryUtil.memPutInt(at + Layout.BUTTON_ID, id);
        MemoryUtil.memPutAddressUnaligned(at + Layout.BUTTON_TEXT, text);
        return index + 1;
    }

    /** The Win32 entry points this needs, resolved by address as {@code Uia} does. */
    private static final class Win32 {

        // MessageBoxW and ShellExecuteW flags and answers, from winuser.h and shellapi.h.
        private static final int MB_YESNO = 0x4;
        private static final int MB_ICONERROR = 0x10;
        private static final int MB_RTLREADING = 0x2000;
        private static final int MB_SETFOREGROUND = 0x10000;
        private static final int MB_RIGHT = 0x80000;
        private static final int IDYES = 6;
        private static final int SW_SHOWNORMAL = 1;
        private static final int UOI_FLAGS = 1;
        private static final int WSF_VISIBLE = 1;
        private static final int COINIT_APARTMENTTHREADED = 0x2;
        private static final int COINIT_DISABLE_OLE1DDE = 0x4;
        private static final long INVALID_HANDLE_VALUE = -1L;
        /** {@code ACTCTXW} on 64-bit Windows, naturally aligned (winbase.h has no pack there). */
        private static final int ACTCTX_SIZE = 56;
        private static final int ACTCTX_SOURCE = 8;

        private static final String COMMON_CONTROLS_6 = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
                  <dependency>
                    <dependentAssembly>
                      <assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls"
                          version="6.0.0.0" processorArchitecture="*"
                          publicKeyToken="6595b64144ccf1df" language="*"/>
                    </dependentAssembly>
                  </dependency>
                </assembly>
                """;

        private final long getConsoleWindow;
        private final long createActCtx;
        private final long activateActCtx;
        private final long deactivateActCtx;
        private final long releaseActCtx;
        private final long getProcessWindowStation;
        private final long getUserObjectInformation;
        private final long messageBox;
        private final long shellExecute;
        private final long coInitializeEx;

        private Win32(SharedLibrary kernel32, SharedLibrary user32, SharedLibrary shell32,
                      SharedLibrary ole32) {
            getConsoleWindow = kernel32.getFunctionAddress("GetConsoleWindow");
            createActCtx = kernel32.getFunctionAddress("CreateActCtxW");
            activateActCtx = kernel32.getFunctionAddress("ActivateActCtx");
            deactivateActCtx = kernel32.getFunctionAddress("DeactivateActCtx");
            releaseActCtx = kernel32.getFunctionAddress("ReleaseActCtx");
            getProcessWindowStation = user32.getFunctionAddress("GetProcessWindowStation");
            getUserObjectInformation = user32.getFunctionAddress("GetUserObjectInformationW");
            messageBox = user32.getFunctionAddress("MessageBoxW");
            shellExecute = shell32.getFunctionAddress("ShellExecuteW");
            coInitializeEx = ole32.getFunctionAddress("CoInitializeEx");
        }

        /** The entry points, or null on a machine that is not 64-bit Windows. */
        static Win32 open() {
            if (Pointer.POINTER_SIZE != 8) {
                return null;
            }
            // By bare name, so that LoadLibrary answers from the system directory and, for
            // comctl32 below, from whatever activation context is current.
            return new Win32(new WindowsLibrary("kernel32.dll"), new WindowsLibrary("user32.dll"),
                    new WindowsLibrary("shell32.dll"), new WindowsLibrary("ole32.dll"));
        }

        boolean hasConsole() {
            return getConsoleWindow != NULL && JNI.invokeP(getConsoleWindow) != NULL;
        }

        boolean stationIsVisible() {
            if (getProcessWindowStation == NULL || getUserObjectInformation == NULL) {
                return true;
            }
            long station = JNI.invokeP(getProcessWindowStation);
            if (station == NULL) {
                return true;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // USEROBJECTFLAGS: fInherit, fReserved, dwFlags.
                long flags = stack.ncalloc(4, 12, 1);
                long needed = stack.ncalloc(4, 4, 1);
                if (JNI.invokePPPI(station, UOI_FLAGS, flags, 12, needed,
                        getUserObjectInformation) == 0) {
                    return true;
                }
                return (MemoryUtil.memGetInt(flags + 8) & WSF_VISIBLE) != 0;
            }
        }

        /**
         * Shows the dialog by the first route this process allows, and logs which: the route is
         * a fact about the executable's manifest, and a report of the dialog misbehaving needs it.
         */
        Choice show(Content content) {
            long taskDialog = taskDialogIndirect();
            if (taskDialog != NULL) {
                return logged("TaskDialogIndirect, the process's own common controls 6",
                        callTaskDialog(content, taskDialog));
            }
            ActivationContext context = ActivationContext.commonControls6(this);
            if (context != null) {
                try {
                    taskDialog = taskDialogIndirect();
                    if (taskDialog != NULL) {
                        return logged("TaskDialogIndirect, common controls 6 by activation context",
                                callTaskDialog(content, taskDialog));
                    }
                } finally {
                    context.close();
                }
            }
            return logged("MessageBoxW, no TaskDialogIndirect to be had", showMessageBox(content));
        }

        private static Choice logged(String route, Choice choice) {
            LOG.log(Level.INFO, "driver dialog shown by {0}; closed with {1}", route, choice);
            return choice;
        }

        /**
         * {@code TaskDialogIndirect} from {@code comctl32.dll} as {@code LoadLibrary} answers it
         * now: version 6, which exports it, when the executable's manifest or a current
         * activation context asks for it; version 5, which does not, otherwise.
         */
        private long taskDialogIndirect() {
            try {
                return new WindowsLibrary("comctl32.dll").getFunctionAddress("TaskDialogIndirect");
            } catch (Throwable absent) {
                return NULL;
            }
        }

        private Choice showMessageBox(Content content) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                String text = content.heading() + "\n\n" + content.body() + "\n\n" + content.askStore();
                int style = MB_YESNO | MB_ICONERROR | MB_SETFOREGROUND
                        | (content.rtl() ? MB_RTLREADING | MB_RIGHT : 0);
                int answer = JNI.invokePPPI(NULL, utf16(stack, text), utf16(stack, content.title()),
                        style, messageBox);
                return answer == IDYES ? Choice.STORE : Choice.CLOSE;
            }
        }

        private Choice callTaskDialog(Content content, long taskDialog) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long config = stack.ncalloc(8, Layout.SIZE, 1);
                long buttons = stack.ncalloc(8, 2 * Layout.BUTTON_SIZE, 1);
                writeConfig(config, buttons, utf16(stack, content.title()),
                        utf16(stack, content.heading()), utf16(stack, content.body()),
                        utf16(stack, content.store()), utf16(stack, content.winget()),
                        utf16(stack, content.details()), content.rtl());
                long pressed = stack.ncalloc(4, 4, 1);
                int result = JNI.invokePPPPI(config, pressed, NULL, NULL, taskDialog);
                if (result != 0) {
                    LOG.log(Level.WARNING, "TaskDialogIndirect failed: HRESULT 0x{0}",
                            Integer.toHexString(result));
                    return Choice.CLOSE;
                }
                return switch (MemoryUtil.memGetInt(pressed)) {
                    case STORE_BUTTON -> Choice.STORE;
                    case WINGET_BUTTON -> Choice.WINGET;
                    default -> Choice.CLOSE;
                };
            }
        }

        void openStore() {
            if (shellOpen(STORE_URI, null) <= 32) {
                // No Store application to take the protocol: the same page on the web.
                shellOpen(STORE_WEB, null);
            }
        }

        void runWinget() {
            // cmd /k, so the window stays after winget ends and the person can read the outcome
            // (and answer winget's own agreement prompt): nothing here decides it for them.
            shellOpen("cmd.exe", "/k " + WINGET_COMMAND);
        }

        private long shellOpen(String file, String parameters) {
            if (coInitializeEx != NULL) {
                // ShellExecute asks for COM on the calling thread, since a protocol handler may be
                // one; S_FALSE and RPC_E_CHANGED_MODE both leave it usable.
                JNI.invokePI(NULL, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE, coInitializeEx);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                long result = JNI.invokePPPPPP(NULL, utf16(stack, "open"), utf16(stack, file),
                        utf16(stack, parameters), NULL, SW_SHOWNORMAL, shellExecute);
                LOG.log(Level.INFO, "ShellExecuteW({0}) answered {1}", file, result);
                return result;
            }
        }

        private static long utf16(MemoryStack stack, String text) {
            return text == null ? NULL : MemoryUtil.memAddress(stack.UTF16(text));
        }

        /** An activated context asking for common controls 6, released by {@link #close}. */
        private record ActivationContext(Win32 win32, long handle, long cookie, Path manifest) {

            static ActivationContext commonControls6(Win32 win32) {
                if (win32.createActCtx == NULL || win32.activateActCtx == NULL) {
                    return null;
                }
                Path manifest;
                try {
                    manifest = Files.createTempFile("limn-common-controls-", ".manifest");
                    Files.writeString(manifest, COMMON_CONTROLS_6, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOG.log(Level.DEBUG, "no manifest for common controls 6", e);
                    return null;
                }
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    long actctx = stack.ncalloc(8, ACTCTX_SIZE, 1);
                    MemoryUtil.memPutInt(actctx, ACTCTX_SIZE);
                    MemoryUtil.memPutAddress(actctx + ACTCTX_SOURCE,
                            MemoryUtil.memAddress(stack.UTF16(manifest.toString())));
                    long handle = JNI.invokePP(actctx, win32.createActCtx);
                    if (handle == INVALID_HANDLE_VALUE || handle == NULL) {
                        deleteQuietly(manifest);
                        return null;
                    }
                    long cookie = stack.ncalloc(8, 8, 1);
                    if (JNI.invokePPI(handle, cookie, win32.activateActCtx) == 0) {
                        JNI.invokePV(handle, win32.releaseActCtx);
                        deleteQuietly(manifest);
                        return null;
                    }
                    return new ActivationContext(win32, handle, MemoryUtil.memGetAddress(cookie),
                            manifest);
                }
            }

            void close() {
                JNI.invokePI(0, cookie, win32.deactivateActCtx);
                JNI.invokePV(handle, win32.releaseActCtx);
                deleteQuietly(manifest);
            }

            private static void deleteQuietly(Path path) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A temporary file of 400 bytes; the temp directory's own cleanup owns it now.
                }
            }
        }
    }
}
