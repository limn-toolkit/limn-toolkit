package limn.scene;

import limn.backend.Cursor;
import limn.backend.NativeWindow;
import limn.backend.WindowInput;
import limn.concurrent.internal.Listeners;
import limn.concurrent.Subscription;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.GpuSurface;
import limn.graphics.Rect;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.math.Scalars;
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
public final class Scene {

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

    private record RawButton(int button, boolean pressed, int mods, float x, float y, int clicks) implements Raw {
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

    private final Widget<?> root;
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

    private Widget<?> hovered;
    private Widget<?> pressed;
    private boolean pointerInside; // last CursorEnter/move state, for hover resync
    private int pressedButton = -1;
    private float mouseX;
    private float mouseY;
    private Widget<?> focused;

    private final List<Widget<?>> overlays = new ArrayList<>();

    /** The overlays pushed as popups rather than as modal layers of their own, by identity. */
    private final java.util.Set<Widget<?>> popupOverlays =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    // Widget that held focus when each overlay was pushed, restored on removal
    // (parallel to overlays), so closing a modal returns focus to its trigger.
    private final List<Widget<?>> overlayFocusReturn = new ArrayList<>();

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

    /**
     * A scene over {@code root}. Subscribes to font, size-step and language changes.
     *
     * @param root the widget tree to lay out, paint and dispatch to; never null
     */
    public Scene(Widget<?> root) {
        this(root, System::nanoTime);
    }

    /**
     * The clock this scene's animations tick on, in nanoseconds: the injected one, or the wall
     * clock by default. Package-private; a widget reaches it through {@link Widget#sceneNanos()}.
     */
    long nanoTime() {
        return clock.getAsLong();
    }

    /**
     * The clock this scene's animations tick on, for a scene built to stand beside it.
     *
     * <p>A surface this scene opens in a window of its own — a combo's list, a menu's cascade, a
     * date picker's calendar, a dialog's card — is the root of a <b>second</b> scene, and that
     * scene owns the fade whose last frame destroys the window ({@link #fadeWindowOut}). Built on
     * the wall clock while its opener runs on an injected one, that fade advances by real
     * microseconds however much time the opener's clock is told has passed, so the window never
     * goes: a headless harness could open a popup and never close it, and did (the accessibility
     * gallery's, 2026-09-15). So the four surfaces pass this along, and a popup's time is its
     * opener's time. With no clock injected both are {@link System#nanoTime}, which is every
     * application.
     *
     * @return what this scene reads as now
     */
    public LongSupplier clock() {
        return clock;
    }

    /**
     * Injectable clock (slow-handler instrumentation and animation ticks),
     * public so component tests can drive animations deterministically.
     */
    public Scene(Widget<?> root, LongSupplier clock) {
        this.root = Objects.requireNonNull(root, "root");
        this.clock = clock;
        root.setSceneRecursively(this);
        // Subscribed HERE, not in bind(): an unbound scene (every component test, a
        // ComboBox popup scene, a Dialog's modal scene between construction and bind)
        // would otherwise never hear a global font or control-size change, and because
        // layoutPass early-returns on !layoutDirty at an unchanged size, nothing would
        // re-measure. Registration is idempotent, so bind()'s repair is harmless.
        metricsListener.armed(
                limn.graphics.Fonts.observeChanges(metricsListener),
                ControlSize.observeChanges(metricsListener),
                LayoutDirection.observeChanges(metricsListener),
                limn.i18n.I18n.observeChanges(metricsListener));
    }

    private limn.graphics.TextRuler textRuler;

