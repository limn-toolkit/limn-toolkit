package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.AccessibleTree;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dialog in a window of its own, shown over a widget the application named, keeps that widget
 * as its host through a page switch; the widget's node says it controls the dialog
 * ({@code CONTROLLER_FOR}, the mirror of the dialog's {@code POPUP_FOR}), says nothing while its
 * page is away, and says it again as soon as the page comes back. Before, the mirror was dropped
 * when the page left and came back only when the dialog's own window happened to walk again, so a
 * reader's jump to the controlled element from the button found nothing.
 */
class OpenerMirrorAcrossPageSwitchTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private HeadlessBackend backend;

    private void frames(int n) {
        for (int i = 0; i < n; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(16));
            runtime.drain();
            for (HeadlessWindow w : new ArrayList<>(backend.windows())) {
                if (!w.isClosed() && w.scene() != null) {
                    w.frame();
                }
            }
        }
    }

    /** Every node of {@code window}'s published tree that names a controlled element, by name. */
    private static List<String> controllers(HeadlessWindow window) {
        AccessibleTree tree = window.bridge().tree();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            for (AccessibleRelation relation : node.relations()) {
                if (relation.kind() == Accessible.Relation.CONTROLLER_FOR) {
                    out.add(node.name());
                }
            }
        }
        return out;
    }

    private static Column page(String name) {
        Column page = new Column();
        page.setAccessibleName(name);
        page.setAccessibleRole(Accessible.Role.GROUP);
        page.add(new Button(name + " button"));
        return page;
    }

    @Test
    void theOpenersMirrorComesBackWithItsPage() {
        backend = new HeadlessBackend(runtime);
        HeadlessWindow window = backend.open("Orders", 600, 400);
        Column pageA = page("Page A");
        Scene scene = new Scene(pageA, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        window.bridge();
        frames(2);
        Widget<?> named = pageA.children().get(0);
        Dialog dialog = new Dialog("Session", "Your session ends in a minute.").addButton("OK", "ok")
                .setDisplayMode(DisplayMode.NATIVE_WINDOW);
        dialog.show(named);
        for (HeadlessWindow w : backend.windows()) {
            w.bridge();
        }
        frames(20);
        assertEquals(List.of("Page A button"), controllers(window), "the named button controls the dialog");

        scene.setRoot(page("Page B"));
        frames(20);
        assertEquals(List.of(), controllers(window), "nothing on the page that replaced it names the dialog");

        scene.setRoot(pageA);
        frames(3);
        assertEquals(List.of("Page A button"), controllers(window),
                "the button names the dialog again as soon as its page is back");
        dialog.dismiss("ok");
        frames(20);
        assertEquals(List.of(), controllers(window), "and nothing once the dialog closed");
    }
}
