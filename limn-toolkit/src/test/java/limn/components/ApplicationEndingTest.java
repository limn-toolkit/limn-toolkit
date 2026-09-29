package limn.components;

import limn.backend.ClosePolicy;
import limn.backend.CloseRequest;
import limn.backend.LoopEnd;
import limn.backend.QuitRequest;
import limn.scene.Scene;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a window's close is decided and how an application ends by asking once (ADR 048), over the
 * headless backend, which decides them as every backend does.
 */
class ApplicationEndingTest extends ComponentTestBase {

    private final HeadlessBackend backend = new HeadlessBackend(runtime);
    private final List<String> asked = new ArrayList<>();
    private final List<CloseRequest> requests = new ArrayList<>();

    /** A window whose handler keeps each request, to be answered by the test. */
    private HeadlessWindow keeping(String title) {
        HeadlessWindow window = backend.open(title, 200, 100);
        window.setCloseRequestHandler(request -> {
            asked.add(title + " " + request.reason());
            requests.add(request);
        });
        return window;
    }

    /** A window whose handler answers inside its call. */
    private HeadlessWindow answering(String title, boolean allow) {
        HeadlessWindow window = backend.open(title, 200, 100);
        window.setCloseRequestHandler(request -> {
            asked.add(title + " " + request.reason());
            if (allow) {
                request.allow();
            } else {
                request.refuse();
            }
        });
        return window;
    }

    // ------------------------------------------------------------------ a user's close

    @Test
    void aUserCloseWithNoHandlerDisposesTheWindow() {
        HeadlessWindow window = backend.open("main", 200, 100);
        window.userClose();
        assertTrue(window.isClosed());
    }

    @Test
    void aUserCloseWaitsForTheAnswerAndThenDoesWhatThePolicySays() {
        HeadlessWindow window = keeping("main");
        window.show();
        window.userClose();
        assertEquals(List.of("main USER_CLOSE"), asked);
        assertFalse(window.isClosed(), "nothing happens until the handler answers");

        requests.get(0).allow();
        assertTrue(window.isClosed());
    }

    @Test
    void aRefusedUserCloseKeepsTheWindow() {
        HeadlessWindow window = keeping("main");
        window.userClose();
        requests.get(0).refuse();
        assertFalse(window.isClosed());

        window.userClose(); // answered, so the next click asks again
        assertEquals(2, asked.size());
    }

    @Test
    void aSecondClickWhileTheQuestionIsOpenAsksNothingNew() {
        HeadlessWindow window = keeping("main");
        window.userClose();
        window.userClose();
        window.userClose();
        assertEquals(List.of("main USER_CLOSE"), asked, "one dialog, however many clicks");
    }

    @Test
    void aHidingWindowIsHiddenAndKept() {
        HeadlessWindow window = backend.open("palette", 200, 100);
        window.setClosePolicy(ClosePolicy.HIDE);
        window.show();
        window.userClose();
        assertFalse(window.isVisible());
        assertFalse(window.isClosed());
    }

    @Test
    void aProgramsCloseAsksNothing() {
        HeadlessWindow window = keeping("main");
        window.setClosePolicy(ClosePolicy.HIDE);
        window.requestClose();
        assertTrue(window.isClosed(), "a program's close disposes whatever the policy says");
        assertEquals(List.of(), asked);
    }

    @Test
    void aWindowThatEndsTheApplicationAsksEveryWindow() {
        HeadlessWindow other = answering("other", true);
        HeadlessWindow main = answering("main", true);
        main.setClosePolicy(ClosePolicy.END_APPLICATION);

        main.userClose();

        assertEquals(List.of("main APPLICATION_END", "other APPLICATION_END"), asked);
        assertTrue(main.isClosed());
        assertTrue(other.isClosed());
        assertEquals(LoopEnd.QUIT, backend.ended());
    }

    @Test
    void anAnswerIsGivenOnce() {
        HeadlessWindow window = keeping("main");
        window.userClose();
        CloseRequest request = requests.get(0);
        request.refuse();
        assertTrue(request.isAnswered());
        assertThrows(IllegalStateException.class, request::allow);
        assertThrows(IllegalStateException.class, request::refuse);
    }

    // ------------------------------------------------------------------ the application ending

    @Test
    void windowsAreAskedFrontToBackOneAtATime() {
        HeadlessWindow first = keeping("first");
        HeadlessWindow second = keeping("second");
        HeadlessWindow third = keeping("third");
        bind(first).desktopFocus(true); // the user worked in the oldest window last

        backend.requestQuit();
        assertEquals(List.of("first APPLICATION_END"), asked, "the next waits for this answer");

        requests.get(0).allow();
        assertEquals(List.of("first APPLICATION_END", "third APPLICATION_END"), asked,
                "then the newest, which opened in front of the rest");
        assertFalse(first.isClosed(), "nothing closes before every window agreed");

        requests.get(1).allow();
        requests.get(2).allow();
        assertEquals(List.of("first APPLICATION_END", "third APPLICATION_END",
                "second APPLICATION_END"), asked);
        assertTrue(first.isClosed() && second.isClosed() && third.isClosed());
        assertEquals(LoopEnd.QUIT, backend.ended());
    }

