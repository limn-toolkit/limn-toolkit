package limn.backend.lwjgl.transfer;

import limn.transfer.DropAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the X11 drag decides apart from the server it speaks to: which window of ours is told a drag
 * left when an enter names another, and what the source is owed after the application's drop.
 */
class X11DragTest {

    /**
     * A drag over one window of ours goes on to a second window of ours stacked over it (a dialog
     * that is not modal, a second window): the source leaves the first and enters the second through
     * the same hidden window. The first was never told the drag left, and its scene kept it after
     * it was over, its drop area lit and its auto-scroll running.
     */
    @Test
    void anEnterForAnotherWindowOfOursTellsTheFirstTheDragLeft() {
        String main = "main window";
        String dialog = "dialog";
        assertSame(main, X11Drag.leftByEnter(main, dialog), "the window the drag was over is told it left");
        assertNull(X11Drag.leftByEnter(main, main), "the same window: the next position replaces what it had");
        assertNull(X11Drag.leftByEnter(null, dialog), "no drag was on");
        assertSame(main, X11Drag.leftByEnter(main, null), "an enter for no window of ours");
    }

    /**
     * An application's drop that throws still has the source told the drop is finished, refused:
     * the throw left the drag held, the source waiting for an answer that never came, and the X
     * thread turning sixty times a second until the next drag.
     */
    @Test
    void aDropThatThrowsIsFinishedAsARefusalAndTheThrowGoesOn() {
        List<String> finished = new ArrayList<>();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> X11Drag.dropAndFinish(() -> {
            throw new IllegalStateException("an application's onDrop bug");
        }, done -> finished.add("finished " + done)));
        assertEquals("an application's onDrop bug", thrown.getMessage(), "the throw is not swallowed");
        assertEquals(List.of("finished null"), finished, "the source is told, as a refusal");

        finished.clear();
        X11Drag.dropAndFinish(() -> DropAction.COPY, done -> finished.add("finished " + done));
        assertEquals(List.of("finished COPY"), finished, "a drop that ran is finished with what it did");
    }
}
