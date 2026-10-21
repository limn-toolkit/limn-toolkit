package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.backend.Backend;
import limn.backend.Display;
import limn.backend.NativeWindow;
import limn.backend.ScreenRect;
import limn.backend.WindowConfig;
import limn.backend.spi.WindowInput;
import limn.backend.WindowStyle;
import limn.concurrent.Ui;
import limn.concurrent.Subscription;
import limn.internal.lang.Checks;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Insets;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.internal.SceneAccess;
import limn.scene.layout.Flex;
import limn.scene.layout.Opacity;
import limn.scene.layout.Transform;
import limn.scene.layout.Row;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.concurrent.CompletionStage;
import limn.backend.spi.WindowPeer;
import limn.backend.spi.BackendPeer;

/**
 * A modal (or non-modal) dialog that resolves a {@link CompletionStage} with
 * the chosen button's result: never a nested event loop, so {@code show*}
 * returns immediately and the stage completes (on the UI thread) later.
 *
 * <p>Two <b>display modes</b> ({@link #setDisplayMode}):
 * <ul>
 *   <li>{@link DisplayMode#NATIVE_WINDOW} (default): a separate floating
 *       native window centered over the owner, with a {@link WindowStyle} and
 *       optional {@linkplain #setAlwaysOnTop always-on-top}. Modality is
 *       enforced by the backend (locked windows dim and ignore input). Not
 *       available to a modal dialog over a fullscreen owner; see below.</li>
 *   <li>{@link DisplayMode#IN_SCENE}: an <b>internal</b> overlay drawn inside
 *       the owner window: a scrim that dims/blocks the content behind plus a
 *       centered card that fades in. No extra OS window; modality is scoped to
 *       that scene.</li>
 * </ul>
 *
 * <p>Three modality scopes (native window mode):
 * <ul>
 *   <li>{@link #show(Scene)} is <b>window-modal</b>: locks only the owner window
 *       (and its owned popups); other windows stay interactive.</li>
 *   <li>{@link #showToolkitModal(Scene)} is <b>toolkit-modal</b>: locks every
 *       window in the application.</li>
 *   <li>{@link #showNonModal(Scene)} is <b>non-modal</b>: locks nothing; a
 *       floating panel alongside a fully interactive owner.</li>
 * </ul>
 * In {@code IN_SCENE} mode the overlay always captures its own scene's input;
 * the owner stays interactive (it hosts the overlay) but its <em>sibling</em>
 * windows are locked like a native modal: {@code show} locks the owner's owned
 * popups, {@code showToolkitModal} locks every window. {@code showNonModal} is
 * unsupported in-scene (use a native window for a non-modal dialog).
 *
 * <p><b>Over a fullscreen owner a modal dialog is drawn in scene</b>, whatever was asked, for the
 * reasons {@link DisplayMode} gives: an owner fullscreen at a named mode is minimized as soon as
 * the dialog's window takes the focus, which it does on opening; one at the mode the display is in
 * already covers the screen, so the card loses nothing drawn inside it; and on a Mac without a GPU
 * the colour picker's dialog held the UI for 1.4 to 3.7 seconds as a window, against 0.6 to 0.7 in
 * scene. Its scope is kept: {@code showToolkitModal} still locks every other window. A
 * <b>non-modal</b> dialog is the exception, and stays a window even there: an overlay captures its
 * window's input, and a non-modal dialog exists so that the owner stays usable, which leaves it no
 * in-scene form to fall back to. A window that cannot be positioned (Wayland) changes nothing: a
 * dialog has no anchor to line up with, so the compositor placing it is where it belongs.
 * {@link #displayMode()} answers what was chosen.
 *
 * <p>The same holds for a modal dialog already open as a window when its owner goes fullscreen:
 * it moves into the scene then, card, contents, focus and scope, and is still waiting for its
 * answer; the owner going fullscreen answers nothing. It is left a window only while another
 * dialog or a modal menu is open over it, since moving it would close the window that one belongs
 * to, and nothing moves back into a window when the owner leaves fullscreen.
 *
 * <h2>Stacking</h2>
 * Dialogs stack in either mode and in any combination: the newest is the one the user
 * answers, everything under it is frozen until it closes, and answering the top one
 * hands control back to the one below.
 *
 * <p>One exception, and it changes the display mode: an {@code IN_SCENE} dialog raised
 * over a window that a modal has <b>already</b> locked is presented as a native window
 * instead, with a warning naming the dialog. An overlay is drawn inside its host window,
 * so it can only come forward by bringing that window forward, which would hide the
 * dialog already floating over it. {@link #keepInScene()} declines the promotion, and a
 * fullscreen owner never makes it, since a window there does not stack over anything.
 *
 * <h2>Asking before it is cancelled</h2>
 * A dialog the user closes with nothing chosen, by Escape, a click on the scrim, a button whose
 * result is the {@linkplain #setCancelResult cancel result}, the system closing its own window or a
 * screen reader's dismiss, asks its {@link #onCancel} handler first when it has one. The handler
 * answers now or later, so a form with unsaved changes can ask "Save changes?" over itself and close
 * only once that is answered. Every other answer closes it at once, and so does {@link #dismiss}.
 *
 * <h2>Size</h2>
 * A dialog is a container of sized components rather than a sized component: it has no
 * size knob of its own, it <em>is</em> a {@link ControlSize} scope that its content
 * inherits. Its three gutters and its {@code dialogMaxWidth} measure cap all follow the
 * resolved step.
 *
 * <p>Because every {@code show*} takes a {@link Scene}, the step is inherited from the
 * <b>owner scene's root</b>, <em>not</em> from the control that opened the dialog, so a
 * dialog raised from a SMALL toolbar inside a MEDIUM scene renders MEDIUM. Declare it
 * with {@link #setControlSize}, or pass the opening widget to {@link #show(Widget)}, to
 * get anything else.
 *
 * <p>A dialog is also <b>bounded</b>, and never grows past what it can be seen in: a native
 * window is capped at the work area of the display it opens on (the monitor less the OS
 * chrome), and an in-scene overlay at the window hosting it, both inset by one gutter so a
 * capped card reads as capped rather than as clipped. Content that does not fit scrolls, and
 * the button row is deliberately outside the scrolling part, so the way out of a dialog can
 * never be scrolled away. A dialog that fits is unaffected: it is exactly as tall as it asks
 * to be, and has nothing to scroll.
 *
 * <p>A {@code Dialog} is <b>single-use</b>: it presents once and resolves once.
 * Calling any {@code show*} method a second time (including after the dialog
 * closed) throws {@link IllegalStateException}; build a new instance per
 * presentation.
 *
 * <pre>{@code
 * new Dialog("Delete?", "This action cannot be undone.")
 *     .addPrimaryButton("Keep", "cancel")
 *     .addDestructiveButton("Delete", "delete")
 *     .show(scene)
 *     .thenAccept(result -> { if ("delete".equals(result)) ...; });
 * }</pre>
 */
public final class Dialog {

    /**
     * One question to a dialog's {@link #onCancel} handler: may the dialog close with its cancel
     * result? Answered once, with {@link #allow()} or {@link #refuse()}, now or later: a handler that
     * shows a dialog of its own over this one keeps the request and answers from that dialog's result.
     * Keeping it unanswered is how a handler holds it, and while it is held the dialog is not asked
     * again.
     *
     * <p>{@code allow()} closes the dialog with its cancel result, so it is the answer for "close and
     * lose the changes" only. A different answer, the Save of a "Save changes?" question asked over a
     * form, is given with {@code request.dialog().dismiss(result)}: the dialog closes with that
     * result, which is what the code that showed it acts on, and the request is settled by it.
     *
     * <pre>{@code
     * form.onCancel(request -> question.show(scene).thenAccept(answer -> {
     *     if ("cancel".equals(answer)) {
     *         request.refuse();                  // back to the form, as it was left
     *     } else {
     *         request.dialog().dismiss(answer);  // the form closes with "save" or "discard"
     *     }
     * }));
     * }</pre>
     *
     * <p>A request the dialog settles itself, because it was answered another way (a button that is
     * not the cancel, {@link #dismiss}, its window or its owner going away) while the request was
     * held, is over: its answer, given later, then does nothing, and a second one throws, as on
     * every request. {@link #isAnswered()} says whether {@code allow} or {@code refuse} has been
     * called, as a window's and a tab's request do; whether the dialog has closed is its
     * {@link Dialog#result() result}'s to say.
     *
     * <p>Only Limn implements this interface. A test asks a handler the way the user does, with
     * Escape, a click or a window's close through the headless backend of {@code limn-test}.
     */
    public sealed interface CancelRequest permits Asked {

        /**
         * How the user closed the dialog.
         *
         * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
         * {@code default} branch.
         */
        enum Reason {

            /** Escape. */
            ESCAPE,

            /** A click outside the card, in a dialog that {@linkplain #setDismissOnScrim dismisses on the scrim}. */
            SCRIM,

            /**
             * A button whose result is the cancel result, pressed in any way: a click, Space, Return
             * on it or answered by it as the default button, an assistive technology's press.
             */
            BUTTON,

            /** The system closing the dialog's own window: its close button, Alt+F4, Cmd+W. */
            WINDOW_CLOSE,

            /** An assistive technology dismissing the dialog. */
            ASSISTIVE_TECHNOLOGY
        }

        /** @return the dialog asked about, whose {@link Dialog#dismiss} gives it an answer of the handler's own */
        Dialog dialog();

        /** @return how the user closed the dialog */
        Reason reason();

        /**
         * Closes the dialog with its cancel result, as the user's gesture would have: through the
         * fade, or at once under less motion; nothing, if the dialog closed another way meanwhile.
         * UI thread only.
         *
         * @throws IllegalStateException if the request was already answered with {@code allow} or
         *                               {@code refuse}
         */
        void allow();

        /**
         * Keeps the dialog open, with the keyboard where it was; nothing, if the dialog closed
         * another way meanwhile. UI thread only.
         *
         * @throws IllegalStateException if the request was already answered with {@code allow} or
         *                               {@code refuse}
         */
        void refuse();

        /** @return whether {@link #allow()} or {@link #refuse()} has been called */
        boolean isAnswered();
    }

    private static final System.Logger LOG = System.getLogger(Dialog.class.getName());

    /**
     * Motion choreography, deliberately <b>not</b> a size token: 14pt of travel reads the
     * same regardless of how big the card is, and scaling it would make an XLARGE dialog
     * feel sluggish for the identical animation duration.
     */
    private static final float SLIDE_DISTANCE = 14;

    private final I18nString title;
    /**
     * The message, or {@code null} when the dialog was built without one. Kept beside the label
     * that paints it because the card's node describes itself from it on every frame that damages
     * the card, and a string handed over by reference costs that frame nothing, where one resolved
     * to find out whether it is empty would cost it an allocation.
     */
    private final I18nString message;
    private final DialogPanel panel;
    /** Title, message and the application's widget: the part that scrolls when the card is capped. */
    private final TokenColumn body;
    private final ScrollView bodyScroll;
    /** The card's padding, round the body and the buttons; its sides are room the body paints in. */
    private final TokenPadding cardPadding;
    private final ActionRow buttonRow;
    private Widget<?> custom;
    private final CompletableFuture<String> result = new CompletableFuture<>();

