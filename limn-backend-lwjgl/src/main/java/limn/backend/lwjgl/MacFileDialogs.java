package limn.backend.lwjgl;

import limn.backend.FileDialogs;
import limn.backend.lwjgl.a11y.ClosureArgs;
import limn.backend.lwjgl.internal.ObjC;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.lwjgl.system.libffi.LibFFI;

import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * macOS's own open and save panels, as a sheet on the window that asked: {@code NSOpenPanel} and
 * {@code NSSavePanel} with {@code -beginSheetModalForWindow:completionHandler:}. The call returns at
 * once and the application keeps running; the answer comes to the completion handler on the main
 * thread, which is the UI thread, when the user picks or cancels.
 *
 * <p><b>The completion handler is a block.</b> LWJGL builds no Objective-C blocks, so this lays one
 * out itself: a global block (flags {@code BLOCK_IS_GLOBAL}, isa {@code _NSConcreteGlobalBlock}),
 * which copying leaves where it is, whose invoke is one closure for the process; the block's own
 * address says which request it answers.
 *
 * <p>A window that is not on screen (a capture, a hidden window) cannot carry a sheet; its panel
 * opens as a window of its own, still without blocking.
 */
final class MacFileDialogs {

    private static final System.Logger LOG = System.getLogger(MacFileDialogs.class.getName());

    /** What is asked for. */
    enum Kind { OPEN, OPEN_MANY, SAVE, FOLDER }

    /** {@code NSModalResponseOK}. */
    private static final long RESPONSE_OK = 1;
    /** {@code BLOCK_IS_GLOBAL}. */
    private static final int BLOCK_IS_GLOBAL = 1 << 28;
    /** A block literal on a 64-bit runtime: isa, flags, reserved, invoke, descriptor. */
    private static final int BLOCK_SIZE = 32;

    /** The open requests, by the address of the block that answers each, with its panel. */
    private static final Map<Long, Pending> PENDING = new HashMap<>();
    private static @Nullable Completion invoke;
    private static long descriptor = NULL;
    private static long globalBlockIsa = NULL;

    private record Pending(long panel, Kind kind, Consumer<List<Path>> answer) {
    }

    private MacFileDialogs() {
    }

    /**
     * Opens a panel on {@code owner}.
     *
     * @param nsWindow the owner's {@code NSWindow}
     * @param onScreen whether the owner is shown, which is what a sheet needs
     * @param answer   the chosen paths, empty when cancelled; called on the UI thread, after the
     *                 event that answered
     * @return whether a panel opened; {@code false} is logged, and the caller answers empty
     */
    static boolean open(long nsWindow, boolean onScreen, Kind kind, String title, @Nullable Path initial,
                        FileDialogs.@Nullable Filter filter, Consumer<List<Path>> answer) {
        try {
            if (!ready()) {
                return false;
            }
            long panel = kind == Kind.SAVE
                    ? ObjC.msg(ObjC.cls("NSSavePanel"), "savePanel")
                    : ObjC.msg(ObjC.cls("NSOpenPanel"), "openPanel");
            if (panel == NULL) {
                return false;
            }
            ObjC.msg(panel, "retain"); // held until it answers
            ObjC.msgVoid(panel, "setTitle:", string(title));
            ObjC.msgVoid(panel, "setMessage:", string(title)); // a sheet shows no title
            if (kind != Kind.SAVE) {
                ObjC.msgVoid(panel, "setCanChooseFiles:", kind == Kind.FOLDER ? 0L : 1L);
                ObjC.msgVoid(panel, "setCanChooseDirectories:", kind == Kind.FOLDER ? 1L : 0L);
                ObjC.msgVoid(panel, "setAllowsMultipleSelection:", kind == Kind.OPEN_MANY ? 1L : 0L);
                ObjC.msgVoid(panel, "setCanCreateDirectories:", kind == Kind.FOLDER ? 1L : 0L);
            } else {
                ObjC.msgVoid(panel, "setCanCreateDirectories:", 1L);
            }
            place(panel, kind, initial);
            if (kind == Kind.OPEN || kind == Kind.OPEN_MANY) {
                restrict(panel, filter);
            }
            long block = MemoryUtil.nmemCallocChecked(1, BLOCK_SIZE);
            MemoryUtil.memPutAddress(block, globalBlockIsa);
            MemoryUtil.memPutInt(block + 8, BLOCK_IS_GLOBAL);
            MemoryUtil.memPutInt(block + 12, 0);
            MemoryUtil.memPutAddress(block + 16, invoke.address());
            MemoryUtil.memPutAddress(block + 24, descriptor);
            PENDING.put(block, new Pending(panel, kind, answer));
            if (onScreen && nsWindow != NULL) {
                ObjC.msgVoid(panel, "beginSheetModalForWindow:completionHandler:", nsWindow, block);
            } else {
                ObjC.msgVoid(panel, "beginWithCompletionHandler:", block);
            }
            return true;
        } catch (RuntimeException | LinkageError error) {
            LOG.log(Level.WARNING, "the macOS file panel could not be opened", error);
            return false;
        }
    }

