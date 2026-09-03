package limn.backend.lwjgl;

import limn.backend.FileDialogs;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Native file/folder dialogs via tinyfiledialogs (bundled with LWJGL; no AWT):
 * the platform's own chooser on Windows, macOS and Linux (zenity/kdialog).
 * Calls block the UI thread while the system dialog is open (system-modal,
 * exactly like any native application's file chooser).
 *
 * <p>Only on Windows is the chooser ours: there tinyfd calls a Win32 common
 * dialog, which pumps its own message loop on this thread. On macOS it writes
 * an AppleScript {@code choose file} command and reads one line back from a
 * child {@code osascript}; on Linux it runs zenity, kdialog, yad or Xdialog the
 * same way. On both, the panel belongs to another process; nothing here can
 * reach into it, and while it is up this process draws nothing at all, which is
 * why every entry point presents first.
 */
final class TinyFdDialogs implements FileDialogs {

    private static final System.Logger LOG = System.getLogger(TinyFdDialogs.class.getName());

    private final LwjglBackend backend;

    TinyFdDialogs(LwjglBackend backend) {
        this.backend = backend;
    }

    /**
     * Extracts and links the tinyfiledialogs shared library on a worker, by
     * class-initializing its binding there. Meant for backend startup; a repeat
     * call costs one worker task and nothing else, since class initialization
     * happens once per JVM. Does nothing without an installed UI runtime; the
     * first dialog then links the library itself, as it would have anyway.
     *
     * <p>This is the only part of a first chooser's cost that Java can move off
     * the click, and it is the small part: single-digit milliseconds. What it
     * does <b>not</b> buy back, on macOS or Linux, is the two probes tinyfd
     * caches in C statics during its first dialog: locating the helper program,
     * and on macOS asking a whole {@code osascript} process for the system
     * version, which is the expensive one. Those run inside the blocking call,
     * in the native, and no entry point the binding exposes reaches them without
     * also putting a panel, a notification or a beep in front of the user. Do
     * not describe this as pre-warming the dialog.
     */
    static void warmNative() {
        if (!limn.concurrent.Ui.isInstalled()) {
            return;
        }
        limn.concurrent.Ui.async(() -> TinyFileDialogs.tinyfd_getGlobalChar("tinyfd_version"))
                .thenAccept(version -> LOG.log(System.Logger.Level.DEBUG,
                        "tinyfiledialogs {0} linked (background warm-up)", version))
                .exceptionally(error -> {
                    // A distribution without the tinyfd native is a supported
                    // shape; the dialog call stays where that surfaces, so this
                    // must not be the thing that reports it.
                    LOG.log(System.Logger.Level.DEBUG,
                            "tinyfiledialogs warm-up failed; file dialogs will link on first use",
                            error);
                    return null;
                });
    }

    /**
     * What every entry point owes before it hands the UI thread to a native
     * call that returns only when the user is done: the thread check, then the
     * frame. The handler that asked for a chooser has already changed what the
     * window should look like (a button clearing its pressed state on the
     * release that precedes the click), and this is the last chance to draw it,
     * because the loop's render phase is on the far side of the block.
     */
    private void beforeBlockingCall() {
        backend.uiRuntime().checkUiThread();
        backend.presentBeforeBlocking();
    }

    @Override
    public Optional<Path> openFile(String title, Path initial, Filter filter) {
        beforeBlockingCall();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MacFilterPatterns.Bounded in = bound(title, initial);
            String picked = TinyFileDialogs.tinyfd_openFileDialog(
                    in.title(), in.location(), patterns(stack, filter, in),
                    description(filter), false);
            return picked == null ? Optional.empty() : Optional.of(Path.of(picked));
        }
    }

    @Override
    public List<Path> openFiles(String title, Path initial, Filter filter) {
        beforeBlockingCall();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MacFilterPatterns.Bounded in = bound(title, initial);
            String picked = TinyFileDialogs.tinyfd_openFileDialog(
                    in.title(), in.location(), patterns(stack, filter, in),
                    description(filter), true);
            if (picked == null) {
                return List.of();
            }
            List<Path> paths = new ArrayList<>();
            for (String one : picked.split("\\|")) { // tinyfd separates a multi-selection with '|'
                paths.add(Path.of(one));
            }
            return List.copyOf(paths);
        }
    }

    @Override
    public Optional<Path> saveFile(String title, Path initial, Filter filter) {
        beforeBlockingCall();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            MacFilterPatterns.Bounded in = bound(title, initial);
            String picked = TinyFileDialogs.tinyfd_saveFileDialog(
                    in.title(), in.location(), patterns(stack, filter, in), description(filter));
            return picked == null ? Optional.empty() : Optional.of(Path.of(picked));
        }
    }

    @Override
    public Optional<Path> chooseFolder(String title, Path initial) {
        beforeBlockingCall();
        MacFilterPatterns.Bounded in = bound(title, initial);
        String picked = TinyFileDialogs.tinyfd_selectFolderDialog(in.title(), in.location());
        return picked == null ? Optional.empty() : Optional.of(Path.of(picked));
    }

    /**
     * The title and location as the native may see them. Off Windows, tinyfd
     * builds the helper's command line by strcat into a fixed buffer, so the
     * two are cut to what fits (see {@link MacFilterPatterns#fit}); the
     * accounting is the macOS one, the larger of the two helpers' scaffolds,
     * which keeps the Linux command in bounds by a wider margin. Windows takes
     * both through wide-character buffers the binding sizes to the input.
     */
    private static MacFilterPatterns.Bounded bound(String title, Path initial) {
        String location = initialPath(initial);
        return Platform.get() == Platform.WINDOWS
                ? new MacFilterPatterns.Bounded(title, location)
                : MacFilterPatterns.fit(title, location);
    }

    /** tinyfd takes "" for "no preference" and a trailing separator to mean a directory. */
    private static String initialPath(Path initial) {
        if (initial == null) {
            return "";
        }
        String text = initial.toAbsolutePath().toString();
        return Files.isDirectory(initial) && !text.endsWith(File.separator)
                ? text + File.separator
                : text;
    }

    private static PointerBuffer patterns(MemoryStack stack, Filter filter,
                                          MacFilterPatterns.Bounded in) {
        if (filter == null || filter.patterns().isEmpty()) {
            return null;
        }
        List<String> patterns = filter.patterns();
        if (Platform.get() == Platform.MACOSX) {
            // macOS reads each pattern minus a leading "*." and matches the
            // dialog's type list by UTI, not filename: patterns too short to
            // strip are dropped first (a bare "*" voids the whole filter), then
            // each *.ext gains a twin naming the system's derived type so that
            // unregistered extensions filter too. Title and location bound how
            // much fits in tinyfd's fixed command buffer (see MacFilterPatterns).
            patterns = MacFilterPatterns.sanitize(patterns);
            if (patterns.isEmpty()) {
                return null;
            }
            patterns = MacFilterPatterns.expand(patterns, in.title(), in.location());
        }
        PointerBuffer buffer = stack.mallocPointer(patterns.size());
        for (String pattern : patterns) {
            buffer.put(stack.UTF8(pattern));
        }
        return buffer.flip();
    }

    private static String description(Filter filter) {
        return filter == null ? null : filter.description();
    }
}
