package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleTree;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import limn.testing.TestPreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dialog asks its cancel handler before the user closes it with nothing chosen, in both display
 * modes, by every way there is: Escape, a click on the scrim, a button whose result is the cancel
 * result, the system closing the dialog's own window, and a screen reader's dismiss. The request
 * is answered now or later; held, it asks nothing new; the application's own dismiss, an answer
 * and a destructive button are never asked about; and a handler that throws has refused. A form
 * whose changes would be lost asks "Save changes?" over itself that way, which until this existed
 * it could only ask after it had gone.
 */
class DialogCancelRequestTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private HeadlessBackend backend;
    private HeadlessWindow ownerWindow;
    private Scene host;
    private Dialog dialog;
    /** The scene the dialog's keys go to: the owner's in the scene, the dialog's own in a window. */
    private Scene keys;
    private final AtomicReference<String> result = new AtomicReference<>("<open>");
    private final List<Dialog.CancelRequest> asked = new ArrayList<>();
    private TestPreferences preferences;

    @AfterEach
    void restorePreferences() {
        if (preferences != null) {
            preferences.close();
        }
    }

    /** A customer's form: Cancel gives the cancel result, Save the answer, Delete destroys. */
    private Dialog form() {
        return new Dialog("Edit customer", "")
                .setContent(new TextField().setText("Harbor Supply"))
                .addButton("Cancel", "cancel")
                .addDestructiveButton("Delete", "delete")
                .addPrimaryButton("Save", "save")
                .setCancelResult("cancel")
                .setDismissOnScrim(true);
    }

    private void open(DisplayMode mode, Dialog d, Consumer<Dialog.CancelRequest> handler) {
        result.set("<open>");
        asked.clear();
        dialog = d.setDisplayMode(mode).onCancel(request -> {
            asked.add(request);
            handler.accept(request);
        });
        if (mode == DisplayMode.NATIVE_WINDOW) {
            dialog.setStyle(limn.backend.WindowStyle.DECORATED); // a window with a close button
        }
        backend = new HeadlessBackend(runtime);
        ownerWindow = backend.open("Orders", 600, 400);
        host = new Scene(new Label("Orders"), clock::get);
        host.setTextRuler(RULER);
        host.bind(ownerWindow);
        ownerWindow.frame();
        dialog.show(host).thenAccept(result::set);
        assertSame(mode, dialog.displayMode(), "sanity: opened in the mode asked for");
        keys = mode == DisplayMode.IN_SCENE ? host : dialog.contentRoot().scene();
        settle();
    }

    /** Frames every window over half a second of the clock, so fades in and out finish. */
    private void settle() {
        for (int i = 0; i < 12; i++) {
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

    private void press(int key) {
        drive(keys).keyEvent(key, true, false, 0);
        drive(keys).keyEvent(key, false, false, 0);
        drive(keys).inputBatchEnded();
        settle();
    }

    private void click(String caption) {
        drive(keys).click(button(caption));
        settle();
    }

    private Button button(String caption) {
        Button found = find(dialog.contentRoot(), caption);
        assertTrue(found != null, "no button " + caption);
        return found;
    }

    private static Button find(Widget<?> root, String caption) {
        if (root instanceof Button b && b.text().equals(caption)) {
            return b;
        }
        for (Widget<?> child : root.children()) {
            Button found = find(child, caption);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The window the dialog is published in: its own, or the owner's in the scene. */
    private HeadlessWindow publishing() {
        for (HeadlessWindow w : backend.windows()) {
            if (w.scene() == keys) {
                return w;
            }
        }
        throw new AssertionError("no window shows the dialog");
    }

    /** A screen reader's dismiss of the card, from the platform's thread, as a bridge sends it. */
    private void readerCancel() throws InterruptedException {
        HeadlessWindow window = publishing();
        AccessibleTree tree = window.bridge().tree();
        long card = -1;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == Accessible.Role.DIALOG) {
                card = tree.node(i).id();
            }
        }
        assertTrue(card >= 0, "the card is published");
        long id = card;
        Thread platform = new Thread(() -> window.bridge().host.perform(id, Accessible.Action.CANCEL,
                Accessible.Argument.NONE), "platform-thread");
        platform.start();
        platform.join(TimeUnit.SECONDS.toMillis(10));
        settle();
    }

    /** A click outside the card, on the scrim, in the owner's scene. */
    private void clickScrim() {
        drive(host).click(4, 4);
        settle();
    }

    private void assertOpen(String what) {
        assertEquals("<open>", result.get(), what + ": the dialog is still open");
    }

    // ------------------------------------------------------------- every way of cancelling

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void everyWayTheUserCancelsAsksWithItsReasonAndARefusalKeepsTheFormOpen(DisplayMode mode)
            throws InterruptedException {
        List<Dialog.CancelRequest.Reason> expected = new ArrayList<>();
        open(mode, form(), Dialog.CancelRequest::refuse);

        press(Keys.ESCAPE);
        expected.add(Dialog.CancelRequest.Reason.ESCAPE);
        click("Cancel");
        expected.add(Dialog.CancelRequest.Reason.BUTTON);
        readerCancel();
        expected.add(Dialog.CancelRequest.Reason.ASSISTIVE_TECHNOLOGY);
        if (mode == DisplayMode.IN_SCENE) {
            clickScrim();
            expected.add(Dialog.CancelRequest.Reason.SCRIM);
        } else {
            publishing().userClose();
            settle();
            expected.add(Dialog.CancelRequest.Reason.WINDOW_CLOSE);
            assertFalse(publishing().isClosed(), "the dialog's window stays when the handler refuses");
        }
        assertEquals(expected, asked.stream().map(Dialog.CancelRequest::reason).toList(), mode.name());
        assertTrue(asked.stream().allMatch(Dialog.CancelRequest::isAnswered));
        assertOpen(mode.name());
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aRefusalLeavesTheKeyboardWhereItWas(DisplayMode mode) {
        open(mode, form(), Dialog.CancelRequest::refuse);
        Widget<?> before = keys.focusedWidget();
        assertTrue(before instanceof TextField, "the field has the first focus: " + before);
        press(Keys.ESCAPE);
        assertSame(before, keys.focusedWidget(), "the keyboard did not move");
        assertOpen(mode.name());
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anAllowedRequestClosesWithTheCancelResultWithAndWithoutMotion(DisplayMode mode) {
        for (boolean reduced : new boolean[] {false, true}) {
            preferences = new TestPreferences().reduceMotion(reduced);
            open(mode, form(), request -> { }); // held
            press(Keys.ESCAPE);
            assertEquals(1, asked.size());
            assertOpen(mode + (reduced ? ", less motion" : "") + ", held");
            asked.get(0).allow();
            settle();
            assertEquals("cancel", result.get(), mode + (reduced ? ", less motion" : ""));
            assertThrows(IllegalStateException.class, () -> asked.get(0).refuse(), "answered once");
            preferences.close();
            preferences = null;
        }
    }

    // ------------------------------------------------------------- a question over the form

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aQuestionAskedOverTheFormDecidesLaterAndTheFormWaitsForIt(DisplayMode mode) {
        AtomicReference<Dialog> question = new AtomicReference<>();
        open(mode, form(), request -> {
            Dialog asking = new Dialog("Save changes?", "")
                    .addButton("Don't save", "discard")
                    .addButton("Cancel", "cancel")
                    .addPrimaryButton("Save", "save")
                    .setCancelResult("cancel")
                    .setDisplayMode(mode);
            if (mode == DisplayMode.IN_SCENE) {
                asking.keepInScene();
            }
            question.set(asking);
            asking.show(host).thenAccept(answer -> {
                if ("cancel".equals(answer)) {
                    request.refuse();          // back to the form, as it was left
                } else {
                    request.dialog().dismiss(answer); // the form closes with that answer
                }
            });
        });
        press(Keys.ESCAPE);
        assertOpen(mode + ", the question is open over it");
        assertSame(dialog, asked.get(0).dialog(), "the request names the dialog it asks about");
        assertFalse(question.get().result().toCompletableFuture().isDone());
        question.get().dismiss("cancel");
        settle();
        assertOpen(mode + ", the question's Cancel goes back to the form");

        click("Cancel");
        question.get().dismiss("discard");
        settle();
        assertEquals("discard", result.get(), mode + ", Don't save closes the form with its own answer");
        assertEquals(2, asked.size());
    }

    /**
     * Save in the question closes the form with "save", which is what the code that opened the
     * form persists on: answered with {@code allow()}, the form closed with its cancel result and
     * the caller saved nothing, exactly when the user chose Save. The request is settled by the
     * dismiss, and a late answer to it does nothing.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void saveInTheQuestionClosesTheFormWithSaveThroughTheRequestsDialog(DisplayMode mode) {
        List<Dialog> opened = new ArrayList<>();
        open(mode, form(), request -> question(mode, opened).show(host).thenAccept(answer -> {
            if ("cancel".equals(answer)) {
                request.refuse();
            } else {
                request.dialog().dismiss(answer);
            }
        }));
        click("Cancel");
        opened.get(0).dismiss("save");
        settle();
        assertEquals("save", result.get(), mode + ": the caller of show() is told save, and saves");
        Dialog.CancelRequest request = asked.get(0);
        request.refuse(); // too late: the form has its answer
        settle();
        assertEquals("save", result.get(), mode + ": a late answer changes nothing");
    }

    // ------------------------------------------------------------- a key held down

    /** The question a changed form asks over itself, as the guide and the Showcase ask it. */
    private Dialog question(DisplayMode mode, List<Dialog> opened) {
        Dialog asking = new Dialog("Save changes?", "")
                .addButton("Don't save", "discard")
                .addButton("Cancel", "cancel")
                .addPrimaryButton("Save", "save")
                .setCancelResult("cancel")
                .setDisplayMode(mode);
        if (mode == DisplayMode.IN_SCENE) {
            asking.keepInScene();
        }
        opened.add(asking);
        return asking;
    }

    /** Where a key goes now: the top open question's own window, or the form's keys. */
    private Scene keyboard(List<Dialog> opened) {
        for (int i = opened.size() - 1; i >= 0; i--) {
            Dialog q = opened.get(i);
            if (!q.result().toCompletableFuture().isDone() && q.displayMode() == DisplayMode.NATIVE_WINDOW) {
                return q.contentRoot().scene();
            }
        }
        return keys;
    }

    /** One step of a key held down: 33 ms of the clock, every window framed twice. */
    private void step() {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(33));
        runtime.drain();
        for (int pass = 0; pass < 2; pass++) {
            for (HeadlessWindow w : List.copyOf(backend.windows())) {
                if (w.scene() != null && !w.isClosed()) {
                    w.frame();
                }
            }
        }
    }

    /**
     * {@code key} pressed, then repeated by the keyboard every 33 ms for a second and a half, each
     * repeat going to whatever has the keyboard then, then released.
     */
    private void hold(int key, List<Dialog> opened) {
        drive(keys).keyEvent(key, true, false, 0);
        drive(keys).inputBatchEnded();
        step();
        for (int t = 0; t < 1500; t += 33) {
            Scene target = keyboard(opened);
            drive(target).keyEvent(key, true, true, 0);
            drive(target).inputBatchEnded();
            step();
        }
        Scene target = keyboard(opened);
        drive(target).keyEvent(key, false, false, 0);
        drive(target).inputBatchEnded();
        settle();
    }

    /**
     * Escape held down on a changed form asks once. The keyboard's repeats land in the question it
     * opened, and acted on there they cancelled it, which gave the form back the keyboard and the
     * next repeat asked again: a question opening and closing for as long as the key was held, a
     * whole-window strobe under less motion, and a window made and destroyed per turn in a window
     * of its own.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void escapeHeldDownAsksOnceAndItsRepeatsDoNotAnswerTheQuestion(DisplayMode mode) {
        for (boolean reduced : new boolean[] {false, true}) {
            String what = mode + (reduced ? ", less motion" : "");
            preferences = new TestPreferences().reduceMotion(reduced);
            List<Dialog> opened = new ArrayList<>();
            open(mode, form(), request -> question(mode, opened).show(host).thenAccept(answer -> {
                if ("cancel".equals(answer)) {
                    request.refuse();
                } else {
                    request.dialog().dismiss(answer);
                }
            }));
            hold(Keys.ESCAPE, opened);
            assertEquals(1, asked.size(), what + ": one question for one press");
            assertEquals(1, opened.size(), what + ": one question opened");
            assertFalse(opened.get(0).result().toCompletableFuture().isDone(),
                    what + ": the repeats did not cancel the question");
            assertOpen(what);
            opened.get(0).dismiss("cancel");
            settle();
            assertOpen(what + ", the question's Cancel");
            preferences.close();
            preferences = null;
        }
    }

    /**
     * Return held down answers once: on an alert whose OK is its cancel result, the press asks, and
     * the repeats do not press the question's default button.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void returnHeldDownAnswersOnceAndItsRepeatsDoNotAnswerTheQuestion(DisplayMode mode) {
        List<Dialog> opened = new ArrayList<>();
        Dialog alert = new Dialog("Stock running low", "").addPrimaryButton("OK", "ok");
        alert.setCancelResult("ok");
        open(mode, alert, request -> question(mode, opened).show(host).thenAccept(answer -> {
            if ("cancel".equals(answer)) {
                request.refuse();
            } else {
                request.dialog().dismiss(answer);
            }
        }));
        hold(Keys.ENTER, opened);
        assertEquals(1, asked.size(), mode + ": one question for one press");
        assertFalse(opened.get(0).result().toCompletableFuture().isDone(),
                mode + ": the repeats did not answer the question with its default button");
        assertOpen(mode.name());
    }

    // ------------------------------------------------------------- what is never asked

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void theDefaultAnswerAndADestructiveButtonAreNeverAsked(DisplayMode mode) {
        open(mode, form(), Dialog.CancelRequest::refuse);
        click("Save");
        assertEquals("save", result.get());
        open(mode, form(), Dialog.CancelRequest::refuse);
        click("Delete");
        assertEquals("delete", result.get());
        assertEquals(List.of(), asked, "an answer is not a cancel");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void theApplicationsOwnDismissIsNeverAskedEvenWithTheCancelResult(DisplayMode mode) {
        open(mode, form(), Dialog.CancelRequest::refuse);
        dialog.dismiss("cancel");
        settle();
        assertEquals("cancel", result.get());
        assertEquals(List.of(), asked);
    }

    /**
     * A button is judged by its result when it is pressed: an OK set as the cancel result after it
     * was added, Return answering through it as the default button, is asked about.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aDefaultButtonWhoseResultIsTheCancelResultIsAskedAbout(DisplayMode mode) {
        Dialog alert = new Dialog("Stock running low", "").addPrimaryButton("OK", "ok");
        alert.setCancelResult("ok");
        open(mode, alert, Dialog.CancelRequest::refuse);
        press(Keys.ENTER);
        assertEquals(List.of(Dialog.CancelRequest.Reason.BUTTON),
                asked.stream().map(Dialog.CancelRequest::reason).toList());
        assertOpen(mode.name());
    }

    // ------------------------------------------------------------- while a request is held

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void whileARequestIsHeldNoWayOfCancellingAsksAgain(DisplayMode mode) throws InterruptedException {
        open(mode, form(), request -> { });
        press(Keys.ESCAPE);
        press(Keys.ESCAPE);
        click("Cancel");
        readerCancel();
        if (mode == DisplayMode.IN_SCENE) {
            clickScrim();
        } else {
            publishing().userClose();
            settle();
        }
        assertEquals(1, asked.size(), "one question, held");
        asked.get(0).allow();
        settle();
        assertEquals("cancel", result.get());
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anAnswerGivenAnotherWayWhileARequestIsHeldSettlesIt(DisplayMode mode) {
        open(mode, form(), request -> { });
        press(Keys.ESCAPE);
        click("Save");
        assertEquals("save", result.get(), "the form is not frozen by a held request");
        Dialog.CancelRequest held = asked.get(0);
        assertFalse(held.isAnswered(), "settled by the answer, and not answered: neither allow nor refuse was called");
        held.allow(); // the application answering its own question late: nothing, and no throw
        assertTrue(held.isAnswered(), "answered now, as a window's and a tab's request count it");
        assertThrows(IllegalStateException.class, held::refuse, "a second answer throws, as on every request");
        settle();
        assertEquals("save", result.get());

        open(mode, form(), request -> { });
        press(Keys.ESCAPE);
        dialog.dismiss("closed");
        settle();
        assertFalse(asked.get(0).isAnswered(), "settled by the application's dismiss, not answered");
        asked.get(0).refuse();
        assertEquals("closed", result.get());
    }

    // ------------------------------------------------------------- a handler that throws

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aHandlerThatThrowsHasRefusedAndTheNextCancelAsksAgain(DisplayMode mode) {
        open(mode, form(), request -> {
            throw new IllegalStateException("the form's check failed");
        });
        press(Keys.ESCAPE);
        assertOpen(mode + ", the handler threw");
        assertTrue(asked.get(0).isAnswered(), "refused, not held");
        click("Cancel");
        assertEquals(2, asked.size(), "a refused request is over, so the next cancel asks");
        assertOpen(mode.name());
        click("Save");
        assertEquals("save", result.get(), "the user is not trapped: the answers still answer");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void withoutAHandlerEveryCancelClosesAsBefore(DisplayMode mode) {
        result.set("<open>");
        backend = new HeadlessBackend(runtime);
        ownerWindow = backend.open("Orders", 600, 400);
        host = new Scene(new Label("Orders"), clock::get);
        host.setTextRuler(RULER);
        host.bind(ownerWindow);
        ownerWindow.frame();
        dialog = form().setDisplayMode(mode);
        if (mode == DisplayMode.NATIVE_WINDOW) {
            dialog.setStyle(limn.backend.WindowStyle.DECORATED);
        }
        dialog.show(host).thenAccept(result::set);
        keys = mode == DisplayMode.IN_SCENE ? host : dialog.contentRoot().scene();
        settle();
        if (mode == DisplayMode.NATIVE_WINDOW) {
            publishing().userClose(); // the system's close of the dialog's window
        } else {
            press(Keys.ESCAPE);
        }
        settle();
        assertEquals("cancel", result.get());
    }
}
