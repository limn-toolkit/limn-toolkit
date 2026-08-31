package limn.components;

import limn.backend.Backend;
import limn.backend.Display;
import limn.backend.NativeWindow;
import limn.backend.ScreenRect;
import limn.backend.WindowConfig;
import limn.backend.WindowStyle;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.layout.Flex;
import limn.scene.layout.Row;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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
 *       enforced by the backend (locked windows dim and ignore input).</li>
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
 * <h2>Stacking</h2>
 * Dialogs stack in either mode and in any combination: the newest is the one the user
 * answers, everything under it is frozen until it closes, and answering the top one
 * hands control back to the one below.
 *
 * <p>One exception, and it changes the display mode: an {@code IN_SCENE} dialog raised
 * over a window that a modal has <b>already</b> locked is presented as a native window
 * instead, with a warning naming the dialog. An overlay is drawn inside its host window,
 * so it can only come forward by bringing that window forward, which would hide the
 * dialog already floating over it. {@link #keepInScene()} declines the promotion.
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
 *     .addButton("Cancel", "cancel")
 *     .addPrimaryButton("Delete", "delete")
 *     .show(scene)
 *     .thenAccept(result -> { if ("delete".equals(result)) ...; });
 * }</pre>
 */
public final class Dialog {

    private static final System.Logger LOG = System.getLogger(Dialog.class.getName());

    /**
     * Motion choreography, deliberately <b>not</b> a size token: 14pt of travel reads the
     * same regardless of how big the card is, and scaling it would make an XLARGE dialog
     * feel sluggish for the identical animation duration.
     */
    private static final float SLIDE_DISTANCE = 14;

    private final I18nString title;
    private final DialogPanel panel;
    /** Title, message and the application's widget: the part that scrolls when the card is capped. */
    private final TokenColumn body;
    private final ScrollView bodyScroll;
    private final ActionRow buttonRow;
    private Widget custom;
    private final CompletableFuture<String> result = new CompletableFuture<>();

    private DisplayMode displayMode = DisplayMode.NATIVE_WINDOW;
    private WindowStyle style = WindowStyle.UNDECORATED_TRANSLUCENT;
    private String cancelResult;
    /**
     * What Return resolves the dialog with, and whether there is a button to resolve it: two
     * fields because null is a legitimate result and cannot stand for "no default button".
     */
    private String defaultResult;
    private boolean hasDefaultButton;
    private boolean alwaysOnTop = true;
    private boolean dismissOnScrim; // default false: modal (ignore + beep), like a native modal
    private float fade;
    private boolean presented; // single-use: any show* after the first throws
    private boolean closing;
    private boolean modal = true;
    private boolean keepInScene; // the app has vouched for IN_SCENE; never promote
    // Unregisters the "died unanswered" observer once a real answer is on its way.
    private Runnable unanswered;

    // Native-window presentation state (null when in-scene / headless).
    private NativeWindow modalWindow;
    private NativeWindow ownerWindow;
    private Scene modalScene;

    // Where the card's step is inherited from, when the tree cannot say: the widget passed
    // to a show(Widget) overload, else the owner scene's root. Installed as a host link on
    // the parentless root of the card's subtree at presentation time.
    private Widget inheritanceHost;

    // In-scene presentation state (null when native-window / headless).
    private Scene hostScene;
    private SceneOverlay overlay;
    private Backend.SceneModalHandle sceneModal; // blocks sibling windows while open
    // Drag offset of the in-scene card from its centered position.
    private float dragOffsetX;
    private float dragOffsetY;

    /** A dialog with fixed text; see the {@link I18nString} constructor for localized text. */
    public Dialog(String title, String message) {
        this(I18nString.literal(Objects.requireNonNull(title, "title")),
                I18nString.literal(Objects.requireNonNull(message, "message")));
    }

