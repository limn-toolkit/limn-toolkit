package limn.backend.lwjgl.transfer;

import limn.transfer.DropAction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        assertTrue(follow.keepsAfterLeave(held(200, 150), FILE_MANAGER, false, false),
                "the file manager's leave, sent as it turns to the window itself, leaves the drag on");
        assertTrue(follow.active());
        assertEquals(X11DragFollow.Step.NOTHING, follow.tick(held(200, 150)),
                "the point the scene was just told is not told again");
    }

    @Test
    void aRealLeaveIsStillALeave() {
        assertFalse(new X11DragFollow().keepsAfterLeave(
                new X11DragFollow.Sample(900, 150, BUTTON1, false, false), FILE_MANAGER, false, false),
                "the pointer went on to another window");
        assertFalse(new X11DragFollow().keepsAfterLeave(
                new X11DragFollow.Sample(200, 150, BUTTON1, true, true), FILE_MANAGER, false, false),
                "Escape called the drag off over the window");
        assertFalse(new X11DragFollow().keepsAfterLeave(
                new X11DragFollow.Sample(200, 150, 0, false, true), FILE_MANAGER, false, false),
                "the button is up: the drag is over");
        assertFalse(new X11DragFollow().keepsAfterLeave(held(200, 150), FILE_MANAGER, true, false),
                "a leave after the drop");
        assertFalse(new X11DragFollow().keepsAfterLeave(held(200, 150),
                List.of(ClipboardTypes.TEXT, ClipboardTypes.HTML), false, false),
                "text alone: GLFW refuses it at the drop, so nothing would ever land and nothing is followed");
        assertTrue(new X11DragFollow().keepsAfterLeave(
                new X11DragFollow.Sample(200, 150, 1 << 9, false, true), FILE_MANAGER, false, false),
                "a drag with the middle button is followed as well");
    }

    @Test
    void aPointerRestingAtTheBottomEdgeIsToldOnceAndThenTheAnswerIsSealedWhileItRests() {
        X11DragFollow follow = new X11DragFollow();
        follow.keepsAfterLeave(held(200, 20), FILE_MANAGER, false, false);
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
        released.keepsAfterLeave(held(200, 150), FILE_MANAGER, false, false);
        assertEquals(List.of(X11DragFollow.Step.OVER, X11DragFollow.Step.LEFT, X11DragFollow.Step.NOTHING),
                run(released, held(200, 292), new X11DragFollow.Sample(200, 292, 0, false, true), held(200, 292)),
                "released over the window: the scene is told the drag left, and GLFW's own drop brings the files");
        assertFalse(released.active());

        X11DragFollow escaped = new X11DragFollow();
        escaped.keepsAfterLeave(held(200, 150), FILE_MANAGER, false, false);
        assertEquals(X11DragFollow.Step.CALLED_OFF, escaped.tick(new X11DragFollow.Sample(200, 150, BUTTON1, true, true)),
                "Escape: the scene is told it left, and the source that nothing here takes it");
        assertFalse(escaped.active());

        X11DragFollow away = new X11DragFollow();
        away.keepsAfterLeave(held(200, 150), FILE_MANAGER, false, false);
        assertEquals(X11DragFollow.Step.LEFT, away.tick(new X11DragFollow.Sample(900, 150, BUTTON1, false, false)),
                "the source tells GLFW's window it left, which nothing hears: the pointer says it");
        assertFalse(away.active());

        X11DragFollow entered = new X11DragFollow();
        entered.keepsAfterLeave(held(200, 150), FILE_MANAGER, false, false);
        entered.entered(0); // the source came back round the proxy with an enter of its own
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

    private static final long MS = 1_000_000L;

    /**
     * The guest's file manager (2026-10-20): enter and one position to the proxy, answered, then
     * nothing, not even a leave, while the pointer went on down the window to its bottom edge.
     */
    @Test
    void aSourceThatFallsSilentWhileThePointerMovesOverTheWindowIsFollowed() {
        X11DragFollow follow = new X11DragFollow();
        follow.entered(0);
        follow.heard(2 * MS);
        follow.answered(18 * MS);
        assertFalse(follow.keepsAfterSilence(34 * MS, held(519, 346), FILE_MANAGER, false, false),
                "the first reading since the source spoke: nothing has moved yet");
        assertFalse(follow.keepsAfterSilence(50 * MS, held(500, 350), FILE_MANAGER, false, false),
                "it moved: the source has a moment to say so");
        assertFalse(follow.keepsAfterSilence(50 * MS + X11DragFollow.SILENCE_NANOS - 1, held(480, 380),
                FILE_MANAGER, false, false), "not yet");
        assertTrue(follow.keepsAfterSilence(50 * MS + X11DragFollow.SILENCE_NANOS, held(470, 400),
                FILE_MANAGER, false, false), "moved and unsaid for the whole wait: followed");
        assertTrue(follow.active());
        assertEquals(470, follow.x());
        assertEquals(400, follow.y());
        assertEquals(X11DragFollow.Step.OVER, follow.tick(held(470, 410)));
        follow.heard(300 * MS);
        assertFalse(follow.active(), "a position from the source: it talks to us again, and the follow ends");
    }

    /** A GTK 3 source keeps to the proxy: it rests, and moves again, and says so at once. */
    @Test
    void aSourceThatRestsAndSaysEachMoveIsNeverFollowed() {
        X11DragFollow follow = new X11DragFollow();
        follow.entered(0);
        follow.heard(2 * MS);
        follow.answered(18 * MS);
        long now = 18 * MS;
        for (int i = 0; i < 300; i++) { // five seconds at rest
            now += 16 * MS;
            assertFalse(follow.keepsAfterSilence(now, held(300, 200), FILE_MANAGER, false, false));
        }
        now += 16 * MS;
        assertFalse(follow.keepsAfterSilence(now, held(300, 230), FILE_MANAGER, false, false),
                "it moved after a long rest: seen before its position arrives");
        follow.heard(now + 3 * MS);
        follow.answered(now + 19 * MS);
        for (int i = 0; i < 30; i++) {
            now += 16 * MS;
            assertFalse(follow.keepsAfterSilence(now, held(300, 230), FILE_MANAGER, false, false),
                    "the position came: the long rest before the move counts for nothing");
        }
        assertFalse(follow.active());
    }

    /** A toolkit drawn at twice the size may name the point in its own units: still never followed at rest. */
    @Test
    void aRestingSourceWhosePointDiffersFromThePointersIsNotFollowed() {
        X11DragFollow follow = new X11DragFollow();
        follow.entered(0);
        follow.heard(2 * MS); // it named 600,400; the pointer is at 300,200
        follow.answered(18 * MS);
        long now = 18 * MS;
        for (int i = 0; i < 300; i++) {
            now += 16 * MS;
            assertFalse(follow.keepsAfterSilence(now, held(300, 200), FILE_MANAGER, false, false));
        }
    }

    @Test
    void aSourceWaitingForOurAnswerOrOurOwnOrOneOfferingNoFilesIsNeverFollowedOnSilence() {
        X11DragFollow waiting = new X11DragFollow();
        waiting.entered(0);
        waiting.heard(2 * MS); // not answered: the UI thread is busy
        assertFalse(waiting.keepsAfterSilence(10 * MS, held(310, 200), FILE_MANAGER, false, false));
        assertFalse(waiting.keepsAfterSilence(500 * MS, held(320, 200), FILE_MANAGER, false, false),
                "a source waiting for our answer sends nothing, however long that takes");

        X11DragFollow ours = new X11DragFollow();
        ours.entered(0);
        ours.heard(2 * MS);
        ours.answered(18 * MS);
        ours.keepsAfterSilence(20 * MS, held(310, 200), FILE_MANAGER, false, true);
        assertFalse(ours.keepsAfterSilence(500 * MS, held(320, 200), FILE_MANAGER, false, true),
                "a drag of this program's own is never followed");

        X11DragFollow text = new X11DragFollow();
        text.entered(0);
        text.heard(2 * MS);
        text.answered(18 * MS);
        text.keepsAfterSilence(20 * MS, held(310, 200), List.of(ClipboardTypes.TEXT), false, false);
        assertFalse(text.keepsAfterSilence(500 * MS, held(320, 200), List.of(ClipboardTypes.TEXT), false, false));

        X11DragFollow outside = new X11DragFollow();
        outside.entered(0);
        outside.heard(2 * MS);
        outside.answered(18 * MS);
        outside.keepsAfterSilence(20 * MS, held(310, 200), FILE_MANAGER, false, false);
        assertFalse(outside.keepsAfterSilence(500 * MS, new X11DragFollow.Sample(900, 200, BUTTON1, false, false),
                FILE_MANAGER, false, false), "gone from the window");
        assertFalse(outside.keepsAfterSilence(500 * MS, new X11DragFollow.Sample(310, 200, 0, false, true),
                FILE_MANAGER, false, false), "the button up");
        assertFalse(outside.keepsAfterSilence(500 * MS, new X11DragFollow.Sample(310, 200, BUTTON1, true, true),
                FILE_MANAGER, false, false), "Escape held");
        assertFalse(outside.keepsAfterSilence(500 * MS, held(310, 200), FILE_MANAGER, true, false), "dropped");
    }

    @Test
    void aDragOverTheWindowWithTheButtonsUpForASecondAndNothingSaidIsForgotten() {
        X11DragFollow follow = new X11DragFollow();
        follow.entered(0);
        X11DragFollow.Sample up = new X11DragFollow.Sample(300, 200, 0, false, true);
        assertFalse(follow.stale(100 * MS, up), "the release: a source that drops on the proxy says so at once");
        assertFalse(follow.stale(100 * MS + X11DragFollow.STALE_NANOS - 1, up));
        assertFalse(follow.stale(100 * MS + X11DragFollow.STALE_NANOS / 2, held(300, 200)), "held again");
        assertFalse(follow.stale(100 * MS + X11DragFollow.STALE_NANOS + 1, up), "counted again from now");
        assertTrue(follow.stale(200 * MS + 2 * X11DragFollow.STALE_NANOS, up),
                "a source that dropped on GLFW's window, or went away, leaves nothing over the window");
    }

    // ---- R11C-1: a real leave, and our own drag

    /**
     * Our own outgoing drag reaches our windows through the hidden window like any source: its leave,
     * as it moves on to a window stacked over ours, is a leave, and is never followed, as its silence
     * never is.
     */
    @Test
    void ourOwnDragsLeaveIsNeverFollowed() {
        X11DragFollow follow = new X11DragFollow();
        follow.entered(0);
        assertFalse(follow.keepsAfterLeave(held(200, 150), FILE_MANAGER, false, true),
                "a drag of this program's own is never followed");
        assertFalse(follow.active());
        assertEquals(X11DragFollow.Step.NOTHING, follow.tick(held(210, 150)), "and the scene is told no point");
    }

    private static X11DragFollow.TopLevel shown(int x, int y, int width, int height) {
        return new X11DragFollow.TopLevel(x, y, width, height, 0, true, false);
    }

    /**
     * Inside is the top-level window under the pointer, read from the top of the stacking order
     * down, and ours: not merely our window's rectangle. A source moving from our window onto
     * another window stacked over it (its own window, partly over ours; a second window of ours)
     * sends a real leave there, and the drag was followed over the window it had left, its drop
     * area lit and its edge band scrolling under the other window.
     */
    @Test
    void thePointerIsOnOurWindowOnlyWhenOursIsTheTopLevelUnderIt() {
        long ours = 0x400001;
        long other = 0x600001;
        long picture = 0x700001;
        long hidden = 0x800001;
        Map<Long, X11DragFollow.TopLevel> windows = new HashMap<>();
        windows.put(ours, shown(100, 100, 600, 400));
        windows.put(other, shown(400, 200, 500, 400)); // stacked over ours, covering its right half
        windows.put(picture, new X11DragFollow.TopLevel(480, 280, 64, 64, 0, true, true)); // a drag picture
        windows.put(hidden, new X11DragFollow.TopLevel(0, 0, 2000, 2000, 0, false, false)); // unmapped
        long[] bottomUp = {ours, other, picture, hidden};
        assertFalse(X11DragFollow.onWindow(bottomUp, windows::get, ours, 500, 300),
                "over the other window stacked over ours, inside our rectangle: not ours");
        assertTrue(X11DragFollow.onWindow(bottomUp, windows::get, ours, 200, 300),
                "over the part of ours the other window does not cover");
        windows.put(picture, new X11DragFollow.TopLevel(180, 280, 64, 64, 0, true, true));
        assertTrue(X11DragFollow.onWindow(bottomUp, windows::get, ours, 200, 300),
                "a drag picture under the pointer, which no window manager frames, is looked through: "
                        + "it is what the first guest build took for a leave");
        assertFalse(X11DragFollow.onWindow(bottomUp, windows::get, ours, 50, 50), "outside every window");
        assertFalse(X11DragFollow.onWindow(new long[] {ours, other}, w -> null, ours, 200, 300),
                "a window that cannot be read (destroyed meanwhile) is no window, and ours is not found");
        assertTrue(X11DragFollow.onWindow(new long[] {ours}, windows::get, ours, 100 + 600, 300) == false
                        && X11DragFollow.onWindow(new long[] {ours}, windows::get, ours, 100 + 599, 300),
                "the window covers its width, not one pixel more");
        windows.put(ours, new X11DragFollow.TopLevel(100, 100, 600, 400, 2, true, false));
        assertTrue(X11DragFollow.onWindow(new long[] {ours}, windows::get, ours, 100 + 603, 300),
                "and its border on both sides");
    }

    // ---- R11C-2: a late drop after a release

    /**
     * A source that still talks to us but stalled for a moment (a busy main thread, a pause of its
     * runtime) reads as silent and is followed; if the button is let go in that stall the source
     * sends its drop late, to the proxy. The follow then hands the release over without forgetting
     * the drag, which waits for that drop as long as a drag over the window waits with the buttons
     * up, and does not start a follow again meanwhile.
     */
    @Test
    void aReleaseInAFollowSilenceStartedKeepsTheDragForALateDrop() {
        X11DragFollow follow = new X11DragFollow();
        follow.entered(0);
        follow.heard(0);
        follow.answered(5 * MS);
        follow.keepsAfterSilence(10 * MS, held(500, 400), FILE_MANAGER, false, false);
        follow.keepsAfterSilence(20 * MS, held(505, 400), FILE_MANAGER, false, false);
        assertTrue(follow.keepsAfterSilence(101 * MS, held(530, 400), FILE_MANAGER, false, false),
                "sanity: stalled 81 ms after the move, followed");
        X11DragFollow.Sample up = new X11DragFollow.Sample(530, 400, 0, false, true);
        assertEquals(X11DragFollow.Step.LET_GO, follow.tick(up),
                "let go: the scene is told no more points, and the drag is not forgotten yet");
        assertFalse(follow.active());
        assertFalse(follow.keepsAfterSilence(120 * MS, held(540, 400), FILE_MANAGER, false, false),
                "a press after it is no reason to follow a drag that was let go");
        assertFalse(follow.stale(130 * MS, up));
        assertFalse(follow.stale(130 * MS + X11DragFollow.STALE_NANOS - 1, up), "still waiting for a late drop");
        assertTrue(follow.stale(130 * MS + X11DragFollow.STALE_NANOS, up), "forgotten when the wait is over");

        X11DragFollow fromLeave = new X11DragFollow();
        fromLeave.keepsAfterLeave(held(200, 150), FILE_MANAGER, false, false);
        assertEquals(X11DragFollow.Step.LEFT, fromLeave.tick(up),
                "a source that sent a leave said it was done with us: its release ends the drag at once");

        X11DragFollow again = new X11DragFollow();
        again.entered(0);
        again.heard(0);
        again.answered(5 * MS);
        again.keepsAfterSilence(10 * MS, held(500, 400), FILE_MANAGER, false, false);
        again.keepsAfterSilence(101 * MS, held(530, 400), FILE_MANAGER, false, false);
        again.tick(up);
        again.entered(200 * MS);
        again.heard(202 * MS);
        again.answered(210 * MS);
        again.keepsAfterSilence(220 * MS, held(500, 400), FILE_MANAGER, false, false);
        again.keepsAfterSilence(230 * MS, held(505, 400), FILE_MANAGER, false, false);
        assertTrue(again.keepsAfterSilence(320 * MS, held(530, 400), FILE_MANAGER, false, false),
                "the next drag's enter starts afresh");
    }
}
