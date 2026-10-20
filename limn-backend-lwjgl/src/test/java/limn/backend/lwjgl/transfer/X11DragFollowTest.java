package limn.backend.lwjgl.transfer;

import limn.transfer.DropAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A drag from another program over one of our windows on X11, followed by reading the pointer once
 * its source has gone round the window's proxy: a toolkit that, from its second position on, talks
 * to the window under the pointer itself (a file manager built on GTK 4), which there is GLFW's.
 * The pointer is a fake: each sample is what a turn of the X thread would read from the server.
 */
class X11DragFollowTest {

    private static final int BUTTON1 = 1 << 8;
    private static final List<String> FILE_MANAGER = List.of(ClipboardTypes.FILES,
            "application/vnd.portal.files", ClipboardTypes.TEXT);

    /** The pointer held down at a point, over the window, Escape up. */
    private static X11DragFollow.Sample held(int x, int y) {
        return new X11DragFollow.Sample(x, y, BUTTON1, false, true);
    }

    private static List<X11DragFollow.Step> run(X11DragFollow follow, X11DragFollow.Sample... samples) {
        List<X11DragFollow.Step> steps = new ArrayList<>();
        for (X11DragFollow.Sample sample : samples) {
            steps.add(follow.tick(sample));
        }
        return steps;
    }

    @Test
    void aLeaveWhileThePointerStaysOverTheWindowWithTheButtonHeldIsTheSourceGoingRoundTheProxy() {
        X11DragFollow follow = new X11DragFollow();
        assertFalse(follow.active());
        assertTrue(follow.keepsAfterLeave(held(200, 150), FILE_MANAGER, false),
                "the file manager's leave, sent as it turns to the window itself, leaves the drag on");
        assertTrue(follow.active());
        assertEquals(X11DragFollow.Step.NOTHING, follow.tick(held(200, 150)),
                "the point the scene was just told is not told again");
    }

    @Test
    void aRealLeaveIsStillALeave() {
        assertFalse(new X11DragFollow().keepsAfterLeave(
                new X11DragFollow.Sample(900, 150, BUTTON1, false, false), FILE_MANAGER, false),
                "the pointer went on to another window");
        assertFalse(new X11DragFollow().keepsAfterLeave(
                new X11DragFollow.Sample(200, 150, BUTTON1, true, true), FILE_MANAGER, false),
                "Escape called the drag off over the window");
        assertFalse(new X11DragFollow().keepsAfterLeave(
                new X11DragFollow.Sample(200, 150, 0, false, true), FILE_MANAGER, false),
                "the button is up: the drag is over");
        assertFalse(new X11DragFollow().keepsAfterLeave(held(200, 150), FILE_MANAGER, true),
                "a leave after the drop");
        assertFalse(new X11DragFollow().keepsAfterLeave(held(200, 150),
                List.of(ClipboardTypes.TEXT, ClipboardTypes.HTML), false),
                "text alone: GLFW refuses it at the drop, so nothing would ever land and nothing is followed");
        assertTrue(new X11DragFollow().keepsAfterLeave(
                new X11DragFollow.Sample(200, 150, 1 << 9, false, true), FILE_MANAGER, false),
                "a drag with the middle button is followed as well");
    }