    /** A dialog whose title and message follow the UI language; see {@link I18nString}. */
    public Dialog(I18nString title, I18nString message) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
        this.title = title;
        // NOTHING here may capture a size: a widget has no parent while it is being built,
        // so a step read now resolves to the process default no matter what the eventual
        // owner declares, and a Dialog is single-use, so there is no re-show that could
        // ever correct it. Hence three self-resolving containers instead of three literals
        // pulled off the theme, and a typographic ROLE on the title instead of a Font
        // (setFont wins over the step forever, which is how a LARGE dialog used to render
        // a MEDIUM title with no error and no obvious cause).
        body = new TokenColumn(Tokens.Role.MEDIUM);
        body.crossAlignment(Flex.CrossAlignment.STRETCH);
        body.add(new Label(title).setRole(Label.Role.TITLE));
        if (!message.get().isEmpty()) {
            body.add(new Label(message).setWrap(true).setMuted(true));
        }
        buttonRow = new ActionRow();
        buttonRow.mainAlignment(Flex.MainAlignment.END)
                .crossAlignment(Flex.CrossAlignment.CENTER);

        // Vertical only. A card that scrolled sideways would be one whose buttons can be
        // scrolled out of reach of a pointer that never learned there was anything to the
        // right; the width is capped by dialogMaxWidth instead, which cannot hide anything.
        bodyScroll = new ScrollView(body, false, true);

