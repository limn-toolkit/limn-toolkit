package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.testing.AccessibleTrees;
import limn.input.Keys;
import limn.scene.Scene;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popup opened from a control inside a popover that is a window of its own, a combo box's list
 * or a text field's menu, is published in the owner window's tree, under the control that opened
 * it, as the same popup opened from a control of the window itself is: the popover's window and
 * the popup's publish nothing of their own, so a popup grafted into the popover's scene that the
 * owner's walk does not reach is reached by no reader at all.
 *
 * <p>Each case reads the tree the owner's window handed its bridge, the one tree a screen reader
 * is given, and the events that came with it.
 */
class PopoverNestedPopupAccessibilityTest extends ComponentTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private HeadlessBackend backend;
    private HeadlessWindow owner;
    private Scene scene;
    private Button anchor;
    private Popover popover;

    /** The owner window with a Filter button, and a popover of {@code content} open from it. */
    private void openPopover(limn.scene.Widget<?> content, DisplayMode mode) {
        backend = new HeadlessBackend(runtime);
        owner = backend.open("Orders", 600, 400);
        anchor = new Button("Filter");
        Column root = new Column();
        root.add(anchor);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
        scene.requestFocus(anchor);
        popover = new Popover(content).setAccessibleName("Filter orders").setDisplayMode(mode);
        popover.showFor(anchor);
        frames();
        assertSame(mode, popover.displayMode(), "sanity: opened in the mode asked for");
    }

    /** A turn and a frame of every window, owner last, so its walk reads what the others laid out. */
    private void frames() {
        frames(3);
    }

    /** Enough frames for a closing popup's fade to end and its window to close. */
    private void settle() {
        frames(40);
    }

    private void frames(int rounds) {
        for (int round = 0; round < rounds; round++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(16);
            runtime.drain();
            for (HeadlessWindow w : new ArrayList<>(backend.windows())) {
                if (w != owner && w.scene() != null && !w.isClosed()) {
                    w.frame();
                }
            }
            owner.frame();
        }
    }

    /**
     * The owner's frame alone, the popup windows not framed since they changed: a frame order a
     * backend can deliver, since every window frames on its own schedule, and the one in which
     * the owner has to lay out what it publishes itself.
     */
    private void ownerFrameOnly(int key, int modifiers) {
        drive(scene).keyEvent(key, true, false, modifiers);
        drive(scene).keyEvent(key, false, false, modifiers);
        drive(scene).inputBatchEnded();
        runtime.drain();
        runtime.drain();
        owner.frame();
    }

    private void key(int key, int modifiers) {
        drive(scene).keyEvent(key, true, false, modifiers);
        drive(scene).keyEvent(key, false, false, modifiers);
        drive(scene).inputBatchEnded();
        frames();
    }

    private AccessibleTree tree() {
        return owner.bridge().tree();
    }

    private String describe() {
        return "\n" + AccessibleTrees.describe(tree());
    }

    private List<AccessibleNode> withRole(Accessible.Role role) {
        List<AccessibleNode> found = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                found.add(tree.node(i));
            }
        }
        return found;
    }

    private AccessibleNode parentOf(AccessibleNode node) {
        return node.parent() < 0 ? null : tree().node(node.parent());
    }

    private boolean isAncestor(AccessibleNode ancestor, AccessibleNode node) {
        for (AccessibleNode at = parentOf(node); at != null; at = parentOf(at)) {
            if (at.id() == ancestor.id()) {
                return true;
            }
        }
        return false;
    }

    private static ComboBox<String> status() {
        return new ComboBox<>(List.of("Open", "Paid", "Shipped")).setAccessibleName("Status");
    }

    private static Column column(limn.scene.Widget<?> child) {
        Column fields = new Column();
        fields.add(new SizedBox(160, 30, child));
        return fields;
    }

    /** No window but the owner's handed a tree to a bridge. */
    private void onlyTheOwnerPublishes() {
        for (HeadlessWindow w : backend.windows()) {
            if (w != owner) {
                assertEquals(0, w.bridge().publishes, "a popup window published a tree of its own");
            }
        }
    }

    /** The combo's list, published once, under the combo, with its three options. */
    private AccessibleNode assertListUnderTheCombo() {
        List<AccessibleNode> lists = withRole(Accessible.Role.LIST);
        assertEquals(1, lists.size(), "one list, published once" + describe());
        AccessibleNode list = lists.get(0);
        AccessibleNode combo = AccessibleTrees.withRole(tree(), Accessible.Role.COMBO_BOX);
        assertNotNull(combo, describe());
        assertEquals(combo.id(), parentOf(list).id(), "the list hangs under the combo" + describe());
        assertTrue(combo.has(Accessible.State.EXPANDED), describe());
        List<String> options = new ArrayList<>();
        for (AccessibleNode item : withRole(Accessible.Role.LIST_ITEM)) {
            options.add(item.name());
        }
        assertEquals(List.of("Open", "Paid", "Shipped"), options, "each option once" + describe());
        assertListGeometry(list, combo);
        return list;
    }

    /**
     * The list's box and its options' as the control case publishes them, a combo directly in a
     * window: the list as wide as the combo and as tall as the window it draws in, each option
     * 30 tall and 6 points in from the list's edges on both sides (148 wide under a 160 combo),
     * 30 points apart, and an option the list's window shows published {@code SHOWING}. Read
     * relative to the list, because a headless window stands at the screen's origin whatever its
     * owner asked (HeadlessWindow#screenX) and has a display of its own size, so where the list
     * lands against its opener, and how tall it, or a popover inside a popover, may grow, are the
     * fixture's and not the walk's.
     */
    private void assertListGeometry(AccessibleNode list, AccessibleNode combo) {
        HeadlessWindow listWindow = listWindow();
        assertEquals(combo.width(), list.width(), "the list is as wide as its combo" + describe());
        assertEquals(listWindow.logicalHeight(), list.height(),
                "and as tall as the window it draws in" + describe());
        float top = Float.NaN;
        for (AccessibleNode item : withRole(Accessible.Role.LIST_ITEM)) {
            assertEquals(list.width() - 12, item.width(), item.name() + describe());
            assertEquals(30f, item.height(), item.name() + describe());
            assertEquals(list.x() + 6, item.x(), item.name() + describe());
            if (!Float.isNaN(top)) {
                assertEquals(top + 30, item.y(), item.name() + describe());
            }
            top = item.y();
            boolean inView = item.y() >= list.y() && item.y() + item.height() <= list.y() + list.height();
            if (inView) {
                assertTrue(item.has(Accessible.State.SHOWING), item.name() + " is drawn" + describe());
            }
        }
        assertTrue(AccessibleTrees.named(tree(), "Open").has(Accessible.State.SHOWING), describe());
    }

    /** The window the combo's list draws in: the one showing a popup panel of options. */
    private HeadlessWindow listWindow() {
        for (HeadlessWindow w : backend.windows()) {
            if (w != owner && !w.isClosed() && w.scene() != null
                    && w.scene().root().getClass().getSimpleName().equals("PopupPanel")) {
                return w;
            }
        }
        throw new AssertionError("no list window among " + backend.windows());
    }

    // ------------------------------------------------------------- a combo inside the popover

    @Test
    void aComboListOpenedInsideAWindowedPopoverIsPublishedUnderTheComboWithItsCursor() {
        ComboBox<String> status = status();
        openPopover(column(status), DisplayMode.NATIVE_WINDOW);
        assertTrue(popover.isOpen());
        AccessibleNode group = AccessibleTrees.named(tree(), "Filter orders");
        assertNotNull(group, "sanity: the popover is grafted into the owner's tree" + describe());

        key(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(status.isOpen(), "sanity: Alt+Down in the owner opened the combo inside");
        AccessibleNode list = assertListUnderTheCombo();
        onlyTheOwnerPublishes();
        long before = tree().activeDescendant();
        assertNotEquals(0, before, "the list's cursor is the tree's active descendant" + describe());

        owner.bridge().events.clear();
        key(Keys.DOWN, 0);
        long after = tree().activeDescendant();
        assertNotEquals(before, after, "Down moved the cursor" + describe());
        AccessibleNode cursor = tree().find(after);
        assertNotNull(cursor, describe());
        assertSame(Accessible.Role.LIST_ITEM, cursor.role());
        assertEquals(list.id(), parentOf(cursor).id(), describe());
        assertFalse(owner.bridge().eventsOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED).isEmpty(),
                "a reader is told the cursor moved: " + owner.bridge().events);
        AccessibleNode focused = tree().find(tree().focused());
        assertNotNull(focused, describe());
        assertSame(Accessible.Role.COMBO_BOX, focused.role(), "the combo keeps the focus" + describe());

        key(Keys.ESCAPE, 0);
        settle();
        assertFalse(status.isOpen(), "sanity: Escape closed the list");
        assertTrue(popover.isOpen(), "and left the popover up");
        assertTrue(withRole(Accessible.Role.LIST).isEmpty(), "the list left the tree" + describe());
        assertTrue(withRole(Accessible.Role.LIST_ITEM).isEmpty(), describe());
        assertNotNull(AccessibleTrees.named(tree(), "Filter orders"), describe());
    }

    @Test
    void theNestedListIsLaidOutBeforeTheOwnerPublishesIt() {
        // The audit's harness framed the owner before the popup windows: the owner walked a list
        // whose scene had never laid out, and published it 0 by 0 with options -12 points wide
        // and not SHOWING; the next diff then threw on the negative box and the tree stayed so.
        // The owner lays out a popup grafted into it before it walks; it did not lay out one
        // grafted into that popup.
        ComboBox<String> status = status();
        openPopover(column(status), DisplayMode.NATIVE_WINDOW);
        ownerFrameOnly(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(status.isOpen());
        assertListUnderTheCombo();
        long before = tree().activeDescendant();
        ownerFrameOnly(Keys.DOWN, 0);
        assertNotEquals(before, tree().activeDescendant(), "a later walk still publishes"
                + describe());
        assertListUnderTheCombo();
    }

    @Test
    void theControlListIsLaidOutBeforeTheOwnerPublishesItToo() {
        // The same frame order for a combo directly in the window: the owner's own graft was
        // always laid out before the walk, which is what the nested case is held to.
        backend = new HeadlessBackend(runtime);
        owner = backend.open("Orders", 600, 400);
        ComboBox<String> status = status();
        scene = new Scene(column(status), () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
        scene.requestFocus(status);
        owner.frame();
        ownerFrameOnly(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(status.isOpen());
        assertListUnderTheCombo();
    }

    @Test
    void aReadersVerbOnAnOptionOfTheNestedListIsPerformed() {
        // The scene performs a verb only on a node of its own tree or of a popup grafted into it:
        // a popup grafted into the popover counts, or the options were published and refused.
        ComboBox<String> status = status();
        openPopover(column(status), DisplayMode.NATIVE_WINDOW);
        key(Keys.DOWN, Keys.MOD_ALT);
        AccessibleNode shipped = AccessibleTrees.named(tree(), "Shipped");
        assertNotNull(shipped, describe());
        Accessible.Action verb = shipped.accepts(Accessible.Action.SELECT)
                ? Accessible.Action.SELECT : Accessible.Action.PRESS;
        assertTrue(shipped.accepts(verb), "the option offers " + verb + describe());
        assertTrue(owner.bridge().host.perform(shipped.id(), verb, Accessible.Argument.NONE));
        frames();
        AccessibleNode after = AccessibleTrees.named(tree(), "Shipped");
        assertNotNull(after, describe());
        assertTrue(after.has(Accessible.State.SELECTED) && after.has(Accessible.State.ACTIVE),
                "performed in the list, two windows away from the tree" + describe());
    }

    @Test
    void thePopoverClosingWithItsComboListOpenTakesBothOutOfTheTree() {
        ComboBox<String> status = status();
        openPopover(column(status), DisplayMode.NATIVE_WINDOW);
        key(Keys.DOWN, Keys.MOD_ALT);
        assertListUnderTheCombo();

        popover.close();
        settle();
        assertFalse(popover.isOpen());
        assertNull(AccessibleTrees.named(tree(), "Filter orders"), describe());
        assertTrue(withRole(Accessible.Role.LIST).isEmpty(), "no list left behind" + describe());
        assertTrue(withRole(Accessible.Role.LIST_ITEM).isEmpty(), describe());
        assertNull(AccessibleTrees.withRole(tree(), Accessible.Role.COMBO_BOX), describe());
        for (HeadlessWindow w : backend.windows()) {
            assertTrue(w == owner || w.isClosed(), "every popup window closed with the popover");
        }
    }

    @Test
    void aComboInsideAnInScenePopoverIsPublishedOnceAsBefore() {
        // Wayland's only mode: the popover is a layer of the owner's scene, and the combo's list
        // opens from the owner's scene, which already published it. The fix must not add it twice.
        ComboBox<String> status = status();
        openPopover(column(status), DisplayMode.IN_SCENE);
        key(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(status.isOpen());
        assertListUnderTheCombo();
        long before = tree().activeDescendant();
        key(Keys.DOWN, 0);
        assertNotEquals(before, tree().activeDescendant(), describe());
    }

    @Test
    void aComboInsideAPopoverOpenedFromAPopoverIsPublishedTwoGraftsDown() {
        // A popup grafted into a popup grafted into a popup: the walk follows grafts to any
        // depth, and the one focused node is the combo, two popovers down.
        ComboBox<String> status = status();
        Popover inner = new Popover(column(status)).setAccessibleName("More filters");
        Button more = new Button("More");
        inner.attachTo(more);
        openPopover(column(more), DisplayMode.NATIVE_WINDOW);
        key(Keys.SPACE, 0);
        assertTrue(inner.isOpen(), "sanity: Space on More, inside the first popover, opened the second");
        AccessibleNode innerGroup = AccessibleTrees.named(tree(), "More filters");
        assertNotNull(innerGroup, "the second popover is in the owner's tree" + describe());
        AccessibleNode moreNode = AccessibleTrees.named(tree(), "More");
        assertEquals(moreNode.id(), parentOf(innerGroup).id(), "under its button" + describe());

        key(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(status.isOpen(), "sanity: the keys reached the combo two popovers down");
        assertListUnderTheCombo();
        onlyTheOwnerPublishes();
        List<AccessibleNode> focused = AccessibleTrees.withState(tree(), Accessible.State.FOCUSED);
        assertEquals(1, focused.size(), "one focused node" + describe());
        assertSame(Accessible.Role.COMBO_BOX, focused.get(0).role(), describe());
        long before = tree().activeDescendant();
        key(Keys.DOWN, 0);
        assertNotEquals(before, tree().activeDescendant(), "the cursor moves" + describe());
    }

    @Test
    void aDatePickersCalendarOpenedInsideAWindowedPopoverIsPublishedWithItsCursor() {
        limn.components.date.DatePicker picker = new limn.components.date.DatePicker()
                .setClock(java.time.Clock.fixed(java.time.Instant.parse("2026-09-16T12:00:00Z"),
                        java.time.ZoneOffset.UTC));
        picker.setDate(java.time.LocalDate.of(2026, 9, 9));
        openPopover(column(picker), DisplayMode.NATIVE_WINDOW);
        picker.open();
        frames();
        assertTrue(picker.isOpen(), "sanity: the calendar is open");
        assertSame(DisplayMode.NATIVE_WINDOW, picker.displayMode(), "in a window of its own");
        onlyTheOwnerPublishes();
        AccessibleNode cursor = tree().find(tree().effectiveFocus());
        assertNotNull(cursor, "the calendar's cursor is the effective focus" + describe());
        assertSame(Accessible.Role.CELL, cursor.role(), "a day of the calendar" + describe());
        assertTrue(isAncestor(AccessibleTrees.named(tree(), "Filter orders"), cursor),
                "published inside the popover's group, under the picker" + describe());
    }

    // --------------------------------------------------------- a menu inside the popover

    @Test
    void aTextFieldsMenuOpenedInsideAWindowedPopoverIsPublishedUnderTheField() {
        TextField customer = new TextField().setText("Kestrel").setAccessibleName("Customer contains");
        openPopover(column(customer), DisplayMode.NATIVE_WINDOW);
        key(Keys.F10, Keys.MOD_SHIFT);
        List<AccessibleNode> menus = withRole(Accessible.Role.MENU);
        assertEquals(1, menus.size(), "the field's menu is in the owner's tree, once" + describe());
        AccessibleNode field = AccessibleTrees.named(tree(), "Customer contains");
        assertNotNull(field, describe());
        assertTrue(isAncestor(field, menus.get(0)), "under the field" + describe());
        onlyTheOwnerPublishes();
        assertNotEquals(0, tree().activeDescendant(), "the menu's row is where the keyboard is"
                + describe());

        key(Keys.ESCAPE, 0);
        settle();
        assertTrue(withRole(Accessible.Role.MENU).isEmpty(), "the menu left the tree" + describe());
        assertTrue(popover.isOpen(), "Escape closed the menu and not the popover");
    }

    // ------------------------------------------------------------- the control case

    @Test
    void aComboDirectlyInAWindowStillPublishesItsListAndCursor() {
        backend = new HeadlessBackend(runtime);
        owner = backend.open("Orders", 600, 400);
        ComboBox<String> status = status();
        scene = new Scene(column(status), () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
        scene.requestFocus(status);
        frames();
        key(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(status.isOpen());
        assertListUnderTheCombo();
        onlyTheOwnerPublishes();
        long before = tree().activeDescendant();
        owner.bridge().events.clear();
        key(Keys.DOWN, 0);
        assertNotEquals(before, tree().activeDescendant(), describe());
        assertFalse(owner.bridge().eventsOf(AccessibleEvent.Type.ACTIVE_DESCENDANT_CHANGED).isEmpty());
    }
}