    private static boolean ready() {
        if (invoke != null) {
            return true;
        }
        if (!ObjC.isAvailable()) {
            return false;
        }
        SharedLibrary system = APIUtil.apiCreateLibrary("/usr/lib/libSystem.B.dylib");
        globalBlockIsa = system.getFunctionAddress("_NSConcreteGlobalBlock");
        if (globalBlockIsa == NULL) {
            LOG.log(Level.WARNING, "libSystem exports no _NSConcreteGlobalBlock; file panels stay unavailable");
            return false;
        }
        // Loaded once so the UTType class exists; the framework ships with every supported macOS.
        APIUtil.apiCreateLibrary("/System/Library/Frameworks/UniformTypeIdentifiers.framework/UniformTypeIdentifiers");
        descriptor = MemoryUtil.nmemCallocChecked(1, 16); // reserved 0, size of the literal
        MemoryUtil.memPutLong(descriptor + 8, BLOCK_SIZE);
        invoke = new Completion() {
            @Override
            public void invoke(long block, long response) {
                answered(block, response);
            }
        };
        return true;
    }

    private static void answered(long block, long response) {
        Pending pending = PENDING.remove(block);
        if (pending == null) {
            return;
        }
        List<Path> chosen = new ArrayList<>();
        try {
            if (response == RESPONSE_OK) {
                if (pending.kind == Kind.SAVE) {
                    addPath(chosen, ObjC.msg(pending.panel, "URL"));
                } else {
                    long urls = ObjC.msg(pending.panel, "URLs");
                    long count = urls == NULL ? 0 : ObjC.msg(urls, "count");
                    for (long i = 0; i < count; i++) {
                        addPath(chosen, ObjC.msg(urls, "objectAtIndex:", i));
                    }
                }
            }
        } catch (RuntimeException error) {
            LOG.log(Level.ERROR, "the macOS file panel's answer could not be read", error);
        }
        // After AppKit's call returns, not in it: -[NSSavePanel didEndSheet:…] releases the block
        // it called once the call is over, so a block freed here was read after it was freed (a
        // crash in objc_release, measured). The panel is let go at the same time, and the answer
        // is delivered then, outside the event that answered.
        limn.concurrent.Ui.post(() -> {
            ObjC.msgVoid(pending.panel, "release");
            MemoryUtil.nmemFree(block);
            pending.answer.accept(List.copyOf(chosen));
        });
    }

    private static void addPath(List<Path> into, long url) {
        if (url == NULL) {
            return;
        }
        long path = ObjC.msg(url, "path");
        if (path != NULL) {
            into.add(Path.of(MemoryUtil.memUTF8(ObjC.msg(path, "UTF8String"))));
        }
    }

    /** Where the panel opens, and for a save panel the name it suggests. */
    private static void place(long panel, Kind kind, @Nullable Path initial) {
        if (initial == null) {
            return;
        }
        Path folder;
        String name = null;
        if (Files.isDirectory(initial)) {
            folder = initial;
        } else {
            folder = initial.getParent();
            name = initial.getFileName() == null ? null : initial.getFileName().toString();
        }
        if (folder != null && Files.isDirectory(folder)) {
            long url = ObjC.msg(ObjC.cls("NSURL"), "fileURLWithPath:", string(folder.toAbsolutePath().toString()));
            ObjC.msgVoid(panel, "setDirectoryURL:", url);
        }
        if (kind == Kind.SAVE && name != null) {
            ObjC.msgVoid(panel, "setNameFieldStringValue:", string(name));
        }
    }

    /** The types an open panel admits: one per {@code *.ext} pattern, a dynamic one for an unregistered extension. */
    private static void restrict(long panel, FileDialogs.@Nullable Filter filter) {
        if (filter == null) {
            return;
        }
        long types = ObjC.msg(ObjC.cls("NSMutableArray"), "array");
        for (String pattern : filter.patterns()) {
            String extension = simpleExtension(pattern);
            if (extension == null) {
                return; // a pattern that is no plain extension ("*"): admit everything
            }
            long type = ObjC.msg(ObjC.cls("UTType"), "typeWithFilenameExtension:", string(extension));
            if (type != NULL) {
                ObjC.msgVoid(types, "addObject:", type);
            }
        }
        if (ObjC.msg(types, "count") > 0) {
            ObjC.msgVoid(panel, "setAllowedContentTypes:", types);
        }
    }

    /** {@code "*.png"} → {@code "png"}; anything else → {@code null}. */
    static @Nullable String simpleExtension(String pattern) {
        if (!pattern.startsWith("*.")) {
            return null;
        }
        String extension = pattern.substring(2);
        return extension.isEmpty() || extension.contains("*") || extension.contains("?")
                || extension.contains("/") ? null : extension;
    }

    private static long string(String text) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return ObjC.msg(ObjC.cls("NSString"), "stringWithUTF8String:",
                    MemoryUtil.memAddress(stack.UTF8(text)));
        }
    }

    /** {@code void (^)(NSModalResponse)}: the block's invoke, which takes the block first. */
    private interface CompletionI extends CallbackI {
        Callback.Descriptor DESCRIPTOR = new Callback.Descriptor(CompletionI.class,
                MethodHandles.lookup(), APIUtil.apiCreateCIF(LibFFI.ffi_type_void,
                        LibFFI.ffi_type_pointer, LibFFI.ffi_type_sint64));

        @Override
        default Callback.Descriptor getDescriptor() {
            return DESCRIPTOR;
        }

        @Override
        default void callback(long ret, long args) {
            invoke(ClosureArgs.pointer(args, 0), MemoryUtil.memGetLong(MemoryUtil.memGetAddress(args + 8)));
        }

        void invoke(long block, long response);
    }

    private abstract static class Completion extends Callback implements CompletionI {
        Completion() {
            super(CompletionI.DESCRIPTOR);
        }
    }
}
