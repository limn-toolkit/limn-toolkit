package limn.components;

import limn.graphics.Color;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialog resolution logic driven headlessly through its panel
 * ({@link Dialog#contentRoot()}): buttons and ESC resolve the
 * {@link java.util.concurrent.CompletionStage} without blocking. The window
 * modality itself is covered by {@link limn.backend.lwjgl.ModalStack} tests.
 *
 * <p>Plus the size-step contract: a dialog is a {@link ControlSize} <em>scope</em>, so its
 * own three gutters and its {@code dialogMaxWidth} cap have to move with the step, the part
 * that used to be baked into the constructor, where no step exists yet.
 */
class DialogTest extends ComponentTestBase {

    /**
     * {@link ComponentTestBase#RULER} is font-blind: every line is 12pt tall whatever the
     * font size, so a step change moves the dialog's box only through spacing and control
     * height. That is exactly what these tests are about; the type ramp is Label's business.
     */
    private static final float LINE = 12;

    private Dialog dialog;
    private Scene scene;

    private void build() {
        build(null);
    }

    /** @param step declared on the card, or {@code null} to inherit (the process default) */
    private void build(ControlSize step) {
        dialog = new Dialog("Confirm", "Proceed with the test action?")
                .addButton("Cancelar", "cancel")
                .addPrimaryButton("OK", "ok")
                .setCancelResult("cancel");
        if (step != null) {
            dialog.setControlSize(step);
        }
        scene = new Scene(dialog.contentRoot());
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
    }

    @Test
    void resultResolvesWithTheActivatedButton() {
        build();
        AtomicReference<String> result = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();
        dialog.result().thenAccept(r -> {
            result.set(r);
            done.set(true);
        });
        assertFalse(done.get(), "not resolved until a button is activated");

        scene.focusTraverse(false); // focus the first button (Cancelar)
        scene.keyEvent(Keys.TAB, true, false, 0); // move to OK
        scene.inputBatchEnded();
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.keyEvent(Keys.ENTER, false, false, 0);
        scene.inputBatchEnded();

        assertTrue(done.get());
        assertEquals("ok", result.get());
    }

    @Test
    void escResolvesWithTheCancelResult() {
        build();
        AtomicReference<String> result = new AtomicReference<>("<open>");
        dialog.result().thenAccept(result::set);
        scene.keyEvent(Keys.ESCAPE, true, false, 0);
        scene.keyEvent(Keys.ESCAPE, false, false, 0);
        scene.inputBatchEnded();
        assertEquals("cancel", result.get());
    }

    @Test
    void returnPressesTheDefaultButtonWhateverHoldsTheFocus() {
        // Every desktop platform answers a dialog with Return. Here it used to do nothing at all
        // unless a button happened to hold the focus, which made a dialog whose body carries a
        // text field unanswerable from the keyboard: focus lands on the field, the field ignores
        // Return, and the card knew only ESC.
        dialog = new Dialog("Rename", "New name:")
                .setContent(new TextField())
                .addButton("Cancel", "cancel")
                .addPrimaryButton("Rename", "rename")
                .setCancelResult("cancel");
        scene = new Scene(dialog.contentRoot());
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        AtomicReference<String> result = new AtomicReference<>("<open>");
        dialog.result().thenAccept(result::set);

        scene.focusTraverse(false);
        assertTrue(scene.focusedWidget() instanceof TextField,
                "sanity: the body's field is focused first, ahead of the buttons");
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.keyEvent(Keys.ENTER, false, false, 0);
        scene.inputBatchEnded();

        assertEquals("rename", result.get());
    }

    @Test
    void aFocusedButtonKeepsReturnForItself() {
        // The ordering is the design: the card answers Return only once nothing inside it wanted
        // the key. A focused Cancel is still Cancel, which is what Windows does, and what stops
        // the default button from overriding a deliberate Tab to another one.
        build();
        AtomicReference<String> result = new AtomicReference<>("<open>");
        dialog.result().thenAccept(result::set);

        scene.focusTraverse(false); // the first button: Cancelar
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.keyEvent(Keys.ENTER, false, false, 0);
        scene.inputBatchEnded();

        assertEquals("cancel", result.get());
    }

    @Test
    void aDialogWithNoPrimaryButtonIgnoresReturn() {
        // Nothing to default to, and resolving with the cancel result would make Return a second,
        // silent ESC.
        dialog = new Dialog("Notice", "Something happened.")
                .addButton("Close", "close")
                .setCancelResult("cancel");
        scene = new Scene(dialog.contentRoot());
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        AtomicReference<String> result = new AtomicReference<>("<open>");
        dialog.result().thenAccept(result::set);

        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.keyEvent(Keys.ENTER, false, false, 0);
        scene.inputBatchEnded();

        assertEquals("<open>", result.get());
    }

    @Test
    void aCappedBodyScrollsFromTheKeyboardWithTheFocusInTheFooter() {
        // The action row sits outside the body's scroll view on purpose, so a key pressed with a
        // button focused bubbles past the body rather than through it. Nothing in a capped card's
        // body has to be focusable (it is usually a wall of text), so without the card handing
        // those keys down, the part that was cut off could not be reached without a mouse.
        dialog = new Dialog("Licence", "Read all of it.")
                .setContent(new TallBox(2000))
                .addPrimaryButton("Agree", "agree");
        scene = new Scene(dialog.contentRoot());
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        ScrollView body = firstScrollView(dialog.contentRoot());
        assertNotNull(body, "the body is wrapped in a scroll view");
        assertTrue(body.maxOffsetY() > 0, "sanity: the card is capped and the body overflows");

        scene.focusTraverse(false);
        assertTrue(scene.focusedWidget() instanceof Button, "sanity: the footer holds the focus");
        scene.keyEvent(Keys.PAGE_DOWN, true, false, 0);
        scene.keyEvent(Keys.PAGE_DOWN, false, false, 0);
        scene.inputBatchEnded();

        assertTrue(body.offsetY() > 0, "the body scrolled: " + body.offsetY());
    }

    /** Content taller than any card, so the dialog is capped and its body overflows. */
    private static final class TallBox extends Widget {
        private final float tall;

        TallBox(float tall) {
            this.tall = tall;
        }

        @Override
        protected limn.scene.Size onMeasure(limn.scene.Constraints c) {
            return c.constrain(100, tall);
        }
    }

    private static ScrollView firstScrollView(Widget root) {
        if (root instanceof ScrollView found) {
            return found;
        }
        for (Widget child : root.children()) {
            ScrollView found = firstScrollView(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void pressingThePanelDoesNotResolve() {
        // The panel has no scrim-dismiss; only buttons/ESC close it.
        build();
        AtomicReference<String> result = new AtomicReference<>("<open>");
        dialog.result().thenAccept(result::set);
        // Half the page padding in: bare card, inside no child, at every step. The old fixed
        // (30, 30) actually landed ON the title Label: the assertion still held, because a
        // Label does not consume presses, but it stopped testing what the name says, and the
        // padding it was written against is now a token that moves.
        float grab = SizeTokens.MEDIUM.spacingLarge() / 2;
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, grab, grab);
        scene.mouseButton(Keys.MOUSE_LEFT, false, 0, grab, grab);
        scene.inputBatchEnded();
        assertEquals("<open>", result.get(), "clicking the panel must not resolve the dialog");
    }

    @Test
    void firstButtonReceivesInitialFocus() {
        build();
        scene.focusTraverse(false);
        Widget focused = scene.focusedWidget();
        assertTrue(focused instanceof Button, "a dialog button should hold focus");
    }

    @Test
    void showingASecondTimeThrows() {
        // Regression (code review): re-showing a resolved dialog used to create
        // a modal whose resolve() was latched shut: popModal unreachable, the
        // owner window locked until process death.
        build();
        dialog.show(scene);
        assertThrows(IllegalStateException.class, () -> dialog.show(scene),
                "a dialog is single-use: showing while presented must throw");
        dialog.dismiss("cancel");
        assertThrows(IllegalStateException.class, () -> dialog.showToolkitModal(scene),
                "a dialog is single-use: re-showing after it closed must throw");
    }

    @Test
    void resolvesOnlyOnce() {
        build();
        AtomicReference<String> result = new AtomicReference<>();
        dialog.result().thenAccept(result::set);
        scene.keyEvent(Keys.ESCAPE, true, false, 0);
        scene.keyEvent(Keys.ESCAPE, false, false, 0);
        scene.inputBatchEnded();
        assertEquals("cancel", result.get());
        // A second ESC must not change the already-completed result.
        scene.keyEvent(Keys.ESCAPE, true, false, 0);
        scene.keyEvent(Keys.ESCAPE, false, false, 0);
        scene.inputBatchEnded();
        assertEquals("cancel", result.get());
    }

    @Test
    void inSceneDialogWithoutButtonsStillCancelsOnEsc() {
        // No buttons → nothing focusable → keys land on the overlay root,
        // which must handle ESC just like the native-window mode.
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Aviso", "Mensagem")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setCancelResult("cancel");
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        host.renderFrame(canvas);

        AtomicReference<String> result = new AtomicReference<>();
        inScene.show(host).thenAccept(result::set);
        pump(clock, host, canvas);
        assertNull(host.focusedWidget(), "sanity: nothing to focus in a buttonless dialog");

        host.keyEvent(Keys.ESCAPE, true, false, 0);
        host.keyEvent(Keys.ESCAPE, false, false, 0);
        host.inputBatchEnded();
        pump(clock, host, canvas);
        assertEquals("cancel", result.get());
    }

    @Test
    void inSceneDialogResolvesEvenIfTheWindowClosesMidFade() {
        // The fade-out ticker only advances while frames render; a window
        // destroyed mid-fade must still complete the result future.
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Confirm", "Proceed?")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setCancelResult("cancel");
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        host.renderFrame(canvas);

        AtomicReference<String> result = new AtomicReference<>();
        inScene.show(host).thenAccept(result::set);
        pump(clock, host, canvas); // fade in settles

        host.keyEvent(Keys.ESCAPE, true, false, 0);
        host.keyEvent(Keys.ESCAPE, false, false, 0);
        host.inputBatchEnded(); // fade-out starts; no frames rendered after this
        assertNull(result.get(), "mid-fade: not resolved yet");

        host.windowClosed(); // owner destroyed before the fade finished
        assertEquals("cancel", result.get(), "close must flush the pending resolution");
    }

    @Test
    void anInSceneDialogOverAnOpenModalIsPromotedToAWindow() {
        // An overlay can only be put in front by raising its host, which hides the
        // dialog already floating over it, so this one is presented as a window
        // instead. The developer sees a warning; the user sees both dialogs.
        Scene host = new Scene(new Label("fundo"));
        host.setTextRuler(RULER);
        host.bind(new BlockedWindow());

        Dialog dialog = new Dialog("Unsaved changes", "Save first?")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .addPrimaryButton("Save", "save")
                .setCancelResult("cancel");
        dialog.show(host);

        assertNotNull(dialog.modalWindow(), "should have been promoted to a native window");
    }

    @Test
    void keepInSceneRefusesThePromotion() {
        Scene host = new Scene(new Label("fundo"));
        host.setTextRuler(RULER);
        host.bind(new BlockedWindow());

        Dialog dialog = new Dialog("Unsaved changes", "Save first?")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .keepInScene()
                .addPrimaryButton("Save", "save")
                .setCancelResult("cancel");
        dialog.show(host);

        assertNull(dialog.modalWindow(), "the app vouched for the overlay; leave it alone");
    }

    @Test
    void aDropdownInsideAnInSceneDialogIsAPopupOfTheExemptedWindow() {
        // The premise the backend's modality policy stands on, and the reason a
        // ComboBox in an in-scene dialog used to be dead: the overlay names its host
        // as the window that stays interactive, while the card's own dropdown opens a
        // window owned by that SAME host. Let the two identities drift apart and the
        // exemption stops covering the card's dropdowns: the popup is created, blocked,
        // and never shown, with the combo reporting itself open the whole time.
        BlockedWindow window = new BlockedWindow(false);
        Scene host = new Scene(new Label("background"));
        host.setTextRuler(RULER);
        host.bind(window);

        ComboBox combo = new ComboBox(List.of("One", "Two"));
        new Dialog("Pick one", "")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setContent(combo)
                .show(host);
        combo.open();
        runtime.drain(); // ComboBox defers the window creation by one turn

        assertSame(window, window.sceneModalOwner, "the overlay exempts the window it is drawn in");
        assertNotNull(combo.popupWindow(), "sanity: the dropdown really did open a window");
        assertTrue(window.childPopups.contains(combo.popupWindow()),
                "and that window belongs to the exempted one");
    }

    @Test
    void anInSceneDialogOverAFreeWindowIsLeftAlone() {
        // The promotion is scoped to the one situation that cannot work. With
        // nothing else open, IN_SCENE means IN_SCENE.
        Scene host = new Scene(new Label("fundo"));
        host.setTextRuler(RULER);
        host.bind(new BlockedWindow(false));

        Dialog dialog = new Dialog("Confirm", "Proceed?")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .addPrimaryButton("OK", "ok")
                .setCancelResult("cancel");
        dialog.show(host);

        assertNull(dialog.modalWindow(), "nothing to stack over: no reason to spend a window");
    }

    @Test
    void aDialogNobodyAnsweredStillResolvesWhenItsWindowDies() {
        // Not the mid-fade case above: here NOTHING was chosen and the window is
        // destroyed anyway: an OS close button on a decorated dialog, or the owner
        // taking its popups down with it. Every completion path is driven by a fade,
        // and frames stop at that instant, so the future was simply abandoned and a
        // caller doing its cleanup in thenAccept never ran it.
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Confirm", "Proceed?")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setCancelResult("cancel");
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        host.renderFrame(canvas);

        AtomicReference<String> result = new AtomicReference<>();
        inScene.show(host).thenAccept(result::set);
        pump(clock, host, canvas); // fully open, nothing pressed
        assertNull(result.get(), "still open");

        host.windowClosed();
        assertEquals("cancel", result.get(),
                "an unanswered dialog whose window dies must resolve as cancelled");
    }

    @Test
    void theCloseObserverDoesNotOverrideARealAnswer() {
        // The two flushes must not race: a dialog answered OK and then destroyed
        // mid-fade reports "ok", not the cancel result of the observer that guards
        // the unanswered case.
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Confirm", "Proceed?")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setCancelResult("cancel");
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        host.renderFrame(canvas);

        AtomicReference<String> result = new AtomicReference<>();
        inScene.show(host).thenAccept(result::set);
        pump(clock, host, canvas);

        inScene.dismiss("ok");    // answered; the fade-out has not finished
        host.windowClosed();      // and now the window dies under it
        assertEquals("ok", result.get(), "the answer wins over the cancel fallback");
    }

    @Test
    void inSceneModalFadesInThenResolvesOnEsc() {
        // Internal (in-scene) presentation: pushed as a scene overlay, animated
        // by the scene's clock, driven deterministically here.
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Confirm", "Proceed?")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setCancelResult("cancel");
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        host.renderFrame(canvas);

        AtomicReference<String> result = new AtomicReference<>();
        inScene.show(host).thenAccept(result::set);
        pump(clock, host, canvas); // fade in settles
        assertTrue(inScene.fadeLevel() > 0.9f, "overlay faded in");
        assertNull(result.get(), "still open until ESC/button");

        host.keyEvent(Keys.ESCAPE, true, false, 0);
        host.keyEvent(Keys.ESCAPE, false, false, 0);
        host.inputBatchEnded();
        pump(clock, host, canvas); // fade out settles
        assertEquals("cancel", result.get());
    }

    /** A box whose natural height is settable: content that changes size after it is shown. */
    private static final class GrowingBox extends limn.scene.Widget {
        private float preferred;

        GrowingBox(float preferred) {
            this.preferred = preferred;
        }

        void setPreferredHeight(float value) {
            preferred = value;
            markNeedsLayout();
        }

        @Override protected limn.scene.Size onMeasure(limn.scene.Constraints c) {
            return c.constrain(120, preferred);
        }

        @Override protected void onPaint(limn.graphics.Canvas canvas) { }
    }

    @Test
    void aNativeDialogResizesItsWindowWhenItsContentGrows() {
        // The colour picker's CMYK tab has a fourth channel row where RGB has three,
        // and the row fell outside the window: it was measured once, at presentation,
        // and never again. The in-scene card never had the bug (its overlay
        // re-measures every layout pass), so this is the native path catching up.
        BlockedWindow owner = new BlockedWindow(false);
        Scene host = new Scene(new Label("fundo"));
        host.setTextRuler(RULER);
        host.bind(owner);

        GrowingBox content = new GrowingBox(100);
        Dialog dialog = new Dialog("Picker", "").setContent(content).addPrimaryButton("OK", "ok");
        dialog.show(host);

        limn.backend.NativeWindow window = dialog.modalWindow();
        assertNotNull(window, "sanity: presented as a native window");
        float openedAt = window.logicalHeight();

        content.setPreferredHeight(180);
        dialog.contentRoot().scene().renderFrame(new FakeCanvas(400, 300));
        runtime.drain(); // the resize is posted: it must not re-enter the layout pass

        assertTrue(window.logicalHeight() >= openedAt + 80,
                "the window did not grow with its content: " + openedAt
                        + " → " + window.logicalHeight());
    }

    @Test
    void aNativeDialogTallerThanTheWorkAreaIsCappedToItAndItsBodyScrolls() {
        // A card sized to its content is a card whose buttons can end up behind the dock. The
        // bound is the DISPLAY's work area, not the owner window: a dialog is a window of its
        // own and may legitimately be larger than the one that opened it.
        BlockedWindow owner = new BlockedWindow(false);
        owner.display = new FakeDisplay(600, 200);
        Scene host = new Scene(new Label("fundo"));
        host.setTextRuler(RULER);
        host.bind(owner);

        Dialog dialog = new Dialog("Picker", "")
                .setContent(new GrowingBox(4000))
                .addPrimaryButton("OK", "ok");
        dialog.show(host);

        float budget = 200 - 2 * SizeTokens.MEDIUM.spacingLarge();
        assertTrue(dialog.modalWindow().logicalHeight() <= budget + 0.001f,
                "4000pt of content should have been capped to the work area, not sized to itself: "
                        + dialog.modalWindow().logicalHeight() + " > " + budget);

        dialog.contentRoot().scene().renderFrame(new FakeCanvas(600, 200));
        ScrollView scroll = find(dialog.contentRoot(), ScrollView.class);
        assertNotNull(scroll, "the body belongs inside a scroll view");
        assertTrue(find(scroll, TokenColumn.class).height() > scroll.viewportHeight(),
                "the body should overflow its viewport: a cap that does not scroll just clips");
    }

    @Test
    void aNativeDialogThatFitsIsNeitherCappedNorScrolled() {
        // The other half of the contract, and the one a regression would land on: a dialog that
        // fits must measure exactly as it did before there was a scroll view in it at all.
        BlockedWindow owner = new BlockedWindow(false);
        owner.display = new FakeDisplay(1600, 1200);
        Scene host = new Scene(new Label("fundo"));
        host.setTextRuler(RULER);
        host.bind(owner);

        Dialog dialog = new Dialog("Confirm", "Proceed?").addPrimaryButton("OK", "ok");
        dialog.show(host);
        dialog.contentRoot().scene().renderFrame(new FakeCanvas(1600, 1200));

        ScrollView scroll = find(dialog.contentRoot(), ScrollView.class);
        assertEquals(find(scroll, TokenColumn.class).height(), scroll.viewportHeight(), 0.001f,
                "a body that fits should be exactly its viewport, i.e. have nothing to scroll");
    }

    @Test
    void theButtonsAreOutsideTheScrollingHalf() {
        // A card capped to the work area with its footer inside the viewport is a card whose
        // only way out can be scrolled off the bottom, and ESC is not something every user tries.
        Dialog dialog = new Dialog("Picker", "").addPrimaryButton("OK", "ok");
        ScrollView scroll = find(dialog.contentRoot(), ScrollView.class);
        assertNotNull(scroll, "the body belongs inside a scroll view");
        assertTrue(collect(scroll, Button.class).isEmpty(),
                "the action row must not be inside the part that scrolls");
        assertEquals(1, collect(dialog.contentRoot(), Button.class).size(),
                "sanity: the button is still in the card, just not in the scrolling half");
    }

    @Test
    void anInSceneDialogTallerThanItsWindowIsCappedToTheWindow() {
        // In-scene the bound is the window that created it, because an overlay cannot leave it,
        // and it keeps the same margin the width has always had, so a capped card reads as
        // capped rather than as clipped by the edge of the scene.
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Picker", "")
                .setContent(new GrowingBox(4000))
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE);
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(500, 400);
        host.renderFrame(canvas);
        inScene.show(host);
        pump(clock, host, canvas);

        float budget = 400 - 2 * SizeTokens.MEDIUM.spacingLarge();
        assertTrue(inScene.contentRoot().height() <= budget + 0.001f,
                "an in-scene card must fit its host window less the margin: "
                        + inScene.contentRoot().height() + " > " + budget);
        assertTrue(inScene.contentRoot().y() >= SizeTokens.MEDIUM.spacingLarge() - 0.001f,
                "the card should sit inside the margin, not against the top edge");
    }

    @Test
    void aNativeDialogWhoseContentIsUnchangedNeverResizesItsWindow() {
        // The guard that stops the refit looping: resizing re-enters layout, so a
        // pass that finds the same size must post nothing at all.
        BlockedWindow owner = new BlockedWindow(false);
        Scene host = new Scene(new Label("fundo"));
        host.setTextRuler(RULER);
        host.bind(owner);

        Dialog dialog = new Dialog("Steady", "nothing moves").addPrimaryButton("OK", "ok");
        dialog.show(host);
        BlockedWindow window = (BlockedWindow) dialog.modalWindow();
        int afterPresent = window.resizes;

        for (int i = 0; i < 3; i++) {
            dialog.contentRoot().scene().renderFrame(new FakeCanvas(400, 300));
            runtime.drain();
        }
        assertEquals(afterPresent, window.resizes, "a steady dialog kept resizing itself");
    }

    @Test
    void nonModalIsRejectedInSceneMode() {
        Dialog inScene = new Dialog("T", "M")
                .addButton("x", "x")
                .setDisplayMode(DisplayMode.IN_SCENE);
        Scene host = new Scene(new Label("fundo"));
        host.setTextRuler(RULER);
        assertThrows(IllegalStateException.class, () -> inScene.showNonModal(host));
    }

    @Test
    void inSceneCardIsDraggable() {
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Drag", "dialog body")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE);
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        host.renderFrame(canvas);
        inScene.show(host);
        pump(clock, host, canvas); // settle fade-in + layout

        Widget card = inScene.contentRoot();
        float x0 = card.x();
        float y0 = card.y();
        // Grab half the page padding in from the card's top-left: bare card at every step,
        // never a child: the literal +10 this replaces sat on the title Label, not the card.
        float grab = SizeTokens.MEDIUM.spacingLarge() / 2;
        float px = x0 + grab;
        float py = y0 + grab;
        host.mouseButton(Keys.MOUSE_LEFT, true, 0, px, py);
        host.inputBatchEnded();
        host.mouseMoved(px + 40, py + 25);
        host.inputBatchEnded();
        host.mouseButton(Keys.MOUSE_LEFT, false, 0, px + 40, py + 25);
        host.inputBatchEnded();
        host.renderFrame(canvas); // re-layout applies the drag offset

        assertEquals(x0 + 40, card.x(), 0.5f, "card follows the drag on x");
        assertEquals(y0 + 25, card.y(), 0.5f, "card follows the drag on y");
    }

    @Test
    void inSceneScrimClickIsIgnoredByDefault() {
        // A modal in-scene dialog ignores scrim clicks (feedback beep only),
        // like a native modal; it does NOT dismiss.
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Modal", "body")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setCancelResult("cancel");
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        host.renderFrame(canvas);
        AtomicReference<String> result = new AtomicReference<>("<open>");
        inScene.show(host).thenAccept(result::set);
        pump(clock, host, canvas);

        // Click the scrim (top-left corner, well outside the centered card).
        host.mouseButton(Keys.MOUSE_LEFT, true, 0, 4, 4);
        host.mouseButton(Keys.MOUSE_LEFT, false, 0, 4, 4);
        host.inputBatchEnded();
        pump(clock, host, canvas);
        assertEquals("<open>", result.get(), "modal in-scene dialog ignores scrim clicks");
    }

    @Test
    void inSceneScrimClickDismissesWhenEnabled() {
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Dismissable", "body")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setDismissOnScrim(true)
                .setCancelResult("cancel");
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        host.renderFrame(canvas);
        AtomicReference<String> result = new AtomicReference<>("<open>");
        inScene.show(host).thenAccept(result::set);
        pump(clock, host, canvas);

        host.mouseButton(Keys.MOUSE_LEFT, true, 0, 4, 4);
        host.mouseButton(Keys.MOUSE_LEFT, false, 0, 4, 4);
        host.inputBatchEnded();
        pump(clock, host, canvas); // fade out settles
        assertEquals("cancel", result.get(), "with dismissOnScrim it closes on the scrim");
    }

    /**
     * The in-scene veil is the palette's, alpha and all. A hardcoded one was invisible in
     * every screenshot the toolkit takes, because a black 55% veil is what most palettes
     * would have asked for anyway; the palette that gives it away is one that asks for
     * something else.
     */
    @Test
    void theInSceneVeilIsThePalettes() {
        Theme veiled = Theme.builder("Veiled", true)
                .scrim(Color.rgba(0x123456, 100 / 255f))
                .build();
        Theme before = Theme.current();
        try {
            Theme.setCurrent(veiled);
            AtomicLong clock = new AtomicLong();
            Dialog inScene = new Dialog("Veil", "body")
                    .addPrimaryButton("OK", "ok")
                    .setDisplayMode(DisplayMode.IN_SCENE);
            Scene host = new Scene(new Label("behind"), clock::get);
            host.setTextRuler(RULER);
            FillRecordingCanvas canvas = new FillRecordingCanvas(400, 300);
            host.renderFrame(canvas);
            inScene.show(host);
            pump(clock, host, canvas); // settle the fade-in, so the alpha is the token's own

            canvas.fills.clear();
            host.renderFrame(canvas);
            assertTrue(canvas.fills.contains(veiled.scrim),
                    "the settled veil must be the palette's scrim, was " + canvas.fills);
        } finally {
            Theme.setCurrent(before);
        }
    }

    /** Records every solid fill, so a test can ask which colours a frame actually painted. */
    private static final class FillRecordingCanvas extends FakeCanvas {
        final List<Color> fills = new ArrayList<>();

        FillRecordingCanvas(float width, float height) {
            super(width, height);
        }

        @Override
        public void fillRect(float x, float y, float w, float h, limn.graphics.Paint paint) {
            if (paint instanceof Color color) {
                fills.add(color);
            }
        }
    }


    /**
     * The promotion an IN_SCENE dialog can undergo is now answerable. It used to be announced
     * only in a log line, which no application reads, while the application may have positioned
     * or styled the card believing it was an overlay.
     */
    @Test
    void theDialogSaysWhichModeItActuallyOpenedIn() {
        Dialog inScene = new Dialog("Mode", "body")
                .setDisplayMode(DisplayMode.IN_SCENE);
        assertEquals(DisplayMode.IN_SCENE, inScene.displayMode(),
                "before show it is the requested mode");

        AtomicLong clock = new AtomicLong();
        Scene host = new Scene(new Label("behind"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(400, 300);
        host.renderFrame(canvas);
        inScene.addPrimaryButton("OK", "ok").show(host);
        pump(clock, host, canvas);

        assertEquals(DisplayMode.IN_SCENE, inScene.displayMode(),
                "and afterwards it is what actually happened");
    }

    // ------------------------------------------------------------------ sizing

    @Test
    void titleAsksForTheRoleInsteadOfPinningAFont() {
        // The regression the conversion exists to kill: the constructor used to do
        // setFont(theme.title), and an explicit font beats the step FOREVER: a LARGE dialog
        // rendered a MEDIUM title with no error and no obvious cause. A Dialog is single-use,
        // so there was no second show that could have corrected it either.
        build(ControlSize.LARGE);
        Label title = find(dialog.contentRoot(), Label.class);
        assertNotNull(title, "the card carries a title Label");
        assertSame(SizeTokens.of(ControlSize.LARGE).title(), title.resolvedFont(),
                "the title follows the resolved step");

        build(); // inherited: the process default is MEDIUM under ComponentTestBase
        assertSame(SizeTokens.MEDIUM.title(), find(dialog.contentRoot(), Label.class).resolvedFont(),
                "MEDIUM is unchanged: the same Font instance Theme.title is initialized from");
    }

    @Test
    void panelGuttersFollowTheStep() {
        // padding + title + gap + message + gap + action row, all three gutters resolved.
        // Derived from the table rather than baked: the box is 2*spacingLarge tall plus a
        // column of one-line Labels (RULER is font-blind) and one control-height button row.
        for (ControlSize step : ControlSize.values()) {
            build(step);
            SizeTokens t = SizeTokens.of(step);
            Size size = dialog.contentRoot().measure(Constraints.loose(400, 300));
            float expected = 2 * t.spacingLarge()
                    + LINE + t.spacingMedium() + LINE + t.spacingMedium() + t.resolvedHeight(LINE);
            assertEquals(expected, size.height(), 0.001f, "card height at " + step);
        }
        // MEDIUM must still be today's pixels: padding 20, gaps 12, a 32pt button row.
        build(ControlSize.MEDIUM);
        assertEquals(120, dialog.contentRoot().measure(Constraints.loose(400, 300)).height(), 0.001f);
    }

    @Test
    void actionRowUsesGapButtonRowNotSpacingSmall() {
        // The two rows agree from MEDIUM up (6) and part ways below it: gapButtonRow is
        // 4 at XSMALL against spacingSmall's 3, because 3pt between two adjacent activatable
        // rects defeats the WCAG 2.2 SC 2.5.8 Spacing exception the dense steps lean on.
        for (ControlSize step : ControlSize.values()) {
            build(step);
            List<Button> buttons = collect(dialog.contentRoot(), Button.class);
            assertEquals(2, buttons.size());
            Button first = buttons.get(0);
            Button second = buttons.get(1);
            SizeTokens t = SizeTokens.of(step);
            assertEquals(t.gapButtonRow(), second.x() - (first.x() + first.width()), 0.001f,
                    "action-row gutter at " + step);
        }
        build(ControlSize.XSMALL);
        List<Button> dense = collect(dialog.contentRoot(), Button.class);
        assertEquals(4, dense.get(1).x() - (dense.get(0).x() + dense.get(0).width()), 0.001f,
                "XSMALL is gapButtonRow 4, NOT spacingSmall 3");
    }

    @Test
    void inSceneCardIsCappedAtDialogMaxWidth() {
        // The measure cap scales to hold a roughly constant character count; that is the
        // entire purpose of a cap, and holding 440 while the body font grew to 19 would leave
        // XLARGE wrapping at ~46 characters.
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            assertEquals(t.dialogMaxWidth(), inSceneCardWidth(step, 900),
                    0.001f, "capped card width at " + step);
        }
    }

    @Test
    void inSceneCardKeepsAScenePaddingMarginWhenTheWindowIsNarrow() {
        // Below the cap the card is inset by spacingLarge on both sides, so the scrim always
        // reads as a margin rather than the card touching the window edge. 300pt keeps the
        // margin binding at every step (the narrowest cap is XSMALL's 320).
        for (ControlSize step : ControlSize.values()) {
            SizeTokens t = SizeTokens.of(step);
            assertEquals(300 - 2 * t.spacingLarge(), inSceneCardWidth(step, 300),
                    0.001f, "margin-bound card width at " + step);
        }
    }

    /** Presents an in-scene dialog at {@code step} in a {@code sceneWidth}-wide scene. */
    private float inSceneCardWidth(ControlSize step, float sceneWidth) {
        AtomicLong clock = new AtomicLong();
        Dialog inScene = new Dialog("Confirm", "Proceed with the test action?")
                .addPrimaryButton("OK", "ok")
                .setDisplayMode(DisplayMode.IN_SCENE)
                .setControlSize(step);
        Scene host = new Scene(new Label("fundo"), clock::get);
        host.setTextRuler(RULER);
        FakeCanvas canvas = new FakeCanvas(sceneWidth, 600);
        host.renderFrame(canvas);
        inScene.show(host);
        pump(clock, host, canvas);
        return inScene.contentRoot().width();
    }

    /** A monitor whose work area a test chooses: the rectangle a native dialog clamps against. */
    private record FakeDisplay(int workWidth, int workHeight) implements limn.backend.Display {
        @Override public String id() {
            return "fake";
        }

        @Override public String name() {
            return "Fake";
        }

        @Override public boolean isPrimary() {
            return true;
        }

        @Override public limn.backend.Resolution currentResolution() {
            return new limn.backend.Resolution(workWidth, workHeight, 60);
        }

        @Override public List<limn.backend.Resolution> availableResolutions() {
            return List.of(currentResolution());
        }

        @Override public limn.backend.ScreenRect bounds() {
            return new limn.backend.ScreenRect(0, 0, workWidth, workHeight);
        }

        @Override public limn.backend.ScreenRect workArea() {
            return new limn.backend.ScreenRect(0, 0, workWidth, workHeight);
        }

        @Override public float contentScale() {
            return 1;
        }
    }

    /** First widget of {@code type} in document order, or null. */
    private static <T extends Widget> T find(Widget root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        for (Widget child : root.children()) {
            T found = find(child, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Every widget of {@code type}, in document order. */
    private static <T extends Widget> List<T> collect(Widget root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collectInto(root, type, out);
        return out;
    }

    private static <T extends Widget> void collectInto(Widget root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        for (Widget child : root.children()) {
            collectInto(child, type, out);
        }
    }

    /** Advances the clock past the fade duration over several frames. */
    private static void pump(AtomicLong clock, Scene scene, FakeCanvas canvas) {
        for (int i = 0; i < 6; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            scene.renderFrame(canvas);
        }
    }

    /**
     * A window that reports whether a modal is already open over it (the one fact
     * the promotion decision reads), and doubles as its own backend, so a dialog
     * promoted to a native window has something to be created by. Everything else
     * is the smallest answer that keeps a presentation from touching an OS.
     */
    private static final class BlockedWindow implements limn.backend.NativeWindow, limn.backend.Backend {
        private final boolean blocked;
        /** The window an in-scene presentation named as the one it keeps interactive. */
        limn.backend.NativeWindow sceneModalOwner;
        /** Everything registered to close with this window: dropdowns and dialogs alike. */
        final List<limn.backend.NativeWindow> childPopups = new ArrayList<>();
        /** Its own size, so a test can watch a dialog refit its window to its content. */
        private float width = 400;
        private float height = 300;
        int resizes;
        /** The monitor this window sits on; null is the headless default, which caps nothing. */
        limn.backend.Display display;

        @Override public limn.backend.Display display() {
            return display;
        }

        BlockedWindow() {
            this(true);
        }

        BlockedWindow(boolean blocked) {
            this.blocked = blocked;
        }

        BlockedWindow(boolean blocked, float width, float height) {
            this(blocked);
            this.width = width;
            this.height = height;
        }

        @Override public boolean isModalBlocked() {
            return blocked;
        }

        @Override public limn.backend.Backend backend() {
            return this;
        }

        // The promoted dialog's own window: never blocked, so it cannot recurse.
        // Born at the size it was asked for, which is what makes a refit visible.
        @Override public limn.backend.NativeWindow createWindow(limn.backend.WindowConfig config) {
            return new BlockedWindow(false, config.width(), config.height());
        }

        @Override public void pushModal(limn.backend.NativeWindow modal, limn.backend.NativeWindow parent) { }

        @Override public void popModal(limn.backend.NativeWindow modal) { }

        @Override public SceneModalHandle pushSceneModal(limn.backend.NativeWindow owner, boolean toolkitScope) {
            sceneModalOwner = owner;
            return () -> sceneModalOwner = null;
        }

        @Override public void signalModalBlocked() { }

        @Override public limn.concurrent.UiRuntime uiRuntime() {
            return null;
        }

        @Override public void runEventLoop() { }

        @Override public void stop() { }

        /** One method, both interfaces: {@code NativeWindow.close} and {@code Backend.close}. */
        @Override public void close() { }

        @Override public void requestFrame() { }

        @Override public void setFrameCallback(limn.backend.FrameCallback callback) { }

        @Override public void setInput(limn.backend.WindowInput input) { }

        @Override public String title() {
            return "";
        }

        @Override public void setTitle(String value) { }

        @Override public float logicalWidth() {
            return width;
        }

        @Override public float logicalHeight() {
            return height;
        }

        @Override public int framebufferWidth() {
            return Math.round(width);
        }

        @Override public int framebufferHeight() {
            return Math.round(height);
        }

        @Override public float contentScale() {
            return 1;
        }

        @Override public void overrideContentScale(float scale) { }

        @Override public void setSize(int newWidth, int newHeight) {
            this.width = newWidth;
            this.height = newHeight;
            resizes++;
        }

        @Override public void show() { }

        @Override public void hide() { }

        @Override public void focus() { }

        @Override public boolean isVisible() {
            return false; // no fade-in to drive, and no window to actually map
        }

        @Override public void setOpacity(float value) { }

        @Override public void setCursor(limn.backend.Cursor value) { }

        @Override public boolean isClosed() {
            return false;
        }

        @Override public void requestClose() { }

        @Override public void enterFullscreen(int width, int height, int refreshRate) { }

        @Override public void exitFullscreen() { }

        @Override public boolean isFullscreen() {
            return false;
        }

        @Override public void registerChildPopup(limn.backend.NativeWindow child, PopupKind kind) {
            childPopups.add(child);
        }

        @Override public void unregisterChildPopup(limn.backend.NativeWindow child) {
            childPopups.remove(child);
        }

        @Override public limn.backend.Clipboard clipboard() {
            return null;
        }

        @Override public int screenX() {
            return 0;
        }

        @Override public int screenY() {
            return 0;
        }

        @Override public void setScreenPosition(int x, int y) { }

        @Override public float logicalToScreenFactor() {
            return 1;
        }

        @Override public void captureNextFrame(java.util.function.Consumer<limn.graphics.Image> sink) { }

        @Override public void setContentScaleListener(ContentScaleListener listener) { }
    }
}
