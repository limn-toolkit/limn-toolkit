package limn.scene;

import limn.concurrent.Ui;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The size step of a control: a deliberate, hand-tuned design axis, <b>not</b> a scale
 * factor and <b>not</b> a DPI setting (that is {@code Canvas.contentScale()}, applied
 * downstream in the backend; the two multiply, in that order, and must never be conflated).
 *
 * <p>Per step, type, control heights and spacing move on three <em>different</em> hand-authored
 * ramps, while strokes (borders, focus rings and their gaps, hairline separators, check-mark
 * strokes, carets) keep the same thickness at every step. Nothing anywhere is
 * {@code base * k(step)}.
 *
 * <p>This enum is the <em>axis</em> only. It carries no numbers, so the toolkit can own it
 * without knowing anything about a theme, which is what lets a raw
 * {@link limn.scene.layout.Row} act as a size scope for the components inside it. What a step
 * <em>means</em> lives with the design tokens in the components layer
 * ({@code limn.components.SizeTokens}, reached through {@code Theme.tokensFor(widget)}).
 *
 * <h2>The coexistence contract</h2>
 * A widget's step is <b>inherited down the tree</b>: its own
 * {@linkplain Widget#declaredControlSize() declared} value, else the nearest declaring
 * ancestor's, else its {@linkplain Scene#controlSize() scene's} default, else its
 * {@linkplain Widget#setControlSizeHost host}'s, else {@link #processDefault()}.
 *
 * <p>Several steps therefore <b>coexist in one window, in one frame</b>: a SMALL toolbar above
 * a MEDIUM form beside a LARGE dialog is three scopes in one tree, resolved independently, laid
 * out in a single pass. Any subtree overrides its ancestors, and a subtree that declares its own
 * step is untouched when an ancestor's changes: {@link Widget#measure} keys its cache on the
 * resolved step, so a container's change re-measures exactly the descendants whose step actually
 * changed.
 *
 * <p><b>A row that mixes steps and carries text needs a baseline, not a box.</b>
 * {@code CrossAlignment.CENTER} aligns boxes, and the baseline offset between steps is a
 * function of the type ramp that no choice of control heights cancels: adjacent steps sit
 * up to 0.69&nbsp;pt apart, the full ramp up to 2.73&nbsp;pt. Use
 * {@link limn.scene.layout.Flex.CrossAlignment#BASELINE} there, {@code CENTER} otherwise.
 * {@code START} aligns box tops exactly, but leaves the same baseline spread.
 *
 * <h2>Never read this in a constructor</h2>
 * {@link Widget#add} assigns the child's parent <em>after</em> the child is fully
 * constructed, so {@code new Button("OK")} runs with no parent and resolves to the process
 * default whatever its eventual parent declares. There is no reparent hook
 * ({@link Widget#onAttached} fires only on the {@code null -> scene} edge), so the usual
 * builder order ({@code toolbar.setControlSize(SMALL); toolbar.add(button);}) never fires
 * anything for the button. A step captured at construction is permanently wrong with no
 * path to recovery.
 *
 * <p>Read it in {@code onMeasure}, {@code onPaint}, {@code onLayout} or an event handler,
 * where the tree is complete, and resolve it <b>once per pass</b> into a local: two
 * resolutions that disagree inside one component route clicks to the wrong segment, row
 * or field.
 *
 * <h2>Density floor</h2>
 * The height floor is 24&nbsp;pt at {@link #XSMALL}, so no control on the ramp needs a
 * pointer target wider than its painted box and input dispatch is untouched. XSMALL is
 * 0.750 of MEDIUM, a <em>compact</em> step, not a miniature one. Where an axis would fall
 * below the floor it is clamped to {@code Strokes.MIN_HIT_TARGET}.
 *
 * @see Widget#controlSize()
 * @see Scene#setControlSize(ControlSize)
 */
public enum ControlSize {
    /** Dense: property inspectors, data-grid rows, packed toolbars. */
    XSMALL,
    /** Compact forms and toolbars. */
    SMALL,
    /** The default. Every existing Limn UI renders here. */
    MEDIUM,
    /** Primary actions, settings dialogs, low-density surfaces. */
    LARGE,
    /** Hero and onboarding surfaces. Every component honours it; there is no clamp. */
    XLARGE;

    private static volatile ControlSize processDefault = MEDIUM;

    /**
     * Listeners notified when the process default changes. Every live {@link Scene}
     * subscribes in its constructor, so unbound (headless) scenes hear it too.
     * Mirrors {@link limn.graphics.Fonts#addChangeListener}.
     */
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** @return the step used where nothing in the tree and no scene declares one */
    public static ControlSize processDefault() {
        return processDefault;
    }

    /**
     * Sets the process-wide fallback step: the root of the inheritance chain, and the
     * app-level "compact mode" switch. Every live scene re-measures, overlays included and
     * <em>unbound scenes included</em>; widgets and scenes that declare their own step are
     * unaffected. No-op when unchanged. UI thread only.
     */
    public static void setProcessDefault(ControlSize size) {
        Ui.checkUiThread();
        Objects.requireNonNull(size, "size");
        if (processDefault == size) {
            return;
        }
        processDefault = size;
        Widget.bumpControlSizeEpoch();
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    /** Subscribes to process-default changes (idempotent per instance). */
    public static void addChangeListener(Runnable listener) {
        LISTENERS.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /** Unsubscribes; no-op when it was never registered. */
    public static void removeChangeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }
}
