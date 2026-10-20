package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleInvariants;
import limn.testing.AccessibleTrees;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.testing.StubWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popover holding nothing that takes the keyboard (a "Last synced 10:42" note, a legend, a
 * picture) still takes it, and the node a reader is put on is the popover's own: a group named
 * as the popover is, focusable and focused, in the scene as in a window of its own. And the panel
 * a popover draws in the scene, where its layer is that group, is no class an application is told
 * to fix.
 */
class PopoverNoControlsAccessibilityTest extends ComponentTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<LogRecord> logged = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private Logger walkLogger;

    @BeforeEach
    void captureTheWalksLog() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    @AfterEach
    void aPopoversPanelIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        for (LogRecord record : logged) {
            Object[] named = record.getParameters();
            String subject = named == null || named.length == 0 ? "" : String.valueOf(named[0]);
            assertFalse(subject.contains("limn.components.Popover"),
                    "a warning naming a class of the toolkit an application cannot reach: " + subject + " "
                            + record.getMessage());
        }
    }

    private static Popover note() {
        return new Popover(new Label("Last synced 10:42")).setAccessibleName("Sync status");
    }

    private static AccessibleNode focusedNode(AccessibleTree tree) {
        List<AccessibleNode> focused = AccessibleTrees.withState(tree, Accessible.State.FOCUSED);
        assertEquals(1, focused.size(), "one focused node: " + AccessibleTrees.describe(tree));
        return focused.get(0);
    }

    private static void theFocusedNodeIsTheNamedGroup(AccessibleTree tree, List<AccessibleEvent> focusEvents) {
        AccessibleNode node = focusedNode(tree);
        assertSame(Accessible.Role.GROUP, node.role(), "the popover's group: " + AccessibleTrees.describe(tree));
        assertEquals("Sync status", node.name());
        assertTrue(node.has(Accessible.State.FOCUSABLE), "focused, so focusable: " + AccessibleTrees.describe(tree));
        assertTrue(node.actions().has(Accessible.Action.CANCEL), "Escape's cancel");
        assertNotNull(AccessibleTrees.named(tree, "Last synced 10:42"), "the note inside it");
        assertTrue(focusEvents.stream().anyMatch(e -> e.nodeId() == node.id()),
                "the focus event names the group: " + focusEvents);
        assertEquals(List.of(), AccessibleInvariants.violations("Orders", tree));
    }

    @Test
    void inTheSceneTheKeyboardIsOnTheNamedGroup() {
        // The only presentation on Wayland: the focus went to the panel, published as an UNKNOWN
        // node with no name under the group, the node the focus event named.
        Button sync = new Button("Sync");
        Column root = new Column();
        root.add(sync);
        AccessibleHarness harness = new AccessibleHarness(runtime, root, new StubWindow(false));
        harness.scene.setTextRuler(RULER); // so the note inside measures, and the tree's boxes hold
        harness.focus(sync);
        Popover popover = note();
        popover.showFor(sync);
        harness.frame();
        assertSame(DisplayMode.IN_SCENE, popover.displayMode(), "sanity: in the scene");
        theFocusedNodeIsTheNamedGroup(harness.tree(), harness.bridge.events.stream()
                .filter(e -> e.type() == AccessibleEvent.Type.FOCUS_CHANGED).toList());
        assertTrue(AccessibleTrees.withRole(harness.tree(), Accessible.Role.UNKNOWN) == null,
                "no unknown node: " + harness.describe());

        drive(harness.scene).press(Keys.ESCAPE);
        harness.frame();
        assertFalse(popover.isOpen(), "Escape on the group closes it");
        assertSame(sync, harness.scene.focusedWidget(), "and the keyboard is back on the button");
    }

    @Test
    void inAWindowOfItsOwnTheFocusedGroupIsFocusable() {
        // In a window of its own the panel is the group, and the root of a popup the owner's keys
        // drive: published focused and, by the root's rule, not focusable.
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow owner = backend.open("Orders", 400, 300);
        Button sync = new Button("Sync");
        Column root = new Column();
        root.add(sync);
        Scene scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
        scene.requestFocus(sync);
        Popover popover = note();
        popover.showFor(sync);
        for (int i = 0; i < 3; i++) {
            runtime.drain();
            for (HeadlessWindow w : new ArrayList<>(backend.windows())) {
                w.frame();
            }
        }
        assertSame(DisplayMode.NATIVE_WINDOW, popover.displayMode(), "sanity: a window of its own");
        theFocusedNodeIsTheNamedGroup(owner.bridge().tree(),
                owner.bridge().eventsOf(AccessibleEvent.Type.FOCUS_CHANGED));
    }

    @Test
    void inTheScenePanelIsDecorationAndSaysSo() throws Exception {
        // The walk warns once per process per class, so whichever test reached it first decides
        // what the log shows: the panel's own answer is read too.
        Button sync = new Button("Sync");
        Column root = new Column();
        root.add(sync);
        AccessibleHarness harness = new AccessibleHarness(runtime, root, new StubWindow(false));
        Popover popover = new Popover(new Button("Sync now")).setAccessibleName("Sync status");
        popover.showFor(sync);
        harness.frame();
        Widget<?> panel = popover.content().parent();
        assertNotNull(panel);
        java.lang.reflect.Method decoration = panel.getClass().getDeclaredMethod("paintsDecoration");
        decoration.setAccessible(true);
        assertEquals(true, decoration.invoke(panel),
                "its fill and shadow tell a reader nothing the group does not");
    }
}
