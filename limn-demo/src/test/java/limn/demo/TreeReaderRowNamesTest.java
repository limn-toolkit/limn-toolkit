package limn.demo;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.demo.a11y.HeadlessBackend;
import limn.demo.a11y.HeadlessWindow;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
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
 * Every row of the reader scene has a name (TREE-ROW-NAME). The L4 baseline on Fedora read the
 * rows as {@code name=''}: their cells are composites — an icon, a label and a count or a
 * button in a {@code Row} — and the text sat in a {@code Label} child, so Orca's name generator
 * yielded nothing for the row and never spoke it. The tree names such a row from its labels.
 */
class TreeReaderRowNamesTest {

    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private HeadlessUi ui;
    private UiRuntime runtime;
    private long nanos;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> nanos);
        runtime = ui.runtime();
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        backend = new HeadlessBackend(runtime);
    }

    @AfterEach
    void uninstallRuntime() {
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    @Test
    void everyRowOfTheReaderSceneHasANameReadFromItsCell() {
        // The reader run's tree since 2026-09-15 (decision 24): the gallery entry that replaced
        // TreeScene.reader(), whose first rows and cells it keeps.
        limn.demo.a11y.AccessibilityGallery.Built built = limn.demo.a11y.AccessibilityGallery
                .readerEntry("tree-loading").build();
        HeadlessWindow window = backend.open("Limn tree", 800, 640);
        new limn.scene.Scene(built.root(), () -> nanos).bind(window);
        window.frame();
        window.desktopFocus(true);
        built.focus().requestFocus();
        settle(window);

        AccessibleTree tree = window.bridge().tree();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.TREE_ITEM) {
                names.add(node.name());
            }
        }
        assertFalse(names.isEmpty(), "the reader scene publishes tree items");
        assertTrue(names.stream().noneMatch(String::isBlank),
                "no row is nameless: " + names);
        assertTrue(names.contains("Documents 2"),
                "a folder reads as its name and its count: " + names);
        assertTrue(names.contains("Reports 2"), names.toString());
        assertTrue(names.contains("Q3 regional revenue and headcount, consolidated (final).pdf"),
                "a row holding a button reads as its label alone: " + names);
        assertTrue(names.contains("Media 2"), names.toString());
        assertEquals(names.size(), names.stream().distinct().count(),
                "and each is its own: " + names);
    }

    private void settle(HeadlessWindow window) {
        for (int i = 0; i < 12; i++) {
            nanos += FRAME_NANOS;
            runtime.drain();
            window.frame();
        }
    }
}
