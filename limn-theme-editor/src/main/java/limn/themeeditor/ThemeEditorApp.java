package limn.themeeditor;

import limn.backend.Backend;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;
import limn.components.Label;
import limn.components.Theme;
import limn.components.ThemeFormat;
import limn.components.TokenColumn;
import limn.components.TokenRow;
import limn.components.Tokens;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.layout.Expanded;
import limn.scene.layout.Flex;
import limn.scene.layout.Padding;

import java.nio.file.Path;

/**
 * The theme editor as a program you run, rather than a screen you embed.
 *
 * <p>{@link ThemeEditor} is a widget, and that is the module's whole point: an application drops
 * it into a settings screen and gets palette authoring. But a designer who wants to build a
 * palette should not have to write an application first, so this is the same widget in a window
 * of its own, with nothing around it that the embeddable version does not also offer.
 *
 * <pre>{@code
 * ./gradlew :limn-theme-editor:run
 * ./gradlew :limn-theme-editor:run --args="brand.limntheme"
 * }</pre>
 *
 * <p><b>The backend is not a dependency of this module's library half.</b> It is
 * {@code compileOnly} plus {@code runtimeOnly}, so this class compiles and runs while the
 * published jar still declares only {@code limn-components}. An application that embeds
 * {@link ThemeEditor} therefore does not inherit a window toolkit it already has, and the module
 * keeps the property its build file opens with: nothing depends on it, and it depends on as
 * little as it can.
 *
 * <p>Everything here is a call an embedding application would also make. The editor applies what
 * it edits as it is edited, so the window re-skins under the user's hands, which is the honest
 * preview and needs no wiring from this file.
 */
public final class ThemeEditorApp {

    private ThemeEditorApp() {
    }

    /**
     * Opens the editor on a palette, and prints what it produced when the window closes.
     *
     * <p>Printing rather than writing a file: where a palette belongs is the application's
     * decision, which is the same reason {@link ThemeEditorFiles} is optional and separate.
     * Standard output is the one destination that is nobody's convention, so it can be
     * redirected into whichever one the reader has.
     *
     * @param args an optional {@code .limntheme} file to open; without one the editor starts on
     *             the palette the window is wearing
     */
    public static void main(String[] args) throws Exception {
        Theme start = args.length > 0 ? ThemeFormat.load(Path.of(args[0])) : Theme.limn();
        Theme.setCurrent(start);

        // macOS needs -XstartOnFirstThread on the JVM command line. The `run` task adds it there
        // and only there: baked into applicationDefaultJvmArgs it would reach the start scripts
        // of every platform, and a JVM that does not know the flag refuses to start.
        try (Backend backend = new LwjglBackend()) {
            NativeWindow window = backend.createWindow(
                    new WindowConfig("Limn theme editor", 1180, 860, true, true));

            ThemeEditor editor = new ThemeEditor(start);
            // In scene, not in a window of its own. The picker recolours the screen behind it
            // live, so a native window would float over the thing it is changing; this is the
            // same choice the toolkit's own gallery makes when it films this screen.
            editor.setPickerDisplayMode(limn.components.DisplayMode.IN_SCENE);

            TokenRow heading = new TokenRow(Tokens.Role.MEDIUM);
            heading.crossAlignment(Flex.CrossAlignment.CENTER);
            heading.add(Expanded.of(
                    new Label("Theme editor").setRole(Label.Role.TITLE).setStrong(true), 1));
            // The file row comes from the module rather than from here, because opening and
            // saving is exactly the part an embedding application is expected to own.
            heading.add(ThemeEditorFiles.buttons(editor));

            TokenColumn column = new TokenColumn(Tokens.Role.MEDIUM);
            column.crossAlignment(Flex.CrossAlignment.STRETCH);
            column.add(heading);
            column.add(Expanded.of(editor));

            Scene scene = new Scene(new Padding(Insets.all(20), column));
            scene.setBackground(start.background);
            scene.bind(window);

            backend.runEventLoop();

            // After the loop, so it is the palette the user finished with rather than whichever
            // frame they were on. The editor is the source of it, not Theme.current(): a user who
            // turned "apply while editing" off never installed what they built.
            System.out.print(ThemeFormat.write(editor.theme()));
        }
    }
}
