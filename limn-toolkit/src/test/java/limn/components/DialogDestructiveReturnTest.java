package limn.components;

import limn.accessibility.Accessible;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dialog's destructive button never answers Return, whatever the order the buttons were added
 * in and whatever holds the focus, in both display modes: the dialog's first focus goes to its
 * default button, or to its first button that destroys nothing, and a focused destructive button
 * lets Return through to the dialog, which gives the default answer or none. Space and a reader's
 * press still press it.
 *
 * <p>Every case opens the dialog through {@link Dialog#show} over a window, as an application
 * does, because the first focus is the presentation's: a test that calls the scene's traversal
 * on the card by hand, or that gives the body a text field, never sees which button the dialog
 * itself focuses, and that is how the destructive button came to take it.
 */
class DialogDestructiveReturnTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private HeadlessBackend backend;
    private Dialog dialog;
    /** The scene the dialog's keys go to: the owner's in the scene, the dialog's own in a window. */
    private Scene keys;
    private final AtomicReference<String> result = new AtomicReference<>("<open>");

    private void open(DisplayMode mode, Dialog d) {
        dialog = d.setDisplayMode(mode).setCancelResult("cancel");
        backend = new HeadlessBackend(runtime);
        HeadlessWindow owner = backend.open("Orders", 600, 400);
        Scene host = new Scene(new Label("Orders"), clock::get);
        host.setTextRuler(RULER);
        host.bind(owner);
        owner.frame();
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
                if (w.scene() != null) {
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

    private String focusedCaption() {
        Widget<?> focused = keys.focusedWidget();
        return focused instanceof Button b ? b.text() : String.valueOf(focused);
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

    // ------------------------------------------------------------- the first focus, no content

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void theSafeAnswerAddedFirstTakesTheFocusAndReturnGivesIt(DisplayMode mode) {
        open(mode, new Dialog("Delete order?", "This cannot be undone.")
                .addPrimaryButton("Keep", "keep")
                .addDestructiveButton("Delete", "delete"));
        assertEquals("Keep", focusedCaption());
        press(Keys.ENTER);
        assertEquals("keep", result.get());
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void aDestructiveButtonAddedAheadOfTheDefaultDoesNotTakeTheFirstFocus(DisplayMode mode) {
        // The audit's case: Delete first in the row, Keep the primary after it. The dialog
        // focused its first button and Return, consumed by the focused Delete, deleted.
        open(mode, new Dialog("Delete order?", "This cannot be undone.")
                .addDestructiveButton("Delete", "delete")
                .addPrimaryButton("Keep", "keep"));
        assertEquals("Keep", focusedCaption(), "the default button takes the first focus");
        press(Keys.ENTER);
        assertEquals("keep", result.get(), "Return does not delete");
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void withNoDefaultTheFirstButtonThatDestroysNothingTakesTheFocus(DisplayMode mode) {
        open(mode, new Dialog("Delete order?", "This cannot be undone.")
                .addDestructiveButton("Delete", "delete")
                .addButton("Cancel", "cancelled"));
        assertEquals("Cancel", focusedCaption());
        press(Keys.ENTER);
        assertEquals("cancelled", result.get(), "Return presses the focused Cancel, not Delete");
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void theDefaultButtonTakesTheFocusAheadOfASecondaryOneBeforeIt(DisplayMode mode) {
        // Cancel, Delete, Save: the row's first stop is a safe button, and still the dialog's
        // first focus is its default, so that Return answers what the filled button says.
        open(mode, new Dialog("Save changes?", "Unsaved changes are lost.")
                .addButton("Cancel", "cancelled")
                .addDestructiveButton("Discard", "discard")
                .addPrimaryButton("Save", "save"));
        assertEquals("Save", focusedCaption());
        press(Keys.ENTER);
        assertEquals("save", result.get());
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void aDialogWhoseOnlyAnswerIsDestructiveIgnoresReturnAndEscapeStillCancels(DisplayMode mode) {
        open(mode, new Dialog("Discard draft?", "It is not saved.")
                .addDestructiveButton("Discard", "discard"));
        // The one button holds the focus, so the keyboard and a reader are on the dialog's answer;
        // Return is refused by the button itself, and the card has no default to give.
        assertEquals("Discard", focusedCaption());
        press(Keys.ENTER);
        assertEquals("<open>", result.get(), "Return does not delete");
        press(Keys.ESCAPE);
        assertEquals("cancel", result.get());
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void severalDestructiveButtonsAloneAllIgnoreReturn(DisplayMode mode) {
        open(mode, new Dialog("Remove account?", "Choose what goes.")
                .addDestructiveButton("Remove files", "files")
                .addDestructiveButton("Remove all", "all"));
        assertEquals("Remove files", focusedCaption());
        press(Keys.ENTER);
        drive(keys).keyEvent(Keys.TAB, true, false, 0);
        drive(keys).keyEvent(Keys.TAB, false, false, 0);
        drive(keys).inputBatchEnded();
        assertEquals("Remove all", focusedCaption());
        press(Keys.ENTER);
        assertEquals("<open>", result.get(), "neither destructive button answers Return");
    }

    // ------------------------------------------------------------ with a field in the body

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void aFieldInTheBodyKeepsTheFirstFocusAndReturnGivesTheDefault(DisplayMode mode) {
        open(mode, new Dialog("Delete order?", "Type the order number to confirm.")
                .setContent(new TextField())
                .addDestructiveButton("Delete", "delete")
                .addPrimaryButton("Keep", "keep"));
        assertTrue(keys.focusedWidget() instanceof TextField, "the body's field is focused first");
        press(Keys.ENTER);
        assertEquals("keep", result.get());
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void aFieldInTheBodyOfADestructiveOnlyDialogLeavesReturnUnanswered(DisplayMode mode) {
        open(mode, new Dialog("Discard draft?", "Type a reason.")
                .setContent(new TextField())
                .addDestructiveButton("Discard", "discard"));
        assertTrue(keys.focusedWidget() instanceof TextField, "the body's field is focused first");
        press(Keys.ENTER);
        assertEquals("<open>", result.get());
        press(Keys.ESCAPE);
        assertEquals("cancel", result.get());
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void aBodyWithNothingFocusableStillLeavesTheFocusToTheDefault(DisplayMode mode) {
        // Content that takes no focus is no reason to fall back to the row's first button.
        Column body = new Column();
        body.add(new Label("Order 1042"));
        open(mode, new Dialog("Delete order?", "This cannot be undone.")
                .setContent(body)
                .addDestructiveButton("Delete", "delete")
                .addPrimaryButton("Keep", "keep"));
        assertEquals("Keep", focusedCaption());
        press(Keys.ENTER);
        assertEquals("keep", result.get());
    }

    // ------------------------------------------------- a destructive button the user focused

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void returnOnAFocusedDestructiveButtonGivesTheDefaultAnswer(DisplayMode mode) {
        open(mode, new Dialog("Delete order?", "This cannot be undone.")
                .addPrimaryButton("Keep", "keep")
                .addDestructiveButton("Delete", "delete"));
        keys.requestFocus(button("Delete"));
        assertEquals("Delete", focusedCaption());
        press(Keys.ENTER);
        assertEquals("keep", result.get(),
                "Return means the default button whatever holds the focus, never the delete");
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void spaceOnAFocusedDestructiveButtonPressesIt(DisplayMode mode) {
        open(mode, new Dialog("Delete order?", "This cannot be undone.")
                .addDestructiveButton("Delete", "delete")
                .addPrimaryButton("Keep", "keep"));
        keys.requestFocus(button("Delete"));
        press(Keys.SPACE);
        assertEquals("delete", result.get(), "Space is the deliberate press of the focused button");
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void spacePressesTheOnlyDestructiveButton(DisplayMode mode) {
        open(mode, new Dialog("Discard draft?", "It is not saved.")
                .addDestructiveButton("Discard", "discard"));
        press(Keys.SPACE);
        assertEquals("discard", result.get());
    }

    @ParameterizedTest
    @EnumSource(value = DisplayMode.class)
    void aReadersPressOnTheDestructiveButtonStillPressesIt(DisplayMode mode) {
        open(mode, new Dialog("Discard draft?", "It is not saved.")
                .addDestructiveButton("Discard", "discard"));
        assertTrue(button("Discard").onAccessibilityAction(Accessible.Action.PRESS,
                Accessible.Argument.NONE), "the press is offered and performed");
        settle();
        assertEquals("discard", result.get());
    }

    // --------------------------------------------------------------- outside a dialog

    @Test
    void aDestructiveButtonOutsideADialogStillAnswersReturn() {
        // The refusal is the dialog's, which has a default answer for Return to mean; a lone
        // "Delete" on a tool bar is pressed by Return as every other focused button is.
        AtomicInteger pressed = new AtomicInteger();
        Button delete = new Button("Delete").setDestructive(true);
        delete.onAction(pressed::incrementAndGet);
        Column root = new Column();
        root.add(delete);
        Scene scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.layoutPass(300, 100);
        scene.requestFocus(delete);
        drive(scene).keyEvent(Keys.ENTER, true, false, 0);
        drive(scene).keyEvent(Keys.ENTER, false, false, 0);
        drive(scene).inputBatchEnded();
        assertEquals(1, pressed.get());
    }
}
