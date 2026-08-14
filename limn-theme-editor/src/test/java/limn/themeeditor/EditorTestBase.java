package limn.themeeditor;

import limn.components.Theme;
import limn.concurrent.Ui;
import limn.concurrent.UiRuntime;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.ControlSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The editor's tests run headless: a {@link UiRuntime} bound to the JUnit thread, and a
 * deterministic monospace ruler so a layout pass can run with no GL and no fonts.
 *
 * <p>The palette is restored after every test. This module's whole subject is a widget
 * that writes {@link Theme#setCurrent}, so a test that left one behind would be handing
 * the next one a palette it never chose, and the failure would land in whichever test
 * happened to run next.
 */
abstract class EditorTestBase {

    /** 10pt per code point; ascent 8, descent 2, lineHeight 12. */
    static final TextRuler RULER = (text, font) ->
            new TextMetrics(10f * (int) text.codePoints().count(), 8, 2, 12);

    private ExecutorService workers;
    private UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        workers = Executors.newFixedThreadPool(1);
        runtime = new UiRuntime(System::nanoTime, () -> { }, workers);
        runtime.bindToCurrentThread();
        Ui.install(runtime);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
    }

    @AfterEach
    void uninstallRuntime() {
        Theme.setCurrent(Theme.dark());
        Ui.uninstall(runtime);
        workers.shutdownNow();
    }
}
