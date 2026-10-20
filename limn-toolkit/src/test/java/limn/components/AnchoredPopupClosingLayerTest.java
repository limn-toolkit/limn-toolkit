package limn.components;

import limn.accessibility.Accessible;
import limn.input.Keys;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleTrees;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * An anchored popup drawn in the scene that is fading out has let go: a press during its fade
 * reaches what is beneath it and the keyboard goes there, as it does once the layer is gone. The
 * layer swallowed that press and took the focus, and when it went the keyboard was nowhere.
 */
class AnchoredPopupClosingLayerTest extends ComponentTestBase {

    @Test
    void aClickDuringAPopoversFadeOutReachesTheFieldBeneath() {
        aClickDuringTheFadeReachesTheFieldBeneath(false);
    }

    @Test
    void aClickDuringAComboListsFadeOutReachesTheFieldBeneath() {
        aClickDuringTheFadeReachesTheFieldBeneath(true);
    }

    private void aClickDuringTheFadeReachesTheFieldBeneath(boolean combo) {
        Button filter = new Button("Filter");
        TextField other = new TextField().setAccessibleName("Other");
        ComboBox<String> status = new ComboBox<>(List.of("Open", "Paid")).setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(filter);
        root.add(status);
        root.add(new SizedBox(200, 30, other));
        AccessibleHarness harness = new AccessibleHarness(runtime, root, new StubWindow(false));
        harness.scene.setTextRuler(RULER);
        harness.frame();
        new Popover(new SizedBox(100, 30, new TextField().setAccessibleName("Inside")))
                .setDisplayMode(DisplayMode.IN_SCENE).attachTo(filter);
        drive(harness.scene).click(combo ? status : filter);
        harness.advanceTime(400, null);

        drive(harness.scene).press(Keys.ESCAPE);
        harness.advanceTime(30, null);
        drive(harness.scene).click(other);
        harness.frame();
        assertSame(other, harness.scene.focusedWidget(), "the click 30 ms into the fade reached the field");
        harness.advanceTime(400, null);
        assertSame(other, harness.scene.focusedWidget(), "and the keyboard stays there once the layer is gone");
        assertEquals(1, AccessibleTrees.withState(harness.tree(), Accessible.State.FOCUSED).size());
        drive(harness.scene).type("z");
        harness.frame();
        assertEquals("z", other.text());
        assertFalse(status.isOpen());
    }
}
