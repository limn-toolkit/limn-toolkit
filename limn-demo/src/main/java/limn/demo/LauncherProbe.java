package limn.demo;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.concurrent.Ui;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/**
 * {@code --open <address or path>} and {@code --show <path>}: one request to the system's
 * launcher from a real window, its answer on standard output and in the exit code. A diagnostic
 * like {@code --gl-info}: how the toolkit's call is measured on a system, with no pointer to
 * move and no scene to build.
 */
final class LauncherProbe {

    private LauncherProbe() {
    }

    /** @return whether the system took what it was handed */
    static boolean run(String verb, String target) {
        boolean[] taken = {false};
        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(WindowConfig.of(AppScene.TITLE, 320, 120));
            Ui.postDelayed(() -> {
                boolean address = target.contains("://") || target.startsWith("mailto:");
                CompletionStage<Boolean> answer = verb.equals("--show")
                        ? window.launcher().showInFolder(Path.of(target))
                        : address ? window.launcher().openAddress(URI.create(target))
                        : window.launcher().openFile(Path.of(target));
                answer.thenAccept(result -> {
                    taken[0] = result;
                    System.out.println("launcher " + verb + " " + target + " -> " + result);
                    Ui.postDelayed(window::requestClose, 1500);
                });
            }, 500);
            backend.runEventLoop();
        }
        return taken[0];
    }
}
