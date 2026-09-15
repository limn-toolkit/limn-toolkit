package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleTree;
import limn.components.DisplayMode;
import limn.components.date.CalendarView;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.components.table.Table;
import limn.components.tree.Tree;
import limn.demo.AccessibleGalleryTest.Harness;
import limn.demo.AccessibleGalleryTest.Palette;
import limn.demo.a11y.AccessibilityGallery;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.demo.a11y.AccessibilityGallery.Step;
import limn.demo.a11y.HeadlessWindow;
import limn.demo.a11y.Transcript;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The reader scripts on the accessibility gallery's entries, held headlessly before a guest is
 * spent on them (decision 24; H2's ratchet).
 *
 * <p>Every step of every script is sent through the scene's key path, as the reader driver sends
 * it, with the entry's surfaces presented as built (a date picker's calendar in a window of its
 * own) and again in the scene; each must change a published tree or announce something to a
 * bridge. <b>A silent step is a finding</b>: either the script presses a key that does nothing
 * there, and a reader run would record silence and blame the bridge, or the widget took the key
 * and told nobody.
 *
 * <p>And the scripts are held complete in both directions: every id the guest recipes are written
 * against ({@link #RECIPE_IDS}, the list the gallery lane's log publishes) names a script, no
 * script exists that the list does not name, and every widget H2 found no reader run for is driven
 * by one.
 */
class ReaderStepsTest {

    /**
     * The reader ids the bridge lanes' clients and phase 5's run recipes are written against, as
     * {@code lanes/gallery-log.md} lists them. A script renamed here is renamed there too.
     */
    static final List<String> RECIPE_IDS = List.of(
            "tree-loading", "table", "calendar", "date-field", "date-picker");

    /** The widgets H2 (with DT8, B9 and T7) found no reader run could be pointed at. */
    private static final List<Class<?>> WIDGETS_A_READER_MUST_HEAR = List.of(
            Table.class, Tree.class, CalendarView.class, DateField.class, DatePicker.class);

    /** How long a step may leave a row busy before the next is sent, in wall time. */
    private static final long BUSY_DEADLINE_MILLIS = 10_000;

    @Test
    void everyIdTheRecipesNameIsAScriptAndEveryScriptIsNamed() {
        List<String> scripted = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            scripted.add(entry.reader().id());
        }
        assertEquals(scripted.size(), new HashSet<>(scripted).size(),
                "two scripts share an id: " + scripted);
        for (String id : RECIPE_IDS) {
            AccessibilityGallery.readerEntry(id);
        }
        assertEquals(Set.copyOf(RECIPE_IDS), Set.copyOf(scripted), "the scripts and the ids the "
                + "recipes name (gallery-log.md) must be the same set");
    }

    @Test
    void everyWidgetAReaderMustBeHeardOnIsDrivenByAScript() {
        Set<Class<?>> driven = new HashSet<>();
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            driven.addAll(entry.covers());
        }
        for (Class<?> widget : WIDGETS_A_READER_MUST_HEAR) {
            assertTrue(driven.contains(widget), widget.getSimpleName() + " is covered by no "
                    + "gallery entry a reader script drives");
        }
    }

    @Test
    void everyScriptedEntryNamesTheWidgetItsRunFocuses() {
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            try (Harness harness = new Harness(Palette.LIGHT)) {
                harness.show(entry);
                assertTrue(harness.built.focus() != null, "\"" + entry.name() + "\" has a reader "
                        + "script and names no widget to focus");
            }
        }
    }

    @TestFactory
    Stream<DynamicTest> everyStepChangesWhatIsPublishedOrAnnouncesSomething() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            tests.add(DynamicTest.dynamicTest(entry.reader().id() + ", as built",
                    () -> runScript(entry, null)));
            tests.add(DynamicTest.dynamicTest(entry.reader().id() + ", in the scene",
                    () -> runScript(entry, DisplayMode.IN_SCENE)));
        }
        return tests.stream();
    }

    /**
     * Runs one script the way the driver does: the entry built, the named widget focused after
     * the first layout, each step sent to the entry's scene and the scene settled; a step that
     * leaves a row busy is waited out, as the driver's three seconds wait it out.
     */
    private static void runScript(Entry entry, DisplayMode presentation) {
        Entry shown = presentation == null ? entry : new Entry(entry.name(), entry.covers(),
                entry.publishes(), () -> {
                    AccessibilityGallery.Built built = entry.build();
                    AccessibilityGallery.present(built.root(), presentation);
                    return built;
                }, entry.reader());
        try (Harness harness = new Harness(Palette.LIGHT)) {
            harness.show(shown);
            Scene scene = harness.scenes.get(0);
            Widget focus = harness.built.focus();
            focus.requestFocus();
            harness.settle();
            assertTrue(isWithin(scene.focusedWidget(), focus), "\"" + entry.name() + "\": the run "
                    + "focuses " + focus.getClass().getSimpleName() + " and the keyboard is in "
                    + scene.focusedWidget());
            List<String> silent = new ArrayList<>();
            List<Step> steps = entry.reader().steps();
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                Snapshot before = Snapshot.of(harness);
                step.sendTo(scene);
                harness.settle();
                waitWhileBusy(harness);
                Snapshot after = Snapshot.of(harness);
                if (before.equals(after)) {
                    silent.add("step " + (i + 1) + " " + step.keys() + " - " + step.label()
                            + " (keyboard in " + describe(scene.focusedWidget()) + ")");
                }
            }
            if (!silent.isEmpty()) {
                StringBuilder trees = new StringBuilder();
                for (HeadlessWindow window : harness.windows()) {
                    trees.append("== window \"").append(window.title()).append("\" ==\n")
                            .append(Transcript.of(window.bridge().tree()));
                }
                fail("reader script \"" + entry.reader().id() + "\" on \"" + entry.name() + "\""
                        + (presentation == null ? "" : " in the scene") + ": " + silent.size()
                        + " step(s) changed nothing published and announced nothing:\n  "
                        + String.join("\n  ", silent) + "\nthe trees at the end:\n" + trees);
            }
        }
    }

    /** Settles frame by frame, in wall time too, while any published node is busy. */
    private static void waitWhileBusy(Harness harness) {
        long deadline = System.currentTimeMillis() + BUSY_DEADLINE_MILLIS;
        while (anyBusy(harness)) {
            if (System.currentTimeMillis() > deadline) {
                fail("a row stayed busy past " + BUSY_DEADLINE_MILLIS + " ms");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            harness.settle(1);
        }
        harness.settle(2);
    }

    private static boolean anyBusy(Harness harness) {
        for (HeadlessWindow window : harness.windows()) {
            AccessibleTree tree = window.bridge().tree();
            for (int i = 0; i < tree.nodeCount(); i++) {
                if (tree.node(i).has(Accessible.State.BUSY)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isWithin(Widget widget, Widget ancestor) {
        for (Widget at = widget; at != null; at = at.parent()) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Widget widget) {
        return widget == null ? "nothing" : widget.getClass().getSimpleName();
    }

    /**
     * What a bridge has been told across every window: each tree's transcript and how many events
     * each window's bridge was handed. Two snapshots are equal when nothing was published or
     * announced between them.
     */
    private record Snapshot(List<String> transcripts, List<Integer> events) {
        static Snapshot of(Harness harness) {
            List<String> transcripts = new ArrayList<>();
            List<Integer> events = new ArrayList<>();
            for (HeadlessWindow window : harness.windows()) {
                transcripts.add(Transcript.of(window.bridge().tree()));
                events.add(window.bridge().events.size());
            }
            return new Snapshot(transcripts, events);
        }
    }
}
