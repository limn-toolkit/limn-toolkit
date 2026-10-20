package limn.components;

import limn.components.date.DatePicker;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popup hanging from a widget that stops being shown goes, as one whose widget leaves the window
 * does: the widget hidden itself or with a container, a tab strip showing another page, an expander
 * folding. A popover and a combo box's list, in the scene and in a window of their own; each close
 * is an adjustment.
 */
class AnchoredPopupHiddenAnchorTest extends ComponentTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<String> heard = new ArrayList<>();
    private HeadlessBackend backend;
    private HeadlessWindow owner;
    private Scene scene;

    private void frames(int rounds) {
        for (int round = 0; round < rounds; round++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(16);
            runtime.drain();
            for (HeadlessWindow w : new ArrayList<>(backend.windows())) {
                if (!w.isClosed() && w.scene() != null) {
                    w.frame();
                }
            }
        }
    }

    private void inWindow(Widget<?> root) {
        backend = new HeadlessBackend(runtime);
        owner = backend.open("Orders", 600, 400);
        runtime.setDelayClock(() -> now[0]);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
    }

    @Test
    void aPopoverWhoseButtonIsHiddenCloses() {
        for (DisplayMode mode : DisplayMode.values()) {
            for (boolean byContainer : new boolean[] {false, true}) {
                heard.clear();
                Button filter = new Button("Filter");
                Column holder = new Column();
                holder.add(filter);
                Column root = new Column();
                root.add(holder);
                inWindow(root);
                Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(mode)
                        .attachTo(filter).onClose(origin -> heard.add(origin.toString()));
                drive(scene).click(filter);
                frames(3);
                assertTrue(popover.isOpen(), "sanity");
                (byContainer ? holder : filter).setVisible(false);
                String where = mode + (byContainer ? ", its container hidden" : ", hidden");
                assertFalse(popover.isOpen(), where);
                assertEquals(List.of("ADJUSTMENT"), heard, where);
                frames(40);
                assertEquals(1, backend.windows().stream().filter(w -> !w.isClosed()).count(), where);
                (byContainer ? holder : filter).setVisible(true);
                frames(2);
                drive(scene).click(filter);
                frames(3);
                assertTrue(popover.isOpen(), where + ": shown again, it opens again");
                owner.requestClose();
                runtime.drain();
            }
        }
    }

    @Test
    void aComboBoxWhoseFieldIsHiddenClosesItsList() {
        for (DisplayMode mode : DisplayMode.values()) {
            ComboBox<String> combo = new ComboBox<>(List.of("Open", "Paid", "Late")).setDisplayMode(mode);
            Column holder = new Column();
            holder.add(combo);
            Column root = new Column();
            root.add(holder);
            inWindow(root);
            List<Change.Origin> expanded = new ArrayList<>();
            combo.observeChanges((source, change) -> {
                if (change.aspect() == Change.Aspect.EXPANDED) {
                    expanded.add(change.origin());
                }
            });
            combo.open();
            frames(3);
            assertTrue(combo.isOpen(), "sanity");
            expanded.clear();
            holder.setVisible(false);
            assertFalse(combo.isOpen(), mode + ": the list went with the field");
            assertTrue(expanded.contains(Change.Origin.ADJUSTMENT), mode + ": " + expanded);
            owner.requestClose();
            runtime.drain();
        }
    }

    // ------------------------------------------------------------------ asked to open while hidden

    @Test
    void aComboBoxAskedToOpenWhileHiddenStaysClosed() {
        for (DisplayMode mode : DisplayMode.values()) {
            ComboBox<String> combo = new ComboBox<>(List.of("Open", "Paid", "Late")).setDisplayMode(mode);
            Column section = new Column();
            section.add(combo);
            Column root = new Column();
            root.add(section);
            inWindow(root);
            List<Change.Origin> expanded = new ArrayList<>();
            combo.observeChanges((source, change) -> {
                if (change.aspect() == Change.Aspect.EXPANDED) {
                    expanded.add(change.origin());
                }
            });
            section.setVisible(false);
            combo.open();
            frames(3);
            assertFalse(combo.isOpen(), mode + ": a list that cannot be shown is not open");
            assertEquals(List.of(), expanded, mode + ": nor said to be");
            section.setVisible(true);
            frames(2);
            drive(scene).click(combo);
            frames(3);
            assertTrue(combo.isOpen(), mode + ": the first click once shown opens it");
            owner.requestClose();
            runtime.drain();
        }
    }

    @Test
    void aDatePickerAskedToOpenWhileHiddenStaysClosedAndItsFieldCommitsNothing() {
        for (DisplayMode mode : DisplayMode.values()) {
            DatePicker picker = new DatePicker().setClock(java.time.Clock.fixed(
                    java.time.Instant.parse("2026-09-09T12:00:00Z"), java.time.ZoneOffset.UTC))
                    .setDisplayMode(mode);
            Column section = new Column();
            section.add(new SizedBox(300, 40, picker));
            Column root = new Column();
            root.add(section);
            inWindow(root);
            List<Change.Origin> expanded = new ArrayList<>();
            picker.observeChanges((source, change) -> {
                if (change.aspect() == Change.Aspect.EXPANDED) {
                    expanded.add(change.origin());
                }
            });
            section.setVisible(false);
            picker.open();
            frames(3);
            assertFalse(picker.isOpen(), mode + ": a calendar that cannot be shown is not open");
            assertEquals(List.of(), expanded, mode.toString());
            section.setVisible(true);
            frames(2);
            scene.requestFocus(picker.field());
            drive(scene).press(Keys.DOWN); // the field's own: the day segment steps
            drive(scene).press(Keys.ENTER);
            frames(3);
            assertNull(picker.date(), mode + ": no whole date committed from a calendar never shown: "
                    + picker.field().text());
            owner.requestClose();
            runtime.drain();
        }
    }

    @Test
    void aPopoverIsNotShownFromAButtonATransformDrawsAsNothing() {
        // A card's face scaled to 0: the button on it is not drawn, and the keyboard is not given
        // to a panel hanging from it. Scrolled out of view, a widget can still be shown for.
        Button filter = new Button("Filter");
        limn.scene.layout.Transform face = new limn.scene.layout.Transform(filter).setScale(0);
        Column root = new Column();
        root.add(face);
        inWindow(root);
        Popover popover = new Popover(new SizedBox(160, 30, new TextField())).setDisplayMode(DisplayMode.IN_SCENE);
        popover.showFor(filter);
        assertFalse(popover.isOpen(), "not from a button drawn as nothing");
        face.setScale(1);
        frames(2);
        popover.showFor(filter);
        assertTrue(popover.isOpen(), "drawn again, it opens");
    }

    @Test
    void aComboBoxOpenedInADialogBeforeItsFirstFrameOpens() {
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two")).setDisplayMode(DisplayMode.IN_SCENE);
        Column root = new Column();
        root.add(new Label("Orders"));
        inWindow(root);
        new Dialog("Pick one", "").setDisplayMode(DisplayMode.IN_SCENE).setContent(combo).show(scene);
        combo.open(); // before any layout: no size yet, under a card that clips
        assertTrue(combo.isOpen(), "not laid out yet is not hidden");
    }

    @Test
    void aTabSwitchedByAButtonInThePopoverClosesIt() {
        Button details = new Button("Details");
        Column first = new Column();
        first.add(details);
        TabbedPane tabs = new TabbedPane().addTab("One", first).addTab("Two", new Label("Two"));
        inWindow(new SizedBox(500, 300, tabs));
        Button go = new Button("Go to Two");
        go.onAction(() -> tabs.setSelectedIndex(1));
        Column content = new Column();
        content.add(go);
        Popover popover = new Popover(content).setDisplayMode(DisplayMode.IN_SCENE).attachTo(details)
                .onClose(origin -> heard.add(origin.toString()));
        drive(scene).click(details);
        frames(3);
        assertTrue(popover.isOpen(), "sanity");
        drive(scene).press(Keys.SPACE);
        frames(3);
        assertEquals(1, tabs.selectedIndex(), "sanity: the tab switched");
        assertFalse(details.isShowing(), "sanity: its button is on the page out of sight");
        assertFalse(popover.isOpen(), "the popover went with the page");
        assertEquals(List.of("ADJUSTMENT"), heard);
    }
}
