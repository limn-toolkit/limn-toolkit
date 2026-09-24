package limn.demo.a11y;

import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.demo.a11y.AccessibilityGallery.Built;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LAB-NEW-13 and the settled reader-scene-clock: every date entry a reader is pointed at is built
 * on 2026-09-09, whatever the machine's clock says, and speaks the process's language — the reader
 * driver's pt-BR (decision 65) — rather than one the entry pins.
 *
 * <p>Built under a Brazilian Portuguese process locale, which is what the reader driver sets and
 * the lab's guests run, on a machine whose clock is whatever day the build happens on. Until
 * 2026-09-14 the calendar named that real day ", hoje", so a reader run on the Fedora guest (its
 * clock days behind) and one on Windows the same afternoon heard different cells. From 2026-09-14
 * to 2026-09-15 the entries pinned en-US over the process locale, which a pt-BR run would have
 * heard as English segment and cell names under a Portuguese voice; decision 65 withdrew that pin.
 */
class DateReaderEntriesTest {

    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    /** The pinned day as the reader driver's language writes it in a cell's name. */
    private static final String PINNED_TODAY = "9 de setembro de 2026, hoje";

    private HeadlessUi ui;
    private UiRuntime runtime;
    private long nanos;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> nanos);
        runtime = ui.runtime();
        I18n.setLocale(ReaderDriver.READER_LOCALE);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        backend = new HeadlessBackend(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        TextRulers.uninstall(HeadlessWindow.RULER);
        I18n.setLocale(Locale.ENGLISH);
        ui.close();
    }

    @Test
    void theCalendarEntryNamesThePinnedDayAsTodayInTheReadersLanguage() {
        AccessibleTree tree = show("Calendar grid").bridge().tree();
        assertEquals(List.of(PINNED_TODAY), todayCells(tree), Transcript.of(tree));
    }

    @Test
    void theOpenPickerEntrysCalendarNamesThePinnedDayAsToday() {
        AccessibleTree tree = show("Date picker, open").bridge().tree();
        assertEquals(List.of(PINNED_TODAY), todayCells(tree), Transcript.of(tree));
        assertSegmentsSpeakTheReadersLanguage(tree);
    }

    /**
     * The closed entry opens natively by default: its calendar is a window of its own, published in
     * the host's tree under the field.
     */
    @Test
    void theClosedPickerEntryOpensOnThePinnedDayInTheReadersLanguage() {
        HeadlessWindow host = show("Date picker, closed");
        AccessibleTree tree = host.bridge().tree();
        assertSegmentsSpeakTheReadersLanguage(tree);
        AccessibleNode field = null;
        for (int i = 0; i < tree.nodeCount() && field == null; i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.GROUP && node.expand() != null) {
                field = node;
            }
        }
        assertTrue(field != null, "a field that opens a calendar: " + Transcript.of(tree));
        assertTrue(host.bridge().host.perform(field.id(), Accessible.Action.EXPAND,
                Accessible.Argument.NONE));
        runtime.drain();
        settle();
        assertEquals(2, backend.windows().size(), "the calendar opened in a window of its own "
                + Transcript.of(host.bridge().tree()));
        settle();
        AccessibleTree opened = host.bridge().tree(); // the calendar is grafted under the field
        assertEquals(List.of(PINNED_TODAY), todayCells(opened), Transcript.of(opened));
    }

    @Test
    void theDateFieldEntryNamesItsSegmentsInTheReadersLanguage() {
        AccessibleTree tree = show("Date field, segmented").bridge().tree();
        assertSegmentsSpeakTheReadersLanguage(tree);
    }

    /** The same entry under the headless tests' English speaks English: nothing pins a language. */
    @Test
    void theEntriesFollowTheProcessLanguageAndPinNone() {
        I18n.setLocale(Locale.ENGLISH);
        AccessibleTree tree = show("Calendar grid").bridge().tree();
        assertEquals(List.of("September 9, 2026, today"), todayCells(tree), Transcript.of(tree));
    }

    private HeadlessWindow show(String name) {
        Entry entry = AccessibilityGallery.entry(name);
        Built built = entry.build();
        HeadlessWindow window = backend.open(name, 800, 640);
        Scene scene = new Scene(built.root(), () -> nanos);
        scene.bind(window);
        window.frame();
        window.desktopFocus(true);
        settle();
        built.afterFirstFrame().run();
        settle();
        return window;
    }

    private void settle() {
        for (int i = 0; i < 24; i++) {
            nanos += FRAME_NANOS;
            runtime.drain();
            for (HeadlessWindow window : backend.windows()) {
                window.frame();
            }
        }
    }

    /** Every cell whose name says it is today, which on a pinned clock is exactly one. */
    private static List<String> todayCells(AccessibleTree tree) {
        List<String> found = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.CELL && node.name() != null
                    && (node.name().contains(", today") || node.name().contains(", hoje"))) {
                found.add(node.name());
            }
        }
        return found;
    }

    private static void assertSegmentsSpeakTheReadersLanguage(AccessibleTree tree) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.SPIN_BUTTON) {
                names.add(node.name());
            }
        }
        assertFalse(names.isEmpty(), Transcript.of(tree));
        assertTrue(names.contains("Mês"), "the segments speak the reader's language: " + names);
        assertFalse(names.contains("Month"), names.toString());
    }
}
