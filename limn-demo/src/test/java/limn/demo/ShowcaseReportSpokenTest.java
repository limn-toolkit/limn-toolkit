package limn.demo;

import limn.accessibility.AccessibleEvent;
import limn.components.Button;
import limn.components.Theme;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What a Showcase page says a control did reaches a screen reader as well as the eye: the status
 * line, and the Motion page's toast, are spoken once, politely and without moving the focus,
 * where a reader's user heard nothing after "Discard and close" or a save.
 */
class ShowcaseReportSpokenTest {

    private static final ImageDecoder DECODER = bytes -> new Image(16, 16, new byte[16 * 16 * 4]);

    private long now;
    private HeadlessUi ui;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> now);
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        Images.installDecoder(DECODER);
        backend = new HeadlessBackend(ui.runtime());
    }

    @AfterEach
    void uninstallRuntime() {
        Page.reportTo(null);
        Images.uninstallDecoder(DECODER);
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    @Test
    void aReportIsSpokenOncePolitelyWithoutMovingTheFocus() {
        Scene scene = AppScene.create().scene();
        HeadlessWindow window = backend.open(AppScene.TITLE, 1280, 800);
        scene.bind(window);
        window.frame();
        Widget<?> focused = scene.focusedWidget();
        window.bridge().events.clear();

        Page.report("Order #1042 kept.");
        window.frame();

        assertEquals(List.of("POLITE Order #1042 kept."), spoken(window));
        assertSame(focused, scene.focusedWidget(), "the focus stays where it was");
    }

    @Test
    void theMotionPagesToastIsSpokenOncePolitely() {
        Widget<?> page = AnimationsScene.content();
        HeadlessWindow window = backend.open("motion", 1100, 3000);
        Scene scene = new Scene(page, () -> now);
        scene.bind(window);
        window.frame();
        Button save = button(page, AnimationsScene.Text.SAVE.get());
        assertNotNull(save, "the toast's Save button");
        window.bridge().events.clear();

        drive(scene).click(save);
        window.frame();

        assertEquals(List.of("POLITE " + AnimationsScene.Text.SAVED.format("Meadowbrook Clinic")),
                spoken(window));
    }

    /** Every announcement the window's bridge received, as its politeness and its words. */
    private static List<String> spoken(HeadlessWindow window) {
        List<String> spoken = new ArrayList<>();
        for (AccessibleEvent event : window.bridge().events) {
            if (event.type() == AccessibleEvent.Type.ANNOUNCEMENT) {
                spoken.add(event.politeness() + " " + event.newValue());
            }
        }
        return spoken;
    }

    private static Button button(Widget<?> at, String text) {
        if (at instanceof Button button && button.text().equals(text)) {
            return button;
        }
        for (Widget<?> child : at.children()) {
            Button found = button(child, text);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
