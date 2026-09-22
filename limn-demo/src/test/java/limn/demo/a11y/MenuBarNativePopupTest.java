package limn.demo.a11y;

import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.components.DisplayMode;
import limn.components.Menu;
import limn.components.MenuBar;
import limn.components.Theme;
import limn.concurrent.UiRuntime;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu bar's titles, operated by a reader while the dropdown is a window of its own — the
 * default presentation, and the only one in which a verb on the bar reaches the bar's hook while a
 * cascade is on screen: the in-scene overlay owns input, and the scene refuses every verb on the
 * strip beneath it before the hook runs.
 *
 * <p>Here rather than in the toolkit's {@code MenuBarAccessibilityTest} for
 * {@link NativePopupRelationTest}'s reason: the toolkit's stub window cannot open a second window.
 * The two cases moved here from that class on 2026-09-15, when a title's verbs came to be keyed
 * on whether a cascade is on screen (decision 2) and the stub's refused cascade stopped being a
 * stand-in for an open one.
 */
class MenuBarNativePopupTest {

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
    void aTitleAlreadyDownIsNotTornDownAndBuiltAgain() {
        MenuBar bar = threeMenus();
        HeadlessWindow host = show(bar);
        assertEquals(DisplayMode.NATIVE_WINDOW, bar.displayMode(),
                "the demo's default presentation is the native one");

        assertTrue(host.bridge().host.perform(title(host, 0).id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE));
        HeadlessWindow popup = popupWindow();
        settle(host, popup);
        assertTrue(title(host, 0).expand().expanded(), Transcript.of(host.bridge().tree()));
        assertEquals(Set.of(Accessible.Action.COLLAPSE), title(host, 0).actions().actions(),
                "open: SHOW_MENU is not published, so no platform offers it");
        assertEquals(Set.of(Accessible.Action.SHOW_MENU, Accessible.Action.EXPAND),
                title(host, 2).actions().actions(),
                "a closed title beside it opens, and takes no FOCUS while a menu is down: the "
                        + "open title is the cursor, and in a window of its own the cascade "
                        + "leaves the bar inside the scene's input layer, so this gate is the "
                        + "bar's own (decision 11)");

        // Sent anyway: Host#perform answers from the snapshot's membership alone, so the hook is
        // what refuses it.
        host.bridge().host.perform(title(host, 0).id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE);
        settle(host, popup);

        assertTrue(bar.isOpen());
        assertEquals(2, backend.windows().size(),
                "the hook refuses what it is already doing: reopening tears the cascade down and "
                        + "builds another window with new identifiers, which the pointer never does");
        assertTrue(title(host, 0).expand().expanded(), Transcript.of(host.bridge().tree()));
    }

    @Test
    void collapseClosesTheOpenTitleAndNothingElse() {
        MenuBar bar = threeMenus();
        HeadlessWindow host = show(bar);
        host.bridge().host.perform(title(host, 0).id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE);
        HeadlessWindow popup = popupWindow();
        settle(host, popup);
        assertTrue(title(host, 0).expand().expanded(), Transcript.of(host.bridge().tree()));

        host.bridge().host.perform(title(host, 1).id(), Accessible.Action.COLLAPSE,
                Accessible.Argument.NONE);
        settle(host, popup);
        assertTrue(bar.isOpen(), "a collapse addressed elsewhere closes nothing");
        assertTrue(title(host, 0).expand().expanded(), Transcript.of(host.bridge().tree()));

        assertTrue(host.bridge().host.perform(title(host, 0).id(), Accessible.Action.COLLAPSE,
                Accessible.Argument.NONE));
        settle(host, popup);
        assertFalse(bar.isOpen(), Transcript.of(host.bridge().tree()));
        assertFalse(title(host, 0).expand().expanded(), Transcript.of(host.bridge().tree()));
        assertEquals(Set.of(Accessible.Action.SHOW_MENU, Accessible.Action.EXPAND,
                        Accessible.Action.FOCUS),
                title(host, 0).actions().actions(), Transcript.of(host.bridge().tree()));
    }

    /** Three titles, each with a menu that can open, in the default presentation. */
    private static MenuBar threeMenus() {
        return new MenuBar()
                .addMenu("File", new Menu().addItem("New", () -> { }))
                .addMenu("Edit", new Menu().addItem("Undo", () -> { }))
                .addMenu("View", new Menu().addItem("Zoom", () -> { }));
    }

    private HeadlessWindow show(MenuBar bar) {
        Column root = new Column();
        root.add(bar);
        HeadlessWindow window = backend.open("Limn menus", 400, 300);
        new Scene(root).bind(window);
        window.frame();
        window.desktopFocus(true);
        settle(window);
        return window;
    }

    /** The window the dropdown opened as: creating it is posted, so the queue is drained first. */
    private HeadlessWindow popupWindow() {
        // Twice: the verb is one posted task, and the window it creates is posted from inside it.
        runtime.drain();
        runtime.drain();
        assertEquals(2, backend.windows().size(), "the host and the dropdown, and nothing else");
        return backend.windows().get(1);
    }

    private void settle(HeadlessWindow... windows) {
        for (int i = 0; i < 24; i++) {
            nanos += FRAME_NANOS;
            runtime.drain();
            for (HeadlessWindow window : windows) {
                window.frame();
            }
        }
    }

    /** Title {@code index} of the one menu bar in the host's newest tree. */
    private static AccessibleNode title(HeadlessWindow window, int index) {
        AccessibleTree tree = window.bridge().tree();
        List<AccessibleNode> titles = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.MENU_ITEM) {
                titles.add(node);
            }
        }
        assertEquals(3, titles.size(), Transcript.of(tree));
        return titles.get(index);
    }
}
