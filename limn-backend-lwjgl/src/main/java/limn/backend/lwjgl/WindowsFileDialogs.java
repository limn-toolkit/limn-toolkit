package limn.backend.lwjgl;

import limn.backend.FileDialogs;
import limn.concurrent.Ui;
import limn.concurrent.internal.Threads;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Windows' own open and save dialogs ({@code IFileOpenDialog}, {@code IFileSaveDialog}), owned by
 * the window that asked. {@code IModalWindow::Show} runs the dialog's own message loop and returns
 * only when the user is done, so it runs on a thread of its own, initialized as a single-threaded
 * apartment as the shell requires: the owner is disabled while it is up, as a dialog's owner is, and
 * the application's loop, its other windows and its timers go on. The answer is posted to the UI
 * thread.
 *
 * <p>Every call is a function pointer read from the object's vtable, through LWJGL's JNI
 * trampolines: no binding for the shell exists in LWJGL, and the slots are the ones
 * {@code ShObjIdl_core.h} declares, counted from {@code IUnknown}'s three.
 */
final class WindowsFileDialogs {

    private static final System.Logger LOG = System.getLogger(WindowsFileDialogs.class.getName());

    // IUnknown
    private static final int RELEASE = 2;
    // IModalWindow
    private static final int SHOW = 3;
    // IFileDialog
    private static final int SET_FILE_TYPES = 4;
    private static final int SET_OPTIONS = 9;
    private static final int GET_OPTIONS = 10;
    private static final int SET_FOLDER = 12;
    private static final int SET_FILE_NAME = 15;
    private static final int SET_TITLE = 17;
    private static final int GET_RESULT = 20;
    // IFileOpenDialog
    private static final int GET_RESULTS = 27;
    // IShellItem
    private static final int GET_DISPLAY_NAME = 5;
    // IShellItemArray
    private static final int GET_COUNT = 7;
    private static final int GET_ITEM_AT = 8;

    private static final int FOS_OVERWRITEPROMPT = 0x2;
    private static final int FOS_PICKFOLDERS = 0x20;
    private static final int FOS_FORCEFILESYSTEM = 0x40;
    private static final int FOS_ALLOWMULTISELECT = 0x200;
    private static final int FOS_PATHMUSTEXIST = 0x800;
    private static final int FOS_FILEMUSTEXIST = 0x1000;
    private static final long SIGDN_FILESYSPATH = 0x80058000L;
    private static final int CLSCTX_INPROC_SERVER = 1;
    private static final int COINIT_APARTMENTTHREADED = 2;
    /** {@code HRESULT_FROM_WIN32(ERROR_CANCELLED)}: what {@code Show} answers when the user cancels. */
    private static final int CANCELLED = 0x800704C7;

    private static final String CLSID_FILE_OPEN_DIALOG = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7";
    private static final String CLSID_FILE_SAVE_DIALOG = "C0B4E2F3-BA21-4773-8DBA-335EC946EB8B";
    private static final String IID_FILE_OPEN_DIALOG = "D57C7288-D4AD-4768-BE02-9D969532D960";
    private static final String IID_FILE_SAVE_DIALOG = "84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB";
    private static final String IID_SHELL_ITEM = "43826D1E-E718-42EE-BC55-A1E261C37BFE";

    private WindowsFileDialogs() {
    }

    /** The two libraries a dialog calls, opened by the first dialog and not by each. */
    private static final class Libraries {
        static final SharedLibrary OLE32 = APIUtil.apiCreateLibrary("ole32");
        static final SharedLibrary SHELL32 = APIUtil.apiCreateLibrary("shell32");
    }

    /**
     * Opens the dialog on a thread of its own, owned by {@code hwnd}.
     *
     * @param answer the chosen paths, empty when cancelled or when the dialog could not be made;
     *               called on the UI thread
     */
    static void open(long hwnd, WindowFileDialogs.Kind kind, String title, @Nullable Path initial,
                     FileDialogs.@Nullable Filter filter, Consumer<List<Path>> answer) {
        Threads.daemon("limn-file-dialog", () -> {
            List<Path> chosen = List.of();
            try {
                chosen = run(hwnd, kind, title, initial, filter);
            } catch (Throwable error) {
                LOG.log(Level.WARNING, "the Windows file dialog could not be shown", error);
            }
            List<Path> result = chosen;
            Ui.post(() -> answer.accept(result));
        });
    }

