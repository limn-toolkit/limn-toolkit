package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.scene.Change;
import limn.scene.Scene;
import limn.scene.layout.Row;
import limn.testing.AccessibleHarness;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One popover per button, and one button per popover: attaching a second popover to a button
 * detaches the first, which then has no button; attaching a popover to another button moves it;
 * {@link Popover#detach()} lets it go; and a popover with no button still opens from
 * {@link Popover#showFor}. Each transition leaves the button and the popover holding nothing of
 * each other that the other has let go of.
 */
class PopoverAttachTest extends ComponentTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<String> heard = new ArrayList<>();
    private Scene scene;
    private Button filter;
    private Button other;
    private int filterActions;

    private void scene() {
        filter = new Button("Filter");
        filter.onAction(() -> filterActions++);
        other = new Button("Other");
        Row row = new Row();
        row.add(filter);
        row.add(other);
        scene = new Scene(row, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
    }

    private Popover popover(String name) {
        return new Popover(new Label(name)).setAccessibleName(name).setDisplayMode(DisplayMode.IN_SCENE)
                .onClose(origin -> heard.add(name + " " + origin));
    }

    private void click(Button button) {
        drive(scene).click(button);
        scene.layoutPass(400, 300);
    }

    @Test
    void aSecondPopoverOnAButtonDetachesTheFirst() {
        scene();
        Popover first = popover("First");
        Popover second = popover("Second");
        first.attachTo(filter);
        second.attachTo(filter);
        assertSame(second, filter.attachedPopover(), "the button holds the second");
        click(filter);
        assertTrue(second.isOpen(), "the button opens the second");
        assertFalse(first.isOpen(), "and not the first, which has no button now");
    }

    @Test
    void movingTheReplacedPopoverElsewhereLeavesTheButtonItsNewPopover() {
        // The audit's case: a tool bar's Filter button given a new popover when the view changed,
        // and the old one put on another button. The old one still thought it was on Filter, let
        // go of Filter's slot, and Filter opened nothing and told a reader it had no popup.
        scene();
        Popover old = popover("Old");
        Popover fresh = popover("Fresh");
        old.attachTo(filter);
        fresh.attachTo(filter);
        old.attachTo(other);
        assertSame(fresh, filter.attachedPopover(), "Filter keeps the popover it was given last");
        assertSame(old, other.attachedPopover());
        click(filter);
        assertTrue(fresh.isOpen(), "Filter opens its popover");
        assertFalse(old.isOpen());
        fresh.close();
        click(other);
        assertTrue(old.isOpen(), "the other button opens the old one");
    }

    @Test
    void attachingToAnotherButtonMovesItThere() {
        scene();
        Popover popover = popover("Filter orders");
        popover.attachTo(filter);
        popover.attachTo(other);
        assertNull(filter.attachedPopover(), "the first button let go of it");
        click(filter);
        assertFalse(popover.isOpen(), "the first button opens nothing");
        assertEquals(1, filterActions, "it is a plain button again, its own action ran");
        click(other);
        assertTrue(popover.isOpen(), "the second one opens it");
    }

    @Test
    void attachingToTheSameButtonAgainChangesNothing() {
        scene();
        Popover popover = popover("Filter orders").attachTo(filter);
        click(filter);
        assertTrue(popover.isOpen());
        popover.attachTo(filter);
        assertTrue(popover.isOpen(), "still open, still its button's");
        assertSame(popover, filter.attachedPopover());
        assertEquals(List.of(), heard);
    }

    @Test
    void detachLetsTheButtonGoAndTheButtonIsAPlainOneAgain() {
        scene();
        Popover popover = popover("Filter orders");
        assertSame(popover, popover.attachTo(filter).detach(), "fluent, as the other setters");
        assertNull(filter.attachedPopover());
        click(filter);
        assertFalse(popover.isOpen(), "the button opens nothing");
        assertEquals(1, filterActions, "its own action ran");
        popover.detach();
        new Popover(new Label("never attached")).detach(); // no button: nothing to let go of

        popover.showFor(filter);
        assertTrue(popover.isOpen(), "a popover with no button still opens from showFor");
    }

    @Test
    void detachingAPopoverOpenFromItsButtonClosesItAsTheCodes() {
        scene();
        Popover popover = popover("Filter orders").attachTo(filter);
        List<Change.Aspect> aspects = new ArrayList<>();
        filter.observeChanges((source, change) -> aspects.add(change.aspect()));
        click(filter);
        assertTrue(popover.isOpen());
        aspects.clear();
        popover.detach();
        assertFalse(popover.isOpen(), "a panel hanging from a button that no longer opens it goes");
        assertEquals(List.of("Filter orders CODE"), heard);
        assertTrue(aspects.contains(Change.Aspect.EXPANDED), "the button said it is collapsed: " + aspects);
    }

    @Test
    void detachingAPopoverShownForAnotherWidgetLeavesItOpen() {
        scene();
        Popover popover = popover("Filter orders").attachTo(filter);
        popover.showFor(other);
        popover.detach();
        assertTrue(popover.isOpen(), "it hangs from another widget, which still holds it");
    }

    @Test
    void replacingAnOpenPopoverClosesItAndTheButtonOpensTheNewOne() {
        scene();
        Popover first = popover("First").attachTo(filter);
        click(filter);
        assertTrue(first.isOpen());
        Popover second = popover("Second").attachTo(filter);
        assertFalse(first.isOpen(), "the replaced one, open from the button, closed");
        assertEquals(List.of("First CODE"), heard);
        click(filter);
        assertTrue(second.isOpen(), "the button opens the new one");
    }

    @Test
    void aReaderIsToldTheButtonNoLongerOpensAPopup() {
        Button button = new Button("Filter");
        Row row = new Row();
        row.add(button);
        AccessibleHarness harness = new AccessibleHarness(runtime, row, new StubWindow(false));
        Popover popover = popover("Filter orders").attachTo(button);
        harness.frame();
        AccessibleNode node = harness.node("Filter");
        assertTrue(node.has(Accessible.State.HAS_POPUP), "sanity: it opens a popup");
        assertTrue(node.actions().has(Accessible.Action.EXPAND));

        popover.detach();
        harness.frame();
        node = harness.node("Filter");
        assertFalse(node.has(Accessible.State.HAS_POPUP), "a plain button again: " + harness.describe());
        assertTrue(node.actions().has(Accessible.Action.PRESS));
        assertFalse(node.actions().has(Accessible.Action.EXPAND));

        Popover second = popover("Second").attachTo(button);
        popover.attachTo(new Button("Elsewhere"));
        harness.frame();
        assertTrue(harness.node("Filter").has(Accessible.State.HAS_POPUP),
                "attaching the old one elsewhere leaves the button its new popup");
        assertSame(second, button.attachedPopover());
    }
}