        panel = new DialogPanel(new TokenPadding(Tokens.Role.LARGE,
                new CardColumn(bodyScroll, buttonRow)));
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
     */
    public Dialog setContent(Widget widget) {
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

    /** Adds a secondary (outlined) button that resolves the dialog with {@code result}. */
    public Dialog addButton(String text, String resultValue) {
        return addButton(I18nString.literal(text), resultValue, false);
    }

    /** A secondary button whose caption follows the UI language. */
    public Dialog addButton(I18nString text, String resultValue) {
        return addButton(text, resultValue, false);
    }

    /**
     * Adds a primary (filled) button that resolves the dialog with {@code result}.
     *
     * <p>The first one added is also the card's <b>default button</b>: Return resolves the dialog
     * with its result whenever the focused widget did not want the key itself, so Return in a
     * text field answers the dialog, while Return on a focused button presses that button and
     * Return in a {@code TextArea} still inserts a newline.
     */
    public Dialog addPrimaryButton(String text, String resultValue) {
        return addButton(I18nString.literal(text), resultValue, true);
    }

    /** A primary button whose caption follows the UI language. */
    public Dialog addPrimaryButton(I18nString text, String resultValue) {
        return addButton(text, resultValue, true);
    }

    private Dialog addButton(I18nString text, String resultValue, boolean primary) {
        Ui.checkUiThread();
        Button button = new Button(text).setSecondary(!primary);
        button.onAction(() -> resolve(resultValue));
        buttonRow.add(button);
        if (primary && !hasDefaultButton) {
            // The first primary button is the default one, and its filled style is the whole
            // affordance: every platform draws the button Return activates differently from the
            // rest, and this card already does.
            hasDefaultButton = true;
            defaultResult = resultValue;
        }
        return this;
    }

    /** Sets the result delivered on ESC / scrim dismiss (default {@code null}). */
    public Dialog setCancelResult(String value) {
        this.cancelResult = value;
        return this;
    }

    /**
     * Declares the size step for the whole card, overriding the inherited one: the only
     * size knob a dialog has, and a <em>forwarder</em> rather than an override because a
     * {@code Dialog} is not a {@link Widget}: it lands on the card's root, which is what
     * every control inside the dialog then inherits, in both display modes.
     *
     * <p>{@code null} restores inheritance from the owner scene. UI thread only.
     */
    public Dialog setControlSize(ControlSize size) {
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
     * <p><b>A preference, not a guarantee.</b> An {@code IN_SCENE} dialog raised while a modal
     * is already open over its window is presented as a native one instead; an overlay can only
     * be brought to the front by raising its host, which would hide the dialog already there.
     * {@link #displayMode()} answers what actually happened; {@code keepInScene()} insists.
     */
    public Dialog setDisplayMode(DisplayMode mode) {
        this.displayMode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * How this dialog is <em>actually</em> presented: the requested mode until {@link #show}
     * decides otherwise, and the effective one afterwards.
     *
     * <p>Worth asking after {@code show} by anything that styles or positions the card on the
     * assumption that it is an overlay: a promoted dialog is a real window, so {@code setStyle}
     * starts meaning something and an in-scene drag affordance stops. Until this existed the
     * promotion was announced only in a log line, which no application reads.
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
     */
    public Dialog setAlwaysOnTop(boolean value) {
        this.alwaysOnTop = value;
        return this;
    }

    /**
     * Whether clicking the scrim dismisses the dialog (default {@code false}).
     * Only applies to {@link DisplayMode#IN_SCENE}. When {@code false} the dialog
     * is truly modal: a scrim click is ignored and answered with the same alert
     * feedback (beep) a native modal gives; when {@code true} it closes with the
     * {@linkplain #setCancelResult cancel result} (a dismissable overlay).
     */
    public Dialog setDismissOnScrim(boolean value) {
        this.dismissOnScrim = value;
        return this;
    }

    /**
     * Sets the native window framing (default {@link WindowStyle#UNDECORATED_TRANSLUCENT}):
     * {@code DECORATED} uses the OS title bar/border (opaque);
     * {@code UNDECORATED_OPAQUE} is a borderless solid card; the default is a
     * borderless glassy panel with rounded, see-through corners. Ignored in
     * {@link DisplayMode#IN_SCENE} (the internal card is always the glassy panel).
     */
    public Dialog setStyle(WindowStyle newStyle) {
        this.style = Objects.requireNonNull(newStyle, "newStyle");
        return this;
    }

    /** Programmatically closes the dialog, resolving with {@code result}. UI thread only. */
    public void dismiss(String result) {
        resolve(result);
    }

    /** @return the stage that completes when the dialog closes (same as {@link #show}) */
    public CompletionStage<String> result() {
        return result;
    }

    /** @return the current fade level in [0..1] (tests/screenshots) */
    public float fadeLevel() {
        return fade;
    }

    /** @return the modal's native window while open (screenshots/tests), else null */
    public NativeWindow modalWindow() {
        return modalWindow;
    }

    /** The panel widget tree; headless tests drive it directly (buttons/ESC/resolve). */
    Widget contentRoot() {
        return panel;
    }

    // --------------------------------------------------------------- showing

    /**
     * Shows the dialog window-modal over {@code owner} (locks only its window),
     * or, in {@link DisplayMode#IN_SCENE}, a scene-modal internal overlay.
     *
     * @throws IllegalStateException if this dialog was already shown (dialogs are single-use)
     */
    public CompletionStage<String> show(Scene owner) {
        return displayMode == DisplayMode.IN_SCENE
                ? presentInScene(owner, false)
                : presentNative(owner, true, owner.window());
    }

    /**
     * Shows the dialog toolkit-modal over {@code owner} (locks every window). In
     * {@link DisplayMode#IN_SCENE} an overlay cannot lock other native windows,
     * so it presents the same scene-modal internal overlay as {@link #show}.
     *
     * @throws IllegalStateException if this dialog was already shown (dialogs are single-use)
     */
    public CompletionStage<String> showToolkitModal(Scene owner) {
        return displayMode == DisplayMode.IN_SCENE
                ? presentInScene(owner, true)
                : presentNative(owner, true, null);
    }

    /**
     * Shows the dialog <b>non-modal</b> over {@code owner}: a floating window
     * that locks nothing, so the owner (and every other window) stays fully
     * interactive. Only valid in {@link DisplayMode#NATIVE_WINDOW}; an in-scene
     * overlay always captures its scene's input.
     *
     * @throws IllegalStateException if the display mode is {@link DisplayMode#IN_SCENE},
     *                               or if this dialog was already shown (dialogs are single-use)
     */
    public CompletionStage<String> showNonModal(Scene owner) {
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
     * @throws IllegalStateException if {@code owner} is not in a scene, or the dialog was already shown
     */
    public CompletionStage<String> show(Widget owner) {
        Scene scene = adoptSizeHost(owner);
        return displayMode == DisplayMode.IN_SCENE
                ? presentInScene(scene, false)
                : presentNative(scene, true, scene.window());
    }

    /** {@link #showToolkitModal(Scene)}, inheriting the size step from {@code owner}. */
    public CompletionStage<String> showToolkitModal(Widget owner) {
        Scene scene = adoptSizeHost(owner);
        return displayMode == DisplayMode.IN_SCENE
                ? presentInScene(scene, true)
                : presentNative(scene, true, null);
    }

    /** {@link #showNonModal(Scene)}, inheriting the size step from {@code owner}. */
    public CompletionStage<String> showNonModal(Widget owner) {
        Scene scene = adoptSizeHost(owner);
        if (displayMode == DisplayMode.IN_SCENE) {
            throw new IllegalStateException(
                    "non-modal dialogs require DisplayMode.NATIVE_WINDOW "
                            + "(an in-scene overlay always captures input)");
        }
        return presentNative(scene, false, null);
    }

    /** Records {@code owner} as the size-inheritance host and returns the scene to present in. */
    private Scene adoptSizeHost(Widget owner) {
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
     * The widget the card's inherited axes resolve through when its own root is parentless:
     * whatever a {@code show(Widget)} overload recorded, else the owner scene's root.
     */
    private Widget inheritanceHostFor(Scene owner) {
        return inheritanceHost != null ? inheritanceHost : owner.root();
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
        panel.setInheritanceHost(inheritanceHostFor(owner));
        // A scene gives the panel a text ruler so it can be measured to size
        // the window; it is then bound to the modal window.
        modalScene = new Scene(panel);
        modalScene.inheritRenderingFlags(owner); // partial/debug follow the owner window
        // Opaque styles fill with the surface color; translucent shows the
        // desktop through the transparent framebuffer's rounded corners.
        Theme theme = Theme.current();
        modalScene.setBackground(style.transparent() ? Color.TRANSPARENT : theme.surface);
        // Resolved once, after the host link: the window is sized from this row and the
        // content must be laid out from the same one.
        SizeTokens t = theme.tokensFor(panel);
        // Measured against what the SCREEN allows, not what the owner happens to be: a card
        // taller than the work area is one whose buttons are behind the dock. The body scrolls
        // inside whatever this leaves it.
        Size budget = nativeBudget(window);
        Size panelSize = panel.measure(Constraints.loose(
                Math.min(t.dialogMaxWidth(), budget.width()), budget.height()));
        int w = Math.max(1, Math.round(Math.min(panelSize.width(), budget.width())));
        int h = Math.max(1, Math.round(Math.min(panelSize.height(), budget.height())));

        // Floating (optional) focus-stealing modal window in the requested style.
        modalWindow = backend.createWindow(
                WindowConfig.styled(title.get(), w, h, style, alwaysOnTop, true));
        // OWNED_WINDOW, not transient: this closes and moves with the owner like a dropdown,
        // but it is a window in its own right, and an in-scene modal over the owner must lock it
        // the way a native modal would. Registering it as transient is what let a non-modal
        // dialog stay clickable under a modal.
        window.registerChildPopup(modalWindow, limn.backend.NativeWindow.PopupKind.OWNED_WINDOW);
        modalScene.bind(modalWindow);

        // Center over the owner window.
        float factor = window.logicalToScreenFactor();
        int sx = window.screenX() + Math.round((window.logicalWidth() - w) / 2f * factor);
        int sy = window.screenY() + Math.round((window.logicalHeight() - h) / 2f * factor);
        modalWindow.setScreenPosition(sx, sy);

        // Show before pushing the modal so it can take native focus (offscreen
        // screenshot mode keeps it hidden and captures via captureNextFrame).
        if (window.isVisible()) {
            // Whole-window compositor fade, uniform across decorated, opaque and
            // translucent styles (the OS frame fades too). Snap transparent first.
            modalScene.fadeWindowIn(Theme.current().animWindow);
            modalWindow.show();
        }
        if (modal) {
            backend.pushModal(modalWindow, blockParent);
        }
        modalScene.focusTraverse(false); // focus the first button
        fade = 1; // content painted opaque; the window opacity does the fading
        observeUnanswered(modalScene); // the OS can close this window out from under us
        modalWindow.requestFrame();
        return result;
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

    private void closeWindow() {
        if (modalWindow == null) {
            return;
        }
        if (modal) {
            modalWindow.backend().popModal(modalWindow);
        }
        if (ownerWindow != null && !ownerWindow.isClosed()) {
            ownerWindow.unregisterChildPopup(modalWindow);
        }
        modalWindow.requestClose();
        modalWindow = null;
        // Re-activate the parent window now that the dialog is gone, unless
        // another modal still owns it (then that modal keeps focus).
        if (ownerWindow != null && !ownerWindow.isClosed() && !ownerWindow.isModalBlocked()) {
            ownerWindow.focus();
        }
    }

    // -------------------------------------------------------------- in-scene

    /**
     * Presents in-scene, unless the host is already locked by a modal, in which
     * case this dialog becomes a native window instead and says so.
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
        if (!keepInScene && host != null && host.isModalBlocked()) {
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
        this.hostScene = owner;
        overlay = new SceneOverlay(panel);
        // The host link goes on the OVERLAY, not on the panel: the panel's parent is the
        // overlay here, and a host link on a widget that has a parent is ignored (the tree
        // wins). The overlay is the parentless one (pushOverlay sets the scene but never a
        // parent), so it is where the chain has to be reattached, before anything measures.
        overlay.setInheritanceHost(inheritanceHostFor(owner));
        // pushOverlay captures input, confines focus and focuses the first button.
        owner.pushOverlay(overlay);
        // The overlay only blocks the owner's own scene; register a backend
        // scene-modal so the rest of the application is locked too, matching a
        // native modal: any dialog already open falls behind this one, and for
        // toolkit scope every other window locks. The owner keeps its own popups:
        // this card's dropdowns and menus are windows owned by the owner.
        if (host != null) {
            sceneModal = host.backend().pushSceneModal(host, toolkitScope);
        }
        observeUnanswered(owner); // the host window can die with this still open
        startFadeInScene();
        return result;
    }

    private void startFadeInScene() {
        // Wall time: a dialog is shell, and its twin fade-out completes the result future.
        hostScene.addRealTimeTicker(dt -> {
            fade = (float) Math.min(1, fade + dt / Theme.current().animWindow);
            if (overlay != null) {
                overlay.invalidate();
            }
            return fade < 1;
        });
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
     * Return pressed in a text field, on a checkbox, or with nothing focused at all, which on
     * every desktop platform means the default button.
     *
     * <p>Without this a dialog whose body holds a text field could not be answered from the
     * keyboard at all: initial focus lands on the field, the field ignores Return, and the card
     * knew only ESC.
     */
    private void answerDialogKey(KeyEvent event) {
        if (!event.isPressed() || closing) {
            return;
        }
        if (event.key() == Keys.ESCAPE) {
            event.consume();
            resolve(cancelResult);
        } else if (event.key() == Keys.ENTER && hasDefaultButton) {
            event.consume();
            resolve(defaultResult);
        }
    }

    /**
     * Completes the dialog with the cancel result if its surface is destroyed
     * while the dialog is still open, registered at presentation, in both display
     * modes.
     *
     * <p>A dialog is normally answered by a button, ESC or the scrim, all of which
     * run {@link #resolve}. Its window can also simply die: an OS close button on a
     * {@link WindowStyle#DECORATED} dialog, or the owner window going away and
     * taking its registered popups with it. Frames stop at that instant, and every
     * completion path here is driven by a fade, so without this the future is
     * abandoned and a caller that does its cleanup in {@code thenAccept} silently
     * never does it. Cancel is the honest answer: nobody chose anything.
     */
    private void observeUnanswered(Scene scene) {
        unanswered = scene.observeWindowClosed(() -> {
            unanswered = null;
            if (closing) {
                return; // an answer is already on its way out; it owns the result
            }
            closing = true;
            // The window is already destroyed and the backend has dropped it from
            // the modal stack, so there is nothing here to pop, close or unhook:
            // only fields to stop pointing at the dead.
            modalWindow = null;
            overlay = null;
            sceneModal = null;
            result.complete(cancelResult);
        });
    }

    private void resolve(String value) {
        Ui.checkUiThread();
        if (closing) {
            return;
        }
        closing = true;
        if (unanswered != null) {
            // An answer arrived: this dialog resolves through the fade path below,
            // which registers its own close flush. Dropping the observer matters for
            // in-scene dialogs, whose host scene outlives them: one leaked Runnable
            // per dialog shown, otherwise.
            unanswered.run();
            unanswered = null;
        }
        if (modalWindow != null) {
            // Fade the whole modal window out, then tear it down + resolve.
            modalScene.fadeWindowOut(Theme.current().animWindow, () -> {
                closeWindow();
                result.complete(value); // fully gone before callbacks run
            });
        } else if (overlay != null && hostScene != null) {
            // The fade ticker advances only while frames render: if the host
            // window dies mid-fade, finish immediately or the result future
            // never completes (the native path gets this same flush from
            // fadeWindowOut's windowClosed handling).
            Runnable unhook = hostScene.observeWindowClosed(() -> {
                closeOverlay();
                result.complete(value);
            });
            // Wall time, and load-bearing: this ticker's last frame is what removes the overlay
            // and completes `result`. On scene time a paused app could never close a dialog.
            hostScene.addRealTimeTicker(dt -> {
                fade = (float) Math.max(0, fade - dt / Theme.current().animWindow);
                if (overlay != null) {
                    overlay.invalidate();
                }
                if (fade > 0) {
                    return true;
                }
                unhook.run();
                closeOverlay();
                result.complete(value);
                return false;
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
     */
    private static final class ActionRow extends Row {
        @Override
        protected Size onMeasure(Constraints constraints) {
            // Silent form: we are already inside the pass that consumes the gap, and a
            // markNeedsLayout() from within onMeasure dirties the whole ancestor chain with
            // no pass scheduled to clear it (the layoutPass flag is reset afterwards).
            gapSilently(Theme.current().tokensFor(this).gapButtonRow());
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
     */
    private final class CardColumn extends Widget {

        private final ScrollView scroll;
        private final Widget footer;

        CardColumn(ScrollView scroll, Widget footer) {
            this.scroll = scroll;
            this.footer = footer;
            add(scroll);
            add(footer);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
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
            // Re-derived rather than remembered from the measure: an in-scene card is laid out
            // again on every paint to follow the fade slide, without a measure in between.
            float gap = Tokens.spacingFor(this, Tokens.Role.MEDIUM);
            float footerHeight = footer.measure(
                    Constraints.loose(width(), Constraints.UNBOUNDED_LIMIT)).height();
            float bodyHeight = Math.max(0, height() - gap - footerHeight);
            scroll.layoutBox(0, 0, width(), bodyHeight);
            footer.layoutBox(0, bodyHeight + gap, width(), footerHeight);
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
    private final class DialogPanel extends Widget {
        private final Widget child;
        private boolean dragging;
        private float grabX;
        private float grabY;

        DialogPanel(Widget child) {
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
            SizeTokens t = Theme.current().tokensFor(this);
            Size budget = nativeBudget(ownerWindow);
            Size wanted = child.measure(Constraints.loose(
                    Math.min(t.dialogMaxWidth(), budget.width()), budget.height()));
            // Restore the measurement the box was assigned from, so the child is not
            // left cached against a constraint nobody laid it out at.
            child.measure(Constraints.tight(width(), height()));

            // Clamped, not just measured against: the card reports what it wants and the body
            // gave up whatever did not fit, but a content that cannot shrink (a fixed-size
            // preview) still answers larger than the budget, and the window is what must hold.
            int w = Math.max(1, Math.round(Math.min(wanted.width(), budget.width())));
            int h = Math.max(1, Math.round(Math.min(wanted.height(), budget.height())));
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
            Theme theme = Theme.current();
            SizeTokens t = theme.tokensFor(this);
            float w = width();
            float h = height();
            // Lands the 1pt outline on one device pixel instead of straddling two; a
            // function of the stroke, so it is 0.5 at every step, like the stroke itself.
            float inset = Strokes.HALF_PIXEL_INSET;
            // In-scene: always the glassy rounded card, composited over the scrim.
            if (displayMode == DisplayMode.IN_SCENE || style == WindowStyle.UNDECORATED_TRANSLUCENT) {
                canvas.fillRoundRect(0, 0, w, h, t.radiusLarge(),
                        theme.surface.withAlpha(0.98f * fade));
                canvas.drawRoundRect(inset, inset, w - 2 * inset, h - 2 * inset, t.radiusLarge(),
                        Strokes.BORDER, theme.outline.withAlpha(fade));
                return;
            }
            switch (style) {
                case UNDECORATED_OPAQUE ->
                    // Opaque solid card: the scene already cleared to surface;
                    // just a border around the borderless window.
                        canvas.drawRect(inset, inset, w - 2 * inset, h - 2 * inset,
                                Strokes.BORDER, theme.outline);
                case DECORATED -> {
                    // The OS draws the frame; the surface fill is the scene clear.
                }
                default -> {
                }
            }
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            // In a finally: the whole dialog subtree paints inside this one save, so anything
            // throwing anywhere in it would leave the frame both unbalanced AND at the wrong
            // opacity for whatever painted next.
            canvas.save();
            try {
                canvas.setOpacity(fade);
                super.paintChildren(canvas);
            } finally {
                canvas.restore();
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
                        overlay.invalidate();
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
    private final class SceneOverlay extends Widget {
        private final Widget card;

        SceneOverlay(Widget card) {
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
            // Resolved on the CARD, not on this overlay: the cap and the scene margin are
            // properties of the dialog, so an explicitly-stepped Dialog keeps its own width
            // budget even though the overlay itself inherits the host scene's step. Called
            // from paintChildren too (the fade slide), which is safe because resolution is a
            // memo read plus an array index, never a re-measure.
            SizeTokens t = Theme.current().tokensFor(card);
            float maxW = Math.min(t.dialogMaxWidth(), width() - 2 * t.spacingLarge());
            // The same margin down the other axis, which the height had never been given: an
            // in-scene card cannot leave the window that owns it, so the window IS the bound,
            // and one that met the top and bottom edges would read as clipped rather than
            // capped. Past this the body scrolls.
            float maxH = height() - 2 * t.spacingLarge();
            Size size = card.measure(
                    new Constraints(0, Math.max(0, maxW), 0, Math.max(0, maxH)));
            float px = (width() - size.width()) / 2 + dragOffsetX;
            float py = (height() - size.height()) / 2 + (1 - fade) * SLIDE_DISTANCE + dragOffsetY;
            card.layoutBox(px, py, size.width(), size.height());
        }

        @Override
        protected void onPaint(Canvas canvas) {
            // The palette's veil over everything below this overlay, fading in. The fade
            // scales the token's own alpha rather than replacing it, so a palette that asks
            // for a lighter veil gets a lighter one all the way through the fade.
            Color veil = Theme.current().scrim;
            canvas.fillRect(0, 0, width(), height(), veil.withAlpha(veil.a() * fade));
        }

        @Override
        protected void paintChildren(Canvas canvas) {
            onLayout(); // re-place the card for the current fade slide
            super.paintChildren(canvas);
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
                    resolve(cancelResult);
                } else if (hostScene != null && hostScene.window() != null) {
                    // Truly modal: ignore the click, but give the same feedback a
                    // native modal gives when its blocked parent is clicked.
                    hostScene.window().backend().signalModalBlocked();
                }
            }
        }
    }
}
