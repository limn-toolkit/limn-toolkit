package limn.backend.lwjgl.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An answer nobody asked for, which a window of ours sends when the content scrolled under a
 * pointer that stands still, goes to the drag it was computed for and to no other. It is computed
 * on the UI thread from the drag the scene last heard of, so when one drag leaves and the next
 * enters the same window between that computation and the send, the backend already holds the next
 * drag; told the first one's accept and action, the next drag's source or the compositor would
 * have held them until its own first answer. The UI thread hears of a drag in the order the
 * backend does, so the stale answer is sent before the next drag's first ordinary one: an
 * unasked answer is let through only to the drag that has had an ordinary answer.
 */
class AnsweredDragTest {

    @Test
    void anUnaskedAnswerReachesTheDragTheLastOrdinaryAnswerWentTo() {
        AnsweredDrag answered = new AnsweredDrag();
        Object first = new Object();
        assertFalse(answered.isAnswered(first), "a drag that has had no answer yet hears none unasked");
        answered.answered(first);
        assertTrue(answered.isAnswered(first), "the drag the window answered hears what changed");
    }

    @Test
    void aStaleAnswerIsNotSentToTheNextDragOverTheSameWindow() {
        AnsweredDrag answered = new AnsweredDrag();
        Object first = new Object();
        Object next = new Object();
        answered.answered(first);
        // The first drag leaves and the next enters before the UI thread hears either: the
        // unasked answer it computed for the first reaches the backend now.
        assertFalse(answered.isAnswered(next), "the next drag is not told the first one's answer");
        answered.answered(next); // its own first answer, to its own position
        assertTrue(answered.isAnswered(next), "from then on what changes is told to it");
        assertFalse(answered.isAnswered(first), "and never again to the first");
    }

    @Test
    void noDragHearsAnythingUnaskedOnceForgotten() {
        AnsweredDrag answered = new AnsweredDrag();
        Object drag = new Object();
        answered.answered(drag);
        answered.forget();
        assertFalse(answered.isAnswered(drag), "a drag that left hears nothing");
        assertFalse(answered.isAnswered(null), "nor does no drag");
    }
}
