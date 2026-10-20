package limn.backend.lwjgl.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What the X11 drag decides apart from the server it speaks to: which window of ours is told a drag
 * left when an enter names another.
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
}
