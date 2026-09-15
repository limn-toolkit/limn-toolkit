package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.demo.AccessibleGalleryTest.Harness;
import limn.demo.AccessibleGalleryTest.Palette;
import limn.demo.a11y.AccessibilityGallery;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.demo.a11y.HeadlessWindow;
import limn.demo.a11y.Transcript;
import limn.components.ColorPickerButton;
import limn.components.ComboBox;
import limn.components.DisplayMode;
import limn.components.MenuBar;
import limn.components.date.DatePicker;
import limn.concurrent.Subscription;
import limn.scene.Change;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ADR 039 §1.5's verb policy, ratcheted over the whole gallery (decision 2; semantics 5): <b>a
 * node accepts exactly the parameterless verbs it publishes.</b> The published snapshot is the
 * only synchronous authority a bridge has — {@code Host#perform} answers from it and posts, and
 * a later refusal on the UI thread reaches no platform — so a widget that performs a verb it did
 * not publish is a control a reader cannot see and one platform will invoke by accident, and a
 * widget that publishes a verb it refuses is a promise every platform breaks. This test performs,
 * on every node of every gallery entry, every parameterless verb the node does <em>not</em>
 * publish, and asserts that nothing moved: the transcript of every window is the same afterwards,
 * and the entry's scene announced no change.
 *
 * <p>The published verbs themselves are each widget's own test's business; this is the
 * complement, and it is what stops a synonym being accepted in silence.
 *
 * <p>The ratchet runs twice over the gallery: as each entry is built, and again with every
 * surface that can float above the page asked to open as an overlay of the scene, and a menu bar's
 * first menu opened (ADR 039 §1.13, amended 2026-09-15). In both, before any verb is sent, it holds
 * the central rule on the entry's window: a node outside the layer that owns input — outside the
 * in-scene overlay's subtree, or anywhere in a window a native modal blocks — publishes no verb and
 * no setter, because the scene refuses every one there. {@link #IN_SCENE_OVERLAYS} names the
 * entries whose second run has an overlay open, so the rule cannot pass by an overlay failing to
 * open.
 *
 * <p>{@link #ALLOWLIST} names the nodes that accept an unpublished verb today, each keyed by the
 * item that owns the fix. An entry there is held to the opposite promise: the moment its node
 * refuses everything it did not publish, the entry is stale and the test says so, so the list
 * can only shrink.
 */
class VerbPolicyRatchetTest {

    /** Frames rendered after a verb is posted: one to run it, one to publish what it changed. */
    private static final int FRAMES_AFTER_A_VERB = 2;

    /**
     * A node that accepts a verb it does not publish, owed to a named item. Matched by the entry
     * it appears in, the node's role and the node's name; {@code verbs} says which unpublished
     * verbs it accepts, so an entry cannot cover more than what was found.
     *
     * @param item   the item id that owns the fix, and the lane it is in
     * @param entry  the gallery entry's name
     * @param role   the node's role
     * @param name   the node's name, or {@code null} for every node of that role in the entry
     * @param verbs  the unpublished verbs the node accepts today
     */
    record Exemption(String item, String entry, Accessible.Role role, String name,
                     Set<Accessible.Action> verbs) {
        boolean covers(String inEntry, AccessibleNode node, Accessible.Action verb) {
            return entry.equals(inEntry) && role == node.role()
                    && (name == null || name.equals(node.name())) && verbs.contains(verb);
        }
    }

    /**
     * What accepts an unpublished verb today, and who empties each line: the widget lanes of the
     * 2026-09-13 pass. Empty when the policy holds everywhere. Found by this test's first run
     * (2026-09-14) over the gallery as it then stood; the DatePicker group (decision 18) and the
     * calendar title (WINDOWS-NEW-10) the pass expected here are not: the open picker's group
     * sits under a modal overlay and refuses everything, and the title refuses {@code EXPAND}
     * rather than accepting it — that defect is a bridge vending a pattern, not a widget
     * accepting a verb, and semantics 5 closes it on the bridge.
     */
    static final List<Exemption> ALLOWLIST = List.of(
            // The three CRIT-1 lines (menu titles and a submenu row accepting EXPAND and PRESS
            // unpublished) were struck on 2026-09-14: the titles and the rows publish EXPAND
            // while closed and COLLAPSE while open, and refuse PRESS.
            // The two TABLE-NEW-13 lines (a cell and a column header accepting SELECT, their keys
            // read as row indices) were struck the same day: cells are keyed by row and column
            // together and header cells distinctly, and each publishes the verbs it accepts.
    );

    /**
     * The entries whose run with its surfaces in the scene has an in-scene overlay open at rest.
     * Exactly these: an entry that opens one and is not listed, or is listed and opens none,
     * fails, so the central rule is never held over a scene that has nothing covering it. The
     * popup menu and the dialog in its own window are built open inside the entry, where no
     * presentation can be asked of them first, and stay windows of their own.
     */
    static final Set<String> IN_SCENE_OVERLAYS = Set.of(
            "Combo box, open", "Date picker, open", "Menu bar", "Dialog, in the scene",
            "Colour picker button, open");

    // ------------------------------------------------------------------------- the ratchet

    @TestFactory
    Stream<DynamicTest> everyNodeRefusesEveryVerbItDoesNotPublish() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            tests.add(DynamicTest.dynamicTest(entry.name(), () -> check(entry, false)));
        }
        return tests.stream();
    }

    /**
     * The same ratchet with every surface that can be an overlay of the scene presented as one
     * and opened where the entry opens it, and a menu bar's first menu down: the layer beneath
     * such an overlay is where a published verb the scene refuses was found (fix round 2c,
     * 2026-09-15), so the rule is ratcheted there too.
     */
    @TestFactory
    Stream<DynamicTest> everyNodeRefusesEveryVerbItDoesNotPublishWithItsSurfacesInTheScene() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            tests.add(DynamicTest.dynamicTest(entry.name(), () -> check(entry, true)));
        }
        return tests.stream();
    }

    /** An exemption names an entry that exists, so a renamed entry cannot orphan one in silence. */
    @Test
    void everyExemptionNamesAGalleryEntry() {
        for (Exemption exemption : ALLOWLIST) {
            AccessibilityGallery.entry(exemption.entry());
        }
    }

    /**
     * One entry: every node, every unpublished parameterless verb. The tree is read by index
     * rather than by identifier, because an accepted verb rebuilds the entry from scratch to
     * continue from a clean state, and a fresh build mints fresh identifiers over the same shape.
     */
    private static void check(Entry entry, boolean inScene) {
        List<String> violations = new ArrayList<>();
        Set<Exemption> used = new LinkedHashSet<>();
        Run run = new Run(entry, inScene);
        try {
            checkNothingOutsideTheInputLayerIsOperable(entry, inScene,
                    run.windows.get(0).bridge().tree());
            for (int w = 0; w < run.windows.size(); w++) {
                for (int i = 0; i < run.windows.get(w).bridge().tree().nodeCount(); i++) {
                    for (Accessible.Action verb : Accessible.Action.values()) {
                        if (!verb.isParameterless()) {
                            continue;
                        }
                        AccessibleNode node = run.windows.get(w).bridge().tree().node(i);
                        if (node.actions() != null && node.actions().has(verb)) {
                            continue;
                        }
                        String moved = run.perform(w, node.id(), verb);
                        if (moved == null) {
                            continue;
                        }
                        Exemption exemption = exemptionFor(entry.name(), node, verb);
                        if (exemption != null) {
                            used.add(exemption);
                        } else {
                            violations.add(describe(node) + " accepted " + verb
                                    + ", which it does not publish (" + published(node) + "): "
                                    + moved);
                        }
                        // Whatever it did, it did: start the entry over so the next verb is
                        // asked of the tree the entry publishes at rest.
                        run.close();
                        run = new Run(entry, inScene);
                    }
                }
            }
        } finally {
            run.close();
        }
        if (!violations.isEmpty()) {
            fail("gallery entry \"" + entry.name() + "\": " + violations.size()
                    + " node(s) accepted a verb they do not publish (ADR 039 §1.5, amended "
                    + "2026-09-14: a node accepts exactly the parameterless verbs it publishes):\n  "
                    + String.join("\n  ", violations));
        }
        for (Exemption exemption : ALLOWLIST) {
            if (exemption.entry().equals(entry.name()) && !used.contains(exemption)) {
                fail("the allowlist entry " + exemption + " is stale: no node of that role and "
                        + "name in \"" + entry.name() + "\" accepts any of those unpublished verbs "
                        + "any more; strike it off");
            }
        }
    }

    /**
     * The central rule on one window's tree at rest (ADR 039 §1.9 and §1.13, amended 2026-09-15;
     * semantics 5): a node outside the layer that owns input publishes no verb, no writable value
     * and no editable text. Outside is outside the subtree of the node published {@code MODAL}
     * (the top in-scene overlay), or anywhere in a window whose own node is not {@code ENABLED}
     * (a native modal blocks it).
     */
    private static void checkNothingOutsideTheInputLayerIsOperable(Entry entry, boolean inScene,
                                                                  AccessibleTree tree) {
        int modal = -1;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).has(Accessible.State.MODAL)) {
                modal = i;
            }
        }
        if (inScene) {
            boolean expected = IN_SCENE_OVERLAYS.contains(entry.name());
            if (expected != (modal >= 0)) {
                fail("gallery entry \"" + entry.name() + "\" with its surfaces in the scene "
                        + (expected ? "opened no in-scene overlay, which IN_SCENE_OVERLAYS says "
                        + "it does" : "opened an in-scene overlay IN_SCENE_OVERLAYS does not "
                        + "name; add it") + ":\n" + Transcript.of(tree));
            }
        }
        boolean blocked = !tree.node(0).has(Accessible.State.ENABLED);
        if (modal < 0 && !blocked) {
            return;
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (!blocked && isWithin(tree, i, modal)) {
                continue;
            }
            AccessibleNode node = tree.node(i);
            if (node.actions() != null) {
                violations.add(describe(node) + " " + published(node));
            }
            if (node.value() != null && !node.value().readOnly()) {
                violations.add(describe(node) + " publishes a writable value");
            }
            if (node.text() != null && !node.has(Accessible.State.READ_ONLY)) {
                violations.add(describe(node) + " publishes editable text");
            }
        }
        if (!violations.isEmpty()) {
            fail("gallery entry \"" + entry.name() + "\"" + (inScene ? " with its surfaces in the "
                    + "scene" : "") + ": " + violations.size() + " node(s) outside the layer that "
                    + "owns input publish an operation the scene refuses (ADR 039 §1.13, amended "
                    + "2026-09-15):\n  " + String.join("\n  ", violations) + "\n"
                    + Transcript.of(tree));
        }
    }

    private static boolean isWithin(AccessibleTree tree, int index, int ancestor) {
        for (int at = index; at != AccessibleNode.NONE; at = tree.node(at).parent()) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static Exemption exemptionFor(String entry, AccessibleNode node,
                                          Accessible.Action verb) {
        for (Exemption exemption : ALLOWLIST) {
            if (exemption.covers(entry, node, verb)) {
                return exemption;
            }
        }
        return null;
    }

    private static String describe(AccessibleNode node) {
        return node.role() + " \"" + node.name() + "\"";
    }

    private static String published(AccessibleNode node) {
        return node.actions() == null ? "no verb published" : "publishes " + node.actions().actions();
    }

    // -------------------------------------------------------------------------- one build

    /** One build of an entry, settled, with the entry's scene watched for changes. */
    private static final class Run implements AutoCloseable {
        final Harness harness = new Harness(Palette.LIGHT);
        final List<HeadlessWindow> windows;
        final List<String> changes = new ArrayList<>();
        private final Subscription watching;

        Run(Entry entry, boolean inScene) {
            windows = new ArrayList<>(harness.show(inScene ? inTheScene(entry) : entry));
            Scene scene = harness.scenes.get(0);
            if (inScene) {
                openAMenuBar(windows.get(0));
            }
            watching = scene.observeChanges((source, change) -> {
                if (change.aspect() != Change.Aspect.LAYOUT) {
                    changes.add(change.aspect() + "/" + change.origin() + " on "
                            + source.getClass().getSimpleName());
                }
            });
            // Whatever the settle left in flight is not this test's: a verb is asked of a scene
            // that has gone quiet, and only what it does after that counts.
            harness.settle(FRAMES_AFTER_A_VERB);
            changes.clear();
        }

        /**
         * The entry with every surface that can float above the page asked to open as an overlay
         * of the scene: a combo's list, a date picker's calendar, a menu bar's cascade and a
         * colour button's dialog. Set on the built tree before the harness binds it, so whatever
         * the entry opens after its first frame opens in the scene.
         */
        private static Entry inTheScene(Entry entry) {
            return new Entry(entry.name(), entry.covers(), entry.publishes(), () -> {
                AccessibilityGallery.Built built = entry.build();
                presentInScene(built.root());
                return built;
            });
        }

        private static void presentInScene(Widget widget) {
            if (widget instanceof ComboBox combo) {
                combo.setDisplayMode(DisplayMode.IN_SCENE);
            } else if (widget instanceof DatePicker picker) {
                picker.setDisplayMode(DisplayMode.IN_SCENE);
            } else if (widget instanceof MenuBar bar) {
                bar.setDisplayMode(DisplayMode.IN_SCENE);
            } else if (widget instanceof ColorPickerButton button) {
                button.setPickerDisplayMode(DisplayMode.IN_SCENE);
            }
            for (Widget child : widget.children()) {
                presentInScene(child);
            }
        }

        /**
         * Opens the first menu of a menu bar in the window, through the verb its title publishes,
         * because no entry opens one and beneath its cascade is where the defect this run holds
         * was found.
         */
        private void openAMenuBar(HeadlessWindow window) {
            AccessibleTree tree = window.bridge().tree();
            for (int i = 0; i < tree.nodeCount(); i++) {
                AccessibleNode node = tree.node(i);
                if (node.role() == Accessible.Role.MENU_ITEM && node.parent() != AccessibleNode.NONE
                        && tree.node(node.parent()).role() == Accessible.Role.MENU_BAR
                        && node.actions() != null
                        && node.actions().has(Accessible.Action.SHOW_MENU)) {
                    assertTrue(window.bridge().host.perform(node.id(), Accessible.Action.SHOW_MENU,
                            Accessible.Argument.NONE));
                    harness.settle();
                    return;
                }
            }
        }

        /**
         * Performs one verb on one node the way a bridge does, and reads what moved.
         *
         * @return {@code null} when nothing moved; otherwise what did, for the message
         */
        String perform(int window, long nodeId, Accessible.Action verb) {
            List<String> before = transcripts();
            int windowsBefore = harness.windows().size();
            boolean accepted = windows.get(window).bridge().host.perform(
                    nodeId, verb, Accessible.Argument.NONE);
            harness.settle(FRAMES_AFTER_A_VERB);
            List<String> after = transcripts();
            StringBuilder moved = new StringBuilder();
            if (!accepted) {
                moved.append("the host refused it from the snapshot; ");
            }
            if (!changes.isEmpty()) {
                moved.append("the scene announced ").append(changes).append("; ");
            }
            if (harness.windows().size() != windowsBefore) {
                moved.append("the window count went from ").append(windowsBefore).append(" to ")
                        .append(harness.windows().size()).append("; ");
            }
            for (int w = 0; w < Math.min(before.size(), after.size()); w++) {
                if (!before.get(w).equals(after.get(w))) {
                    moved.append("window ").append(w).append("'s transcript changed: ")
                            .append(firstDifference(before.get(w), after.get(w))).append("; ");
                }
            }
            return moved.isEmpty() ? null : moved.toString();
        }

        private List<String> transcripts() {
            List<String> out = new ArrayList<>();
            for (HeadlessWindow window : harness.windows()) {
                AccessibleTree tree = window.bridge().tree();
                out.add(Transcript.of(tree));
            }
            return out;
        }

        private static String firstDifference(String before, String after) {
            String[] a = before.split("\n");
            String[] b = after.split("\n");
            StringBuilder out = new StringBuilder();
            if (a.length != b.length) {
                out.append(a.length).append(" lines became ").append(b.length).append("; ");
            }
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                String x = i < a.length ? a[i] : "(no line)";
                String y = i < b.length ? b[i] : "(no line)";
                if (!x.strip().equals(y.strip())) {
                    return out.append("[").append(x.strip()).append("] became [")
                            .append(y.strip()).append("]").toString();
                }
            }
            return out.append("(only the numbering moved)").toString();
        }

        @Override
        public void close() {
            watching.cancel();
            harness.close();
        }
    }

    /** The allowlist's shape is documented above; this keeps the class from being empty-tested. */
    @Test
    void theAllowlistIsKeyedByAnItemId() {
        for (Exemption exemption : ALLOWLIST) {
            assertTrue(exemption.item().matches("[A-Z]+(-[A-Z]+)*-?[0-9]+.*|decision [0-9]+.*"),
                    "an allowlist entry is keyed by the item or decision that owns the fix: "
                            + exemption);
        }
    }
}
