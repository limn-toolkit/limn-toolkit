package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.spi.TextInput;
import limn.graphics.Rect;
import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Insets;
import limn.scene.LayoutDirection;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.Padding;
import limn.scene.layout.Row;
import limn.scene.layout.Stack;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popover: where it is placed, both ways and at the edges; the four ways it closes and the origin
 * each is heard with; the keyboard going in, cycling inside and coming back; the scene and a window
 * of its own; and in a window, the owner's keys, characters and input-method composition delivered
 * to the field inside it, with one focused node in the tree.
 */
class PopoverTest extends ComponentTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<Change.Origin> closes = new ArrayList<>();

    private Scene scene;
    private Button anchor;
    private TextField first;
    private TextField second;
    private Popover popover;

    /** A filter panel: two fields in a column, as the Showcase's popover holds. */
    private Popover filterPopover() {
        first = new TextField().setAccessibleName("Customer contains");
        second = new TextField().setAccessibleName("Order number");
        Column fields = new Column();
        fields.add(new limn.scene.layout.SizedBox(160, 30, first));
        fields.add(new limn.scene.layout.SizedBox(160, 30, second));
        Popover p = new Popover(fields).setAccessibleName("Filter orders").onClose(closes::add);
        return p;
    }

    /** The anchor at {@code top} and {@code left} in a 400 by 300 scene, drawn in the scene. */
    private void inScene(float left, float top) {
        anchor = new Button("Filter");
        Stack root = new Stack();
        Row row = new Row();
        row.add(anchor);
        root.add(new Padding(new Insets(top, 0, 0, left), row));
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        popover = filterPopover().setDisplayMode(DisplayMode.IN_SCENE);
    }

    private Rect panelBox() {
        Widget<?> panel = popover.content().parent();
        assertNotNull(panel, "the panel is up");
        return panel.localToSceneBounds(0, 0, panel.width(), panel.height());
    }

    private Rect anchorBox() {
        return anchor.localToSceneBounds(0, 0, anchor.width(), anchor.height());
    }

    private void open() {
        popover.showFor(anchor);
        scene.layoutPass(400, 300);
    }

    // ------------------------------------------------------------------ placement

    @Test
    void itHangsBelowTheAnchorFromItsLeadingEdge() {
        inScene(40, 20);
        open();
        assertTrue(popover.isOpen());
        assertSame(DisplayMode.IN_SCENE, popover.displayMode());
        Rect panel = panelBox();
        assertTrue(panel.y() >= anchorBox().bottom(), "below the anchor: " + panel + " " + anchorBox());
        assertEquals(anchorBox().x(), panel.x(), 0.5f, "lined up with its leading edge");
        float pad = Theme.current().tokensFor(anchor).spacingMedium();
        assertEquals(160 + 2 * pad, panel.width(), 0.5f, "the content's measure and the padding round it");
    }

    @Test
    void endAlignsWithTheTrailingEdgeAndRightToLeftStartIsTheRight() {
        inScene(200, 20);
        popover.setAlignment(Popover.Alignment.END);
        open();
        assertEquals(anchorBox().right(), panelBox().right(), 0.5f, "END: the trailing edges meet");
        popover.close();

        // The anchor reads right to left, as a button in an Arabic or a Hebrew window does: its
        // leading edge is its right one, and the panel lines up with it.
        inScene(200, 20);
        anchor.setLayoutDirection(LayoutDirection.RTL);
        scene.layoutPass(400, 300);
        open();
        assertEquals(anchorBox().right(), panelBox().right(), 0.5f,
                "right to left, START is the anchor's right edge: anchor " + anchorBox() + " panel " + panelBox());
    }

    @Test
    void itGoesAboveWhenBelowHasNoRoomAndAboveHasMore() {
        inScene(40, 250);
        open();
        assertTrue(panelBox().bottom() <= anchorBox().y(), "above the anchor: " + panelBox());
        assertTrue(panelBox().y() >= 0, "inside the scene");
    }

    @Test
    void atTheEdgeItIsClampedIntoTheScene() {
        inScene(380, 20);
        open();
        assertTrue(panelBox().right() <= 400, "clamped: " + panelBox());
    }

    // ------------------------------------------------------------------ closing

    @Test
    void escapeAndAPressOutsideAreTheUsersAndCloseIsTheCodes() {
        inScene(40, 20);
        open();
        drive(scene).keyEvent(Keys.ESCAPE, true, false, 0);
        drive(scene).inputBatchEnded();
        assertFalse(popover.isOpen());

        open();
        drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, 390, 290);
        drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, 390, 290);
        drive(scene).inputBatchEnded();
        assertFalse(popover.isOpen(), "a press outside closes it");

        open();
        popover.close();
        assertEquals(List.of(Change.Origin.USER, Change.Origin.USER, Change.Origin.CODE), closes);
    }

    // ------------------------------------------------------------------ focus

    @Test
    void theKeyboardGoesInCyclesInsideAndComesBackToTheAnchor() {
        inScene(40, 20);
        scene.requestFocus(anchor);
        open();
        assertSame(first, scene.focusedWidget(), "on open the first focusable widget inside");
        List<Widget<?>> visited = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            drive(scene).keyEvent(Keys.TAB, true, false, 0);
            drive(scene).keyEvent(Keys.TAB, false, false, 0);
            drive(scene).inputBatchEnded();
            visited.add(scene.focusedWidget());
        }
        assertTrue(visited.contains(second), "Tab reaches the second field: " + visited);
        for (Widget<?> w : visited) {
            assertFalse(w == anchor, "Tab never leaves the popover for the window beneath");
        }
        // And stops only on its controls: the layer that holds the keyboard in the scene was a stop
        // of its own, an empty group that no key typed into, after the last field (a reader run on
        // the Wayland guest heard "Filter orders" there instead of the field, 2026-10-25).
        assertEquals(List.of(second, first, second, first), visited, "Tab goes round the two fields");
        drive(scene).keyEvent(Keys.ESCAPE, true, false, 0);
        drive(scene).inputBatchEnded();
        assertSame(anchor, scene.focusedWidget(), "back to the anchor on close");
    }

    @Test
    void aFieldInsideOpensItsContextMenuOverThePopover() {
        // A text field's menu, asked for from inside the popover, opens over it and leaves it up.
        Button button = new Button("Filter");
        Column root = new Column();
        root.add(button);
        root.add(new limn.scene.layout.SizedBox(300, 200, new Label("Orders")));
        TextField field = new TextField().setText("Kestrel").setAccessibleName("Customer contains");
        Popover p = new Popover(field).setDisplayMode(DisplayMode.IN_SCENE);
        limn.testing.AccessibleHarness harness =
                new limn.testing.AccessibleHarness(runtime, root, new limn.testing.StubWindow(false));
        p.showFor(button);
        harness.frame();
        AccessibleNode node = harness.node("Customer contains");
        assertTrue(node.actions().has(Accessible.Action.SHOW_MENU), "the field offers its menu");
        assertTrue(harness.perform(node.id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE));
        harness.frame();
        assertTrue(p.isOpen(), "the popover stayed open under its field's menu");
        assertNotNull(limn.testing.AccessibleTrees.withRole(harness.tree(), Accessible.Role.MENU),
                "and the menu is up: " + harness.describe());
    }

    @Test
    void aPopoverWithNoControlsTakesTheKeyboardOnItsLayerInTheScene() {
        // The layer is the popover's node in the scene, the group a reader is put on; the panel
        // drawn on it is no node (PopoverNoControlsAccessibilityTest reads the tree).
        inScene(40, 20);
        scene.requestFocus(anchor);
        popover = new Popover(new Label("Last synced 10:42")).setDisplayMode(DisplayMode.IN_SCENE);
        open();
        Widget<?> panel = popover.content().parent();
        Widget<?> focused = scene.focusedWidget();
        assertNotNull(focused);
        assertFalse(focused == panel, "not the panel");
        boolean below = false;
        for (Widget<?> w = panel.parent(); w != null; w = w.parent()) {
            below |= w == focused;
        }
        assertTrue(below, "the layer the panel is drawn on: " + focused);
        drive(scene).keyEvent(Keys.ESCAPE, true, false, 0);
        drive(scene).inputBatchEnded();
        assertFalse(popover.isOpen(), "Escape closes it");
        assertSame(anchor, scene.focusedWidget(), "back to the anchor");
    }

    // ------------------------------------------------------------------ the attached button

    @Test
    void anAttachedButtonOpensAndClosesIt() {
        inScene(40, 20);
        popover.attachTo(anchor);
        Rect a = anchorBox();
        drive(scene).click(a.centerX(), a.centerY());
        scene.layoutPass(400, 300);
        assertTrue(popover.isOpen(), "the press opened it");
        drive(scene).keyEvent(Keys.ESCAPE, true, false, 0);
        drive(scene).inputBatchEnded();
        scene.requestFocus(anchor);
        drive(scene).keyEvent(Keys.SPACE, true, false, 0);
        drive(scene).keyEvent(Keys.SPACE, false, false, 0);
        drive(scene).inputBatchEnded();
        assertTrue(popover.isOpen(), "Space on the button opens it again");
    }

    // ------------------------------------------------------------------ a window of its own

    private HeadlessWindow owner;
    private TextField ownField;

    private void inWindow() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        owner = backend.open("Orders", 400, 300);
        anchor = new Button("Filter");
        ownField = new TextField();
        Column root = new Column();
        root.add(ownField);
        root.add(anchor);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
        popover = filterPopover();
        scene.requestFocus(ownField);
        popover.showFor(anchor);
        runtime.drain(); // the window is made a turn later
    }

    private Scene popupScene() {
        Widget<?> panel = popover.content().parent();
        assertNotNull(panel);
        return panel.scene();
    }

    @Test
    void inAWindowTheOwnersKeysAndCharactersReachTheFieldInside() {
        inWindow();
        assertSame(DisplayMode.NATIVE_WINDOW, popover.displayMode());
        Scene popup = popupScene();
        assertFalse(popup == scene, "a window of its own");
        assertSame(first, popup.focusedWidget(), "the first field inside has the popup's focus");
        drive(scene).charTyped('a');
        drive(scene).charTyped('b');
        drive(scene).inputBatchEnded();
        assertEquals("ab", first.text(), "typed in the owner, landed in the popover");
        assertEquals("", ownField.text(), "and not in the field the owner had focused");
        drive(scene).keyEvent(Keys.TAB, true, false, 0);
        drive(scene).keyEvent(Keys.TAB, false, false, 0);
        drive(scene).inputBatchEnded();
        assertSame(second, popup.focusedWidget(), "Tab moved inside the popover");
        drive(scene).keyEvent(Keys.ESCAPE, true, false, 0);
        drive(scene).inputBatchEnded();
        assertFalse(popover.isOpen(), "Escape closed it");
        assertEquals(List.of(Change.Origin.USER), closes);
        assertSame(ownField, scene.focusedWidget(), "the owner's focus never moved");
    }

    @Test
    void inAWindowTheCompositionGoesToTheFieldInsideAndTheCandidatesFollowItsCaret() {
        inWindow();
        Scene popup = popupScene();
        assertEquals(TextInput.COMPOSITION, owner.textInput, "the owner composes for the popover's field");
        drive(scene).preeditChanged("に", 1, 0, 1);
        drive(scene).inputBatchEnded();
        assertEquals("に", first.composingText(), "the composition reached the field inside");
        assertEquals("", ownField.composingText(), "and not the owner's field");
        drive(scene).preeditChanged("", 0, 0, 0);
        drive(scene).charTyped('日');
        drive(scene).inputBatchEnded();
        assertEquals("日", first.text(), "the committed text");
        ((HeadlessWindow) popup.window()).frame(); // the popover lays out and paints its field
        owner.frame();
        assertNotNull(owner.preeditCaret, "the candidate window was placed");
        Rect caret = first.caretRect();
        assertEquals(caret, owner.preeditCaret, "at the popover field's caret");
        popover.close();
        runtime.drain();
        owner.frame();
        assertEquals(TextInput.COMPOSITION, owner.textInput, "the owner's own field composes again");
    }

    @Test
    void inAWindowTheTreeHasOneFocusedNodeTheFieldInside() {
        inWindow();
        owner.frame();
        AccessibleTree tree = owner.bridge().tree();
        List<AccessibleNode> focused = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).has(Accessible.State.FOCUSED)) {
                focused.add(tree.node(i));
            }
        }
        assertEquals(1, focused.size(), "one focused node: " + focused);
        assertEquals("Customer contains", focused.get(0).name(),
                "the field inside the popover, not the owner's own field: " + focused);
        AccessibleNode group = null;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if ("Filter orders".equals(tree.node(i).name())) {
                group = tree.node(i);
            }
        }
        assertNotNull(group, "the popover's group is grafted into the owner's tree");
        assertSame(Accessible.Role.GROUP, group.role());
        assertTrue(group.actions().has(Accessible.Action.CANCEL));
    }

    @Test
    void inAWindowThePanelIsAsWideAsItsControlsAskAndNotAsTheWindow() {
        // Seen on the macOS guest: a field and a combo box measured against a bounded width take
        // all of it, and the first panel was as wide as the owner's window.
        HeadlessBackend backend = new HeadlessBackend(runtime);
        owner = backend.open("Orders", 600, 300);
        anchor = new Button("Filter");
        Column root = new Column();
        root.add(anchor);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
        ComboBox<String> alone = new ComboBox<>(java.util.List.of("Open", "Paid"));
        // A column that stretches its controls across, as a form's does, is as wide as it is let.
        Column form = new Column();
        form.crossAlignment(limn.scene.layout.Flex.CrossAlignment.STRETCH);
        form.add(alone);
        popover = new Popover(form);
        popover.showFor(anchor);
        runtime.drain();
        Widget<?> panel = popover.content().parent();
        assertNotNull(panel);
        float pad = Theme.current().tokensFor(anchor).spacingMedium();
        float natural = alone.measure(limn.scene.Constraints.loose(Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY)).width();
        assertEquals(natural + 2 * pad, panel.measure(limn.scene.Constraints.loose(Float.POSITIVE_INFINITY,
                Float.POSITIVE_INFINITY)).width(), 0.5f);
        HeadlessWindow window = (HeadlessWindow) panel.scene().window();
        assertTrue(window.logicalWidth() < 600 - 16, "narrower than the owner: " + window.logicalWidth());
    }

    @Test
    void inAWindowTheOwnerLosingTheKeyboardIsAnAdjustment() {
        inWindow();
        assertInstanceOf(HeadlessWindow.class, popupScene().window());
        owner.desktopFocus(false);
        runtime.drain();
        runtime.drain();
        assertFalse(popover.isOpen());
        assertEquals(List.of(Change.Origin.ADJUSTMENT), closes);
    }
}
