package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modality rules, with String tokens as windows. {@code LOCKS} treats a
 * parent as locking only itself (no owned popups in these cases).
 */
class ModalStackTest {

    private static final BiPredicate<Object, Object> LOCKS = (parent, w) -> parent == w;

    private final ModalStack stack = new ModalStack();

    @Test
    void nothingBlockedWithoutModals() {
        assertFalse(stack.isBlocked("main", LOCKS, LOCKS));
        assertNull(stack.topModal());
    }

    @Test
    void windowModalLocksOnlyItsParent() {
        String main = "main";
        String other = "other";
        String modal = "modal";
        stack.push(modal, main);

        assertTrue(stack.isBlocked(main, LOCKS, LOCKS), "parent is locked");
        assertFalse(stack.isBlocked(other, LOCKS, LOCKS), "unrelated window stays free");
        assertFalse(stack.isBlocked(modal, LOCKS, LOCKS), "the modal itself is interactive");
        assertSame(modal, stack.topModal());
    }

    @Test
    void toolkitModalLocksEveryOtherWindow() {
        stack.push("modal", null); // no parent → toolkit-modal
        assertTrue(stack.isBlocked("main", LOCKS, LOCKS));
        assertTrue(stack.isBlocked("other", LOCKS, LOCKS));
        assertFalse(stack.isBlocked("modal", LOCKS, LOCKS), "the modal itself is interactive");
    }

    @Test
    void stackedModalsLeaveOnlyTheTopInteractive() {
        String main = "main";
        stack.push("modal1", main);
        stack.push("modal2", "modal1"); // opened from modal1

        assertSame("modal2", stack.topModal());
        assertFalse(stack.isBlocked("modal2", LOCKS, LOCKS), "top modal is interactive");
        assertTrue(stack.isBlocked("modal1", LOCKS, LOCKS), "lower modal is frozen");
        assertTrue(stack.isBlocked(main, LOCKS, LOCKS), "the original parent stays locked");
    }

    @Test
    void nonModalsReleaseOnlyAfterAllModalsClose() {
        String main = "main";
        stack.push("modal1", main);
        stack.push("modal2", main); // both lock main

        assertTrue(stack.isBlocked(main, LOCKS, LOCKS));
        stack.pop("modal2");
        assertTrue(stack.isBlocked(main, LOCKS, LOCKS), "still one modal open");
        assertSame("modal1", stack.topModal());
        stack.pop("modal1");
        assertFalse(stack.isBlocked(main, LOCKS, LOCKS), "released after the last modal closes");
    }

    @Test
    void toolkitModalOverANonModalReleasesOnClose() {
        stack.push("dlg", null);
        assertTrue(stack.isBlocked("a", LOCKS, LOCKS));
        assertTrue(stack.isBlocked("b", LOCKS, LOCKS));
        stack.pop("dlg");
        assertFalse(stack.isBlocked("a", LOCKS, LOCKS));
        assertFalse(stack.isBlocked("b", LOCKS, LOCKS));
    }

    @Test
    void sceneModalKeepsItsHostAndItsHostsPopupsInteractive() {
        // In-scene modal hosted by "main": the overlay blocks main's own content, so
        // main stays interactive, and so does main's popup window, because a dialog
        // drawn inside main opens its dropdowns as popups of main. Locking those makes
        // a ComboBox in the dialog dead: the popup is created and never becomes usable.
        BiPredicate<Object, Object> owns = (parent, w) ->
                parent == w || (parent.equals("main") && w.equals("popup"));
        Object token = new Object();
        stack.push(token, "main", "main"); // window-scope, owner-exception = main
        assertFalse(stack.isBlocked("main", owns, owns), "host window stays interactive (hosts the overlay)");
        assertFalse(stack.isBlocked("popup", owns, owns), "and so does the popup that host owns");
        assertSame(token, stack.topModal());
    }

    @Test
    void toolkitSceneModalLocksEveryWindowButItsHostAndTheHostsPopups() {
        // Toolkit scope locks by default, so the exemption is the only thing standing
        // between an overlay and its own dropdown here, and it must not spill onto
        // another window's popup, which has nothing to do with the overlay.
        BiPredicate<Object, Object> owns = (parent, w) -> parent == w
                || (parent.equals("main") && w.equals("popup"))
                || (parent.equals("other") && w.equals("other-popup"));
        Object token = new Object();
        stack.push(token, null, "main"); // toolkit-scope, owner-exception = main
        assertFalse(stack.isBlocked("main", owns, owns), "host exempt");
        assertFalse(stack.isBlocked("popup", owns, owns), "the host's own popup with it");
        assertTrue(stack.isBlocked("other", owns, owns), "every other window is locked");
        assertTrue(stack.isBlocked("other-popup", owns, owns), "including the popups of other windows");
    }

    @Test
    void removeOwnedByReleasesSceneModalsWhenTheHostCloses() {
        Object token = new Object();
        stack.push(token, null, "main");
        assertTrue(stack.isBlocked("other", LOCKS, LOCKS));
        assertTrue(stack.removeOwnedBy("main"));
        assertFalse(stack.isBlocked("other", LOCKS, LOCKS), "released once the host closed");
        assertFalse(stack.removeOwnedBy("main"), "nothing left to remove");
    }

