package limn.scene;

import limn.backend.Cursor;
import limn.backend.NativeWindow;
import limn.backend.spi.WindowInput;
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
import limn.math.Vec2;
import limn.scene.event.CharEvent;
import limn.scene.event.PreeditEvent;
import limn.scene.event.DragEvent;
import limn.scene.event.DragStartEvent;
import limn.scene.event.DropEvent;
import limn.scene.event.InputEvent;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.jspecify.annotations.Nullable;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import limn.backend.spi.WindowPeer;

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

    private record RawScroll(float dx, float dy, float x, float y, boolean began) implements Raw {
    }

    private record RawKey(int key, int layoutCodePoint, boolean pressed, boolean repeat, int mods) implements Raw {
    }

    private record RawChar(int codepoint) implements Raw {
    }

    private record RawPreedit(String text, int caret, int targetStart, int targetLength) implements Raw {
    }

    private record RawPointer(boolean entered) implements Raw {
    }

    private record RawDrop(java.util.List<java.nio.file.Path> paths, float x, float y) implements Raw {
    }

    private record RawFocus(boolean focused) implements Raw {
    }

    private record RawFullscreen(boolean fullscreen) implements Raw {
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
    /** The colour the application chose to clear to, or null to clear to the theme's background. */
    private @Nullable Color background;

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
        paletteListener.armed(limn.scene.internal.SceneBackground.observeChanges(paletteListener));
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
     * palette does ({@link limn.components.Theme}, heard through
     * {@link limn.scene.internal.SceneBackground}).
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
     * response. The four measurement axes share one, answering {@code relayout()}; the palette
     * has its own, answering a repaint.
     *
     * <p><b>The palette is heard without being named.</b> {@code Theme} is a
     * {@code limn.components} type and this is {@code limn.scene}, the layer that package is built
     * on, and no file here names one. So the theme tells {@link limn.scene.internal.SceneBackground}
     * when it switches, and the scene subscribes there. Until 2026-10-18 a palette switch was the
     * application's to answer, scene by scene, and a window it forgot, or opened after the switch,
     * cleared to the old palette's canvas behind the new palette's widgets.
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

    private final GlobalAxisListener metricsListener = new GlobalAxisListener(this, Scene::relayoutForAnAxis);

    private final GlobalAxisListener paletteListener = new GlobalAxisListener(this, Scene::repaintForThePalette);

    /**
     * What the palette moving asks of this scene: to repaint whole, since every widget reads it as
     * it paints and so does the clear colour unless {@link #setBackground} chose one. With no
     * runtime installed the scene only keeps the fact, for the reason {@link #relayoutForAnAxis}
     * gives.
     */
    private void repaintForThePalette() {
        if (!Ui.isInstalled()) {
            fullDamagePending = true;
            return;
        }
        requestRender();
    }

    /**
     * What a process-wide axis moving asks of this scene: to measure again. With a runtime
     * installed that is {@link #relayout()}. With none, the scene only keeps the fact: there is
     * no user-interface thread to be on and no frame to ask for, and the axis is the process's,
     * so it goes on moving after the runtime a scene was built under has gone and before the
     * scene is collected. Asked for a frame there, every such scene threw at every change of
     * language, direction or size; a suite of tests that builds thousands of scenes under
     * runtimes of a test's length logged ten thousand stack traces doing it.
     */
    private void relayoutForAnAxis() {
        if (!Ui.isInstalled()) {
            markEveryLayerForMeasure();
            return;
        }
        relayout();
    }

    /** The root and every overlay must measure again at the next layout pass. */
    private void markEveryLayerForMeasure() {
        root.markMeasureDirtyDeep();
        for (int i = 0; i < overlays.size(); i++) {
            overlays.get(i).markMeasureDirtyDeep(); // overlays are NOT in root's tree
        }
        layoutDirty = true;
    }

    /** Wires this scene into a window: input, frame rendering, invalidation, clipboard. */
    public void bind(NativeWindow window) {
        this.window = window;
        if (detachedByClose) {
            // Bound again after its window closed: the tree that close took out comes back.
            detachedByClose = false;
            if (locale != null) {
                limn.i18n.I18n.retainLocale(locale);
            }
            root.setSceneRecursively(this);
            for (int i = 0; i < overlays.size(); i++) {
                if (overlays.get(i).scene() == null) {
                    overlays.get(i).setSceneRecursively(this);
                }
            }
        }
        // A grafted popup is published by the scene it was grafted into, so its window is never
        // asked for a bridge (asking is what opens one) and is told why.
        if (graftedInto != null) {
            WindowPeer.of(window).publishAccessibilityElsewhere();
        }
        limn.backend.AccessibilityBridge attached = graftedInto != null
                ? limn.backend.AccessibilityBridge.NONE : window.accessibility();
        this.bridge = attached != null ? attached : limn.backend.AccessibilityBridge.NONE;
        // Asked once, remembered, and never asked again: only the scene needs to know that it owes
        // a first tree to a bridge whose own gate cannot open until it has one.
        this.primingPublishOwed = this.bridge.needsPrimingPublish();
        this.accessibleNodesDirty = true;
        // attach() and not a tree: at this instant the scene has never laid out, so every box in
        // it would be a zero-size rectangle at the origin. The first frame is where the boxes are.
        this.bridge.attach(accessibilityHost());
        WindowPeer.of(window).setInput(input);
        this.renderRequester = window::requestFrame;
        WindowPeer.of(window).setFrameCallback((renderer, frame) -> renderFrame(renderer.canvas(), frame));
        // Last, and after the two calls that make this window usable. It walks and publishes, which
        // is more than the rest of this method does, and a bind that threw here would leave the
        // window holding neither input nor a frame callback — a window that is up, attached and
        // dead. Nothing above it waits for it: the frame it would be read from cannot run until the
        // pump turns, which is after bind returns (2026-09-16 review).
        publishTheWindowNodeIfOwed();
        watchUnderlines(this);
        runObservers(windowBoundObservers);
    }

    /** The scenes bound to a window that is open, which a change of the system's underlines repaints. */
    private static final java.util.Set<Scene> BOUND_SCENES =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** The one subscription to the system's preferences, made at the first bind and kept. */
    private static @Nullable Subscription underlinesWatch;
    /** Whether the system asked for access letters drawn at all times, as last heard. */
    private static boolean underlinesWere;

    /**
     * Repaints {@code scene}'s letters when the system's "underline access keys" setting changes
     * while it is open (Windows' keyboard accessibility setting): partial rendering repaints only
     * what was damaged, so without this the underlines appeared or vanished button by button as
     * each happened to be drawn again.
     */
    private static void watchUnderlines(Scene scene) {
        BOUND_SCENES.add(scene);
        if (underlinesWatch == null) {
            underlinesWere = SystemPreferences.current().underlineAccessKeys();
            underlinesWatch = SystemPreferences.observeChanges(() -> {
                boolean now = SystemPreferences.current().underlineAccessKeys();
                if (now == underlinesWere) {
                    return;
                }
                underlinesWere = now;
                for (Scene each : new ArrayList<>(BOUND_SCENES)) {
                    if (!each.accessLettersShown) { // Alt held: they are drawn, and stay
                        each.repaintAccessLetters();
                    }
                }
            });
        }
    }

    private Runnable[] windowBoundObservers;

    /** The widget that had the keyboard before the one that has it now; see {@link #focusedBefore()}. */
    private java.lang.ref.WeakReference<Widget<?>> focusedBefore = new java.lang.ref.WeakReference<>(null);

    /**
     * Not API: for a menu bar, which takes the keyboard when it is pressed and whose editing items
     * act on the widget that had it.
     *
     * @return the widget focused before the current one, when it is still in this scene
     */
    @Nullable Widget<?> focusedBefore() {
        Widget<?> before = focusedBefore.get();
        return before != null && before.scene() == this ? before : null;
    }

    /**
     * Not API: a widget whose presentation depends on the window, which a scene made before its
     * window does not have when the widget is attached.
     *
     * @param observer told at the end of each {@link #bind}
     */
    Subscription observeWindowBound(Runnable observer) {
        Ui.checkUiThread();
        Objects.requireNonNull(observer, "observer");
        windowBoundObservers = Listeners.added(windowBoundObservers, observer, NO_RUNNABLES);
        return once(() -> windowBoundObservers = Listeners.removed(windowBoundObservers, observer));
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
                WindowPeer.of(window).supportsAbsolutePositioning());
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
        markEveryLayerForMeasure();
        requestRender();
    }

    /**
     * Sizes the bound window to what this scene's content asks for: the root measured with no bound
     * on either axis, rounded up to whole points, kept within the window's
     * {@linkplain NativeWindow#sizeLimits() size limits} and no larger than its display's work
     * area. The call a dialog or a tool window makes once its content is in place, instead of
     * guessing a size in its configuration; a window created hidden can be packed before it is
     * shown, and appears at its size.
     *
     * <p>What the content asks for is each widget's natural size: a label its text, a button its
     * label and padding, a column the sum of its children. The width is settled first, and the
     * height is then measured at that width, so a paragraph that wraps gets the lines it wraps
     * into. A dialog or a form packs well. Content that fills whatever it is given does not ask
     * for much: a flexible child asks for its floor ({@code Expanded.atLeast}), a list or a table
     * for little more than its header, and a widget with no natural size on an axis leaves the
     * window's size there as it was. Text that wraps asks for its whole length on one line, which
     * the work area then caps. Bounds on the content are what a packed window follows: a
     * paragraph with {@link Widget#setMaxWidth} packs to that width, and a list given a floor
     * packs to that height. The layout is redone at the new size on the next frame. UI thread
     * only.
     *
     * @throws IllegalStateException when the scene is bound to no window
     */
    public void pack() {
        Ui.checkUiThread();
        if (window == null) {
            throw new IllegalStateException("pack() sizes the window a scene is bound to, and this "
                    + "scene is bound to none");
        }
        limn.backend.SizeLimits limits = window.sizeLimits();
        float areaWidth = Float.POSITIVE_INFINITY;
        float areaHeight = Float.POSITIVE_INFINITY;
        limn.backend.Display display = window.display();
        float factor = window.logicalToScreenFactor();
        if (display != null && factor > 0) {
            limn.backend.ScreenRect area = display.workArea();
            if (area.width() > 0 && area.height() > 0) {
                areaWidth = area.width() / factor;
                areaHeight = area.height() / factor;
            }
        }
        // The width first, with nothing bounding either axis, and then the height AT that width:
        // text that wraps has no natural width of its own, only a height for a width it is given,
        // and measured once with both open it would ask for one line's height at any width.
        Size natural = root.measure(new Constraints(0, Constraints.UNBOUNDED_LIMIT, 0,
                Constraints.UNBOUNDED_LIMIT));
        float width = Math.min(areaWidth, limits.clampWidth(Float.isFinite(natural.width())
                ? (float) Math.ceil(natural.width()) : window.logicalWidth()));
        Size atWidth = root.measure(new Constraints(width, width, 0, Constraints.UNBOUNDED_LIMIT));
        float height = Math.min(areaHeight, limits.clampHeight(Float.isFinite(atWidth.height())
                ? (float) Math.ceil(atWidth.height()) : window.logicalHeight()));
        window.setSize(Math.max(1, (int) width), Math.max(1, (int) height));
        relayout(); // measured with no bound just now; the next pass measures at the window's size
    }

    /** @return the bound window, or {@code null} when headless */
    public @Nullable NativeWindow window() {
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
    public @Nullable Widget<?> find(String id) {
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
    public <T> @Nullable T find(String id, Class<T> type) {
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
    public @Nullable ControlSize controlSize() {
        return controlSize;
    }

    /**
     * Sets this window's default step, the per-window root of the inheritance chain (a
     * compact tool window beside a regular main window). Widgets that declare their own step,
     * and their subtrees, are unaffected. {@code null} restores fall-through. UI thread only.
     */
    public void setControlSize(@Nullable ControlSize size) {
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
    public @Nullable LayoutDirection layoutDirection() {
        return layoutDirection;
    }

    /**
     * Sets this window's default layout direction, the per-window root of the inheritance chain:
     * the one line an application whose interface reads right to left writes. Widgets that declare
     * their own direction, and their subtrees, are unaffected &mdash; which is what lets a
     * left-to-right code editor, log pane or URL bar sit inside it. {@code null} restores
     * fall-through. UI thread only.
     */
    public void setLayoutDirection(@Nullable LayoutDirection direction) {
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
    public java.util.@Nullable Locale locale() {
        return locale;
    }

    /**
     * Sets this window's default locale, the per-window root of the inheritance chain: what makes
     * two windows in two languages expressible, which one process-wide locale could not say.
     * Widgets that declare their own locale, and their subtrees, are unaffected. {@code null}
     * restores fall-through. Retains the declared locale's bundle tables exactly as
     * {@link Widget#setLocale} does. UI thread only.
     */
    public void setLocale(java.util.@Nullable Locale locale) {
        Ui.checkUiThread();
        if (Objects.equals(this.locale, locale)) {
            return;
        }
        java.util.Locale previous = this.locale;
        this.locale = locale;
        // A scene whose window closed holds no retain: the close released its locale and the next
        // bind retains whichever it has then. Counting here too would keep a language loaded for
        // ever, or release one another window still shows.
        if (!detachedByClose) {
            if (locale != null) {
                limn.i18n.I18n.retainLocale(locale);
            }
            if (previous != null) {
                limn.i18n.I18n.releaseLocale(previous);
            }
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
        stackOverlay(overlay);
        // Move focus into the new modal layer.
        focusTraverse(false);
        requestRender();
    }

    /**
     * Pushes {@code overlay} as {@link #pushOverlay(Widget)} does, over content that another
     * scene, {@code movedFrom}, drew until now, and with the keyboard where it was there: the
     * widget that held that scene's focus, now inside {@code overlay}, holds this one's. A modal
     * dialog that was a window of its own moves into its owner's scene this way when the owner
     * goes fullscreen.
     *
     * <p>The widget is told nothing, neither a loss nor a gain, because for it nothing changed: a
     * field keeps its caret and its selection, and an entry left half typed stays open. Moving the
     * focus out and back in would not be the same thing. The loss is what ends an entry: a field
     * drops its selection and a spinner commits what was typed. And the overlay's first stop is
     * focused by traversal, the arrival by Tab, which makes a field select all of its text, so the
     * next key typed would replace everything the person had typed.
     *
     * <p>What does not come along is a composition an input method had open, which belongs to the
     * window being left: it is cancelled there, and the widget is told it ended as a platform
     * tells it of a cancelled one. When nothing inside {@code overlay} held {@code movedFrom}'s
     * focus, the focus goes to the overlay's first stop, as with {@link #pushOverlay(Widget)}.
     *
     * <p>Not API, since only the toolkit moves content between scenes: a component reaches it
     * through {@link limn.scene.internal.SceneAccess#pushMovedOverlay}.
     *
     * @param overlay   the overlay's root, holding content {@code movedFrom} drew until now
     * @param movedFrom the scene that content comes from, which lets go of its focus
     */
    void pushOverlay(Widget<?> overlay, Scene movedFrom) {
        Ui.checkUiThread();
        Objects.requireNonNull(overlay, "overlay");
        Objects.requireNonNull(movedFrom, "movedFrom");
        Widget<?> keyboard = movedFrom == this ? null : movedFrom.focused;
        if (keyboard == null || !isInSubtree(keyboard, overlay) || !canTakeFocus(keyboard)) {
            pushOverlay(overlay);
            return;
        }
        stackOverlay(overlay);
        boolean composed = movedFrom.letGoOfMovedFocus();
        if (composed) {
            dispatchBubbling(keyboard, new PreeditEvent("", 0, 0, 0));
        }
        // CODE, the origin of pushOverlay(Widget)'s traversal: the widget this scene had focused is
        // told of its loss exactly as that traversal would tell it.
        setFocus(keyboard, Change.Origin.CODE, true);
        requestRender();
    }

    /** Puts {@code overlay} on top, capturing input, and keeps the focus it takes to give back. */
    private void stackOverlay(Widget<?> overlay) {
        // A drag captured before this modal opened must not keep feeding the
        // now-blocked content beneath the scrim: DRAG follows the capture, not
        // hit-testing. Synthesize its RELEASE (window blur's treatment).
        cancelPointerState();
        // Nor may the rest of a wheel gesture: the scroller that took it is beneath the scrim now,
        // and the inertia still arriving goes where hit-testing sends it, into the modal.
        wheelLatch = null;
        overlayFocusReturn.add(focused); // remember who to focus when this closes
        overlays.add(overlay);
        overlay.setSceneRecursively(this);
        layoutDirty = true;
    }

    /**
     * Lets go of the focused widget without telling it, because it keeps the keyboard in the scene
     * it moved to, and cancels what the input method was composing for it in this window, which
     * cannot finish there.
     *
     * @return whether a composition was open in it, which the widget must still be told has ended
     */
    private boolean letGoOfMovedFocus() {
        Widget<?> keyboard = focused;
        boolean wasComposing = composing;
        focused = null;
        composing = false;
        if (window != null && keyboard.acceptsTextInputInternal()) {
            WindowPeer.of(window).resetPreedit();
        }
        updateImeState();
        return wasComposing;
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
     * Publishes a popup that draws in a window of its own as part of this scene's accessible tree,
     * under the widget that opened it, the way a native drop-down list is a child of its field: a
     * combo's list, a date picker's calendar. An assistive technology then never leaves this
     * window's tree while the popup is open; the list's cursor is this tree's own, and the popup's
     * window publishes nothing of its own.
     *
     * <p>Call it before {@code popup} is bound to its window, and only for a popup whose keyboard is
     * its opener's: the field keeps it and drives the popup, so the focused node stays in this
     * tree. A pointer click in the popup's window makes it the desktop's key window; such a popup
     * hands every key that reaches that window on to its opener, and gives this window the
     * keyboard back when it closes while holding it, as the combo box's list and the date
     * picker's calendar do. The popup's root names its opener with
     * {@link Widget#setInheritanceHost}; its nodes are published under the opener's, in this
     * window's coordinates, while its window is visible, and leave the tree when that window
     * closes. What a reader performs on them is performed in the popup's scene.
     *
     * @param popup the popup's scene, not yet bound
     * @throws IllegalArgumentException if the popup's root names no opener
     * @throws IllegalStateException    if the popup is already bound, or grafted elsewhere
     */
    public void graftPopup(Scene popup) {
        Ui.checkUiThread();
        Objects.requireNonNull(popup, "popup");
        if (popup == this || popup.window != null || popup.graftedInto != null) {
            throw new IllegalStateException("a popup is grafted once, into another scene, before it is bound");
        }
        if (popup.root.inheritanceHost() == null) {
            throw new IllegalArgumentException("a grafted popup's root names the widget that opened it "
                    + "with setInheritanceHost");
        }
        popup.graftedInto = this;
        graftedPopups.add(popup);
        invalidateAccessible();
    }

    /** The popups grafted into this scene's tree, for the walk: the widget each hangs under is its root's host. */
    List<Scene> graftedPopups() {
        return graftedPopups;
    }

    /** @return whether {@code other} is a popup grafted into this scene's tree */
    boolean isGraftedPopup(Scene other) {
        return other != null && other.graftedInto == this;
    }

    /** @return whether {@code widget} is the root of a popup scene grafted into another's tree */
    boolean isGraftedPopupRoot(Widget<?> widget) {
        return graftedInto != null && widget == root;
    }

    /**
     * Whether every overlay above the one at {@code index} was pushed as a popup, so what lies
     * at {@code index} is beneath popups alone and is published enabled.
     *
     * @param index an overlay's position in the stack, or {@code -1} for the root
     * @return true when at least one overlay lies above and every one of them is a popup
     */
    boolean onlyPopupsAbove(int index) {
        boolean any = false;
        for (int i = index + 1; i < overlays.size(); i++) {
            Widget<?> overlay = overlays.get(i);
            if (keyboardBelow.contains(overlay)) {
                continue; // it leaves the keyboard below: it shadows nothing there
            }
            if (!popupOverlays.contains(overlay)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** Removes a modal overlay and restores focus to whatever it took it from. */
    public void removeOverlay(Widget<?> overlay) {
        Ui.checkUiThread();
        int index = overlays.indexOf(overlay);
        if (index < 0) {
            return;
        }
        popupOverlays.remove(overlay);
        boolean keptKeyboardBelow = keyboardBelow.remove(overlay);
        closingOverlays.remove(overlay);
        boolean wasTop = index == overlays.size() - 1;
        overlays.remove(index);
        Widget<?> restore = overlayFocusReturn.remove(index);
        // An overlay opened over this one while it held the focus saved a widget inside it (the
        // menu a menu bar's arrow walked to, opened while the one walked from was fading): that
        // overlay's way back now goes where this one's would have, or its close focused nothing.
        for (int i = index; i < overlayFocusReturn.size(); i++) {
            Widget<?> saved = overlayFocusReturn.get(i);
            if (saved != null && isInSubtree(saved, overlay)) {
                overlayFocusReturn.set(i, restore);
            }
        }
        onWidgetDetached(overlay);
        overlay.setSceneRecursively(null);
        layoutDirty = true;
        // Focus belongs to the topmost overlay. Removing a lower one (e.g. the
        // previous menu still fading out beneath the menu it was switched to) must
        // leave the current top overlay's focus untouched. One that left the keyboard beneath it
        // never took the focus, so there is nothing to give back.
        if (!wasTop || keptKeyboardBelow) {
            requestRender();
            return;
        }
        // Return focus to the widget that had it before this overlay opened
        // (the button that triggered the dialog), if it is still usable.
        if (restore != null && restore != overlay && isInSubtree(restore, keyboardRoot())
                && canTakeFocus(restore)) {
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
     * The colour cleared behind the tree each frame, chosen by the application and kept whatever
     * the palette does: a scene that clears to transparent to let the desktop through, a video
     * that wants black around it. A translucent one only shows through where the window itself
     * is translucent.
     *
     * <p>A scene whose application chose none, which is every scene until this is called, clears
     * to the background of the palette in effect and follows it when the palette switches, in
     * every window, those opened after the switch included.
     *
     * @param color the colour to clear to, or {@code null} to follow the palette again
     */
    public void setBackground(@Nullable Color color) {
        Ui.checkUiThread();
        this.background = color;
    }

    /**
     * @return the colour the next frame clears to: the one {@link #setBackground} chose, or the
     *         background of the palette in effect when none was chosen
     */
    public Color background() {
        Color chosen = background;
        return chosen != null ? chosen : limn.scene.internal.SceneBackground.of(root);
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
    /**
     * Widgets in this scene that draw their children through a matrix, kept as a list for the
     * reason the backdrop's are: the hit test asks it whenever a point falls outside a box, and it
     * is empty in almost every application. Maintained on the same funnel, and as a widget's
     * matrix comes and goes.
     */
    private final List<Widget<?>> transformed = new ArrayList<>();
    /** A rectangle carried through a matrix by the damage clamp; one, since damage runs on the UI thread. */
    private final float[] projectScratch = new float[4];
    // The frame's damage, as fixed-capacity float lists rather than lists of Rect: every
    // invalidate() lands in pendingDamage, and with partial rendering on by default an object per
    // call there was the toolkit's busiest allocation. See DamageRects. A list that starts whole
    // is what null meant before: a first frame has no history and repaints everything.
    private final DamageRects pendingDamage = new DamageRects(MAX_DAMAGE_RECTS, false);
    private final DamageRects freshDamage = new DamageRects(MAX_DAMAGE_RECTS, false);
    private final DamageRects repaintRegion = new DamageRects(MAX_DAMAGE_RECTS, false);
    // What each of the last PRESENT_HISTORY presents changed, newest at presentHead - 1. Counted in
    // presents rather than in content frames, because a back buffer's age is: a re-present is a
    // present that changed nothing, and leaving it out of the history made an age of 2 reach one
    // content frame too far back (the popups that came up empty on Linux, 2026-09-25).
    private static final int PRESENT_HISTORY = 8;
    private final DamageRects[] presentChanges = newPresentHistory();
    private int presentHead;
    private int presentsRecorded;
    private final DamageRects presentChange = new DamageRects(MAX_DAMAGE_RECTS, false); // scratch: this present's
    private final DamageRects unionScratch = new DamageRects(MAX_DAMAGE_RECTS, false);
    /** Scratch for the clip walk, so damaging a widget makes no object. UI thread only. */
    private final float[] clipScratch = new float[4];
    private final float[] backdropScratch = new float[4];
    private boolean[] backdropTaken = new boolean[0];

    // Damage-debug flashes: each fresh damage region stays highlighted for
    // DAMAGE_FLASH_SECONDS, fading out. A fading flash changes pixels every
    // frame, so its rect is part of what a present changes while alive, and
    // once more when it goes (flashPrev1: the frame that erases it changed that
    // area too); the present history then carries it to the older buffers.
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
        markAccessibleNodesDirty();
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
    /**
     * Sets the node flag, and for a grafted popup tells the scene that publishes its nodes at once
     * rather than at this scene's next frame, which that scene's walk does not wait for.
     */
    private void markAccessibleNodesDirty() {
        accessibleNodesDirty = true;
        if (graftedInto != null) {
            graftedInto.invalidateAccessible();
        }
    }

    void invalidateAccessible() {
        accessibleNodesDirty = true;
        if (graftedInto != null) {
            graftedInto.invalidateAccessible();
            return;
        }
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
        if (graftedInto != null) {
            graftedInto.announce(text, politeness); // said by the window that publishes it
            return;
        }
        if (announcements.size() >= MAX_ANNOUNCEMENTS) {
            // An application announcing once per frame is a defect the slow-task budget already
            // catches; dropping the oldest keeps this bounded without hiding the newest.
            announcements.remove(0);
        }
        announcements.add(new Announcement(text, politeness));
        announced++;
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
    /** The popups in windows of their own this scene's tree publishes ({@link #graftPopup}). */
    private final List<Scene> graftedPopups = new ArrayList<>(1);
    /**
     * Popup overlays that leave the keyboard with the layer beneath them (SceneAccess
     * #pushPopupLeavingKeyboard): an editable combo's list drawn in scene, whose field keeps the
     * focus, the caret and the input method while the list is up.
     */
    private final List<Widget<?>> keyboardBelow = new ArrayList<>(1);
    /**
     * Overlays that gave the keyboard back and are fading out. One is still the top layer for the
     * pointer until it is removed, and is no longer in the accessible tree: what is beneath it is
     * being typed into again, and a reader has nothing to be on in a layer that is going.
     */
    private final List<Widget<?>> closingOverlays = new ArrayList<>(1);
    /** A grafted popup whose cursor is this tree's while its opener keeps the keyboard; see SceneAccess#showCursorOf. */
    private @Nullable Scene cursorPopup;
    /** Where this window's keys go instead of the focus, while a menu it opened is up; see SceneAccess#routeKeyboard. */
    private limn.scene.internal.SceneAccess.@Nullable KeyRoute keyRoute;
    /** Keys and characters received for {@link #keyRoute} in the batch in progress. */
    private final List<Raw> routed = new ArrayList<>(2);
    /** The scene whose tree publishes this one, when this is a grafted popup; else {@code null}. */
    private Scene graftedInto;

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
        if (graftedInto != null) {
            return graftedInto.accessibleIdOf(widget); // published by the tree it is grafted into
        }
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
        // A node of a popup grafted into this tree is its own scene's widget: performed there, and
        // gated by that scene's layer rather than this one's.
        Scene home = owner == null ? null : owner.scene();
        if (owner == null || (home != this && !isGraftedPopup(home))) {
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
        // Enabled is the effective state, so a control inside a disabled container, one the
        // keyboard refuses too, stops here without a walk of its own.
        if (!owner.isEnabled() || owner.inertInTree()) {
            // Inert: a section's content sliding shut. The pointer and the keyboard pass it by,
            // and the walk publishes no verb there, so a verb a reader took off an older snapshot
            // is refused as well (ADR 067 §3, amended 2026-10-16).
            return;
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
        Widget<?> layer = home.accessibleInputLayer();
        Widget<?> keyboardLayer = home.accessibleKeyboardLayer();
        if (layer == null || !(isInSubtree(owner, layer)
                || (keyboardLayer != null && isInSubtree(owner, keyboardLayer)))) {
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
            done = home.performFreeVerb(owner, action);
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

    /** @return whether {@code overlay} gave the keyboard back and is fading out */
    boolean isClosingOverlay(Widget<?> overlay) {
        return closingOverlays.contains(overlay);
    }

    /**
     * The step that turns a frame into an accessible tree, run after layout and the hover update
     * and before the paint passes: bounds are settled there and hover has already moved whatever
     * it moves, and every change that matters to an assistive technology already schedules a frame,
     * so the frame is the flush point and no new scheduling is invented.
     */
    private void accessibilityStep(boolean rePresent) {
        if (graftedInto != null) {
            // Its boxes moved or its nodes changed in this frame (damage, layout, a first frame
            // after the window was shown): the scene that publishes them walks again.
            if (!rePresent && (accessibleNodesDirty || accessibleHeaderDirty)) {
                accessibleNodesDirty = false;
                accessibleHeaderDirty = false;
                graftedInto.invalidateAccessible();
            }
            return;
        }
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
            if (walkMayNameDetached && accessibleWalk != null) {
                accessibleWalk.forgetDetached(); // nobody reads, so no walk will write over them
            }
            walkMayNameDetached = false;
            return;
        }
        walkMayNameDetached = false; // a walk, or a tree that did not change
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
                window == null || WindowPeer.of(window).supportsAbsolutePositioning());
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
        for (int i = 0; i < graftedPopups.size(); i++) {
            // A popup's boxes are laid out in its own frame, which may not have run since it
            // changed; its nodes are published here, against boxes that have to be true now.
            graftedPopups.get(i).layOutForTheSceneThatPublishesIt();
        }
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
                window == null || WindowPeer.of(window).supportsAbsolutePositioning());
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

    /** What is queued to be said and not yet delivered, oldest first; for tests of this package. */
    List<String> queuedAnnouncements() {
        List<String> texts = new ArrayList<>();
        for (Announcement queued : announcements) {
            texts.add(queued.text().get());
        }
        return texts;
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
    public void setFrontPainter(java.util.function.@Nullable Consumer<Canvas> painter) {
        Ui.checkUiThread();
        this.frontPainter = painter;
        requestRender();
    }

    /** @return the front painter, or {@code null}. */
    public java.util.function.@Nullable Consumer<Canvas> frontPainter() {
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
        markAccessibleNodesDirty();
        scheduleFrame();
    }

    /** Widget.invalidate(x,y,w,h): damages a region given in the widget's local coordinates. */
    void damageWidgetRegion(Widget<?> widget, float x, float y, float w, float h) {
        if (partialRendering || damageDebug) {
            addClippedDamage(widget, x - 1, y - 1, w + 2, h + 2);
        }
        markAccessibleNodesDirty();
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
        markAccessibleNodesDirty();
        scheduleFrame();
    }

    /**
     * An opacity container's set and the steps of its fade: the damage {@link #damageWidget} adds,
     * grown to what the subtree paints, and no walk of the accessible tree, because nothing it
     * publishes reads an opacity. A fade of twelve frames would otherwise walk and compare the tree
     * twelve times to find it unchanged.
     */
    void damageWidgetPicture(Widget<?> widget) {
        if (partialRendering || damageDebug) {
            // What the whole subtree paints, and not the widget's box and outset alone: a child
            // laid out past the edge of a parent that does not clip fades with it.
            float[] b = widget.paintedBounds();
            addClippedDamage(widget, b[0], b[1], b[2] - b[0], b[3] - b[1]);
        }
        scheduleFrame();
    }

    /**
     * A transform container's set, a frame of its motion and an arrival: where its children are
     * drawn as its matrix stands, each with what it paints, damaged as {@link #damageWidget} damage
     * is, clamped by every clipping ancestor, and no layout. The container calls it before its
     * matrix changes and after, so that a move repaints the place it leaves and the place it
     * reaches; under a perspective neither is a fixed outset, which is why it is the projection
     * that is damaged.
     *
     * @param widget  the container
     * @param settled a set or an arrival, which moves the bounds the accessible tree publishes and
     *                so has it walked; a frame between has not, as a fade's has not
     */
    void damageMovedPicture(Widget<?> widget, boolean settled) {
        if (partialRendering || damageDebug) {
            float[] b = widget.childrenPaintedBounds();
            if (!Float.isNaN(b[0])) {
                addClippedDamage(widget, b[0], b[1], b[2] - b[0], b[3] - b[1]);
            }
        }
        if (settled) {
            markAccessibleNodesDirty();
        }
        scheduleFrame();
    }

    /**
     * A transform container came to rest somewhere new, by a set or at a motion's arrival: a popup
     * drawn in this scene whose anchor lies under it is laid out again, and a popup in a window of
     * its own placed against such an anchor is placed again by what placed it, so that each stands
     * against where its anchor is now drawn. Not on the frames of a motion, which a popup does not
     * follow.
     */
    void anchorsMoved(Widget<?> container) {
        for (int i = 0; i < overlays.size(); i++) {
            Widget<?> overlay = overlays.get(i);
            Widget<?> anchor = overlay.inheritanceHost();
            if (anchor != null && isInSubtree(anchor, container)) {
                overlay.markNeedsLayout();
            }
        }
        if (anchoredWindows.isEmpty()) {
            return;
        }
        // A copy: placing a window again may close a popup, which lets go of its entry.
        for (AnchoredWindow placed : List.copyOf(anchoredWindows)) {
            if (isInSubtree(placed.anchor(), container)) {
                placed.placeAgain().run();
            }
        }
    }

    /** A popup's window placed against {@code anchor}, and what places it again. */
    private record AnchoredWindow(Widget<?> anchor, Runnable placeAgain) {
    }

    /** The popup windows placed against an anchor in this scene; see {@link #observeAnchorMoved}. */
    private final List<AnchoredWindow> anchoredWindows = new ArrayList<>();

    /**
     * Not API: tells {@code placeAgain} each time a transform container above {@code anchor} comes
     * to rest somewhere new, for a popup in a window of its own placed against the anchor, which the
     * scene cannot place since it knows nothing of the window's placement. Laid out again in the
     * scene, a popup drawn there needs none of this.
     *
     * @param anchor     the widget the popup's window stands against
     * @param placeAgain places the window again against where the anchor is now drawn
     * @return a handle that unregisters
     */
    Subscription observeAnchorMoved(Widget<?> anchor, Runnable placeAgain) {
        Ui.checkUiThread();
        AnchoredWindow placed = new AnchoredWindow(Objects.requireNonNull(anchor, "anchor"),
                Objects.requireNonNull(placeAgain, "placeAgain"));
        anchoredWindows.add(placed);
        return once(() -> anchoredWindows.remove(placed));
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
            // Where the parent draws it, through the matrix a transform container draws its
            // children with: the damage lands where the pixels are.
            Widget<?> up = node.parent();
            if (up != null && up.childTransform != null && up.spreadsChildChanges()) {
                // Laid through a mipmap chain, a change is seen past its own bounds: the whole
                // picture, where the parent draws it.
                float[] whole = up.childrenPaintedBounds();
                if (Float.isNaN(whole[0])) {
                    return false;
                }
                x0 = whole[0];
                y0 = whole[1];
                x1 = whole[2];
                y1 = whole[3];
            } else if (up != null && up.childTransform != null) {
                float[] b = projectScratch;
                up.childTransform.bounds(x0, y0, x1, y1, b);
                if (Float.isNaN(b[0])) {
                    return false; // behind the eye: nothing drawn
                }
                x0 = b[0];
                y0 = b[1];
                x1 = b[2];
                y1 = b[3];
            }
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
     * <p>Each rectangle goes in snapped to the device grid, as {@link #consumeFreshDamage} snaps
     * every other one. A panel lies wherever layout put it, a fraction of a point in when it is
     * centred, and a pass whose edge cuts a pixel clears that pixel whole and paints it back only in
     * part: on a Retina screen that was a line down the panel's edge, the picture under it mixed with
     * the window's background, as tall as the pass and standing until the pixel was repainted whole.
     *
     * @param canvas the frame's canvas, for its grid and its bounds
     * @param fresh this frame's damage, widened in place; whole or empty, it has nothing to add
     */
    private void withBackdropDependants(Canvas canvas, DamageRects fresh) {
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
                // Its reach as well as its box: what the widget reads is what it depends on, and it
                // must read it inside the pass, painted in order, as a whole frame would let it.
                float outset = 1 + widget.paintOutset() + Math.max(0, widget.backdropReach());
                if (!clippedSceneRect(widget, -outset, -outset,
                        widget.width() + 2 * outset, widget.height() + 2 * outset,
                        backdropScratch)
                        || !snapToDeviceGrid(canvas, backdropScratch)) {
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
    public Subscription observePresses(java.util.function.Consumer<@Nullable Widget<?>> observer) {
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
        runObservers(windowBlurObservers);
    }

    private Runnable[] windowFocusObservers;

    /**
     * Observes the bound window gaining OS focus, as the backend reports it: the other half of
     * {@link #observeWindowBlur}. Told at the end of the input batch that brought the report.
     *
     * <p>Not API: only the toolkit waits on it, a modal dialog whose card has moved into this scene
     * keeping its own window up until this one has the focus. A component reaches it through
     * {@link limn.scene.internal.SceneAccess#observeWindowFocus}.
     *
     * @param observer told when the window gains focus; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    Subscription observeWindowFocus(Runnable observer) {
        Ui.checkUiThread();
        Objects.requireNonNull(observer, "observer");
        windowFocusObservers = Listeners.added(windowFocusObservers, observer, NO_RUNNABLES);
        return once(() -> windowFocusObservers = Listeners.removed(windowFocusObservers, observer));
    }

    private static void runObservers(Runnable[] snapshot) {
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

    @SuppressWarnings("unchecked")
    private static final java.util.function.Consumer<Boolean>[] NO_FULLSCREEN_OBSERVERS =
            (java.util.function.Consumer<Boolean>[]) new java.util.function.Consumer<?>[0];

    private java.util.function.Consumer<Boolean>[] fullscreenObservers;

    /**
     * Observes the bound window entering or leaving fullscreen, the cue that closes a surface open
     * in a window of its own over it (a menu, a drop-down list, a calendar), as the platforms
     * dismiss their own menus on a change of their window's state: the change resizes and moves
     * the window under a surface placed against its old geometry, and a window fullscreen at a
     * named mode is minimized as soon as another window takes the focus. The person can make the
     * change on macOS with the green button or Control-Command-F while a list or a calendar is
     * open, because neither takes the focus from the window. A modal dialog's window hears it too,
     * and moves into the scene.
     *
     * <p>Told once per change, at the end of the input batch that brought it, even while a modal
     * blocks the window; see {@link WindowInput#windowFullscreenChanged}.
     *
     * @param observer told whether the window is fullscreen now; never null
     * @return a handle that unregisters; cancelling it twice is a no-op. UI thread
     */
    public Subscription observeWindowFullscreen(java.util.function.Consumer<Boolean> observer) {
        Ui.checkUiThread();
        Objects.requireNonNull(observer, "observer");
        fullscreenObservers =
                Listeners.added(fullscreenObservers, observer, NO_FULLSCREEN_OBSERVERS);
        return once(() -> fullscreenObservers = Listeners.removed(fullscreenObservers, observer));
    }

    private void notifyFullscreenObservers(boolean fullscreen) {
        java.util.function.Consumer<Boolean>[] snapshot = fullscreenObservers;
        if (snapshot == null) {
            return;
        }
        for (java.util.function.Consumer<Boolean> observer : snapshot) {
            try {
                observer.accept(fullscreen);
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
        markAccessibleNodesDirty();
        scheduleFrame();
    }

    void markContainedLayout(Widget<?> widget) {
        if (!containedLayouts.contains(widget)) {
            containedLayouts.add(widget);
        }
        markAccessibleNodesDirty();
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
     * <p><b>An old box is grown by the {@code paintOutset()} its widget declared before the pass,
     * and a new one by what it declares after</b>, because a layout can change how far a widget
     * paints past its box as well as the box: a scroll view whose content starts to overflow it on
     * the last axis it let a focus ring past declares nothing from then on, and its old box grown
     * by that nothing left the part of the ring it had drawn beside the box on screen.
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
            // Where the widget is drawn now, in its parent's space, and how far past that box it
            // paints, read before anything moves: the reach belongs to the box it was drawn around.
            float wx = widget.x();
            float wy = widget.y();
            float ww = widget.width();
            float wh = widget.height();
            float wo = 1 + widget.paintOutset();
            List<Widget<?>> children = List.copyOf(anchor.children());
            float[] was = new float[children.size() * 4];
            float[] wasReach = new float[children.size()];
            boolean[] wasVisible = new boolean[children.size()];
            for (int i = 0; i < children.size(); i++) {
                Widget<?> child = children.get(i);
                was[i * 4] = child.x();
                was[i * 4 + 1] = child.y();
                was[i * 4 + 2] = child.width();
                was[i * 4 + 3] = child.height();
                wasReach[i] = 1 + child.paintOutset();
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
                if (wasVisible[i]) {
                    float o = wasReach[i];
                    damageInParent(anchor, child, was[i * 4] - o, was[i * 4 + 1] - o,
                            was[i * 4 + 2] + 2 * o, was[i * 4 + 3] + 2 * o);
                }
                if (child.isVisible()) {
                    float o = 1 + child.paintOutset();
                    damageInParent(anchor, child, child.x() - o, child.y() - o,
                            child.width() + 2 * o, child.height() + 2 * o);
                }
            }
            if (widget.isVisible()) {
                damageWidget(widget);
            } else {
                damageInParent(parent, widget, wx - wo, wy - wo, ww + 2 * wo, wh + 2 * wo);
            }
            markAccessibleNodesDirty();
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

    /** A widget in this scene that now draws its children through a matrix. */
    void addTransformed(Widget<?> widget) {
        if (!transformed.contains(widget)) {
            transformed.add(widget);
        }
    }

    /** A widget that left this scene, or draws its children where layout put them again. */
    void removeTransformed(Widget<?> widget) {
        transformed.remove(widget);
    }

    /** @return the widgets in this scene that draw their children through a matrix; read only */
    List<Widget<?>> transformed() {
        return transformed;
    }

    /** Whether a widget left the tree since the accessibility walk last listed who answers for a node. */
    private boolean walkMayNameDetached;

    void onWidgetDetached(Widget<?> widget) {
        walkMayNameDetached = true;
        if (wheelLatch != null && isInSubtree(wheelLatch, widget)) {
            wheelLatch = null;
        }
        // Never leave dangling references to removed/disabled/hidden subtrees,
        // and never leave the widget itself with stuck hover/pressed state:
        // synthesize the EXIT/RELEASE it will otherwise never receive.
        // (State is cleared BEFORE dispatching, the reentrancy-safe order.)
        if (isInSubtree(hovered, widget)) {
            Widget<?> oldHover = hovered;
            hovered = null;
            timedDispatch(oldHover, new MouseEvent(MouseEvent.Type.EXIT, mouseX, mouseY, -1, 0, 0, 0));
            tellHoverWithin(null);
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
        DragSession dragging = dragSession;
        if (dragging != null) {
            // A drag whose source is gone has nobody to tell; one whose target is gone has
            // nowhere to land until the pointer finds another.
            if (isInSubtree(dragging.source, widget)) {
                finishDrag(false);
            } else if (isInSubtree(dragging.target, widget)) {
                dragging.target = null;
                dragging.action = null;
                applyCursor();
            }
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
        Widget<?> keyTarget = focused != null ? focused : keyboardRoot();
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
        if (dragSession != null) {
            finishDrag(false);
        }
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
    public @Nullable Widget<?> focusedWidget() {
        return focused;
    }

    /**
     * Moves keyboard focus to {@code widget}, or clears it when {@code null}. Ignored
     * for a widget that is not focusable, for one that is hidden or disabled itself or through
     * any ancestor (a control inside a hidden tab or a disabled form), for one inside a subtree
     * sliding shut, and for one outside the topmost modal overlay. One merely scrolled out of
     * its viewport is not refused: arriving reveals it. UI thread only.
     *
     * <p>An application calling this is code moving the focus, so the two {@code FOCUS} changes
     * it announces carry {@code CODE}. A gesture that moves focus does not come through here: a
     * click and a Tab enter the funnel from the scene's own input paths, and the two components
     * that move focus out of a gesture -- a tab strip's arrow key and a radio group's -- enter it
     * through {@link Widget#requestFocus(Change.Origin)}. Reading the method rather than the
     * entry point would report every keyboard-driven focus move in the toolkit as made by code.
     */
    public void requestFocus(@Nullable Widget<?> widget) {
        Ui.checkUiThread();
        requestFocus(widget, Change.Origin.CODE);
    }

    /** The funnel the origin travels through; see {@link #requestFocus(Widget)}. */
    void requestFocus(Widget<?> widget, Change.Origin origin) {
        Ui.checkUiThread();
        if (widget != null && !canTakeFocus(widget)) {
            return;
        }
        // Focus is confined to the input root (the top modal overlay): every
        // other focus path already is (click-to-focus hit-tests overlay-first,
        // traversal collects from inputRoot), and a background widget holding
        // focus would siphon keys and committed text from the open modal.
        if (widget != null && !isInSubtree(widget, keyboardRoot())) {
            return;
        }
        setFocus(widget, origin);
    }

    /**
     * Whether the keyboard can land on {@code widget}: focusable, and visible and enabled through
     * its whole ancestry with nothing inert on the way. The one test every path that moves the
     * focus applies -- this funnel, the focus an overlay gives back as it closes, the focus that
     * comes along into an overlay -- and the one Tab applies as it collects its stops. When these
     * paths read the widget's own flags alone, code and a closing dialog put the focus on a button
     * inside a disabled form, and Enter then ran its action.
     */
    private static boolean canTakeFocus(Widget<?> widget) {
        return widget.isFocusable() && widget.inReach();
    }

    /**
     * The one place focus moves, and the one place {@code FOCUS} is announced: on the widget
     * losing it and then on the widget gaining it, both carrying the origin of the path that
     * entered here. Each announcement follows that widget's own focus-lost or focus-gained hook,
     * so a watcher reads a widget that has already settled.
     */
    private void setFocus(Widget<?> widget, Change.Origin origin) {
        setFocus(widget, origin, false);
    }

    /**
     * {@link #setFocus(Widget, Change.Origin)}, where {@code carried} says {@code widget} arrives
     * with the focus it held in another scene, and so is told of no gain; see
     * {@link #pushOverlay(Widget, Scene)}.
     */
    private void setFocus(Widget<?> widget, Change.Origin origin, boolean carried) {
        if (focused == widget) {
            return;
        }
        Widget<?> old = focused;
        if (focused != null && focused != widget) {
            focusedBefore = new java.lang.ref.WeakReference<>(focused);
        }
        focused = widget;
        composing = false; // an open one was the old widget's; the platform's is cancelled below
        if (old != null) {
            if (window != null && old.acceptsTextInputInternal()) {
                // Focus is leaving a text widget: cancel any OS-side composition
                // it owned, so the preedit can't teleport into, and later
                // commit into, whatever gains focus next.
                WindowPeer.of(window).resetPreedit();
            }
            old.notifyFocus(false, origin);
            if (focused != widget) {
                return; // a focus-lost handler re-routed focus; it finished the job
            }
        }
        if (widget != null) {
            if (!carried) {
                widget.notifyFocus(true, origin);
            }
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

    /**
     * Whether the last composition the focused widget was handed is still open: an input method's
     * text not yet committed or cancelled, which only a focus that moves between scenes has to end
     * by hand, since a focus lost ends it in the widget's own focus-lost hook.
     */
    private boolean composing;

    // ------------------------------------------------------------- IME/preedit

    /** Last caret rect pushed to the window, so we only re-push on change. */
    private limn.graphics.Rect lastCaretRect;

    /**
     * Asks for composition iff a text-editing widget is focused, and for characters otherwise, and
     * seeds the caret.
     */
    private void updateImeState() {
        if (window == null) {
            return; // headless: no platform IME
        }
        boolean textInput = focused != null && focused.acceptsTextInputInternal();
        WindowPeer.of(window).setTextInput(
                textInput ? limn.backend.spi.TextInput.COMPOSITION : limn.backend.spi.TextInput.CHARACTERS);
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
        WindowPeer.of(window).setPreeditCaretRect(rect.x(), rect.y(), rect.width(), rect.height());
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
        collectFocusable(keyboardRoot(), order); // modal overlay confines traversal
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
        if (!widget.isVisible() || !widget.isEnabled() || widget.inert) {
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
    public void disposeLater(@Nullable GpuSurface surface) {
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
    public void disposeLater(@Nullable Runnable gpuCleanup) {
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

    /**
     * @return how many tickers are registered, those joining on the next frame included, and how
     *         many of the wall-time and first-frame sets hold; for tests that hold them bounded
     */
    int[] tickerCounts() {
        return new int[] {tickers.size() + pendingTickers.size(), realTimeTickers.size(), freshTickers.size()};
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
            Scene.this.scrolled(deltaX, deltaY, x, y, false);
        }
        @Override public void scrolled(float deltaX, float deltaY, float x, float y,
                                       boolean gestureBegins) {
            Scene.this.scrolled(deltaX, deltaY, x, y, gestureBegins);
        }
        @Override public void keyEvent(int key, int layoutCodePoint, boolean pressed, boolean repeat,
                                       int modifiers) {
            if (keyRoute != null) {
                routed.add(new RawKey(key, layoutCodePoint, pressed, repeat, modifiers));
                return;
            }
            Scene.this.keyEvent(key, layoutCodePoint, pressed, repeat, modifiers);
        }
        @Override public void charTyped(int codepoint) {
            if (keyRoute != null) {
                routed.add(new RawChar(codepoint));
                return;
            }
            Scene.this.charTyped(codepoint);
        }
        @Override public void preeditChanged(String text, int caret, int targetStart, int targetLength) {
            Scene.this.preeditChanged(text, caret, targetStart, targetLength);
        }
        @Override public void pointerEntered(boolean entered) { Scene.this.pointerEntered(entered); }
        @Override public void windowResized(float logicalWidth, float logicalHeight) {
            Scene.this.windowResized(logicalWidth, logicalHeight);
        }
        @Override public void filesDropped(java.util.List<java.nio.file.Path> paths, float x, float y) {
            Scene.this.filesDropped(paths, x, y);
        }
        @Override public limn.transfer.@Nullable DropAction dragOver(limn.transfer.Offer offer,
                java.util.Set<limn.transfer.DropAction> allowed, float x, float y) {
            return Scene.this.systemDragOver(offer, allowed, x, y);
        }
        @Override public void dragLeft() {
            Scene.this.systemDragLeft();
        }
        @Override public limn.transfer.@Nullable DropAction dropped(limn.transfer.Offer offer,
                java.util.Set<limn.transfer.DropAction> allowed, float x, float y) {
            return Scene.this.systemDropped(offer, allowed, x, y);
        }
        @Override public void windowFocusChanged(boolean focused) { Scene.this.windowFocusChanged(focused); }
        @Override public boolean canEditFocused(EditCommand command) { return Scene.this.canEdit(command); }
        @Override public boolean editFocused(EditCommand command) { return Scene.this.edit(command); }
        @Override public void windowFullscreenChanged(boolean fullscreen) {
            Scene.this.windowFullscreenChanged(fullscreen);
        }
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
            public long keyPresses(Scene scene) {
                return scene.keyPresses;
            }

            @Override
            public void ownAccessLetter(Widget<?> widget, limn.scene.internal.AccessLetterOwner owner) {
                widget.accessLetterOwner = owner;
            }

            @Override
            public void drawsAccessLetters(Widget<?> widget) {
                widget.drawsAccessLetters = true;
            }

            @Override
            public void pressFocus(Widget<?> widget, limn.scene.internal.PressFocus filter) {
                widget.pressFocus = filter;
            }

            @Override
            public void fieldControl(Widget<?> widget, boolean held, boolean required, boolean invalid) {
                widget.setAccessibleFieldBits(!held ? 0 : Widget.FIELD_CONTROL
                        | (required ? Widget.FIELD_REQUIRED : 0)
                        | (invalid ? Widget.FIELD_INVALID : 0));
            }

            @Override
            public void repaintPicture(Widget<?> widget) {
                Scene scene = widget.scene();
                if (scene != null) {
                    scene.damageWidgetPicture(widget);
                }
            }

            @Override
            public boolean enabledByItsOwnFlag(Widget<?> widget) {
                return widget.isEnabledByItsOwnFlag();
            }

            @Override
            public boolean inReach(Widget<?> widget) {
                return widget.inReach();
            }

            @Override
            public boolean namedByApplication(Widget<?> widget) {
                return widget.accessibleName() != null || widget.accessibleLabelledBy() != null
                        || widget.tooltipSource() != null;
            }

            @Override
            public @Nullable String applicationName(Widget<?> widget) {
                limn.i18n.I18nString named = widget.accessibleName();
                if (named == null) {
                    named = widget.tooltipSource();
                }
                String text = named == null ? null : named.get();
                return text == null || text.isEmpty() ? null : text;
            }

            @Override
            public void observeHoverWithin(Widget<?> widget,
                                           limn.scene.internal.SceneAccess.@Nullable HoverWithin observer) {
                if (widget.hoverWithin == null && observer != null) {
                    hoverWatchers++;
                } else if (widget.hoverWithin != null && observer == null) {
                    hoverWatchers--;
                }
                widget.hoverWithin = observer;
            }

            @Override
            public void setInert(Widget<?> widget, boolean inert) {
                if (widget.inert == inert) {
                    return;
                }
                widget.inert = inert;
                Scene scene = widget.scene();
                if (inert && scene != null) {
                    scene.onWidgetDetached(widget); // no hover, press, drag or focus left inside
                }
                // Nothing on the glass changes, so no paint asks for the walk that withdraws the
                // subtree's verbs, or gives them back: it is asked for here.
                widget.invalidateAccessible();
            }

            @Override
            public void focusByUser(Widget<?> widget) {
                Scene scene = widget.scene();
                if (scene != null) {
                    scene.requestFocus(widget, Change.Origin.USER);
                }
            }

            @Override
            public boolean accessLettersShown(Scene scene) {
                // While Alt is held, or always where the user asked the system for underlines. A
                // popup grafted into another scene's tree (a menu in a window of its own) never has
                // the keyboard: Alt is held in the window that opened it, and that one's answer is
                // its own.
                Scene holder = scene.graftedInto != null ? scene.graftedInto : scene;
                return holder.accessLettersShown || SystemPreferences.current().underlineAccessKeys();
            }

            @Override
            public WindowInput inputOf(Scene scene) {
                return scene.input;
            }

            @Override
            public Scene sceneOf(WindowInput input) {
                return input instanceof Input own ? own.scene() : null;
            }

            @Override
            public void pushMovedOverlay(Scene scene, Widget<?> overlay, Scene movedFrom) {
                scene.pushOverlay(overlay, movedFrom);
            }

            @Override
            public void releaseOverlayKeyboard(Scene scene, Widget<?> overlay) {
                scene.releaseOverlayKeyboard(overlay);
            }

            @Override
            public Subscription observeWindowFocus(Scene scene, Runnable observer) {
                return scene.observeWindowFocus(observer);
            }

            @Override
            public Subscription observeWindowBound(Scene scene, Runnable observer) {
                return scene.observeWindowBound(observer);
            }

            @Override
            public Subscription observeAnchorMoved(Scene scene, Widget<?> anchor, Runnable placeAgain) {
                return scene.observeAnchorMoved(anchor, placeAgain);
            }

            @Override
            public @Nullable Widget<?> focusedBefore(Scene scene) {
                return scene.focusedBefore();
            }

            @Override
            public Subscription routeKeyboard(Scene scene, limn.scene.internal.SceneAccess.KeyRoute route) {
                return scene.routeKeyboard(route);
            }

            @Override
            public void pushPopupLeavingKeyboard(Scene scene, Widget<?> popup) {
                scene.pushPopupLeavingKeyboard(popup);
            }

            @Override
            public Subscription showCursorOf(Scene scene, Scene popup) {
                return scene.showCursorOf(popup);
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

    private void scrolled(float deltaX, float deltaY, float x, float y, boolean began) {
        Raw last = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        // Never merged into the gesture before it: the first event of a gesture is where the
        // scroller it latches to is chosen afresh.
        if (last instanceof RawScroll s && !began) {
            queue.set(queue.size() - 1, new RawScroll(s.dx + deltaX, s.dy + deltaY, x, y, s.began));
        } else {
            queue.add(new RawScroll(deltaX, deltaY, x, y, began));
        }
    }

    private void keyEvent(int key, int layoutCodePoint, boolean pressed, boolean repeat, int modifiers) {
        queue.add(new RawKey(key, layoutCodePoint, pressed, repeat, modifiers)); // never dropped
    }

    private void charTyped(int codepoint) {
        queue.add(new RawChar(codepoint));
    }

    private void preeditChanged(String text, int caret, int targetStart, int targetLength) {
        queue.add(new RawPreedit(text, caret, targetStart, targetLength));
    }

    private void pointerEntered(boolean entered) {
        queue.add(new RawPointer(entered));
    }

    private void filesDropped(java.util.List<java.nio.file.Path> paths, float x, float y) {
        if (!paths.isEmpty()) {
            queue.add(new RawDrop(paths, x, y)); // never dropped, like clicks/keys
        }
    }

    private void windowFocusChanged(boolean focused) {
        queue.add(new RawFocus(focused));
    }

    private void windowFullscreenChanged(boolean fullscreen) {
        queue.add(new RawFullscreen(fullscreen)); // never dropped: each change closes what it must
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
        deliverRouted();
    }

    /**
     * Hands the batch's routed keys to the route, each to the route in place when it is handed
     * over: a key can close the menu that took it, or open the neighbour a menu bar walks to, and
     * the rest follow the keyboard there, or come back to this scene's focus.
     */
    private void deliverRouted() {
        if (routed.isEmpty()) {
            return;
        }
        Raw[] batch = routed.toArray(new Raw[0]);
        routed.clear();
        boolean ownQueued = false;
        for (Raw raw : batch) {
            limn.scene.internal.SceneAccess.KeyRoute route = keyRoute;
            if (route == null) {
                queue.add(raw);
                ownQueued = true;
            } else if (raw instanceof RawKey k) {
                if ((k.key() == Keys.LEFT_ALT || k.key() == Keys.RIGHT_ALT)
                        && limn.scene.internal.AccessLetterSystem.present()) {
                    // Alt is still this window's to show: a menu opened by a title's letter takes
                    // the release, and the window's letters stayed drawn until the next Alt.
                    showAccessLetters(k.pressed());
                }
                route.key(k.key(), k.layoutCodePoint(), k.pressed(), k.repeat(), k.mods());
            } else if (raw instanceof RawChar c) {
                route.character(c.codepoint());
            }
        }
        if (ownQueued) {
            processInput();
        }
    }

    private Subscription routeKeyboard(limn.scene.internal.SceneAccess.KeyRoute route) {
        Ui.checkUiThread();
        keyRoute = route;
        invalidateAccessible();
        return () -> {
            if (keyRoute == route) {
                keyRoute = null;
                invalidateAccessible();
            }
        };
    }

    /** @return the grafted popup this window's keys drive, whose cursor is this tree's; or {@code null} */
    @Nullable Scene keyboardPopup() {
        limn.scene.internal.SceneAccess.KeyRoute route = keyRoute;
        Scene popup = route == null ? null : route.popup();
        if (popup == null) {
            popup = cursorPopup;
        }
        return popup != null && popup.graftedInto == this ? popup : null;
    }

    private Subscription showCursorOf(Scene popup) {
        Ui.checkUiThread();
        cursorPopup = popup;
        invalidateAccessible();
        return () -> {
            if (cursorPopup == popup) {
                cursorPopup = null;
                invalidateAccessible();
            }
        };
    }

    /**
     * Pushes a popup overlay that leaves the keyboard where it is, in the layer beneath: keys,
     * characters, Tab and the focus stay there, and the pointer reaches the popup as it reaches any
     * overlay. The overlay's own cursor is where this tree says the keyboard is.
     */
    private void pushPopupLeavingKeyboard(Widget<?> popup) {
        Ui.checkUiThread();
        Objects.requireNonNull(popup, "popup");
        stackOverlay(popup);
        popupOverlays.add(popup);
        keyboardBelow.add(popup);
        requestRender();
    }

    /**
     * An overlay that has begun to close gives the keyboard back now, to what it took it from, and
     * stays on screen for the rest of its fade as a layer that leaves the keyboard beneath it.
     *
     * <p>The focus used to come back when the fade ended and the overlay was removed. For that
     * sixth of a second the tree said the keyboard was on a layer about to be destroyed, and a
     * screen reader standing there lost its element and settled on a neighbour before it followed
     * the focus: heard on macOS, where a calendar closed with Enter was read as the next group in
     * the window and then as the field. A key typed during the fade goes to the field as well,
     * where it went to a calendar that was no longer there to answer it.
     *
     * <p>Nothing happens for an overlay that is not the top one, that already leaves the keyboard
     * beneath it, or that is not in this scene. {@link #removeOverlay} then gives nothing back a
     * second time.
     */
    private void releaseOverlayKeyboard(Widget<?> overlay) {
        Ui.checkUiThread();
        int index = overlays.indexOf(overlay);
        if (index < 0 || index != overlays.size() - 1 || keyboardBelow.contains(overlay)) {
            return;
        }
        keyboardBelow.add(overlay);
        closingOverlays.add(overlay);
        Widget<?> restore = overlayFocusReturn.get(index);
        if (restore != null && restore != overlay && isInSubtree(restore, keyboardRoot())
                && canTakeFocus(restore)) {
            setFocus(restore, Change.Origin.ADJUSTMENT);
        } else {
            setFocus(null, Change.Origin.ADJUSTMENT);
        }
        invalidateAccessible();
        requestRender();
    }

    /** @return whether {@code overlay} leaves the keyboard with the layer beneath it */
    boolean leavesKeyboardBelow(Widget<?> overlay) {
        return keyboardBelow.contains(overlay);
    }

    /** @return the layer the keyboard is confined to: the top overlay that takes it, or the root */
    private Widget<?> keyboardRoot() {
        for (int i = overlays.size() - 1; i >= 0; i--) {
            Widget<?> overlay = overlays.get(i);
            if (!keyboardBelow.contains(overlay)) {
                return overlay;
            }
        }
        return root;
    }

    /**
     * @return the layer the keyboard is confined to, as {@link #accessibleInputLayer} answers for the
     *         pointer: the same layer unless a popup leaves the keyboard beneath it, and
     *         {@code null} while a native modal blocks the window
     */
    @Nullable Widget<?> accessibleKeyboardLayer() {
        if (window != null && window.isModalBlocked()) {
            return null;
        }
        return keyboardRoot();
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
                    if (!wheelStreamEnded(scroll.began)) {
                        dispatchWheel(hitAt(scroll.x, scroll.y), new MouseEvent(
                                MouseEvent.Type.WHEEL, scroll.x, scroll.y, -1, scroll.dx, scroll.dy,
                                modifiers));
                        // Scrolling moves content under a stationary cursor: re-hover.
                        if (pressed == null) {
                            updateHover(hitAt(scroll.x, scroll.y));
                        }
                        invalidate = true;
                    }
                } else if (raw instanceof RawKey key) {
                    handleKey(key);
                    invalidate = true;
                } else if (raw instanceof RawChar ch) {
                    // Like keys: with nothing focused, chars go to the topmost
                    // modal layer, never to the blocked content underneath.
                    CharEvent typed = new CharEvent(ch.codepoint);
                    dispatchBubbling(focused != null ? focused : keyboardRoot(), typed);
                    // A letter that finds an option moves a list too; one typed by a held key's
                    // auto-repeat is a repeat as well, and a repeat does not end a stream.
                    if (typed.isConsumed() && !lastPressRepeated) {
                        endWheelStreams();
                    }
                    invalidate = true;
                } else if (raw instanceof RawPreedit preedit) {
                    // Composition only makes sense for a focused widget that
                    // accepts text input (the documented onPreedit contract);
                    // secure fields refuse it so a composed secret is never
                    // echoed, and with nothing focused there is nowhere to
                    // show it, so drop it.
                    if (focused != null && focused.acceptsTextInputInternal()) {
                        Widget<?> target = focused;
                        PreeditEvent event = new PreeditEvent(preedit.text, preedit.caret,
                                preedit.targetStart, preedit.targetLength);
                        dispatchBubbling(target, event);
                        if (focused == target) { // a handler that moved the focus ended it
                            composing = !event.isEmpty();
                        }
                    }
                    invalidate = true;
                } else if (raw instanceof RawDrop drop) {
                    // Where the backend says it landed: the platform's point, or the pointer's.
                    dropFromOutside(limn.transfer.Transfer.of(limn.transfer.DataFormat.FILES, drop.paths)
                            .asOffer(), java.util.Set.of(limn.transfer.DropAction.COPY), drop.x, drop.y);
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
                        showAccessLetters(false); // the Alt release goes to the window it moved to
                    }
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
                    } else {
                        runObservers(windowFocusObservers);
                    }
                } else if (raw instanceof RawFullscreen change) {
                    // Nothing of its own to repaint: the resize that comes with it relays out, and
                    // a surface an observer closes or moves invalidates what it touched.
                    notifyFullscreenObservers(change.fullscreen);
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
        if (dragSession != null) {
            if (!dragSession.keyboard && !dragSession.system) {
                dragMoved(x, y);
            }
            return true;
        }
        if (pressed != null && !dragAsked && pressedButton == Keys.MOUSE_LEFT
                && Math.hypot(x - pressX, y - pressY) >= DRAG_THRESHOLD) {
            dragAsked = true;
            if (startDrag(pressed, new DragStartEvent(pressX, pressY, modifiers, false))) {
                dragMoved(x, y);
                return true;
            }
        }
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
        if (dragSession != null) {
            // The release that ends a pointer drag is the drop; any other button, or a click
            // during a keyboard drag, calls it off.
            if (dragSession.system) {
                return; // the system has the pointer, and says when the drag is over
            }
            if (!button.pressed && button.button == Keys.MOUSE_LEFT && !dragSession.keyboard) {
                dragMoved(button.x, button.y);
                finishDrag(true);
            } else if (button.pressed) {
                finishDrag(false);
            }
            return;
        }
        if (button.pressed) {
            hideTooltip(); // any click dismisses a showing/pending tooltip
            Widget<?> hit = hitAt(button.x, button.y);
            // Capture BEFORE dispatching: a PRESS/focus handler may detach the
            // widget, and onWidgetDetached must find (and clear) this state.
            pressed = hit;
            pressedButton = button.button;
            pressX = button.x;
            pressY = button.y;
            dragAsked = false;
            // Click-to-focus: nearest focusable ancestor of the press target.
            Widget<?> focusTarget = hit;
            while (focusTarget != null && !focusTarget.isFocusable()) {
                focusTarget = focusTarget.parent();
            }
            if (focusTarget != null && (focusTarget.pressFocus == null
                    || focusTarget.pressFocus.takesFocusAt(button.x, button.y))) {
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
        if (key.pressed) {
            lastPressRepeated = key.repeat;
        }
        if (key.key >= 0) { // GLFW reports -1 for a key it cannot name; there is nothing to pair
            if (key.pressed) {
                // A repeat re-sets a bit that is already set: held keys stay held, and a REPEAT
                // that arrives after a lost PRESS still leaves the scene able to release it.
                keysDown.set(key.key);
            } else {
                keysDown.clear(key.key);
            }
        }
        // Before the drag's branch: Alt let go during a drag (Alt is the link modifier on Windows)
        // must still take the letters away, or they stay drawn until the next Alt.
        if ((key.key == Keys.LEFT_ALT || key.key == Keys.RIGHT_ALT)
                && limn.scene.internal.AccessLetterSystem.present()) {
            showAccessLetters(key.pressed);
        }
        if (dragSession != null) {
            dragKey(key); // a drag in flight has the keyboard: Escape, and the keys that steer it
            return;
        }
        KeyEvent event = new KeyEvent(key.key, key.layoutCodePoint, key.pressed, key.repeat, key.mods);
        if (key.pressed && !key.repeat && !isModifierKey(key.key)) {
            keyPresses++;
        }
        // Keys go to the focused widget, else the topmost modal layer (so a
        // dialog can handle ESC even with nothing focused).
        Widget<?> keyTarget = focused != null ? focused : keyboardRoot();
        dispatchBubbling(keyTarget, event);
        // An access letter the focused widget did not want: a control's in the window, or the
        // topmost modal's, before any menu bar's (the shortcut handlers below).
        if (!event.isConsumed() && key.pressed && !key.repeat && key.mods == Keys.MOD_ALT
                && limn.scene.internal.AccessLetterSystem.present()
                && accessLetter(event.shortcutKey(), key.layoutCodePoint)) {
            event.consume();
        }
        if (!event.isConsumed() && key.pressed && !key.repeat && key.key == dragKeyCode
                && (key.mods & DRAG_KEY_MASK) == dragKeyModifiers && focused != null) {
            Vec2 middle = focused.localToScene(focused.width() / 2, focused.height() / 2);
            if (startDrag(focused, new DragStartEvent(middle.x(), middle.y(), key.mods, true))) {
                event.consume();
            }
        }
        // Between the focused widget and the Tab fallback: what nobody focused wanted may still
        // be a shortcut, and what nobody wants at all must still traverse.
        offerToShortcutHandlers(event);
        boolean acted = event.isConsumed();
        if (!acted && key.pressed && key.key == Keys.TAB) {
            focusTraverse((key.mods & Keys.MOD_SHIFT) != 0, Change.Origin.USER);
            acted = true; // a traversal reveals the widget it lands on
        }
        if (acted && key.pressed && !key.repeat) {
            endWheelStreams();
        }
    }

    /**
     * The longest pause between two wheel events of one stream. A trackpad's or a Magic Mouse's
     * inertia on macOS arrived every 8 to 17 ms and slowed to 67 ms at its tail, measured in a
     * combo box's list; a precision touchpad's on Windows is delivered as wheel messages too.
     */
    private static final long WHEEL_STREAM_GAP_NANOS = TimeUnit.MILLISECONDS.toNanos(150);

    /** Whether Alt is held, which is when the access letters are drawn. */
    private boolean accessLettersShown;
    /** Keys other than a modifier pressed in this scene, whoever took them; see SceneAccess. */
    private long keyPresses;

    private static boolean isModifierKey(int key) {
        return key == Keys.LEFT_ALT || key == Keys.RIGHT_ALT || key == Keys.LEFT_SHIFT
                || key == Keys.RIGHT_SHIFT || key == Keys.LEFT_CONTROL || key == Keys.RIGHT_CONTROL
                || key == Keys.LEFT_SUPER || key == Keys.RIGHT_SUPER;
    }

    private void showAccessLetters(boolean shown) {
        if (accessLettersShown == shown) {
            return;
        }
        accessLettersShown = shown;
        if (SystemPreferences.current().underlineAccessKeys()) {
            return; // the system has them drawn at all times: nothing on screen changes
        }
        repaintAccessLetters();
    }

    /**
     * Repaints every widget that draws an access letter, and nothing else: under partial rendering
     * a frame costs what changed, and Alt pressed over a large table or chart (an Alt+Tab away,
     * whose focus loss hides them again) repainted the whole window twice for a few underlines.
     * Found by a walk rather than kept in a list in step with the tree, since Alt is rare.
     */
    private void repaintAccessLetters() {
        invalidateAccessLetters(root);
        for (Widget<?> overlay : overlays) {
            invalidateAccessLetters(overlay);
        }
        // And the menus this window has open in windows of their own, which draw by its Alt.
        for (int i = 0; i < graftedPopups.size(); i++) {
            invalidateAccessLetters(graftedPopups.get(i).root);
        }
    }

    private static void invalidateAccessLetters(Widget<?> at) {
        if (!at.isVisible()) {
            return; // draws nothing, nor does anything inside it
        }
        if (at.accessLetterOwner != null || at.drawsAccessLetters) {
            at.invalidate();
        }
        for (Widget<?> child : at.children()) {
            invalidateAccessLetters(child);
        }
    }

    /**
     * Acts on the access letter of a key press: the one control of the window, or of the topmost
     * modal, whose letter it is; or, where several share it, the keyboard moves to the next of
     * them and none acts, as in a Windows dialog.
     *
     * @return whether a control took it
     */
    private boolean accessLetter(int key, int codePoint) {
        List<limn.scene.internal.AccessLetterOwner> owners = new ArrayList<>();
        // The layer the keyboard is confined to, not the topmost: a popup that leaves the keyboard
        // beneath it (an editable combo box's list) holds no letters and must not hide the window's.
        collectAccessLetters(keyboardRoot(), key, codePoint, owners);
        if (owners.isEmpty()) {
            return false;
        }
        if (owners.size() == 1) {
            owners.get(0).accessLetterPressed();
            return true;
        }
        reportSharedLetter(owners);
        int at = -1;
        for (int i = 0; i < owners.size(); i++) {
            if (owners.get(i).accessLetterFocus() == focused) {
                at = i;
            }
        }
        // The next that takes the keyboard: one that cannot (a label whose control has gone, a
        // radio its group will not let have it) is stepped past, or the turn would stop there.
        for (int step = 1; step <= owners.size(); step++) {
            limn.scene.internal.AccessLetterOwner owner = owners.get((at + step) % owners.size());
            Widget<?> next = owner.accessLetterFocus();
            if (next == null) {
                continue;
            }
            owner.accessLetterTurn();
            requestFocus(next, Change.Origin.USER); // a key the user pressed, not code
            if (focused == next) {
                break;
            }
        }
        return true;
    }

    /** The letters shared by several controls already logged, each with the set that shares it. */
    private java.util.Set<String> sharedLettersReported;

    /**
     * Logs, once for each set of controls, that several share one access letter: a
     * translator's collision, which a press only turns into a turn of the keyboard, is found in
     * development rather than by a user.
     */
    private void reportSharedLetter(List<limn.scene.internal.AccessLetterOwner> owners) {
        char letter = owners.get(0).accessLetter();
        StringBuilder which = new StringBuilder().append(letter);
        StringBuilder names = new StringBuilder();
        for (limn.scene.internal.AccessLetterOwner owner : owners) {
            Widget<?> widget = owner.accessLetterFocus();
            which.append(':').append(System.identityHashCode(owner));
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(widget == null ? "?" : widget.getClass().getSimpleName());
        }
        if (sharedLettersReported == null) {
            sharedLettersReported = new java.util.HashSet<>();
        }
        if (sharedLettersReported.add(which.toString())) {
            LOG.log(Level.WARNING, "access letter {0} is marked by {1} controls in one window ({2}): "
                    + "each press moves the keyboard among them and none acts; give each its own "
                    + "letter in this language", letter, owners.size(), names);
        }
    }

    private static void collectAccessLetters(Widget<?> at, int key, int codePoint,
            List<limn.scene.internal.AccessLetterOwner> into) {
        if (!at.isVisible() || !at.isEnabled() || at.inert) {
            return; // hidden, disabled or inert: neither it nor anything inside it answers
        }
        limn.scene.internal.AccessLetterOwner owner = at.accessLetterOwner;
        if (owner != null
                && limn.scene.internal.AccessLetterOwner.matches(owner.accessLetter(), key, codePoint)) {
            into.add(owner);
        }
        for (Widget<?> child : at.children()) {
            collectAccessLetters(child, key, codePoint, into);
        }
    }

    private boolean wheelSeen;
    private long lastWheelNanos;
    /**
     * The widget that took the wheel stream in flight, which the rest of the stream stays with:
     * see {@link #dispatchWheel}. Null at the start of a stream and when the widget leaves.
     */
    private @Nullable Widget<?> wheelLatch;
    /** Whether a key ended the wheel stream still arriving; see {@link #endWheelStreams}. */
    private boolean wheelStreamCut;
    /**
     * Whether the last key press was the platform's auto-repeat of a held key. The characters a
     * held key types carry no flag of their own, so they are judged by the press they follow.
     */
    private boolean lastPressRepeated;

    /**
     * Ends the wheel stream in flight in this window and in the popups it publishes: a key a
     * widget acted on wins over the wheel events still arriving from a gesture that ended before
     * it. The inertia a trackpad adds after the fingers lift kept scrolling a combo box's list,
     * so the row the arrow had just scrolled into view was carried off again by some twenty rows.
     * The key goes to the owner window and the wheel to the list's own, which is why the popups
     * a window publishes are ended with it. What the stream still sends is dropped until it
     * pauses; a new gesture after a pause scrolls as ever.
     *
     * <p>Only a fresh press ends a stream. A held key repeats every 30 ms or so, and when each
     * repeat ended the stream, a wheel turned while the key was held lost all but its first
     * event; the stream in flight when the key went down is still ended by that first press.
     */
    private void endWheelStreams() {
        Scene owner = graftedInto != null ? graftedInto : this;
        owner.wheelStreamCut = true;
        for (int i = 0; i < owner.graftedPopups.size(); i++) {
            owner.graftedPopups.get(i).wheelStreamCut = true;
        }
    }

    /**
     * Whether this wheel event belongs to a stream a key has ended; a pause starts a new one, and
     * so does an event the platform says begins a gesture, which a pause cannot tell while the
     * inertia of the gesture before keeps the events coming.
     */
    private boolean wheelStreamEnded(boolean began) {
        long now = clock.getAsLong();
        boolean continuing = !began && wheelSeen && now - lastWheelNanos < WHEEL_STREAM_GAP_NANOS;
        wheelSeen = true;
        lastWheelNanos = now;
        if (!continuing) {
            wheelStreamCut = false;
            wheelLatch = null; // a new gesture chooses its scroller afresh
        }
        return wheelStreamCut;
    }

    /**
     * Offers a wheel event from the widget under the pointer outward, as any event bubbles, with
     * one rule for a gesture: the widget that took a stream keeps it. A scroller that reaches an
     * end in the middle of a flick, the inertia of a trackpad included, holds the rest of that
     * flick instead of passing it to the scroller around it, and a new gesture after a pause, at
     * the same end, passes on: what browsers and macOS call latching. Without it a list scrolled
     * to its top with a flick carried the page under it a moment later, the user having asked
     * for nothing of the kind. A stream that starts with the inner scroller already at its end
     * passes on at once, as nothing took it.
     */
    private void dispatchWheel(Widget<?> hit, MouseEvent event) {
        Widget<?> latch = wheelLatch;
        if (latch != null && latch.scene() == this && latch.isShowing()) {
            // The gesture goes to the scroller that took it, and to nothing else, whatever is under
            // the pointer now: a page scrolled back up carries a list under a pointer that does
            // not move, and the rest of the gesture went to the list, which still had room.
            timedDispatch(latch, event);
            event.consume(); // held where it was taken, at its end or not
            return;
        }
        for (Widget<?> w = hit; w != null; w = w.parent()) {
            timedDispatch(w, event);
            if (event.isConsumed()) {
                wheelLatch = w;
                return;
            }
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
     * <p>One system reverses the first two for a menu bar: where the bar is shown in the system's
     * own, on macOS, the system runs a bar's accelerator before the key is sent to the window at
     * all. See {@link limn.components.MenuBar}.
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
    /**
     * Whether an editing command can be done now by the widget that has the keyboard here, or by
     * one of its ancestors. What a menu's item of an editing role is enabled by, and what a tool
     * bar's button for the command may be.
     *
     * @return {@code false} with nothing focused
     */
    public boolean canEdit(EditCommand command) {
        Ui.checkUiThread();
        return editorOf(command, focused) != null;
    }

    /**
     * Whether an editing command can be done now by {@code from} or by one of its ancestors: the
     * question {@link #canEdit(EditCommand)} asks of the focused widget, asked of a widget the
     * caller remembered, as a menu does of the one that had the keyboard before it opened.
     *
     * @param from a widget of this scene, or {@code null}, which can do nothing
     */
    public boolean canEdit(EditCommand command, @Nullable Widget<?> from) {
        Ui.checkUiThread();
        return editorOf(command, from) != null;
    }

    /**
     * Does an editing command in the widget that has the keyboard here, or in the first of its
     * ancestors that can, as the user's own act: what the command's chord does there.
     *
     * @return whether a widget did it
     */
    public boolean edit(EditCommand command) {
        Ui.checkUiThread();
        return edit(command, focused);
    }

    /**
     * Does an editing command in {@code from}, or in the first of its ancestors that can.
     *
     * @param from a widget of this scene, or {@code null}, which does nothing
     * @return whether a widget did it
     */
    public boolean edit(EditCommand command, @Nullable Widget<?> from) {
        Ui.checkUiThread();
        Widget<?> editor = editorOf(command, from);
        return editor != null && editor.onEdit(command);
    }

    private @Nullable Widget<?> editorOf(EditCommand command, @Nullable Widget<?> from) {
        java.util.Objects.requireNonNull(command, "command");
        if (from != null && from.scene() != this) {
            return null;
        }
        for (Widget<?> widget = from; widget != null; widget = widget.parent()) {
            if (widget.isEnabled() && widget.onCanEdit(command)) {
                return widget;
            }
        }
        return null;
    }

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

    private @Nullable Widget<?> hitAt(float sceneX, float sceneY) {
        // A modal overlay owns all input; hit-testing never reaches the content,
        // unless the overlay yields this point (e.g. a menu bar's titles behind a
        // fullscreen in-scene menu), which then hit-tests the content below. An inert
        // subtree is passed by in Widget.hitTest itself, which every widget's answer goes
        // through now that it is the one entry.
        Widget<?> top = topOverlay();
        Widget<?> asked = top != null && !top.overlayPassesPointer(sceneX, sceneY) ? top : root;
        float[] local = hitScratch;
        asked.sceneToLocal(sceneX, sceneY, local);
        return asked.hitTest(local[0], local[1]);
    }

    /** The point {@link #hitAt} asks about, in the asked root's space; one, since input runs on the UI thread. */
    private final float[] hitScratch = new float[2];

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
        tellHoverWithin(hovered);
        applyCursor();
        updateTooltip();
        return true;
    }

    /**
     * How many widgets, in any scene, ask to be told of the hover entering and leaving their
     * subtree: while none does, a change of the hovered widget costs a comparison and no walk.
     */
    private static int hoverWatchers;

    /**
     * The observing widgets told the hover is within them and not yet told it left: the
     * ancestors of the hovered widget that observe, at most a few. Kept rather than worked out
     * again from the widget left, whose ancestry a removal has already cut by the time the scene
     * hears of it.
     */
    private final List<Widget<?>> hoverHeld = new ArrayList<>(2);

    /**
     * Tells each observing widget whether the hover, now on {@code entered}, has left its subtree
     * or come into it: those it has left first, then those it entered, the ones it is still in
     * told nothing.
     */
    private void tellHoverWithin(@Nullable Widget<?> entered) {
        for (int i = hoverHeld.size() - 1; i >= 0; i--) {
            Widget<?> w = hoverHeld.get(i);
            if (!isInSubtree(entered, w)) {
                hoverHeld.remove(i);
                if (w.hoverWithin != null) {
                    w.hoverWithin.hoverChanged(false);
                }
            }
        }
        if (hoverWatchers == 0) {
            return;
        }
        for (Widget<?> w = entered; w != null; w = w.parent()) {
            if (w.hoverWithin != null && !hoverHeld.contains(w)) {
                hoverHeld.add(w);
                w.hoverWithin.hoverChanged(true);
            }
        }
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
        if (dragSession != null && !dragSession.keyboard && !dragSession.system) {
            window.setImageCursor(null);
            window.setCursor(dragSession.target != null ? Cursor.DEFAULT : Cursor.NOT_ALLOWED);
            return;
        }
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

    // ----------------------------------------------------------------- drags

    /** How far a press travels before it asks whether a drag begins. */
    private static final float DRAG_THRESHOLD = 4;
    /** How near an edge of the widget that accepted the pointer rests for it to scroll. */
    private static final float AUTO_SCROLL_EDGE = 24;
    private static final double AUTO_SCROLL_SECONDS = 0.06;
    private static final float DRAG_IMAGE_OPACITY = 0.75f;
    private static final int DRAG_KEY_MASK =
            Keys.MOD_SHIFT | Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SUPER;
    private static final boolean MAC = limn.backend.Platform.current().isMacOs();

    private float pressX;
    private float pressY;
    private boolean dragAsked;
    private int dragKeyCode = Keys.D;
    private int dragKeyModifiers = Keys.MOD_SHIFT | (MAC ? Keys.MOD_SUPER : Keys.MOD_CONTROL);
    private @Nullable DragSession dragSession;
    private @Nullable Rect paintedDragImage;
    /** How many announcements this scene has been asked for: what tells whether a call made one. */
    private long announced;
    /**
     * What a keyboard drag says as it begins, held for the place that follows at once: said with
     * it as one announcement. An announcement that interrupts cuts off the one before it, and said
     * apart the place cut off what had been picked up.
     */
    private @Nullable String dragBeginning;

    /** A drag the scene drives: what is carried, who began it, and who would take it now. */
    private static final class DragSession {
        final limn.transfer.Drag drag;
        final Widget<?> source;
        final limn.transfer.Offer offer;
        final boolean keyboard;
        @Nullable Widget<?> target;
        limn.transfer.@Nullable DropAction action;
        float x;
        float y;
        // The part of the source painted under the pointer, in the source's coordinates, and
        // where in it the pointer holds it; a width of zero is no preview.
        float partX;
        float partY;
        float partW;
        float partH;
        float grabX;
        float grabY;
        /** The next frame is being captured for the picture the system will show: ours is held back. */
        boolean awaitingSystem;
        /** Handed to the system: the pointer is its own until it says the drag is over. */
        boolean system;
        boolean droppedHere;
        limn.transfer.@Nullable DropAction doneHere;

        DragSession(limn.transfer.Drag drag, Widget<?> source, boolean keyboard, float x, float y) {
            this.drag = drag;
            this.source = source;
            this.offer = drag.content().asOffer();
            this.keyboard = keyboard;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Sets the key that begins a drag from the keyboard on the focused widget. The default is
     * Ctrl+Shift+D, and Cmd+Shift+D on macOS.
     *
     * @param key       a {@code Keys} constant
     * @param modifiers the {@code Keys.MOD_*} bits held with it
     */
    public void setDragKey(int key, int modifiers) {
        Ui.checkUiThread();
        dragKeyCode = key;
        dragKeyModifiers = modifiers & DRAG_KEY_MASK;
    }

    /** @return whether a drag is in flight in this scene */
    public boolean isDragging() {
        return dragSession != null;
    }

    /**
     * Asks {@code from} and its ancestors whether a drag begins, and begins the first answered.
     *
     * @return whether a drag began
     */
    private boolean startDrag(Widget<?> from, DragStartEvent event) {
        for (Widget<?> w = from; w != null; w = w.parent()) {
            if (!w.isEnabled()) {
                continue;
            }
            limn.transfer.Drag drag = w.dispatchDragStart(event);
            if (drag == null) {
                continue;
            }
            // The press is over for whoever had it: a drag is not also a click.
            if (pressed != null) {
                Widget<?> oldPressed = pressed;
                int oldButton = pressedButton;
                pressed = null;
                pressedButton = -1;
                timedDispatch(oldPressed,
                        new MouseEvent(MouseEvent.Type.RELEASE, mouseX, mouseY, oldButton, 0, 0, modifiers));
            }
            hideTooltip();
            updateHover(null);
            DragSession session = new DragSession(drag, w, event.isKeyboard(), event.x(), event.y());
            dragSession = session;
            choosePreview(session);
            if (event.isKeyboard()) {
                // Named by the drag, or else by what the source itself is called.
                limn.i18n.I18nString called = w.accessibleName();
                String what = !drag.description().isEmpty() ? drag.description()
                        : called != null ? called.get() : "";
                dragBeginning = what.isEmpty() ? DragStrings.STARTED_UNNAMED.get() : DragStrings.STARTED.format(what);
                // Where it is, first: moving something within its own list is the common case.
                keyboardTarget(w, 0, 0, false);
                String beginning = dragBeginning;
                dragBeginning = null;
                if (beginning != null) {
                    // Its own widget takes no drop: what was picked up is all there is to say yet.
                    announce(beginning, limn.accessibility.Accessible.Politeness.ASSERTIVE);
                }
            } else {
                addRealTimeTicker(new AutoScroll(session));
                if (!drag.isWithinWindow() && window != null && WindowPeer.of(window).supportsDrag()) {
                    handToSystem(session);
                }
            }
            applyCursor();
            scheduleFrame();
            return true;
        }
        return false;
    }

    private static limn.transfer.DropAction proposedAction(java.util.Set<limn.transfer.DropAction> allowed,
            int mods) {
        limn.transfer.DropAction asked = null;
        boolean shift = (mods & Keys.MOD_SHIFT) != 0;
        boolean control = (mods & Keys.MOD_CONTROL) != 0;
        boolean alt = (mods & Keys.MOD_ALT) != 0;
        boolean command = (mods & Keys.MOD_SUPER) != 0;
        if (MAC) {
            // Option copies, Command moves, Control (or Option with Command) links.
            if (control || (alt && command)) {
                asked = limn.transfer.DropAction.LINK;
            } else if (alt) {
                asked = limn.transfer.DropAction.COPY;
            } else if (command) {
                asked = limn.transfer.DropAction.MOVE;
            }
        } else if (control && shift) {
            asked = limn.transfer.DropAction.LINK;
        } else if (control) {
            asked = limn.transfer.DropAction.COPY;
        } else if (shift) {
            asked = limn.transfer.DropAction.MOVE;
        }
        return asked != null && allowed.contains(asked) ? asked : allowed.iterator().next();
    }

    private DragEvent dragEvent(DragSession session, int stepX, int stepY) {
        java.util.Set<limn.transfer.DropAction> allowed = session.drag.actions();
        return new DragEvent(session.x, session.y, modifiers, session.keyboard, stepX, stepY,
                session.offer, allowed, proposedAction(allowed, modifiers), session.source);
    }

    /** The pointer moved in a drag: who accepts it there, and who no longer does. */
    private void dragMoved(float x, float y) {
        DragSession session = dragSession;
        if (session == null) {
            return;
        }
        damageDragImage();
        session.x = x;
        session.y = y;
        DragEvent event = dragEvent(session, 0, 0);
        Widget<?> acceptor = null;
        for (Widget<?> w = hitAt(x, y); w != null && acceptor == null; w = w.parent()) {
            if (w.isEnabled()) {
                w.dispatchDragOver(event);
                if (event.isAccepted()) {
                    acceptor = w;
                }
            }
        }
        retarget(session, acceptor, event.acceptedAction());
        damageDragImage();
        scheduleFrame();
    }

    private void retarget(DragSession session, @Nullable Widget<?> acceptor,
            limn.transfer.@Nullable DropAction action) {
        Widget<?> before = session.target;
        session.target = acceptor;
        session.action = action;
        if (before != null && before != acceptor) {
            before.dispatchDragExit(dragEvent(session, 0, 0));
        }
        if (before != acceptor) {
            applyCursor();
        }
    }

    /** Ends the drag in flight: a drop on the widget that accepts it, or nothing at all. */
    private void finishDrag(boolean drop) {
        DragSession session = dragSession;
        if (session == null || session.system) {
            return; // a drag the system drives ends when the system says so
        }
        dragSession = null;
        damageDragImage();
        paintedDragImage = null;
        limn.transfer.DropAction done = null;
        Widget<?> target = session.target;
        limn.transfer.DropAction action = session.action;
        long saidBefore = announced;
        if (target != null && drop && action != null) {
            DropEvent event = new DropEvent(session.x, session.y, modifiers, session.keyboard,
                    session.offer, action, session.source);
            target.dispatchDrop(event);
            done = event.isRejected() ? null : action;
        } else if (target != null) {
            target.dispatchDragExit(dragEvent(session, 0, 0));
        }
        // A target that said where what it took went ("Total, 3 of 12") has said the outcome,
        // and "Dropped" after it would cut that off.
        if (session.keyboard && !(done != null && announced != saidBefore)) {
            announce((done != null ? DragStrings.DROPPED : DragStrings.CANCELLED).get(),
                    limn.accessibility.Accessible.Politeness.ASSERTIVE);
        }
        session.drag.end(done);
        applyCursor();
        if (pointerInside) {
            updateHover(hitAt(mouseX, mouseY));
        }
        scheduleFrame();
    }

    /**
     * What another program dragged is dropped on the window: the widget under the point is asked
     * whether it takes it, and handed it at once if it does.
     */
    private void dropFromOutside(limn.transfer.Offer offer, java.util.Set<limn.transfer.DropAction> allowed,
            float x, float y) {
        DragEvent over = new DragEvent(x, y, modifiers, false, 0, 0, offer, allowed,
                proposedAction(allowed, modifiers), null);
        for (Widget<?> w = hitAt(x, y); w != null; w = w.parent()) {
            if (!w.isEnabled()) {
                continue;
            }
            w.dispatchDragOver(over);
            limn.transfer.DropAction action = over.acceptedAction();
            if (action != null) {
                w.dispatchDrop(new DropEvent(x, y, modifiers, false, offer, action, null));
                return;
            }
        }
    }

    // ---- the keyboard's drag

    private void dragKey(RawKey key) {
        DragSession session = dragSession;
        if (session == null) {
            return;
        }
        if (session.system) {
            return;
        }
        if (key.pressed && key.key == Keys.ESCAPE) {
            finishDrag(false);
            return;
        }
        if (!session.keyboard) {
            dragMoved(session.x, session.y); // a modifier pressed or let go of changes what is proposed
            return;
        }
        if (!key.pressed) {
            return;
        }
        Widget<?> target = session.target;
        boolean rtl = target != null && target.isRightToLeft();
        switch (key.key) {
            case Keys.TAB -> nextKeyboardTarget((key.mods & Keys.MOD_SHIFT) != 0);
            case Keys.ENTER, Keys.SPACE -> {
                if (target != null) {
                    finishDrag(true);
                } else {
                    announce(DragStrings.NO_TARGET.get(), limn.accessibility.Accessible.Politeness.ASSERTIVE);
                }
            }
            case Keys.UP -> step(session, 0, -1);
            case Keys.DOWN -> step(session, 0, 1);
            case Keys.LEFT -> step(session, rtl ? 1 : -1, 0);
            case Keys.RIGHT -> step(session, rtl ? -1 : 1, 0);
            default -> { }
        }
        scheduleFrame();
    }

    private void step(DragSession session, int stepX, int stepY) {
        Widget<?> target = session.target;
        if (target != null) {
            keyboardTarget(target, stepX, stepY, true);
        }
    }

    /**
     * Asks one widget whether it takes the keyboard's drag, and makes it the target if it does.
     *
     * @param keep whether a refusal leaves the target as it was (an arrow key that leads nowhere)
     * @return whether it accepted
     */
    private boolean keyboardTarget(Widget<?> widget, int stepX, int stepY, boolean keep) {
        DragSession session = dragSession;
        if (session == null) {
            return false;
        }
        float wasX = session.x;
        float wasY = session.y;
        Vec2 middle = widget.localToScene(widget.width() / 2, widget.height() / 2);
        session.x = middle.x();
        session.y = middle.y();
        DragEvent event = dragEvent(session, stepX, stepY);
        widget.dispatchDragOver(event);
        if (!event.isAccepted()) {
            session.x = wasX;
            session.y = wasY;
            if (!keep && session.target == widget) {
                retarget(session, null, null);
            }
            return false;
        }
        retarget(session, widget, event.acceptedAction());
        String place = event.place();
        if (place == null) {
            limn.i18n.I18nString name = widget.accessibleName();
            place = name != null ? name.get() : null;
        }
        if (place == null) {
            List<Widget<?>> all = new ArrayList<>();
            collectDropCandidates(keyboardRoot(), all);
            place = DragStrings.TARGET.format(all.indexOf(widget) + 1, all.size());
        }
        String beginning = dragBeginning;
        dragBeginning = null;
        announce(beginning == null ? place : beginning + " " + place,
                limn.accessibility.Accessible.Politeness.ASSERTIVE);
        return true;
    }

    /** Tab in a keyboard drag: the next widget, in the tree's order, that accepts what is carried. */
    private void nextKeyboardTarget(boolean backward) {
        DragSession session = dragSession;
        if (session == null) {
            return;
        }
        List<Widget<?>> all = new ArrayList<>();
        collectDropCandidates(keyboardRoot(), all);
        if (all.isEmpty()) {
            return;
        }
        Widget<?> current = session.target;
        int from = all.indexOf(current);
        int start = from >= 0 ? from : (backward ? 0 : -1);
        for (int i = 1; i <= all.size(); i++) {
            Widget<?> candidate = all.get(Math.floorMod(start + (backward ? -i : i), all.size()));
            if (candidate != current && keyboardTarget(candidate, 0, 0, true)) {
                return;
            }
        }
        if (current == null) {
            announce(DragStrings.NO_TARGET.get(), limn.accessibility.Accessible.Politeness.ASSERTIVE);
        }
    }

    private static void collectDropCandidates(Widget<?> widget, List<Widget<?>> out) {
        if (!widget.isVisible() || !widget.isEnabled() || widget.inert) {
            return;
        }
        out.add(widget);
        for (Widget<?> child : widget.children()) {
            collectDropCandidates(child, out);
        }
    }

    // ---- a drag the system drives

    /** What another program is dragging over this window. */
    private static final class Incoming {
        @Nullable Widget<?> target;
        limn.transfer.@Nullable DropAction action;
    }

    /** {@code -Dlimn.drag.trace=true}: each step of handing a drag to the system, on standard error. */
    private static final boolean DRAG_TRACE = Boolean.getBoolean("limn.drag.trace");

    private int dragTraceMoves;

    private static void dragTrace(String line) {
        if (DRAG_TRACE) {
            System.err.println("limn.drag(scene): " + line);
        }
    }

    private @Nullable Incoming incoming;

    /**
     * Hands a pointer drag to the system, with a picture of what is dragged: the part of the
     * source the scene would paint under the pointer, cut out of the next frame where it stands.
     * Until that frame the scene drives the drag as it would any other, and goes on driving it
     * if the system does not take it.
     */
    private void handToSystem(DragSession session) {
        NativeWindow host = window;
        if (host == null) {
            return;
        }
        limn.transfer.Drag drag = session.drag;
        limn.graphics.Image given = drag.image();
        if (given != null || session.partW <= 0 || session.partH <= 0) {
            float w = given == null ? 0 : given.width();
            float h = given == null ? 0 : given.height();
            startSystemDrag(session, host, given, session.x - drag.hotSpotX(), session.y - drag.hotSpotY(), w, h);
            return;
        }
        damageDragImage(); // our own picture, if a frame already showed it, goes
        session.awaitingSystem = true; // and the next frame makes the system's (takePictureForSystem)
        dragTrace("awaiting a frame for the picture, part " + session.partW + "x" + session.partH);
        scheduleFrame();
    }

    /**
     * At the top of the frame after a drag asked to be handed to the system: paints what is
     * dragged alone on a corner of the frame cleared to nothing, reads it back with its
     * transparency, and hands the drag over with that picture. The frame is then painted whole
     * over the corner. Where the frame keeps no transparency, the picture is cut out of the
     * finished frame instead, opaque, as the part of the source stands in it.
     */
    private void takePictureForSystem(Canvas canvas) {
        DragSession session = dragSession;
        NativeWindow host = window;
        if (session == null || !session.awaitingSystem || host == null) {
            return;
        }
        float w = Math.min(session.partW, width);
        float h = Math.min(session.partH, height);
        limn.graphics.Image picture;
        canvas.save();
        try {
            canvas.damageScissorHint(0, 0, 0, 0); // no pass of the last frame clips this
            canvas.clearRect(0, 0, w, h, limn.graphics.Color.rgba(0, 0f));
            canvas.clipRect(0, 0, w, h);
            paintPreview(canvas, session);
        } finally {
            canvas.restore();
        }
        // Through the canvas, which every frame has. Read through the renderer of the frame
        // callback this scene installs, the picture was missing for any scene painted from a
        // callback of the application's own, the demo's among them: the drag went out with none.
        picture = canvas.readBack(0, 0, w, h);
        fullDamagePending = true; // that corner is painted over by the whole frame
        dragTrace("frame: picture " + (picture == null ? "none" : picture.width() + "x" + picture.height()));
        if (picture != null) {
            session.awaitingSystem = false;
            startSystemDrag(session, host, seeThrough(picture), session.x - session.grabX,
                    session.y - session.grabY, w, h);
            return;
        }
        if (session.drag.previewPainter() != null) {
            session.awaitingSystem = false; // nothing to cut the painter's picture from
            startSystemDrag(session, host, null, session.x, session.y, 0, 0);
            return;
        }
        Vec2 part = session.source.localToScene(session.partX, session.partY);
        float partX = part.x();
        float partY = part.y();
        float sceneWidth = width;
        host.captureNextFrame(frame -> {
            if (dragSession != session) {
                return; // released, or cancelled, within that one frame
            }
            session.awaitingSystem = false;
            float scale = sceneWidth > 0 ? frame.width() / sceneWidth : 1;
            startSystemDrag(session, host,
                    seeThrough(crop(frame, scale, partX, partY, session.partW, session.partH)),
                    session.x - session.grabX, session.y - session.grabY, session.partW, session.partH);
            scheduleFrame();
        });
    }

    private void startSystemDrag(DragSession session, NativeWindow host, limn.graphics.@Nullable Image image,
            float x, float y, float w, float h) {
        boolean taken = WindowPeer.of(host).startDrag(new limn.backend.spi.DragRequest(
                session.drag.content(), session.drag.actions(), image, x, y, w, h,
                action -> systemDragEnded(session, action)));
        dragTrace("the system " + (taken ? "took" : "did not take") + " the drag, picture "
                + (image == null ? "none" : image.width() + "x" + image.height()));
        if (taken) {
            session.system = true;
            if (session.target != null) {
                retarget(session, null, null); // found again as the system reports the pointer
            }
        }
    }

    /** A part of a captured frame, in the frame's own pixels. */
    private static limn.graphics.Image crop(limn.graphics.Image frame, float scale, float x, float y, float w,
            float h) {
        int x0 = Math.max(0, Math.min(frame.width(), Math.round(x * scale)));
        int y0 = Math.max(0, Math.min(frame.height(), Math.round(y * scale)));
        int cw = Math.max(1, Math.min(frame.width() - x0, Math.round(w * scale)));
        int ch = Math.max(1, Math.min(frame.height() - y0, Math.round(h * scale)));
        byte[] from = frame.pixels();
        byte[] to = new byte[cw * ch * 4];
        for (int row = 0; row < ch && y0 + row < frame.height(); row++) {
            System.arraycopy(from, ((y0 + row) * frame.width() + x0) * 4, to, row * cw * 4, cw * 4);
        }
        return new limn.graphics.Image(cw, ch, to);
    }

    /** A picture for the system at the opacity the scene's own is shown at. */
    private static limn.graphics.Image seeThrough(limn.graphics.Image picture) {
        byte[] rgba = picture.pixels().clone();
        for (int i = 3; i < rgba.length; i += 4) {
            rgba[i] = (byte) Math.round((rgba[i] & 0xff) * DRAG_IMAGE_OPACITY);
        }
        return new limn.graphics.Image(picture.width(), picture.height(), rgba);
    }

    private void systemDragEnded(DragSession session, limn.transfer.@Nullable DropAction action) {
        dragTrace("ended by the system with " + action + ", dropped here " + session.droppedHere);
        if (dragSession != session) {
            return;
        }
        dragSession = null;
        Widget<?> target = session.target;
        if (target != null && !session.droppedHere) {
            target.dispatchDragExit(dragEvent(session, 0, 0));
        }
        // A drop in one of our own windows is told by the widget that took it; one in another
        // program by the system.
        session.drag.end(session.droppedHere ? session.doneHere : action);
        applyCursor();
        if (pointerInside) {
            updateHover(hitAt(mouseX, mouseY));
        }
        scheduleFrame();
    }

    /** What the source allows, of what the system says the modifier keys allow, in the source's order. */
    private static java.util.Set<limn.transfer.DropAction> allowedBoth(
            java.util.Set<limn.transfer.DropAction> source, java.util.Set<limn.transfer.DropAction> system) {
        java.util.Set<limn.transfer.DropAction> both = new java.util.LinkedHashSet<>();
        for (limn.transfer.DropAction action : source) {
            if (system.contains(action)) {
                both.add(action);
            }
        }
        return both;
    }

    private limn.transfer.@Nullable DropAction systemDragOver(limn.transfer.Offer offer,
            java.util.Set<limn.transfer.DropAction> allowed, float x, float y) {
        Ui.checkUiThread();
        if (DRAG_TRACE && ++dragTraceMoves % 30 == 1) {
            dragTrace("over " + x + "," + y + " allowed " + allowed + " own " + (dragSession != null && dragSession.system));
        }
        DragSession own = dragSession != null && dragSession.system ? dragSession : null;
        java.util.Set<limn.transfer.DropAction> actions = own != null
                ? allowedBoth(own.drag.actions(), allowed) : allowed;
        Widget<?> acceptor = null;
        limn.transfer.DropAction action = null;
        DragEvent event = null;
        if (!actions.isEmpty()) {
            // Our own drag keeps its transfer at hand: the object itself, and who began it.
            event = new DragEvent(x, y, modifiers, false, 0, 0, own != null ? own.offer : offer, actions,
                    actions.iterator().next(), own != null ? own.source : null);
            for (Widget<?> w = hitAt(x, y); w != null && acceptor == null; w = w.parent()) {
                if (w.isEnabled()) {
                    w.dispatchDragOver(event);
                    if (event.isAccepted()) {
                        acceptor = w;
                        action = event.acceptedAction();
                    }
                }
            }
        }
        if (own != null) {
            own.x = x;
            own.y = y;
            retarget(own, acceptor, action);
        } else {
            Incoming in = incoming;
            if (in == null) {
                in = new Incoming();
                incoming = in;
            }
            Widget<?> before = in.target;
            in.target = acceptor;
            in.action = action;
            if (before != null && before != acceptor) {
                before.dispatchDragExit(event != null ? event : new DragEvent(x, y, modifiers, false, 0, 0,
                        offer, allowed, limn.transfer.DropAction.COPY, null));
            }
        }
        scheduleFrame();
        return action;
    }

    private void systemDragLeft() {
        Ui.checkUiThread();
        DragSession own = dragSession != null && dragSession.system ? dragSession : null;
        if (own != null) {
            retarget(own, null, null);
        }
        Incoming in = incoming;
        incoming = null;
        if (in != null && in.target != null) {
            in.target.dispatchDragExit(new DragEvent(mouseX, mouseY, modifiers, false, 0, 0,
                    limn.transfer.Offer.EMPTY, java.util.Set.of(limn.transfer.DropAction.COPY),
                    limn.transfer.DropAction.COPY, null));
        }
        scheduleFrame();
    }

    private limn.transfer.@Nullable DropAction systemDropped(limn.transfer.Offer offer,
            java.util.Set<limn.transfer.DropAction> allowed, float x, float y) {
        limn.transfer.DropAction action = systemDragOver(offer, allowed, x, y);
        DragSession own = dragSession != null && dragSession.system ? dragSession : null;
        Incoming in = incoming;
        incoming = null;
        Widget<?> target = own != null ? own.target : in != null ? in.target : null;
        if (target == null || action == null) {
            return null;
        }
        DropEvent event = new DropEvent(x, y, modifiers, false, own != null ? own.offer : offer, action,
                own != null ? own.source : null);
        target.dispatchDrop(event);
        limn.transfer.DropAction done = event.isRejected() ? null : action;
        if (own != null) {
            own.droppedHere = true;
            own.doneHere = done;
            own.target = null;
            own.action = null;
        }
        scheduleFrame();
        return done;
    }

    // ---- what a pointer drag shows

    /** Scrolls the widget that accepts the drag while the pointer rests near one of its edges. */
    private final class AutoScroll implements Ticker {
        private final DragSession session;
        private double waited;

        AutoScroll(DragSession session) {
            this.session = session;
        }

        @Override
        public boolean tick(double dtSeconds) {
            if (dragSession != session) {
                return false;
            }
            Widget<?> target = session.target;
            waited += dtSeconds;
            if (target == null || waited < AUTO_SCROLL_SECONDS) {
                return true;
            }
            waited = 0;
            limn.graphics.Rect box = target.localToSceneBounds(0, 0, target.width(), target.height());
            float dx = session.x < box.x() + AUTO_SCROLL_EDGE ? 1
                    : session.x > box.right() - AUTO_SCROLL_EDGE ? -1 : 0;
            float dy = session.y < box.y() + AUTO_SCROLL_EDGE ? 1
                    : session.y > box.bottom() - AUTO_SCROLL_EDGE ? -1 : 0;
            if (dx != 0 || dy != 0) {
                dispatchBubbling(target, new MouseEvent(MouseEvent.Type.WHEEL, session.x, session.y, -1,
                        dx, dy, 0));
                dragMoved(session.x, session.y); // what is under the pointer moved
            }
            return true;
        }
    }

    /** The largest part of a widget shown under the pointer when the source names none. */
    private static final float PREVIEW_MAX_WIDTH = 280;
    private static final float PREVIEW_MAX_HEIGHT = 72;

    /**
     * Settles what a pointer drag shows: the part of its widget the source named, or, when it
     * named none and gave no picture, the widget whole if it is small and otherwise the part
     * around the point where the drag began.
     */
    private void choosePreview(DragSession session) {
        limn.transfer.Drag drag = session.drag;
        if (session.keyboard || drag.isPreviewOff() || drag.image() != null) {
            return;
        }
        Widget<?> source = session.source;
        Vec2 pressLocal = source.sceneToLocal(session.x, session.y);
        float pressLocalX = pressLocal.x();
        float pressLocalY = pressLocal.y();
        if (drag.previewPainter() != null) {
            // The source paints the item alone: its size, and where the pointer holds it.
            session.partW = drag.previewWidth();
            session.partH = drag.previewHeight();
            session.grabX = Math.max(0, Math.min(session.partW, drag.previewX()));
            session.grabY = Math.max(0, Math.min(session.partH, drag.previewY()));
            return;
        } else if (drag.hasPreviewPart()) {
            session.partX = drag.previewX();
            session.partY = drag.previewY();
            session.partW = drag.previewWidth();
            session.partH = drag.previewHeight();
        } else {
            session.partW = Math.min(source.width(), PREVIEW_MAX_WIDTH);
            session.partH = Math.min(source.height(), PREVIEW_MAX_HEIGHT);
            session.partX = Math.max(0, Math.min(source.width() - session.partW, pressLocalX - session.partW / 2));
            session.partY = Math.max(0, Math.min(source.height() - session.partH, pressLocalY - session.partH / 2));
        }
        // The pointer keeps hold of the point it pressed, kept inside the part.
        session.grabX = Math.max(0, Math.min(session.partW, pressLocalX - session.partX));
        session.grabY = Math.max(0, Math.min(session.partH, pressLocalY - session.partY));
    }

    private @Nullable Rect dragImageRect() {
        DragSession session = dragSession;
        if (session == null || session.keyboard || session.system || session.awaitingSystem) {
            return null; // the system shows its own, or is about to
        }
        limn.graphics.Image image = session.drag.image();
        if (image != null) {
            return new Rect(session.x - session.drag.hotSpotX(), session.y - session.drag.hotSpotY(),
                    image.width(), image.height());
        }
        return session.partW <= 0 || session.partH <= 0 ? null
                : new Rect(session.x - session.grabX, session.y - session.grabY, session.partW, session.partH);
    }

    private void damageDragImage() {
        if (paintedDragImage != null) {
            addDamage(paintedDragImage.x() - 1, paintedDragImage.y() - 1,
                    paintedDragImage.width() + 2, paintedDragImage.height() + 2);
        }
        Rect now = dragImageRect();
        if (now != null) {
            addDamage(now.x() - 1, now.y() - 1, now.width() + 2, now.height() + 2);
        }
    }

    private void paintDragImage(Canvas canvas) {
        Rect rect = dragImageRect();
        DragSession session = dragSession;
        paintedDragImage = rect;
        if (rect == null || session == null) {
            return;
        }
        limn.graphics.Image image = session.drag.image();
        canvas.save();
        try {
            canvas.setOpacity(DRAG_IMAGE_OPACITY);
            if (image != null) {
                canvas.drawImage(image, rect.x(), rect.y(), rect.width(), rect.height());
            } else {
                canvas.clipRect(rect.x(), rect.y(), rect.width(), rect.height());
                canvas.translate(rect.x(), rect.y());
                paintPreview(canvas, session);
            }
        } finally {
            canvas.restore();
        }
    }

    /**
     * Paints what a drag shows, at the canvas's origin: what the source's painter draws, or the
     * part of the source widget, painted again away from its place.
     */
    private void paintPreview(Canvas canvas, DragSession session) {
        java.util.function.Consumer<Canvas> painter = session.drag.previewPainter();
        // A partial frame skips a widget whose own place misses the pass being painted, and the
        // source is painted here away from its place: with the cull left on, its picture vanished
        // whole whenever the pass was the target repainting itself and not the source (the
        // owner's report, over the other board in the demo). The clip is this painting's bound.
        // A painter is under the same rule: a list's paints the row's own widget.
        boolean culling = cullActive;
        cullActive = false;
        try {
            if (painter != null) {
                painter.accept(canvas);
            } else {
                canvas.translate(-session.partX, -session.partY);
                session.source.paintWidget(canvas);
            }
        } finally {
            cullActive = culling;
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
        renderFrame(canvas, rePresent, Float.NaN, 2);
    }

    /**
     * Renders the frame a window's backend asked for, with everything the backend said about it:
     * the re-present flag, the GPU-time sample and the back buffer's age. A frame callback of an
     * application's own, one that does something before or after the scene paints, forwards the
     * {@link limn.backend.spi.FrameInfo} whole through here.
     *
     * <p>It replaced an overload that took the flag and the sample and assumed the age, which every
     * such callback in the demo called: when the backend began reading the age from the driver,
     * those callbacks went on dropping it, and on Wayland, where the age is three or four, the
     * demo's window drew black but for the status bar that changes every frame (2026-09-25).
     *
     * @param canvas the backend's canvas for this frame
     * @param frame  what the backend passed to the frame callback
     */
    public void renderFrame(Canvas canvas, limn.backend.spi.FrameInfo frame) {
        renderFrame(canvas, frame.rePresent(), frame.gpuFrameMs(), frame.bufferAge());
    }

    /**
     * @param gpuFrameMs backend-measured GPU time of a recently completed frame
     *                   in ms ({@link Float#NaN} = no new sample); recorded into
     *                   {@link #metrics()} alongside this frame's CPU numbers
     * @param bufferAge how many presents old the canvas's contents are ({@link
     *                  limn.backend.spi.FrameInfo#bufferAge}): 0 repaints the whole frame, 1 what changed
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
        if (!rePresent && dragSession != null && dragSession.awaitingSystem) {
            takePictureForSystem(canvas);
        }
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
        if (!rePresent && pointerInside && pressed == null && dragSession == null) {
            // Content may have moved under a stationary mouse (keyboard scroll,
            // overlay close, layout change): hover follows what is under the
            // pointer NOW, because pointer events alone would leave a
            // scrolled-away widget with stale ENTER state and the wrong cursor.
            updateHover(hitAt(mouseX, mouseY));
        }
        accessibilityStep(rePresent);
        DamageRects repaint = repaintRegion; // whole = the whole frame, empty = nothing
        // What this present changes: nothing for a re-present, which draws the same frame again
        // into whichever buffer the backend hands over; the fresh damage, widened, for a content
        // frame.
        DamageRects change = presentChange;
        if (rePresent) {
            change.clear();
        } else {
            DamageRects fresh = freshDamage;
            consumeFreshDamage(canvas, fresh);
            // Widened BEFORE it is recorded as this present's change, so the older buffers repaint
            // the backdrop rects too, for the same reason this one does.
            withBackdropDependants(canvas, fresh);
            change.copyFrom(fresh);
            if (damageDebug) {
                List<Rect> flashNow = updateDamageFlashes(fresh.isWhole() ? null : fresh.toList(),
                        canvas);
                change.unionWith(flashNow);
                change.unionWith(flashPrev1); // the frame that erases a flash changes its area
                flashPrev1 = flashNow;
            }
        }
        // The back buffer holds the frame from bufferAge presents ago, so this present's change
        // repaints together with what every present since then changed (ADR 046 §5): nothing more
        // for a buffer that keeps its contents (1), the last present's for double buffering (2),
        // two for triple (3), and the whole frame for an age the backend does not know (0) or one
        // older than the history reaches. A driver may hand over a buffer it has not presented
        // for twenty frames, and one mid-run switch from one buffer to three is what emptied the
        // popups on Linux.
        repaint.copyFrom(change);
        boolean whole = !partialRendering || bufferAge <= 0 || bufferAge - 1 > PRESENT_HISTORY
                || bufferAge - 1 > presentsRecorded;
        for (int k = 0; !whole && k < bufferAge - 1; k++) {
            unionScratch.unionOf(repaint, presentChanges[Math.floorMod(presentHead - 1 - k, PRESENT_HISTORY)]);
            repaint.copyFrom(unionScratch);
        }
        if (whole || repaint.coversWhole(canvas.width(), canvas.height())) {
            repaint.setWhole();
        }
        presentChanges[presentHead].copyFrom(change);
        presentHead = (presentHead + 1) % PRESENT_HISTORY;
        presentsRecorded++;
        if (!rePresent) {
            // Animation state advances once per frame; the per-pass paints
            // below must be pure (a pass per damage rect would double-advance).
            updateModalScrim();
            updateTooltipFade();
        }
        // Once for the frame: the palette's background is the same for every pass of it.
        Color clearTo = background();
        if (repaint.isWhole()) {
            canvas.damageScissorHint(0, 0, 0, 0); // disabled: the frame is full
            canvas.clear(clearTo);
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
                    canvas.clearRect(px, py, pw, ph, clearTo);
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

    private static DamageRects[] newPresentHistory() {
        DamageRects[] history = new DamageRects[PRESENT_HISTORY];
        for (int i = 0; i < history.length; i++) {
            history[i] = new DamageRects(MAX_DAMAGE_RECTS, true); // no history yet: whole
        }
        return history;
    }

    /**
     * Resolves and resets the damage accumulated since the last content frame into
     * {@code out}: whole = the whole scene, empty = nothing. Rects are clamped and
     * snapped outward to the device pixel grid ({@link #snapToDeviceGrid}).
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
        float[] rect = snapScratch;
        for (int i = 0; i < pendingDamage.size(); i++) {
            rect[0] = pendingDamage.x(i);
            rect[1] = pendingDamage.y(i);
            rect[2] = pendingDamage.width(i);
            rect[3] = pendingDamage.height(i);
            if (snapToDeviceGrid(canvas, rect)) {
                out.append(rect[0], rect[1], rect[2], rect[3]);
            }
        }
        pendingDamage.clear();
    }

    private final float[] snapScratch = new float[4];

    /**
     * Grows a damage rectangle ({@code x, y, width, height}, in place) outward to the DEVICE pixel
     * grid and clamps it to the canvas; every rectangle a pass is painted in goes through here.
     *
     * <p>A pass clears its rectangle rounded to whole device pixels and paints inside a clip that
     * antialiases its edges, so an edge between two pixels clears the pixel it cuts and paints it
     * back only in part: what shows there is the content with a share of the background in it, a
     * line the height of the pass. On the grid both are exact, which is what lets clearRect replace
     * exactly the pixels the pass repaints, on a translucent background as on an opaque one. A union
     * of rectangles on the grid is on the grid, so merging them afterwards keeps it.
     *
     * @return whether anything of it is left on the canvas
     */
    private static boolean snapToDeviceGrid(Canvas canvas, float[] rect) {
        float s = canvas.contentScale();
        float x = Math.max(0, (float) Math.floor(rect[0] * s) / s);
        float y = Math.max(0, (float) Math.floor(rect[1] * s) / s);
        float right = Math.min(canvas.width(), (float) Math.ceil((rect[0] + rect[2]) * s) / s);
        float bottom = Math.min(canvas.height(), (float) Math.ceil((rect[1] + rect[3]) * s) / s);
        if (right <= x || bottom <= y) {
            return false;
        }
        rect[0] = x;
        rect[1] = y;
        rect[2] = right - x;
        rect[3] = bottom - y;
        return true;
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
        paintDragImage(canvas);
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
    /** The box of the widget a pass is asking about, in the scene: one, since the paint runs on the UI thread. */
    private final float[] cullScratch = new float[4];

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
        float[] box = cullScratch;
        widget.localToSceneBounds(-outset, -outset, widget.width() + outset, widget.height() + outset, box);
        // A box whose every corner lies behind the eye answers NaN, and NaN is not culled: what
        // cannot be placed is painted rather than lost.
        boolean misses = box[2] <= cullX0 || box[0] >= cullX1 || box[3] <= cullY0 || box[1] >= cullY1;
        // A pass reaches a moved child wherever it is drawn (ADR 072 §2.6): an ancestor's box says
        // nothing of where a transform under it moved its child, and a drawer slid past it would be
        // skipped by the pass that repaints where the drawer now is.
        return misses && (transformed.isEmpty()
                || !widget.drawsMovedChildInScene(cullX0, cullY0, cullX1, cullY1));
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
    private float paintedTooltipReach; // how far past it that frame's shadow went

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
        // The panel and what its shadow reaches, the reach as last painted for where it was.
        if (paintedTooltipRect != null) {
            float was = 1 + paintedTooltipReach;
            addDamage(paintedTooltipRect.x() - was, paintedTooltipRect.y() - was,
                    paintedTooltipRect.width() + 2 * was, paintedTooltipRect.height() + 2 * was);
        }
        Rect now = tooltipRect();
        if (now != null) {
            java.util.function.Function<ControlSize, TooltipStyle> supplier = tooltipStyle;
            float reach = 1 + (supplier == null ? 0 : supplier.apply(tooltipStep()).shadowReach());
            addDamage(now.x() - reach, now.y() - reach, now.width() + 2 * reach, now.height() + 2 * reach);
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
        // One picture: the panel, its border, its shadow and its line fade together, so the shadow
        // does not darken the panel halfway through the fade. A canvas layer and not a widget's
        // opacity, since the tooltip is the scene's own painting and not a widget.
        float reach = 1 + style.shadowReach();
        canvas.saveLayer(x - reach, y - reach, w + 2 * reach, h + 2 * reach, tooltipAlpha);
        for (limn.graphics.Shadow shadow : style.shadows()) {
            canvas.drawShadow(limn.graphics.RoundRect.of(x, y, w, h, style.radius()), shadow);
        }
        canvas.fillRoundRectWithBorder(x, y, w, h, style.radius(), style.fill(), 1, style.border());
        // Inside the pad on the side reading starts from; the panel was sized from this very
        // line, so the two pads are equal and the run fills what is between them.
        float textX = tooltipDirection().isRightToLeft()
                ? x + w - style.padH() - line.metrics().width()
                : x + style.padH();
        canvas.drawText(line, textX, y + (h - fm.height()) / 2 + fm.ascent(), style.text());
        canvas.restore();
        paintedTooltipRect = rect;
        paintedTooltipReach = style.shadowReach();
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
    public void fadeWindowOut(double seconds, @Nullable Runnable onGone) {
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
    public void fadeWindow(float target, double seconds, @Nullable Runnable onArrive) {
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
        if (graftedInto != null) {
            // Its nodes leave the tree that published them, on that tree's next walk.
            graftedInto.graftedPopups.remove(this);
            graftedInto.invalidateAccessible();
            graftedInto = null;
        }
        bridge = limn.backend.AccessibilityBridge.NONE;
        publishedTree = limn.accessibility.AccessibleTree.EMPTY;
        metricsListener.release();
        paletteListener.release();
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
        detachForTheClosedWindow();
    }

    /** Whether the tree was taken out of this scene by its window closing; {@link #bind} puts it back. */
    private boolean detachedByClose;

    /**
     * The tree leaves the scene with its window, as it would leave it by being removed: every
     * widget's {@code onDetached} runs. What a widget took in {@code onAttached} from something
     * that outlives the window (a place in a process-wide list of listeners, the system's menu
     * bar, a decoder's pictures) it gives back there and nowhere else, so a window that closed
     * with its widgets still attached left each of those holding the widget, and through it the
     * scene and all it shows, for the life of the process. Last, because every step before this
     * one may still ask a widget for its scene.
     */
    private void detachForTheClosedWindow() {
        detachedByClose = true;
        BOUND_SCENES.remove(this);
        if (locale != null) {
            limn.i18n.I18n.releaseLocale(locale); // a closed window's language is nobody's to keep loaded
        }
        List<Widget<?>> layers = new ArrayList<>(overlays);
        layers.add(root);
        for (Widget<?> layer : layers) {
            if (layer.scene() != this) {
                continue; // an overlay closed by a widget that was detached before it
            }
            try {
                layer.setSceneRecursively(null);
            } catch (Throwable error) {
                LOG.log(Level.ERROR, "a widget's detach threw during window close; teardown continues", error);
                limn.backend.Crashes.report(limn.backend.CrashPhase.WINDOW_CLOSE, error);
            }
        }
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

    private void layOutForTheSceneThatPublishesIt() {
        if (window != null) {
            layoutPass(window.logicalWidth(), window.logicalHeight());
        }
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
        markAccessibleNodesDirty();
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
