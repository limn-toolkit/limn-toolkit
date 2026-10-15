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
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Scene;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.CharEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.internal.SceneAccess;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A surface that hangs from a widget inside its window: a combo box's list, a date picker's
 * calendar, the list of every tab a tab strip keeps out of sight. The work they share, done once:
 * a window of its own placed against the widget where the platform allows, the scene where it does
 * not (a window that cannot be positioned, as on Wayland, or a fullscreen owner), a fade in and
 * out, closing when the application loses the keyboard, a press lands elsewhere or the owner's
 * fullscreen changes, the window's tree grafted into the owner's so a reader never leaves it.
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
         * the user's, the application losing the keyboard or the owner's fullscreen changing is an
         * adjustment.
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
    private @Nullable NativeWindow window;
    private @Nullable Scene popupScene;
    private @Nullable Widget<?> panel;
    private @Nullable Layer layer;
    /** How far the layer in the scene has faded in, 0 to 1; a window fades itself. */
    private float fade = 1;
    private int margin;
    private @Nullable Subscription blur;
    private @Nullable Subscription popupBlur;
    private @Nullable Subscription presses;
    private @Nullable Subscription fullscreen;
    private @Nullable Subscription cursor;

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

    /** @return how far the layer in the scene has faded in, which its panel paints with; 1 in a window */
    public float fade() {
        return layer != null ? fade : 1;
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
        float[] box = windowBox(parent);
        margin = PopupFrame.marginFor(parent, Theme.of(anchor), Elevation.OVERLAY);
        int m = margin;
        window = parent.backend().createWindow(WindowConfig.popup(parent,
                Math.max(1, Math.round(box[2])) + 2 * m, Math.max(1, Math.round(box[3])) + 2 * m)
                .atInOwner(box[0] - m, box[1] - m));
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
        popupScene.inheritRenderingFlags(scene);
        // Published by the owner's tree, under the opener, as a platform's own popup is a child of
        // its field: a reader never leaves the owner while it is open.
        scene.graftPopup(popupScene);
        if (host.keepsKeyboard()) {
            cursor = SceneAccess.showCursorOf(scene, popupScene);
        }
        popupScene.bind(window);
        // A click inside may hand the popup's window the keyboard; its losing it is the same rule.
        popupBlur = popupScene.observeWindowBlur(() -> Ui.post(this::closeUnlessRefocused));
        popupScene.setBackground(Color.TRANSPARENT);
        if (parent.isVisible()) {
            popupScene.fadeWindowIn(Motion.duration(Theme.of(anchor).animWindow));
            window.show();
        }
        window.requestFrame();
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
        float top = anchor.localToSceneY();
        int anchorTop = parent.screenY() + Math.round(top * factor);
        int anchorBottom = parent.screenY() + Math.round((top + anchor.height() + gap) * factor);
        ScreenRect area = parent.display() != null ? parent.display().workArea() : null;
        float spaceBelow = area != null
                ? (area.bottom() - anchorBottom) / factor - EDGE_MARGIN : Float.MAX_VALUE;
        float spaceAbove = area != null
                ? (anchorTop - area.y()) / factor - gap - EDGE_MARGIN : Float.MAX_VALUE;
        Placement p = host.place(t, spaceAbove, spaceBelow);
        float y = p.above() ? top - gap - p.height() : top + anchor.height() + gap;
        float x = leadingX(anchor, p.width());
        return new float[] {x, y, p.width(), p.height()};
    }

    /** The left edge that lines a box of {@code width} up with the anchor's leading edge. */
    private static float leadingX(Widget<?> anchor, float width) {
        float left = anchor.localToSceneX();
        return anchor.isRightToLeft() ? left + anchor.width() - width : left;
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

    /** Draws the panel in the owner's scene, on a layer that takes every press beside it. */
    private void presentInScene(Scene owner) {
        panel = host.createPanel(false);
        // Before the layer is pushed, for the reason a window's root takes it before binding.
        panel.setInheritanceHost(host.opener());
        layer = new Layer(panel);
        // On the layer as well, and it is the layer's that works: a host link on a widget with a
        // parent loses to the tree, and the panel's parent is the layer.
        layer.setInheritanceHost(host.opener());
        boolean animate = owner.window() != null && !Motion.reduced();
        fade = animate ? 0 : 1;
        if (host.keepsKeyboard()) {
            SceneAccess.pushPopupLeavingKeyboard(owner, layer);
        } else {
            owner.pushPopup(layer);
        }
        if (animate) {
            Layer fading = layer;
            owner.addRealTimeTicker(dt -> {
                if (!open || layer != fading) {
                    return false; // closed mid-fade: the fade-out takes over
                }
                fade = (float) Math.min(1, fade + Motion.progress(dt, Theme.of(host.anchor()).animWindow));
                fading.invalidate();
                return fade < 1;
            });
        }
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
        Scene scene = host.anchor().scene();
        boolean ownerFocused = scene != null && scene.isWindowFocused();
        boolean popupFocused = popupScene != null && popupScene.isWindowFocused();
        if (!ownerFocused && !popupFocused) {
            host.dismiss(Change.Origin.ADJUSTMENT);
        }
    }

    /**
     * Takes it down, fading it where there is a frame pump to fade it. What it holds is let go
     * first, so one opened again while this fades builds afresh. A no-op while closed.
     */
    public void close() {
        Ui.checkUiThread();
        if (!open) {
            return;
        }
        open = false;
        for (Subscription each : new Subscription[] {cursor, presses, blur, popupBlur, fullscreen}) {
            if (each != null) {
                each.cancel();
            }
        }
        cursor = null;
        presses = null;
        blur = null;
        popupBlur = null;
        fullscreen = null;
        Scene scene = host.anchor().scene();
        if (window != null) {
            closeWindow(scene);
        } else if (layer != null) {
            closeLayer(scene);
        } else {
            panel = null; // closed before its window was made
        }
    }

    private void closeWindow(@Nullable Scene scene) {
        // The panel's verbs are withdrawn now: a window's fade moves only its opacity and damages
        // nothing, so the walk that withdraws them has to be asked for.
        if (panel != null) {
            panel.invalidateAccessible();
        }
        NativeWindow closing = window;
        Scene closingScene = popupScene;
        window = null;
        popupScene = null;
        panel = null;
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

    private void closeLayer(@Nullable Scene owner) {
        Layer closing = layer;
        layer = null;
        panel = null;
        if (owner == null) {
            host.released(); // detached while open: nothing holds the layer any more
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
        SceneAccess.releaseOverlayKeyboard(owner, closing);
        if (refocus != null) {
            refocus.requestFocus();
        }
        float[] left = {fade};
        closing.fade = left[0];
        // Wall time: this fade's last frame removes the layer that holds the pointer, and frozen it
        // would leave a paused application with its field beneath unreachable.
        owner.addRealTimeTicker(dt -> {
            left[0] = (float) Math.max(0, left[0] - Motion.progress(dt, Theme.of(host.anchor()).animWindow));
            closing.fade = left[0];
            closing.invalidate();
            if (left[0] > 0) {
                return true;
            }
            owner.removeOverlay(closing);
            host.released();
            return false;
        });
    }

    /**
     * The opacity a panel paints with: its layer's fade in the scene, in and out, and 1 in a
     * window, which fades itself. A layer keeps its own fade from the moment it closes, so a popup
     * opened again meanwhile fades in on its own.
     *
     * @param widget the panel, or anything inside it
     * @return the opacity, 0 to 1
     */
    public static float fadeOf(Widget<?> widget) {
        for (Widget<?> w = widget; w != null; w = w.parent()) {
            if (w instanceof AnchoredPopup.Layer l) {
                return l.paintFade();
            }
        }
        return 1;
    }

    /**
     * The layer a popup is drawn on in the scene. It covers the scene, so a press that missed the
     * card is a press on it, and closing on that press is the click-outside dismissal. It holds the
     * keyboard while the popup is open, unless the host keeps it, and passes the keys on.
     */
    private final class Layer extends Widget<Layer> {

        private final Widget<?> content;
        /** -1 while open, when the popup's own fade is read; the fade-out's own value after. */
        private float fade = -1;

        Layer(Widget<?> content) {
            this.content = content;
            setFocusable(true);
            add(content);
        }

        float paintFade() {
            return fade >= 0 ? fade : AnchoredPopup.this.fade;
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
            float top = anchor.localToSceneY();
            float bottom = top + anchor.height() + gap;
            Placement p = host.place(t, top - gap - EDGE_MARGIN, height() - bottom - EDGE_MARGIN);
            float w = Math.min(p.width(), width());
            float h = Math.min(p.height(), height());
            float x = Math.max(0, Math.min(leadingX(anchor, w), width() - w));
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
