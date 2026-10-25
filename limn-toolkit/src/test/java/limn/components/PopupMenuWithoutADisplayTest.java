package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.backend.NativeWindow;
import limn.backend.spi.WindowPeer;
import limn.input.Keys;
import limn.math.Vec2;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu over a window that is open and reports no display is drawn in that window's scene, and
 * a menu over a window that has closed opens nothing.
 *
 * <p>A window reports no display where the windowing system lists no monitor: the only one
 * asleep, a session with none, a capture process on a Mac on 2026-10-25 with its screen on. Until
 * then a menu opened nothing over such a window, {@link DisplayMode#IN_SCENE} included, which
 * needs nothing of a display, so every context menu and every menu-bar dropdown in it was dead.
 * The window here is a headless one that answers no display, as a desktop backend's does there,
 * and a menu window made by mistake would show among the backend's windows.
 */
class PopupMenuWithoutADisplayTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private final List<String> chosen = new ArrayList<>();
    private HeadlessBackend backend;
    private HeadlessWindow inner;
    private Scene scene;
    private Button row;

    /** Binds a page with one row over a window that is open and reports no display. */
    private void open(UnaryOperator<Widget<?>> wrapRow) {
        backend = new HeadlessBackend(runtime);
        inner = backend.open("Files", 600, 400);
        NativeWindow window = (NativeWindow) Proxy.newProxyInstance(
                WindowPeer.class.getClassLoader(), new Class<?>[] {WindowPeer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("display") && method.getParameterCount() == 0) {
                        return null; // no monitor listed, as GLFW answers with none
                    }
                    try {
                        return method.invoke(inner, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        row = new Button("Invoice 1042.pdf");
        Column page = new Column();
        page.add(wrapRow.apply(row));
        page.add(new Button("Refresh"));
        scene = new Scene(page, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 14; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            runtime.drain();
            for (HeadlessWindow w : backend.windows()) {
                if (w.scene() != null && !w.isClosed()) {
                    w.frame();
                }
            }
        }
        runtime.drain();
    }

    private Menu files() {
        return new Menu()
                .addItem("Open", () -> chosen.add("Open"))
                .addItem("Rename", () -> chosen.add("Rename"))
                .addSubmenu("Move to", new Menu()
                        .addItem("Archive", () -> chosen.add("Archive"))
                        .addItem("Drafts", () -> chosen.add("Drafts")))
                .addItem("Delete", () -> chosen.add("Delete"));
    }

    /** @return the names of the rows of every menu column the window published, column by column */
    private List<List<String>> publishedColumns() {
        AccessibleTree tree = inner.bridge().tree();
        List<List<String>> columns = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (node.role() == Accessible.Role.MENU) {
                List<String> rows = new ArrayList<>();
                for (AccessibleNode child : tree.children(node)) {
                    if (child.role() == Accessible.Role.MENU_ITEM) {
                        rows.add(child.name());
                    }
                }
                columns.add(rows);
            }
        }
        return columns;
    }

    private long openWindows() {
        return backend.windows().stream().filter(w -> !w.isClosed()).count();
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuOverAWindowWithNoDisplayOpensInItsSceneWithItsRows(DisplayMode mode) {
        open(content -> content);
        PopupMenu menu = new PopupMenu(files()).setDisplayMode(mode);

        menu.showAt(row, 10, 10);
        settle();

        assertTrue(menu.isOpen(), mode + ": the menu opened over a window that reports no display");
        assertEquals(DisplayMode.IN_SCENE, menu.displayMode(), mode + ": in the scene");
        assertNull(menu.popupWindow(), mode + ": in no window of its own");
        assertEquals(1, openWindows(), mode + ": and none was made for it");
        assertEquals(List.of(List.of("Open", "Rename", "Move to", "Delete")), publishedColumns(),
                mode + ": its rows are in the window, where a reader finds them");

        // The keyboard drives it there, a submenu included: Down to Rename, Down to Move to, the
        // trailing arrow into its column, Down to Drafts, Enter.
        inner.key(Keys.DOWN);
        inner.key(Keys.DOWN);
        inner.key(Keys.RIGHT);
        settle();
        assertEquals(List.of(List.of("Open", "Rename", "Move to", "Delete"),
                        List.of("Archive", "Drafts")),
                publishedColumns(), mode + ": the submenu cascades in the scene");
        inner.key(Keys.DOWN);
        inner.key(Keys.ENTER);
        settle();

        assertEquals(List.of("Drafts"), chosen, mode + ": the row chosen from the keyboard ran");
        assertFalse(menu.isOpen(), mode + ": and the menu closed");
        assertEquals(List.of(), publishedColumns(), mode + ": taking its rows with it");
    }

    /** The gallery's own path: a right-click on a row given a menu, at the default presentation. */
    @Test
    void aRightClickOverAWindowWithNoDisplayOpensTheRowsMenu() {
        open(content -> ContextMenus.attach(content, this::files));
        assertEquals(DisplayMode.NATIVE_WINDOW, PopupMenu.defaultDisplayMode(),
                "the default every application starts from");

        Vec2 at = row.localToScene(row.width() * 0.35f, row.height() * 0.5f);
        drive(scene).mouseMoved(at.x(), at.y());
        drive(scene).mouseButton(Keys.MOUSE_RIGHT, true, 0, at.x(), at.y());
        drive(scene).mouseButton(Keys.MOUSE_RIGHT, false, 0, at.x(), at.y());
        drive(scene).inputBatchEnded();
        settle();

        assertEquals(List.of(List.of("Open", "Rename", "Move to", "Delete")), publishedColumns(),
                "the right-click opened the row's menu, in the scene");
        assertEquals(1, openWindows(), "and in no window of its own");
        inner.key(Keys.ESCAPE);
        settle();
        assertEquals(List.of(), publishedColumns(), "Escape dismissed it");
        assertEquals(List.of(), chosen, "running nothing");
    }

    /**
     * A closed window reports no display either, and has nowhere to draw a menu: its scene keeps
     * answering it as its window, so a task that runs after the close and shows a menu reaches the
     * refusal, and nothing opens.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuOverAClosedWindowOpensNothing(DisplayMode mode) {
        open(content -> content);
        inner.close();
        assertTrue(scene.window() != null && scene.window().isClosed(),
                "the scene keeps its closed window");
        PopupMenu menu = new PopupMenu(files()).setDisplayMode(mode);

        menu.showAt(scene, 10, 10);
        runtime.drain();

        assertFalse(menu.isOpen(), mode + ": nothing opened over a closed window");
        assertNull(menu.popupWindow(), mode.name());
        assertEquals(0, openWindows(), mode + ": and no window was made for it");
    }
}
