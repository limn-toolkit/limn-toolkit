package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
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
import limn.demo.a11y.AccessibilityGallery.Fact;
import limn.demo.a11y.AccessibilityGallery.Step;
import limn.demo.a11y.GalleryStrings;
import limn.testing.HeadlessWindow;
import limn.demo.a11y.ReaderDriver;
import limn.demo.a11y.Transcript;
import limn.i18n.I18n;
import limn.scene.Scene;
import limn.scene.Widget;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * and told nobody. And each must leave the trees holding the facts it declares, which is what
 * holds its label true: a step that is loud and does the opposite of what its label says (a
 * Space that takes a row out of the selection, labelled as adding it) fails here and not in a
 * recipe written against the label.
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
            "tree-loading", "table", "calendar", "date-field", "date-picker",
            // Added 2026-09-15 (brief item 4 of the phase-3 fix round): the only script that makes
            // the application speak, so the three bridges' announcement paths can be heard in
            // phase 5. The guest recipes owe a section for it.
            "announcement",
            // Added 2026-09-23: the list's MULTI mode (decision 136), owed a reader run before 0.8.0.
            "list-multi",
            // Added 2026-09-23: the combo's open list, never driven by a reader before.
            "combo");

    /** The widgets H2 (with DT8, B9 and T7) found no reader run could be pointed at. */
    private static final List<Class<?>> WIDGETS_A_READER_MUST_HEAR = List.of(
            Table.class, Tree.class, CalendarView.class, DateField.class, DatePicker.class);

    /**
     * The roles whose widgets keep a cursor or a set of fields of their own inside one focusable
     * node, which a reader hears only through a run that walks them (H2's rule).
     */
    private static final Set<Accessible.Role> ROLES_A_READER_MUST_WALK = Set.of(
            Accessible.Role.TABLE, Accessible.Role.TREE, Accessible.Role.SPIN_BUTTON);

    /**
     * Gallery entries that publish one of {@link #ROLES_A_READER_MUST_WALK} and no reader script
     * drives, each with why. A new entry publishing those roles needs a script or a line here.
     */
    private static final Map<String, String> NOT_WALKED_BY_A_READER = Map.of(
            "Spinners", "a Spinner is one SPIN_BUTTON stepped by arrows, with no cursor inside it; "
                    + "no finding asked a reader run of it",
            "Colour picker", "its spin buttons are Spinners, one per colour channel and one for "
                    + "the opacity; no finding asked a reader run of the colour picker",
            "Colour picker button, open", "the same colour picker, opened from its button");

    /** How long a step may leave a row busy before the next is sent, in wall time. */
    private static final long BUSY_DEADLINE_MILLIS = 10_000;

    /**
     * Frames rendered while a popup window is still open before the next step is sent: the same
     * width the sibling suites settle with, nearly three times the eight frames
     * {@code Theme.animWindow} (0.16 s) takes at the harness's 20 ms step, plus the one that
     * destroys the window (NativePopupTeardownTest measures those nine).
     */
    private static final int FRAMES_OF_A_WINDOW_FADE = 24;

    @Test
    void everyStepDeclaresWhatItLeaves() {
        List<String> bare = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            List<Step> steps = entry.reader().steps();
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).facts().isEmpty()) {
                    bare.add(entry.reader().id() + " step " + (i + 1) + " " + steps.get(i).keys()
                            + " - " + steps.get(i).label());
                }
            }
        }
        assertTrue(bare.isEmpty(), "a step with no fact holds its label to nothing, and a label "
                + "that says the opposite of what the key does passes: " + bare);
    }

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

    /**
     * H2's rule and not only its list: every gallery entry whose trees publish a table, a tree or
     * a spin button is driven by a reader script, or shares a widget class with an entry that is,
     * or is named in {@link #NOT_WALKED_BY_A_READER} with its reason, so an entry that publishes
     * those roles through another widget cannot arrive without a run or a stated exemption.
     */
    @Test
    void everyEntryPublishingATableATreeOrASpinButtonIsWalkedByAReaderOrSaysWhyNot() {
        Set<Class<?>> driven = new HashSet<>();
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            driven.addAll(entry.covers());
        }
        List<String> unwalked = new ArrayList<>();
        Set<String> exemptions = new HashSet<>();
        for (Entry entry : AccessibilityGallery.entries()) {
            Set<Accessible.Role> published = new TreeSet<>();
            try (Harness harness = new Harness(Palette.LIGHT)) {
                for (HeadlessWindow window : harness.show(entry)) {
                    AccessibleTree tree = window.bridge().tree();
                    for (int i = 0; i < tree.nodeCount(); i++) {
                        if (ROLES_A_READER_MUST_WALK.contains(tree.node(i).role())) {
                            published.add(tree.node(i).role());
                        }
                    }
                }
            }
            if (published.isEmpty()) {
                continue;
            }
            boolean walked = entry.reader() != null
                    || entry.covers().stream().anyMatch(driven::contains);
            if (NOT_WALKED_BY_A_READER.containsKey(entry.name())) {
                exemptions.add(entry.name());
                assertTrue(!walked, "\"" + entry.name() + "\" is walked by a reader script and "
                        + "still named in NOT_WALKED_BY_A_READER; drop the line");
            } else if (!walked) {
                unwalked.add(entry.name() + " " + published);
            }
        }
        assertTrue(unwalked.isEmpty(), "these gallery entries publish roles a reader must walk and "
                + "no reader script drives them or their widgets; give one a script, or name it in "
                + "NOT_WALKED_BY_A_READER with why: " + unwalked);
        assertEquals(NOT_WALKED_BY_A_READER.keySet(), exemptions, "an exemption names an entry that "
                + "is gone or publishes none of " + ROLES_A_READER_MUST_WALK);
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

    /**
     * The driver puts the keyboard in on the first frame, the one that lays the entry out, and
     * not on a timer a slow guest start can beat: however long the UI thread runs before that
     * frame, nothing is focused and no focus line is printed; the frame focuses the entry's widget
     * with its box laid out, once.
     */
    @Test
    void theDriverPutsTheKeyboardInOnTheFrameThatLaysTheEntryOut() {
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            try (Harness harness = new Harness(Palette.LIGHT)) {
                AccessibilityGallery.Built built = entry.build();
                HeadlessWindow window = harness.open(entry.name());
                Scene scene = new Scene(built.root());
                scene.bind(window);
                List<String> printed = new ArrayList<>();
                ReaderDriver.placeKeyboardOnFirstFrame(window, scene, built, printed::add);
                harness.idle(10_000);
                assertTrue(scene.focusedWidget() == null && printed.isEmpty(), "\"" + entry.name()
                        + "\": the keyboard was placed before any frame laid the entry out: "
                        + printed + ", keyboard in " + describe(scene.focusedWidget()));
                window.frame();
                assertTrue(isWithin(scene.focusedWidget(), built.focus()), "\"" + entry.name()
                        + "\": the first frame did not put the keyboard in "
                        + built.focus().getClass().getSimpleName());
                assertTrue(built.focus().width() > 0 && built.focus().height() > 0, "\""
                        + entry.name() + "\": the widget focused is not laid out");
                assertEquals(List.of("--- focus " + scene.focusedWidget().getClass().getSimpleName()),
                        printed);
                window.frame();
                assertEquals(1, printed.size(), "the focus is placed once");
            }
        }
    }

    /**
     * The announcement entry says what it announces, and in which politeness (brief item 4 of the
     * phase-3 fix round, 2026-09-15). The pass above only knows that something happened; this is
     * what phase 5 reads to know what a reader should have heard, and it is the one place in the
     * gallery where the application speaks rather than a node changing.
     *
     * <p>The two sentences come from {@link GalleryStrings} since decision 68, so this reads them
     * from there: an English run hears the English they carry, and
     * {@code ReaderEntryLanguageTest.theAnnouncementsSpeakTheRunsLanguage} hears the pt-BR a
     * reader pass on a guest does.
     */
    @Test
    void theAnnouncementEntrySpeaksBothPolitenessLevels() {
        Entry entry = AccessibilityGallery.readerEntry("announcement");
        List<AccessibleEvent> spoken = new ArrayList<>();
        try (Harness harness = new Harness(Palette.LIGHT)) {
            harness.show(entry);
            Scene scene = harness.scenes.get(0);
            harness.built.focus().requestFocus();
            harness.settle();
            HeadlessWindow window = harness.windows().get(0);
            window.bridge().events.clear();
            for (Step step : entry.reader().steps()) {
                step.sendTo(scene);
                harness.settle();
            }
            for (AccessibleEvent event : window.bridge().events) {
                if (event.type() == AccessibleEvent.Type.ANNOUNCEMENT) {
                    spoken.add(event);
                }
            }
        }
        assertEquals(2, spoken.size(), "the two presses speak once each: " + spoken);
        assertEquals(GalleryStrings.SAVED.get(), spoken.get(0).newValue());
        assertEquals(Accessible.Politeness.POLITE, spoken.get(0).politeness());
        assertEquals(GalleryStrings.STOPPED.get(), spoken.get(1).newValue());
        assertEquals(Accessible.Politeness.ASSERTIVE, spoken.get(1).politeness(),
                "both levels, because the three platforms map them to different values");
    }

    @TestFactory
    Stream<DynamicTest> everyStepChangesWhatIsPublishedOrAnnouncesSomething() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : AccessibilityGallery.readerEntries()) {
            tests.add(DynamicTest.dynamicTest(entry.reader().id() + ", as built",
                    () -> runScript(entry, null, null)));
            tests.add(DynamicTest.dynamicTest(entry.reader().id() + ", in the scene",
                    () -> runScript(entry, DisplayMode.IN_SCENE, null)));
            // The language a run speaks (decision 65): a date field's segments come in another
            // order there, and a week may start on another day, so a step silent only in pt-BR
            // is found before a guest is spent on it.
            tests.add(DynamicTest.dynamicTest(entry.reader().id() + ", as built, in "
                            + ReaderDriver.READER_LOCALE.toLanguageTag(),
                    () -> runScript(entry, null, ReaderDriver.READER_LOCALE)));
        }
        return tests.stream();
    }

    /**
     * Runs one script the way the driver does: the entry built, the named widget focused after
     * the first layout, each step sent to the entry's scene and the scene settled; a step that
     * leaves a row busy is waited out, and so is a popup window's fade, as the driver's three
     * seconds wait both out. Then the step must have changed something, and its facts must hold.
     */
    private static void runScript(Entry entry, DisplayMode presentation, Locale locale) {
        Entry shown = presentation == null ? entry : new Entry(entry.name(), entry.covers(),
                entry.publishes(), () -> {
                    AccessibilityGallery.Built built = entry.build();
                    AccessibilityGallery.present(built.root(), presentation);
                    return built;
                }, entry.reader());
        try (Harness harness = new Harness(Palette.LIGHT)) {
            if (locale != null) {
                I18n.setLocale(locale);
            }
            harness.show(shown);
            Scene scene = harness.scenes.get(0);
            Widget<?> focus = harness.built.focus();
            focus.requestFocus();
            harness.settle();
            assertTrue(isWithin(scene.focusedWidget(), focus), "\"" + entry.name() + "\": the run "
                    + "focuses " + focus.getClass().getSimpleName() + " and the keyboard is in "
                    + scene.focusedWidget());
            List<String> silent = new ArrayList<>();
            List<String> untrue = new ArrayList<>();
            List<Step> steps = entry.reader().steps();
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                Snapshot before = Snapshot.of(harness);
                step.sendTo(scene);
                harness.settle();
                waitWhileBusy(harness);
                waitForWindowFades(harness);
                Snapshot after = Snapshot.of(harness);
                String line = "step " + (i + 1) + " " + step.keys() + " - " + step.label();
                if (before.equals(after)) {
                    silent.add(line + " (keyboard in " + describe(scene.focusedWidget()) + ")");
                }
                for (Fact fact : step.facts()) {
                    String unmet = unmet(fact, harness, locale == null);
                    if (unmet != null) {
                        untrue.add(line + ": expected " + fact + ", but " + unmet);
                    }
                }
            }
            if (!silent.isEmpty() || !untrue.isEmpty()) {
                StringBuilder trees = new StringBuilder();
                for (HeadlessWindow window : harness.windows()) {
                    trees.append("== window \"").append(window.title()).append("\" ==\n")
                            .append(Transcript.of(window.bridge().tree()));
                }
                fail("reader script \"" + entry.reader().id() + "\" on \"" + entry.name() + "\""
                        + (presentation == null ? "" : " in the scene")
                        + (locale == null ? "" : " in " + locale.toLanguageTag()) + ":\n"
                        + (silent.isEmpty() ? "" : silent.size() + " step(s) changed nothing "
                        + "published and announced nothing:\n  " + String.join("\n  ", silent)
                        + "\n")
                        + (untrue.isEmpty() ? "" : untrue.size() + " step(s) left a fact untrue:"
                        + "\n  " + String.join("\n  ", untrue) + "\n")
                        + "the trees at the end:\n" + trees);
            }
        } finally {
            I18n.setLocale(Locale.ENGLISH);
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

    /**
     * Renders out a window's fade while a popup window is still open, so the next step is sent to
     * the scene the driver's own three seconds would send it to: a picker reopened while the last
     * popup is still published leaves the field the controller of two of them, a state no run
     * reaches.
     *
     * <p>In scene time since 2026-09-15. A popup's scene takes its opener's clock
     * ({@code Scene#clock}), so the harness's frames advance the fade that closes the window, and
     * this waited in wall time only because they did not.
     */
    private static void waitForWindowFades(Harness harness) {
        List<HeadlessWindow> windows = harness.windows();
        boolean popup = false;
        for (int i = 1; i < windows.size(); i++) {
            popup |= !windows.get(i).isClosed();
        }
        if (!popup) {
            return;
        }
        harness.settle(FRAMES_OF_A_WINDOW_FADE);
    }

    /**
     * @param fact    what a step declared
     * @param harness the run, settled after the step
     * @param english whether the run is built in the language the facts' names are written in
     * @return what the trees hold instead, or {@code null} when the fact is true
     */
    private static String unmet(Fact fact, Harness harness, boolean english) {
        List<AccessibleTree> open = new ArrayList<>();
        for (HeadlessWindow window : harness.windows()) {
            if (!window.isClosed()) {
                open.add(window.bridge().tree());
            }
        }
        AccessibleTree host = open.get(0);
        switch (fact.subject()) {
            case CURSOR, FOCUSED -> {
                long id = fact.subject() == Fact.Subject.CURSOR ? host.effectiveFocus()
                        : host.focused();
                for (AccessibleTree tree : open) {
                    AccessibleNode node = tree.find(id);
                    if (node != null) {
                        String mismatch = mismatch(fact, tree, node, english);
                        return mismatch == null ? null : "it is " + line(tree, node)
                                + " (" + mismatch + ")";
                    }
                }
                return "no open window publishes the node " + id;
            }
            default -> {
                List<String> seen = new ArrayList<>();
                for (AccessibleTree tree : open) {
                    for (int i = 0; i < tree.nodeCount(); i++) {
                        AccessibleNode node = tree.node(i);
                        if (node.role() != fact.role()) {
                            continue;
                        }
                        if (fact.subject() == Fact.Subject.ROW
                                && !fact.row().equals(firstCellName(tree, node))) {
                            continue;
                        }
                        String mismatch = mismatch(fact, tree, node, english);
                        if (mismatch == null) {
                            return null;
                        }
                        seen.add(line(tree, node) + " (" + mismatch + ")");
                    }
                }
                return seen.isEmpty() ? "no open window publishes one" : "the candidates are " + seen;
            }
        }
    }

    private static String mismatch(Fact fact, AccessibleTree tree, AccessibleNode node,
                                   boolean english) {
        if (fact.role() != null && node.role() != fact.role()) {
            return "role";
        }
        if (english && fact.name() != null && !fact.name().equals(node.name())) {
            return "name";
        }
        if (english && fact.description() != null
                && !fact.description().equals(node.description())) {
            return "description \"" + node.description() + "\"";
        }
        if (english && fact.value() != null
                && (node.value() == null || !fact.value().equals(node.value().text()))) {
            return "value " + (node.value() == null ? "none" : "\"" + node.value().text() + "\"");
        }
        if (fact.row() != null && fact.subject() != Fact.Subject.ROW) {
            AccessibleNode row = node;
            while (row != null && row.role() != Accessible.Role.ROW) {
                row = row.parent() == AccessibleNode.NONE ? null : tree.node(row.parent());
            }
            String first = row == null ? null : firstCellName(tree, row);
            if (!fact.row().equals(first)) {
                return "in the row of " + (first == null ? "no row" : "\"" + first + "\"");
            }
        }
        for (Accessible.State state : fact.with()) {
            if (!node.has(state)) {
                return "not " + state;
            }
        }
        for (Accessible.State state : fact.without()) {
            if (node.has(state)) {
                return state.toString();
            }
        }
        return null;
    }

    private static String firstCellName(AccessibleTree tree, AccessibleNode row) {
        return row.firstChild() == AccessibleNode.NONE ? null
                : tree.node(row.firstChild()).name();
    }

    private static String line(AccessibleTree tree, AccessibleNode node) {
        String row = "";
        for (AccessibleNode at = node; at != null;
                at = at.parent() == AccessibleNode.NONE ? null : tree.node(at.parent())) {
            if (at.role() == Accessible.Role.ROW) {
                row = " in the row of \"" + firstCellName(tree, at) + "\"";
                break;
            }
        }
        return node.role() + " \"" + node.name() + "\"" + (node.description() == null
                || node.description().isEmpty() ? ""
                : " described \"" + node.description() + "\"")
                + (node.value() == null ? "" : " valued \"" + node.value().text() + "\"")
                + row + " " + node.states();
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

    private static boolean isWithin(Widget<?> widget, Widget<?> ancestor) {
        for (Widget<?> at = widget; at != null; at = at.parent()) {
            if (at == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Widget<?> widget) {
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