    /** Overrides the text measurer (tests use deterministic fake metrics). */
    public void setTextRuler(limn.graphics.TextRuler ruler) {
        Ui.checkUiThread();
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
     * Answers a <b>process-wide</b> change for one scene: a re-measure when an input to
     * measurement moves — the UI font family/catalog ({@link limn.graphics.Fonts}), the process
     * default control size ({@link ControlSize}), the process default layout direction
     * ({@link LayoutDirection}) or the language ({@link limn.i18n.I18n}) — and a repaint when the
     * palette does ({@link limn.components.Theme}).
     *
     * <p>Holds the scene WEAKLY: a scene replaced on a live window (a new scene bound over it)
     * never receives {@code windowClosed}, and a strong process-wide listener would pin the
     * abandoned tree forever; when the scene is collected, the wrapper cancels its own
     * subscriptions on the next change.
     *
     * <p><b>It holds its own handles, and that placement is the whole of why the purge still
     * works.</b> A handle-only channel has no removal by identity, so something has to keep the
     * handles — and the holder of a handle must outlive the thing the handle releases. The
     * {@code Scene} does not qualify: by the time the purge is wanted the scene has been
     * collected and any handle it held with it, leaving one dead entry per scene ever
     * constructed in every axis's array, forever. The listener does qualify, and holding them
     * here also makes the purge O(1) per axis instead of an identity scan.
     *
     * <p><b>The response is a field and not the method body</b>, because {@code run()} takes no
     * argument and no axis hands it one: one instance on several axes can only answer them all
     * identically, so an axis whose answer differs takes an instance of its own carrying its own
     * response. The four measurement axes share one, answering {@code relayout()}.
     *
     * <p><b>The palette is not among them, and that is a package boundary rather than a
     * judgement about what a palette costs.</b> {@code Theme} is a {@code limn.components} type
     * and this is {@code limn.scene}, the layer that package is built on -- no file here has ever
     * named one, and a repaint is not worth being the first. So a palette switch is still the
     * application's to answer, with the {@code invalidate()} or {@code relayout()} it already
     * writes; what {@code Theme} gained is the same {@code observeChanges} handle the four axes
     * hand back, so a subscriber there writes no list of its own.
     */
    private static final class GlobalAxisListener implements Runnable {
        private final java.lang.ref.WeakReference<Scene> scene;
        private final java.util.function.Consumer<Scene> response;
        private Subscription[] handles;

        GlobalAxisListener(Scene scene, java.util.function.Consumer<Scene> response) {
            this.scene = new java.lang.ref.WeakReference<>(scene);
            this.response = response;
        }

        /** Takes the handles for this instance's own registrations, right after they are made. */
        void armed(Subscription... registered) {
            this.handles = registered;
        }

        /** Cancels them, once: the scene is gone, or the window it was bound to has closed. */
        void release() {
            Subscription[] taken = handles;
            if (taken == null) {
                return;
            }
            handles = null;
            for (Subscription handle : taken) {
                handle.cancel();
            }
        }

        @Override
        public void run() {
            Scene target = scene.get();
            if (target != null) {
                response.accept(target);
                return;
            }
            // A change delivered between the registration and armed() finds no handles; the next
            // one purges, which is the same "purged on the next change" the mechanism promised
            // when it purged by identity.
            release();
        }
    }

    private final GlobalAxisListener metricsListener = new GlobalAxisListener(this, Scene::relayout);

    /** Wires this scene into a window: input, frame rendering, invalidation, clipboard. */
    public void bind(NativeWindow window) {
        this.window = window;
        limn.backend.AccessibilityBridge attached = window.accessibility();
        this.bridge = attached != null ? attached : limn.backend.AccessibilityBridge.NONE;
        // Asked once, remembered, and never asked again: only the scene needs to know that it owes
        // a first tree to a bridge whose own gate cannot open until it has one.
        this.primingPublishOwed = this.bridge.needsPrimingPublish();
        this.accessibleNodesDirty = true;
        // attach() and not a tree: at this instant the scene has never laid out, so every box in
        // it would be a zero-size rectangle at the origin. The first frame is where the boxes are.
        this.bridge.attach(accessibilityHost());
        window.setInput(input);
        this.renderRequester = window::requestFrame;
        window.setFrameCallback((renderer, frame) ->
                renderFrame(renderer.canvas(), frame.rePresent(), frame.gpuFrameMs(), frame.bufferAge()));
        // Last, and after the two calls that make this window usable. It walks and publishes, which
        // is more than the rest of this method does, and a bind that threw here would leave the
        // window holding neither input nor a frame callback — a window that is up, attached and
        // dead. Nothing above it waits for it: the frame it would be read from cannot run until the
        // pump turns, which is after bind returns (2026-09-16 review).
        publishTheWindowNodeIfOwed();
    }

    /**
     * The window's own node, published at the bind for the one bridge that can be asked whether this
     * window has accessibility at all before a frame has run.
     *
     * <p>It does not walk the scene and it is not the priming publish, which stays where it is: the
     * first frame still publishes the real tree, with the boxes layout has by then made real, and
     * this is only what stands in the meantime so that the answer to the platform's question is
     * "yes, and here it is" rather than "nothing here". A client that asks before the first frame
     * and is told no does not ask again — measured, with the count of runs it decided, in
     * {@link limn.backend.AccessibilityBridge#needsRootBeforeTheFirstFrame}.
     *
     * <p><b>Emitted like any other publish.</b> A first draft published without emitting, on the
     * reasoning that a window appearing is the platform's own news; what that actually dropped was
     * {@code WINDOW_ACTIVATED}, which the difference reserves when the window node arrives already
     * {@code ACTIVE} and which the first frame then cannot reserve again because by then it was
     * active before (2026-09-16 review, measured on a scene bound while focused). On Windows that
     * is the event that raises focus into the window — the same silence this whole change is about.
     * A publish emits what its own difference found, here as everywhere.
     */
    private void publishTheWindowNodeIfOwed() {
        if (window == null || !bridge.needsRootBeforeTheFirstFrame()) {
            return;
        }
        // The window's own size and not the scene's: width and height here are zero until the first
        // layoutPass assigns them, and the window has had a size since it was created.
        accessibleWalk().walkWindowOnly(this, window.logicalWidth(), window.logicalHeight());
        limn.accessibility.AccessibleTree tree = accessibleWalk.publish(
                window.screenX(), window.screenY(), window.logicalToScreenFactor(),
                window.supportsAbsolutePositioning());
        publishedTree = tree;
        bridge.publish(tree, false);
        List<limn.accessibility.AccessibleEvent> events = accessibleWalk.builder().events();
        for (int i = 0; i < events.size(); i++) {
            bridge.emit(events.get(i));
        }
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
        Ui.checkUiThread();
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
    public Widget<?> root() {
        return root;
    }

    /**
     * The first widget whose {@linkplain Widget#setId id} is {@code id}, searching the root's tree
     * in order, parents before children, then each overlay in the order it was pushed. A hidden
     * widget is found like a shown one. UI thread only.
     *
     * @param id the id to look for
     * @return the widget, or {@code null} when none has that id
     */
    public Widget<?> find(String id) {
        Ui.checkUiThread();
        Objects.requireNonNull(id, "id");
        Widget<?> found = root == null ? null : findIn(root, id);
        for (int i = 0; found == null && i < overlays.size(); i++) {
            found = findIn(overlays.get(i), id);
        }
        return found;
    }

    /**
     * {@link #find(String)}, typed.
     *
     * @param id the id to look for
     * @param type the class the widget is expected to be
     * @param <T> that class
     * @return the widget, or {@code null} when none has that id
     * @throws ClassCastException when the widget with that id is not a {@code type}
     */
    public <T> T find(String id, Class<T> type) {
        return type.cast(find(id));
    }

    private static Widget<?> findIn(Widget<?> widget, String id) {
        if (id.equals(widget.id())) {
            return widget;
        }
        for (Widget<?> child : widget.children()) {
            Widget<?> found = findIn(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
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

    private java.util.Locale locale; // null = fall through to a hosted root's host link

    /**
     * @return this scene's default locale, or {@code null} to fall through to a hosted root's
     *         {@linkplain Widget#setInheritanceHost host link} and then to
     *         {@link limn.i18n.I18n#processLocale()}. <b>Nullable by design</b>, for the reason
     *         {@link #controlSize()} is: a popup's own scene declares nothing, which is what
     *         lets it inherit from the widget that opened it.
     */
    public java.util.Locale locale() {
        return locale;
    }

    /**
     * Sets this window's default locale, the per-window root of the inheritance chain: what makes
     * two windows in two languages expressible, which one process-wide locale could not say.
     * Widgets that declare their own locale, and their subtrees, are unaffected. {@code null}
     * restores fall-through. Retains the declared locale's bundle tables exactly as
     * {@link Widget#setLocale} does. UI thread only.
     */
    public void setLocale(java.util.Locale locale) {
        Ui.checkUiThread();
        if (Objects.equals(this.locale, locale)) {
            return;
        }
        java.util.Locale previous = this.locale;
        this.locale = locale;
        if (locale != null) {
            limn.i18n.I18n.retainLocale(locale);
        }
        if (previous != null) {
            limn.i18n.I18n.releaseLocale(previous);
        }
        Widget.bumpLocaleEpoch();
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
    public void pushOverlay(Widget<?> overlay) {
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

    /**
     * Pushes {@code popup} as {@link #pushOverlay} does — it captures all input and confines
     * focus — for a surface that opens from a control and belongs to it: a combo's list, a menu,
     * a date picker's calendar. What differs is what an assistive technology is told about the
     * page beneath: under a modal overlay it is published not enabled, because a dialog blocks
     * its owner as a native modal disables the window behind it; under a popup it stays enabled
     * and merely offers no verb, because a native drop-down list does not disable the field it
     * opened from. Closed with {@link #removeOverlay}.
     *
     * @param popup the popup's root widget
     */
    public void pushPopup(Widget<?> popup) {
        Ui.checkUiThread();
        Objects.requireNonNull(popup, "popup");
        pushOverlay(popup);
        popupOverlays.add(popup);
    }

    /**
     * @param overlay an overlay of this scene
     * @return whether it was pushed with {@link #pushPopup}
     */
    boolean isPopup(Widget<?> overlay) {
        return popupOverlays.contains(overlay);
    }

    /**
     * Whether every overlay above the one at {@code index} was pushed as a popup, so what lies
     * at {@code index} is beneath popups alone and is published enabled.
     *
     * @param index an overlay's position in the stack, or {@code -1} for the root
     * @return true when at least one overlay lies above and every one of them is a popup
     */
    boolean onlyPopupsAbove(int index) {
        if (index + 1 >= overlays.size()) {
            return false;
        }
        for (int i = index + 1; i < overlays.size(); i++) {
            if (!popupOverlays.contains(overlays.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Removes a modal overlay and restores focus to whatever it took it from. */
    public void removeOverlay(Widget<?> overlay) {
        Ui.checkUiThread();
        int index = overlays.indexOf(overlay);
        if (index < 0) {
            return;
        }
        popupOverlays.remove(overlay);
        boolean wasTop = index == overlays.size() - 1;
        overlays.remove(index);
        Widget<?> restore = overlayFocusReturn.remove(index);
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
            // The overlay closing is what moved the focus, not whoever closed it.
            setFocus(restore, Change.Origin.ADJUSTMENT);
        } else {
            setFocus(null, Change.Origin.ADJUSTMENT);
        }
        requestRender();
    }

    /** @return the topmost modal overlay, or {@code null} when none is open */
    Widget<?> topOverlay() {
        return overlays.isEmpty() ? null : overlays.get(overlays.size() - 1);
    }

    /** @return the widget subtree that currently owns input (top overlay or root) */
    private Widget<?> inputRoot() {
        Widget<?> top = topOverlay();
        return top != null ? top : root;
    }

    /**
     * The layer an assistive technology's verb may operate: the layer that owns input, or
     * {@code null} when the window's backend says a modal is open over it and no layer here owns
     * input at all. The one rule both halves of the accessibility model read: the walk publishes
     * every node outside it without {@code ENABLED}, {@code FOCUSABLE} or a verb &mdash; and so
     * accepting no setter, which a facet implies only on an {@code ENABLED} node &mdash; and
     * {@link #performAccessibleAction} refuses every verb on a widget outside it, so the list a
     * platform is answered from is the list the scene performs.
     *
     * @return the top overlay, the root when no overlay is open, or {@code null} while a native
     *         modal blocks the window
     */
    Widget<?> accessibleInputLayer() {
        if (window != null && window.isModalBlocked()) {
            return null;
        }
        return inputRoot();
    }

    /**
     * The colour cleared behind the tree each frame. A translucent one only shows
     * through where the window itself is translucent.
     */
    public void setBackground(Color color) {
        Ui.checkUiThread();
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
        Ui.checkUiThread();
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
    private boolean partialRendering = true; // ADR 043: repaint what changed, name every exception
    private boolean damageDebug;
    private boolean fullDamagePending = true; // first frame paints everything
    // Damage is a SMALL LIST of rects, not one bounding box: a progress bar
    // animating at the top and a status footer at the bottom must not conspire
    // to repaint everything between them. Rects whose union wastes little area
    // merge on arrival; disjoint hot spots stay separate repaint passes.
    // Damage semantics throughout (DamageRects): whole = the whole scene, empty = nothing.
    private static final int MAX_DAMAGE_RECTS = 8;
    /**
     * Widgets whose picture is made of the pixels behind them, kept as a list rather than found
     * by walking: the walk would be per frame and would cost the same whether the count is zero
     * or two, and it is zero in almost every application. Maintained on the one funnel a subtree
     * joins and leaves a scene through, so it cannot drift from the tree.
     */
    private final List<Widget<?>> backdropDependants = new ArrayList<>();
    // The frame's damage, as fixed-capacity float lists rather than lists of Rect: every
    // invalidate() lands in pendingDamage, and with partial rendering on by default an object per
    // call there was the toolkit's busiest allocation. See DamageRects. A list that starts whole
    // is what null meant before: a first frame has no history and repaints everything.
    private final DamageRects pendingDamage = new DamageRects(MAX_DAMAGE_RECTS, false);
    private final DamageRects freshDamage = new DamageRects(MAX_DAMAGE_RECTS, false);
    private final DamageRects frameDamage1 = new DamageRects(MAX_DAMAGE_RECTS, true); // previous frame's fresh damage
    private final DamageRects frameDamage2 = new DamageRects(MAX_DAMAGE_RECTS, true); // and the one before it
    private final DamageRects olderDamage = new DamageRects(MAX_DAMAGE_RECTS, true); // scratch: the two unioned
    private final DamageRects repaintRegion = new DamageRects(MAX_DAMAGE_RECTS, false);
    private final DamageRects lastRepaintRegion = new DamageRects(MAX_DAMAGE_RECTS, true); // what it repainted
    /** Scratch for the clip walk, so damaging a widget makes no object. UI thread only. */
    private final float[] clipScratch = new float[4];
    private final float[] backdropScratch = new float[4];
    private boolean[] backdropTaken = new boolean[0];

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
     * window. A subtree that misses the pass region is skipped from the paint
     * walk too ({@code culledFromPaint}), as one that misses the canvas clip is
     * in every mode.
     *
     * <p><b>Default on</b>: the mode the toolkit is correct in is the one it runs,
     * and every widget is held to it by {@code DamageContractTest}. {@code false} is the escape
     * hatch for a scene that composites something the toolkit cannot see, and for a capture
     * harness that wants whole frames on purpose; nothing else should need it.
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
        Ui.checkUiThread();
        fullDamagePending = true;
        accessibleNodesDirty = true;
        renderRequester.run();
    }

    // ------------------------------------------------------- accessibility flags
    //
    // Two flags, cleared separately, and the separation is the whole saving. The NODE flag says
    // something in the tree may have moved and costs a walk and a comparison; the HEADER flag says
    // only where the window is has moved and costs four numbers. A window drag would otherwise
    // re-walk the entire tree on every callback the compositor sends, to discover that every
    // per-node box — which is scene-local — is exactly where it was.
    //
    // The node flag rides the funnel every repaint goes through, not the structural funnels alone.
    // That is the correction the first draft of ADR 039 needed: a checkbox toggle, a slider move
    // and a keystroke are repaints and nothing else, so a flag hung on attach, focus and layout
    // would raise nothing for three quarters of what a screen reader exists to report.

    /** Something a node publishes may have changed: the next frame walks and compares. */
    private boolean accessibleNodesDirty = true;

    /** Only the window stamp moved: the next frame re-stamps the published tree and walks nothing. */
    private boolean accessibleHeaderDirty;

    /**
     * {@link Widget#invalidateAccessible()}: sets the node flag whatever is listening, and buys
     * the frame that reads it only when something is.
     */
    void invalidateAccessible() {
        accessibleNodesDirty = true;
        if (accessibilityLive()) {
            scheduleFrame();
        }
    }

    /**
     * Whether a bridge is attached and an assistive technology is listening to it.
     *
     * <p>The gate the whole cost argument rests on. With nothing listening the flags above are
     * still maintained, so switching a bridge on mid-session needs no audit of what was missed;
     * what is not spent is the frame.
     */
    private boolean accessibilityLive() {
        return bridge != limn.backend.AccessibilityBridge.NONE && bridge.isListening();
    }

    /**
     * Says something out loud to whoever is using an assistive technology: a status that changed
     * where nothing on screen says so, a background task that finished, an error that has no
     * control to attach itself to.
     *
     * <p>It is on the scene and not on a widget because it is a message to the user rather than a
     * property of a box. Nothing is announced automatically: a status label that updates silently
     * stays silent until an application adds this call.
     *
     * <p><b>It buys the frame that delivers it</b>, and only while something is listening. The
     * steady state while a screen reader is reading a quiet interface is a loop parked with no
     * frame pending, and an announcement enqueued into that state would never be spoken — the
     * application's one way of saying something out loud going silent exactly when the interface
     * is quiet, which is when it is most likely to be used. With nothing listening the entry is
     * still queued and still bounded, so an application's diagnostics do not depend on a reader
     * being present, and no frame is spent on speech nobody will hear.
     *
     * <p>UI thread only.
     *
     * @param text       what to say; resolved under this scene's own language when it is drained
     * @param politeness whether it waits for the assistive technology to finish, or interrupts it.
     *                   Not a boolean, because all three platforms carry the distinction and it
     *                   decides whether a user is cut off mid-sentence.
     * @throws NullPointerException if either argument is {@code null}
     */
    public void announce(limn.i18n.I18nString text,
                         limn.accessibility.Accessible.Politeness politeness) {
        Ui.checkUiThread();
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(politeness, "politeness");
        if (announcements.size() >= MAX_ANNOUNCEMENTS) {
            // An application announcing once per frame is a defect the slow-task budget already
            // catches; dropping the oldest keeps this bounded without hiding the newest.
            announcements.remove(0);
        }
        announcements.add(new Announcement(text, politeness));
        if (accessibilityLive()) {
            scheduleFrame();
        }
    }

    /**
     * Says something out loud with a fixed string. UI thread only.
     *
     * @param text       what to say
     * @param politeness whether it waits, or interrupts
     * @throws NullPointerException if either argument is {@code null}
     */
    public void announce(String text, limn.accessibility.Accessible.Politeness politeness) {
        announce(limn.i18n.I18nString.literal(Objects.requireNonNull(text, "text")), politeness);
    }

    // ------------------------------------------------------- the accessible tree

    private limn.backend.AccessibilityBridge bridge = limn.backend.AccessibilityBridge.NONE;
    private limn.backend.AccessibilityBridge.Host accessibilityHost;
    private AccessibleWalk accessibleWalk;

    /**
     * The tree a reader on any thread answers from.
     *
     * <p>{@code volatile}, written by the user-interface thread with a fully constructed immutable
     * object and read from wherever a platform gave a bridge its thread. That single write is the
     * whole of the synchronisation between the two sides, and it is enough because everything on
     * the other end of it is a value.
     */
    private volatile limn.accessibility.AccessibleTree publishedTree =
            limn.accessibility.AccessibleTree.EMPTY;

    /** Whether the one bridge that needs a tree before its gate can open is still owed one. */
    private boolean primingPublishOwed;

    /** Whether a layout pass has ever run, which is what makes the boxes real rather than zero. */
    private boolean hasLaidOut;

    private limn.backend.AccessibilityBridge.Host accessibilityHost() {
        if (accessibilityHost == null) {
            accessibilityHost = new Host();
        }
        return accessibilityHost;
    }

    /**
     * The walk that describes this scene, created on first use: another window's walk asks for
     * it when a popup there names a widget here as its opener, and the tests of
     * the walk read its builder through it.
     *
     * @return this scene's walk
     */
    AccessibleWalk accessibleWalk() {
        if (accessibleWalk == null) {
            accessibleWalk = new AccessibleWalk();
        }
        return accessibleWalk;
    }

    /**
     * The identifier this scene last published for one of its widgets, for a relation declared
     * in another window's walk that names it: a native popup's root naming the field that opened
     * it, or that field naming the popup's root back.
     *
     * @param widget a widget of this scene
     * @return the identifier of its node in this scene's last walk, or {@code 0} when that walk
     *         published none for it or no walk has run
     */
    long accessibleIdOf(Widget<?> widget) {
        return accessibleWalk == null ? 0 : accessibleWalk.idOfWidget(widget);
    }

    /**
     * The tree this scene last published, for another window's walk that reads the cursor of a
     * popup this scene draws: a value, read from the user-interface thread like
     * everything else the walk touches.
     *
     * @return the tree, or the empty one when nothing was published yet
     */
    limn.accessibility.AccessibleTree publishedTree() {
        return publishedTree;
    }

    /** The scene-side half a bridge holds, and the only way a platform reaches toolkit state. */
    private final class Host implements limn.backend.AccessibilityBridge.Host {

        @Override
        public void requestRepublish() {
            Ui.post(() -> {
                accessibleNodesDirty = true;
                // Unconditionally, unlike every other accessibility path that buys a frame: the
                // caller is a bridge that has just been asked for a tree.
                scheduleFrame();
            });
        }

        @Override
        public void requestRestamp() {
            Ui.post(() -> {
                accessibleHeaderDirty = true;
                scheduleFrame();
            });
        }

        @Override
        public limn.accessibility.AccessibleTree republishNow() {
            Ui.checkUiThread();
            if (!hasLaidOut) {
                // Nothing truthful can be said about a window whose widgets have no boxes. A tree
                // of zero-size rectangles in the corner is worse than an empty one, because it
                // looks like an answer.
                scheduleFrame();
                return publishedTree;
            }
            if (!accessibleNodesDirty && !accessibleHeaderDirty) {
                return publishedTree;
            }
            if (!accessibleNodesDirty) {
                restampAccessibleTree(true); // the platform's pump is on the stack: see the method
                scheduleFrame();
                return publishedTree;
            }
            publishAccessibleTree(true);
            scheduleFrame();
            return publishedTree;
        }

        @Override
        public boolean perform(long nodeId, limn.accessibility.Accessible.Action action,
                               limn.accessibility.Accessible.Argument arg) {
            Objects.requireNonNull(action, "action");
            limn.accessibility.Accessible.Argument argument =
                    arg != null ? arg : limn.accessibility.Accessible.Argument.NONE;
            // The one refusal that can honestly be immediate: an immutable read, safe from any
            // thread. Every other precondition is a fact about live widgets and is re-checked on
            // the thread that owns them, when the task arrives.
            if (publishedTree.indexOf(nodeId) == limn.accessibility.AccessibleNode.NONE) {
                return false;
            }
            Ui.post(() -> performAccessibleAction(nodeId, action, argument));
            return true;
        }
    }

    /**
     * Runs one action on the thread that owns the widget tree, having re-checked everything the
     * snapshot could not promise.
     */
    private void performAccessibleAction(long nodeId, limn.accessibility.Accessible.Action action,
                                         limn.accessibility.Accessible.Argument arg) {
        if (accessibleWalk == null) {
            return;
        }
        Widget<?> owner = accessibleWalk.ownerOf(nodeId);
        if (owner == null || owner.scene() != this) {
            return;
        }
        boolean synthetic = accessibleWalk.isSynthetic(nodeId);
        // A verb the owner's container claimed on it (ADR 039 §1.5, amended 2026-09-14): still
        // gated on the owner below, because the node the reader addressed is the child's, and
        // routed to the container, which is the only thing that can perform it. The container
        // is re-checked as the child's parent: a cell re-mounted elsewhere since the walk would
        // hand the container a child it no longer holds.
        boolean delegated = !synthetic && accessibleWalk.isDelegated(nodeId, action);
        Widget<?> container = delegated ? accessibleWalk.delegateOf(nodeId) : null;
        if (delegated && owner.parent() != container) {
            return;
        }
        boolean free = !synthetic && !delegated && owner.isFocusable() && isFreeVerb(action);
        // The showing axis of semantics 5, decision 66 (2026-09-15): ONE test for every verb, and
        // it is visibility through the ancestry rather than showing.
        //
        // What it was until now: the free verbs were gated on visibility, and everything else on
        // the owner showing (a delegated verb on the container showing). So a control merely
        // scrolled out of a viewport published PRESS, INCREMENT and its setters -- the snapshot is
        // the only synchronous authority a bridge has, and Host#perform had already said yes --
        // and then this gate dropped the verb in silence. "Chapter 20" below the fold, the media
        // bar's volume slider, a colour picker's rails in a scrolled panel: every one of them a
        // control a reader is shown and cannot work.
        //
        // Both halves of the answer are here. A node that is visible through its ancestry and
        // merely clipped is revealed and performed, which is what the free verbs have always done
        // and what a sighted user gets by reaching for it. A node that is not visible at all --
        // an unselected tab's contents, a collapsed panel -- is refused, and the walk publishes no
        // verb and no setter there (AccessibleWalk's inoperableAt, AccessibleNode#accepts), so the
        // refusal and the snapshot say one thing. That is what makes this gate and `accepts` one
        // reading of one fact instead of two that drift.
        //
        // The delegated case needs no clause of its own any more. The container performs the verb
        // and the child is the node the reader addressed; climbing from the child passes through
        // the container, so one call covers both, and a container clipped off the glass is
        // revealed rather than refused (which is what decision 20's kept cursor row wanted all
        // along, and what SCROLL_INTO_VIEW was exempted for).
        if (!isVisibleThroughAncestry(owner)) {
            return;
        }
        // The setters included: SET_VALUE and SET_TEXT on a disabled widget or under a disabled
        // ancestor stop here, and so do they outside the layer below. That is the pair of facts
        // the snapshot carries as a missing ENABLED, which is all a bridge reads to refuse a
        // setter synchronously (AccessibleNode#accepts; semantics 5, amended 2026-09-15 in fix
        // round 2e): the node keeps its true writability, and this gate is its refusal.
        for (Widget<?> at = owner; at != null; at = at.parent()) {
            if (!at.isEnabled()) {
                return; // a control inside a disabled container is one the keyboard refuses too
            }
        }
        // Not past the top of an overlay's subtree to its inheritance host (ADR 039 §1.9, amended
        // 2026-09-15): the keyboard and the pointer do not climb there either, and the walk
        // publishes an overlay's contents on the same chain this loop reads, so a dialog shown
        // from a control disabled since is performed as it is published and as Return answers it.
        // Two tests and not one, both inside accessibleInputLayer(), which the walk reads too. A
        // window's own modal flag answers false for the host of an in-scene modal by
        // construction, so gating on it alone would invoke a button underneath an open dialog;
        // and a snapshot can predate the modal, so the layer that owns input has to be re-checked
        // here whatever the tree said.
        Widget<?> layer = accessibleInputLayer();
        if (layer == null || !isInSubtree(owner, layer)) {
            return;
        }
        // Revealed, and then performed (decision 66). Only what is clipped out of a viewport ever
        // gets here not showing: everything not visible was refused above. The free verbs do
        // their own revealing -- SCROLL_INTO_VIEW is the reveal, and requestFocus() reveals on
        // arrival -- so they are left to it rather than made to do it twice. A widget with no
        // scrollable ancestor is already in view and revealInView() is a no-op there, so this
        // costs nothing in the ordinary case where the node was on the glass all along.
        //
        // Only for a verb the node published, read off the snapshot the reader was answered from.
        // A reveal is a visible effect, and a verb the node never published has to move NOTHING:
        // without this test, sending TOGGLE to a button scrolled out of view scrolled the pane
        // and then refused the toggle, so "a node refuses every verb it does not publish" would
        // have become "a node scrolls itself and then refuses", which is the promise
        // VerbPolicyRatchetTest exists to hold.
        // Whose box is revealed is whose showing the gate used to refuse on: the owner's for a
        // verb the owner performs, and the container's for one it claimed on a child. A container
        // that is on the glass and holds a child outside its own viewport reveals nothing, which
        // is decision 22 as the tree lane settled it: the kept cursor row is expanded, selected
        // and deselected where it stands, and SCROLL_INTO_VIEW is the one verb that moves the
        // view, through the container's own hook, which knows where a recycled row really is.
        Widget<?> reveal = delegated ? container : owner;
        if (!free && !reveal.isShowing()) {
            limn.accessibility.AccessibleNode node = publishedTree.find(nodeId);
            if (node != null && node.accepts(action)) {
                reveal.revealInView();
            }
        }
        boolean done;
        if (free) {
            done = performFreeVerb(owner, action);
        } else if (synthetic) {
            done = owner.performSyntheticAction(accessibleWalk.keyOf(nodeId), action, arg);
        } else if (delegated) {
            done = container.performChildAction(owner, accessibleWalk.keyOf(nodeId), action, arg);
        } else {
            done = owner.performAccessibleAction(action, arg);
        }
        if (done && action == limn.accessibility.Accessible.Action.PRESS && accessibilityLive()) {
            bridge.emit(limn.accessibility.AccessibleEvent.of(
                    limn.accessibility.AccessibleEvent.Type.INVOKED, nodeId));
        }
    }

    /**
     * @param widget a widget attached to this scene
     * @return whether it is visible by its own flag and every ancestor's, whether or not it is
     *         clipped out of a scroll viewport
     */
    private static boolean isVisibleThroughAncestry(Widget<?> widget) {
        for (Widget<?> at = widget; at != null; at = at.parent()) {
            if (!at.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** @return whether the walk hands {@code action} to every focusable node of its own accord */
    private static boolean isFreeVerb(limn.accessibility.Accessible.Action action) {
        return action == limn.accessibility.Accessible.Action.FOCUS
                || action == limn.accessibility.Accessible.Action.SCROLL_INTO_VIEW;
    }

    /**
     * The walk's two free verbs, performed here rather than by every widget.
     *
     * <p>A focusable widget gets {@code FOCUS} and {@code SCROLL_INTO_VIEW} for free, because
     * {@code requestFocus()} and {@code revealInView()} exist for every widget, and the walk duly
     * advertises both on every focusable node and on no other. Nothing performed them: the hook's
     * default refuses, and no component wrote the two lines &mdash; so on Windows every element's
     * {@code SetFocus} did nothing and every {@code ScrollIntoView} failed. The verbs are the
     * walk's, so the answer is the walk's counterpart and not thirty copies of the same pair, one
     * of which would be forgotten.
     *
     * <p><b>Instead of the widget's hook and not after it.</b> The walk advertises these two on
     * a widget that never declared them, so the widget is not their authority and cannot be asked
     * to be: a hook that answered {@code true} for an action it never claimed &mdash; a shape a
     * test double takes and a real one could &mdash; would silently swallow a verb the tree had
     * promised, and one that answered {@code false} would make the scene's answer depend on
     * whether it had been asked first. Both routes are the scene's own in any case:
     * {@code requestFocus()} and {@code revealInView()} are final and go nowhere else.
     *
     * <p>Never for a synthetic node, for the reason the walk never advertises them there: a thing
     * a widget paints is not a tab stop, holds no keyboard and has no box of its own to reveal.
     *
     * @param owner  the widget the node belongs to, focusable, and already checked for showing,
     *               enabled through its ancestry, unblocked and inside the layer that owns input
     * @param action {@code FOCUS} or {@code SCROLL_INTO_VIEW}
     * @return whether this performed it
     */
    private boolean performFreeVerb(Widget<?> owner,
                                    limn.accessibility.Accessible.Action action) {
        switch (action) {
            case FOCUS -> {
                owner.requestFocus();
                return focusedWidget() == owner;
            }
            case SCROLL_INTO_VIEW -> {
                owner.revealInView();
                return true; // a widget with no scrollable ancestor is already in view
            }
            default -> {
                return false;
            }
        }
    }

    /** The overlay stack, for the walk: overlays are a second root set and not part of the tree. */
    List<Widget<?>> overlays() {
        return overlays;
    }

    /** Whether {@code widget} is the layer that currently owns input. */
    boolean isTopOverlay(Widget<?> widget) {
        return topOverlay() == widget;
    }

    /**
     * The step that turns a frame into an accessible tree, run after layout and the hover update
     * and before the paint passes: bounds are settled there and hover has already moved whatever
     * it moves, and every change that matters to an assistive technology already schedules a frame,
     * so the frame is the flush point and no new scheduling is invented.
     */
    private void accessibilityStep(boolean rePresent) {
        publishStep(rePresent);
        // Every frame ends, whichever way the step above returned: announcements drained without
        // a walk, a re-present, a clean tree after a reentrant publish already took the walk. A
        // bridge that posts on this thread posts here, so what this frame said is told in this
        // frame and not held until the tree next changes (ADR 039 §5.3).
        bridge.frameEnded();
    }

    /** The step's publishing half: announcements, then the walk or the re-stamp, if either is owed. */
    private void publishStep(boolean rePresent) {
        boolean live = accessibilityLive();
        drainAnnouncements(live);
        if (rePresent) {
            // The whole contract of a re-present is to redraw the same pixels into the other
            // buffer. A walk that can only ever conclude "nothing changed" belongs in the list of
            // things it already skips.
            return;
        }
        if (!live && !primingPublishOwed) {
            return;
        }
        if (!accessibleNodesDirty && !accessibleHeaderDirty && !primingPublishOwed) {
            return;
        }
        if (!accessibleNodesDirty && !primingPublishOwed) {
            restampAccessibleTree(false); // a frame of the scene's own: nothing of the platform's
            return;
        }
        publishAccessibleTree(false);
    }

    /**
     * Above the re-present guard and above both flags, because an announcement is the application
     * speaking rather than a property of a node: a frame that changes nothing in the tree still
     * carries whatever was queued since the last one.
     */
    private void drainAnnouncements(boolean live) {
        if (announcements.isEmpty()) {
            return;
        }
        for (int i = 0; i < announcements.size(); i++) {
            Announcement queued = announcements.get(i);
            if (!live) {
                continue; // still drained, so the queue stays bounded; simply not spoken
            }
            java.util.Locale enclosing = limn.i18n.I18n.pushScope(
                    locale() != null ? locale() : limn.i18n.I18n.processLocale());
            String said;
            try {
                said = queued.text().get();
            } finally {
                limn.i18n.I18n.popScope(enclosing);
            }
            bridge.emit(limn.accessibility.AccessibleEvent.announcement(said,
                    queued.politeness()));
        }
        announcements.clear();
    }

    /**
     * A window that moved changed no box in the tree; only where the tree is.
     *
     * <p>The flag it hands {@link limn.backend.AccessibilityBridge#publish} is
     * <b>{@code reentrant}</b>, never "something changed": a restamp that changed nothing has
     * already returned above, and a bridge is told what it may touch, not what moved. The two
     * callers answer it differently and that is the whole point of the parameter. The frame's
     * publish step is the scene's own thread with nothing of the platform's on the stack, so it is
     * not reentrant and the bridge may sweep, re-push and drain. {@code Host#republishNow} is the
     * other one, and its contract says in so many words that it "publishes reentrantly, so the
     * bridge defers every registry obligation": it is called from inside the platform's own pump,
     * standing on the elements a sweep would release. Passing {@code false} there — which this did
     * until now, while the walk branch beside it passed {@code true} — invited a bridge to destroy,
     * re-push and drain under the caller on the one path that costs four numbers and so looked
     * harmless.
     *
     * @param reentrant whether the platform is on the stack, holding what this bridge vended
     */
    private void restampAccessibleTree(boolean reentrant) {
        accessibleHeaderDirty = false;
        limn.accessibility.AccessibleTree before = publishedTree;
        limn.accessibility.AccessibleTree after = before.restamp(
                window != null ? window.screenX() : 0,
                window != null ? window.screenY() : 0,
                window != null ? window.logicalToScreenFactor() : 1,
                window == null || window.supportsAbsolutePositioning());
        if (after == before) {
            return;
        }
        publishedTree = after;
        bridge.publish(after, reentrant);
        bridge.emit(limn.accessibility.AccessibleEvent.of(
                limn.accessibility.AccessibleEvent.Type.BOUNDS_CHANGED, 0));
    }

    /**
     * Walks, compares, and publishes only a difference.
     *
     * <p>Damage is a coarse trigger — a caret blink, a hover ripple and a tween all damage
     * something and change no accessible fact — so the walk writes into a buffer this scene owns
     * and reuses, comparing as it goes. Only a difference costs the immutable copy a reader may
     * hold.
     *
     * @param reentrant whether the platform is on the stack, holding what this bridge vended
     */
    private void publishAccessibleTree(boolean reentrant) {
        accessibleWalk();
        try {
            accessibleWalk.walk(this, width, height);
        } catch (limn.backend.Crashes.ShutdownRequested shutdown) {
            throw shutdown;
        } catch (Throwable error) {
            // A describe pass runs application code the moment a widget subclass overrides its
            // hook, and on one platform it runs inside a native callback where an escaping
            // exception unwinds into code with no Java frame to report it. A crashed pass leaves
            // the previous tree published and answers from that.
            if (!limn.backend.Crashes.dispatch(limn.backend.CrashPhase.ACCESSIBILITY, error)) {
                throw limn.backend.Crashes.shutdownRequested(error);
            }
            return;
        }
        accessibleNodesDirty = false;
        accessibleHeaderDirty = false;
        primingPublishOwed = false;
        if (!accessibleWalk.builder().changed()) {
            return; // no snapshot, no publish, no events
        }
        limn.accessibility.AccessibleTree tree = accessibleWalk.publish(
                window != null ? window.screenX() : 0,
                window != null ? window.screenY() : 0,
                window != null ? window.logicalToScreenFactor() : 1,
                window == null || window.supportsAbsolutePositioning());
        publishedTree = tree;
        bridge.publish(tree, reentrant);
        List<limn.accessibility.AccessibleEvent> events = accessibleWalk.builder().events();
        for (int i = 0; i < events.size(); i++) {
            bridge.emit(events.get(i));
        }
    }

    /** One queued announcement, resolved when it is drained rather than when it is made. */
    private record Announcement(limn.i18n.I18nString text,
                                limn.accessibility.Accessible.Politeness politeness) {
    }

    /** Queued announcements past which the oldest is dropped. */
    private static final int MAX_ANNOUNCEMENTS = 32;

    private final List<Announcement> announcements = new ArrayList<>();

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
        Ui.checkUiThread();
        this.frontPainter = painter;
        requestRender();
    }

    /** @return the front painter, or {@code null}. */
    public java.util.function.Consumer<Canvas> frontPainter() {
        return frontPainter;
    }

    /**
     * Schedules a frame without adding damage (animation keep-alive, disposals).
     *
     * <p>Package-private rather than private because the accessibility plumbing needs exactly
     * this and must not have {@link #requestRender()}: buying a frame and declaring damage are
     * two acts, and a re-stamp, an announcement and an accessibility invalidation want the first
     * without the second. Reaching for {@code requestRender()} there would mark the accessible
     * node flag on the way out and make the walk those paths exist to avoid run anyway.
     */
    void scheduleFrame() {
        renderRequester.run();
    }

    /**
     * Marks a scene-space region as damaged and schedules a frame, for
     * painting that extends beyond the invalidating widget's bounds (shadows,
     * carets in overlays). {@link Widget#invalidate()} is the common path.
     */
    public void damage(Rect region) {
        addDamage(region.x(), region.y(), region.width(), region.height());
        accessibleNodesDirty = true;
        scheduleFrame();
    }

    /** Widget.invalidate(x,y,w,h): damages a region given in the widget's local coordinates. */
    void damageWidgetRegion(Widget<?> widget, float x, float y, float w, float h) {
        if (partialRendering || damageDebug) {
            addClippedDamage(widget, x - 1, y - 1, w + 2, h + 2);
        }
        accessibleNodesDirty = true;
        scheduleFrame();
    }

    /** Widget.invalidate(): damages the widget's bounds (in scene coordinates). */
    void damageWidget(Widget<?> widget) {
        if (partialRendering || damageDebug) {
            // +1px on every side: analytic AA lets a shape hugging the widget
            // edge feather just outside its bounds. paintOutset() widens it for
            // widgets that declare painting beyond their box.
            float outset = 1 + widget.paintOutset();
            addClippedDamage(widget, -outset, -outset,
                    widget.width() + 2 * outset, widget.height() + 2 * outset);
        }
        accessibleNodesDirty = true;
        scheduleFrame();
    }

    /**
     * Adds damage for a rect in {@code widget}'s local coordinates, walking up
     * to scene coordinates while clamping it at every ancestor that clips its
     * children: a region scrolled out of a viewport damages only its visible
     * slice, and one fully out of view (or inside a hidden branch) damages
     * nothing at all.
     */
    private void addClippedDamage(Widget<?> widget, float x, float y, float w, float h) {
        if (clippedSceneRect(widget, x, y, w, h, clipScratch)) {
            addDamage(clipScratch[0], clipScratch[1], clipScratch[2], clipScratch[3]);
        }
    }

    /**
     * A rect in {@code widget}'s local coordinates, walked up to scene coordinates and clamped at
     * every ancestor that clips its children, or {@code null} if nothing of it survives.
     *
     * <p>Extracted so that damage and the backdrop pass ask the question once: a backdrop panel
     * scrolled out of a viewport must add nothing, by exactly the rule that stops a scrolled-away
     * widget damaging the viewport it is no longer inside.
     */
    private boolean clippedSceneRect(Widget<?> widget, float x, float y, float w, float h,
                                     float[] out) {
        float x0 = x;
        float y0 = y;
        float x1 = x + w;
        float y1 = y + h;
        Widget<?> below = null;
        for (Widget<?> node = widget; node != null; below = node, node = node.parent()) {
            if (!node.isVisible()) {
                return false; // hidden branch: it paints nothing, so no pixel changed
            }
            if (node != widget && node.clipsChildren()) {
                // The ancestor's clip for the child we came up through, which is its box unless it
                // says otherwise. +1: the clip boundary itself antialiases within a pixel.
                float cx = node.clipX(below);
                float cy = node.clipY(below);
                x0 = Math.max(x0, cx - 1);
                y0 = Math.max(y0, cy - 1);
                x1 = Math.min(x1, cx + node.clipWidth(below) + 1);
                y1 = Math.min(y1, cy + node.clipHeight(below) + 1);
                if (x1 <= x0 || y1 <= y0) {
                    return false; // fully clipped away (scrolled out of view)
                }
            }
            x0 += node.x();
            y0 += node.y();
            x1 += node.x();
            y1 += node.y();
        }
        if (x1 <= x0 || y1 <= y0) {
            return false;
        }
        out[0] = x0;
        out[1] = y0;
        out[2] = x1 - x0;
        out[3] = y1 - y0;
        return true;
    }

    /**
     * Adds the rectangle of every backdrop-dependent widget this frame's damage reaches.
     *
     * <p>A known limit of partial rendering, closed: a shape filled from what is behind it is stale
     * when what is behind it repaints, and nothing about the shape itself moved to say so. Adding
     * one such rectangle can reach another &mdash; a panel over a panel &mdash; so the pass repeats
     * until nothing new is added, which is at most once per registered widget because each is added
     * at most once.
     *
     * <p>Inert when there are none, which is almost every scene: one emptiness check per frame.
     *
     * @param fresh this frame's damage, widened in place; whole or empty, it has nothing to add
     */
    private void withBackdropDependants(DamageRects fresh) {
        if (backdropDependants.isEmpty() || fresh.isWhole() || fresh.isEmpty()) {
            return;
        }
        int n = backdropDependants.size();
        if (backdropTaken.length < n) {
            backdropTaken = new boolean[n];
        }
        for (int i = 0; i < n; i++) {
            backdropTaken[i] = false;
        }
        for (boolean added = true; added; ) {
            added = false;
            for (int i = 0; i < n; i++) {
                if (backdropTaken[i]) {
                    continue;
                }
                Widget<?> widget = backdropDependants.get(i);
                float outset = 1 + widget.paintOutset();
                if (!clippedSceneRect(widget, -outset, -outset,
                        widget.width() + 2 * outset, widget.height() + 2 * outset,
                        backdropScratch)) {
                    backdropTaken[i] = true; // clipped away or hidden: nothing of it is on screen
                    continue;
                }
                if (!fresh.intersectsAny(backdropScratch[0], backdropScratch[1],
                        backdropScratch[2], backdropScratch[3])) {
                    continue;
                }
                fresh.add(backdropScratch[0], backdropScratch[1], backdropScratch[2],
                        backdropScratch[3]);
                backdropTaken[i] = true;
                added = true;
            }
        }
    }

    private void addDamage(float x, float y, float w, float h) {
        pendingDamage.add(x, y, w, h); // merging and bounding: see DamageRects
    }

    @SuppressWarnings("unchecked")
    private static final java.util.function.Consumer<Widget<?>>[] NO_PRESS_OBSERVERS =
            (java.util.function.Consumer<Widget<?>>[]) new java.util.function.Consumer<?>[0];

    private java.util.function.Consumer<Widget<?>>[] pressObservers;

    /**
     * Observes every mouse press with its hit-tested target (after normal
     * dispatch), the hook for "click outside to dismiss" overlays and popups
     * that must react to presses landing on non-focusable widgets.
     *
     * @param observer told about every press; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    public Subscription observePresses(java.util.function.Consumer<Widget<?>> observer) {
        Ui.checkUiThread();
        Objects.requireNonNull(observer, "observer");
        pressObservers = Listeners.added(pressObservers, observer, NO_PRESS_OBSERVERS);
        return once(() -> pressObservers = Listeners.removed(pressObservers, observer));
    }

    private void notifyPressObservers(Widget<?> target) {
        java.util.function.Consumer<Widget<?>>[] snapshot = pressObservers;
        if (snapshot == null) {
            return;
        }
        for (java.util.function.Consumer<Widget<?>> observer : snapshot) {
            try {
                observer.accept(target);
            } catch (Throwable error) {
                Listeners.failed(limn.backend.CrashPhase.OBSERVER, error);
            }
        }
    }

    private Runnable[] windowBlurObservers;
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
     *
     * @param observer told when the window loses focus; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    public Subscription observeWindowBlur(Runnable observer) {
        Ui.checkUiThread();
        Objects.requireNonNull(observer, "observer");
        windowBlurObservers = Listeners.added(windowBlurObservers, observer, NO_RUNNABLES);
        return once(() -> windowBlurObservers = Listeners.removed(windowBlurObservers, observer));
    }

    private void notifyWindowBlurObservers() {
        Runnable[] snapshot = windowBlurObservers;
        if (snapshot == null) {
            return;
        }
        for (Runnable observer : snapshot) {
            try {
                observer.run();
            } catch (Throwable error) {
                Listeners.failed(limn.backend.CrashPhase.OBSERVER, error);
            }
        }
    }

    /**
     * The widget whose subtree a contained layout pass is running over, or {@code null}. Held so
     * that the mounting and recycling such a pass exists to perform (which goes through
     * {@code add}/{@code remove} and therefore asks for a full layout) is recognised as work the
     * pass in progress is already doing, rather than escalating it into the very full frame the
     * contained pass was there to avoid.
     */
    private Widget<?> containedTarget;

    /** Widgets that asked for a contained layout, in request order. */
    private final List<Widget<?>> containedLayouts = new ArrayList<>();
    /** Widgets shown or hidden since the last frame, in request order; see runVisibilityLayouts. */
    private final List<Widget<?>> visibilityChanges = new ArrayList<>();
    /**
     * The widgets this frame's contained pass laid out and kept contained. A visibility change
     * inside one of them is already laid out and damaged, and laying it out a second time is not
     * merely waste: a layout that is not idempotent -- a tab indicator that snaps when the tab it
     * points at has not changed -- reads the second pass as nothing having happened.
     */
    private final List<Widget<?>> containedThisFrame = new ArrayList<>();
    /**
     * Set when a layout or visibility request arrives from inside a narrow pass and is absorbed by
     * it. A contained pass damages its whole widget, so for it that is the end of the matter; a
     * visibility pass damages only the children it saw move, and a request from deeper down means
     * something moved that it did not see.
     */
    private boolean absorbedInsidePass;

    void markLayoutDirty(Widget<?> origin) {
        if (containedTarget != null && origin != null && isInSubtree(origin, containedTarget)) {
            absorbedInsidePass = true;
            return; // the pass already running over this subtree covers it
        }
        layoutDirty = true;
        requestRender();
    }

    void markLayoutDirty() {
        markLayoutDirty(null);
    }

    /**
     * See {@link Widget#setVisible} and {@link Widget#markNeedsLayoutInPlace}: a widget shown or
     * hidden, or one whose size may have changed, laid out and damaged at the next frame by
     * {@link #runVisibilityLayouts} rather than by a full pass.
     */
    void markVisibilityChanged(Widget<?> widget) {
        if (containedTarget != null && isInSubtree(widget, containedTarget)) {
            absorbedInsidePass = true;
            return; // the pass already running over this subtree lays it out
        }
        if (!visibilityChanges.contains(widget)) {
            visibilityChanges.add(widget);
        }
        accessibleNodesDirty = true;
        scheduleFrame();
    }

    void markContainedLayout(Widget<?> widget) {
        if (!containedLayouts.contains(widget)) {
            containedLayouts.add(widget);
        }
        accessibleNodesDirty = true;
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
        containedThisFrame.clear();
        if (containedLayouts.isEmpty()) {
            return;
        }
        List<Widget<?>> pending = List.copyOf(containedLayouts);
        containedLayouts.clear();
        if (layoutDirty) {
            return; // a full pass is already scheduled and covers all of them
        }
        for (Widget<?> widget : pending) {
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
            containedThisFrame.add(widget);
        }
    }

    /**
     * Lays out and damages what a visibility change actually moved, instead of the window.
     *
     * <p>A widget shown or hidden can only move things inside the nearest ancestor whose size
     * survives the change: that ancestor's parent placed it against a size that is still true, so
     * nothing outside it moves. The pass climbs from the widget's parent, re-measuring each
     * ancestor against the constraints it was last given, and stops at the first whose size came
     * out the same. It lays that one out in place, compares where each of its children was with
     * where it is now, and damages the ones that moved -- where they were and where they went --
     * plus the widget itself.
     *
     * <p><b>The widget's own old box is damaged through its parent</b>, because a hidden branch
     * damages nothing through itself ({@link #clippedSceneRect}) and a box never erased is a widget
     * still on screen. A child whose visibility changed during the pass counts as moved, since a
     * container may show or hide its own parts as it lays out (a scroll view's bars).
     *
     * <p>Every case the comparison cannot vouch for falls back rather than guesses: a widget no
     * longer in this scene or with no parent, an ancestor never measured, a climb past the root,
     * all escalate to a full pass; a child list that changed during the pass, or a request
     * absorbed from deeper than the children compared, damages the whole ancestor if it clips its
     * children and escalates if it does not. Wrong costs a frame, never a stale pixel.
     */
    private void runVisibilityLayouts() {
        if (visibilityChanges.isEmpty()) {
            return;
        }
        List<Widget<?>> pending = List.copyOf(visibilityChanges);
        visibilityChanges.clear();
        if (layoutDirty) {
            return; // a full pass is already scheduled and covers all of them
        }
        for (Widget<?> widget : pending) {
            if (insideContainedThisFrame(widget)) {
                // Laid out and damaged already, by a pass over a box that clips its children and
                // kept its size -- re-measured with this change in it, since setVisible marked the
                // measures stale on the way up. So nothing it moved can be outside that box.
                continue;
            }
            Widget<?> parent = widget.parent();
            if (widget.scene() != this || parent == null) {
                layoutDirty = true;
                continue;
            }
            Widget<?> anchor = parent;
            boolean absorbed = false;
            while (anchor != null) {
                Constraints constraints = anchor.lastConstraints();
                Size before = anchor.lastSize();
                if (constraints == null || before == null) {
                    break; // never measured: nothing to compare against
                }
                Size after = anchor.measure(constraints);
                if (after.equals(before)) {
                    absorbed = true;
                    break;
                }
                anchor = anchor.parent(); // this one moved size, so its parent must place it
            }
            if (!absorbed) {
                layoutDirty = true;
                continue;
            }
            // Where the widget is drawn now, in its parent's space, read before anything moves.
            float wx = widget.x();
            float wy = widget.y();
            float ww = widget.width();
            float wh = widget.height();
            List<Widget<?>> children = List.copyOf(anchor.children());
            float[] was = new float[children.size() * 4];
            boolean[] wasVisible = new boolean[children.size()];
            for (int i = 0; i < children.size(); i++) {
                Widget<?> child = children.get(i);
                was[i * 4] = child.x();
                was[i * 4 + 1] = child.y();
                was[i * 4 + 2] = child.width();
                was[i * 4 + 3] = child.height();
                wasVisible[i] = child.isVisible();
            }
            absorbedInsidePass = false;
            containedTarget = anchor;
            try {
                anchor.layoutBox(anchor.x(), anchor.y(), anchor.width(), anchor.height());
            } finally {
                containedTarget = null;
            }
            if (absorbedInsidePass || !children.equals(anchor.children())) {
                // Something moved that the comparison below would not see.
                absorbedInsidePass = false;
                if (anchor.clipsChildren()) {
                    damageWidget(anchor);
                } else {
                    layoutDirty = true;
                }
                continue;
            }
            for (int i = 0; i < children.size(); i++) {
                Widget<?> child = children.get(i);
                boolean moved = was[i * 4] != child.x() || was[i * 4 + 1] != child.y()
                        || was[i * 4 + 2] != child.width() || was[i * 4 + 3] != child.height();
                if (!moved && wasVisible[i] == child.isVisible()) {
                    continue;
                }
                float o = 1 + child.paintOutset();
                if (wasVisible[i]) {
                    damageInParent(anchor, child, was[i * 4] - o, was[i * 4 + 1] - o,
                            was[i * 4 + 2] + 2 * o, was[i * 4 + 3] + 2 * o);
                }
                if (child.isVisible()) {
                    damageInParent(anchor, child, child.x() - o, child.y() - o,
                            child.width() + 2 * o, child.height() + 2 * o);
                }
            }
            float o = 1 + widget.paintOutset();
            if (widget.isVisible()) {
                damageWidget(widget);
            } else {
                damageInParent(parent, widget, wx - o, wy - o, ww + 2 * o, wh + 2 * o);
            }
            accessibleNodesDirty = true;
        }
    }

    private boolean insideContainedThisFrame(Widget<?> widget) {
        for (int i = 0; i < containedThisFrame.size(); i++) {
            Widget<?> target = containedThisFrame.get(i);
            if (widget != target && isInSubtree(widget, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Damages a rectangle given in {@code parent}'s coordinates, on behalf of {@code child}: clamped
     * to the parent's own clip for that child when it clips, and then through every ancestor as
     * any damage is. For a region that belongs to a child which may no longer be drawn -- a hidden
     * widget's old box -- and so cannot be damaged through the child itself.
     */
    private void damageInParent(Widget<?> parent, Widget<?> child, float x, float y, float w, float h) {
        if (!partialRendering && !damageDebug) {
            return;
        }
        float x0 = x;
        float y0 = y;
        float x1 = x + w;
        float y1 = y + h;
        if (parent.clipsChildren()) {
            float cx = parent.clipX(child);
            float cy = parent.clipY(child);
            x0 = Math.max(x0, cx - 1);
            y0 = Math.max(y0, cy - 1);
            x1 = Math.min(x1, cx + parent.clipWidth(child) + 1);
            y1 = Math.min(y1, cy + parent.clipHeight(child) + 1);
            if (x1 <= x0 || y1 <= y0) {
                return;
            }
        }
        addClippedDamage(parent, x0, y0, x1 - x0, y1 - y0);
    }

    /** See {@link Widget#paintsFromBackdrop()}. Called as the widget joins this scene. */
    void addBackdropDependant(Widget<?> widget) {
        if (!backdropDependants.contains(widget)) {
            backdropDependants.add(widget);
        }
    }

    /** See {@link Widget#paintsFromBackdrop()}. Called as the widget leaves this scene. */
    void removeBackdropDependant(Widget<?> widget) {
        backdropDependants.remove(widget);
    }

    /** @return how many backdrop-dependent widgets this scene holds; for tests */
    int backdropDependantCount() {
        return backdropDependants.size();
    }

    void onWidgetDetached(Widget<?> widget) {
        // Never leave dangling references to removed/disabled/hidden subtrees,
        // and never leave the widget itself with stuck hover/pressed state:
        // synthesize the EXIT/RELEASE it will otherwise never receive.
        // (State is cleared BEFORE dispatching, the reentrancy-safe order.)
        if (isInSubtree(hovered, widget)) {
            Widget<?> oldHover = hovered;
            hovered = null;
            timedDispatch(oldHover, new MouseEvent(MouseEvent.Type.EXIT, mouseX, mouseY, -1, 0, 0, 0));
            applyCursor(); // hovered widget gone: fall back to the arrow
            // The tooltip owner is an ancestor-or-self of the hover: cancel its
            // pending/visible tooltip too, or the delayed show still fires for
            // (and a visible one keeps describing) a widget no longer on screen.
            hideTooltip();
        }
        if (isInSubtree(pressed, widget)) {
            Widget<?> oldPressed = pressed;
            int oldButton = pressedButton;
            pressed = null;
            pressedButton = -1;
            timedDispatch(oldPressed,
                    new MouseEvent(MouseEvent.Type.RELEASE, mouseX, mouseY, oldButton, 0, 0, 0));
        }
        if (isInSubtree(focused, widget)) {
            setFocus(null, Change.Origin.ADJUSTMENT); // hidden, disabled or detached under it
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
        Widget<?> keyTarget = focused != null ? focused : inputRoot();
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
            Widget<?> oldPressed = pressed;
            int oldButton = pressedButton;
            pressed = null;
            pressedButton = -1;
            timedDispatch(oldPressed,
                    new MouseEvent(MouseEvent.Type.RELEASE, mouseX, mouseY, oldButton, 0, 0, 0));
        }
        updateHover(null);
    }

    private static boolean isInSubtree(Widget<?> candidate, Widget<?> subtreeRoot) {
        for (Widget<?> w = candidate; w != null; w = w.parent()) {
            if (w == subtreeRoot) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- the change channel

    private static final Runnable[] NO_RUNNABLES = new Runnable[0];

    private static final ChangeObserver[] NO_WATCHERS = new ChangeObserver[0];

    /** The watchers of this whole tree, null until the first one arrives. */
    private ChangeObserver[] changeWatchers;

    /**
     * Watches every change to every widget in this scene's tree, overlays included, from every
     * origin. One registration serves a whole window, adds nothing per widget, and works on an
     * unbound scene -- which is the trap {@code Scene}'s own metrics listener was moved into the
     * constructor for, and which every component test, a combo box's popup and a dialog's modal
     * scene before it binds all depend on.
     *
     * <p><b>This is a channel over a tree, not over a set of widgets.</b> It hears what the scene
     * holds now, and goes quiet about a widget the scene no longer contains -- so a recycled list
     * cell written to while unmounted reaches its own watchers and no scene's. A consumer that
     * must follow one particular widget wherever it goes registers on the widget.
     *
     * @param observer told about every change in this tree; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    public Subscription observeChanges(ChangeObserver observer) {
        Ui.checkUiThread();
        Objects.requireNonNull(observer, "observer");
        changeWatchers = Listeners.added(changeWatchers, observer, NO_WATCHERS);
        return once(() -> changeWatchers = Listeners.removed(changeWatchers, observer));
    }

    /** Whether anything watches this tree; read by the text seam before it builds an edit. */
    boolean hasChangeWatchers() {
        return changeWatchers != null;
    }

    /** A widget in this tree announcing, after its own watchers have run. */
    void announceChange(Widget<?> source, Change change) {
        ChangeObserver[] snapshot = changeWatchers;
        if (snapshot == null) {
            return;
        }
        for (ChangeObserver observer : snapshot) {
            try {
                observer.changed(source, change);
            } catch (Throwable error) {
                Listeners.failed(limn.backend.CrashPhase.OBSERVER, error);
            }
        }
    }

    /**
     * A handle that drops its registration once: a second cancel finds nothing to take off the
     * array, which is what keeps <i>cancelling twice is a no-op</i> true even where the same
     * listener is registered twice.
     */
    private static Subscription once(Runnable removal) {
        return new Subscription() {
            private Runnable pending = removal;

            @Override
            public void cancel() {
                Ui.checkUiThread();
                Runnable taken = pending;
                if (taken == null) {
                    return;
                }
                pending = null;
                taken.run();
            }
        };
    }

    // ----------------------------------------------------------------- focus

    /** The widget holding keyboard focus, or {@code null} when nothing does. */
    public Widget<?> focusedWidget() {
        return focused;
    }

    /**
     * Moves keyboard focus to {@code widget}, or clears it when {@code null}. Ignored
     * for a widget that is not focusable, visible and enabled, and for one outside the
     * topmost modal overlay. UI thread only.
     *
     * <p>An application calling this is code moving the focus, so the two {@code FOCUS} changes
     * it announces carry {@code CODE}. A gesture that moves focus does not come through here: a
     * click and a Tab enter the funnel from the scene's own input paths, and the two components
     * that move focus out of a gesture -- a tab strip's arrow key and a radio group's -- enter it
     * through {@link Widget#requestFocus(Change.Origin)}. Reading the method rather than the
     * entry point would report every keyboard-driven focus move in the toolkit as made by code.
     */
    public void requestFocus(Widget<?> widget) {
        Ui.checkUiThread();
        requestFocus(widget, Change.Origin.CODE);
    }

    /** The funnel the origin travels through; see {@link #requestFocus(Widget)}. */
    void requestFocus(Widget<?> widget, Change.Origin origin) {
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
        setFocus(widget, origin);
    }

    /**
     * The one place focus moves, and the one place {@code FOCUS} is announced: on the widget
     * losing it and then on the widget gaining it, both carrying the origin of the path that
     * entered here. Each announcement follows that widget's own focus-lost or focus-gained hook,
     * so a watcher reads a widget that has already settled.
     */
    private void setFocus(Widget<?> widget, Change.Origin origin) {
        if (focused == widget) {
            return;
        }
        Widget<?> old = focused;
        focused = widget;
        if (old != null) {
            if (window != null && old.acceptsTextInputInternal()) {
                // Focus is leaving a text widget: cancel any OS-side composition
                // it owned, so the preedit can't teleport into, and later
                // commit into, whatever gains focus next.
                window.resetPreedit();
            }
            old.notifyFocus(false, origin);
            if (focused != widget) {
                return; // a focus-lost handler re-routed focus; it finished the job
            }
        }
        if (widget != null) {
            widget.notifyFocus(true, origin);
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
        // The two widgets that moved, and not the whole window.
        //
        // This was a bare requestRender(), which is a full frame by definition, so every focus
        // change repainted everything -- measured, and true of a plain Button as much as of
        // anything newer. What actually changed is at most two boxes, and each is damaged with
        // the outset its focus ring reaches into, which is the same inflation every other painter
        // of a ring relies on. Their own onFocusGained/onFocusLost usually damage them anyway;
        // this makes it true for a widget that draws a ring and overrides neither.
        if (old != null) {
            old.invalidate();
        }
        if (widget != null) {
            widget.invalidate();
        }
    }

    /** Focused widget awaiting post-layout reveal (see {@link #setFocus}). */
    private Widget<?> pendingReveal;

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

    /**
     * Moves focus to the next/previous focusable widget in layout (DFS) order.
     *
     * <p>{@code CODE}, because this is the method an application calls; the Tab key reaches the
     * same traversal through the scene's own key path and announces {@code USER}.
     */
    public void focusTraverse(boolean backward) {
        focusTraverse(backward, Change.Origin.CODE);
    }

    /** The traversal with the origin of whatever asked for it. */
    private void focusTraverse(boolean backward, Change.Origin origin) {
        Ui.checkUiThread();
        List<Widget<?>> order = new ArrayList<>();
        collectFocusable(inputRoot(), order); // modal overlay confines traversal
        if (order.isEmpty()) {
            setFocus(null, origin);
            return;
        }
        int index = order.indexOf(focused);
        int next = index < 0
                ? (backward ? order.size() - 1 : 0)
                : Math.floorMod(index + (backward ? -1 : 1), order.size());
        focusByTraversal = true;
        focusTraversalBackward = backward;
        try {
            setFocus(order.get(next), origin);
        } finally {
            focusByTraversal = false;
            focusTraversalBackward = false;
        }
    }

    /**
     * Whether the focus change being delivered right now came from Tab rather than from a click
     * or from code. Only meaningful inside a {@code onFocusGained} callback, which is the one
     * moment it is set; see {@link Widget#focusArrivedByTraversal()}.
     */
    private boolean focusByTraversal;

    /** And, when it did, whether it was Shift+Tab; see {@link Widget#focusArrivedBackward()}. */
    private boolean focusTraversalBackward;

    boolean focusCameFromTraversal() {
        return focusByTraversal;
    }

    boolean focusTraversalWentBackward() {
        return focusByTraversal && focusTraversalBackward;
    }

    private static void collectFocusable(Widget<?> widget, List<Widget<?>> out) {
        if (!widget.isVisible() || !widget.isEnabled()) {
            return;
        }
        if (widget.isFocusable()) {
            out.add(widget);
        }
        for (Widget<?> child : widget.children()) {
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
        Checks.notNegative(scale, "time scale");
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

    /**
     * What the window delivers input to: a private adapter and not the scene itself, so that a
     * window's plumbing — raw key codes, the end of an input batch, the close — is not part of what
     * an application calls on its scene. A test drives a scene through the same
     * adapter, reached by {@code limn.testing.SceneDriver} in the {@code limn-test} module.
     */
    private final class Input implements WindowInput {
        @Override public void mouseMoved(float x, float y) { Scene.this.mouseMoved(x, y); }
        @Override public void mouseDelta(float dx, float dy) { Scene.this.mouseDelta(dx, dy); }
        @Override public void mouseButton(int button, boolean pressed, int modifiers, float x, float y,
                                          int clickCount) {
            Scene.this.mouseButton(button, pressed, modifiers, x, y, clickCount);
        }
        @Override public void scrolled(float deltaX, float deltaY, float x, float y) {
            Scene.this.scrolled(deltaX, deltaY, x, y);
        }
        @Override public void keyEvent(int key, boolean pressed, boolean repeat, int modifiers) {
            Scene.this.keyEvent(key, pressed, repeat, modifiers);
        }
        @Override public void charTyped(int codepoint) { Scene.this.charTyped(codepoint); }
        @Override public void preeditChanged(String text, int[] blockSizes, int focusedBlock, int caret) {
            Scene.this.preeditChanged(text, blockSizes, focusedBlock, caret);
        }
        @Override public void pointerEntered(boolean entered) { Scene.this.pointerEntered(entered); }
        @Override public void windowResized(float logicalWidth, float logicalHeight) {
            Scene.this.windowResized(logicalWidth, logicalHeight);
        }
        @Override public void filesDropped(java.util.List<java.nio.file.Path> paths) {
            Scene.this.filesDropped(paths);
        }
        @Override public void windowFocusChanged(boolean focused) { Scene.this.windowFocusChanged(focused); }
        @Override public void inputBatchEnded() { Scene.this.inputBatchEnded(); }
        @Override public void windowClosed() { Scene.this.windowClosed(); }

        Scene scene() {
            return Scene.this;
        }
    }

    private final Input input = new Input();

    static {
        limn.scene.internal.SceneAccess.install(new limn.scene.internal.SceneAccess.Hook() {
            @Override
            public WindowInput inputOf(Scene scene) {
                return scene.input;
            }

            @Override
            public Scene sceneOf(WindowInput input) {
                return input instanceof Input own ? own.scene() : null;
            }
        });
    }


    private void mouseMoved(float x, float y) {
        Raw last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        if (last instanceof RawMove) {
            queue.set(queue.size() - 1, new RawMove(x, y)); // coalesce: newest wins
        } else {
            queue.add(new RawMove(x, y));
        }
    }

    private void mouseDelta(float dx, float dy) {
        Raw last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        if (last instanceof RawDelta d) {
            queue.set(queue.size() - 1, new RawDelta(d.dx + dx, d.dy + dy)); // coalesce: sum
        } else {
            queue.add(new RawDelta(dx, dy));
        }
    }

    private void mouseButton(int button, boolean pressed, int modifiers, float x, float y, int clickCount) {
        queue.add(new RawButton(button, pressed, modifiers, x, y, clickCount)); // never dropped
    }

    /**
     * The count a press the backend did not count gets from this scene's clock: the fallback, with
     * the interval the table and the tree used to time on their own.
     */
    private static final long FALLBACK_DOUBLE_CLICK_NANOS = 400_000_000L;
    private static final float DOUBLE_CLICK_SLOP = 4;
    private int lastClickButton = -1;
    private long lastClickNanos;
    private float lastClickX;
    private float lastClickY;
    private int lastClickCount;

    private int countClick(RawButton button) {
        if (button.clicks > 0) {
            lastClickCount = button.clicks;
            return button.clicks;
        }
        long now = clock.getAsLong();
        boolean again = button.button == lastClickButton && lastClickCount > 0
                && now - lastClickNanos < FALLBACK_DOUBLE_CLICK_NANOS
                && Math.abs(button.x - lastClickX) <= DOUBLE_CLICK_SLOP
                && Math.abs(button.y - lastClickY) <= DOUBLE_CLICK_SLOP;
        lastClickCount = again ? lastClickCount + 1 : 1;
        lastClickButton = button.button;
        lastClickNanos = now;
        lastClickX = button.x;
        lastClickY = button.y;
        return lastClickCount;
    }

    private void scrolled(float deltaX, float deltaY, float x, float y) {
        Raw last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        if (last instanceof RawScroll s) {
            queue.set(queue.size() - 1, new RawScroll(s.dx + deltaX, s.dy + deltaY, x, y));
        } else {
            queue.add(new RawScroll(deltaX, deltaY, x, y));
        }
    }

    private void keyEvent(int key, boolean pressed, boolean repeat, int modifiers) {
        queue.add(new RawKey(key, pressed, repeat, modifiers)); // never dropped
    }

    private void charTyped(int codepoint) {
        queue.add(new RawChar(codepoint));
    }

    private void preeditChanged(String text, int[] blockSizes, int focusedBlock, int caret) {
        queue.add(new RawPreedit(text, blockSizes, focusedBlock, caret));
    }

    private void pointerEntered(boolean entered) {
        queue.add(new RawPointer(entered));
    }

    private void filesDropped(java.util.List<java.nio.file.Path> paths) {
        if (!paths.isEmpty()) {
            queue.add(new RawDrop(paths)); // never dropped, like clicks/keys
        }
    }

    private void windowFocusChanged(boolean focused) {
        queue.add(new RawFocus(focused));
    }

    private void windowResized(float logicalWidth, float logicalHeight) {
        Raw last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        RawResize resize = new RawResize(logicalWidth, logicalHeight);
        if (last instanceof RawResize) {
            queue.set(queue.size() - 1, resize);
        } else {
            queue.add(resize);
        }
    }

    private void inputBatchEnded() {
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
                    // The window's own ACTIVE state moved, and nothing else on this path would
                    // say so: the branch below invalidates only when focus is LOST, for the
                    // pointer and key state it has to cancel. The walk this buys is what
                    // publishes the bit, and the difference between that tree and the last one
                    // is what raises WINDOW_ACTIVATED or WINDOW_DEACTIVATED, on the window
                    // node, in the same publish (ADR 039 §1.10, amended 2026-09-14). Until then
                    // the event was emitted from here, ahead of any walk, so a client told the
                    // window became active found a tree that still said it was not -- and a
                    // screen reader believes the tree.
                    invalidateAccessible();
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
            // (Resize is safe: a layout frame forces full damage structurally, and the pass
            // sets the accessible node flag itself, so the moved boxes are republished.)
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
            Widget<?> hit = hitAt(x, y);
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
            Widget<?> hit = hitAt(button.x, button.y);
            // Capture BEFORE dispatching: a PRESS/focus handler may detach the
            // widget, and onWidgetDetached must find (and clear) this state.
            pressed = hit;
            pressedButton = button.button;
            // Click-to-focus: nearest focusable ancestor of the press target.
            Widget<?> focusTarget = hit;
            while (focusTarget != null && !focusTarget.isFocusable()) {
                focusTarget = focusTarget.parent();
            }
            if (focusTarget != null) {
                setFocus(focusTarget, Change.Origin.USER); // click-to-focus
            }
            dispatchBubbling(pressed, new MouseEvent(
                    MouseEvent.Type.PRESS, button.x, button.y, button.button, 0, 0, button.mods,
                    countClick(button)));
            notifyPressObservers(hit);
        } else {
            Widget<?> target = pressed != null ? pressed : hitAt(button.x, button.y);
            dispatchBubbling(target, new MouseEvent(
                    MouseEvent.Type.RELEASE, button.x, button.y, button.button, 0, 0, button.mods,
                    lastClickCount));
            Widget<?> releaseHit = hitAt(button.x, button.y);
            if (pressed != null && isInSubtree(releaseHit, pressed)) {
                dispatchBubbling(pressed, new MouseEvent(
                        MouseEvent.Type.CLICK, button.x, button.y, button.button, 0, 0, button.mods,
                        lastClickCount));
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
        Widget<?> keyTarget = focused != null ? focused : inputRoot();
        dispatchBubbling(keyTarget, event);
        // Between the focused widget and the Tab fallback: what nobody focused wanted may still
        // be a shortcut, and what nobody wants at all must still traverse.
        offerToShortcutHandlers(event);
        if (!event.isConsumed() && key.pressed && key.key == Keys.TAB) {
            focusTraverse((key.mods & Keys.MOD_SHIFT) != 0, Change.Origin.USER);
        }
    }

    @SuppressWarnings("unchecked")
    private static final java.util.function.Predicate<KeyEvent>[] NO_SHORTCUT_HANDLERS =
            (java.util.function.Predicate<KeyEvent>[]) new java.util.function.Predicate<?>[0];

    private java.util.function.Predicate<KeyEvent>[] shortcutHandlers;

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
     * <p>A handler that throws is contained and read as <i>did not handle</i>, so the chord goes
     * on to the next handler and to Tab traversal -- the policy {@code Work.deliverIf} already
     * states for the same shape.
     *
     * <p>Registering or unregistering from inside a handler is legal and takes effect on the next
     * event, never on the one being dispatched.
     *
     * @param handler consulted for chords nobody focused wanted; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    public Subscription addShortcutHandler(java.util.function.Predicate<KeyEvent> handler) {
        Ui.checkUiThread();
        Objects.requireNonNull(handler, "handler");
        shortcutHandlers = Listeners.added(shortcutHandlers, handler, NO_SHORTCUT_HANDLERS);
        return once(() -> shortcutHandlers = Listeners.removed(shortcutHandlers, handler));
    }

    /**
     * Offers an unconsumed key event to the registered handlers. Walks the array the dispatch
     * started with: a handler that unregisters itself (the ordinary shape for one that closes
     * what it opened) would otherwise shift the list under the loop and skip its neighbour.
     */
    private void offerToShortcutHandlers(KeyEvent event) {
        java.util.function.Predicate<KeyEvent>[] snapshot = shortcutHandlers;
        if (event.isConsumed() || snapshot == null || topOverlay() != null) {
            return;
        }
        for (java.util.function.Predicate<KeyEvent> handler : snapshot) {
            boolean handled;
            try {
                handled = handler.test(event);
            } catch (Throwable error) {
                Listeners.failed(limn.backend.CrashPhase.OBSERVER, error);
                handled = false; // a thrower did not handle it: the chord goes on down the chain
            }
            if (handled) {
                event.consume();
                return;
            }
        }
    }

    private Widget<?> hitAt(float sceneX, float sceneY) {
        // A modal overlay owns all input; hit-testing never reaches the content,
        // unless the overlay yields this point (e.g. the menu bar strip behind a
        // fullscreen in-scene menu), which then hit-tests the content below.
        Widget<?> top = topOverlay();
        if (top != null && !top.overlayPassesPointer(sceneX, sceneY)) {
            return top.hitTest(sceneX - top.x(), sceneY - top.y());
        }
        return root.hitTest(sceneX - root.x(), sceneY - root.y());
    }

    /** @return whether the hover leaf changed (ENTER/EXIT dispatched, no bubbling) */
    private boolean updateHover(Widget<?> newHover) {
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
        for (Widget<?> w = hovered; w != null; w = w.parent()) {
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
    void cursorChanged(Widget<?> widget) {
        if (isInSubtree(hovered, widget)) {
            applyCursor();
        }
    }

    /** Bubbles from {@code target} to the root until consumed, timing each handler. */
    private void dispatchBubbling(Widget<?> target, InputEvent event) {
        for (Widget<?> w = target; w != null && !event.isConsumed(); w = w.parent()) {
            timedDispatch(w, event);
        }
    }

    private void timedDispatch(Widget<?> widget, InputEvent event) {
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
        renderFrame(canvas, rePresent, gpuFrameMs, 2);
    }

    /**
     * @param bufferAge how many presents old the canvas's contents are ({@link
     *                  limn.backend.FrameInfo#bufferAge}): 0 repaints the whole frame, 1 what changed
     *                  since the last frame, 2 that plus the frame before, and so on. The overloads
     *                  without it assume 2, ordinary double buffering, which is what rendering into a
     *                  canvas of one's own has always been treated as
     */
    public void renderFrame(Canvas canvas, boolean rePresent, float gpuFrameMs, int bufferAge) {
        try {
            renderFrameImpl(canvas, rePresent, gpuFrameMs, bufferAge);
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

    private void renderFrameImpl(Canvas canvas, boolean rePresent, float gpuFrameMs, int bufferAge) {
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
        runVisibilityLayouts();
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
        accessibilityStep(rePresent);
        DamageRects repaint = repaintRegion; // whole = the whole frame, empty = nothing
        if (rePresent) {
            // Identical frame into the other buffer: repaint exactly what the
            // last content frame painted, so both double buffers converge.
            if (partialRendering) {
                repaint.copyFrom(lastRepaintRegion);
            } else {
                repaint.setWhole();
            }
        } else {
            DamageRects fresh = freshDamage;
            consumeFreshDamage(canvas, fresh);
            // Widened BEFORE it is stored as this frame's damage, so the next frame's union
            // carries the backdrop rects too: the other buffer needs them for the same reason
            // this one does.
            withBackdropDependants(fresh);
            // The back buffer holds the frame from bufferAge presents ago, so the damage of every
            // frame since repaints too (ADR 046 §5): the previous frame's for double buffering,
            // two frames' for triple, nothing extra for a buffer that keeps its contents, and the
            // whole frame for a backend that does not know.
            if (bufferAge <= 1) {
                repaint.copyFrom(fresh);
            } else if (bufferAge == 2) {
                repaint.unionOf(fresh, frameDamage1);
            } else {
                olderDamage.unionOf(frameDamage1, frameDamage2);
                repaint.unionOf(fresh, olderDamage);
            }
            frameDamage2.copyFrom(frameDamage1);
            frameDamage1.copyFrom(fresh);
            if (damageDebug) {
                List<Rect> flashNow = updateDamageFlashes(fresh.isWhole() ? null : fresh.toList(),
                        canvas);
                repaint.unionWith(flashNow);
                repaint.unionWith(flashPrev1);
                repaint.unionWith(flashPrev2);
                flashPrev2 = flashPrev1;
                flashPrev1 = flashNow;
            }
            if (!partialRendering || bufferAge <= 0
                    || repaint.coversWhole(canvas.width(), canvas.height())) {
                repaint.setWhole();
            }
            lastRepaintRegion.copyFrom(repaint);
        }
        if (!rePresent) {
            // Animation state advances once per frame; the per-pass paints
            // below must be pure (a pass per damage rect would double-advance).
            updateModalScrim();
            updateTooltipFade();
        }
        if (repaint.isWhole()) {
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
                sx0 = Math.min(sx0, repaint.x(i));
                sy0 = Math.min(sy0, repaint.y(i));
                sx1 = Math.max(sx1, repaint.right(i));
                sy1 = Math.max(sy1, repaint.bottom(i));
            }
            canvas.damageScissorHint(sx0, sy0, sx1 - sx0, sy1 - sy0);
            for (int i = 0; i < repaint.size(); i++) {
                float px = repaint.x(i);
                float py = repaint.y(i);
                float pw = repaint.width(i);
                float ph = repaint.height(i);
                // The restore is in a finally for the same reason endPaintCull's is: a frame crash
                // is CONTAINED rather than fatal, so the loop carries on with a canvas that would
                // otherwise still be holding this pass's clip. The next pass would then paint
                // inside the previous one's rectangle, and the frame would end unbalanced,
                // reported against nobody, because whatever threw is long out of the stack.
                canvas.save();
                try {
                    canvas.clipRect(px, py, pw, ph);
                    // clear() ignores the clip; clearRect REPLACES exactly this
                    // pass (works on translucent popup framebuffers too, where a
                    // blended fill could never write alpha back to 0).
                    canvas.clearRect(px, py, pw, ph, background);
                    beginPaintCull(px, py, pw, ph);
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
            if (!repaint.isEmpty()) { // whole, or some passes: the frame painted
                metrics.recordFrameTime((float) ((clock.getAsLong() - frameStart) / 1_000_000.0));
                // A full frame is one region, which is what makes the two readings
                // comparable across the partial-rendering switch rather than showing
                // a dash on one side of it.
                metrics.recordPaintedFrame(repaint.isWhole() ? 1 : repaint.size());
            }
        }
    }

    /**
     * Resolves and resets the damage accumulated since the last content frame into
     * {@code out}: whole = the whole scene, empty = nothing. Rects are clamped and
     * snapped outward to whole logical pixels.
     */
    private void consumeFreshDamage(Canvas canvas, DamageRects out) {
        float scrimTarget = window != null && window.isModalBlocked() ? SCRIM_MAX_ALPHA : 0f;
        boolean full = fullDamagePending
                || scrimAlpha != scrimTarget; // scrim mid-fade retints the whole window
        fullDamagePending = false;
        if (full) {
            pendingDamage.clear();
            out.setWhole();
            return;
        }
        out.clear();
        if (pendingDamage.isEmpty()) {
            return;
        }
        // Snap outward to the DEVICE pixel grid: pass clips then have hard,
        // whole-pixel edges (no fractional AA coverage at the seam), which is
        // what lets clearRect replace exactly the pixels the pass repaints.
        // Required on translucent backgrounds, exact everywhere else.
        float s = canvas.contentScale();
        for (int i = 0; i < pendingDamage.size(); i++) {
            float x = Math.max(0, (float) Math.floor(pendingDamage.x(i) * s) / s);
            float y = Math.max(0, (float) Math.floor(pendingDamage.y(i) * s) / s);
            float right = Math.min(canvas.width(), (float) Math.ceil(pendingDamage.right(i) * s) / s);
            float bottom = Math.min(canvas.height(),
                    (float) Math.ceil(pendingDamage.bottom(i) * s) / s);
            if (right > x && bottom > y) {
                out.append(x, y, right - x, bottom - y);
            }
        }
        pendingDamage.clear();
    }

    /**
     * Everything a frame paints, in order; called once per repaint pass (must be pure).
     *
     * <p>The pass is bracketed so that <b>an announcement made from inside a paint throws</b>,
     * whether or not anything is watching. A paint is re-run when nothing changed, a mutation
     * from one lands on a frame that has already laid out, and a mutator that announces after
     * the pass tells the truth about the state and not about the layout it implies -- so a paint
     * paints. The counter lives on {@link Widget} rather than here because a widget mutated
     * during a paint need not belong to the scene being painted, or to any scene at all.
     */
    private void paintFramePass(Canvas canvas) {
        Widget.beginPaint();
        try {
            paintFrameContent(canvas);
        } finally {
            Widget.endPaint();
        }
    }

    private void paintFrameContent(Canvas canvas) {
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

    private void beginPaintCull(float x, float y, float width, float height) {
        cullActive = true;
        cullX0 = x;
        cullY0 = y;
        cullX1 = x + width;  // the float operation Rect.right() performed
        cullY1 = y + height;
    }

    private void endPaintCull() {
        cullActive = false;
    }

    /** Whether the active repaint pass can skip {@code widget}'s whole subtree. */
    boolean culledFromPaint(Widget<?> widget) {
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
        Widget<?> target = tooltipTarget;
        return target != null ? target.controlSize() : ControlSize.processDefault();
    }

    /**
     * The direction the tooltip panel is laid out and shaped in: the hovered anchor's, resolved
     * live, with the same fallback and for the same reason as {@link #tooltipStep()}. This is the
     * one surface a {@link Scene} paints itself rather than delegating to a widget, so it is the
     * one place the inherited axes have to be read here rather than inside an {@code onPaint}.
     */
    private LayoutDirection tooltipDirection() {
        Widget<?> target = tooltipTarget;
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
                tooltipDirection().isRightToLeft()
                        ? limn.graphics.ShapedText.Direction.RTL
                        : limn.graphics.ShapedText.Direction.LTR;
        return textRuler().shape(text, font,
                limn.graphics.ShapedText.Direction.of(text, neutral));
    }
    // Themed appearance is supplied by the components layer (the toolkit has no
    // Theme of its own); null → tooltips are not painted.
    private static volatile java.util.function.Function<ControlSize, TooltipStyle> tooltipStyle;
    private Widget<?> tooltipTarget; // owner whose tooltip is scheduled or showing
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
    Widget<?> tooltipOwner(Widget<?> from) {
        for (Widget<?> w = from; w != null; w = w.parent()) {
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
        Widget<?> owner = tooltipOwner(hovered);
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
        float x = tooltipDirection().isRightToLeft()
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
        float textX = tooltipDirection().isRightToLeft()
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
            float eased = Scalars.smoothstep(scrimAlpha / SCRIM_MAX_ALPHA) * SCRIM_MAX_ALPHA;
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
        winFadeCurrent = winFadeFrom + (winFadeTarget - winFadeFrom) * Scalars.smoothstep(t);
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
    private void windowClosed() {
        // Every step must run even when an earlier app callback throws: a
        // skipped axis-listener release pins this scene's wrappers in the
        // process-wide listener lists forever, and a skipped close observer is
        // exactly the abandoned completion the observer mechanism exists to
        // prevent. App-code steps are contained individually (fine-grained
        // WINDOW_CLOSE reports; the handler's verdict is not honored here).
        try {
            setFocus(null, Change.Origin.ADJUSTMENT); // runs app focus-lost handlers
        } catch (Throwable error) {
            LOG.log(Level.ERROR, "focus-lost handler threw during window close; teardown continues", error);
            limn.backend.Crashes.report(limn.backend.CrashPhase.WINDOW_CLOSE, error);
        }
        try {
            // The window pair is the bridge's to raise, because a scene bound over a live window
            // never learns it was replaced and so cannot raise its own close. Here there is a
            // window genuinely going away, and this is the one caller that knows it.
            bridge.detach();
        } catch (Throwable error) {
            LOG.log(Level.ERROR, "accessibility bridge threw during window close; teardown continues",
                    error);
            limn.backend.Crashes.report(limn.backend.CrashPhase.WINDOW_CLOSE, error);
        }
        if (accessibleWalk != null) {
            // What this window published answers nothing now, and the window that opened it (a
            // native popup's owner) is told to stop naming it.
            accessibleWalk.close();
        }
        bridge = limn.backend.AccessibilityBridge.NONE;
        publishedTree = limn.accessibility.AccessibleTree.EMPTY;
        metricsListener.release();
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
        Runnable[] closing = windowCloseObservers;
        if (closing != null) {
            for (Runnable observer : closing) {
                try {
                    observer.run();
                } catch (Throwable error) {
                    LOG.log(Level.ERROR, "window-close observer threw; the remaining observers still run", error);
                    limn.backend.Crashes.report(limn.backend.CrashPhase.WINDOW_CLOSE, error);
                }
            }
            // Only the ones that ran, and not clear(): an observer registered DURING this walk --
            // a second in-scene dialog opened by the first one's completion -- would otherwise be
            // dropped unread, and its own unhook with it, which is exactly the abandoned
            // completion this mechanism exists to prevent.
            windowCloseObservers = Listeners.removedAll(windowCloseObservers, closing);
        }
        changeWatchers = null;
    }

    private Runnable[] windowCloseObservers;

    /**
     * Runs {@code observer} when the bound window is destroyed, the hook for
     * completion work driven by tickers/frames, which stop forever at that
     * point (an abandoned fade would otherwise leak an uncompleted future).
     * Observers run once and are dropped; the returned handle unregisters
     * earlier (call it when the normal path completed first).
     *
     * @param observer told once, when the window is destroyed; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    public Subscription observeWindowClosed(Runnable observer) {
        Ui.checkUiThread();
        Objects.requireNonNull(observer, "observer");
        windowCloseObservers = Listeners.added(windowCloseObservers, observer, NO_RUNNABLES);
        return once(() -> windowCloseObservers = Listeners.removed(windowCloseObservers, observer));
    }

    /** Runs measure/layout when dirty or resized (public for headless tests/embedding). */
    public void layoutPass(float newWidth, float newHeight) {
        if (!layoutDirty && newWidth == width && newHeight == height) {
            // Contained requests are still owed a pass: this is the entry point a headless test
            // or an embedder calls instead of rendering a frame, and without this a list that
            // scrolled would sit on stale rows until something else dirtied the layout.
            runContainedLayouts();
            runVisibilityLayouts();
            if (!layoutDirty) {
                return;
            }
            // One of them could not keep its promise, so do the full pass it escalated to, now,
            // rather than leaving the caller with a layout that is neither.
        }
        containedLayouts.clear(); // a full pass covers every contained request outstanding
        visibilityChanges.clear(); // and every visibility change, having measured from the root
        // A full pass moves boxes, and a box is what the accessible tree publishes, so the pass
        // itself owes the node flag. Every other path here reaches it through a funnel that set
        // the flag already; the two that do not are a window resize -- whose arm marks the layout
        // dirty and schedules a frame without declaring damage, because a layout frame damages
        // everything structurally -- and this method called directly by a headless embedder with
        // a new size. Both moved every node a reader was holding and published nothing.
        accessibleNodesDirty = true;
        width = newWidth;
        height = newHeight;
        root.measure(Constraints.tight(newWidth, newHeight));
        root.layoutBox(0, 0, newWidth, newHeight);
        for (Widget<?> overlay : overlays) {
            overlay.measure(Constraints.tight(newWidth, newHeight));
            overlay.layoutBox(0, 0, newWidth, newHeight);
        }
        layoutDirty = false;
        // A pass has run, so the boxes are real. Until it has, there is nothing truthful to say
        // about this window's geometry and the accessible tree says nothing rather than zeros.
        hasLaidOut = true;
        // One marker per pass that actually ran, sourced at the root, saying re-read the bounds
        // you hold. Per-widget geometry is deliberately not an aspect: layoutBox and moveChild
        // are the innermost loop of every pass and of every scroll frame, and hooking them is the
        // one change that would put this channel on the hot path and make it scale with the tree.
        root.notifyChange(Change.of(Change.Aspect.LAYOUT, Change.Origin.ADJUSTMENT));
        if (pendingReveal != null) {
            Widget<?> reveal = pendingReveal;
            pendingReveal = null;
            // Only if it is still the focus and still in this tree: focus may
            // have moved (or the widget detached) between defer and layout.
            if (reveal == focused && reveal.scene() == this) {
                reveal.revealInView();
            }
        }
    }
}
