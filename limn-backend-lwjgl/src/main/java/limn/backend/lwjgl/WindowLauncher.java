package limn.backend.lwjgl;

import limn.backend.Platform;
import limn.backend.lwjgl.a11y.linux.DesktopOpener;
import limn.backend.spi.LauncherPeer;
import limn.concurrent.Ui;
import limn.concurrent.internal.Threads;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/**
 * A window's way to the system's programs: each request is handed over off the UI thread, since
 * every system's call waits on the program it starts (a sixth of a second for a browser already
 * open, measured on macOS), and answered on it after the event that asked.
 */
final class WindowLauncher implements LauncherPeer {

    private static final Platform PLATFORM = Platform.current();

    private final LwjglWindow window;

    WindowLauncher(LwjglWindow window) {
        this.window = window;
    }

    @Override
    public CompletionStage<Boolean> openAddress(URI address) {
        String text = address.toASCIIString();
        if (PLATFORM.isMacOs()) {
            return onItsOwnThread(() -> MacLauncher.openAddress(text));
        }
        if (PLATFORM.isWindows()) {
            long owner = owner();
            return onItsOwnThread(() -> WindowsLauncher.open(owner, text));
        }
        String session = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        String parent = window.isClosed() ? "" : window.portalParent();
        java.util.function.Supplier<String> token = window.launchToken();
        return onItsOwnThread(() -> DesktopOpener.openAddress(session, parent, address, token.get()));
    }

    @Override
    public CompletionStage<Boolean> openFile(Path file) {
        if (PLATFORM.isMacOs()) {
            return onItsOwnThread(() -> MacLauncher.openFile(file));
        }
        if (PLATFORM.isWindows()) {
            long owner = owner();
            return onItsOwnThread(() -> WindowsLauncher.open(owner, file.toString()));
        }
        java.util.function.Supplier<String> token = window.launchToken();
        return onItsOwnThread(() -> DesktopOpener.openFile(file, token.get()));
    }

    @Override
    public CompletionStage<Boolean> showInFolder(Path file) {
        if (PLATFORM.isMacOs()) {
            return onItsOwnThread(() -> MacLauncher.showInFolder(file));
        }
        if (PLATFORM.isWindows()) {
            long owner = owner();
            return onItsOwnThread(() -> WindowsLauncher.showInFolder(owner, file));
        }
        String session = System.getenv("DBUS_SESSION_BUS_ADDRESS");
        java.util.function.Supplier<String> token = window.launchToken();
        return onItsOwnThread(() -> DesktopOpener.showInFolder(session, file, token.get()));
    }

    /** The window's handle for the system to own what it opens by, or none once it is closed. */
    private long owner() {
        return window.isClosed() ? 0 : window.nativeHandle();
    }

    private static CompletionStage<Boolean> onItsOwnThread(BooleanSupplier handOver) {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        Threads.daemon("limn-launcher", () -> {
            boolean taken = safely(handOver);
            Ui.post(() -> answer.complete(taken));
        });
        return answer;
    }

    private static boolean safely(BooleanSupplier handOver) {
        try {
            return handOver.getAsBoolean();
        } catch (RuntimeException | LinkageError failed) {
            System.getLogger(WindowLauncher.class.getName()).log(System.Logger.Level.WARNING,
                    "the system's launcher failed", failed);
            return false;
        }
    }
}