    private static List<Path> run(long hwnd, WindowFileDialogs.Kind kind, String title, @Nullable Path initial,
                                  FileDialogs.@Nullable Filter filter) {
        SharedLibrary ole32 = Libraries.OLE32;
        SharedLibrary shell32 = Libraries.SHELL32;
        long coInitializeEx = ole32.getFunctionAddress("CoInitializeEx");
        long coUninitialize = ole32.getFunctionAddress("CoUninitialize");
        long coCreateInstance = ole32.getFunctionAddress("CoCreateInstance");
        long coTaskMemFree = ole32.getFunctionAddress("CoTaskMemFree");
        long fromPath = shell32.getFunctionAddress("SHCreateItemFromParsingName");
        int init = JNI.invokePPI(NULL, COINIT_APARTMENTTHREADED, coInitializeEx);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean save = kind == WindowFileDialogs.Kind.SAVE;
            ByteBuffer clsid = guid(stack, save ? CLSID_FILE_SAVE_DIALOG : CLSID_FILE_OPEN_DIALOG);
            ByteBuffer iid = guid(stack, save ? IID_FILE_SAVE_DIALOG : IID_FILE_OPEN_DIALOG);
            var out = stack.mallocPointer(1);
            int hr = JNI.invokePPPPPI(MemoryUtil.memAddress(clsid), NULL, CLSCTX_INPROC_SERVER,
                    MemoryUtil.memAddress(iid), MemoryUtil.memAddress(out), coCreateInstance);
            if (hr < 0 || out.get(0) == NULL) {
                throw new IllegalStateException("CoCreateInstance failed: 0x" + Integer.toHexString(hr));
            }
            long dialog = out.get(0);
            try {
                var options = stack.mallocInt(1);
                call(dialog, GET_OPTIONS, MemoryUtil.memAddress(options));
                int flags = options.get(0) | FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST;
                switch (kind) {
                    case OPEN -> flags |= FOS_FILEMUSTEXIST;
                    case OPEN_MANY -> flags |= FOS_FILEMUSTEXIST | FOS_ALLOWMULTISELECT;
                    case FOLDER -> flags |= FOS_PICKFOLDERS;
                    case SAVE -> flags |= FOS_OVERWRITEPROMPT;
                }
                call(dialog, SET_OPTIONS, flags);
                call(dialog, SET_TITLE, MemoryUtil.memAddress(stack.UTF16(title)));
                if (filter != null && kind != WindowFileDialogs.Kind.FOLDER && !filter.patterns().isEmpty()) {
                    // COMDLG_FILTERSPEC { LPCWSTR name; LPCWSTR spec; }, then "all files" after it.
                    long specs = stack.nmalloc(8, 32);
                    MemoryUtil.memPutAddress(specs, MemoryUtil.memAddress(stack.UTF16(filter.description())));
                    MemoryUtil.memPutAddress(specs + 8,
                            MemoryUtil.memAddress(stack.UTF16(String.join(";", filter.patterns()))));
                    MemoryUtil.memPutAddress(specs + 16, MemoryUtil.memAddress(stack.UTF16("*.*")));
                    MemoryUtil.memPutAddress(specs + 24, MemoryUtil.memAddress(stack.UTF16("*.*")));
                    JNI.invokePPPI(dialog, 2L, specs, slot(dialog, SET_FILE_TYPES));
                }
                place(stack, dialog, kind, initial, fromPath);
                hr = JNI.invokePPI(dialog, hwnd, slot(dialog, SHOW));
                if (hr == CANCELLED) {
                    return List.of();
                }
                if (hr < 0) {
                    throw new IllegalStateException("IModalWindow::Show failed: 0x" + Integer.toHexString(hr));
                }
                List<Path> chosen = new ArrayList<>();
                if (kind == WindowFileDialogs.Kind.OPEN_MANY) {
                    var items = stack.mallocPointer(1);
                    if (JNI.invokePPI(dialog, MemoryUtil.memAddress(items), slot(dialog, GET_RESULTS)) >= 0) {
                        long array = items.get(0);
                        var count = stack.mallocInt(1);
                        JNI.invokePPI(array, MemoryUtil.memAddress(count), slot(array, GET_COUNT));
                        for (int i = 0; i < count.get(0); i++) {
                            var item = stack.mallocPointer(1);
                            if (JNI.invokePPPI(array, i, MemoryUtil.memAddress(item), slot(array, GET_ITEM_AT)) >= 0) {
                                addPath(stack, chosen, item.get(0), coTaskMemFree);
                            }
                        }
                        release(array);
                    }
                } else {
                    var item = stack.mallocPointer(1);
                    if (JNI.invokePPI(dialog, MemoryUtil.memAddress(item), slot(dialog, GET_RESULT)) >= 0) {
                        addPath(stack, chosen, item.get(0), coTaskMemFree);
                    }
                }
                return chosen;
            } finally {
                release(dialog);
            }
        } finally {
            if (init >= 0) {
                JNI.invokeV(coUninitialize);
            }
        }
    }

    /** The folder the dialog opens in, and for a save the name it suggests. */
    private static void place(MemoryStack stack, long dialog, WindowFileDialogs.Kind kind, @Nullable Path initial,
                              long fromPath) {
        if (initial == null) {
            return;
        }
        Path folder = Files.isDirectory(initial) ? initial : initial.getParent();
        String name = Files.isDirectory(initial) || initial.getFileName() == null
                ? null : initial.getFileName().toString();
        if (folder != null && Files.isDirectory(folder)) {
            var item = stack.mallocPointer(1);
            ByteBuffer iid = guid(stack, IID_SHELL_ITEM);
            int hr = JNI.invokePPPPI(MemoryUtil.memAddress(stack.UTF16(folder.toAbsolutePath().toString())), NULL,
                    MemoryUtil.memAddress(iid), MemoryUtil.memAddress(item), fromPath);
            if (hr >= 0 && item.get(0) != NULL) {
                call(dialog, SET_FOLDER, item.get(0));
                release(item.get(0));
            }
        }
        if (kind == WindowFileDialogs.Kind.SAVE && name != null) {
            call(dialog, SET_FILE_NAME, MemoryUtil.memAddress(stack.UTF16(name)));
        }
    }

    private static void addPath(MemoryStack stack, List<Path> into, long item, long coTaskMemFree) {
        var name = stack.mallocPointer(1);
        if (JNI.invokePPPI(item, SIGDN_FILESYSPATH, MemoryUtil.memAddress(name), slot(item, GET_DISPLAY_NAME)) >= 0
                && name.get(0) != NULL) {
            into.add(Path.of(MemoryUtil.memUTF16(name.get(0))));
            JNI.invokePV(name.get(0), coTaskMemFree);
        }
        release(item);
    }

    private static int call(long object, int slot, long argument) {
        return JNI.invokePPI(object, argument, slot(object, slot));
    }

    private static void release(long object) {
        JNI.invokePI(object, slot(object, RELEASE));
    }

    private static long slot(long object, int slot) {
        return MemoryUtil.memGetAddress(MemoryUtil.memGetAddress(object) + (long) slot * Long.BYTES);
    }

    /** A GUID in its in-memory layout: Data1 to Data3 little-endian, Data4 as written. */
    static ByteBuffer guid(MemoryStack stack, String text) {
        String hex = text.replace("-", "");
        ByteBuffer guid = stack.malloc(16).order(ByteOrder.LITTLE_ENDIAN);
        guid.putInt(0, (int) Long.parseLong(hex.substring(0, 8), 16));
        guid.putShort(4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        guid.putShort(6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) {
            guid.put(8 + i, (byte) Integer.parseInt(hex.substring(16 + 2 * i, 18 + 2 * i), 16));
        }
        return guid;
    }
}