    @Test
    void aPointerRestingAtTheBottomEdgeIsToldOnceAndThenTheAnswerIsSealedWhileItRests() {
        X11DragFollow follow = new X11DragFollow();
        follow.keepsAfterLeave(held(200, 20), FILE_MANAGER, false);
        List<X11DragFollow.Step> steps = run(follow, held(200, 120), held(200, 250), held(200, 292));
        assertEquals(List.of(X11DragFollow.Step.OVER, X11DragFollow.Step.OVER, X11DragFollow.Step.OVER), steps,
                "every new point on the way down is told to the scene");
        assertEquals(200, follow.x());
        assertEquals(292, follow.y());
        List<X11DragFollow.Sample> resting = new ArrayList<>();
        for (int i = 0; i < 3 * X11DragFollow.SEAL_TICKS; i++) {
            resting.add(held(200, 292));
        }
        List<X11DragFollow.Step> still = run(follow, resting.toArray(X11DragFollow.Sample[]::new));
        assertFalse(still.contains(X11DragFollow.Step.OVER),
                "a resting pointer is not told again: the scene's own auto-scroll asks again after each step");
        assertEquals(3, still.stream().filter(s -> s == X11DragFollow.Step.SEAL).count(),
                "the scene's answer goes to the source again while the pointer rests, after the answers GLFW "
                        + "sent to the positions of the move, so it is ours the source acts on at the release");
        assertEquals(X11DragFollow.Step.SEAL, still.get(X11DragFollow.SEAL_TICKS - 1));
        assertEquals(X11DragFollow.Step.OVER, follow.tick(held(201, 292)), "a move after the rest is told");
        assertEquals(X11DragFollow.Step.NOTHING, follow.tick(held(201, 292)), "and the rest counts again from it");
    }

    @Test
    void theFollowStopsAtTheReleaseAtEscapeAndWhenThePointerLeavesTheWindow() {
        X11DragFollow released = new X11DragFollow();
        released.keepsAfterLeave(held(200, 150), FILE_MANAGER, false);
        assertEquals(List.of(X11DragFollow.Step.OVER, X11DragFollow.Step.LEFT, X11DragFollow.Step.NOTHING),
                run(released, held(200, 292), new X11DragFollow.Sample(200, 292, 0, false, true), held(200, 292)),
                "released over the window: the scene is told the drag left, and GLFW's own drop brings the files");
        assertFalse(released.active());

        X11DragFollow escaped = new X11DragFollow();
        escaped.keepsAfterLeave(held(200, 150), FILE_MANAGER, false);
        assertEquals(X11DragFollow.Step.LEFT, escaped.tick(new X11DragFollow.Sample(200, 150, BUTTON1, true, true)));
        assertFalse(escaped.active());

        X11DragFollow away = new X11DragFollow();
        away.keepsAfterLeave(held(200, 150), FILE_MANAGER, false);
        assertEquals(X11DragFollow.Step.LEFT, away.tick(new X11DragFollow.Sample(900, 150, BUTTON1, false, false)),
                "the source tells GLFW's window it left, which nothing hears: the pointer says it");
        assertFalse(away.active());

        X11DragFollow entered = new X11DragFollow();
        entered.keepsAfterLeave(held(200, 150), FILE_MANAGER, false);
        entered.stop(); // the source came back round the proxy with an enter of its own
        assertEquals(X11DragFollow.Step.NOTHING, entered.tick(held(250, 150)));
    }

    @Test
    void whatIsFollowedIsWhatGlfwCanDeliverAndOnlyAsACopy() {
        assertEquals(List.of(ClipboardTypes.FILES), X11DragFollow.deliverable(FILE_MANAGER),
                "only files arrive through GLFW's drop, so a place that takes only text is not told it could");
        assertEquals(List.of(), X11DragFollow.deliverable(List.of(ClipboardTypes.TEXT)));
        assertEquals(Set.of(DropAction.COPY), X11DragFollow.ALLOWED,
                "GLFW tells the source every drop was a copy: a move answered here would have the source "
                        + "delete files the application was handed only the paths of");
    }

    @Test
    void overTheWindowIsInsideItWithNoOtherWindowThatTakesDragsOnTop() {
        long ours = 0x3c00007L;
        assertTrue(X11DragFollow.over(true, ours, ours));
        assertTrue(X11DragFollow.over(true, 0, ours),
                "nothing that speaks XDND found at the point: the source's own drag picture, or a window "
                        + "that takes no drags, does not end the drag over ours");
        assertFalse(X11DragFollow.over(true, 0x4a00011L, ours), "another program's window on top of ours");
        assertFalse(X11DragFollow.over(false, ours, ours));
    }
}
