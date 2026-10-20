package limn.components.internal;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Motion;
import limn.backend.NativeWindow;
import limn.backend.ScreenRect;
import limn.backend.WindowConfig;
import limn.backend.spi.WindowPeer;
import limn.components.DisplayMode;
import limn.components.Elevation;
import limn.components.SizeTokens;
import limn.components.Theme;
import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.graphics.Color;
import limn.graphics.Rect;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.internal.SceneAccess;
import limn.scene.layout.Opacity;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A surface that hangs from a widget inside its window: a combo box's list, a date picker's
 * calendar, the list of every tab a tab strip keeps out of sight. The work they share, done once:
 * a window of its own placed against the widget where the platform allows, the scene where it does
 * not (a window that cannot be positioned, as on Wayland, or a fullscreen owner), a fade in and
 * out, closing when the application loses the keyboard, a press lands elsewhere, the owner's
 * fullscreen changes or the widget leaves the window, the window's tree grafted into the owner's
 * so a reader never leaves it.
 *
 * <p>What is the component's own stays with it, behind {@link Host}: what the surface shows and
 * how big it is, the keys it answers, what its layer is to a reader, and its own close, with the
 * announcement that goes with it. The component calls {@link #open} and {@link #close}; the popup
 * calls {@link Host#dismiss} when it is the one that decides to close.
 *
 * <p><b>Not API</b>: see the package. UI thread only.
 */
public final class AnchoredPopup {

    /** The room kept between a popup and the edge of the work area or the scene, in points. */
    public static final float EDGE_MARGIN = 8;

    /** Where a popup goes and how big it is, as its host decides given the room it has. */
    public record Placement(float width, float height, boolean above) {
    }

    /** What the component answers for its popup. */
    public interface Host {

        /** @return the widget the popup hangs from: placed below or above it, lined up with its leading edge */
        Widget<?> anchor();

        /**
         * @return the widget the popup's content inherits its step, direction and language from,
         *         and which a reader is told opened it
         */
        Widget<?> opener();

        /**
         * @param t          the anchor's size row
         * @param spaceAbove the room above the anchor, in points, past the gap and the edge margin
         * @param spaceBelow the room below it, likewise
         * @return the popup's size and side
         */
        Placement place(SizeTokens t, float spaceAbove, float spaceBelow);

        /**
         * @param inWindow whether the panel goes in a window of its own, or in the scene
         * @return a fresh panel for this presentation
         */
        Widget<?> createPanel(boolean inWindow);

        /**
         * The component's own close, with what caused it: a press elsewhere or beside the card is
         * the user's, the application losing the keyboard, the owner's fullscreen changing or the
         * anchor leaving the window is an adjustment. The popup closes itself after this when the
         * component did not.
         *
         * @param origin who closed it
         */
        void dismiss(Change.Origin origin);

        /**
         * @param target a widget a press landed on, in the owner's window
         * @return whether it is part of the component, so the press is no dismissal
         */
        boolean contains(@Nullable Widget<?> target);

        /** A key that reached the layer in the scene; the component's keys drive the popup. */
        void key(KeyEvent event);

        /** A character that reached the layer in the scene. */
        void character(CharEvent event);

        /**
         * Says what the layer in the scene is to a reader: its role and its name. The popup adds
         * {@code CANCEL} while it is open.
         *
         * @param a the layer's node
         */
        void describeLayer(Accessibility a);

        /**
         * @return whether the component keeps the keyboard while its popup is open, as an editable
         *         combo box's field does: in the scene the layer leaves it, in a window the field's
         *         caret is shown as the popup's cursor
         */
        default boolean keepsKeyboard() {
            return false;
        }

        /**
         * @return whether, in a window of its own, the owner's keyboard is routed to the popup:
         *         for a popup opened from a widget that does not hold the keyboard, whose keys
         *         would otherwise go to whatever the owner has focused
         */
        default boolean routesKeyboard() {
            return false;
        }

        /**
         * @return whether the popup lines up with the anchor's trailing edge rather than its
         *         leading one: for an anchor at the end of a row, under which a wider popup would
         *         run out past it
         */
        default boolean alignsTrailing() {
            return false;
        }

        /**
         * @return whether the panel holds controls that take the keyboard themselves, as a
         *         popover's do: in the scene the focus moves into the layer's content, and in a
         *         window of its own the owner keeps the desktop's keyboard and its keys,
         *         characters and input-method composition are delivered into the popup's scene,
         *         whose own focus, Tab order and Escape then answer them
         */
        default boolean holdsFocus() {
            return false;
        }

        /**
         * The panel is up: in the scene, or in its window with that window's scene. Where the host
         * moves the keyboard into it.
         *
         * @param panel      the panel presented
         * @param popupScene the scene of its window, or {@code null} in the owner's scene
         */
        default void presented(Widget<?> panel, @Nullable Scene popupScene) {
        }

        /** The surface is gone for good, its fade over: what it borrowed can be taken back. */
        default void released() {
        }

        /** @return where the keyboard goes as the layer in the scene closes, or null for where it was */
        default @Nullable Widget<?> refocus() {
            return null;
        }
    }

    private final Host host;
    private boolean open;
    /**
     * The scene it was opened in, kept for its close: the anchor may have left that scene by then
     * (a pane taken out of the window with its list open), and a layer the close could no longer
     * find would stay on top of the window for good, holding its keyboard and every press.
     */
    private @Nullable Scene ownerScene;
    private @Nullable NativeWindow window;
    private @Nullable Scene popupScene;
    private @Nullable Widget<?> panel;
    private @Nullable Layer layer;
    private int margin;
    private @Nullable Subscription blur;
    private @Nullable Subscription popupBlur;
    private @Nullable Subscription presses;
    private @Nullable Subscription fullscreen;
    private @Nullable Subscription cursor;
    private @Nullable Subscription route;
    /** While its window is up: placing it again when a transform above the anchor comes to rest. */
    private @Nullable Subscription anchorMoved;
    /** While open: closing it when the anchor leaves the window, or the popup it lies in closes. */
    private @Nullable Subscription anchorLeaves;
    /** Set while it closes because its anchor left: nothing is focused on a widget on its way out. */
    private boolean anchorGone;

    public AnchoredPopup(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** @return whether it is open, the time a window takes to be made included */
    public boolean isOpen() {
        return open;
    }

    /** @return whether it is open and drawn in the scene rather than in a window of its own */
    public boolean inScene() {
        return layer != null;
    }

    /** @return its window while it is open in one, else null */
    public @Nullable NativeWindow window() {
        return window;
    }

    /** @return the scene of its window while it is open in one, else null */
    public @Nullable Scene popupScene() {
        return popupScene;
    }

    /** @return the panel of the presentation open now, or null */
    public @Nullable Widget<?> panel() {
        return panel;
    }

    /** @return the margin its window keeps around the panel for a shadow, in pixels */
    public int margin() {
        return margin;
    }

    /**
     * Opens it, in a window of its own where that was asked and the owner allows, in the scene
     * otherwise. Nothing happens with no scene: a test without a window drives the component's own
     * state. A no-op while open.
     *
     * @param requested the presentation the component was asked for
     */
    public void open(DisplayMode requested) {
        Ui.checkUiThread();
        Widget<?> anchor = host.anchor();
        Scene scene = anchor.scene();
        if (open || scene == null) {
            return;
        }
        open = true;
        ownerScene = scene;
        // Here and not in each component's onDetached, which a component that is no widget has no
        // way to hear: the anchor taken out of the window, by itself or with a container or a
        // dialog it is in, or the window closing, or the popup it lies in closing, takes this one
        // down. Left up, it would hang over the window from nothing, holding its keyboard and
        // every press, and its owner would think it open.
        anchorLeaves = SceneAccess.observeAnchorLeaving(scene, anchor, this::anchorLeft);
        // A press in another application never reaches the owner: the application losing the
        // keyboard closes the popup instead, or it would float over whatever took it.
        blur = scene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        NativeWindow owner = scene.window();
        if (requested == DisplayMode.IN_SCENE
                || (owner != null && !FloatingSurfaces.anchoredWindowAvailable(owner))) {
            presentInScene(scene);
            return;
        }
        // Any press elsewhere in the owner closes it, on a widget that takes no focus as well.
        // Only for a window: in the scene the layer takes every press, the popup's own included.
        presses = scene.observePresses(target -> {
            if (!host.contains(target)) {
                host.dismiss(Change.Origin.USER);
            }
        });
        // The owner going fullscreen or leaving it resizes it under a popup placed against where
        // the anchor was, and at a named mode a popup's window would minimize it at its first
        // click: it goes, as a platform's own does. One in the scene is laid out again instead.
        fullscreen = scene.observeWindowFullscreen(state -> host.dismiss(Change.Origin.ADJUSTMENT));
        if (host.holdsFocus()) {
            // The popup's window never takes the desktop's keyboard, as none of its siblings'
            // does: the owner's keys, characters and composition are its scene's input instead,
            // so its own focus, Tab order and Escape answer them and a field in it types as any
            // field does, an input method's composition included. Each is a batch of its own, so
            // a key that moves the focus is settled before the character after it arrives.
            route = SceneAccess.routeKeyboard(scene, new SceneAccess.KeyRoute() {
                @Override
                public void key(int key, int layoutCodePoint, boolean pressed, boolean repeat,
                                int modifiers) {
                    Scene into = popupScene;
                    if (into != null) {
                        SceneAccess.input(into).keyEvent(key, layoutCodePoint, pressed, repeat, modifiers);
                        SceneAccess.input(into).inputBatchEnded();
                    }
                }

                @Override
                public void character(int codepoint) {
                    Scene into = popupScene;
                    if (into != null) {
                        SceneAccess.input(into).charTyped(codepoint);
                        SceneAccess.input(into).inputBatchEnded();
                    }
                }

                @Override
                public void preedit(String text, int caret, int targetStart, int targetLength) {
                    Scene into = popupScene;
                    if (into != null) {
                        SceneAccess.input(into).preeditChanged(text, caret, targetStart, targetLength);
                        SceneAccess.input(into).inputBatchEnded();
                    }
                }

                @Override
                public boolean popupHoldsFocus() {
                    return true;
                }

                @Override
                public @Nullable Scene popup() {
                    return popupScene;
                }
            });
        } else if (host.routesKeyboard()) {
            // From the open on, before the window exists: a key pressed meanwhile is the popup's
            // and not the focused widget's.
            route = SceneAccess.routeKeyboard(scene, new SceneAccess.KeyRoute() {
                @Override
                public void key(int key, int layoutCodePoint, boolean pressed, boolean repeat,
                                int modifiers) {
                    host.key(new KeyEvent(key, layoutCodePoint, pressed, repeat, modifiers));
                }

                @Override
                public void character(int codepoint) {
                    host.character(new CharEvent(codepoint));
                }

                @Override
                public @Nullable Scene popup() {
                    return popupScene;
                }
            });
        }
        if (owner != null) {
            // Deferred: making a window switches the GL context, which must not happen inside
            // another window's frame.
            Ui.post(this::createWindow);
        }
    }

    private void createWindow() {
        Widget<?> anchor = host.anchor();
        Scene scene = anchor.scene();
        if (!open || window != null || scene == null || scene.window() == null
                || scene.window().isClosed()) {
            return;
        }
        NativeWindow parent = scene.window();
        if (!FloatingSurfaces.anchoredWindowAvailable(parent)) {
            // The owner went fullscreen between the open and this turn: the window it would have
            // closed is never made.
            host.dismiss(Change.Origin.ADJUSTMENT);
            return;
        }
        margin = PopupFrame.marginFor(parent, Theme.of(anchor), Elevation.OVERLAY);
        int m = margin;
        panel = host.createPanel(true);
        // Where the compositor casts no shadow for such a window, the window is larger than the
        // card by the shadow's reach and the card is laid out inset in it; a press beside the card
        // is a press outside it.
        Widget<?> root = m == 0 ? panel
                : new PopupFrame(panel, m, () -> host.dismiss(Change.Origin.USER));
        // Before the scene binds: binding measures the panel, and one that resolved the process
        // defaults there would lay out at one step, or in one direction, in a window sized at another.
        root.setInheritanceHost(host.opener());
        // On the owner's clock, so the fade-out that destroys the window advances with it.
        popupScene = new Scene(root, scene.clock());
        // Measured as the owner measures, and before the window exists: a host whose size is its
        // panel's own measure (a popover's content) is asked for it by the placement below, which
        // sizes the window, and a panel with no scene has no ruler to measure text with.
        popupScene.setTextRuler(scene.textRuler());
        popupScene.inheritRenderingFlags(scene);
        float[] box = windowBox(parent);
        window = parent.backend().createWindow(WindowConfig.popup(parent,
                Math.max(1, Math.round(box[2])) + 2 * m, Math.max(1, Math.round(box[3])) + 2 * m)
                .atInOwner(box[0] - m, box[1] - m));
        // Published by the owner's tree, under the opener, as a platform's own popup is a child of
        // its field: a reader never leaves the owner while it is open.
        scene.graftPopup(popupScene);
        if (host.keepsKeyboard()) {
            cursor = SceneAccess.showCursorOf(scene, popupScene);
        }
        popupScene.bind(window);
        // A transform above the anchor that comes to rest somewhere new moves where the anchor is
        // drawn: the window is placed again there, a turn later, outside the frame that settled it.
        anchorMoved = SceneAccess.observeAnchorMoved(scene, anchor, () -> Ui.post(() -> {
            if (window != null) {
                refit();
            }
        }));
        // A click inside may hand the popup's window the keyboard; its losing it is the same rule.
        popupBlur = popupScene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        popupScene.setBackground(Color.TRANSPARENT);
        if (parent.isVisible()) {
            popupScene.fadeWindowIn(Motion.duration(Theme.of(anchor).animWindow));
            window.show();
        }
        window.requestFrame();
        host.presented(panel, popupScene);
    }

    /**
     * The window's box in the owner's points: below the anchor or above it, as the host places it
     * given the room on the display's work area, lined up with the anchor's leading edge.
     */
    private float[] windowBox(NativeWindow parent) {
        Widget<?> anchor = host.anchor();
        SizeTokens t = Theme.of(anchor).tokensFor(anchor);
        float gap = t.popupGap();
        float factor = parent.logicalToScreenFactor();
        Rect box = anchor.localToSceneBounds(0, 0, anchor.width(), anchor.height());
        int anchorTop = parent.screenY() + Math.round(box.y() * factor);
        int anchorBottom = parent.screenY() + Math.round((box.bottom() + gap) * factor);
        ScreenRect area = parent.display() != null ? parent.display().workArea() : null;
        float spaceBelow = area != null
                ? (area.bottom() - anchorBottom) / factor - EDGE_MARGIN : Float.MAX_VALUE;
        float spaceAbove = area != null
                ? (anchorTop - area.y()) / factor - gap - EDGE_MARGIN : Float.MAX_VALUE;
        Placement p = host.place(t, spaceAbove, spaceBelow);
        float y = p.above() ? box.y() - gap - p.height() : box.bottom() + gap;
        float x = alignedX(anchor, p.width());
        return new float[] {x, y, p.width(), p.height()};
    }

    /** The left edge that lines a box of {@code width} up with the anchor's leading edge, or its trailing one. */
    private float alignedX(Widget<?> anchor, float width) {
        Rect box = anchor.localToSceneBounds(0, 0, anchor.width(), anchor.height());
        boolean fromRight = anchor.isRightToLeft() != host.alignsTrailing();
        return fromRight ? box.right() - width : box.x();
    }

    /**
     * Places the window again, for content that changed size while it is up; the layer in the
     * scene is laid out again instead.
     */
    public void refit() {
        Ui.checkUiThread();
        if (panel != null) {
            panel.markNeedsLayout();
            panel.invalidate();
        }
        if (layer != null) {
            layer.markNeedsLayout();
            layer.invalidate();
        }
        Scene scene = host.anchor().scene();
        if (window == null || scene == null || scene.window() == null) {
            return;
        }
        NativeWindow parent = scene.window();
        float[] box = windowBox(parent);
        float factor = parent.logicalToScreenFactor();
        int m = margin;
        window.setSize(Math.max(1, Math.round(box[2])) + 2 * m, Math.max(1, Math.round(box[3])) + 2 * m);
        window.setScreenPosition(parent.screenX() + Math.round((box[0] - m) * factor),
                parent.screenY() + Math.round((box[1] - m) * factor));
        window.requestFrame();
    }

    /**
     * Draws the panel in the owner's scene, on a layer that takes every press beside it, and fades
     * the panel in through an {@link Opacity} container round it: the panel and its shadow, rows,
     * highlight and icons as one picture, so nothing in it shows through what covers it halfway
     * through the fade. Round the panel and not the layer, which covers the whole scene: a picture
     * the size of the panel and a repaint of the panel's box each frame, not of the window. The
     * container is no node of the accessible tree, so the panel is read where it was.
     */
    private void presentInScene(Scene owner) {
        Widget<?> card = host.createPanel(false);
        panel = card;
        // Before the layer is pushed, for the reason a window's root takes it before binding.
        card.setInheritanceHost(host.opener());
        Opacity fading = new Opacity(card);
        layer = new Layer(fading);
        // On the layer as well, and it is the layer's that works: a host link on a widget with a
        // parent loses to the tree, and the panel has a parent inside the layer.
        layer.setInheritanceHost(host.opener());
        boolean animate = owner.window() != null && !Motion.reduced();
        if (animate) {
            fading.setOpacity(0f); // before the push, so nothing in the scene hears a fresh panel start
        }
        if (host.keepsKeyboard()) {
            SceneAccess.pushPopupLeavingKeyboard(owner, layer);
        } else {
            owner.pushPopup(layer);
        }
        if (animate) {
            fading.fadeTo(1f, Theme.of(host.anchor()).animWindow, null); // a close supersedes it
        }
        host.presented(card, null);
    }

    /**
     * Deferred a turn, so a switch of the keyboard between the owner and the popup's own window
     * settles first: either can lose it in the same batch the other gains it, and a check between
     * the two would find neither holding it.
     */
    private void closeUnlessRefocused() {
        if (!open) {
            return;
        }
        Scene scene = ownerScene;
        boolean ownerFocused = scene != null && scene.isWindowFocused();
        boolean popupFocused = popupScene != null && popupScene.isWindowFocused();
        if (!ownerFocused && !popupFocused) {
            host.dismiss(Change.Origin.ADJUSTMENT);
        }
    }

    /** Its anchor left the window: the component closes it as an adjustment, or it closes itself. */
    private void anchorLeft() {
        if (!open) {
            return;
        }
        anchorGone = true;
        try {
            host.dismiss(Change.Origin.ADJUSTMENT);
            close();
        } finally {
            anchorGone = false;
        }
    }

    /**
     * Takes it down, fading it where there is a frame pump to fade it. What it holds is let go
     * first, so one opened again while this fades builds afresh; a popup hung from a widget in its
     * panel goes first, at once. A no-op while closed.
     */
    public void close() {
        Ui.checkUiThread();
        if (!open) {
            return;
        }
        open = false;
        boolean leaving = anchorGone;
        anchorGone = false;
        // What this presentation holds comes off the fields before any application code can run:
        // the closes of the popups hung in its panel run their handlers, and a handler that shows
        // this popup again builds a presentation of its own, which nothing below then touches. Its
        // layer, taken down here instead, stayed over the window for good, owned by nothing.
        Widget<?> closingPanel = panel;
        Layer closingLayer = layer;
        NativeWindow closingWindow = window;
        Scene closingPopupScene = popupScene;
        Scene scene = ownerScene;
        Subscription[] held = {cursor, route, presses, blur, popupBlur, fullscreen, anchorMoved, anchorLeaves};
        panel = null;
        layer = null;
        window = null;
        popupScene = null;
        ownerScene = null;
        anchorLeaves = null;
        anchorMoved = null;
        cursor = null;
        route = null;
        presses = null;
        blur = null;
        popupBlur = null;
        fullscreen = null;
        for (Subscription each : held) {
            if (each != null) {
                each.cancel();
            }
        }
        if (closingPanel != null) {
            // Before this one lets go of the keyboard, as a stack unwinds: the inner popup gives it
            // back to its button in this panel, and this one then takes it home.
            SceneAccess.closePopupsHungIn(closingPanel);
        }
        if (closingWindow != null) {
            closeWindow(scene, closingWindow, closingPopupScene, closingPanel);
        } else if (closingLayer != null) {
            closeLayer(scene, closingLayer, leaving);
        }
        // else closed before its window was made: nothing was shown
    }

    private void closeWindow(@Nullable Scene scene, NativeWindow closing, @Nullable Scene closingScene,
                             @Nullable Widget<?> closingPanel) {
        // The panel's verbs are withdrawn now: a window's fade moves only its opacity and damages
        // nothing, so the walk that withdraws them has to be asked for.
        if (closingPanel != null) {
            closingPanel.invalidateAccessible();
        }
        NativeWindow parent = scene != null ? scene.window() : null;
        if (closingScene != null && closingScene.isWindowFocused()
                && parent != null && !parent.isClosed()) {
            // A click made the popup's window the key one, and a destroyed key window hands the
            // keyboard to nobody on macOS: the owner takes it back.
            parent.focus();
        }
        Runnable destroy = () -> {
            if (parent != null && !parent.isClosed()) {
                WindowPeer.of(parent).unregisterChildPopup(closing);
            }
            closing.requestClose();
            host.released();
        };
        if (closingScene != null) {
            closingScene.fadeWindowOut(Motion.duration(Theme.of(host.anchor()).animWindow), destroy);
        } else {
            destroy.run();
        }
    }

    private void closeLayer(@Nullable Scene owner, Layer closing, boolean leaving) {
        if (owner == null) {
            host.released(); // never opened in a scene: nothing holds the layer
            return;
        }
        if (owner.window() == null) {
            owner.removeOverlay(closing); // no frame pump to fade it
            host.released();
            return;
        }
        // The keyboard goes back as the fade starts, and on to where the host sends it: the fade is
        // of a card nobody is typing into any more, and the focus is never published on a layer
        // about to go.
        Widget<?> refocus = host.refocus();
        if (leaving) {
            refocus = null; // on its way out of the window: the overlay's own way back is the rule
        }
        SceneAccess.releaseOverlayKeyboard(owner, closing);
        if (refocus != null) {
            refocus.requestFocus();
        }
        // From wherever the fade in had got to, over the share of the window animation that brings
        // it back to nothing, so a list closed as it opened goes as fast as it came; on wall time:
        // this fade's arrival removes the layer that holds the pointer, and a paused application
        // must still get its field beneath back. Nothing else fades this panel, a fresh one for
        // each presentation, so nothing supersedes the fade that removes it.
        Opacity card = closing.content;
        // No time while the user asks for less motion: a fade that goes straight there keeps its
        // arrival for the seconds it asked for, and this arrival is what gives the field back.
        card.fadeTo(0f, Motion.duration(Theme.of(host.anchor()).animWindow) * card.opacity(), () -> {
            owner.removeOverlay(closing);
            host.released();
        });
    }

    /**
     * The layer a popup is drawn on in the scene. It covers the scene, so a press that missed the
     * card is a press on it, and closing on that press is the click-outside dismissal. It holds the
     * keyboard while the popup is open, unless the host keeps it, and passes the keys on.
     */
    private final class Layer extends Widget<Layer> {

        /** The panel, in the container that fades it. */
        private final Opacity content;

        Layer(Opacity content) {
            this.content = content;
            setFocusable(true);
            add(content);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(constraints.maxWidth(), constraints.maxHeight());
        }

        @Override
        protected void onLayout() {
            Widget<?> anchor = host.anchor();
            SizeTokens t = Theme.of(this).tokensFor(anchor);
            float gap = t.popupGap();
            // Read after the root was laid out: the scene lays overlays out last, so the anchor's
            // place here is this frame's.
            Rect box = anchor.localToSceneBounds(0, 0, anchor.width(), anchor.height());
            float top = box.y();
            float bottom = box.bottom() + gap;
            Placement p = host.place(t, top - gap - EDGE_MARGIN, height() - bottom - EDGE_MARGIN);
            float w = Math.min(p.width(), width());
            float h = Math.min(p.height(), height());
            float x = Math.max(0, Math.min(alignedX(anchor, w), width() - w));
            float y = p.above() ? top - gap - h : bottom;
            y = Math.max(0, Math.min(y, height() - h));
            content.measure(Constraints.tight(w, h));
            content.layoutBox(x, y, w, h);
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            host.key(event);
        }

        @Override
        protected void onCharTyped(CharEvent event) {
            host.character(event);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            // Only presses that missed the card arrive here.
            if (event.type() == MouseEvent.Type.PRESS) {
                if (open && layer == this) {
                    host.dismiss(Change.Origin.USER);
                }
                event.consume();
            }
        }

        /**
         * Not scaffolding: while the popup is open this layer is the scene's focused widget and its
         * only tab stop, unless the host keeps the keyboard, so it says what it is through its host,
         * and offers the cancel that a press on it and Escape perform, only while it is open.
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            host.describeLayer(a);
            if (open && layer == this) {
                a.action(Accessible.Action.CANCEL);
            }
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action action, Accessible.Argument arg) {
            if (action != Accessible.Action.CANCEL || !open || layer != this) {
                return false;
            }
            host.dismiss(Change.Origin.USER);
            return true;
        }
    }
}