    /**
     * The regression this class exists for. A native dialog locks the main window;
     * the app then raises an in-scene dialog on that same main window (an
     * unsaved-changes prompt on the way out). Before the top-surface rule the two
     * modals blocked each other: the native one locked the only window the overlay
     * could be answered from, and the overlay locked the native one as an owned
     * popup. Two dialogs on screen, neither answerable.
     */
    @Test
    void aSceneModalOverALockedWindowIsStillAnswerable() {
        BiPredicate<Object, Object> owns = (parent, w) ->
                parent == w || (parent.equals("main") && w.equals("picker"));
        stack.push("picker", "main");        // native dialog, window-modal on main
        assertTrue(stack.isBlocked("main", owns, owns));

        Object prompt = new Object();
        stack.push(prompt, "main", "main");  // in-scene dialog hosted by main

        assertFalse(stack.isBlocked("main", owns, owns),
                "main hosts the top modal; blocking it leaves nothing to answer");
        // main owns picker here, because a native dialog registers its window with its
        // owner so it closes with it. The exemption the overlay grants main must not
        // reach it: being below the top is decided first, and wins.
        assertTrue(stack.isBlocked("picker", owns, owns), "the older dialog waits its turn");
    }

    @Test
    void theOlderModalTakesOverWhenTheSceneModalCloses() {
        BiPredicate<Object, Object> owns = (parent, w) ->
                parent == w || (parent.equals("main") && w.equals("picker"));
        stack.push("picker", "main");
        Object prompt = new Object();
        stack.push(prompt, "main", "main");
        stack.pop(prompt);

        assertSame("picker", stack.topModal());
        assertFalse(stack.isBlocked("picker", owns, owns), "back on top, back to interactive");
        assertTrue(stack.isBlocked("main", owns, owns), "and main is locked by it again");
    }

    @Test
    void lockingConsidersOwnedPopups() {
        // 'popup' is an owned popup of 'main'. A native modal names no owner-exception,
        // so nothing is exempt from it: a dropdown left open on the window it locked is
        // as frozen as the window, which is the case this predicate exists for.
        BiPredicate<Object, Object> owns = (parent, w) ->
                parent == w || (parent.equals("main") && w.equals("popup"));
        stack.push("modal", "main");
        assertTrue(stack.isBlocked("main", owns, owns));
        assertTrue(stack.isBlocked("popup", owns, owns), "the parent's popup is locked too");
    }

    @Test
    void aToolkitModalWithNoHostStillLocksEveryPopup() {
        // The other no-exception shape: toolkit scope without an overlay. Same rule:
        // an exemption reachable only through a named host must not appear here.
        BiPredicate<Object, Object> owns = (parent, w) ->
                parent == w || (parent.equals("main") && w.equals("popup"));
        stack.push("modal", null);
        assertTrue(stack.isBlocked("main", owns, owns));
        assertTrue(stack.isBlocked("popup", owns, owns));
    }

    // ------------------------------------------------------- the decided contracts

    /**
     * Pushing a window that is already modal is a programming error. It used to move the entry
     * to the top AND adopt the new parent and owner-exception, in silence: the caller believed
     * it had pushed a second modal, the stack held one, and popping either released the only
     * entry. No shipped caller ever asked for it.
     */
    @Test
    void pushingAWindowThatIsAlreadyModalThrows() {
        stack.push("modal", "main");

        assertThrows(IllegalStateException.class, () -> stack.push("modal", "other"));
        assertThrows(IllegalStateException.class, () -> stack.push("modal", "main", "main"));
        assertSame("modal", stack.topModal(), "and the stack is untouched by the refusal");
        assertTrue(stack.isBlocked("main", LOCKS, LOCKS), "the original scope still stands");
        assertFalse(stack.isBlocked("other", LOCKS, LOCKS), "the rejected scope was not adopted");
    }

    /**
     * The exemption an in-scene modal grants its host covers the host's own content reaching
     * outside its frame (a dropdown, a menu) and NOT a window of its own. A non-modal dialog
     * or a floating palette owned by the host is blocked, the way a native modal would block it.
     */
    @Test
    void theHostExemptionCoversItsTransientPopupsAndNotItsOwnedWindows() {
        // Scope: main owns both. Exemption: only the dropdown is main's own content.
        BiPredicate<Object, Object> owns = (parent, w) -> parent == w
                || (parent.equals("main") && (w.equals("dropdown") || w.equals("palette")));
        BiPredicate<Object, Object> transient_ = (host, w) -> host == w
                || (host.equals("main") && w.equals("dropdown"));
        Object overlay = new Object();
        stack.push(overlay, null, "main"); // toolkit-scope in-scene modal hosted by main

        assertFalse(stack.isBlocked("main", owns, transient_), "the host draws the overlay");
        assertFalse(stack.isBlocked("dropdown", owns, transient_),
                "its own dropdown is the host reaching outside its frame");
        assertTrue(stack.isBlocked("palette", owns, transient_),
                "a window of its own is locked like any other; this is the case that was wrong");
    }

    /**
     * Ownership is one level deep for locking, deliberately. A menu keeps a whole cascade inside
     * one window, so a popup-of-a-popup in separate windows does not arise; asserting it here is
     * what turns that from folklore into something the first component to try it discovers in the
     * build rather than on screen.
     */
    @Test
    void lockingIsNotTransitiveDownAPopupChain() {
        // main owns popup; popup owns sub. Neither predicate walks the chain.
        BiPredicate<Object, Object> owns = (parent, w) -> parent == w
                || (parent.equals("main") && w.equals("popup"))
                || (parent.equals("popup") && w.equals("sub"));
        stack.push("modal", "main");

        assertTrue(stack.isBlocked("main", owns, owns), "the parent is locked");
        assertTrue(stack.isBlocked("popup", owns, owns), "and what it directly owns");
        assertFalse(stack.isBlocked("sub", owns, owns),
                "but not its grandchild: one level deep, by decision");
    }
}