    @Test
    void oneRefusalKeepsEveryWindowOpen() {
        HeadlessWindow back = answering("back", true);
        HeadlessWindow middle = answering("middle", false);
        HeadlessWindow front = answering("front", true);

        backend.requestQuit();

        assertEquals(List.of("front APPLICATION_END", "middle APPLICATION_END"), asked,
                "the refusal ends the request; the window behind it is never asked");
        assertFalse(front.isClosed(), "the window that agreed is not closed: this is GLFW's partial quit");
        assertFalse(middle.isClosed());
        assertFalse(back.isClosed());
        assertNull(backend.ended());

        asked.clear();
        backend.requestQuit(); // over: a new request asks again
        assertEquals(List.of("front APPLICATION_END", "middle APPLICATION_END"), asked);
    }

    @Test
    void aRequestMadeWhileOneIsAnsweredJoinsIt() {
        keeping("main");
        backend.requestQuit();
        backend.requestQuit();
        assertEquals(List.of("main APPLICATION_END"), asked);
    }

    @Test
    void theQuitHandlerIsAskedFirstAndMayRefuse() {
        answering("main", true);
        List<QuitRequest> quits = new ArrayList<>();
        backend.setQuitHandler(request -> {
            asked.add("application");
            quits.add(request);
        });

        backend.requestQuit();
        assertEquals(List.of("application"), asked);
        quits.get(0).refuse();
        assertEquals(List.of("application"), asked, "no window is asked after the refusal");
        assertNull(backend.ended());

        backend.requestQuit();
        quits.get(1).allow();
        assertEquals(List.of("application", "application", "main APPLICATION_END"), asked);
        assertEquals(LoopEnd.QUIT, backend.ended());
    }

    @Test
    void aWindowWithNoHandlerAgrees() {
        HeadlessWindow plain = backend.open("plain", 200, 100);
        backend.requestQuit();
        assertTrue(plain.isClosed());
        assertEquals(LoopEnd.QUIT, backend.ended());
    }

    @Test
    void aWindowAlreadyAskedByTheUserIsNotAskedTwice() {
        HeadlessWindow window = keeping("main");
        window.userClose();
        backend.requestQuit();
        assertEquals(List.of("main USER_CLOSE"), asked, "its open question counts for the request");

        requests.get(0).allow();
        assertTrue(window.isClosed());
        assertEquals(LoopEnd.QUIT, backend.ended());
    }

    @Test
    void aWindowThatClosesWhileItIsAskedCountsAsAgreeing() {
        HeadlessWindow front = keeping("front");
        HeadlessWindow back = answering("back", true);
        backend.open("unused", 1, 1).close();

        backend.requestQuit();
        front.requestClose(); // the program closed it before its dialog answered

        assertTrue(back.isClosed());
        assertEquals(LoopEnd.QUIT, backend.ended());
    }

    @Test
    void aHandlerThatThrowsHasRefused() {
        HeadlessWindow window = backend.open("main", 200, 100);
        window.setCloseRequestHandler(request -> {
            throw new IllegalStateException("broken handler");
        });

        assertThrows(IllegalStateException.class, backend::requestQuit);
        assertFalse(window.isClosed());
        assertNull(backend.ended());

        window.setCloseRequestHandler(CloseRequest::allow);
        backend.requestQuit();
        assertEquals(LoopEnd.QUIT, backend.ended(), "the failed request is over, not stuck");
    }

    @Test
    void anOwnedWindowIsNotAskedAndClosesWithItsOwner() {
        HeadlessWindow main = answering("main", true);
        HeadlessWindow palette = answering("palette", false);
        main.registerChildPopup(palette);

        backend.requestQuit();

        assertEquals(List.of("main APPLICATION_END"), asked, "its owner speaks for it");
        assertTrue(palette.isClosed());
        assertEquals(LoopEnd.QUIT, backend.ended());
    }

    @Test
    void aNativeDialogOpenWhenTheApplicationEndsCompletesAsCancelled() {
        HeadlessWindow main = backend.open("main", 400, 300);
        Scene scene = new Scene(new Label("document"));
        scene.bind(main);
        CompletableFuture<String> answer = new Dialog("Save?", "Unsaved changes.")
                .setDisplayMode(DisplayMode.NATIVE_WINDOW)
                .addButton("Discard", "discard")
                .addPrimaryButton("Save", "save")
                .setCancelResult("cancelled")
                .show(scene)
                .toCompletableFuture();
        assertEquals(2, backend.windows().size(), "the dialog is a window of its own");

        backend.requestQuit();

        assertEquals(LoopEnd.QUIT, backend.ended(), "the blocked owner has no handler, so it agreed");
        assertTrue(answer.isDone());
        assertEquals("cancelled", answer.join());
    }

    private HeadlessWindow bind(HeadlessWindow window) {
        new Scene(new Label(window.title())).bind(window);
        return window;
    }
}
