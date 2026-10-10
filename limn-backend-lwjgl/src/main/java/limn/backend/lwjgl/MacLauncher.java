package limn.backend.lwjgl;

import limn.backend.lwjgl.internal.ObjC;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Path;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Hands an address or a file to macOS through {@code NSWorkspace}, on a thread of its own: the
 * workspace waits on the program it starts. A thread that is not AppKit's has no autorelease
 * pool, so each request makes one for the objects it creates and drains it.
 */
final class MacLauncher {

    private static final System.Logger LOG = System.getLogger(MacLauncher.class.getName());

    private MacLauncher() {
    }

    static boolean openAddress(String address) {
        return pooled(() -> open(ObjC.msg(ObjC.cls("NSURL"), "URLWithString:", string(address)), address));
    }

    static boolean openFile(Path file) {
        return pooled(() -> open(fileUrl(file), file.toString()));
    }

    static boolean showInFolder(Path file) {
        return pooled(() -> {
            long url = fileUrl(file);
            if (url == NULL) {
                return false;
            }
            long urls = ObjC.msg(ObjC.cls("NSArray"), "arrayWithObject:", url);
            ObjC.msgVoid(workspace(), "activateFileViewerSelectingURLs:", urls);
            return true;
        });
    }

    private static boolean pooled(java.util.function.BooleanSupplier request) {
        if (!ObjC.isAvailable()) {
            return false;
        }
        long pool = ObjC.msg(ObjC.msg(ObjC.cls("NSAutoreleasePool"), "alloc"), "init");
        try {
            return request.getAsBoolean();
        } finally {
            ObjC.msgVoid(pool, "drain");
        }
    }

    private static boolean open(long url, String what) {
        if (url == NULL) {
            LOG.log(System.Logger.Level.WARNING, "not opened, the system makes no URL of {0}", what);
            return false;
        }
        // A BOOL comes back in the low byte of the register; the rest is not the answer's.
        boolean taken = (ObjC.msg(workspace(), "openURL:", url) & 0xFF) != 0;
        if (!taken) {
            LOG.log(System.Logger.Level.WARNING, "the workspace did not open {0}", what);
        }
        return taken;
    }

    private static long workspace() {
        return ObjC.msg(ObjC.cls("NSWorkspace"), "sharedWorkspace");
    }

    private static long fileUrl(Path file) {
        return ObjC.msg(ObjC.cls("NSURL"), "fileURLWithPath:", string(file.toString()));
    }

    private static long string(String text) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return ObjC.msg(ObjC.cls("NSString"), "stringWithUTF8String:",
                    MemoryUtil.memAddress(stack.UTF8(text)));
        }
    }
}
