package limn.backend.lwjgl.a11y.macos;

import limn.backend.lwjgl.a11y.ProbeScene;
import limn.concurrent.UiRuntime;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The one decision the live probe makes off a property before a run starts: which widget, if any,
 * gets the keyboard before a cycle that never moves it. Wrong here is a run whose caption panel
 * shows nothing, and that reads as a bridge that posted nothing.
 */
class LiveProbeTest {

    private HeadlessUi ui;
    private UiRuntime runtime;
    private ProbeScene probe;

    @BeforeEach
    void build() {
        ui = new HeadlessUi();
        runtime = ui.runtime();
        probe = new ProbeScene();
    }

    @AfterEach
    void tearDown() {
        ui.close();
    }

    @Test
    void nothingAskedForIsNothingFocused() {
        assertNull(LiveProbe.firstFocus(probe, null));
        assertNull(LiveProbe.firstFocus(probe, ""));
    }

    @Test
    void theListIsTheOneWidgetNamedRatherThanNumbered() {
        assertSame(probe.rows(), LiveProbe.firstFocus(probe, "list"),
                "the scroll cycle pages the list, which the focus cycle never visits");
    }

    @Test
    void aNumberIsAPlaceInTheFocusCyclesOrder() {
        assertSame(probe.focusable.get(6), LiveProbe.firstFocus(probe, "6"),
                "the slider, which is what the drag and value cycles move");
        assertSame(probe.focusable.get(0), LiveProbe.firstFocus(probe, "0"));
    }

    @Test
    void anythingElseRefusesToStartTheRun() {
        assertThrows(IllegalArgumentException.class, () -> LiveProbe.firstFocus(probe, "slider"),
                "a name that is not 'list' is a typo, and a run that focused nothing would be read "
                        + "as a reader that says nothing about a value");
        assertThrows(IllegalArgumentException.class,
                () -> LiveProbe.firstFocus(probe, String.valueOf(probe.focusable.size())));
    }
}
