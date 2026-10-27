package limn.demo.site;

// The shortest complete Limn program, and the one the site's "get started" page and every README
// show, imports and all, so that a reader who copies it has a file that compiles.
//
// It exists as a compiled source file rather than as prose in a Markdown page for the reason every
// sample on that site does: this is built by ./gradlew check, so the snippet a reader copies is a
// program that compiles against the version they are reading about. A pasted sample is correct on
// the day it is written and never checked again. Not run by anything: it is a main a reader runs,
// and a region the site reads.

// #region hello-window
import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.components.Button;
import limn.components.Label;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;

/** A window with a label and a button that closes it. */
public final class HelloWindow {

    public static void main(String[] args) {
        // macOS needs -XstartOnFirstThread on the JVM command line; it is macOS-only and a
        // JVM elsewhere given it will not start.
        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(
                    WindowConfig.of("Hello, Limn", 480, 320));

            Column column = new Column();
            column.gap(12);
            column.add(new Label("A window, drawn by Limn."));
            column.add(new Button("Close").onAction(window::requestClose));

            Scene scene = new Scene(new Padding(Insets.all(24), column));
            scene.bind(window);

            backend.runEventLoop();
        }
    }
}
// #endregion
