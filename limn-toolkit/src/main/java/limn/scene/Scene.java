package limn.scene;

import limn.backend.Cursor;
import limn.backend.NativeWindow;
import limn.backend.WindowInput;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.GpuSurface;
import limn.graphics.Rect;
import limn.input.Keys;
import limn.scene.event.CharEvent;
import limn.scene.event.PreeditEvent;
import limn.scene.event.FileDropEvent;
import limn.scene.event.InputEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Owns a widget tree bound to one window: queues native input (with
 * coalescing), dispatches events with hit-testing and bubbling, tracks hover,
 * click synthesis and keyboard focus (Tab traversal in layout order), ticks
 * animations and runs the two-phase layout before painting.
 *
 * <p>Per-frame pipeline (deterministic, spec order): user input (dispatched
 * from the backend's input-batch hook, before the {@code Ui.post} drain) →
 * posted tasks → animation tick → layout if dirty → paint.
 *
 * <p>Coalescing: consecutive mouse-moves collapse to the newest, consecutive
 * scrolls accumulate deltas, consecutive resizes collapse; clicks and keys
 * are never dropped and never reordered.
 *
 * <p>Slow-handler instrumentation: every widget handler invocation is timed;
 * exceeding the budget (default 8 ms) logs a warning naming the widget, the
 * "StrictMode" that keeps the render thread honest.
 *
 * <p>V1 limitation: mouse capture tracks a single pressed widget/button, so
 * chorded presses (two buttons held at once) synthesize a CLICK only for the
 * most recent one.
 */
public final class Scene implements WindowInput {

    /** Animation hook, called once per frame while registered. */
    @FunctionalInterface
    public interface Ticker {
        /**
         * @param dtSeconds seconds since the previous tick (0 on the first), already clamped to
         *                  {@link #MAX_TICK_SECONDS} and (for a scene-time ticker) multiplied
         *                  by {@link #timeScale()}
         * @return {@code true} to keep ticking, {@code false} to stop
         */
        boolean tick(double dtSeconds);
    }

    /**
     * Longest step any ticker is handed in one frame, whatever the wall clock says.
     *
     * <p>The frame interval is not bounded by anything: a GC pause, a window drag, a breakpoint,
     * a laptop lid or a monitor switch can leave seconds between two frames. Unclamped, that
     * interval reaches every ticker at once: a 0.14&nbsp;s focus fade jumps straight to its end
     * (merely ugly), and anything integrating it (a game step, a physics tick, a sweep that wraps
     * modulo 1) skips through states it was supposed to pass through, which is how tunnelling and
     * NaN-poisoned simulations start. 0.25&nbsp;s is the conventional guard: long enough that no
     * healthy frame is ever clipped (it is 15 frames at 60&nbsp;Hz), short enough that the worst
     * a stall can do is one slow-motion step.
     *
     * <p>Clamping <b>loses</b> time on purpose: the toolkit does not bank the missing seconds and
     * replay them. A stalled second is a second the app did not run, not a debt.
     */
    public static final double MAX_TICK_SECONDS = 0.25;

    private static final System.Logger LOG = System.getLogger(Scene.class.getName());

    // -- raw input queue (records; coalesced on append, drained per frame) --
    private sealed interface Raw {
    }

    private record RawMove(float x, float y) implements Raw {
    }

    private record RawDelta(float dx, float dy) implements Raw {
    }

    private record RawButton(int button, boolean pressed, int mods, float x, float y) implements Raw {
    }

    private record RawScroll(float dx, float dy, float x, float y) implements Raw {
    }

    private record RawKey(int key, boolean pressed, boolean repeat, int mods) implements Raw {
    }

    private record RawChar(int codepoint) implements Raw {
    }

    private record RawPreedit(String text, int[] blockSizes, int focusedBlock, int caret) implements Raw {
    }

    private record RawPointer(boolean entered) implements Raw {
    }

    private record RawDrop(java.util.List<java.nio.file.Path> paths) implements Raw {
    }

    private record RawFocus(boolean focused) implements Raw {
    }

    private record RawResize(float width, float height) implements Raw {
    }

    private final Widget root;
    private final LongSupplier clock;
    private final List<Raw> queue = new ArrayList<>();
    // GPU surfaces to release; disposal needs the owning GL context, so it is
    // deferred to the next frame (drained at the top of renderFrame, context current).
    private final List<Runnable> pendingDisposals = new ArrayList<>();
    private final List<Ticker> tickers = new ArrayList<>();
    private final List<Ticker> pendingTickers = new ArrayList<>(); // added while a tick is running
    // Tickers awaiting their first frame; each gets dt == 0 there (per the Ticker
    // contract) regardless of whether other tickers were already running.
    private final java.util.Set<Ticker> freshTickers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    // Tickers that run on the WALL clock: the scale and the pause do not reach them (see
    // addRealTimeTicker). Identity, and pruned as each one finishes.
    private final java.util.Set<Ticker> realTimeTickers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private boolean tickingInProgress;
    private double timeScale = 1;
    private boolean paused;
    private double tickDtScene; // this frame's dt for scene-time tickers (clamped × factor)
    private double tickDtReal;  // ... and for real-time tickers (clamped only)
    private boolean sceneTimeFrozen; // factor == 0 this frame: scene-time tickers do not advance
    // Cached once so tickAnimations does not allocate a capturing lambda every frame.
    private final java.util.function.Predicate<Ticker> tickStep = ticker -> {
        boolean realTime = realTimeTickers.contains(ticker);
        if (sceneTimeFrozen && !realTime) {
            // Frozen, not dropped, and NOT ticked with 0: a ticker that has never run keeps its
            // pending first frame (freshTickers is only consumed by an actual tick), so resuming
            // still hands it dt == 0 exactly once.
            return false;
        }
        double dt = freshTickers.remove(ticker) ? 0 : (realTime ? tickDtReal : tickDtScene);
        boolean done;
        try {
            done = !ticker.tick(dt);
        } catch (Throwable error) {
            LOG.log(Level.ERROR, "ticker threw and was removed; the UI loop keeps running", error);
            limn.backend.Crashes.report(limn.backend.CrashPhase.TICKER, error);
            done = true; // keeping it would re-throw every frame
        }
        if (done) {
            realTimeTickers.remove(ticker);
        }
        return done;
    };

    private Runnable renderRequester = () -> {
    };
    private Color background = Color.rgb(0x14181F);

    // Keys physically down, by key code: the state no push-only input SPI keeps for you, and
    // the only way to release them when focus is stolen (see cancelKeyState).
    private final java.util.BitSet keysDown = new java.util.BitSet();

    private Widget hovered;
    private Widget pressed;
    private boolean pointerInside; // last CursorEnter/move state, for hover resync
    private int pressedButton = -1;
    private float mouseX;
    private float mouseY;
    private Widget focused;

    private final List<Widget> overlays = new ArrayList<>();
    // Widget that held focus when each overlay was pushed, restored on removal
    // (parallel to overlays), so closing a modal returns focus to its trigger.
    private final List<Widget> overlayFocusReturn = new ArrayList<>();

    /** @see #setFrontPainter */
    private java.util.function.Consumer<Canvas> frontPainter;

    private float width;
    private float height;
    private boolean layoutDirty = true;
    private long lastTickNanos = -1;

    private long slowHandlerBudgetNanos = TimeUnit.MILLISECONDS.toNanos(8);
    private int slowHandlerCount;

    private final FrameMetrics metrics = new FrameMetrics();
    private long lastFrameStartNanos = -1;

    /** @return live per-frame performance samples (FPS, frame time, event time) */
    public FrameMetrics metrics() {
        return metrics;
    }

    /** A scene over {@code root}. Subscribes to font, size-step and language changes. */
    public Scene(Widget root) {
        this(root, System::nanoTime);
    }

    /**
     * Injectable clock (slow-handler instrumentation and animation ticks),
     * public so component tests can drive animations deterministically.
     */
    public Scene(Widget root, LongSupplier clock) {
        this.root = Objects.requireNonNull(root, "root");
        this.clock = clock;
        root.setSceneRecursively(this);
        // Subscribed HERE, not in bind(): an unbound scene (every component test, a
        // ComboBox popup scene, a Dialog's modal scene between construction and bind)
        // would otherwise never hear a global font or control-size change, and because
        // layoutPass early-returns on !layoutDirty at an unchanged size, nothing would
        // re-measure. Registration is idempotent, so bind()'s repair is harmless.
        limn.graphics.Fonts.addChangeListener(metricsListener);
        ControlSize.addChangeListener(metricsListener);
        LayoutDirection.addChangeListener(metricsListener);
        limn.i18n.I18n.addChangeListener(metricsListener);
    }

    private limn.graphics.TextRuler textRuler;

    /** Overrides the text measurer (tests use deterministic fake metrics). */
    public void setTextRuler(limn.graphics.TextRuler ruler) {
        this.textRuler = ruler;
        markLayoutDirty();
    }

    /** @return the scene's text measurer (defaults to the backend-installed one) */
    public limn.graphics.TextRuler textRuler() {
        return textRuler != null ? textRuler : limn.graphics.TextRulers.get();
    }

    private NativeWindow window;
    private limn.backend.Clipboard clipboard;

    /**
     * Re-measures the whole tree and repaints when a <b>global</b> input to measurement
     * changes: the UI font family/catalog ({@link limn.graphics.Fonts}), the process
     * default control size ({@link ControlSize}) or the process default layout direction
     * ({@link LayoutDirection}). Holds the scene WEAKLY: a scene replaced
     * on a live window (a new scene bound over it) never receives {@code windowClosed}, and
     * a strong process-wide listener would pin the abandoned tree forever; when the scene
     * is collected, the wrapper unregisters itself on the next change.
     */
    private static final class GlobalMetricsListener implements Runnable {
        private final java.lang.ref.WeakReference<Scene> scene;

        GlobalMetricsListener(Scene scene) {
            this.scene = new java.lang.ref.WeakReference<>(scene);
        }

        @Override
        public void run() {
            Scene target = scene.get();
            if (target == null) {
                limn.graphics.Fonts.removeChangeListener(this);
                ControlSize.removeChangeListener(this);
                LayoutDirection.removeChangeListener(this);
                limn.i18n.I18n.removeChangeListener(this);
            } else {
                target.relayout();
            }
        }
    }

    private final GlobalMetricsListener metricsListener = new GlobalMetricsListener(this);

    /** Wires this scene into a window: input, frame rendering, invalidation, clipboard. */
    public void bind(NativeWindow window) {
        this.window = window;
        window.setInput(this);
        this.renderRequester = window::requestFrame;
        window.setFrameCallback((renderer, frame) ->
                renderFrame(renderer.canvas(), frame.rePresent(), frame.gpuFrameMs()));
        // Registration itself happens in the constructor (see there). Kept here so a
        // rebind is still a no-op rather than a double-register, and so a scene that was
        // somehow unsubscribed is repaired.
        limn.graphics.Fonts.removeChangeListener(metricsListener);
        limn.graphics.Fonts.addChangeListener(metricsListener);
        ControlSize.removeChangeListener(metricsListener);
        ControlSize.addChangeListener(metricsListener);
        LayoutDirection.removeChangeListener(metricsListener);
        LayoutDirection.addChangeListener(metricsListener);
        limn.i18n.I18n.removeChangeListener(metricsListener);
        limn.i18n.I18n.addChangeListener(metricsListener);
    }

    /**
     * Re-measures and repaints this whole scene, root <b>and overlays</b>. Call it after
     * changing an input to measurement that is not a widget property: the active
     * {@code Theme}, the UI font family, this scene's default control size.
     *
     * <p>{@code root().markNeedsLayout()} is <b>not</b> enough: it dirties only the path to
     * the root, so children return cached sizes under unchanged constraints, and it misses
     * overlays entirely (open dialogs, open menus). The global path must therefore go through
     * {@code Scene}, never through app code.
     */
    public void relayout() {
        root.markMeasureDirtyDeep();
        for (int i = 0; i < overlays.size(); i++) {
            overlays.get(i).markMeasureDirtyDeep(); // overlays are NOT in root's tree
        }
        layoutDirty = true;
        requestRender();
    }

    /** @return the bound window, or {@code null} when headless */
    public NativeWindow window() {
        return window;
    }

    /** Overrides the clipboard (tests inject mocks). */
    public void setClipboard(limn.backend.Clipboard newClipboard) {
        this.clipboard = newClipboard;
    }

    /** @return the clipboard (window's, injected, or a local no-op; never null) */
    public limn.backend.Clipboard clipboard() {
        if (clipboard != null) {
            return clipboard;
        }
        return window != null ? window.clipboard() : limn.backend.Clipboard.NONE;
    }

    /** The root widget of this scene's tree. */
    public Widget root() {
        return root;
    }

    private ControlSize controlSize; // null = fall through to a hosted root's host link

    /**
     * @return this scene's default step, or {@code null} to fall through to a hosted root's
     *         {@linkplain Widget#setInheritanceHost host link} and then to
     *         {@link ControlSize#processDefault()}. <b>Nullable by design:</b> a popup's own
     *         scene declares nothing, which is what lets it inherit from the widget that
     *         opened it.
     */
    public ControlSize controlSize() {
        return controlSize;
    }

    /**
     * Sets this window's default step, the per-window root of the inheritance chain (a
     * compact tool window beside a regular main window). Widgets that declare their own step,
     * and their subtrees, are unaffected. {@code null} restores fall-through. UI thread only.
     */
    public void setControlSize(ControlSize size) {
        Ui.checkUiThread();
        if (controlSize == size) {
            return;
        }
        controlSize = size;
        Widget.bumpControlSizeEpoch();
        relayout();
    }

    private LayoutDirection layoutDirection; // null = fall through to a hosted root's host link

    /**
     * @return this scene's default layout direction, or {@code null} to fall through to a hosted
     *         root's {@linkplain Widget#setInheritanceHost host link} and then to
     *         {@link LayoutDirection#processDefault()}. <b>Nullable by design</b>, for the reason
     *         {@link #controlSize()} is: a popup's own scene declares nothing, which is what lets
     *         it inherit from the widget that opened it.
     */
    public LayoutDirection layoutDirection() {
        return layoutDirection;
    }

    /**
     * Sets this window's default layout direction, the per-window root of the inheritance chain:
     * the one line an application whose interface reads right to left writes. Widgets that declare
     * their own direction, and their subtrees, are unaffected &mdash; which is what lets a
     * left-to-right code editor, log pane or URL bar sit inside it. {@code null} restores
     * fall-through. UI thread only.
     */
    public void setLayoutDirection(LayoutDirection direction) {
        Ui.checkUiThread();
        if (layoutDirection == direction) {
            return;
        }
        layoutDirection = direction;
        Widget.bumpLayoutDirectionEpoch();
        relayout();
    }

    // ------------------------------------------------------------- overlays

    /**
     * Pushes {@code overlay} as a full-scene modal layer painted on top of
     * everything: it captures all input and confines focus (hit-testing and
     * Tab traversal ignore the content and lower overlays). This is how modal
     * dialogs work: an in-scene overlay, no nested event loop. Focus moves to
     * the overlay's first focusable widget.
     */
    public void pushOverlay(Widget overlay) {
        Ui.checkUiThread();
        Objects.requireNonNull(overlay, "overlay");
        // A drag captured before this modal opened must not keep feeding the
        // now-blocked content beneath the scrim: DRAG follows the capture, not
        // hit-testing. Synthesize its RELEASE (window blur's treatment).
        cancelPointerState();
        overlayFocusReturn.add(focused); // remember who to focus when this closes
        overlays.add(overlay);
        overlay.setSceneRecursively(this);
        layoutDirty = true;
        // Move focus into the new modal layer.
        focusTraverse(false);
        requestRender();
    }

    /** Removes a modal overlay and restores focus to whatever it took it from. */
    public void removeOverlay(Widget overlay) {
        Ui.checkUiThread();
        int index = overlays.indexOf(overlay);
        if (index < 0) {
            return;
        }
        boolean wasTop = index == overlays.size() - 1;
        overlays.remove(index);
        Widget restore = overlayFocusReturn.remove(index);
        onWidgetDetached(overlay);
        overlay.setSceneRecursively(null);
        layoutDirty = true;
        // Focus belongs to the topmost overlay. Removing a lower one (e.g. the
        // previous menu still fading out beneath the menu it was switched to) must
        // leave the current top overlay's focus untouched.
        if (!wasTop) {
            requestRender();
            return;
        }
        // Return focus to the widget that had it before this overlay opened
        // (the button that triggered the dialog), if it is still usable.
        if (restore != null && restore != overlay && isInSubtree(restore, inputRoot())
                && restore.isFocusable() && restore.isVisible() && restore.isEnabled()) {
            setFocus(restore);
        } else {
            setFocus(null);
        }
        requestRender();
    }

    /** @return the topmost modal overlay, or {@code null} when none is open */
    Widget topOverlay() {
        return overlays.isEmpty() ? null : overlays.get(overlays.size() - 1);
    }

    /** @return the widget subtree that currently owns input (top overlay or root) */
    private Widget inputRoot() {
        Widget top = topOverlay();
        return top != null ? top : root;
    }

    /**
     * The colour cleared behind the tree each frame. A translucent one only shows
     * through where the window itself is translucent.
     */
    public void setBackground(Color color) {
        this.background = Objects.requireNonNull(color);
    }

    /**
     * The colour cleared behind the tree each frame. Read it before replacing it to tell a
     * deliberately translucent scene from one that simply took a theme's canvas: a caller
     * swapping the palette under a built scene must leave the first alone.
     */
    public Color background() {
        return background;
    }

    /** Budget above which an event handler is reported (default 8 ms). */
    public void setSlowHandlerBudgetMillis(long millis) {
        this.slowHandlerBudgetNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, millis));
    }

    /** @return handler-budget violations seen so far (debug/tests) */
    public int slowHandlerCount() {
        return slowHandlerCount;
    }

    // ----------------------------------------------------------- invalidation

    // Partial rendering (damage tracking). Widget.invalidate() records the
    // widget's scene-space bounds; the frame then repaints only the union of
    // this frame's damage and the previous frame's (the back buffer under
    // standard double buffering holds the frame from TWO presents ago, so what
    // the previous frame changed must be repainted again into this buffer).
    // requestRender() and every layout/overlay/tooltip path stay full-frame:
    // anything not routed through invalidate() is conservatively "everything".
    private boolean partialRendering;
    private boolean damageDebug;
    private boolean fullDamagePending = true; // first frame paints everything
    // Damage is a SMALL LIST of rects, not one bounding box: a progress bar
    // animating at the top and a status footer at the bottom must not conspire
    // to repaint everything between them. Rects whose union wastes little area
    // merge on arrival; disjoint hot spots stay separate repaint passes.
    // List semantics throughout: null = the whole scene, empty = nothing.
    private static final int MAX_DAMAGE_RECTS = 8;
    private final List<Rect> pendingDamage = new ArrayList<>();
    private List<Rect> frameDamage1;      // fresh damage of the previous content frame
    private List<Rect> lastRepaintRegion; // what the previous content frame actually repainted

    // Damage-debug flashes: each fresh damage region stays highlighted for
    // DAMAGE_FLASH_SECONDS, fading out. A fading flash changes pixels every
    // frame, so its rect joins the repaint region while alive, and for two
    // more frames after it goes (flashPrev1/2), because the double buffers
    // are two presents apart and both still hold the old highlight.
    private static final double DAMAGE_FLASH_SECONDS = 1.0;
    private static final long DAMAGE_FLASH_FRAME_MS = 100; // fade heartbeat (~10 fps, debug only)
    private static final int MAX_DAMAGE_FLASHES = 64;
    private boolean flashKeepAlivePending;

    private static final class DamageFlash {
        final Rect rect;
        long bornNanos;
        float lastFade; // fade painted on the last content frame (re-present replays it)

        DamageFlash(Rect rect, long bornNanos) {
            this.rect = rect;
            this.bornNanos = bornNanos;
            this.lastFade = 1;
        }
    }

    private final List<DamageFlash> damageFlashes = new ArrayList<>();
    private List<Rect> flashPrev1 = List.of(); // flash rects painted on the previous content frame
    private List<Rect> flashPrev2 = List.of(); // and on the frame before that

    /**
     * Enables partial rendering: frames repaint only the damaged region
     * (plus the previous frame's, for double buffering) instead of the whole
     * window. Experimental (Phase A): painting is still a full tree walk;
     * only rasterization is confined. Default off.
     */
    public void setPartialRendering(boolean enabled) {
        Ui.checkUiThread();
        if (partialRendering != enabled) {
            partialRendering = enabled;
            requestRender(); // start (or leave) with one clean full frame
        }
    }

    /** Whether only damaged regions are repainted rather than the whole window. */
    public boolean isPartialRendering() {
        return partialRendering;
    }

    /**
     * Highlights each frame's fresh damage region (magenta wash + border),
     * the tool for spotting under-invalidation bugs and seeing what partial
     * rendering repaints. Works with partial rendering on or off (off shows
     * what <em>would</em> be repainted). Default off.
     */
    public void setDamageDebug(boolean enabled) {
        Ui.checkUiThread();
        if (damageDebug != enabled) {
            damageDebug = enabled;
            if (!enabled) {
                damageFlashes.clear();
                flashPrev1 = List.of();
                flashPrev2 = List.of();
            }
            requestRender(); // full frame: wipes any highlight from both buffers
        }
    }

    /** Whether each frame's damage region is highlighted for inspection. */
    public boolean isDamageDebug() {
        return damageDebug;
    }

    /**
     * Copies the rendering flags (partial rendering, damage debug) from the
     * scene that spawned this one: popup/dialog scenes live in their own
     * native windows, so without this the flags would only ever affect the
     * window they were toggled in. (A transparent-background popup still
     * falls back to full frames; the debug overlay works regardless.)
     */
    public void inheritRenderingFlags(Scene owner) {
        setPartialRendering(owner.isPartialRendering());
        setDamageDebug(owner.isDamageDebug());
    }

    /** Schedules a frame and marks the whole scene damaged. */
    public void requestRender() {
        fullDamagePending = true;
        renderRequester.run();
    }

    /**
     * Draws over the finished frame: after the root, after every overlay, after the modal scrim
     * and after the tooltip. {@code null} removes it.
     *
     * <p><b>It is not in the tree and not in hit-testing.</b> That is the whole difference from a
     * widget, and it is what this exists for. A widget added last to the root paints under any
     * overlay, so it disappears the moment a modal dialog opens; an overlay pushed on top paints
     * correctly but becomes the topmost layer, and {@link #hitAt} then routes every press to it
     * alone, so a dialog underneath can never be driven. A front painter has neither problem
     * because it takes no input at all.
     *
     * <p>Meant for a recording or capture harness that has to draw something over the whole
     * window: a mouse pointer into a filmed frame, a watermark, a debug readout. It is not a
     * place to put user interface. Nothing here can be clicked, focused, or reached by a
     * keyboard, and a control drawn from here would look live and be inert.
     *
     * <p><b>It draws on every repaint, including partial ones</b>, and it is given the same
     * canvas the frame used, so what it draws outside the damaged region will be clipped away.
     * A caller that moves what it draws must therefore mark the scene damaged itself, which
     * {@link #requestRender()} does; the capture harness renders whole frames and so does not
     * have to.
     *
     * <p>Painted before the damage-debug flashes, so those stay legible on top of it.
     */
    public void setFrontPainter(java.util.function.Consumer<Canvas> painter) {
        this.frontPainter = painter;
        requestRender();
    }

    /** @return the front painter, or {@code null}. */
    public java.util.function.Consumer<Canvas> frontPainter() {
        return frontPainter;
    }

    /** Schedules a frame without adding damage (animation keep-alive, disposals). */
    private void scheduleFrame() {
        renderRequester.run();
    }

    /**
     * Marks a scene-space region as damaged and schedules a frame, for
     * painting that extends beyond the invalidating widget's bounds (shadows,
     * carets in overlays). {@link Widget#invalidate()} is the common path.
     */
    public void damage(Rect region) {
        addDamage(region.x(), region.y(), region.width(), region.height());
        scheduleFrame();
    }

    /** Widget.invalidate(x,y,w,h): damages a region given in the widget's local coordinates. */
    void damageWidgetRegion(Widget widget, float x, float y, float w, float h) {
        if (partialRendering || damageDebug) {
            addClippedDamage(widget, x - 1, y - 1, w + 2, h + 2);
        }
        scheduleFrame();
    }

    /** Widget.invalidate(): damages the widget's bounds (in scene coordinates). */
    void damageWidget(Widget widget) {
        if (partialRendering || damageDebug) {
            // +1px on every side: analytic AA lets a shape hugging the widget
            // edge feather just outside its bounds. paintOutset() widens it for
            // widgets that declare painting beyond their box.
            float outset = 1 + widget.paintOutset();
            addClippedDamage(widget, -outset, -outset,
                    widget.width() + 2 * outset, widget.height() + 2 * outset);
        }
        scheduleFrame();
    }

    /**
     * Adds damage for a rect in {@code widget}'s local coordinates, walking up
     * to scene coordinates while clamping it at every ancestor that clips its
     * children: a region scrolled out of a viewport damages only its visible
     * slice, and one fully out of view (or inside a hidden branch) damages
     * nothing at all.
     */
    private void addClippedDamage(Widget widget, float x, float y, float w, float h) {
        float x0 = x;
        float y0 = y;
        float x1 = x + w;
        float y1 = y + h;
        for (Widget node = widget; node != null; node = node.parent()) {
            if (!node.isVisible()) {
                return; // hidden branch: it paints nothing, so no pixel changed
            }
            if (node != widget && node.clipsChildren()) {
                // +1: the clip boundary itself antialiases within a pixel.
                x0 = Math.max(x0, -1);
                y0 = Math.max(y0, -1);
                x1 = Math.min(x1, node.width() + 1);
                y1 = Math.min(y1, node.height() + 1);
                if (x1 <= x0 || y1 <= y0) {
                    return; // fully clipped away (scrolled out of view)
                }
            }
            x0 += node.x();
            y0 += node.y();
            x1 += node.x();
            y1 += node.y();
        }
        addDamage(x0, y0, x1 - x0, y1 - y0);
    }

    private void addDamage(float x, float y, float w, float h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        mergeDamage(pendingDamage, new Rect(x, y, w, h));
    }

    /**
     * Adds {@code rect} to {@code rects}, merging with any rect whose union
     * wastes little area (so repeated/overlapping damage collapses, while
     * disjoint hot spots stay separate: an animation at the top, a footer at
     * the bottom). Bounded at {@link #MAX_DAMAGE_RECTS} by merging the
     * cheapest pair.
     */
    private static void mergeDamage(List<Rect> rects, Rect rect) {
        boolean merged = true;
        while (merged) { // a merge can bring the grown rect near another one
            merged = false;
            for (int i = 0; i < rects.size(); i++) {
                Rect e = rects.get(i);
                // Measured, not built. Every invalidate() in the frame reaches here and scans up
                // to MAX_DAMAGE_RECTS entries; constructing the union to ask its area and then
                // dropping it was a Rect per candidate on the busiest path in the scene.
                if (unionWaste(e, rect) <= 0.5f * (area(e) + area(rect))) {
                    rects.remove(i);
                    rect = e.union(rect); // now it is the answer, so now it is worth an object
                    merged = true;
                    break;
                }
            }
        }
        rects.add(rect);
        while (rects.size() > MAX_DAMAGE_RECTS) {
            mergeCheapestPair(rects);
        }
    }

    private static void mergeCheapestPair(List<Rect> rects) {
        int bestA = 0;
        int bestB = 1;
        float bestWaste = Float.MAX_VALUE;
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                float waste = unionWaste(rects.get(i), rects.get(j));
                if (waste < bestWaste) {
                    bestWaste = waste;
                    bestA = i;
                    bestB = j;
                }
            }
        }
        Rect u = rects.get(bestA).union(rects.get(bestB));
        rects.remove(bestB); // higher index first
        rects.remove(bestA);
        rects.add(u);
    }

    private static float area(Rect r) {
        return r.width() * r.height();
    }

    /**
     * Extra area the union of {@code a} and {@code b} would cover beyond the two of them
     * (negative when they overlap): the same number {@code area(a.union(b)) - area(a) - area(b)}
     * gives, without the union.
     */
    private static float unionWaste(Rect a, Rect b) {
        float width = Math.max(a.right(), b.right()) - Math.min(a.x(), b.x());
        float height = Math.max(a.bottom(), b.bottom()) - Math.min(a.y(), b.y());
        return width * height - area(a) - area(b);
    }

    private final List<java.util.function.Consumer<Widget>> pressObservers = new ArrayList<>();

    /**
     * Observes every mouse press with its hit-tested target (after normal
     * dispatch), the hook for "click outside to dismiss" overlays and popups
     * that must react to presses landing on non-focusable widgets. Returns a
     * handle that unregisters the observer.
     */
    public Runnable observePresses(java.util.function.Consumer<Widget> observer) {
        pressObservers.add(Objects.requireNonNull(observer, "observer"));
        return () -> pressObservers.remove(observer);
    }

    private void notifyPressObservers(Widget target) {
        if (pressObservers.isEmpty()) {
            return;
        }
        for (java.util.function.Consumer<Widget> observer : List.copyOf(pressObservers)) {
            observer.accept(target);
        }
    }

    private final List<Runnable> windowBlurObservers = new ArrayList<>();
    private boolean windowFocused;

    /**
     * Whether the bound window holds OS input focus, as reported by the last
     * focus event ({@code false} until the first one arrives; windows shown
     * without stealing focus, like popups, start unfocused).
     */
    public boolean isWindowFocused() {
        return windowFocused;
    }

    /**
     * Observes the bound window losing OS focus, the cue that dismisses
     * transient popups (dropdowns, menus) anchored to this window: a press in
     * another window or application never reaches {@link #observePresses}.
     * Returns a handle that unregisters the observer.
     */
    public Runnable observeWindowBlur(Runnable observer) {
        windowBlurObservers.add(Objects.requireNonNull(observer, "observer"));
        return () -> windowBlurObservers.remove(observer);
    }

    private void notifyWindowBlurObservers() {
        for (Runnable observer : List.copyOf(windowBlurObservers)) {
            observer.run();
        }
    }

    /**
     * The widget whose subtree a contained layout pass is running over, or {@code null}. Held so
     * that the mounting and recycling such a pass exists to perform (which goes through
     * {@code add}/{@code remove} and therefore asks for a full layout) is recognised as work the
     * pass in progress is already doing, rather than escalating it into the very full frame the
     * contained pass was there to avoid.
     */
    private Widget containedTarget;

    /** Widgets that asked for a contained layout, in request order. */
    private final List<Widget> containedLayouts = new ArrayList<>();

    void markLayoutDirty(Widget origin) {
        if (containedTarget != null && origin != null && isInSubtree(origin, containedTarget)) {
            return; // the pass already running over this subtree covers it
        }
        layoutDirty = true;
        requestRender();
    }

    void markLayoutDirty() {
        markLayoutDirty(null);
    }

    void markContainedLayout(Widget widget) {
        if (!containedLayouts.contains(widget)) {
            containedLayouts.add(widget);
        }
        // scheduleFrame, NOT requestRender: the latter declares the whole scene damaged, which
        // is exactly the frame this request exists to avoid. The damage is the widget's, and the
        // pass adds it once the layout is known to have stayed inside the box.
        scheduleFrame();
    }

    /**
     * Lays out each widget that asked for a contained pass, damaging only its bounds.
     *
     * <p>Every reason to distrust one escalates to a full pass instead of guessing: a widget that
     * has left the scene, one that does not clip its children, one that has never been measured,
     * and (the one that matters) one whose size came out different, because then its parent
     * placed it against a size that is no longer true and only a full pass can fix that.
     */
    private void runContainedLayouts() {
        if (containedLayouts.isEmpty()) {
            return;
        }
        List<Widget> pending = List.copyOf(containedLayouts);
        containedLayouts.clear();
        if (layoutDirty) {
            return; // a full pass is already scheduled and covers all of them
        }
        for (Widget widget : pending) {
            if (widget.scene() != this || !widget.clipsChildren()) {
                layoutDirty = true;
                continue;
            }
            Constraints constraints = widget.lastConstraints();
            Size before = widget.lastSize();
            if (constraints == null || before == null) {
                layoutDirty = true;
                continue;
            }
            containedTarget = widget;
            try {
                Size after = widget.measure(constraints);
                if (!after.equals(before)) {
                    layoutDirty = true; // the parent placed it against a size that just moved
                    continue;
                }
                widget.layoutBox(widget.x(), widget.y(), widget.width(), widget.height());
            } finally {
                containedTarget = null;
            }
            damageWidget(widget);
        }
    }

    void onWidgetDetached(Widget widget) {
        // Never leave dangling references to removed/disabled/hidden subtrees,
        // and never leave the widget itself with stuck hover/pressed state:
        // synthesize the EXIT/RELEASE it will otherwise never receive.
        // (State is cleared BEFORE dispatching, the reentrancy-safe order.)
        if (isInSubtree(hovered, widget)) {
            Widget oldHover = hovered;
            hovered = null;
            timedDispatch(oldHover, new MouseEvent(MouseEvent.Type.EXIT, mouseX, mouseY, -1, 0, 0, 0));
            applyCursor(); // hovered widget gone: fall back to the arrow
            // The tooltip owner is an ancestor-or-self of the hover: cancel its
            // pending/visible tooltip too, or the delayed show still fires for
            // (and a visible one keeps describing) a widget no longer on screen.
            hideTooltip();
        }
        if (isInSubtree(pressed, widget)) {
            Widget oldPressed = pressed;
            int oldButton = pressedButton;
            pressed = null;
            pressedButton = -1;
            timedDispatch(oldPressed,
                    new MouseEvent(MouseEvent.Type.RELEASE, mouseX, mouseY, oldButton, 0, 0, 0));
        }
        if (isInSubtree(focused, widget)) {
            setFocus(null);
        }
    }

    /**
     * The modifier keys held right now, mirrored from every native event that
     * reports them (key presses and mouse buttons).
     *
     * <p>It exists because the platform does not report modifiers everywhere:
     * the scroll callback has no mask at all, and moves/drags are derived from
     * cursor positions. Without this mirror, "hold Shift to constrain a drag"
     * or "Ctrl+wheel to zoom" (table stakes in editors, canvases and maps)
     * could not be written against the toolkit at all.
     */
    private int modifiers;

    /**
     * Updates {@link #modifiers} from a native key event. The bit for a
     * modifier key is set/cleared from its own press/release because a platform
     * is not required to include the bit in the mask of the very event that
     * announces it (GLFW notably does not).
     */
    private void trackModifiers(int mask, int key, boolean pressed) {
        modifiers = mask;
        int bit = switch (key) {
            case Keys.LEFT_SHIFT, Keys.RIGHT_SHIFT -> Keys.MOD_SHIFT;
            case Keys.LEFT_CONTROL, Keys.RIGHT_CONTROL -> Keys.MOD_CONTROL;
            case Keys.LEFT_ALT, Keys.RIGHT_ALT -> Keys.MOD_ALT;
            case Keys.LEFT_SUPER, Keys.RIGHT_SUPER -> Keys.MOD_SUPER;
            default -> 0;
        };
        if (bit != 0) {
            modifiers = pressed ? (modifiers | bit) : (modifiers & ~bit);
        }
    }

    /** @return the modifier keys held right now (see {@link Keys} bits) */
    public int modifiers() {
        return modifiers;
    }

    /**
     * Synthesizes the RELEASE for every key still held when the window loses focus.
     *
     * <p>The counterpart of {@link #cancelPointerState} for the keyboard, and the same argument:
     * the key-up happens in whatever application took focus and never reaches us, so a widget
     * that pairs PRESS with RELEASE (a held arrow that repeats, a key that keeps a gun firing,
     * push-to-talk) would stay armed forever after one alt-tab. The modifier <em>mask</em> was
     * already reset by {@code cancelPointerState}; this is the event half, which no amount of
     * mask-clearing delivers.
     *
     * <p>Two deliberate choices. The releases carry <b>no modifiers</b>: focus is gone, so what
     * is still physically down is unknowable and 0 is the only honest answer. And they all go to
     * <b>one target</b>, resolved once before the loop: a handler that moves focus mid-release
     * must not redirect the rest of a batch that models a single instant.
     */
    private void cancelKeyState() {
        if (keysDown.isEmpty()) {
            return;
        }
        Widget keyTarget = focused != null ? focused : inputRoot();
        java.util.BitSet held = (java.util.BitSet) keysDown.clone();
        // Cleared BEFORE dispatch: a handler that throws must not leave the scene believing keys
        // are down, and a re-entrant press during the batch must land on empty state.
        keysDown.clear();
        for (int key = held.nextSetBit(0); key >= 0; key = held.nextSetBit(key + 1)) {
            dispatchBubbling(keyTarget, new KeyEvent(key, false, false, 0));
        }
    }

    /** Synthesizes the RELEASE/EXIT for in-flight press/hover state (window blur). */
    private void cancelPointerState() {
        // The OS delivers no key-up for modifiers held while focus is stolen
        // (alt-tab, cmd-tab): without this the toolkit would believe Shift is
        // still down forever after the user comes back.
        modifiers = 0;
        if (pressed != null) {
            Widget oldPressed = pressed;
            int oldButton = pressedButton;
            pressed = null;
            pressedButton = -1;
            timedDispatch(oldPressed,
                    new MouseEvent(MouseEvent.Type.RELEASE, mouseX, mouseY, oldButton, 0, 0, 0));
        }
        updateHover(null);
    }

    private static boolean isInSubtree(Widget candidate, Widget subtreeRoot) {
        for (Widget w = candidate; w != null; w = w.parent()) {
            if (w == subtreeRoot) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------- focus

    /** The widget holding keyboard focus, or {@code null} when nothing does. */
    public Widget focusedWidget() {
        return focused;
    }

    /**
     * Moves keyboard focus to {@code widget}, or clears it when {@code null}. Ignored
     * for a widget that is not focusable, visible and enabled, and for one outside the
     * topmost modal overlay. UI thread only.
     */
    public void requestFocus(Widget widget) {
        Ui.checkUiThread();
        if (widget != null && (!widget.isFocusable() || !widget.isVisible() || !widget.isEnabled())) {
            return;
        }
        // Focus is confined to the input root (the top modal overlay): every
        // other focus path already is (click-to-focus hit-tests overlay-first,
        // traversal collects from inputRoot), and a background widget holding
        // focus would siphon keys and committed text from the open modal.
        if (widget != null && !isInSubtree(widget, inputRoot())) {
            return;
        }
        setFocus(widget);
    }

    private void setFocus(Widget widget) {
        if (focused == widget) {
            return;
        }
        Widget old = focused;
        focused = widget;
        if (old != null) {
            if (window != null && old.acceptsTextInputInternal()) {
                // Focus is leaving a text widget: cancel any OS-side composition
                // it owned, so the preedit can't teleport into, and later
                // commit into, whatever gains focus next.
                window.resetPreedit();
            }
            old.notifyFocus(false);
            if (focused != widget) {
                return; // a focus-lost handler re-routed focus; it finished the job
            }
        }
        if (widget != null) {
            widget.notifyFocus(true);
            // Auto-scroll: focus must never land off-screen. With a layout
            // pending the geometry is stale (a just-added widget still sits at
            // 0,0 against already-offset scroll content), so revealing NOW
            // would scroll somewhere meaningless; defer to right after the
            // next layout pass instead.
            if (layoutDirty) {
                pendingReveal = widget;
            } else {
                widget.revealInView();
            }
        }
        updateImeState(); // turn the platform IME on/off for the new focus
        requestRender();
    }

    /** Focused widget awaiting post-layout reveal (see {@link #setFocus}). */
    private Widget pendingReveal;

    // ------------------------------------------------------------- IME/preedit

    /** Last caret rect pushed to the window, so we only re-push on change. */
    private limn.graphics.Rect lastCaretRect;

    /** Enables the IME iff a text-editing widget is focused, and seeds its caret. */
    private void updateImeState() {
        if (window == null) {
            return; // headless: no platform IME
        }
        boolean textInput = focused != null && focused.acceptsTextInputInternal();
        window.setImeEnabled(textInput);
        lastCaretRect = null; // force the next sync to push afresh
        if (textInput) {
            syncImeCaret();
        }
    }

    /** Pushes the focused text widget's caret rect to the IME when it changes. */
    private void syncImeCaret() {
        if (window == null || focused == null || !focused.acceptsTextInputInternal()) {
            return;
        }
        limn.graphics.Rect rect = focused.caretRectInternal();
        if (rect == null || rect.equals(lastCaretRect)) {
            return;
        }
        lastCaretRect = rect;
        window.setPreeditCaretRect(rect.x(), rect.y(), rect.width(), rect.height());
    }

    /** Moves focus to the next/previous focusable widget in layout (DFS) order. */
    public void focusTraverse(boolean backward) {
        Ui.checkUiThread();
        List<Widget> order = new ArrayList<>();
        collectFocusable(inputRoot(), order); // modal overlay confines traversal
        if (order.isEmpty()) {
            setFocus(null);
            return;
        }
        int index = order.indexOf(focused);
        int next = index < 0
                ? (backward ? order.size() - 1 : 0)
                : Math.floorMod(index + (backward ? -1 : 1), order.size());
        focusByTraversal = true;
        try {
            setFocus(order.get(next));
        } finally {
            focusByTraversal = false;
        }
    }

    /**
     * Whether the focus change being delivered right now came from Tab rather than from a click
     * or from code. Only meaningful inside a {@code onFocusGained} callback, which is the one
     * moment it is set; see {@link Widget#focusArrivedByTraversal()}.
     */
    private boolean focusByTraversal;

    boolean focusCameFromTraversal() {
        return focusByTraversal;
    }

    private static void collectFocusable(Widget widget, List<Widget> out) {
        if (!widget.isVisible() || !widget.isEnabled()) {
            return;
        }
        if (widget.isFocusable()) {
            out.add(widget);
        }
        for (Widget child : widget.children()) {
            collectFocusable(child, out);
        }
    }

    // ------------------------------------------------------------- animations

    /**
     * Registers an animation ticker on <b>scene time</b>; frames keep coming while any is active.
     * Safe to call from inside another ticker's {@code tick} (e.g. one animation
     * kicking off another); such a ticker joins on the next frame.
     *
     * <p>Its {@code dt} is clamped to {@link #MAX_TICK_SECONDS} and multiplied by
     * {@link #timeScale()}, so {@link #setPaused} freezes it. That is the right default for
     * anything the app animates. Use {@link #addRealTimeTicker} for an animation whose
     * <em>completion</em> frees a resource or settles state; freezing one of those hangs it.
     */
    public void addTicker(Ticker ticker) {
        Ui.checkUiThread();
        Objects.requireNonNull(ticker);
        (tickingInProgress ? pendingTickers : tickers).add(ticker);
        freshTickers.add(ticker); // its first tick gets dt == 0
        // Schedule-only: the ticker runs at the top of the next frame, before
        // damage is consumed, and invalidates whatever it animates itself.
        scheduleFrame();
    }

    /**
     * Registers a ticker on <b>wall time</b>: {@link #setTimeScale} and {@link #setPaused} do not
     * reach it. Its {@code dt} is still clamped to {@link #MAX_TICK_SECONDS}: that guard is
     * about a stalled clock, not about intent.
     *
     * <p>For animations that are not content: the toolkit's own window / dialog / menu fades,
     * widget transitions, scrollbar fades, progress sweeps. Two reasons they belong here. The
     * hard one: several of them <b>own a lifecycle</b>; the fade-out is what destroys the popup
     * window, removes the overlay that is capturing input, or completes the dialog's future, so a
     * pause that froze them would leave a half-closed dialog on screen and a future nobody ever
     * completes. The soft one: shell feedback that stops responding is read as a hang, not as a
     * pause; a paused app should still highlight the button under the pointer.
     */
    public void addRealTimeTicker(Ticker ticker) {
        Ui.checkUiThread();
        Objects.requireNonNull(ticker);
        realTimeTickers.add(ticker);
        addTicker(ticker);
    }

    /**
     * Schedules a {@link GpuSurface} for disposal at the next frame, when this
     * scene's window/GL context is current. Widgets that own GPU resources (e.g.
     * a 3D viewport) call this from {@link Widget#onDetached()} instead of
     * disposing inline, because {@code dispose()} would run without a current context.
     */
    public void disposeLater(GpuSurface surface) {
        Ui.checkUiThread();
        if (surface != null) {
            disposeLater(surface::dispose);
        }
    }

    /**
     * Like {@link #disposeLater(GpuSurface)} for any GPU cleanup that must run
     * with this scene's GL context current, e.g. a retained 3D scene's
     * {@code Scene3D.dispose()}. Runs once, at the top of the next frame.
     */
    public void disposeLater(Runnable gpuCleanup) {
        Ui.checkUiThread();
        if (gpuCleanup != null) {
            pendingDisposals.add(gpuCleanup);
            scheduleFrame(); // ensure a frame runs to drain it (disposal paints nothing)
        }
    }

    /** Package-private so tests can drive disposal without a Canvas. Runs at frame top (context current). */
    void drainPendingDisposals() {
        if (pendingDisposals.isEmpty()) {
            return;
        }
        for (int i = 0; i < pendingDisposals.size(); i++) {
            try {
                pendingDisposals.get(i).run();
            } catch (Throwable error) {
                // GPU deletes are independent, so one bad disposal must not make
                // the rest leak. This was a fatal site before containment, so
                // the handler's verdict IS honored: on shutdown the tail is
                // dropped (window teardown frees GPU state with the context).
                if (!limn.backend.Crashes.dispatch(limn.backend.CrashPhase.FRAME, error)) {
                    pendingDisposals.clear();
                    throw limn.backend.Crashes.shutdownRequested(error);
                }
            }
        }
        pendingDisposals.clear();
    }

    /** Package-private so tests can advance registered tickers without a Canvas. */
    void tickAnimations() {
        if (tickers.isEmpty()) {
            lastTickNanos = -1;
            return;
        }
        long now = clock.getAsLong();
        // A newly-registered ticker starts at dt == 0 (its own first frame), even if
        // other animations were already running this frame; see the tickStep field.
        double elapsed = lastTickNanos < 0 ? 0 : (now - lastTickNanos) / 1e9;
        lastTickNanos = now;
        // Clamp BEFORE scaling: the clamp guards against a stalled wall clock, the scale is the
        // app's intent. Scaling first would let a 4-second stall through at scale 0.25.
        tickDtReal = Math.min(elapsed, MAX_TICK_SECONDS);
        double factor = paused ? 0 : timeScale;
        tickDtScene = tickDtReal * factor;
        sceneTimeFrozen = factor == 0;
        tickingInProgress = true;
        try {
            tickers.removeIf(tickStep);
        } finally {
            tickingInProgress = false;
        }
        if (!pendingTickers.isEmpty()) { // tickers registered during this pass
            tickers.addAll(pendingTickers);
            pendingTickers.clear();
        }
        if (needsAnotherAnimationFrame()) {
            scheduleFrame(); // keep animating next frame (tickers invalidate what they move)
        } else {
            // Nothing will advance until something changes. Dropping the reference is what makes
            // resuming free of banked time: the first frame after it starts from dt == 0.
            lastTickNanos = -1;
        }
    }

    /**
     * Whether any registered ticker will actually move on the next frame.
     *
     * <p>The frozen case is the one that matters: a paused scene whose only tickers are scene-time
     * must stop asking for frames, or "paused" would cost a full frame rate to repaint pixels that
     * cannot change, and idling at zero when nothing moves is the property the whole event-driven
     * loop is built on.
     */
    private boolean needsAnotherAnimationFrame() {
        if (tickers.isEmpty()) {
            return false;
        }
        return !sceneTimeFrozen || !realTimeTickers.isEmpty();
    }

    /**
     * Speed of <b>scene time</b>, the clock every {@link #addTicker} ticker integrates.
     * 1 is real time, 0.5 slow motion, 2 double speed, 0 frozen. Never negative: nothing in the
     * toolkit's animation model runs backwards, and a negative dt would drive
     * {@code Transition} past its start with no way back.
     *
     * <p><b>What it does not touch.</b> Wall time keeps running underneath: input, layout,
     * {@link limn.concurrent.Ui#postDelayed} (so the caret keeps blinking), the frame metrics, the
     * tooltip and modal-scrim fades, and every {@link #addRealTimeTicker} ticker, which is where
     * the toolkit puts its own shell animation, so a paused app still opens dialogs, closes menus
     * and fades windows normally. Scene time is for what the app animates, not for the chrome
     * around it.
     *
     * @param scale a finite factor &ge; 0
     */
    public void setTimeScale(double scale) {
        Ui.checkUiThread();
        if (!Double.isFinite(scale) || scale < 0) {
            throw new IllegalArgumentException("time scale must be finite and >= 0: " + scale);
        }
        if (timeScale != scale) {
            boolean wasFrozen = paused || timeScale == 0;
            timeScale = scale;
            if (wasFrozen && !paused && scale > 0) {
                scheduleFrame(); // frozen scenes stopped asking for frames, so restart the pump
            }
        }
    }

    /** @return the current scene-time scale (see {@link #setTimeScale}) */
    public double timeScale() {
        return timeScale;
    }

    /**
     * Freezes or resumes scene time, keeping the {@link #timeScale()} the app chose (so a paused
     * slow-motion scene resumes in slow motion). Equivalent in effect to a scale of 0.
     *
     * <p>No time is banked while paused: the paused seconds are never replayed. A scene-time
     * ticker resumes measuring from the last frame that actually ran, which is the resume itself
     * when the pause stopped the frame pump (so {@code dt == 0}), or the last frame a real-time
     * ticker kept alive. Either way what it receives is the time since <em>that</em> frame, not
     * the length of the pause. A ticker registered while paused still gets its own {@code dt == 0}
     * first frame, whenever it finally runs.
     */
    public void setPaused(boolean newPaused) {
        Ui.checkUiThread();
        if (paused != newPaused) {
            paused = newPaused;
            if (!paused && timeScale > 0) {
                scheduleFrame(); // see setTimeScale
            }
        }
    }

    /** @return whether scene time is frozen (see {@link #setPaused}) */
    public boolean isPaused() {
        return paused;
    }

    // -------------------------------------------------- WindowInput (queue)

    @Override
    public void mouseMoved(float x, float y) {
        Raw last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        if (last instanceof RawMove) {
            queue.set(queue.size() - 1, new RawMove(x, y)); // coalesce: newest wins
        } else {
            queue.add(new RawMove(x, y));
        }
    }

    @Override
    public void mouseDelta(float dx, float dy) {
        Raw last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        if (last instanceof RawDelta d) {
            queue.set(queue.size() - 1, new RawDelta(d.dx + dx, d.dy + dy)); // coalesce: sum
        } else {
            queue.add(new RawDelta(dx, dy));
        }
    }

    @Override
    public void mouseButton(int button, boolean pressed, int modifiers, float x, float y) {
        queue.add(new RawButton(button, pressed, modifiers, x, y)); // never dropped
    }

    @Override
    public void scrolled(float deltaX, float deltaY, float x, float y) {
        Raw last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        if (last instanceof RawScroll s) {
            queue.set(queue.size() - 1, new RawScroll(s.dx + deltaX, s.dy + deltaY, x, y));
        } else {
            queue.add(new RawScroll(deltaX, deltaY, x, y));
        }
    }

    @Override
    public void keyEvent(int key, boolean pressed, boolean repeat, int modifiers) {
        queue.add(new RawKey(key, pressed, repeat, modifiers)); // never dropped
    }

    @Override
    public void charTyped(int codepoint) {
        queue.add(new RawChar(codepoint));
    }

    @Override
    public void preeditChanged(String text, int[] blockSizes, int focusedBlock, int caret) {
        queue.add(new RawPreedit(text, blockSizes, focusedBlock, caret));
    }

    @Override
    public void pointerEntered(boolean entered) {
        queue.add(new RawPointer(entered));
    }

    @Override
    public void filesDropped(java.util.List<java.nio.file.Path> paths) {
        if (!paths.isEmpty()) {
            queue.add(new RawDrop(paths)); // never dropped, like clicks/keys
        }
    }

    @Override
    public void windowFocusChanged(boolean focused) {
        queue.add(new RawFocus(focused));
    }

    @Override
    public void windowResized(float logicalWidth, float logicalHeight) {
        Raw last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        RawResize resize = new RawResize(logicalWidth, logicalHeight);
        if (last instanceof RawResize) {
            queue.set(queue.size() - 1, resize);
        } else {
            queue.add(resize);
        }
    }

    @Override
    public void inputBatchEnded() {
        boolean hadInput = !queue.isEmpty();
        long start = hadInput ? clock.getAsLong() : 0;
        processInput();
        if (hadInput) {
            metrics.recordEventTime((float) ((clock.getAsLong() - start) / 1_000_000.0));
        }
    }

    // ---------------------------------------------------------- dispatching

    /** Drains the queue, dispatching in order. Called once per frame batch. */
    void processInput() {
        if (queue.isEmpty()) {
            return;
        }
        boolean invalidate = false;
        for (int i = 0; i < queue.size(); i++) {
            Raw raw = queue.get(i);
            // Contained per event: a throwing app handler must not abort the
            // drain, because the already-dispatched prefix would stay queued
            // and be replayed on the next batch (duplicate clicks/keys), and a
            // deterministic throw would grow the queue without bound.
            try {
                if (raw instanceof RawMove move) {
                    invalidate |= handleMove(move.x, move.y);
                } else if (raw instanceof RawDelta delta) {
                    // Relative capture: no meaningful cursor position to
                    // hit-test, so deltas go to the focused widget, like keys
                    // (the capturing interaction takes focus on its press).
                    dispatchBubbling(focused != null ? focused : inputRoot(), new MouseEvent(
                            MouseEvent.Type.MOTION, mouseX, mouseY, -1, delta.dx, delta.dy,
                            modifiers));
                    invalidate = true;
                } else if (raw instanceof RawButton button) {
                    handleButton(button);
                    invalidate = true;
                } else if (raw instanceof RawScroll scroll) {
                    dispatchBubbling(hitAt(scroll.x, scroll.y), new MouseEvent(
                            MouseEvent.Type.WHEEL, scroll.x, scroll.y, -1, scroll.dx, scroll.dy,
                            modifiers));
                    // Scrolling moves content under a stationary cursor: re-hover.
                    if (pressed == null) {
                        updateHover(hitAt(scroll.x, scroll.y));
                    }
                    invalidate = true;
                } else if (raw instanceof RawKey key) {
                    handleKey(key);
                    invalidate = true;
                } else if (raw instanceof RawChar ch) {
                    // Like keys: with nothing focused, chars go to the topmost
                    // modal layer, never to the blocked content underneath.
                    dispatchBubbling(focused != null ? focused : inputRoot(),
                            new CharEvent(ch.codepoint));
                    invalidate = true;
                } else if (raw instanceof RawPreedit preedit) {
                    // Composition only makes sense for a focused widget that
                    // accepts text input (the documented onPreedit contract);
                    // secure fields refuse it so a composed secret is never
                    // echoed, and with nothing focused there is nowhere to
                    // show it, so drop it.
                    if (focused != null && focused.acceptsTextInputInternal()) {
                        dispatchBubbling(focused, new PreeditEvent(
                                preedit.text, preedit.blockSizes, preedit.focusedBlock, preedit.caret));
                    }
                    invalidate = true;
                } else if (raw instanceof RawDrop drop) {
                    // The platform moves the cursor onto the window before the
                    // drop, so the last MOVE left mouseX/mouseY at the drop point.
                    dispatchBubbling(hitAt(mouseX, mouseY),
                            new FileDropEvent(drop.paths, mouseX, mouseY));
                    invalidate = true;
                } else if (raw instanceof RawPointer pointer) {
                    pointerInside = pointer.entered;
                    if (!pointer.entered) {
                        updateHover(null);
                        invalidate = true;
                    }
                } else if (raw instanceof RawFocus focus) {
                    windowFocused = focus.focused;
                    if (!focus.focused) {
                        // The RELEASE happens in another app and never reaches us:
                        // without this, the next MOVE would still be a DRAG (pointer), and every
                        // key held at the moment focus was stolen would stay down forever.
                        cancelPointerState();
                        cancelKeyState();
                        notifyWindowBlurObservers();
                        invalidate = true;
                    }
                } else if (raw instanceof RawResize) {
                    layoutDirty = true;
                    invalidate = true;
                }
            } catch (Throwable error) {
                LOG.log(Level.ERROR, "input handler threw; the event loop keeps running", error);
                limn.backend.Crashes.report(limn.backend.CrashPhase.INPUT, error);
                // Settle whatever half-applied state remains: unknown extent,
                // so this one stays a full-frame request.
                requestRender();
            }
        }
        queue.clear();
        if (invalidate) {
            // Schedule-only: whatever the events changed visually invalidated
            // itself (hover transitions, press states, text edits), so partial
            // rendering repaints just those regions. A full-window request here
            // would turn every hover change and keystroke into a full frame.
            // (Resize is safe: a layout frame forces full damage structurally.)
            scheduleFrame();
        }
    }

    private boolean handleMove(float x, float y) {
        mouseX = x;
        mouseY = y;
        pointerInside = true;
        boolean changed;
        if (pressed != null) {
            MouseEvent drag = new MouseEvent(MouseEvent.Type.DRAG, x, y, pressedButton, 0, 0,
                    modifiers);
            dispatchBubbling(pressed, drag);
            changed = drag.isConsumed();
        } else {
            Widget hit = hitAt(x, y);
            changed = updateHover(hit);
            MouseEvent move = new MouseEvent(MouseEvent.Type.MOVE, x, y, -1, 0, 0, modifiers);
            dispatchBubbling(hit, move);
            changed |= move.isConsumed();
            if (tooltipShowing) {
                damageTooltip(); // the tooltip hugs the pointer: old + new spots repaint
            }
        }
        return changed;
    }

    private void handleButton(RawButton button) {
        mouseX = button.x;
        mouseY = button.y;
        modifiers = button.mods; // presses carry the authoritative native mask
        if (button.pressed) {
            hideTooltip(); // any click dismisses a showing/pending tooltip
            Widget hit = hitAt(button.x, button.y);
            // Capture BEFORE dispatching: a PRESS/focus handler may detach the
            // widget, and onWidgetDetached must find (and clear) this state.
            pressed = hit;
            pressedButton = button.button;
            // Click-to-focus: nearest focusable ancestor of the press target.
            Widget focusTarget = hit;
            while (focusTarget != null && !focusTarget.isFocusable()) {
                focusTarget = focusTarget.parent();
            }
            if (focusTarget != null) {
                setFocus(focusTarget);
            }
            dispatchBubbling(pressed, new MouseEvent(
                    MouseEvent.Type.PRESS, button.x, button.y, button.button, 0, 0, button.mods));
            notifyPressObservers(hit);
        } else {
            Widget target = pressed != null ? pressed : hitAt(button.x, button.y);
            dispatchBubbling(target, new MouseEvent(
                    MouseEvent.Type.RELEASE, button.x, button.y, button.button, 0, 0, button.mods));
            Widget releaseHit = hitAt(button.x, button.y);
            if (pressed != null && isInSubtree(releaseHit, pressed)) {
                dispatchBubbling(pressed, new MouseEvent(
                        MouseEvent.Type.CLICK, button.x, button.y, button.button, 0, 0, button.mods));
            }
            pressed = null;
            pressedButton = -1;
            updateHover(hitAt(button.x, button.y));
        }
    }

    private void handleKey(RawKey key) {
        trackModifiers(key.mods, key.key, key.pressed);
        if (key.key >= 0) { // GLFW reports -1 for a key it cannot name; there is nothing to pair
            if (key.pressed) {
                // A repeat re-sets a bit that is already set: held keys stay held, and a REPEAT
                // that arrives after a lost PRESS still leaves the scene able to release it.
                keysDown.set(key.key);
            } else {
                keysDown.clear(key.key);
            }
        }
        KeyEvent event = new KeyEvent(key.key, key.pressed, key.repeat, key.mods);
        // Keys go to the focused widget, else the topmost modal layer (so a
        // dialog can handle ESC even with nothing focused).
        Widget keyTarget = focused != null ? focused : inputRoot();
        dispatchBubbling(keyTarget, event);
        // Between the focused widget and the Tab fallback: what nobody focused wanted may still
        // be a shortcut, and what nobody wants at all must still traverse.
        offerToShortcutHandlers(event);
        if (!event.isConsumed() && key.pressed && key.key == Keys.TAB) {
            focusTraverse((key.mods & Keys.MOD_SHIFT) != 0);
        }
    }

    private final List<java.util.function.Predicate<KeyEvent>> shortcutHandlers = new ArrayList<>();

    /**
     * Registers a scene-wide keyboard handler for chords the focused widget did not want:
     * menu accelerators, and anything else that must work without being focused.
     *
     * <p><b>The ordering is the contract.</b> A key event is offered, in this order, to:
     *
     * <ol>
     *   <li>the focused widget, bubbling to the root, so {@code Ctrl+C} inside a text field
     *       with a selection is the field's copy and never a menu's;</li>
     *   <li>every handler registered here, oldest first, until one returns {@code true};</li>
     *   <li>Tab traversal, if the key is Tab and still nobody took it.</li>
     * </ol>
     *
     * <p>Returning {@code true} consumes the event and stops both the remaining handlers and the
     * Tab fallback. Handlers see presses, auto-repeats and <b>releases</b> alike: a shortcut
     * whose trigger is a modifier let go of (a bare Alt reaching for the menu bar) has nothing
     * else to key on.
     *
     * <p>Handlers are <b>not</b> consulted while a modal overlay owns the scene: an open dialog
     * or an in-scene menu has the keyboard, and a shortcut belonging to what it covers must not
     * fire behind it.
     *
     * <p>Registering or unregistering from inside a handler is legal and takes effect on the next
     * event, never on the one being dispatched. Call the returned {@link Runnable} to unregister;
     * it is idempotent. UI thread only.
     */
    public Runnable addShortcutHandler(java.util.function.Predicate<KeyEvent> handler) {
        Ui.checkUiThread();
        Objects.requireNonNull(handler, "handler");
        shortcutHandlers.add(handler);
        return () -> shortcutHandlers.remove(handler);
    }

    /**
     * Offers an unconsumed key event to the registered handlers. Iterates a snapshot: a handler
     * that unregisters itself (the ordinary shape for one that closes what it opened) would
     * otherwise shift the list under the loop and skip its neighbour.
     */
    private void offerToShortcutHandlers(KeyEvent event) {
        if (event.isConsumed() || shortcutHandlers.isEmpty() || topOverlay() != null) {
            return;
        }
        for (java.util.function.Predicate<KeyEvent> handler : List.copyOf(shortcutHandlers)) {
            if (handler.test(event)) {
                event.consume();
                return;
            }
        }
    }

    private Widget hitAt(float sceneX, float sceneY) {
        // A modal overlay owns all input; hit-testing never reaches the content,
        // unless the overlay yields this point (e.g. the menu bar strip behind a
        // fullscreen in-scene menu), which then hit-tests the content below.
        Widget top = topOverlay();
        if (top != null && !top.overlayPassesPointer(sceneX, sceneY)) {
            return top.hitTest(sceneX - top.x(), sceneY - top.y());
        }
        return root.hitTest(sceneX - root.x(), sceneY - root.y());
    }

    /** @return whether the hover leaf changed (ENTER/EXIT dispatched, no bubbling) */
    private boolean updateHover(Widget newHover) {
        if (hovered == newHover) {
            return false;
        }
        if (hovered != null) {
            timedDispatch(hovered,
                    new MouseEvent(MouseEvent.Type.EXIT, mouseX, mouseY, -1, 0, 0, modifiers));
        }
        hovered = newHover;
        if (hovered != null) {
            timedDispatch(hovered,
                    new MouseEvent(MouseEvent.Type.ENTER, mouseX, mouseY, -1, 0, 0, modifiers));
        }
        applyCursor();
        updateTooltip();
        return true;
    }

    /**
     * Pushes the effective mouse cursor for the current hover to the window: the
     * first non-null {@link Widget#cursor()} walking up from the hovered leaf,
     * else {@link Cursor#DEFAULT}. No window (headless) → no-op.
     */
    private void applyCursor() {
        if (window == null) {
            return;
        }
        // First widget up the ancestor chain that declares ANY cursor wins;
        // on that widget an image cursor beats a shape (it is the more
        // specific request). The backend treats an active image cursor as an
        // override, so the shape is still recorded for when it clears.
        Cursor shape = Cursor.DEFAULT;
        limn.backend.ImageCursor image = null;
        for (Widget w = hovered; w != null; w = w.parent()) {
            limn.backend.ImageCursor ic = w.imageCursor();
            Cursor c = w.cursor();
            if (ic != null) {
                image = ic;
                break;
            }
            if (c != null) {
                shape = c;
                break;
            }
        }
        window.setImageCursor(image);
        window.setCursor(shape);
    }

    /**
     * A widget's cursor changed. If it is the hovered leaf or an ancestor of it,
     * the pointer is over it now, so re-resolve immediately. Called by
     * {@link Widget#setCursor}.
     */
    void cursorChanged(Widget widget) {
        if (isInSubtree(hovered, widget)) {
            applyCursor();
        }
    }

    /** Bubbles from {@code target} to the root until consumed, timing each handler. */
    private void dispatchBubbling(Widget target, InputEvent event) {
        for (Widget w = target; w != null && !event.isConsumed(); w = w.parent()) {
            timedDispatch(w, event);
        }
    }

    private void timedDispatch(Widget widget, InputEvent event) {
        long start = clock.getAsLong();
        if (event instanceof MouseEvent mouse) {
            widget.dispatchMouse(mouse);
        } else if (event instanceof KeyEvent key) {
            widget.dispatchKey(key);
        } else if (event instanceof CharEvent ch) {
            widget.dispatchChar(ch);
        } else if (event instanceof PreeditEvent preedit) {
            widget.dispatchPreedit(preedit);
        } else if (event instanceof FileDropEvent drop) {
            widget.dispatchFileDrop(drop);
        }
        long elapsed = clock.getAsLong() - start;
        if (elapsed > slowHandlerBudgetNanos) {
            slowHandlerCount++;
            LOG.log(Level.WARNING,
                    "handler of {0} took {1} ms for {2} (budget {3} ms); move work to Ui.async(...)",
                    widget.getClass().getName(), TimeUnit.NANOSECONDS.toMillis(elapsed), event,
                    TimeUnit.NANOSECONDS.toMillis(slowHandlerBudgetNanos));
        }
    }

    // --------------------------------------------------------------- frames

    private static final float SCRIM_MAX_ALPHA = 0.5f;
    private static final double SCRIM_SECONDS = 0.14;
    private float scrimAlpha;
    private long lastScrimNanos = -1;

    /** Full frame: animation tick → layout (if dirty) → paint (root then overlays) → modal scrim. */
    public void renderFrame(Canvas canvas) {
        renderFrame(canvas, false);
    }

    // Consecutive renderFrame crashes: self-heal (full repaint) the first few,
    // then stop self-requesting frames so a deterministic crash cannot spin the
    // loop at 100% CPU; input/damage still retries, so the app stays rescuable.
    private static final int FRAME_CRASH_RECOVERY_LIMIT = 8;
    private int frameCrashStreak;

    /**
     * @param rePresent {@code true} when re-drawing the same already-settled frame
     *                  only to converge the double buffers (the backend's
     *                  double-present anti-flicker): identical pixels, but
     *                  excluded from {@link #metrics()} so FPS/frame-time count
     *                  only frames rendered for content.
     */
    public void renderFrame(Canvas canvas, boolean rePresent) {
        renderFrame(canvas, rePresent, Float.NaN);
    }

    /**
     * @param gpuFrameMs backend-measured GPU time of a recently completed frame
     *                   in ms ({@link Float#NaN} = no new sample); recorded into
     *                   {@link #metrics()} alongside this frame's CPU numbers
     */
    public void renderFrame(Canvas canvas, boolean rePresent, float gpuFrameMs) {
        try {
            renderFrameImpl(canvas, rePresent, gpuFrameMs);
            frameCrashStreak = 0;
        } catch (limn.backend.Crashes.ShutdownRequested shutdown) {
            throw shutdown; // already dispatched below (or by nested code)
        } catch (Throwable error) {
            containFrameCrash(error);
        }
    }

    /**
     * A frame callback (widget paint/layout override, disposal, hover handler)
     * threw. Keep the loop alive: notify the {@link limn.backend.Crashes}
     * handler, then repaint everything: the frame's damage bookkeeping was
     * consumed but never presented, so only a full repaint is trustworthy.
     */
    private void containFrameCrash(Throwable error) {
        frameCrashStreak++;
        if (!limn.backend.Crashes.dispatch(limn.backend.CrashPhase.FRAME, error)) {
            throw limn.backend.Crashes.shutdownRequested(error);
        }
        if (frameCrashStreak <= FRAME_CRASH_RECOVERY_LIMIT) {
            requestRender();
        } else if (frameCrashStreak == FRAME_CRASH_RECOVERY_LIMIT + 1) {
            LOG.log(Level.ERROR, "frame code crashed {0} consecutive times; pausing automatic "
                    + "repaint: the next input or damage retries", frameCrashStreak);
        }
    }

    private void renderFrameImpl(Canvas canvas, boolean rePresent, float gpuFrameMs) {
        long frameStart = clock.getAsLong();
        metrics.beginFrame();
        if (!rePresent && lastFrameStartNanos >= 0) {
            double periodMs = (frameStart - lastFrameStartNanos) / 1_000_000.0;
            if (periodMs > 1.0 && periodMs < 1000) { // ignore idle-sleep gaps and sub-1ms re-presents
                metrics.recordFps((float) (1000.0 / periodMs));
            }
        }
        if (!rePresent) {
            lastFrameStartNanos = frameStart;
        }
        if (!Float.isNaN(gpuFrameMs)) {
            // A sample always measures a CONTENT frame a few presents back
            // (the backend never times re-present frames); the frame carrying
            // it is just the courier, so record regardless of rePresent: with
            // sparse rendering the delivery often lands on the re-present that
            // settles a burst, and dropping it there would starve the metric.
            metrics.recordGpuTime(gpuFrameMs);
        }

        drainPendingDisposals(); // GL context is current here: safe to delete GPU resources
        // Ticking is skipped in two cases:
        //
        //  - Re-present frames, whose whole contract is to redraw the SAME
        //    pixels into the other buffer. Advancing animations there would
        //    paint a different frame than the one being duplicated, and it
        //    only ever happens once a render burst has settled, so nothing is
        //    animating anyway (an animating scene keeps requesting frames,
        //    which suppresses re-presents entirely).
        //  - Past the frame-crash recovery cap: an active ticker calls
        //    scheduleFrame() every tick, which would re-arm the crash storm at
        //    vsync rate even though containFrameCrash went quiet (the cap would
        //    bound nothing). Animations freeze with the broken window; the
        //    first successful frame resets the streak and resumes.
        if (!rePresent && frameCrashStreak <= FRAME_CRASH_RECOVERY_LIMIT) {
            tickAnimations();
        }
        // Before the full-damage decision, because a contained pass that could not keep its
        // promise sets layoutDirty and this frame has to become the full one after all.
        runContainedLayouts();
        if (layoutDirty || canvas.width() != width || canvas.height() != height) {
            // A layout pass can move any widget without it invalidating its old
            // bounds: layout frames are always full, which is a structural
            // invariant, not a convention for every layoutDirty producer to
            // remember.
            //
            // The one exception is above, and it earns it by proving what this rule assumes it
            // cannot know: a contained pass runs only over a widget that clips its children and
            // only after re-measuring it to the same size, so nothing it moved can have left the
            // rectangle it damages. Anything it cannot prove sets layoutDirty and lands here.
            fullDamagePending = true;
        }
        layoutPass(canvas.width(), canvas.height());
        if (!rePresent && pointerInside && pressed == null) {
            // Content may have moved under a stationary mouse (keyboard scroll,
            // overlay close, layout change): hover follows what is under the
            // pointer NOW, because pointer events alone would leave a
            // scrolled-away widget with stale ENTER state and the wrong cursor.
            updateHover(hitAt(mouseX, mouseY));
        }
        List<Rect> repaint; // rects to repaint; null = the whole frame, empty = nothing
        if (rePresent) {
            // Identical frame into the other buffer: repaint exactly what the
            // last content frame painted, so both double buffers converge.
            repaint = partialRendering ? lastRepaintRegion : null;
        } else {
            List<Rect> fresh = consumeFreshDamage(canvas);
            // Double buffering: the back buffer holds the frame from two
            // presents ago, so the previous frame's damage repaints too.
            repaint = unionDamage(fresh, frameDamage1);
            frameDamage1 = fresh;
            if (damageDebug) {
                List<Rect> flashNow = updateDamageFlashes(fresh, canvas);
                repaint = unionDamage(repaint, flashNow);
                repaint = unionDamage(repaint, flashPrev1);
                repaint = unionDamage(repaint, flashPrev2);
                flashPrev2 = flashPrev1;
                flashPrev1 = flashNow;
            }
            if (!partialRendering || coversWholeCanvas(repaint, canvas)) {
                repaint = null;
            }
            lastRepaintRegion = repaint;
        }
        if (!rePresent) {
            // Animation state advances once per frame; the per-pass paints
            // below must be pure (a pass per damage rect would double-advance).
            updateModalScrim();
            updateTooltipFade();
        }
        if (repaint == null) {
            canvas.damageScissorHint(0, 0, 0, 0); // disabled: the frame is full
            canvas.clear(background);
            paintFramePass(canvas);
        } else if (!repaint.isEmpty()) {
            // Rasterization can never leave the union of the passes: let the
            // backend confine fragment work to its bounding box (glScissor).
            float sx0 = Float.MAX_VALUE;
            float sy0 = Float.MAX_VALUE;
            float sx1 = -Float.MAX_VALUE;
            float sy1 = -Float.MAX_VALUE;
            for (int i = 0; i < repaint.size(); i++) {
                Rect r = repaint.get(i);
                sx0 = Math.min(sx0, r.x());
                sy0 = Math.min(sy0, r.y());
                sx1 = Math.max(sx1, r.right());
                sy1 = Math.max(sy1, r.bottom());
            }
            canvas.damageScissorHint(sx0, sy0, sx1 - sx0, sy1 - sy0);
            for (int i = 0; i < repaint.size(); i++) {
                Rect pass = repaint.get(i);
                // The restore is in a finally for the same reason endPaintCull's is: a frame crash
                // is CONTAINED rather than fatal, so the loop carries on with a canvas that would
                // otherwise still be holding this pass's clip. The next pass would then paint
                // inside the previous one's rectangle, and the frame would end unbalanced,
                // reported against nobody, because whatever threw is long out of the stack.
                canvas.save();
                try {
                    canvas.clipRect(pass);
                    // clear() ignores the clip; clearRect REPLACES exactly this
                    // pass (works on translucent popup framebuffers too, where a
                    // blended fill could never write alpha back to 0).
                    canvas.clearRect(pass.x(), pass.y(), pass.width(), pass.height(), background);
                    beginPaintCull(pass);
                    try {
                        paintFramePass(canvas);
                    } finally {
                        endPaintCull();
                    }
                } finally {
                    canvas.restore();
                }
            }
        }
        if (damageDebug && !rePresent && !damageFlashes.isEmpty() && !flashKeepAlivePending) {
            // Debug-only fade heartbeat, THROTTLED: a plain scheduleFrame here
            // would chain frame-after-frame at vsync whenever any flash is
            // alive (a 1 Hz metrics widget + a 1 s fade = permanent 60 fps),
            // destroying the event-driven behavior the overlay exists to show.
            // ~10 fps renders the fade smoothly enough and leaves the app
            // measurably idle between steps.
            flashKeepAlivePending = true;
            Ui.postDelayed(() -> {
                flashKeepAlivePending = false;
                scheduleFrame();
            }, DAMAGE_FLASH_FRAME_MS);
        }
        if (!rePresent) {
            syncImeCaret(); // layout is settled: the candidate window can follow the caret
            // Count only frames that painted: under partial rendering the
            // backend may run frames whose repaint region is empty (the loop
            // conservatively requests one for every drained UI task); they do
            // no work, and counting them would make the FPS gauge report
            // near-free wakeups as if they were real paints.
            if (repaint == null || !repaint.isEmpty()) {
                metrics.recordFrameTime((float) ((clock.getAsLong() - frameStart) / 1_000_000.0));
                // A full frame is one region, which is what makes the two readings
                // comparable across the partial-rendering switch rather than showing
                // a dash on one side of it.
                metrics.recordPaintedFrame(repaint == null ? 1 : repaint.size());
            }
        }
    }

    /**
     * Resolves and resets the damage accumulated since the last content frame:
     * {@code null} = the whole scene, empty = nothing. Rects are clamped and
     * snapped outward to whole logical pixels.
     */
    private List<Rect> consumeFreshDamage(Canvas canvas) {
        float scrimTarget = window != null && window.isModalBlocked() ? SCRIM_MAX_ALPHA : 0f;
        boolean full = fullDamagePending
                || scrimAlpha != scrimTarget; // scrim mid-fade retints the whole window
        fullDamagePending = false;
        if (full) {
            pendingDamage.clear();
            return null;
        }
        if (pendingDamage.isEmpty()) {
            return List.of();
        }
        // Snap outward to the DEVICE pixel grid: pass clips then have hard,
        // whole-pixel edges (no fractional AA coverage at the seam), which is
        // what lets clearRect replace exactly the pixels the pass repaints.
        // Required on translucent backgrounds, exact everywhere else.
        float s = canvas.contentScale();
        List<Rect> fresh = new ArrayList<>(pendingDamage.size());
        for (int i = 0; i < pendingDamage.size(); i++) {
            Rect r = pendingDamage.get(i);
            float x = Math.max(0, (float) Math.floor(r.x() * s) / s);
            float y = Math.max(0, (float) Math.floor(r.y() * s) / s);
            float right = Math.min(canvas.width(), (float) Math.ceil(r.right() * s) / s);
            float bottom = Math.min(canvas.height(), (float) Math.ceil(r.bottom() * s) / s);
            if (right > x && bottom > y) {
                fresh.add(new Rect(x, y, right - x, bottom - y));
            }
        }
        pendingDamage.clear();
        return fresh;
    }

    /** List union where {@code null} means the whole scene; merges near/overlapping rects. */
    private static List<Rect> unionDamage(List<Rect> a, List<Rect> b) {
        if (a == null || b == null) {
            return null;
        }
        if (b.isEmpty()) {
            return a;
        }
        if (a.isEmpty()) {
            return b;
        }
        List<Rect> result = new ArrayList<>(a);
        for (int i = 0; i < b.size(); i++) {
            mergeDamage(result, b.get(i));
        }
        return result;
    }

    private static boolean coversWholeCanvas(List<Rect> repaint, Canvas canvas) {
        if (repaint == null) {
            return false; // already "whole frame"
        }
        for (int i = 0; i < repaint.size(); i++) {
            Rect r = repaint.get(i);
            if (r.x() <= 0 && r.y() <= 0
                    && r.right() >= canvas.width() && r.bottom() >= canvas.height()) {
                return true;
            }
        }
        return false;
    }

    /** Everything a frame paints, in order; called once per repaint pass (must be pure). */
    private void paintFramePass(Canvas canvas) {
        root.paintWidget(canvas);
        for (int i = 0; i < overlays.size(); i++) { // indexed: no iterator alloc per frame
            overlays.get(i).paintWidget(canvas);
        }
        paintModalScrim(canvas);
        paintTooltip(canvas);
        if (frontPainter != null) {
            frontPainter.accept(canvas);
        }
        if (damageDebug) {
            paintDamageFlashes(canvas);
        }
    }

    // ------------------------------------------------------------ paint culling
    // While a partial pass paints, its rect is exposed here (scene coords);
    // Widget.paintWidget skips any subtree whose bounds (+ paintOutset) miss it.

    private boolean cullActive;
    private float cullX0;
    private float cullY0;
    private float cullX1;
    private float cullY1;

    private void beginPaintCull(Rect pass) {
        cullActive = true;
        cullX0 = pass.x();
        cullY0 = pass.y();
        cullX1 = pass.right();
        cullY1 = pass.bottom();
    }

    private void endPaintCull() {
        cullActive = false;
    }

    /** Whether the active repaint pass can skip {@code widget}'s whole subtree. */
    boolean culledFromPaint(Widget widget) {
        if (!cullActive) {
            return false;
        }
        // +1: analytic AA feathers up to a pixel past the box (invalidate()
        // inflates damage by the same margin).
        float outset = 1 + widget.paintOutset();
        float x = widget.localToSceneX();
        float y = widget.localToSceneY();
        return x + widget.width() + outset <= cullX0 || x - outset >= cullX1
                || y + widget.height() + outset <= cullY0 || y - outset >= cullY1;
    }

    private static final Color DAMAGE_DEBUG_FILL = Color.rgba(0xFF2BD6, 0.10f);
    private static final Color DAMAGE_DEBUG_BORDER = Color.rgba(0xFF2BD6, 0.85f);

    /**
     * Expires finished flashes, starts (or restarts) one per fresh damage rect,
     * stamps each survivor's fade for this frame (the per-pass paint and any
     * re-present replay the stamped value), and returns the live flash rects.
     */
    private List<Rect> updateDamageFlashes(List<Rect> fresh, Canvas canvas) {
        long now = clock.getAsLong();
        damageFlashes.removeIf(flash -> (now - flash.bornNanos) / 1e9 >= DAMAGE_FLASH_SECONDS);
        List<Rect> freshRects = fresh == null
                ? List.of(new Rect(0, 0, canvas.width(), canvas.height()))
                : fresh;
        for (int i = 0; i < freshRects.size(); i++) {
            Rect rect = freshRects.get(i);
            if (rect.width() <= 0 || rect.height() <= 0) {
                continue;
            }
            DamageFlash existing = null;
            for (int j = 0; j < damageFlashes.size(); j++) {
                if (damageFlashes.get(j).rect.equals(rect)) {
                    existing = damageFlashes.get(j);
                    break;
                }
            }
            if (existing != null) {
                existing.bornNanos = now; // same region flashing repeatedly (caret blink): restart
            } else {
                if (damageFlashes.size() == MAX_DAMAGE_FLASHES) {
                    damageFlashes.remove(0);
                }
                damageFlashes.add(new DamageFlash(rect, now));
            }
        }
        List<Rect> live = new ArrayList<>(damageFlashes.size());
        for (int i = 0; i < damageFlashes.size(); i++) {
            DamageFlash flash = damageFlashes.get(i);
            flash.lastFade = (float) Math.max(0,
                    1 - (now - flash.bornNanos) / 1e9 / DAMAGE_FLASH_SECONDS);
            live.add(flash.rect);
        }
        return live;
    }

    /** Damage-debug overlay: washes and outlines every live flash at its stamped fade. */
    private void paintDamageFlashes(Canvas canvas) {
        for (int i = 0; i < damageFlashes.size(); i++) {
            DamageFlash flash = damageFlashes.get(i);
            float fade = flash.lastFade;
            if (fade <= 0) {
                continue;
            }
            Rect r = flash.rect;
            canvas.fillRect(r.x(), r.y(), r.width(), r.height(),
                    DAMAGE_DEBUG_FILL.withAlpha(DAMAGE_DEBUG_FILL.a() * fade));
            // Inset so the 2px stroke stays inside the region (it must be erasable
            // by repainting the region itself on a later frame).
            canvas.drawRect(r.x() + 1, r.y() + 1,
                    Math.max(0, r.width() - 2), Math.max(0, r.height() - 2), 2,
                    DAMAGE_DEBUG_BORDER.withAlpha(DAMAGE_DEBUG_BORDER.a() * fade));
        }
    }

    // ------------------------------------------------------------- tooltips
    // A lightweight, non-capturing hover tooltip: after a short dwell over a
    // widget with a tooltip, a small panel fades in near the pointer, painted on
    // top of everything. It never steals input or focus.

    private static final long TOOLTIP_DWELL_MS = 600;
    private static final double TOOLTIP_FADE_SECONDS = 0.10;
    /**
     * The step the tooltip panel is styled at: the hovered anchor's, resolved live. The
     * anchor can be null or detached between {@code showTooltip} and paint
     * ({@link #tooltipRect()} is reachable from the hide path), so the fallback is stated
     * rather than left to the reader. The panel's padding travels with it (see
     * {@link TooltipStyle}), which is why the two pinned pad constants are gone.
     */
    private ControlSize tooltipStep() {
        Widget target = tooltipTarget;
        return target != null ? target.controlSize() : ControlSize.processDefault();
    }

    /**
     * The direction the tooltip panel is laid out and shaped in: the hovered anchor's, resolved
     * live, with the same fallback and for the same reason as {@link #tooltipStep()}. This is the
     * one surface a {@link Scene} paints itself rather than delegating to a widget, so it is the
     * one place the inherited axes have to be read here rather than inside an {@code onPaint}.
     */
    private LayoutDirection tooltipDirection() {
        Widget target = tooltipTarget;
        return target != null ? target.layoutDirection() : LayoutDirection.processDefault();
    }

    /**
     * The tooltip's text, shaped for the anchor's direction. Shaped rather than measured because
     * a plain {@code drawText(String, ...)} resolves its own paragraph direction with a
     * left-to-right neutral fallback, so a tooltip whose leading or trailing characters are
     * neutrals renders them on the wrong end inside a right-to-left interface. One shaping sizes
     * the panel and draws the text, so the two cannot disagree about the width.
     */
    private limn.graphics.ShapedText tooltipLine(limn.graphics.Font font) {
        String text = tooltipText;
        limn.graphics.ShapedText.Direction neutral =
                tooltipDirection() == LayoutDirection.RTL
                        ? limn.graphics.ShapedText.Direction.RTL
                        : limn.graphics.ShapedText.Direction.LTR;
        return textRuler().shape(text, font,
                limn.graphics.ShapedText.Direction.of(text, neutral));
    }
    // Themed appearance is supplied by the components layer (the toolkit has no
    // Theme of its own); null → tooltips are not painted.
    private static volatile java.util.function.Function<ControlSize, TooltipStyle> tooltipStyle;
    private Widget tooltipTarget; // owner whose tooltip is scheduled or showing
    private String tooltipText;
    private boolean tooltipShowing;
    private float tooltipAlpha;
    private int tooltipGeneration;
    private long lastTooltipNanos = -1;
    private Rect paintedTooltipRect; // where the last frame drew it (scene coords), null when hidden

    /** Installs the themed tooltip appearance (the components layer calls this once). */
    public static void installTooltipStyle(
            java.util.function.Function<ControlSize, TooltipStyle> supplier) {
        tooltipStyle = supplier;
    }

    /** Nearest ancestor of {@code from} (inclusive) that declares a tooltip, else null. */
    Widget tooltipOwner(Widget from) {
        for (Widget w = from; w != null; w = w.parent()) {
            String text = w.tooltip();
            if (text != null && !text.isEmpty()) {
                return w;
            }
        }
        return null;
    }

    private void updateTooltip() {
        if (window == null) {
            return; // headless: no display to place a tooltip on
        }
        Widget owner = tooltipOwner(hovered);
        if (owner == tooltipTarget) {
            return; // still over the same owner (or still over nothing)
        }
        tooltipGeneration++; // cancel any pending show
        tooltipTarget = owner;
        if (tooltipShowing) {
            tooltipShowing = false;
            damageTooltip();
        }
        if (owner != null) {
            int generation = tooltipGeneration;
            Ui.postDelayed(() -> showTooltip(generation), TOOLTIP_DWELL_MS);
        }
    }

    private void showTooltip(int generation) {
        if (generation != tooltipGeneration || tooltipTarget == null) {
            return; // superseded, or the owner is gone
        }
        tooltipText = tooltipTarget.tooltip();
        if (tooltipText == null || tooltipText.isEmpty()) {
            return;
        }
        tooltipShowing = true;
        tooltipAlpha = 0;
        lastTooltipNanos = -1;
        damageTooltip();
    }

    private void hideTooltip() {
        tooltipGeneration++; // cancel pending show
        tooltipTarget = null;
        if (tooltipShowing) {
            tooltipShowing = false;
            damageTooltip();
        }
    }

    /** Advances the tooltip fade, once per content frame (the paint is per-pass and pure). */
    private void updateTooltipFade() {
        if (!tooltipShowing || tooltipText == null || tooltipStyle == null) {
            lastTooltipNanos = -1;
            return;
        }
        long now = clock.getAsLong();
        double dt = lastTooltipNanos < 0 ? 0 : (now - lastTooltipNanos) / 1e9;
        lastTooltipNanos = now;
        if (tooltipAlpha < 1) {
            tooltipAlpha = (float) Math.min(1, tooltipAlpha + dt / TOOLTIP_FADE_SECONDS);
            damageTooltip(); // keep fading; only the tooltip's own region repaints
        }
    }

    /**
     * Damages where the tooltip was last painted and where it would paint now,
     * and schedules a frame: the localized replacement for a full-window
     * request on show/fade/hide/pointer-move (the tooltip is scene-painted, so
     * no widget invalidates for it).
     */
    private void damageTooltip() {
        if (paintedTooltipRect != null) {
            addDamage(paintedTooltipRect.x() - 1, paintedTooltipRect.y() - 1,
                    paintedTooltipRect.width() + 2, paintedTooltipRect.height() + 2);
        }
        Rect now = tooltipRect();
        if (now != null) {
            addDamage(now.x() - 1, now.y() - 1, now.width() + 2, now.height() + 2);
        }
        scheduleFrame();
    }

    /** The rect the tooltip occupies for the current pointer/text, or null when hidden. */
    private Rect tooltipRect() {
        java.util.function.Function<ControlSize, TooltipStyle> supplier = tooltipStyle;
        if (!tooltipShowing || tooltipText == null || supplier == null) {
            return null;
        }
        TooltipStyle style = supplier.apply(tooltipStep());
        limn.graphics.TextMetrics fm = textRuler().measure("Hg", style.font());
        float w = tooltipLine(style.font()).metrics().width() + 2 * style.padH();
        float h = fm.lineHeight() + 2 * style.padV();
        // The panel grows away from the pointer on the side reading starts from, and is held
        // inside the window on whichever edge it would otherwise leave. Horizontally this is the
        // twin of the vertical flip below: the offset that clears the cursor, then the clamp.
        float x = tooltipDirection() == LayoutDirection.RTL
                ? Math.max(4, mouseX - 12 - w)
                : Math.min(mouseX + 12, width - w - 4);
        float y = mouseY + 20;
        if (y + h > height - 4) {
            y = mouseY - h - 8; // flip above the pointer
        }
        return new Rect(Math.min(Math.max(4, x), Math.max(4, width - w - 4)),
                Math.max(4, y), w, h);
    }

    private void paintTooltip(Canvas canvas) {
        java.util.function.Function<ControlSize, TooltipStyle> supplier = tooltipStyle;
        Rect rect = tooltipRect();
        if (rect == null || supplier == null) {
            paintedTooltipRect = null;
            return;
        }
        TooltipStyle style = supplier.apply(tooltipStep());
        limn.graphics.Font font = style.font();
        limn.graphics.ShapedText line = tooltipLine(font);
        limn.graphics.TextMetrics fm = textRuler().measure("Hg", font);
        float x = rect.x();
        float y = rect.y();
        float w = rect.width();
        float h = rect.height();
        canvas.save();
        canvas.setOpacity(tooltipAlpha);
        canvas.fillRoundRect(x, y, w, h, style.radius(), style.fill());
        canvas.drawRoundRect(x + 0.5f, y + 0.5f, w - 1, h - 1, style.radius(), 1, style.border());
        // Inside the pad on the side reading starts from; the panel was sized from this very
        // line, so the two pads are equal and the run fills what is between them.
        float textX = tooltipDirection() == LayoutDirection.RTL
                ? x + w - style.padH() - line.metrics().width()
                : x + style.padH();
        canvas.drawText(line, textX, y + (h - fm.height()) / 2 + fm.ascent(), style.text());
        canvas.restore();
        paintedTooltipRect = rect;
    }

    /** Advances the scrim fade, once per content frame (the paint is per-pass and pure). */
    private void updateModalScrim() {
        float target = window != null && window.isModalBlocked() ? SCRIM_MAX_ALPHA : 0f;
        long now = clock.getAsLong();
        double dt = lastScrimNanos < 0 ? 0 : (now - lastScrimNanos) / 1e9;
        lastScrimNanos = now;
        if (scrimAlpha < target) {
            scrimAlpha = (float) Math.min(target, scrimAlpha + dt / SCRIM_SECONDS);
        } else if (scrimAlpha > target) {
            scrimAlpha = (float) Math.max(target, scrimAlpha - dt / SCRIM_SECONDS);
        }
        if (scrimAlpha != target) {
            requestRender(); // keep animating
        } else {
            lastScrimNanos = -1; // settled; restart the clock on the next transition
        }
    }

    /** Dims this window while a modal locks it, just like the old in-scene dialog. */
    private void paintModalScrim(Canvas canvas) {
        if (scrimAlpha > 0.001f) {
            // Ease the linear time-ramp so the dim glides in/out instead of
            // marching at a constant rate.
            float eased = smoothstep(scrimAlpha / SCRIM_MAX_ALPHA) * SCRIM_MAX_ALPHA;
            canvas.fillRect(0, 0, canvas.width(), canvas.height(),
                    Color.rgba(0x000000, eased));
        }
    }

    // ------------------------------------------------ whole-window opacity fade
    // The fade primitive for native dialogs, popups and floating windows: drives
    // NativeWindow.setOpacity off the scene ticker. Retarget-safe (a newer call
    // supersedes the older via a generation gate) and headless-safe (no window →
    // jump + run the callback at once).

    private double winFadeSeconds;
    private float winFadeFrom;
    private float winFadeTarget = 1f;
    private float winFadeCurrent = 1f;
    private double winFadeElapsed;
    private int winFadeGeneration;
    private Runnable winFadeOnArrive;

    /**
     * Snaps the window opacity to {@code 0} immediately, then eases it to
     * {@code 1} over {@code seconds}, the flash-free "appear" sequence. Call it
     * right before {@link NativeWindow#show()} so the window is already
     * transparent when it first maps.
     */
    public void fadeWindowIn(double seconds) {
        fadeWindow(0f, 0, null);
        fadeWindow(1f, seconds, null);
    }

    /** Eases the window opacity to {@code 0}, then runs {@code onGone} (typically a close). */
    public void fadeWindowOut(double seconds, Runnable onGone) {
        fadeWindow(0f, seconds, onGone);
    }

    /**
     * Animates this scene's native-window opacity toward {@code target}
     * ({@code [0..1]}) over {@code seconds}, running {@code onArrive} once it
     * settles. A newer call supersedes an in-flight fade without stacking
     * tickers. Zero duration, an already-current value, or a headless scene (no
     * window) jumps straight to {@code target} and runs {@code onArrive} now.
     * UI thread only.
     */
    public void fadeWindow(float target, double seconds, Runnable onArrive) {
        float clamped = Math.max(0f, Math.min(1f, target));
        int generation = ++winFadeGeneration; // supersede any in-flight fade
        NativeWindow w = window;
        if (w == null) {
            // Headless: remember the value (a later real window starts here) and fire.
            winFadeCurrent = clamped;
            if (onArrive != null) {
                onArrive.run();
            }
            return;
        }
        winFadeFrom = winFadeCurrent;
        winFadeTarget = clamped;
        winFadeElapsed = 0;
        winFadeSeconds = seconds;
        w.setOpacity(winFadeCurrent); // apply the starting value now (e.g. 0 before show)
        if (seconds <= 0 || winFadeCurrent == clamped) {
            winFadeCurrent = clamped;
            w.setOpacity(clamped);
            winFadeOnArrive = null;
            if (onArrive != null) {
                onArrive.run();
            }
            return;
        }
        winFadeOnArrive = onArrive;
        // Wall time: onArrive is what destroys the window and completes the caller's future.
        addRealTimeTicker(dt -> tickWindowFade(dt, generation));
    }

    private boolean tickWindowFade(double dt, int generation) {
        if (generation != winFadeGeneration) {
            return false; // a newer fadeWindow superseded this ticker
        }
        NativeWindow w = window;
        if (w == null || w.isClosed()) {
            return false;
        }
        winFadeElapsed += dt;
        float t = winFadeSeconds > 0 ? (float) Math.min(1.0, winFadeElapsed / winFadeSeconds) : 1f;
        winFadeCurrent = winFadeFrom + (winFadeTarget - winFadeFrom) * smoothstep(t);
        w.setOpacity(winFadeCurrent);
        w.requestFrame();
        if (t >= 1f) {
            winFadeCurrent = winFadeTarget;
            w.setOpacity(winFadeCurrent);
            Runnable cb = winFadeOnArrive;
            winFadeOnArrive = null;
            if (cb != null) {
                cb.run();
            }
            return false;
        }
        return true;
    }

    /**
     * The bound window was destroyed. A window-fade that had not yet reached its
     * target will never tick again (a closed window renders no frames), so run
     * its pending arrival callback now; otherwise a dialog fading out when its
     * owner closes would leak an uncompleted result future. Runs at most once:
     * bumping the generation kills the abandoned ticker, and clearing the field
     * makes a later normal completion a no-op (and the callbacks it drives, like
     * {@code CompletableFuture.complete}, are themselves idempotent).
     *
     * <p>Focus is cleared first: widgets gate self-rescheduling work (e.g. the
     * text-caret blink chain) on {@code isFocused()}, and a widget that stays
     * "focused" in a dead scene would re-arm on the global UI queue forever,
     * waking the event loop, forcing frames on every live window, and pinning
     * this scene against GC. Clearing through {@link #setFocus} also runs the
     * normal focus-lost path (composition dropped, IME state torn down).
     */
    @Override
    public void windowClosed() {
        // Every step must run even when an earlier app callback throws: a
        // skipped Fonts.removeChangeListener pins this scene in the
        // process-wide listener list forever, and a skipped close observer is
        // exactly the abandoned completion the observer mechanism exists to
        // prevent. App-code steps are contained individually (fine-grained
        // WINDOW_CLOSE reports; the handler's verdict is not honored here).
        try {
            setFocus(null); // runs app focus-lost handlers
        } catch (Throwable error) {
            LOG.log(Level.ERROR, "focus-lost handler threw during window close; teardown continues", error);
            limn.backend.Crashes.report(limn.backend.CrashPhase.WINDOW_CLOSE, error);
        }
        limn.graphics.Fonts.removeChangeListener(metricsListener);
        ControlSize.removeChangeListener(metricsListener);
        LayoutDirection.removeChangeListener(metricsListener);
        Runnable cb = winFadeOnArrive;
        winFadeOnArrive = null;
        winFadeGeneration++;
        if (cb != null) {
            try {
                cb.run();
            } catch (Throwable error) {
                LOG.log(Level.ERROR, "window-fade completion threw during window close; teardown continues", error);
                limn.backend.Crashes.report(limn.backend.CrashPhase.WINDOW_CLOSE, error);
            }
        }
        // Same flush for ticker-driven completions (e.g. an in-scene dialog's
        // fade-out): tickers only advance while frames render, and a closed
        // window renders none, so anything waiting on one must finish NOW.
        for (Runnable observer : List.copyOf(windowCloseObservers)) {
            try {
                observer.run();
            } catch (Throwable error) {
                LOG.log(Level.ERROR, "window-close observer threw; the remaining observers still run", error);
                limn.backend.Crashes.report(limn.backend.CrashPhase.WINDOW_CLOSE, error);
            }
        }
        windowCloseObservers.clear();
    }

    private final List<Runnable> windowCloseObservers = new ArrayList<>();

    /**
     * Runs {@code observer} when the bound window is destroyed, the hook for
     * completion work driven by tickers/frames, which stop forever at that
     * point (an abandoned fade would otherwise leak an uncompleted future).
     * Observers run once and are dropped; the returned handle unregisters
     * earlier (call it when the normal path completed first).
     */
    public Runnable observeWindowClosed(Runnable observer) {
        windowCloseObservers.add(Objects.requireNonNull(observer, "observer"));
        return () -> windowCloseObservers.remove(observer);
    }

    /** Smooth (ease-in-out) 0→1 ramp shared by the modal scrim and window fades. */
    private static float smoothstep(float t) {
        float x = Math.max(0f, Math.min(1f, t));
        return x * x * (3f - 2f * x);
    }

    /** Runs measure/layout when dirty or resized (public for headless tests/embedding). */
    public void layoutPass(float newWidth, float newHeight) {
        if (!layoutDirty && newWidth == width && newHeight == height) {
            // Contained requests are still owed a pass: this is the entry point a headless test
            // or an embedder calls instead of rendering a frame, and without this a list that
            // scrolled would sit on stale rows until something else dirtied the layout.
            runContainedLayouts();
            if (!layoutDirty) {
                return;
            }
            // One of them could not keep its promise, so do the full pass it escalated to, now,
            // rather than leaving the caller with a layout that is neither.
        }
        containedLayouts.clear(); // a full pass covers every contained request outstanding
        width = newWidth;
        height = newHeight;
        root.measure(Constraints.tight(newWidth, newHeight));
        root.layoutBox(0, 0, newWidth, newHeight);
        for (Widget overlay : overlays) {
            overlay.measure(Constraints.tight(newWidth, newHeight));
            overlay.layoutBox(0, 0, newWidth, newHeight);
        }
        layoutDirty = false;
        if (pendingReveal != null) {
            Widget reveal = pendingReveal;
            pendingReveal = null;
            // Only if it is still the focus and still in this tree: focus may
            // have moved (or the widget detached) between defer and layout.
            if (reveal == focused && reveal.scene() == this) {
                reveal.revealInView();
            }
        }
    }
}