    private DisplayMode displayMode = DisplayMode.NATIVE_WINDOW;
    private WindowStyle style = WindowStyle.UNDECORATED_TRANSLUCENT;
    /** The transparent margin around the card in its own window, for its shadow; zero for none. */
    private int windowMargin;
    private String cancelResult;
    /**
     * What Return resolves the dialog with, and whether there is a button to resolve it: two
     * fields because null is a legitimate result and cannot stand for "no default button".
     */
    private String defaultResult;
    private boolean hasDefaultButton;
    private boolean alwaysOnTop = true;
    private boolean dismissOnScrim; // default false: modal (ignore + beep), like a native modal
    private @Nullable Consumer<CancelRequest> onCancel;
    /** The cancel request asked and not answered yet, or {@code null}. */
    private @Nullable Asked pendingCancel;
    /** How far the dialog has faded in where nothing fades its card: 1 in a window of its own. */
    private float fade;
    /** In the scene the card's opacity is the dialog's fade, which the veil reads and the slide keeps pace with. */
    private boolean cardFades;
    /**
     * The container the card is faded through in the scene, made as the card moves into it; its
     * opacity is the dialog's fade there.
     */
    private @Nullable Opacity cardFade;
    /**
     * The container inside it that slides the card, {@link #SLIDE_DISTANCE} below its place when the
     * fade is at nothing and at its place when it is whole: a picture moved, which neither the
     * layout nor the accessible tree follows frame by frame.
     */
    private @Nullable Transform cardSlide;
    /** Whether the card's fade is moving, so the veil has to be repainted with it. */
    private boolean cardMoving;
    /** Whether the ticker that repaints the veil while the card fades is registered. */
    private boolean veilFollowing;
    private boolean presented; // single-use: any show* after the first throws
    private boolean closing;
    private boolean modal = true;
    private boolean keepInScene; // the app has vouched for IN_SCENE; never promote
    // Unregisters the "died unanswered" observer once a real answer is on its way.
    private Subscription unanswered;
    // While a modal dialog is a window of its own: moves it into the scene if the owner goes
    // fullscreen.
    private Subscription fullscreenWatch;

    /**
     * How long a modal dialog's own window waits, once its card has moved into the owner's scene,
     * for the owner to report the focus it was asked for, before it is sent away regardless.
     */
    static final long OWNER_FOCUS_WAIT_MILLIS = 300;

    // Native-window presentation state (null when in-scene / headless).
    private NativeWindow modalWindow;
    private NativeWindow ownerWindow;
    private Scene modalScene;

    // Where the card's step is inherited from, when the tree cannot say: the widget passed
    // to a show(Widget) overload, else the owner scene's root, followed through setRoot.
    // Installed as a host link on the parentless root of the card's subtree at presentation time.
    private Widget<?> inheritanceHost;

    // In-scene presentation state (null when native-window / headless).
    private Scene hostScene;
    private SceneOverlay overlay;
    private BackendPeer.SceneModalHandle sceneModal; // blocks sibling windows while open
    // Drag offset of the in-scene card from its centered position.
    private float dragOffsetX;
    private float dragOffsetY;

    /**
     * A dialog with fixed text; see the {@link I18nString} constructor for localized text.
     *
     * @param title   the title, drawn on the card and read as the dialog's name; never null
     * @param message the sentence under the title; empty for none, never null
     */
    public Dialog(String title, String message) {
        this(I18nString.literal(Objects.requireNonNull(title, "title")),
                I18nString.literal(Objects.requireNonNull(message, "message")));
    }

    /**
     * A dialog whose title and message follow the UI language; see {@link I18nString}.
     *
     * @param title   the title, drawn on the card and read as the dialog's name; never null
     * @param message the sentence under the title; empty for none, never null
     */
    public Dialog(I18nString title, I18nString message) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
        this.title = title;
        // Resolved once, here, to decide whether there is a message at all; from then on the
        // reference is the fact, and nothing else in the dialog resolves it to ask.
        this.message = message.get().isEmpty() ? null : message;
        // NOTHING here may capture a size: a widget has no parent while it is being built,
        // so a step read now resolves to the process default no matter what the eventual
        // owner declares, and a Dialog is single-use, so there is no re-show that could
        // ever correct it. Hence three self-resolving containers instead of three literals
        // pulled off the theme, and a typographic ROLE on the title instead of a Font
        // (setFont wins over the step forever, which is how a LARGE dialog used to render
        // a MEDIUM title with no error and no obvious cause).
        body = new TokenColumn(Tokens.Role.MEDIUM);
        body.crossAlignment(Flex.CrossAlignment.STRETCH);
        Label heading = new Label(title).setRole(Label.Role.TITLE);
        // The painted title is the rendering of the dialog's own name, the way a title bar's
        // caption is, and the DIALOG node carries that string where every platform reads a
        // dialog's title from. As a node of its own it was the same fact twice: a reader entering
        // the dialog said the name, then read the body and said it again. See DialogPanel's hook.
        heading.setAccessibleIgnored(true);
        body.add(heading);
        if (this.message != null) {
            body.add(new Label(this.message).setWrap(true).setMuted(true));
        }
        buttonRow = new ActionRow();
        buttonRow.mainAlignment(Flex.MainAlignment.END)
                .crossAlignment(Flex.CrossAlignment.CENTER);

        // Vertical only. A card that scrolled sideways would be one whose buttons can be
        // scrolled out of reach of a pointer that never learned there was anything to the
        // right; the width is capped by dialogMaxWidth instead, which cannot hide anything.
        // And since nothing scrolls sideways, the body is not clipped sideways at the viewport:
        // the column below lets it paint into the card's padding on both sides.
        bodyScroll = new ScrollView(body, false, true);

