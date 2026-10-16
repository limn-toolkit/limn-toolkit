package limn.components;

import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Expander} and {@link ExpanderGroup} (ADR 067 section 3): a header that opens and closes
 * the content below it by click, by Space or Enter and from code, the content hidden and taking no
 * room while closed, the keyboard brought back to the header when what it was in closes, the
 * application told what the user did and nothing it did itself, and a group that keeps one open.
 */
class ExpanderTest extends AccessibleComponentTestBase {

    private final List<Boolean> heard = new ArrayList<>();

    private Expander expander(String title, Widget<?> content) {
        return new Expander(title, content);
    }

    private Column bindAll(Expander... sections) {
        Column root = new Column();
        for (Expander section : sections) {
            root.add(section);
        }
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        return root;
    }

    private void clickHeader(Expander section) {
        float x = section.localToSceneX() + 20;
        float y = section.localToSceneY() + 8;
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, x, y);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, x, y);
        drive(scene).inputBatchEnded();
        settleAnimations(null);
    }

    private void key(int key, boolean down) {
        drive(scene).keyEvent(key, down, false, 0);
        drive(scene).inputBatchEnded();
    }

    @Test
    void aClickOpensAndClosesAndTheContentTakesRoomOnlyWhileOpen() {
        Label inside = new Label("Inside");
        Expander section = expander("Details", inside).onChange(heard::add);
        bindAll(section);
        float closed = section.height();
        assertFalse(inside.isVisible(), "closed content is hidden");

        clickHeader(section);
        assertTrue(section.isExpanded());
        assertTrue(inside.isVisible());
        assertTrue(section.height() > closed + inside.height() - 0.5f, "open, it holds the content");
        assertEquals(List.of(true), heard);

        clickHeader(section);
        assertFalse(section.isExpanded());
        assertFalse(inside.isVisible(), "hidden again once the slide ends");
        assertEquals(closed, section.height(), 0.01f);
        assertEquals(List.of(true, false), heard);
    }

    @Test
    void aCallerOpensItAnnouncedButNoHandlerRuns() {
        Expander section = expander("Details", new Label("Inside")).onChange(heard::add);
        bindAll(section);
        List<Change> changes = new ArrayList<>();
        section.observeChanges((w, change) -> changes.add(change));
        section.setExpanded(true);
        section.setExpanded(true);
        assertEquals(List.of(Change.of(Change.Aspect.EXPANDED, Change.Origin.CODE)), changes,
                "once, and the same state again is nothing");
        assertTrue(heard.isEmpty());
    }

    @Test
    void spaceAndEnterOnTheHeaderToggleOnTheRelease() {
        Expander section = expander("Details", new Label("Inside")).onChange(heard::add);
        bindAll(section);
        key(Keys.TAB, true);
        key(Keys.TAB, false);
        key(Keys.SPACE, true);
        assertFalse(section.isExpanded(), "not on the press");
        key(Keys.SPACE, false);
        assertTrue(section.isExpanded());
        key(Keys.ENTER, true);
        key(Keys.ENTER, false);
        assertFalse(section.isExpanded());
        assertEquals(List.of(true, false), heard);
    }

    @Test
    void closingWhatHoldsTheKeyboardBringsItBackToTheHeader() {
        Button inside = new Button("Inside");
        Expander section = expander("Details", inside);
        bindAll(section);
        section.setExpanded(true);
        settleAnimations(null);
        inside.requestFocus();
        assertTrue(inside.isFocused());
        section.setExpanded(false);
        Widget<?> focused = scene.focusedWidget();
        assertSame(section, focused.parent(), "the header holds it now");
        assertFalse(inside.isFocused());
    }

    @Test
    void aGroupKeepsOneOpenAndTellsTheUsersChangesInOrder() {
        List<String> order = new ArrayList<>();
        Expander first = expander("First", new Label("1")).onChange(on -> order.add("first " + on));
        Expander second = expander("Second", new Label("2")).onChange(on -> order.add("second " + on));
        ExpanderGroup group = new ExpanderGroup().add(first).add(second)
                .onChange(index -> order.add("group " + index));
        bindAll(first, second);
        first.observeChanges((w, change) -> said(order, "first", change));
        second.observeChanges((w, change) -> said(order, "second", change));

        clickHeader(first);
        clickHeader(second);
        assertFalse(first.isExpanded());
        assertTrue(second.isExpanded());
        assertSame(second, group.expanded());
        // Both settle, then both speak, then the handlers run: the radio group's order.
        assertEquals(List.of("first says USER", "first true", "group 0",
                "first says USER", "second says USER", "first false", "second true", "group 1"),
                order);

        order.clear();
        clickHeader(second);
        assertEquals(-1, group.expandedIndex(), "the open one closed leaves none");
        assertEquals(List.of("second says USER", "second false", "group -1"), order);

        order.clear();
        group.setExpandedIndex(0);
        group.setExpandedIndex(1);
        group.collapseAll();
        assertEquals(-1, group.expandedIndex());
        assertEquals(List.of("first says CODE", "first says CODE", "second says CODE",
                "second says CODE"), order, "a caller's writes are announced and reach no handler");
    }

    private static void said(List<String> order, String who, Change change) {
        if (change.aspect() == Change.Aspect.EXPANDED) {
            order.add(who + " says " + change.origin());
        }
    }

    @Test
    void anOpenSectionAddedToAGroupClosesTheOneThatWasOpen() {
        Expander first = expander("First", new Label("1"));
        Expander second = expander("Second", new Label("2"));
        first.setExpanded(true);
        second.setExpanded(true);
        ExpanderGroup group = new ExpanderGroup().add(first).add(second);
        assertFalse(first.isExpanded());
        assertSame(second, group.expanded());
        group.remove(second);
        first.setExpanded(true);
        assertTrue(second.isExpanded(), "out of the group it is on its own");
    }

    /**
     * A section out of the scene it was in is out of its group until it is back, as a radio is out
     * of its ButtonGroup: a dialog rebuilt each time it opens, against a group the controller keeps,
     * must not leave the group every section of every dialog before, each still counted and each
     * holding its content.
     */
    @Test
    void aSectionOutOfItsSceneIsOutOfItsGroupUntilItIsBack() {
        Expander first = expander("First", new Label("1"));
        Expander second = expander("Second", new Label("2"));
        Expander third = expander("Third", new Label("3"));
        ExpanderGroup group = new ExpanderGroup().add(first).add(second).add(third);
        Column root = bindAll(first, second, third);
        group.setExpandedIndex(1);

        root.remove(second);
        frame();
        assertEquals(List.of(first, third), group.members(), "the section that left is not counted");
        assertEquals(-1, group.expandedIndex(), "nor is it the open one");
        group.setExpandedIndex(0);

        root.add(second);
        frame();
        assertEquals(List.of(first, second, third), group.members(), "back in the place it was added at");
        assertSame(first, group.expanded(), "the group kept the one it opened meanwhile");
        assertFalse(second.isExpanded(), "and the one that came back open gave way");
    }

    @Test
    void rightToLeftTheTitleStartsOnTheRightAndTheSummaryEndsOnTheLeft() {
        Map<String, Float> ltr = textAt(LayoutDirection.LTR);
        Map<String, Float> rtl = textAt(LayoutDirection.RTL);
        assertTrue(ltr.get("Details") < ltr.get("3 files"), "left to right: title, then summary");
        assertTrue(rtl.get("Details") > rtl.get("3 files"), "right to left: the other way round");
    }

    /** Where each string of a closed section's header is drawn, read from the canvas. */
    private Map<String, Float> textAt(LayoutDirection direction) {
        Expander section = expander("Details", new Label("Inside")).setSummary("3 files");
        section.setLayoutDirection(direction);
        bindAll(section);
        Map<String, Float> at = new HashMap<>();
        canvas = new FakeCanvas(400, 300) {
            @Override
            public void drawText(ShapedText text, float x, float y, Paint paint) {
                at.put(text.text(), x);
            }
        };
        section.invalidate();
        frame();
        return at;
    }
}
