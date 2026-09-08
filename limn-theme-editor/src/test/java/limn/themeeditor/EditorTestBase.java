package limn.themeeditor;

import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.graphics.TextRuler;
import limn.scene.ControlSize;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;


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
    static final TextRuler RULER = TestRulers.FIXED;

    private HeadlessUi ui;
    private UiRuntime runtime;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
        runtime = ui.runtime();
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
    }

    @AfterEach
    void uninstallRuntime() {
        Theme.setCurrent(Theme.dark());
        ui.close();
    }
}
