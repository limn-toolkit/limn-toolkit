package limn.demo;

import limn.components.Label;
import limn.components.ScrollView;
import limn.i18n.I18n;
import limn.input.Keys;
import limn.math.Vec2;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Padding;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picker page's "Approved" board takes cards only from "In progress", and while a card from
 * elsewhere is over it draws a red edge without accepting it. On the Fedora KDE guest (2026-10-25)
 * the edge stayed after the pointer left the board, and after the card was let go over it: only a
 * widget that had accepted heard {@code onDragExit}. Since ADR 054's amendment of 2026-10-26 every
 * widget the drag was over hears it, and the board takes the edge away with nothing changed in it.
 */
class PickerBoardRefusalTest {

    private static final float W = 900;
    private static final float H = 1600;
    /** Where the first card of a board stands: under its 34-point header, in a 36-point row. */
    private static final float FIRST_CARD = 34 + 18;

    @Test
    void theRefusingBoardTakesItsRedEdgeAwayWhenTheCardLeavesItAndWhenItIsLetGoOverIt() throws Exception {
        Locale before = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
        try (HeadlessUi ui = new HeadlessUi()) {
            Label status = new Label("");
            Page.reportTo(status);
            ScrollView page = new ScrollView(new Padding(Insets.all(12), PickerScene.content())); // AppScene.scrolling
            Scene scene = new Scene(page);
            scene.layoutPass(W, H);
            List<Widget<?>> boards = new ArrayList<>();
            collect(page, "Board", boards);
            assertEquals(3, boards.size(), "to do, in progress, approved");
            Widget<?> todo = boards.get(0);
            Widget<?> doing = boards.get(1);
            Widget<?> approved = boards.get(2);

            Vec2 card = todo.localToScene(todo.width() / 2, FIRST_CARD);
            drive(scene).mouseMoved(card.x(), card.y());
            drive(scene).mouseButton(Keys.MOUSE_LEFT, true, 0, card.x(), card.y(), 1);
            drive(scene).inputBatchEnded();
            move(scene, card.x(), card.y() + 10);
            assertTrue(scene.isDragging(), "the card is picked up");

            Vec2 overApproved = approved.localToScene(approved.width() / 2, approved.height() / 2);
            move(scene, overApproved.x(), overApproved.y());
            assertTrue(refusing(approved), "it takes no card from \"To do\", and says so");

            Vec2 overDoing = doing.localToScene(doing.width() / 2, doing.height() / 2);
            move(scene, overDoing.x(), overDoing.y());
            assertFalse(refusing(approved), "the card left it: the red edge goes");

            move(scene, overApproved.x(), overApproved.y());
            assertTrue(refusing(approved));
            drive(scene).mouseButton(Keys.MOUSE_LEFT, false, 0, overApproved.x(), overApproved.y(), 1);
            drive(scene).inputBatchEnded();
            assertFalse(scene.isDragging());
            assertFalse(refusing(approved), "let go over it, refused: the red edge goes too");
            assertTrue(status.text().contains("stayed where it was"), status.text());
        } finally {
            Page.reportTo(null);
            I18n.setLocale(before);
        }
    }

    private static void move(Scene scene, float x, float y) {
        drive(scene).mouseMoved(x, y);
        drive(scene).inputBatchEnded();
    }

    /** The board's own record of the red edge, which its paint reads. */
    private static boolean refusing(Widget<?> board) throws ReflectiveOperationException {
        java.lang.reflect.Field field = board.getClass().getDeclaredField("refusing");
        field.setAccessible(true);
        return field.getBoolean(board);
    }

    private static void collect(Widget<?> widget, String simpleName, List<Widget<?>> into) {
        if (widget.getClass().getSimpleName().equals(simpleName)) {
            into.add(widget);
        }
        for (Widget<?> child : widget.children()) {
            collect(child, simpleName, into);
        }
    }
}
