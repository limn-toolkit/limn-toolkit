package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.internal.AnchoredPopup;
import limn.components.internal.a11y.PopupOwnerAccessibility;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.RoundRect;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.internal.SceneAccess;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A panel of controls that hangs from a widget and closes when it is done with: a filter panel
 * from a tool bar's button, a column chooser, a short form. Not a widget, as {@link PopupMenu} and
 * {@link Dialog} are not: it shows one, its content, on the raised surface with the overlay's
 * shadow, below the anchor unless it does not fit there and above has more room.
 *
 * <p><b>It takes the keyboard.</b> On open the focus goes to the content's first focusable widget,
 * or, when it has none, to the popover itself, which a reader is then on; Tab and Shift+Tab cycle
 * inside it, as in a dialog; on close the focus is where it was, the anchor for a popover opened
 * from a button. It shows in a window
 * of its own where the platform allows ({@link DisplayMode#NATIVE_WINDOW}, the default, as its
 * siblings), so it can pass the window's edge; that window never takes the desktop's keyboard, and
 * the owner's keys, characters and input-method composition are delivered into it, so a field
 * inside types as any field does, a Japanese or a Chinese input method included.
 *
 * <p><b>It closes</b> on a press outside it or Escape (the user's); on the application losing the
 * keyboard, the owner's fullscreen changing, or the anchor leaving the window or no longer shown
 * (an adjustment): the anchor taken out of the tree by itself, with a container or a dialog it is
 * in, or with the window, even to be put back elsewhere in the window as a tool bar moves a
 * button into its overflow; hidden, itself or with a container, a tab strip's page or an expander;
 * and the popover it lies in closing, so a popover opened from another goes with it. The
 * keyboard, when it was in the panel, then goes where a focused widget's goes when it is taken
 * out or hidden: to nobody. And on {@link #close()} (the code's). {@link #onClose} hears each with
 * that origin. It is not modal: a press outside closes it and reaches nothing else, as a combo
 * box's list does. A question that must be answered is a
 * {@link Dialog}. It opens from {@link #showFor} or from the button {@link #attachTo} gave it, and
 * never on hover or focus: a surface that opens as the pointer passes is a tooltip.
 *
 * <p>To a reader the panel is a {@link Accessible.Role#GROUP} named by {@link #setAccessibleName},
 * or by the anchor when none is set, offering the cancel that Escape performs; an attached button
 * says it opens a popup and whether it is open, the shape a combo box's button has.
 *
 * <pre>{@code
 * Button filter = new Button("Filter").setStyle(Button.Style.SECONDARY);
 * new Popover(filterPanel).setAccessibleName("Filter orders").attachTo(filter);
 * }</pre>
 */
public final class Popover {

    /**
     * Which edge of the anchor the panel lines up with.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Alignment {
        /** The anchor's leading edge: its left reading left to right, its right reading right to left. */
        START,
        /** The anchor's trailing edge, for an anchor at the end of a row. */
        END
    }

    private final Widget<?> content;
    private DisplayMode requested = DisplayMode.NATIVE_WINDOW;
    private @Nullable DisplayMode shownAs;
    private Alignment alignment = Alignment.START;
    private @Nullable I18nString name;
    private @Nullable Consumer<Change.Origin> onClose;
    private @Nullable Button attached;
    private @Nullable Widget<?> anchor;
    private boolean open;
    private @Nullable Panel panel;
    /** The size the panel last measured to, kept for a placement asked before it has a scene. */
    private float lastWidth = 1;
    private float lastHeight = 1;
    private final AnchoredPopup popup = new AnchoredPopup(new Host());
    /** What the attached button drives: one for the popover's life, whichever button it is on. */
    private final Owner owner = new Owner();

    /**
     * A popover showing {@code content}, which the popover lays out at its own measure within the
     * room there is: a long one is the application's {@link ScrollView}.
     *
     * @param content what the panel holds; never null, and in no other parent
     */
    public Popover(Widget<?> content) {
        this.content = Objects.requireNonNull(content, "content");
    }

    /** @return what the panel holds */
    public Widget<?> content() {
        return content;
    }

    /**
     * Shows the panel against {@code anchor}. A no-op while open; nothing shows for an anchor that
     * is in no scene, is hidden, or is being taken out of its window.
     *
     * @param anchor the widget the panel hangs from
     */
    public void showFor(Widget<?> anchor) {
        Ui.checkUiThread();
        Objects.requireNonNull(anchor, "anchor");
        if (open || !AnchoredPopup.canHangFrom(anchor)) {
            return;
        }
        if (content.parent() != null) {
            // The last presentation's panel is still fading out holding it: the panel being built
            // is the one the user is looking at, so it takes the content now.
            Widget<?> holder = content.parent();
            if (holder instanceof Panel old) {
                old.drop();
            } else {
                throw new IllegalStateException("the popover's content is in another parent");
            }
        }
        this.anchor = anchor;
        this.open = true;
        popup.open(requested);
        shownAs = popup.inScene() ? DisplayMode.IN_SCENE : DisplayMode.NATIVE_WINDOW;
        if (attached != null) {
            attached.invalidateAccessible();
            attached.notifyExpanded();
        }
    }

    /** Takes the panel down; {@link #onClose} hears it as the code's. A no-op while closed. */
    public void close() {
        Ui.checkUiThread();
        closeWith(Change.Origin.CODE);
    }

    private void closeWith(Change.Origin origin) {
        if (!open) {
            return;
        }
        open = false;
        popup.close();
        if (attached != null) {
            attached.invalidateAccessible();
            attached.notifyExpanded();
        }
        if (onClose != null) {
            onClose.accept(origin);
        }
    }

    /** @return whether the panel is showing, or about to in a window being made */
    public boolean isOpen() {
        return open;
    }

    /**
     * Asks for a window of its own ({@link DisplayMode#NATIVE_WINDOW}, the default) or the owner's
     * scene ({@link DisplayMode#IN_SCENE}). A window is used only where the platform can place
     * one against the anchor and the owner is not fullscreen; elsewhere the scene is.
     *
     * @param mode the presentation asked for; never null
     * @return this popover
     */
    public Popover setDisplayMode(DisplayMode mode) {
        Ui.checkUiThread();
        this.requested = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * @return what the panel was last shown as, which is the scene where no window could be placed;
     *         the mode asked for until it has been shown
     */
    public DisplayMode displayMode() {
        return shownAs != null ? shownAs : requested;
    }

    /**
     * Lines the panel up with the anchor's leading edge ({@link Alignment#START}, the default) or
     * its trailing one.
     *
     * @param value the edge; never null
     * @return this popover
     */
    public Popover setAlignment(Alignment value) {
        Ui.checkUiThread();
        this.alignment = Objects.requireNonNull(value, "alignment");
        return this;
    }

    /** @return the edge of the anchor the panel lines up with */
    public Alignment alignment() {
        return alignment;
    }

    /**
     * Names the panel for a reader ("Filter orders"); {@code null} names it by its anchor.
     *
     * @param value the name, or {@code null}
     * @return this popover
     */
    public Popover setAccessibleName(@Nullable I18nString value) {
        Ui.checkUiThread();
        this.name = value;
        if (panel != null) {
            panel.invalidateAccessible();
        }
        return this;
    }

    /**
     * Names the panel with a fixed string; see {@link #setAccessibleName(I18nString)}.
     *
     * @param value the name, or {@code null}
     * @return this popover
     */
    public Popover setAccessibleName(@Nullable String value) {
        return setAccessibleName(value == null ? null : I18nString.literal(value));
    }

    /**
     * Hears the panel close, with who closed it: {@code USER} for a press outside or Escape,
     * {@code ADJUSTMENT} for the application losing the keyboard, the owner's fullscreen changing
     * or the anchor leaving the window (with a dialog it was in, say, or a popover it was in
     * closing), {@code CODE} for {@link #close()}.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this popover
     * @throws IllegalStateException if a handler is already registered
     */
    public Popover onClose(@Nullable Consumer<Change.Origin> handler) {
        Ui.checkUiThread();
        this.onClose = Checks.handlerSlot(onClose, handler, "Popover.onClose");
        return this;
    }

    /**
     * Makes {@code button} open the panel when pressed, and close it when pressed while it is open.
     * The button then tells a reader it opens a popup and whether it is open, and offers expand and
     * collapse in place of press.
     *
     * <p>A button opens one popover, and a popover is attached to one button: a popover already on
     * {@code button} is {@linkplain #detach() detached} from it, and this one is detached from the
     * button it was on, so {@code attachTo} on another button moves it there. Attaching it to the
     * button it is on changes nothing.
     *
     * @param button the button; never null
     * @return this popover
     */
    public Popover attachTo(Button button) {
        Ui.checkUiThread();
        Objects.requireNonNull(button, "button");
        if (attached == button) {
            return this;
        }
        // Both buttons and both popovers are settled before any handler runs: an onClose below
        // that attaches, detaches or shows a popover then starts from the end state, and what it
        // does is final.
        Button left = letGo();
        Popover previous = button.attachedPopover();
        if (previous != null) {
            previous.letGo();
        }
        attached = button;
        button.attachPopover(this);
        if (left != null) {
            closeIfOpenFrom(left);
        }
        if (previous != null) {
            previous.closeIfOpenFrom(button);
        }
        return this;
    }

    /**
     * Lets go of the button {@link #attachTo} gave it: the button opens nothing any more and is a
     * plain button again, to a reader as well. A panel open from that button closes, as the code's
     * ({@link #onClose} hears {@code CODE}); one {@linkplain #showFor shown for} another widget
     * stays. The popover still opens from {@link #showFor}, and can be attached again. A no-op when
     * it is on no button.
     *
     * <p>The popover is off the button before {@link #onClose} hears the close, so what the handler
     * does is the end state: attaching it again puts it back on a button, and showing it for the
     * button shows it as {@link #showFor} does for any widget, from a button that does not open it.
     *
     * @return this popover
     */
    public Popover detach() {
        Ui.checkUiThread();
        Button left = letGo();
        if (left != null) {
            closeIfOpenFrom(left);
        }
        return this;
    }

    /**
     * Takes this popover off its button, both sides at once and before any handler runs.
     *
     * @return the button it was on, or null
     */
    private @Nullable Button letGo() {
        Button button = attached;
        if (button == null) {
            return null;
        }
        attached = null;
        if (button.attachedPopover() == this) {
            button.attachPopover(null);
        }
        return button;
    }

    /**
     * A panel open from a button that no longer opens it closes, as the code's, and the button is
     * told it is collapsed. Not when a handler put the popover back on that button meanwhile.
     */
    private void closeIfOpenFrom(Button button) {
        if (open && anchor == button && attached != button) {
            closeWith(Change.Origin.CODE);
            button.notifyExpanded();
        }
    }

    /** @return the button {@link #attachTo} gave it, or null */
    @Nullable Button attachedButton() {
        return attached;
    }

    /** @return the attached button's popup, as the owner shape drives it */
    PopupOwnerAccessibility.Host ownerShape() {
        return owner;
    }

    /** The attached button's popup, as the owner shape drives it. */
    private final class Owner implements PopupOwnerAccessibility.Host {
        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void open() {
            if (attached != null) {
                showFor(attached);
            }
        }

        @Override
        public void close() {
            closeWith(Change.Origin.USER);
        }
    }

    // ------------------------------------------------------------------ the panel

    /** The panel's padding around the content: the medium spacing of the anchor's step. */
    private float pad() {
        Widget<?> at = anchor != null ? anchor : content;
        return Theme.of(at).tokensFor(at).spacingMedium();
    }

    /** What a reader calls the panel: the name given, else the anchor's. */
    private @Nullable String spokenName() {
        if (name != null) {
            return name.get();
        }
        if (anchor instanceof Button b && !b.text().isEmpty()) {
            return b.text();
        }
        return anchor == null ? null : SceneAccess.applicationName(anchor);
    }

    /**
     * Whether the keyboard is the popover's: somewhere inside the panel, or in the scene on the
     * layer the panel is drawn on, which holds it when the content takes none.
     */
    private boolean holdsFocusInside(@Nullable Widget<?> focused) {
        if (focused == null || panel == null) {
            return false;
        }
        for (Widget<?> w = focused; w != null; w = w.parent()) {
            if (w == panel) {
                return true;
            }
        }
        for (Widget<?> w = panel.parent(); w != null; w = w.parent()) {
            if (w == focused) {
                return true;
            }
        }
        return false;
    }

    /**
     * Moves the keyboard to the content's first focusable widget. With none, it goes to the
     * popover's node: the panel in a window of its own; in the scene the layer, which took it as
     * it was pushed.
     */
    private static void focusFirst(Panel into) {
        Widget<?> first = firstFocusable(into.content());
        if (first != null) {
            first.requestFocus();
        } else if (into.inWindow) {
            into.requestFocus();
        }
    }

    private static @Nullable Widget<?> firstFocusable(Widget<?> widget) {
        if (!widget.isVisible() || !widget.isEnabled()) {
            return null;
        }
        if (widget.isFocusable()) {
            return widget;
        }
        for (Widget<?> child : widget.children()) {
            Widget<?> found = firstFocusable(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Escape closes the panel as the user's; consumed, so nothing beneath hears it too. */
    private boolean escape(KeyEvent event) {
        if (event.key() == Keys.ESCAPE && event.isPressed()) {
            event.consume();
            closeWith(Change.Origin.USER);
            return true;
        }
        return false;
    }

    /**
     * The raised surface the content sits on: the overlay's shadow where the panel casts one, the
     * medium radius, and the medium spacing round the content.
     */
    private final class Panel extends Widget<Panel> {

        private final boolean inWindow;

        Panel(boolean inWindow) {
            this.inWindow = inWindow;
            add(content);
            // In a window of its own the panel takes the keyboard when nothing in it can: it is
            // then where Escape and a reader's focus land, the popover's named group. In the scene
            // the layer is that group, and holds the keyboard itself: a focusable panel there was
            // an unnamed node of no role under it, and the one the focus was published on.
            setFocusable(inWindow && firstFocusable(content) == null);
        }

        Widget<?> content() {
            return content;
        }

        void drop() {
            remove(content);
        }

        private boolean castsShadow() {
            return !inWindow || popup.margin() > 0;
        }

        @Override
        protected float paintOutset() {
            return castsShadow() ? Theme.of(this).elevation(Elevation.OVERLAY).reach() : 0;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            float p = pad();
            Size inner = content.measure(Constraints.loose(
                    Math.max(0, constraints.maxWidth() - 2 * p), Math.max(0, constraints.maxHeight() - 2 * p)));
            return constraints.constrain(inner.width() + 2 * p, inner.height() + 2 * p);
        }

        @Override
        protected void onLayout() {
            float p = pad();
            float w = Math.max(0, width() - 2 * p);
            float h = Math.max(0, height() - 2 * p);
            content.measure(Constraints.tight(w, h));
            content.layoutBox(p, p, w, h);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            Theme theme = Theme.of(this);
            float radius = theme.tokensFor(this).radiusMedium();
            if (castsShadow()) {
                theme.drawElevation(canvas, RoundRect.of(0, 0, width(), height(), radius), Elevation.OVERLAY);
            }
            canvas.fillRoundRectWithBorder(0, 0, width(), height(), radius, theme.surfaceRaised(),
                    Strokes.BORDER, theme.outline());
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            escape(event);
        }

        /**
         * Its fill, border and shadow are the raised surface's and tell a reader nothing: in a
         * window of its own the panel names itself, and in the scene the layer it is drawn on is
         * the popover's node, so the panel is rightly absent there and nobody is told to fix it.
         */
        @Override
        protected boolean paintsDecoration() {
            return true;
        }

        /**
         * In a window of its own the panel is the popover's node: a named group with the cancel
         * that Escape performs. In the scene the layer it sits on is that node, and the panel says
         * nothing of its own.
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            if (!inWindow) {
                return;
            }
            describe(a);
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
            if (!inWindow || action != Accessible.Action.CANCEL || !open) {
                return false;
            }
            closeWith(Change.Origin.USER);
            return true;
        }
    }

    /** The popover's node: a group, named, with CANCEL while open. */
    private void describe(Accessibility a) {
        a.role(Accessible.Role.GROUP);
        String spoken = spokenName();
        if (spoken != null) {
            a.name(spoken, spoken.hashCode(), Accessible.NameFrom.CONTENT);
        }
        if (open) {
            a.action(Accessible.Action.CANCEL);
        }
    }

    /** What the popover answers for its anchored popup. */
    private final class Host implements AnchoredPopup.Host {
        @Override
        public Widget<?> anchor() {
            return Objects.requireNonNull(anchor, "anchor");
        }

        @Override
        public Widget<?> opener() {
            return anchor();
        }

        /**
         * Below the anchor unless it does not fit there and above has more room; never taller than
         * the larger room, so a long content scrolls in the application's own scroll view.
         */
        @Override
        public AnchoredPopup.Placement place(SizeTokens t, float spaceAbove, float spaceBelow) {
            float w = lastWidth;
            float h = lastHeight;
            Panel p = panel;
            if (p != null && p.scene() != null) {
                Scene scene = anchor().scene();
                float maxW = scene != null ? scene.root().width() - 2 * AnchoredPopup.EDGE_MARGIN
                        : Float.POSITIVE_INFINITY;
                // Its own width first, unbounded, as a control in a row of them measures to its
                // step's width: a field or a combo box given a bounded width takes all of it, and
                // a panel measured against the window would be as wide as the window.
                Size size = p.measure(Constraints.loose(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY));
                if (size.width() > maxW) {
                    size = p.measure(Constraints.loose(Math.max(1, maxW), Float.POSITIVE_INFINITY));
                }
                w = size.width();
                h = size.height();
                lastWidth = w;
                lastHeight = h;
            }
            boolean above = h > spaceBelow && spaceAbove > spaceBelow;
            float room = Math.max(1, above ? spaceAbove : spaceBelow);
            return new AnchoredPopup.Placement(w, Math.min(h, room), above);
        }

        @Override
        public Widget<?> createPanel(boolean inWindow) {
            if (content.parent() instanceof Panel old) {
                old.drop();
            }
            panel = new Panel(inWindow);
            return panel;
        }

        @Override
        public void dismiss(Change.Origin origin) {
            closeWith(origin);
        }

        /** A press on the anchor or the attached button is no press elsewhere: it toggles. */
        @Override
        public boolean contains(@Nullable Widget<?> target) {
            for (Widget<?> at = target; at != null; at = at.parent()) {
                if (at == anchor || at == attached) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void key(KeyEvent event) {
            escape(event); // in the scene the layer hears what the content left; Escape closes
        }

        @Override
        public void character(CharEvent event) {
        }

        @Override
        public void describeLayer(Accessibility a) {
            a.role(Accessible.Role.GROUP);
            String spoken = spokenName();
            if (spoken != null) {
                a.name(spoken, spoken.hashCode(), Accessible.NameFrom.CONTENT);
            }
        }

        @Override
        public boolean holdsFocus() {
            return true;
        }

        @Override
        public boolean alignsTrailing() {
            return alignment == Alignment.END;
        }

        @Override
        public void presented(Widget<?> shown, @Nullable Scene popupScene) {
            if (!(shown instanceof Panel p)) {
                return;
            }
            focusFirst(p);
        }

        /** Back to the anchor when the keyboard was inside the panel as it closes in the scene. */
        @Override
        public @Nullable Widget<?> refocus() {
            Scene scene = anchor == null ? null : anchor.scene();
            return scene != null && holdsFocusInside(scene.focusedWidget()) ? anchor : null;
        }

        @Override
        public void released() {
            Panel p = panel;
            if (p != null && !open) {
                p.drop();
                panel = null;
            }
        }
    }
}