        cardPadding = new TokenPadding(Tokens.Role.LARGE, new CardColumn(bodyScroll, buttonRow));
        panel = new DialogPanel(cardPadding);
    }

    /**
     * Puts a widget between the message and the buttons: a form, a preview, a
     * picker. A dialog is the toolkit's one modal surface, and until now the only
     * thing it could say was a sentence; anything that needs an answer richer than
     * a button had to build its own overlay and re-solve focus, dismissal and the
     * scrim.
     *
     * <p>The widget inherits the card's {@link ControlSize} like everything else in
     * it. Passing {@code null} removes the current one. UI thread only.
     *
     * @param widget the content, or {@code null} to remove it
     * @return this dialog
     */
    public Dialog setContent(@Nullable Widget<?> widget) {
        Ui.checkUiThread();
        if (custom != null) {
            body.remove(custom);
        }
        custom = widget;
        if (widget != null) {
            // Appended, and last in the body, which is where it belongs: the buttons are no
            // longer in this column at all, so there is nothing to re-add behind it.
            body.add(widget);
        }
        return this;
    }

    /**
     * Adds a secondary (outlined) button that resolves the dialog with {@code resultValue}.
     *
     * @param text        the caption; never null
     * @param resultValue what the dialog resolves with when this button is pressed; may be null
     * @return this dialog
     */
    public Dialog addButton(String text, @Nullable String resultValue) {
        Ui.checkUiThread();
        return addButton(I18nString.literal(text), resultValue, false);
    }

    /**
     * A secondary button whose caption follows the UI language.
     *
     * @param text        the caption; never null
     * @param resultValue what the dialog resolves with when this button is pressed; may be null
     * @return this dialog
     */
    public Dialog addButton(I18nString text, @Nullable String resultValue) {
        Ui.checkUiThread();
        return addButton(text, resultValue, false);
    }

    /**
     * Adds a primary (filled) button that resolves the dialog with {@code resultValue}.
     *
     * <p>The first one added is also the card's <b>default button</b>: Return resolves the dialog
     * with its result whenever the focused widget did not want the key itself, so Return in a
     * text field answers the dialog, while Return on a focused button presses that button and
     * Return in a {@code TextArea} still inserts a newline.
     *
     * @param text        the caption; never null
     * @param resultValue what the dialog resolves with when this button is pressed; may be null
     * @return this dialog
     */
    public Dialog addPrimaryButton(String text, @Nullable String resultValue) {
        Ui.checkUiThread();
        return addButton(I18nString.literal(text), resultValue, true);
    }

    /**
     * A primary button whose caption follows the UI language.
     *
     * @param text        the caption; never null
     * @param resultValue what the dialog resolves with when this button is pressed; may be null
     * @return this dialog
     */
    public Dialog addPrimaryButton(I18nString text, @Nullable String resultValue) {
        Ui.checkUiThread();
        return addButton(text, resultValue, true);
    }

    /**
     * Adds a button that destroys something ("Delete", "Discard") and resolves the dialog with
     * {@code resultValue}: a {@linkplain Button.Style#SECONDARY secondary} button with its caption
     * in the palette's danger colour.
     *
     * <p>It is <b>never the default button</b>, whatever was added before it, and Return does
     * not press it, even while it holds the focus: so a person typing into the dialog and
     * pressing Return out of habit does not delete. Return then gives the default button's
     * answer, or none in a dialog without one. Nor does it take the dialog's first focus, which
     * goes to the widget the content asked for as it joined ({@link Scene#requestFocus} from its
     * {@code onAttached}), else to the first control of the content that takes the keyboard (a
     * field, say), or, when
     * the content has none, to the default button, or else to the first button that destroys
     * nothing; only in a dialog whose every button is destructive does the first of them take it,
     * Return still not pressing it and Escape still cancelling. Space, a click and an assistive technology's
     * press do press it. Give the dialog a primary button for the safe answer, which Return then
     * gives.
     *
     * @param text        the caption; never null
     * @param resultValue what the dialog resolves with when this button is pressed; may be null
     * @return this dialog
     */
    public Dialog addDestructiveButton(String text, @Nullable String resultValue) {
        Ui.checkUiThread();
        return addDestructiveButton(I18nString.literal(text), resultValue);
    }

    /**
     * A destructive button whose caption follows the UI language; see
     * {@link #addDestructiveButton(String, String)}.
     *
     * @param text        the caption; never null
     * @param resultValue what the dialog resolves with when this button is pressed; may be null
     * @return this dialog
     */
    public Dialog addDestructiveButton(I18nString text, @Nullable String resultValue) {
        Ui.checkUiThread();
        // Added as a secondary button, and only a primary one can become the default: so this
        // one never answers Return, whatever the order the buttons were added in. And it refuses
        // Return while focused (addButton), so the dialog answers the key as one nothing wanted.
        return addButton(text, resultValue, false, true);
    }

    private Dialog addButton(I18nString text, String resultValue, boolean primary) {
        return addButton(text, resultValue, primary, false);
    }

    private Dialog addButton(I18nString text, String resultValue, boolean primary,
            boolean destructive) {
        Ui.checkUiThread();
        Button button = new Button(text)
                .setStyle(primary ? Button.Style.PRIMARY : Button.Style.SECONDARY)
                .setDestructive(destructive);
        if (destructive) {
            // A focused Button presses itself on Return before the card hears the key, which made
            // a focused "Delete" answer Return (2026-10-19): this one lets the key through.
            button.refuseReturn();
        }
        button.onAction(() -> answer(resultValue));
        // Its press resolves the dialog, and once an answer is on its way out resolve() drops
        // it: so a reader is offered the press, and the press is performed, on the condition
        // the card's CANCEL is (semantics 5, 2026-09-15).
        button.acceptPressWhile(this::answerable);
        buttonRow.add(button);
        if (primary && !hasDefaultButton) {
            // The first primary button is the default one, and its filled style is the whole
            // affordance: every platform draws the button Return activates differently from the
            // rest, and this card already does. The row is told which button that is in the
            // same branch, so what a screen reader hears as the default is never a different
            // button from the one Return answers with.
            hasDefaultButton = true;
            defaultResult = resultValue;
            buttonRow.defaultButton = button;
        }
        return this;
    }

    /**
     * Sets the result delivered on ESC / scrim dismiss (default {@code null}): the answer that means
     * nothing was chosen. A button whose result is this one cancels too, and every way of cancelling
     * asks {@link #onCancel} first when it is set.
     *
     * @param value what Escape or a dismissing scrim click resolves with; may be null
     * @return this dialog
     */
    public Dialog setCancelResult(@Nullable String value) {
        Ui.checkUiThread();
        this.cancelResult = value;
        return this;
    }

    /**
     * Asks the application whether the dialog may close when the user closes it with nothing
     * chosen, which is every way of giving the {@linkplain #setCancelResult cancel result}: Escape, a
     * click on the scrim of a dialog that {@linkplain #setDismissOnScrim dismisses on it}, a button
     * whose result is the cancel result (judged when it is pressed, Return on the default button
     * included), the system closing the dialog's own window, and an assistive technology's dismiss.
     * The handler is given a {@link CancelRequest} it answers now or later; without a handler the
     * dialog closes. An answer other than the cancel result, the Save of a question asked over a
     * form, is given with the request's {@linkplain CancelRequest#dialog() dialog}'s {@link #dismiss},
     * which settles the request. Its other buttons, a destructive one included, are answers and never asked
     * about, and neither is {@link #dismiss}, the application's own close, nor the dialog's window
     * or its owner going away, nor the application ending.
     *
     * <p>The handler is called on the UI thread, during the user's gesture, before anything fades.
     * While its request is unanswered the dialog asks nothing new: a second Escape does nothing. A
     * handler that throws has refused: the dialog stays open, and the exception reaches the crash
     * handler as any exception from application code on the UI thread does.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this dialog
     * @throws IllegalStateException if a handler is already registered
     */
    public Dialog onCancel(@Nullable Consumer<CancelRequest> handler) {
        Ui.checkUiThread();
        this.onCancel = Checks.handlerSlot(onCancel, handler, "Dialog.onCancel");
        return this;
    }

    /** A button's press, or Return answered by the default button: the cancel asks, any other answers. */
    private void answer(@Nullable String value) {
        if (Objects.equals(value, cancelResult)) {
            cancel(CancelRequest.Reason.BUTTON);
        } else {
            resolve(value);
        }
    }

    /**
     * The user closing the dialog with nothing chosen: its handler is asked, or it closes.
     *
     * @return whether the dialog began closing
     */
    private boolean cancel(CancelRequest.Reason reason) {
        if (closing) {
            return false;
        }
        Consumer<CancelRequest> handler = onCancel;
        if (handler == null) {
            resolve(cancelResult);
            return true;
        }
        if (pendingCancel != null) {
            return false; // asked already, and not answered: a second Escape asks nothing new
        }
        Asked asking = new Asked(reason);
        pendingCancel = asking;
        try {
            handler.accept(asking);
        } catch (RuntimeException | Error thrown) {
            if (!asking.isAnswered()) {
                asking.refuse(); // a handler that failed to decide must not lose what it guards
            }
            throw thrown;
        }
        return closing;
    }

    /** The request the cancel handler is given. */
    private final class Asked implements CancelRequest {
        private final Reason reason;
        private boolean answered;
        /** Over: the dialog closed another way while it was held, so its answer decides nothing. */
        private boolean settled;

        Asked(Reason reason) {
            this.reason = reason;
        }

        @Override
        public Dialog dialog() {
            return Dialog.this;
        }

        @Override
        public Reason reason() {
            return reason;
        }

        @Override
        public void allow() {
            if (answer()) {
                resolve(cancelResult);
            }
        }

        @Override
        public void refuse() {
            answer();
        }

        /** @return whether this answer is the one that decides */
        private boolean answer() {
            Ui.checkUiThread();
            if (answered) {
                throw new IllegalStateException("the request was already answered");
            }
            answered = true;
            if (pendingCancel == this) {
                pendingCancel = null;
            }
            return !settled; // settled, the dialog has its answer already: nothing is left to decide
        }

        @Override
        public boolean isAnswered() {
            return answered;
        }
    }

    /** The dialog closed another way: a request still held is over. */
    private void settlePendingCancel() {
        Asked held = pendingCancel;
        if (held != null) {
            pendingCancel = null;
            held.settled = true;
        }
    }

    /**
     * Declares the size step for the whole card, overriding the inherited one: the only
     * size knob a dialog has, and a <em>forwarder</em> rather than an override because a
     * {@code Dialog} is not a {@link Widget}: it lands on the card's root, which is what
     * every control inside the dialog then inherits, in both display modes.
     *
     * <p>{@code null} restores inheritance from the owner scene. UI thread only.
     *
     * @param size the step, or {@code null} to inherit it from the owner
     * @return this dialog
     */
    public Dialog setControlSize(@Nullable ControlSize size) {
        Ui.checkUiThread();
        panel.setControlSize(size);
        return this;
    }

    /** @return the step the card currently resolves to (declared, inherited or default) */
    public ControlSize controlSize() {
        return panel.controlSize();
    }

    /**
     * Selects native-window vs. internal (in-scene) presentation (default
     * {@link DisplayMode#NATIVE_WINDOW}).
     *
     * <p><b>A preference, not a guarantee</b>, in both directions. An {@code IN_SCENE} dialog
     * raised while a modal is already open over its window is presented as a native one instead;
     * an overlay can only be brought to the front by raising its host, which would hide the dialog
     * already there. And a modal {@code NATIVE_WINDOW} dialog over a fullscreen window is drawn in
     * scene, for the reasons {@link DisplayMode} gives, and one already a window moves into the
     * scene when its owner goes fullscreen; a non-modal one
     * stays a window, having no in-scene form. {@link #displayMode()} answers what actually
     * happened; {@code keepInScene()} insists on the overlay.
     *
     * @param mode the presentation asked for
     * @return this dialog
     */
    public Dialog setDisplayMode(DisplayMode mode) {
        Ui.checkUiThread();
        this.displayMode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * How this dialog is <em>actually</em> presented: the requested mode until {@link #show}
     * decides otherwise, and the effective one afterwards, which changes once more, to
     * {@link DisplayMode#IN_SCENE}, if a modal dialog's owner goes fullscreen while it is a window.
     *
     * <p>Worth asking after {@code show} by anything that styles or positions the card on the
     * assumption that it is one or the other: a promoted dialog is a real window, so
     * {@code setStyle} starts meaning something and an in-scene drag affordance stops, and a modal
     * dialog raised over a fullscreen window is an overlay whatever was asked, so the reverse.
     * Until this existed the promotion was announced only in a log line, which no application
     * reads.
     */
    public DisplayMode displayMode() {
        return displayMode;
    }

    /**
     * Insists on {@link DisplayMode#IN_SCENE} even when a modal window is already
     * open over the host, the case this dialog would otherwise be
     * {@linkplain #setDisplayMode promoted} out of, silently and with a warning.
     *
     * <p>Say this when you have looked at the stacking and are content with it: the
     * overlay will be answerable, but bringing its host window forward hides any
     * dialog floating over it, so the earlier one appears to vanish until this one
     * closes. The honest reason to accept that is not needing an extra OS window at
     * all: an embedded or kiosk surface, or a host that cannot create one.
     *
     * <p>No boolean: promotion is the default and this is an assertion, not a
     * property. {@code keepInScene(false)} would be a way to write "promote me,
     * probably", which is just the default spelled less clearly.
     */
    public Dialog keepInScene() {
        this.keepInScene = true;
        return this;
    }

    /**
     * Whether the native dialog window floats above other windows
     * (default {@code true}). Only applies to {@link DisplayMode#NATIVE_WINDOW}.
     *
     * @param value whether the window floats above others
     * @return this dialog
     */
    public Dialog setAlwaysOnTop(boolean value) {
        Ui.checkUiThread();
        this.alwaysOnTop = value;
        return this;
    }

    /**
     * Whether clicking the scrim dismisses the dialog (default {@code false}).
     * Only applies to {@link DisplayMode#IN_SCENE}. When {@code false} the dialog
     * is truly modal: a scrim click is ignored and answered with the same alert
     * feedback (beep) a native modal gives; when {@code true} it closes with the
     * {@linkplain #setCancelResult cancel result} (a dismissable overlay), asking
     * {@link #onCancel} first when it is set.
     *
     * @param value whether a scrim click dismisses
     * @return this dialog
     */
    public Dialog setDismissOnScrim(boolean value) {
        Ui.checkUiThread();
        this.dismissOnScrim = value;
        return this;
    }

    /**
     * Sets the native window framing (default {@link WindowStyle#UNDECORATED_TRANSLUCENT}):
     * {@code DECORATED} uses the OS title bar/border (opaque);
     * {@code UNDECORATED_OPAQUE} is a borderless solid card; the default is a
     * borderless glassy panel with rounded, see-through corners. Ignored in
     * {@link DisplayMode#IN_SCENE} (the internal card is always the glassy panel).
     *
     * @param newStyle the framing
     * @return this dialog
     */
    public Dialog setStyle(WindowStyle newStyle) {
        Ui.checkUiThread();
        this.style = Objects.requireNonNull(newStyle, "newStyle");
        return this;
    }

    /**
     * Programmatically closes the dialog, resolving with {@code result}. UI thread only.
     *
     * <p>Never asks the {@link #onCancel} handler, whatever the result, and settles a request that
     * handler holds: it is how a handler gives an answer other than the cancel result.
     *
     * @param result what the dialog resolves with; may be null
     */
    public void dismiss(@Nullable String result) {
        Ui.checkUiThread();
        resolve(result);
    }

    /** @return the stage that completes when the dialog closes (same as {@link #show}) */
    public CompletionStage<@Nullable String> result() {
        return result;
    }

    /** @return the current fade level in [0..1] (tests/screenshots) */
    public float fadeLevel() {
        Opacity fading = cardFade;
        return cardFades && fading != null ? fading.opacity() : fade;
    }

    /**
     * @return the modal's native window while open (screenshots/tests), else null, which it also
     *         is once a modal dialog has moved into the scene of an owner that went fullscreen
     */
    public @Nullable NativeWindow modalWindow() {
        return modalWindow;
    }

    /** The panel widget tree; headless tests drive it directly (buttons/ESC/resolve). */
    Widget<?> contentRoot() {
        return panel;
    }

    // --------------------------------------------------------------- showing

    /**
     * Shows the dialog window-modal over {@code owner} (locks only its window),
     * or, in {@link DisplayMode#IN_SCENE} and over a fullscreen owner, a scene-modal
     * internal overlay.
     *
     * <p>The card takes its size step, layout direction and locale from the scene's root, and
     * from the new root when {@link Scene#setRoot} switches the page while it is open, as every
     * {@code Scene} overload of this class does; the {@code Widget} overloads take them from the
     * widget given, which stays the card's host whatever happens to the page around it.
     *
     * @param owner the scene the dialog is modal over
     * @return a stage that completes with the result when the dialog closes
     * @throws IllegalStateException if this dialog was already shown (dialogs are single-use)
     */
    public CompletionStage<@Nullable String> show(Scene owner) {
        Ui.checkUiThread();
        return presentsModalInScene(owner)
                ? presentInScene(owner, false)
                : presentNative(owner, true, owner.window());
    }

    /**
     * Shows the dialog toolkit-modal over {@code owner} (locks every window). In
     * {@link DisplayMode#IN_SCENE}, and over a fullscreen owner, it presents the same
     * scene-modal internal overlay as {@link #show}, which still locks every other window.
     *
     * @param owner the scene the dialog opens over
     * @return a stage that completes with the result when the dialog closes
     * @throws IllegalStateException if this dialog was already shown (dialogs are single-use)
     */
    public CompletionStage<@Nullable String> showToolkitModal(Scene owner) {
        Ui.checkUiThread();
        return presentsModalInScene(owner)
                ? presentInScene(owner, true)
                : presentNative(owner, true, null);
    }

    /**
     * Shows the dialog <b>non-modal</b> over {@code owner}: a floating window
     * that locks nothing, so the owner (and every other window) stays fully
     * interactive. Only valid in {@link DisplayMode#NATIVE_WINDOW}; an in-scene
     * overlay always captures its scene's input. For the same reason it stays a window
     * even over a fullscreen owner, where every other floating surface is drawn in scene,
     * and when its owner goes fullscreen while it is open, and there it pays what a second
     * window costs: an owner fullscreen at a named mode is minimized when the dialog takes the
     * focus, and on a Mac without a GPU the window is slow to open.
     *
     * @param owner the scene the dialog opens over
     * @return a stage that completes with the result when the dialog closes
     * @throws IllegalStateException if the display mode is {@link DisplayMode#IN_SCENE},
     *                               or if this dialog was already shown (dialogs are single-use)
     */
    public CompletionStage<@Nullable String> showNonModal(Scene owner) {
        Ui.checkUiThread();
        if (displayMode == DisplayMode.IN_SCENE) {
            throw new IllegalStateException(
                    "non-modal dialogs require DisplayMode.NATIVE_WINDOW "
                            + "(an in-scene overlay always captures input)");
        }
        return presentNative(owner, false, null);
    }

    /**
     * {@link #show(Scene)} over {@code owner}'s scene, but inheriting the size step from
     * <b>{@code owner} itself</b> rather than from the scene root, the overload to reach
     * for when a SMALL toolbar (or a LARGE settings pane) raises the dialog and the card
     * should match the surface it came from.
     *
     * @param owner the widget whose scene the dialog is modal over, and whose step it takes
     * @return a stage that completes with the result when the dialog closes
     * @throws IllegalStateException if {@code owner} is not in a scene, or the dialog was already shown
     */
    public CompletionStage<@Nullable String> show(Widget<?> owner) {
        Ui.checkUiThread();
        Scene scene = adoptSizeHost(owner);
        return presentsModalInScene(scene)
                ? presentInScene(scene, false)
                : presentNative(scene, true, scene.window());
    }

    /**
     * {@link #showToolkitModal(Scene)}, inheriting the size step from {@code owner}.
     *
     * @param owner the widget whose scene the dialog opens over, and whose step it takes
     * @return a stage that completes with the result when the dialog closes
     */
    public CompletionStage<@Nullable String> showToolkitModal(Widget<?> owner) {
        Ui.checkUiThread();
        Scene scene = adoptSizeHost(owner);
        return presentsModalInScene(scene)
                ? presentInScene(scene, true)
                : presentNative(scene, true, null);
    }

    /**
     * {@link #showNonModal(Scene)}, inheriting the size step from {@code owner}.
     *
     * @param owner the widget whose scene the dialog opens over, and whose step it takes
     * @return a stage that completes with the result when the dialog closes
     */
    public CompletionStage<@Nullable String> showNonModal(Widget<?> owner) {
        Ui.checkUiThread();
        Scene scene = adoptSizeHost(owner);
        if (displayMode == DisplayMode.IN_SCENE) {
            throw new IllegalStateException(
                    "non-modal dialogs require DisplayMode.NATIVE_WINDOW "
                            + "(an in-scene overlay always captures input)");
        }
        return presentNative(scene, false, null);
    }

    /** Records {@code owner} as the size-inheritance host and returns the scene to present in. */
    private Scene adoptSizeHost(Widget<?> owner) {
        Objects.requireNonNull(owner, "owner");
        Scene scene = owner.scene();
        if (scene == null) {
            throw new IllegalStateException(
                    "the owner widget is not attached to a scene: nothing to present over");
        }
        inheritanceHost = owner;
        return scene;
    }

    /**
     * Whether a modal presentation over {@code owner} is an overlay: when one was asked for, and
     * over a fullscreen window whatever was asked. A dialog window over an owner fullscreen at a
     * named mode takes the focus on opening and so minimizes it, taking the person away from the
     * very thing the dialog is asking about; one at the mode the display is in already covers the
     * screen, so the card loses nothing drawn inside it and is spared the cost of a window, which
     * on a Mac without a GPU held the UI for seconds when the colour picker's dialog opened as one.
     *
     * <p>Fullscreen only. A window that cannot be positioned keeps its dialogs as windows: a dialog
     * has no anchor inside its owner to line up with, so the compositor placing it is where it
     * belongs rather than a fallback.
     */
    private boolean presentsModalInScene(Scene owner) {
        Objects.requireNonNull(owner, "owner");
        NativeWindow window = owner.window();
        return displayMode == DisplayMode.IN_SCENE || (window != null && window.isFullscreen());
    }

    /**
     * Links {@code card}, the parentless root of the card's subtree, to the widget its inherited
     * axes resolve through: whatever a {@code show(Widget)} overload recorded, else the owner
     * scene's root, which a page switch replaces with the new one.
     */
    private void linkHost(Widget<?> card, Scene owner) {
        if (inheritanceHost != null) {
            card.setInheritanceHost(inheritanceHost);
        } else {
            SceneAccess.hostOnRoot(owner, card); // and on the next root, when the page switches
        }
    }

    /**
     * A dialog presents once and resolves once. Re-showing a resolved dialog
     * would create a modal that can never close: {@code resolve()} is latched
     * shut, so {@code popModal} would be unreachable and the owner window would
     * stay locked for good.
     */
    private void checkNotPresented() {
        if (presented || closing) {
            throw new IllegalStateException("this Dialog was already shown: a Dialog is "
                    + "single-use (one show*, one result); build a new instance per presentation");
        }
    }

    // ---------------------------------------------------------- native window

    private CompletionStage<String> presentNative(Scene owner, boolean asModal, NativeWindow blockParent) {
        Ui.checkUiThread();
        Objects.requireNonNull(owner, "owner");
        checkNotPresented();
        presented = true;
        NativeWindow window = owner.window();
        if (window == null) {
            // Headless (embedded/tests): no window to host a modal. The dialog
            // is still a usable state machine; drive contentRoot() directly.
            fade = 1;
            return result;
        }
        this.ownerWindow = window;
        this.modal = asModal;
        Backend backend = window.backend();

        // The card is the root of its OWN scene here, so it has no parent to inherit a step
        // from: link it to the owner before anything sizes it. The ordering is not cosmetic:
        // the window is sized from dialogMaxWidth and panel.measure() below, before bind();
        // install the link after that and the window is sized at the process default while
        // the content re-measures at the owner's step inside it, so the card clips or floats.
        // An undecorated card on a system whose compositor casts no shadow for such a window
        // gets a window larger than itself by the shadow's reach, and draws its own into the
        // margin. A press there does nothing: it is beside the dialog, which a modal one does
        // not close on and another kind has no reason to.
        windowMargin = style == WindowStyle.UNDECORATED_TRANSLUCENT
                ? limn.components.internal.PopupFrame.marginFor(window, Theme.current(), Elevation.MODAL) : 0;
        int margin = windowMargin;
        Widget<?> root = margin == 0 ? panel
                : new limn.components.internal.PopupFrame(panel, margin, () -> { });
        linkHost(root, owner);
        // A scene gives the panel a text ruler so it can be measured to size
        // the window; it is then bound to the modal window.
        // On the owner's clock, so the fade-out that destroys this window — and completes the
        // dialog's future — advances with it (Scene#clock).
        modalScene = new Scene(root, owner.clock());
        modalScene.inheritRenderingFlags(owner); // partial/debug follow the owner window
        // Opaque styles fill with the surface color; translucent shows the
        // desktop through the transparent framebuffer's rounded corners.
        Theme theme = Theme.current();
        modalScene.setBackground(style.transparent() ? Color.TRANSPARENT : theme.surface());
        // Resolved once, after the host link: the window is sized from this row and the
        // content must be laid out from the same one.
        SizeTokens t = theme.tokensFor(panel);
        // Measured against what the SCREEN allows, not what the owner happens to be: a card
        // taller than the work area is one whose buttons are behind the dock. The body scrolls
        // inside whatever this leaves it.
        Size budget = nativeBudget(window);
        Size panelSize = panel.measure(Constraints.loose(
                Math.min(t.dialogMaxWidth(), budget.width()), budget.height()));
        int w = Math.max(1, Math.round(Math.min(panelSize.width(), budget.width()))) + 2 * margin;
        int h = Math.max(1, Math.round(Math.min(panelSize.height(), budget.height()))) + 2 * margin;

        // Floating (optional) focus-stealing modal window in the requested style.
        // Owned, not a popup: this closes and moves with the owner like a dropdown, but it is a
        // window in its own right, and an in-scene modal over the owner must lock it the way a
        // native modal would. Owning it as a popup is what let a non-modal dialog stay clickable
        // under a modal. Centred over the owner, in the owner's points.
        modalWindow = backend.createWindow(WindowConfig.styled(title.get(), w, h, style, alwaysOnTop, true)
                .ownedBy(window).atInOwner((window.logicalWidth() - w) / 2f, (window.logicalHeight() - h) / 2f));
        modalScene.bind(modalWindow);
        // The system closing this window is the user cancelling the dialog, asked as Escape is. An
        // application ending never asks it: an owned window is not asked, and closes with its
        // owner once the windows that are asked agree.
        modalWindow.setCloseRequestHandler(request -> {
            request.refuse();
            cancel(CancelRequest.Reason.WINDOW_CLOSE);
        });

        // Show before pushing the modal so it can take native focus (offscreen
        // screenshot mode keeps it hidden and captures via captureNextFrame).
        if (window.isVisible()) {
            // Whole-window compositor fade, uniform across decorated, opaque and
            // translucent styles (the OS frame fades too). Snap transparent first.
            modalScene.fadeWindowIn(limn.animation.Motion.duration(Theme.current().animWindow));
            modalWindow.show();
        }
        if (modal) {
            BackendPeer.of(backend).pushModal(modalWindow, blockParent);
        }
        // Unless the content asked for one of its widgets as it joined the new scene: that is the
        // first focus, and traversing from it would move on to the stop after it.
        if (modalScene.focusedWidget() == null) {
            Button first = firstFocusedButton();
            if (first != null) {
                modalScene.requestFocus(first);
            } else {
                modalScene.focusTraverse(false); // the body's first stop, or nothing
            }
        }
        fade = 1; // content painted opaque; the window opacity does the fading
        observeUnanswered(modalScene); // the OS can close this window out from under us
        if (modal) {
            // Told even though the owner is blocked: the application can still make it
            // fullscreen, and on macOS the lock stops the owner's input, not the green button in
            // its title bar, which AppKit answers itself.
            boolean toolkitScope = blockParent == null;
            fullscreenWatch = owner.observeWindowFullscreen(fullscreen -> {
                if (fullscreen) {
                    moveIntoScene(owner, toolkitScope);
                }
            });
        }
        modalWindow.requestFrame();
        return result;
    }

    /**
     * Moves a modal dialog that is a window of its own into its owner's scene, the owner having
     * gone fullscreen: the presentation it would have had if it had opened there.
     *
     * <p>Moved rather than dismissed, because a modal dialog is a question the application is
     * waiting on, and a fullscreen change is no answer to it. Moved rather than left, for the
     * reasons a dialog opened over a fullscreen owner is drawn in scene: an owner fullscreen at a
     * named mode is minimized as soon as the person reaches the dialog's window, which takes the
     * focus from it; and one at the mode the display is in covers the screen, so the card loses
     * nothing inside it, while the transition resizes the owner under a window that was centred
     * over its old frame. The card itself moves, so what the person typed in it and where the
     * keyboard was come with it, the focused field keeping its caret and selection as they were,
     * and the lock goes on the same windows the window's did.
     *
     * <p>Not while an answer is on its way out, and not while something is open over the dialog's
     * own window, a second dialog or a modal menu: moving this one would close the window that one
     * belongs to. Both stay windows then, as they were before this existed. Never back: an overlay
     * is a valid presentation over a window of any kind, so leaving fullscreen changes nothing.
     */
    private void moveIntoScene(Scene owner, boolean toolkitScope) {
        NativeWindow host = owner.window();
        if (modalWindow == null || closing || modalWindow.isModalBlocked()
                || host == null || host.isClosed()) {
            return;
        }
        Scene leaving = modalScene;
        if (unanswered != null) {
            // The window goes and the dialog does not: its close must not answer for it.
            unanswered.cancel();
            unanswered = null;
        }
        // Lets go of the owner's lock first, since a blocked owner hands the focus straight back to
        // the modal over it; the window itself stays up until the owner has the focus.
        NativeWindow left = releaseWindow();
        modalScene = null;
        displayMode = DisplayMode.IN_SCENE;
        hostScene = owner;
        // The card is the root of the scene being left, or, in a window larger than the card by
        // its shadow's reach, the child of the frame that insets it; that scene lets go of it (a
        // scene's root is refused by every container until then) and takes it out of the frame,
        // leaving it in the scene with the keyboard, so the container that fades it can adopt it,
        // and adopting it takes it out of that scene before pushOverlay puts it in this one. The
        // window left draws nothing and answers nothing but its close from here on.
        SceneAccess.letGoOfRoot(leaving, panel);
        overlay = new SceneOverlay(fadingCard());
        linkHost(overlay, owner);
        // With the keyboard, taken from the scene being left before its close could run the
        // widget's focus-lost path, and never put on the first stop as if by Tab: the one would
        // drop a selection and commit a half-typed entry, the other select all of a field's text.
        SceneAccess.pushMovedOverlay(owner, overlay, leaving);
        sceneModal = BackendPeer.of(host.backend()).pushSceneModal(host, toolkitScope);
        observeUnanswered(owner);
        startFadeInScene();
        new LeftWindow(left, leaving).goOnceFocused(owner, host);
    }

    /**
     * A modal dialog's own window after its card has moved into the owner's scene: kept up, out of
     * sight, until the owner reports the focus it is asked for here, and only then hidden and
     * closed.
     *
     * <p>The window held the focus, and a window hidden with the focus leaves the desktop to choose
     * the next one. Hidden in the same instant the owner was asked, it left the choice to GNOME
     * over X11 anyway: the window manager unmapped it before it had registered the owner's request,
     * and the owner, given the focus, lost it again some 20 ms later, in 3 runs of 5 and in 13 of
     * 13 traced ones. Kept up until the owner has the focus, it has nothing left to hand over when
     * it goes. It goes regardless after {@link #OWNER_FOCUS_WAIT_MILLIS}, so that an owner that
     * never reports the focus cannot keep it up, and at once when another modal still blocks the
     * owner, which then keeps the focus itself.
     *
     * <p>Meanwhile it draws nothing and answers nothing: the card is the owner's, and a frame or a
     * key reaching it through the scene it left would lay it out at this window's size or act on
     * it from a window the person no longer sees. Only its close still reaches that scene, which
     * lets go of what it holds; and the system's close of it, which it may still receive since it
     * holds the focus, closes it without asking the dialog.
     */
    private static final class LeftWindow implements Runnable {
        private final NativeWindow window;
        private Subscription focusWatch;
        private boolean gone;

        LeftWindow(NativeWindow window, Scene leaving) {
            this.window = window;
            // The system's close of this window, which still holds the focus, closes it alone: the
            // dialog's handler would refuse it and cancel the dialog that now lives in the scene.
            window.setCloseRequestHandler(null);
            WindowPeer.of(window).setFrameCallback((renderer, frame) -> { });
            WindowPeer.of(window).setInput(new ClosingOnly(SceneAccess.input(leaving)));
            leaving.fadeWindow(0f, 0, null); // out of sight at once, though still up
        }

        void goOnceFocused(Scene owner, NativeWindow host) {
            if (host.isModalBlocked()) {
                run();
                return;
            }
            focusWatch = SceneAccess.observeWindowFocus(owner, this);
            host.focus();
            Ui.postDelayed(this, OWNER_FOCUS_WAIT_MILLIS);
        }

        /** Hides and closes the window, once, whichever of the focus and the wait comes first. */
        @Override
        public void run() {
            if (gone) {
                return;
            }
            gone = true;
            if (focusWatch != null) {
                focusWatch.cancel();
                focusWatch = null;
            }
            if (!window.isClosed()) {
                window.hide(); // gone at once, not at the loop's next turn
                window.requestClose();
            }
        }
    }

    /**
     * What a dialog's own window hears once its card has moved into the owner's scene: only its
     * close, passed to the scene it left so that that scene lets go of what it holds.
     */
    private record ClosingOnly(WindowInput scene) implements WindowInput {
        @Override public void mouseMoved(float x, float y) { }
        @Override public void mouseButton(int button, boolean pressed, int modifiers, float x,
                                          float y, int clickCount) { }
        @Override public void scrolled(float deltaX, float deltaY, float x, float y) { }
        @Override public void keyEvent(int key, int layoutCodePoint, boolean pressed, boolean repeat,
                                       int modifiers) { }
        @Override public void charTyped(int codepoint) { }
        @Override public void pointerEntered(boolean entered) { }
        @Override public void windowResized(float logicalWidth, float logicalHeight) { }
        @Override public void windowClosed() { scene.windowClosed(); }
    }

    /**
     * The box a native dialog may occupy, in logical points, inset by one {@code spacingLarge}
     * on every side so a clamped card never sits flush against the edge that clamped it.
     *
     * <p>The bound is the <b>work area</b> of the display the owner sits on: the monitor less
     * the OS chrome, the same rectangle a popup menu clamps against. Not the owner window: a
     * dialog is a window of its own and is allowed to be larger than the one that opened it,
     * and a card sized from an owner squeezed into a corner would be needlessly cramped.
     *
     * <p><b>Unbounded when there is no display to ask</b>: every headless and embedded backend.
     * Substituting the owner's own size there would be a guess, and the wrong one by this
     * method's own argument: it would cap a dialog at a window it is expressly allowed to
     * exceed, on the backends least able to say otherwise.
     */
    private Size nativeBudget(NativeWindow owner) {
        Display display = owner.display();
        float factor = owner.logicalToScreenFactor();
        if (display == null || factor <= 0) {
            return new Size(Constraints.UNBOUNDED_LIMIT, Constraints.UNBOUNDED_LIMIT);
        }
        float margin = 2 * Theme.current().tokensFor(panel).spacingLarge();
        ScreenRect area = display.workArea();
        return new Size(Math.max(1, area.width() / factor - margin),
                Math.max(1, area.height() / factor - margin));
    }

    /**
     * Lets go of the owner's lock, gives the owner the focus back, and only then asks the window
     * to close, in that order.
     *
     * <p>The window holds the focus, and one that is closed with it leaves the desktop to choose
     * the next window, which may be the desktop itself: on GNOME over X11 it was, which takes the
     * keyboard from the owner and minimizes one fullscreen at a named mode. The lock goes first
     * because a blocked owner hands the focus straight back to the modal over it; and the owner is
     * not asked while another modal still blocks it, which keeps the focus then.
     */
    private void closeWindow() {
        NativeWindow closing = releaseWindow();
        if (closing == null) {
            return;
        }
        if (ownerWindow != null && !ownerWindow.isClosed() && !ownerWindow.isModalBlocked()) {
            ownerWindow.focus();
        }
        closing.requestClose();
    }

    /**
     * Lets go of the dialog's own window, which stays up: stops listening for the owner's
     * fullscreen, lets go of the owner's lock and of the owner's hold on the window.
     *
     * @return the window let go of, or {@code null} when there was none
     */
    private NativeWindow releaseWindow() {
        if (fullscreenWatch != null) {
            fullscreenWatch.cancel();
            fullscreenWatch = null;
        }
        NativeWindow window = modalWindow;
        if (window == null) {
            return null;
        }
        modalWindow = null;
        if (modal) {
            BackendPeer.of(window.backend()).popModal(window);
        }
        if (ownerWindow != null && !ownerWindow.isClosed()) {
            WindowPeer.of(ownerWindow).unregisterChildPopup(window);
        }
        return window;
    }

    // -------------------------------------------------------------- in-scene

    /**
     * Presents in-scene, unless the host is already locked by a modal and is not
     * fullscreen, in which case this dialog becomes a native window instead and says so.
     *
     * <p>An overlay is drawn inside its host window, so it can only be put in front
     * by bringing that window forward, and that hides whatever dialog was floating
     * over it. The person sees the first dialog disappear; the developer sees
     * nothing, because it works in every test where only one dialog is open. So the
     * toolkit takes the one decision that cannot be wrong here (a native window
     * stacks over the other one, which is what "in front" was supposed to mean),
     * and leaves {@link #keepInScene()} for an app that has considered it.
     */
    private CompletionStage<String> presentInScene(Scene owner, boolean toolkitScope) {
        Ui.checkUiThread();
        Objects.requireNonNull(owner, "owner");
        checkNotPresented();
        NativeWindow host = owner.window();
        // Never over a fullscreen host. The promotion rests on a new window stacking over the
        // dialog already there, and over a fullscreen window a new one is not offered, for the
        // reasons that sent a dialog into the scene there at all: at a named mode it takes the
        // focus and minimizes the host, and at the mode the display is in the host already covers
        // the screen.
        if (!keepInScene && host != null && host.isModalBlocked() && !host.isFullscreen()) {
            LOG.log(System.Logger.Level.WARNING,
                    "dialog \"{0}\" asked for IN_SCENE while a modal is already open over its "
                            + "window; presenting it as a native window instead, because an "
                            + "overlay can only be brought to the front by raising its host, "
                            + "which would hide the dialog already there. Call keepInScene() to "
                            + "insist on the overlay.", title.get());
            // NATIVE_WINDOW for real, not a native window flying an in-scene flag: the
            // card paints from this field, and setStyle (documented as ignored in-scene)
            // becomes meaningful again the moment there is a window to apply it to.
            displayMode = DisplayMode.NATIVE_WINDOW;
            return presentNative(owner, true, toolkitScope ? null : host);
        }
        presented = true;
        // IN_SCENE for real here too, including when a fullscreen owner overrode a request for a
        // window: the card paints from this field, and displayMode() answers from it.
        displayMode = DisplayMode.IN_SCENE;
        this.hostScene = owner;
        overlay = new SceneOverlay(fadingCard());
        // The host link goes on the OVERLAY, not on the panel: the panel's parent is the
        // overlay here, and a host link on a widget that has a parent is ignored (the tree
        // wins). The overlay is the parentless one (pushOverlay sets the scene but never a
        // parent), so it is where the chain has to be reattached, before anything measures.
        linkHost(overlay, owner);
        // pushOverlay captures input, confines focus and focuses the overlay's first stop; the
        // dialog names that stop itself when it is one of its buttons, so that no destructive
        // button is focused on the way.
        Button first = firstFocusedButton();
        if (first != null) {
            SceneAccess.pushOverlayFocusing(owner, overlay, first);
        } else {
            owner.pushOverlay(overlay);
        }
        // The overlay only blocks the owner's own scene; register a backend
        // scene-modal so the rest of the application is locked too, matching a
        // native modal: any dialog already open falls behind this one, and for
        // toolkit scope every other window locks. The owner keeps its own popups:
        // this card's dropdowns and menus are windows owned by the owner.
        if (host != null) {
            sceneModal = BackendPeer.of(host.backend()).pushSceneModal(host, toolkitScope);
        }
        observeUnanswered(owner); // the host window can die with this still open
        startFadeInScene();
        return result;
    }

    /**
     * The card in the containers that fade and slide it in the scene, {@code Opacity(Transform(card))},
     * made the first time: they adopt the card, which takes it out of the scene it was the root of
     * when it moves in from a window of its own, once that scene has let go of it. Neither container is a node of the accessible tree,
     * so the card is read under the overlay where it was. The fade outside, so the opacity's picture
     * lies on the window's grid and holds the card wherever the slide draws it.
     */
    private Opacity fadingCard() {
        Opacity fading = cardFade;
        if (fading == null) {
            Transform slide = new Transform(panel);
            cardSlide = slide;
            fading = new Opacity(slide);
            cardFade = fading;
        }
        return fading;
    }

    /** The container that slides the card, made with {@link #fadingCard()}. */
    private Transform slidingCard() {
        fadingCard();
        return java.util.Objects.requireNonNull(cardSlide);
    }

    /**
     * Fades the card in, in the scene, through the container round it, which draws it, its shadow
     * and its buttons as one picture. That opacity is the dialog's fade: the veil's alpha reads it as
     * it paints, the card's slide keeps pace with it, and the fade out that ends the dialog is the
     * card's too. One
     * fade and not two side by side: two kept apart, the dialog's linear one and the card's eased
     * one, met only at their ends, so a dialog closed while it faded in took its veil away with its
     * card still partly drawn, and while the dialog's fade in went on stepping up beside the fade
     * out stepping down, the two cancelled each other and the dialog never closed. The veil is one
     * fill and fades by its alpha, as one fill cannot show through itself.
     */
    private void startFadeInScene() {
        cardFades = true;
        Opacity fading = fadingCard();
        if (limn.animation.Motion.reduced()) {
            fading.setOpacity(1f); // no fade for a user who asked for less motion: the card is there at once
            slidingCard().setTranslation(0, 0);
            if (overlay != null) {
                overlay.invalidate();
            }
            return;
        }
        fading.setOpacity(0f);
        slidingCard().setTranslation(0, SLIDE_DISTANCE);
        fadeCard(1f, Theme.current().animWindow, null);
    }

    /**
     * Moves the card's opacity, and the veil and the slide with it, to {@code target}: its
     * container's {@code fadeTo}, on wall time (a dialog is shell, and its fade out completes the result
     * future), and while it moves a ticker that repaints the veil, one however many fades follow.
     *
     * <p>The slide is the card's other container sliding to {@code (1 - target)} of its distance
     * over the same time, eased the same way on the same wall-time frames from where it is, which
     * keeps it at {@code (1 - fade)} of the distance on every frame: a picture moved, where the card
     * was laid at a new place inside each frame's paint and the accessible tree walked again for it.
     */
    private void fadeCard(float target, double asked, @Nullable Runnable onArrive) {
        // No time where nothing is shown to fade, a host with no window, or while the user asks for
        // less motion: a fade that goes straight there keeps its arrival for the seconds it asked
        // for, and this arrival is what removes the overlay and completes the result.
        double seconds = hostScene == null || hostScene.window() == null
                ? 0 : limn.animation.Motion.duration(asked);
        slidingCard().slideTo(0, (1 - target) * SLIDE_DISTANCE, seconds, null);
        cardMoving = true;
        fadingCard().fadeTo(target, seconds, () -> {
            cardMoving = false;
            if (overlay != null) {
                SceneAccess.repaintPicture(overlay); // the veil's last step
            }
            if (onArrive != null) {
                onArrive.run();
            }
        });
        if (cardMoving && !veilFollowing && hostScene != null) {
            veilFollowing = true;
            hostScene.addRealTimeTicker(dt -> {
                if (overlay != null) {
                    // The veil's alpha, a picture: nothing the accessible tree publishes moved, so
                    // no frame of the fade walks it, as no frame of the slide does.
                    SceneAccess.repaintPicture(overlay);
                }
                veilFollowing = cardMoving;
                return veilFollowing;
            });
        }
    }

    private void closeOverlay() {
        if (sceneModal != null) {
            sceneModal.release();
            sceneModal = null;
        }
        if (overlay != null && hostScene != null) {
            hostScene.removeOverlay(overlay);
        }
        overlay = null;
    }

    // ---------------------------------------------------------------- resolve

    /**
     * The two keys the card itself answers, from whichever root the event reached: ESC cancels,
     * Return presses the default button.
     *
     * <p>These arrive only once nothing inside the card wanted them, because a key event that
     * reaches a widget's ancestors is one the focused widget did not consume. That ordering is
     * the whole design of the Return case: a focused {@link Button} consumes Return and fires
     * itself, a {@link TextArea} consumes it and inserts a newline, and what is left over is
     * Return pressed in a text field, on a checkbox, on a destructive button (which lets it
     * through, so that Return never deletes), or with nothing focused at all, which on every
     * desktop platform means the default button.
     *
     * <p>Without this a dialog whose body holds a text field could not be answered from the
     * keyboard at all: initial focus lands on the field, the field ignores Return, and the card
     * knew only ESC.
     *
     * <p>Only the press answers, never the keyboard's repeats of it, which are consumed, as a
     * {@link Button} arms on the press alone. A key held down otherwise acted on every dialog it
     * reached: an Escape held on a form whose cancel handler asks a question cancelled that
     * question on the next repeat, the form asked again on the one after, and the two alternated
     * for as long as the key was down (2026-10-20).
     */
    private void answerDialogKey(KeyEvent event) {
        if (!event.isPressed() || closing) {
            return;
        }
        boolean answers = event.key() == Keys.ESCAPE || (event.key() == Keys.ENTER && hasDefaultButton);
        if (event.isRepeat()) {
            if (answers) {
                event.consume(); // nor does it reach anything behind the card
            }
            return;
        }
        if (event.key() == Keys.ESCAPE) {
            event.consume();
            cancel(CancelRequest.Reason.ESCAPE);
        } else if (event.key() == Keys.ENTER && hasDefaultButton) {
            event.consume();
            answer(defaultResult);
        }
    }

    /**
     * The button that takes the dialog's first focus, or {@code null} when that focus is the
     * body's: a field, or any other stop the body holds ahead of the buttons, which the scene's
     * traversal finds as it always did. Otherwise the default button, then the first button that
     * destroys nothing, and only in a dialog whose every button is destructive the first of
     * those, which refuses Return and leaves Escape to the card.
     *
     * <p>The scene's traversal alone took the row's first button, and a destructive one added
     * ahead of the safe answer took the focus and, with it, Return (2026-10-19).
     */
    private @Nullable Button firstFocusedButton() {
        if (firstStop(bodyScroll) != null) {
            return null;
        }
        Button safe = null;
        Button destructive = null;
        for (Widget<?> child : buttonRow.children()) {
            if (!(child instanceof Button button) || !button.isFocusable()
                    || !SceneAccess.inReach(button)) {
                continue;
            }
            if (button == buttonRow.defaultButton) {
                return button;
            }
            if (!button.isDestructive()) {
                safe = safe == null ? button : safe;
            } else {
                destructive = destructive == null ? button : destructive;
            }
        }
        return safe != null ? safe : destructive;
    }

    /** The first widget of {@code root}'s subtree that Tab would stop at, or {@code null}. */
    private static @Nullable Widget<?> firstStop(Widget<?> root) {
        if (!root.isVisible() || !root.isEnabled()) {
            return null;
        }
        if (root.isFocusable() && SceneAccess.inReach(root)) {
            return root;
        }
        for (Widget<?> child : root.children()) {
            Widget<?> found = firstStop(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Completes the dialog with the cancel result if its surface is destroyed
     * while the dialog is still open, registered at presentation, in both display
     * modes.
     *
     * <p>A dialog is normally answered by a button, ESC or the scrim, all of which
     * run {@link #resolve}, and the system's close of its own window asks it as ESC
     * does. Its surface can still simply die: the owner window going away and taking
     * the windows it owns with it, or the window an in-scene dialog is drawn in
     * closing. Frames stop at that instant, and every completion path here is driven
     * by a fade, so without this the future is abandoned and a caller that does its
     * cleanup in {@code thenAccept} silently never does it. Cancel is the honest
     * answer: nobody chose anything.
     */
    private void observeUnanswered(Scene scene) {
        unanswered = scene.observeWindowClosed(() -> {
            unanswered = null;
            if (closing) {
                return; // an answer is already on its way out; it owns the result
            }
            closing = true;
            settlePendingCancel();
            // The window is already destroyed and the backend has dropped it from
            // the modal stack, so there is nothing here to pop, close or unhook:
            // only fields to stop pointing at the dead.
            if (fullscreenWatch != null) {
                fullscreenWatch.cancel(); // held by the owner's scene, which may outlive this
                fullscreenWatch = null;
            }
            modalWindow = null;
            overlay = null;
            sceneModal = null;
            result.complete(cancelResult);
        });
    }

    /**
     * Whether the dialog can still be answered: not once an answer is on its way out, through the
     * fade that follows it. The one condition the card publishes and performs {@code CANCEL} on,
     * and its buttons {@code PRESS} (2026-09-15, semantics 5): through the fade both were
     * published, and the cancel refused while the press was answered done for a resolution that
     * {@link #resolve}'s own guard dropped.
     */
    private boolean answerable() {
        return !closing;
    }

    private void resolve(String value) {
        Ui.checkUiThread();
        if (closing) {
            return;
        }
        closing = true;
        settlePendingCancel();
        // The card's verb and its buttons' go with it. A native window's fade moves only the
        // window's opacity and damages nothing, so the walk that withdraws them is asked for.
        panel.invalidateAccessible();
        if (unanswered != null) {
            // An answer arrived: this dialog resolves through the fade path below,
            // which registers its own close flush. Dropping the observer matters for
            // in-scene dialogs, whose host scene outlives them: one leaked Runnable
            // per dialog shown, otherwise.
            unanswered.cancel();
            unanswered = null;
        }
        if (modalWindow != null) {
            // Fade the whole modal window out, then tear it down + resolve.
            modalScene.fadeWindowOut(limn.animation.Motion.duration(Theme.current().animWindow), () -> {
                closeWindow();
                result.complete(value); // fully gone before callbacks run
            });
        } else if (overlay != null && hostScene != null) {
            // The fade ticker advances only while frames render: if the host
            // window dies mid-fade, finish immediately or the result future
            // never completes (the native path gets this same flush from
            // fadeWindowOut's windowClosed handling).
            Subscription unhook = hostScene.observeWindowClosed(() -> {
                closeOverlay();
                result.complete(value);
            });
            // The card's fade out, the veil and the slide with it, from wherever its fade in had got
            // to, over the share of the window animation that brings it back to nothing; it
            // supersedes a fade in still running. Its arrival is what removes the overlay and
            // completes `result`, on wall time, and load-bearing: on scene time a paused app
            // could never close a dialog.
            fadeCard(0f, Theme.current().animWindow * fadingCard().opacity(), () -> {
                unhook.cancel();
                closeOverlay();
                result.complete(value);
            });
        } else {
            result.complete(value); // headless: no animation/window
        }
    }

    // ------------------------------------------------------------- containers

    /**
     * The action row. Its gutter is {@code gapButtonRow} (4/5/6/8/10), deliberately
     * <b>not</b> {@code spacingSmall} (3/4/6/8/10): the two agree from MEDIUM up and part
     * ways below it, where 3pt between two adjacent activatable rects would defeat the
     * WCAG 2.2 SC 2.5.8 <em>Spacing</em> exception the dense steps lean on. No {@code Token*}
     * container carries that token, so the push lives here.
     *
     * <p>To an assistive technology it is nothing: it declares no role, name, action or state of
     * its own, is never focusable and paints nothing, so the tree deletes it and hoists the
     * buttons into the card's place, and a reader hears the buttons rather than a box between
     * them. It still carries one hook, because the walk asks a widget's <em>direct</em> parent
     * to add what only the parent knows about a child, whether or not that parent survives as a
     * node &mdash; and the one fact about a dialog button that the button itself refuses to
     * declare is whether it is the default one.
     */
    private static final class ActionRow extends Row {

        /**
         * The button Return answers with when nothing focused wanted the key, or {@code null}
         * while the card has none. Written by {@link Dialog#addButton} in the branch that
         * records the default result, so the two can never disagree, and never rewritten,
         * because a dialog is single-use and its first primary button stays its default.
         */
        private Button defaultButton;

        /**
         * Marks the default button as such on its node. The state is the dialog's fact and not
         * the button's, which holds no such notion, and this is the only parent whose hook
         * reaches the button's node. A reference comparison and a single bit: nothing here
         * allocates, and the bit never churns, so a quiet frame stays free.
         *
         * @param child one of this row's buttons
         * @param a     that button's node
         */
        @Override
        protected void onAccessibilityChild(Widget<?> child, Accessibility a) {
            if (child == defaultButton) {
                a.state(Accessible.State.DEFAULT);
            }
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            // Silent form: we are already inside the pass that consumes the gap, and a
            // markNeedsLayout() from within onMeasure dirties the whole ancestor chain with
            // no pass scheduled to clear it (the layoutPass flag is reset afterwards).
            gapSilently(Theme.of(this).tokensFor(this).gapButtonRow());
            return super.onMeasure(constraints);
        }
    }

    /**
     * The card's two-part stack: a body that scrolls over a footer that never does.
     *
     * <p>Not a {@code TokenColumn}, and that is the whole reason it exists. A {@code Flex}
     * measures a non-flex child against an <b>unbounded</b> main axis, so a {@link ScrollView}
     * inside one always answers with its content's full height and never learns there is a
     * budget at all; give it a flex factor instead and it takes every point going, so a
     * two-line dialog would stand as tall as the screen. This container is the one place that
     * holds both numbers (what the body wants, and what the card is allowed), so it is the
     * only one that can hand the body a height.
     *
     * <p>Keeping the buttons out of the scrolling half is not a refinement: a card capped at the
     * work area with its footer inside the viewport is one whose only way out can be scrolled
     * off, and ESC is not a thing every user tries.
     *
     * <p>The gutter is {@code spacingMedium}, the same token the body's own column carries, so
     * title → message → content → buttons reads as one rhythm however the card is split.
     *
     * <p>It is also where the body learns how far past its edges it may paint. The body scrolls
     * only vertically, so its view lets it past the sides by whatever band it is given, and past
     * the top and bottom too until the card is capped and the body overflows, when it clips there;
     * this column gives it the card's padding less the card's outline, so a focus ring or a colour
     * plane's cursor at the body's edge is drawn whole, into the padding, and nothing of the body
     * is ever drawn over the outline or past the card. Nothing else paints there: the padding is
     * empty card, the body's bar sits inside the body, and below it the gap leads to the footer,
     * which paints after the body.
     */
    private final class CardColumn extends Widget<CardColumn> {

        private final ScrollView scroll;
        private final Widget<?> footer;
        /** How far past its sides the body may paint, as the last measure resolved it. */
        private float band;

        CardColumn(ScrollView scroll, Widget<?> footer) {
            this.scroll = scroll;
            this.footer = footer;
            add(scroll);
            add(footer);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            // The padding sets its insets from the step before it measures this column, so they
            // are this pass's. The body column declares the same band as its view, because a
            // repaint pass skips the first widget on the way down that declared less than what
            // hangs past it, and what hangs past the body can reach as far as the view lets it.
            Insets padding = cardPadding.insets();
            band = Math.max(0, Math.min(padding.left(), padding.right()) - Strokes.BORDER);
            scroll.paintOverflow(band);
            body.paintOverflow(band);
            float gap = Tokens.spacingFor(this, Tokens.Role.MEDIUM);
            Constraints free = Constraints.loose(constraints.maxWidth(), Constraints.UNBOUNDED_LIMIT);
            Size footerSize = footer.measure(free);
            // The BODY, not the scroll view wrapping it: a scroll view offered a bounded height
            // answers with the height it was offered, so asking it what it wants is asking the
            // one widget in the tree that has agreed never to say.
            Size wanted = body.measure(free);

            float width = Math.max(wanted.width(), footerSize.width());
            float bodyHeight = wanted.height();
            if (constraints.hasBoundedHeight()) {
                bodyHeight = Math.min(bodyHeight,
                        Math.max(0, constraints.maxHeight() - footerSize.height() - gap));
            }
            // Tight, which is what turns the budget into a cap: below what the body wants, the
            // scroll view keeps the difference and starts scrolling. At or above it, the view is
            // exactly its content and behaves as though it were not there.
            scroll.measure(Constraints.tight(width, bodyHeight));
            return new Size(width, bodyHeight + gap + footerSize.height());
        }

        @Override
        protected void onLayout() {
            // Re-derived rather than remembered from the measure, so that the two boxes follow
            // the height this column was given, whatever measure came before.
            float gap = Tokens.spacingFor(this, Tokens.Role.MEDIUM);
            float footerHeight = footer.measure(
                    Constraints.loose(width(), Constraints.UNBOUNDED_LIMIT)).height();
            float bodyHeight = Math.max(0, height() - gap - footerHeight);
            scroll.layoutBox(0, 0, width(), bodyHeight);
            footer.layoutBox(0, bodyHeight + gap, width(), footerHeight);
        }

        /**
         * The larger of the reach of the theme's focus ring, which every layout declares for its
         * children, and the band the body may paint past its sides. The footer is laid out flush
         * with this column's bottom and sides, so the ring of a button at its end hangs past the
         * column into the card's padding; and the body is laid out flush with its top and sides,
         * let past the sides by the band, and past the top while it fits.
         */
        @Override
        protected float paintOutset() {
            return Math.max(Theme.of(this).focusRingReach(), band);
        }
    }

    // ------------------------------------------------------------- panel

    /**
     * The dialog card: root of the modal window's scene (native mode) or the
     * centered child of the {@link SceneOverlay} (in-scene mode). Paints the
     * rounded translucent panel for in-scene and translucent styles, a bordered
     * card for opaque, and nothing for decorated (the OS draws the frame).
     * Handles the card's own keys, ESC and Return.
     *
     * <p>It is also the widget a declared step lands on ({@link Dialog#setControlSize}) and
     * the one the host link is installed on in native mode, but it pushes <b>no</b> spacing
     * of its own: the padding, the content gutter and the action-row gutter are
     * {@code Token*} containers below it, each resolving its own step inside its own measure,
     * so the push stays correct at any depth and however the tree is rebuilt.
     */
    private final class DialogPanel extends Widget<DialogPanel> {

        /** The shadow the card casts while the dialog is drawn in the scene. */
        @Override
        protected float paintOutset() {
            return displayMode == DisplayMode.IN_SCENE || windowMargin > 0
                    ? Theme.of(this).elevation(Elevation.MODAL).reach() : 0;
        }
        private final Widget<?> child;
        private boolean dragging;
        private float grabX;
        private float grabY;

        DialogPanel(Widget<?> child) {
            this.child = child;
            add(child);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return child.measure(constraints);
        }

        @Override
        protected void onLayout() {
            refitNativeWindow();
            child.layoutBox(0, 0, width(), height());
        }

        /**
         * Describes the card as the dialog: a {@code DIALOG} named by the title it holds, with the
         * message as its description and one verb, the dismissal, offered while the dialog can
         * still be answered and not through the fade that follows an answer.
         *
         * <p>The name is the dialog's own painted text, handed over by reference, so a frame that
         * damaged the card and changed nothing about it allocates nothing to say so; its
         * provenance is {@code CONTENT} because that is what one platform reads as a dialog's
         * title. The heading the card paints carries the same string and is <b>not a node</b>:
         * the constructor marks it ignored, because it is the rendering of this node's own name
         * — a title bar's caption, drawn inside the card — and published beside the name it was
         * the one fact twice, said by a reader on entering the dialog and again on reading its
         * body. Not a {@code LABELLED_BY} relation either, which would keep the second node and
         * move the name's provenance to {@code LABEL}, which is the other attribute on that
         * platform. The message goes over the same way, from the field the constructor kept, and
         * a dialog built without one publishes no description rather than resolving a string to
         * find out it is empty.
         *
         * <p>What is deliberately not declared. Not {@code MODAL}: in scene the walk puts it on
         * the layer that owns input, one level up, and a second bit here would announce one fact
         * twice; as a native window, modality is the window's and stays on the window's own node.
         * No window facet: the card is never a window, in either mounting &mdash; as a scene root
         * the walk mints the window node beside it, and a second facet would be two windows for
         * one. No {@code PRESS}: Return is the default button's own verb. No key binding: the
         * package holds no localized display string for a bare Escape, and one built here would be
         * an allocation per damaged frame. Bounds, enabled, visible, showing, and the focusable
         * set are the walk's, and the card is never focusable of its own accord.
         *
         * @param a the node under construction
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            a.role(Accessible.Role.DIALOG);
            a.name(title, Accessible.NameFrom.CONTENT);
            if (message != null) {
                a.description(message);
            }
            if (answerable()) {
                a.action(Accessible.Action.CANCEL);
            }
        }

        /**
         * Dismisses the dialog when an assistive technology asks, exactly as Escape does: the
         * same private resolution with the cancel result, so the stage completes the same way and
         * the application cannot tell the two apart. Any other verb is refused, and so is a
         * dismissal once an answer is already on its way out, which is the guard the key path
         * keeps and the reason a second cancel during the fade is a truthful {@code false} rather
         * than a silently swallowed call. The describe hook reads the same {@code answerable()},
         * so through the fade the card publishes no {@code CANCEL} either (2026-09-15). With a
         * cancel handler the dismissal asks it, as Escape does, and this hook answers
         * {@code false} when the handler kept the dialog, held its request or had one held
         * already. That answer is the scene's, which has already told the platform the action was
         * taken when it posted it here: the reader hears nothing different, and the dialog does
         * nothing.
         *
         * @param verb what was asked
         * @param arg  ignored; a dismissal carries none
         * @return whether the dialog began closing
         */
        @Override
        protected boolean onAccessibilityAction(Accessible.Action verb, Accessible.Argument arg) {
            if (verb != Accessible.Action.CANCEL || !answerable()) {
                return false;
            }
            return cancel(CancelRequest.Reason.ASSISTIVE_TECHNOLOGY);
        }

        /**
         * Grows or shrinks the modal window when its content stops wanting the size the
         * window was created for: a tab switch that adds a row, say.
         *
         * <p>Three things here are load-bearing. It measures against the same loose
         * constraint presentation used, not the one arriving: a bound scene lays its root
         * out tight to the window, so the incoming constraint is the window's own size and
         * can never report that the content wants more. The resize is <b>posted</b>, since
         * this runs inside a layout pass and resizing re-enters layout; the size comparison
         * is what stops it looping. And the dialog keeps its own centre rather than being
         * re-centred on the owner, because the card can be dragged.
         */
        private void refitNativeWindow() {
            // The native test, and the first thing asked: an in-scene dialog leaves
            // here on one null check per layout pass. It reads modalWindow rather
            // than displayMode on purpose: an IN_SCENE dialog raised over a locked
            // window is promoted to a window, and that one has a window to refit
            // while still calling itself in-scene.
            if (modalWindow == null || ownerWindow == null || closing) {
                return;
            }
            SizeTokens t = Theme.of(this).tokensFor(this);
            Size budget = nativeBudget(ownerWindow);
            Size wanted = child.measure(Constraints.loose(
                    Math.min(t.dialogMaxWidth(), budget.width()), budget.height()));
            // Restore the measurement the box was assigned from, so the child is not
            // left cached against a constraint nobody laid it out at.
            child.measure(Constraints.tight(width(), height()));

            // Clamped, not just measured against: the card reports what it wants and the body
            // gave up whatever did not fit, but a content that cannot shrink (a fixed-size
            // preview) still answers larger than the budget, and the window is what must hold.
            int w = Math.max(1, Math.round(Math.min(wanted.width(), budget.width()))) + 2 * windowMargin;
            int h = Math.max(1, Math.round(Math.min(wanted.height(), budget.height()))) + 2 * windowMargin;
            int currentW = Math.round(modalWindow.logicalWidth());
            int currentH = Math.round(modalWindow.logicalHeight());
            if (w == currentW && h == currentH) {
                return;
            }
            Ui.post(() -> {
                if (modalWindow == null || closing) {
                    return; // answered while the resize was queued
                }
                float factor = modalWindow.logicalToScreenFactor();
                modalWindow.setScreenPosition(
                        modalWindow.screenX() - Math.round((w - currentW) / 2f * factor),
                        modalWindow.screenY() - Math.round((h - currentH) / 2f * factor));
                modalWindow.setSize(w, h);
                modalWindow.requestFrame();
            });
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(this);
            float w = width();
            float h = height();
            // Lands the 1pt outline on one device pixel instead of straddling two; a
            // function of the stroke, so it is 0.5 at every step, like the stroke itself.
            float inset = Strokes.HALF_PIXEL_INSET;
            // In-scene: always the glassy rounded card, composited over the scrim.
            if (displayMode == DisplayMode.IN_SCENE || style == WindowStyle.UNDECORATED_TRANSLUCENT) {
                if (displayMode == DisplayMode.IN_SCENE || windowMargin > 0) {
                    // The card of a dialog is the surface lifted furthest. Drawn in the scene it
                    // lies over the scrim and fades in with the card; in a window of its own
                    // the shadow is the compositor's, or drawn here into the window's margin
                    // where the compositor casts none.
                    theme.drawElevation(canvas, limn.graphics.RoundRect.of(0, 0, w, h, t.radiusLarge()),
                            Elevation.MODAL);
                }
                // Solid: in the scene the card's own opacity fades it, its shadow, border and
                // buttons together as one picture (see startFadeInScene), so the border no longer
                // shows through the buttons halfway; a window of its own fades through its
                // compositor opacity.
                canvas.fillRoundRectWithBorder(0, 0, w, h, t.radiusLarge(),
                        theme.surface().withAlpha(0.98f), Strokes.BORDER, theme.outline());
                return;
            }
            switch (style) {
                case UNDECORATED_OPAQUE ->
                    // Opaque solid card: the scene already cleared to surface;
                    // just a border around the borderless window.
                        canvas.drawRect(inset, inset, w - 2 * inset, h - 2 * inset,
                                Strokes.BORDER, theme.outline());
                case DECORATED -> {
                    // The OS draws the frame; the surface fill is the scene clear.
                }
                default -> {
                }
            }
        }

        /**
         * Dragging the card body (anywhere the buttons/labels do not consume the
         * press) moves the whole dialog: the native window via
         * {@link NativeWindow#setScreenPosition}, or the in-scene card via a
         * layout offset. This gives undecorated and internal dialogs a drag
         * affordance the OS title bar would otherwise provide.
         */
        @Override
        protected void onMouseEvent(MouseEvent event) {
            switch (event.type()) {
                case PRESS -> {
                    if (event.button() == Keys.MOUSE_LEFT && !closing) {
                        dragging = true;
                        grabX = event.x();
                        grabY = event.y();
                        event.consume();
                    }
                }
                case DRAG -> {
                    if (!dragging) {
                        return;
                    }
                    event.consume();
                    if (modalWindow != null) {
                        // Native window: move by the grab-point delta. As the
                        // window moves, the grab point returns under the cursor,
                        // so grabX/grabY stay fixed (classic undecorated drag).
                        float factor = modalWindow.logicalToScreenFactor();
                        int dx = Math.round((event.x() - grabX) * factor);
                        int dy = Math.round((event.y() - grabY) * factor);
                        if (dx != 0 || dy != 0) {
                            modalWindow.setScreenPosition(
                                    modalWindow.screenX() + dx, modalWindow.screenY() + dy);
                        }
                    } else if (overlay != null) {
                        // In-scene card: accumulate the cursor delta (scene
                        // coordinates do not move with the card).
                        dragOffsetX += event.x() - grabX;
                        dragOffsetY += event.y() - grabY;
                        grabX = event.x();
                        grabY = event.y();
                        overlay.placeCard();
                    }
                }
                case RELEASE -> {
                    if (dragging) {
                        dragging = false;
                        event.consume();
                    }
                }
                default -> {
                }
            }
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            answerDialogKey(event);
            // The action row is outside the body's scroll view on purpose: a card capped at the
            // work area with its footer inside the viewport is a card whose only way out can be
            // scrolled off the bottom. The cost is that a key pressed with a button focused
            // bubbles past the body rather than through it, so the body is handed them here.
            bodyScroll.scrollByKey(event);
        }
    }

    /**
     * The in-scene overlay root: a full-scene scrim that dims/blocks the content
     * behind, with the {@link DialogPanel} centered and sliding/fading in.
     * A press on the scrim (outside the card) dismisses when
     * {@linkplain #setDismissOnScrim enabled}.
     */
    private final class SceneOverlay extends Widget<SceneOverlay> {
        private final Widget<?> card;

        SceneOverlay(Widget<?> card) {
            this.card = card;
            add(card);
        }

        @Override
        protected void onKeyEvent(limn.scene.event.KeyEvent event) {
            // With nothing focused (a dialog without buttons), keys land on
            // this overlay root instead of the panel: ESC and Return must still
            // answer, matching the native-window mode.
            answerDialogKey(event);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            // Resolved on the CARD, not on this overlay nor on the container that fades the card,
            // which inherits the overlay's: the cap and the scene margin are properties of the
            // dialog, so an explicitly-stepped Dialog keeps its own width budget even though the
            // overlay itself inherits the host scene's step.
            SizeTokens t = Theme.of(this).tokensFor(panel);
            float maxW = Math.min(t.dialogMaxWidth(), width() - 2 * t.spacingLarge());
            // The same margin down the other axis, which the height had never been given: an
            // in-scene card cannot leave the window that owns it, so the window IS the bound,
            // and one that met the top and bottom edges would read as clipped rather than
            // capped. Past this the body scrolls.
            float maxH = height() - 2 * t.spacingLarge();
            Size size = card.measure(
                    new Constraints(0, Math.max(0, maxW), 0, Math.max(0, maxH)));
            card.layoutBox(cardX(size.width()), cardY(size.height()), size.width(), size.height());
        }

        /** Where the card's left edge goes: centred, then dragged. */
        private float cardX(float cardWidth) {
            return (width() - cardWidth) / 2 + dragOffsetX;
        }

        /** Where its top goes: centred, then dragged; the fade's slide is a picture the card's container moves. */
        private float cardY(float cardHeight) {
            return (height() - cardHeight) / 2 + dragOffsetY;
        }

        /**
         * Puts the card where a drag left it, for real: a dragged card is where the user put it, so
         * the accessible tree and the pointer must say so, which a picture moved would not. On the
         * drag's events only, never in a paint, and the damage of the whole overlay asks for the
         * accessible walk that publishes the new box.
         */
        void placeCard() {
            moveChild(card, cardX(card.width()), cardY(card.height()));
            invalidate();
        }

        @Override
        protected void onPaint(Canvas canvas) {
            // The palette's veil over everything below this overlay, fading in. The fade
            // scales the token's own alpha rather than replacing it, so a palette that asks
            // for a lighter veil gets a lighter one all the way through the fade.
            Color veil = Theme.of(this).scrim();
            Color faded = veil.withAlpha(veil.a() * fadeLevel());
            if (canvas instanceof limn.scene.internal.VeilCanvas veiling) {
                // Laid as a locked window's veil is: over a scene whose window is transparent
                // round what it shows, a dialog's own window round its rounded card, the veil
                // painted over put a dark square at each corner, and on an opaque window the
                // pixels are the ones painting over gives.
                veiling.fillVeil(0, 0, width(), height(), faded);
            } else {
                canvas.fillRect(0, 0, width(), height(), faded);
            }
        }

        /**
         * The scrim is material over content this layer does not own, and it is the reason this
         * class needs the seam at all.
         *
         * <p>While it is the top overlay the walk writes {@code MODAL} on it and it survives as
         * the card's parent with no code here. Buried under a second in-scene dialog or an
         * in-scene dropdown it declares nothing, is never focusable and holds no synthetic
         * children, so the transparency rule deletes it and hoists the card to the window &mdash;
         * and the guard that catches a custom gauge nobody named cannot tell a gauge from a veil:
         * it would name a toolkit class in an application's log, for a picture that says only
         * "the background is blocked", which the tree already carries as {@code MODAL} on the
         * layer above and as the enabled bit taken from everything beneath it, and it would
         * recommend the ignore flag, which on this widget deletes the dialog's own controls with
         * it.
         *
         * @return {@code true}, always
         */
        @Override
        protected boolean paintsDecoration() {
            return true;
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            // Reaching the overlay means the press missed the card: the scrim.
            if (event.type() == MouseEvent.Type.PRESS) {
                event.consume();
                if (closing) {
                    return;
                }
                if (dismissOnScrim) {
                    cancel(CancelRequest.Reason.SCRIM);
                } else if (hostScene != null && hostScene.window() != null) {
                    // Truly modal: ignore the click, but give the same feedback a
                    // native modal gives when its blocked parent is clicked.
                    BackendPeer.of(hostScene.window().backend()).signalModalBlocked();
                }
            }
        }
    }
}
