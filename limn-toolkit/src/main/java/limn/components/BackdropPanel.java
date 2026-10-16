package limn.components;

import limn.concurrent.Ui;
import limn.graphics.BackdropEffect;
import limn.graphics.Canvas;
import limn.scene.Insets;
import limn.scene.Widget;
import limn.scene.layout.Padding;

import java.util.Objects;

/**
 * A panel whose background is the content behind it, put through a {@link BackdropEffect}: a
 * glass control bar over video, a washed header over a picture, a redacted field. Wraps one child
 * with insets, like the {@link Padding} it extends, and paints the effect under it.
 *
 * <p>{@link #frosted(Widget)} is the one most applications want: frosted glass in the theme's own
 * tone, light or dark as the palette is, with an optional {@linkplain #setTint tint}.
 *
 * <p><b>Put it where it will be painted after what it sits over.</b> The effect samples what the
 * frame has already drawn, so a panel is a sibling <em>after</em> the content: in a stack, the
 * layer above; in a column, nothing sits behind it and the effect shows the window's own
 * background, which is a legitimate but very dull result.
 *
 * <p>Costs a batch break and a copy of its own bounds per frame it paints. That is a control bar's
 * worth of work, not a list row's: a panel per row is the wrong shape for this widget, and a
 * hundred of them will show up in the frame time.
 *
 * <p>Degrades to a flat panel in the effect's {@link BackdropEffect#tint() tint} where the renderer
 * has no backdrop support, with the same size, position and corner radius.
 *
 * <p><b>A redaction painted here is a picture, and not a fact.</b> The effect covers whatever the
 * frame has already drawn — siblings this panel holds no reference to and knows nothing about — so
 * a screen reader still reads the covered widget's text, verbatim, from the covered widget's own
 * node. Blurring a card number hides it from the person looking at the screen and from nobody
 * else. An application that means to redact something hides or ignores the widget that holds it,
 * and uses the panel for what the eye is shown.
 *
 * <p>The panel itself is never in the accessible tree: it declares no role, no name, no state and
 * no action, so it is scaffolding, and a reader hears the controls inside it with the glass
 * nowhere in the telling. It says its painting is decoration, so it is removed in silence.
 *
 * <p><b>Size axis:</b> the corner radius follows the resolved {@link limn.scene.ControlSize} row
 * like every other component's chrome, unless {@link #setCornerRadius} pins it. The effect's own
 * dimensions (a rim width, a cell size) are authored in points and do not scale with the step:
 * they are optical properties of the material, not of the control.
 */
public final class BackdropPanel extends Padding {

    /** {@link #setCornerRadius} takes this to mean "follow the resolved size row". */
    public static final float RADIUS_FROM_TOKENS = -1;

    private java.util.List<BackdropEffect> effects;
    private float cornerRadius = RADIUS_FROM_TOKENS;

    /** A panel with 12pt of padding around {@code child}. */
    public BackdropPanel(BackdropEffect effect, Widget<?> child) {
        this(effect, Insets.all(12), child);
    }

    /**
     * A panel with {@code insets} of padding around {@code child}.
     *
     * @param effect what the panel does to the pixels behind it; never null
     * @param insets the padding
     * @param child  the content
     */
    public BackdropPanel(BackdropEffect effect, Insets insets, Widget<?> child) {
        super(insets, child);
        this.effects = java.util.List.of(Objects.requireNonNull(effect, "effect"));
    }

    /**
     * Yes: this panel's picture is the pixels behind it, put through an effect.
     *
     * <p>Which is why it is stale whenever they change, without anything about the panel moving.
     * Answering this is what puts its rectangle into the damage of a frame that repaints anything
     * underneath it, and without it partial rendering could not be trusted over one of these.
     */
    @Override
    protected boolean paintsFromBackdrop() {
        return true;
    }

    // ------------------------------------------------------------ frosted glass

    /** Whether the effects are worked out from the theme at each paint; see {@link #frosted}. */
    private boolean frosted;
    private limn.graphics.@org.jspecify.annotations.Nullable Color tint;
    private float tintAmount;
    /** The stack last worked out, and what it was worked out from. */
    private java.util.@org.jspecify.annotations.Nullable List<BackdropEffect> frostedStack;
    private limn.graphics.@org.jspecify.annotations.Nullable Color frostedFrom;

    /**
     * A panel of frosted glass with 12pt of padding around {@code child}; see
     * {@link #frosted(Insets, Widget)}.
     *
     * @param child the content
     * @return the panel
     */
    public static BackdropPanel frosted(Widget<?> child) {
        return frosted(Insets.all(12), child);
    }

    /**
     * A panel of <b>frosted glass that follows the theme</b>: what is behind it is blurred, then
     * calmed and laid over with the palette's surface tone, so that text on it reads. The tone is
     * read from the theme in effect at each paint: light over a light palette, dark over a dark
     * one, and the other one the moment the palette changes, with nothing for the application to
     * do. A bar that stays while a page scrolls under it, or a panel of controls over a picture.
     *
     * <p>{@link #setTint} mixes a colour of the application's into that tone. A panel that needs
     * something else, another blur, a refracting rim, takes its effects by hand with
     * {@link #setEffects}, and is then no longer worked out from the theme.
     *
     * @param insets the padding
     * @param child  the content
     * @return the panel
     */
    public static BackdropPanel frosted(Insets insets, Widget<?> child) {
        BackdropPanel panel = new BackdropPanel(
                new BackdropEffect.Wash(limn.graphics.Color.TRANSPARENT, 1f, 0f), insets, child);
        panel.frosted = true;
        return panel;
    }

    /** @return whether this panel's effects are worked out from the theme, as {@link #frosted} makes it */
    public final boolean isFrosted() {
        return frosted;
    }

    /**
     * Mixes a colour into frosted glass: the glass keeps the theme's light or dark tone and
     * leans toward {@code colour} by {@code amount}. A brand colour at a tenth is a hint of it; at
     * a half the glass is plainly that colour.
     *
     * @param colour the colour to lean toward, or {@code null} for none; its alpha is not used
     * @param amount how far, from 0 (the theme's tone alone) to 1 (the colour alone)
     * @return this panel
     * @throws IllegalStateException when this panel is not {@linkplain #frosted frosted}
     * @throws IllegalArgumentException if {@code amount} is not a number; one past either end is
     *         clamped to it
     */
    public BackdropPanel setTint(limn.graphics.@org.jspecify.annotations.Nullable Color colour, float amount) {
        Ui.checkUiThread();
        if (Float.isNaN(amount)) {
            throw new IllegalArgumentException("an amount is a number from 0 to 1: " + amount);
        }
        if (!frosted) {
            throw new IllegalStateException("a tint is frosted glass's: this panel was given its effects, "
                    + "and each of those carries its own");
        }
        this.tint = colour;
        this.tintAmount = colour == null ? 0 : Math.max(0, Math.min(1, amount));
        frostedStack = null;
        invalidate();
        return this;
    }

    /** @return the colour mixed into frosted glass, or {@code null} */
    public final limn.graphics.@org.jspecify.annotations.Nullable Color tint() {
        return tint;
    }

    /** @return how far frosted glass leans toward {@link #tint()}, 0 to 1 */
    public final float tintAmount() {
        return tintAmount;
    }

    /**
     * Sets how much of its tone frosted glass lays over what is behind it: the less, the more of
     * what is behind shows through, blurred, and the less the text on the glass is helped against
     * it. 0.6 unless set, which reads over any content; a panel over a picture
     * chosen for it can take less.
     *
     * @param cover from 0, where what is behind is blurred and nothing more is laid on it, to 1,
     *              where the tone hides it
     * @return this panel
     * @throws IllegalStateException when this panel is not {@linkplain #frosted frosted}
     * @throws IllegalArgumentException if {@code cover} is not a number; one past either end is
     *         clamped to it
     */
    public BackdropPanel setCover(float cover) {
        Ui.checkUiThread();
        if (Float.isNaN(cover)) {
            throw new IllegalArgumentException("a cover is a number from 0 to 1: " + cover);
        }
        if (!frosted) {
            throw new IllegalStateException("a cover is frosted glass's: this panel was given its effects, "
                    + "and each of those carries its own");
        }
        this.cover = Math.max(0, Math.min(1, cover));
        frostedStack = null;
        invalidate();
        return this;
    }

    /** @return how much of its tone frosted glass lays over what is behind, 0 to 1 */
    public final float cover() {
        return cover;
    }

    private float cover = FROST_COVER;

    /** How wide frosted glass blurs, in points, along each axis. */
    private static final float FROST_BLUR = 10;
    /** How much of the tone covers what is behind unless {@link #setCover} says: a little more than half. */
    static final float FROST_COVER = 0.6f;
    /** How much colour what is behind keeps under the tone. */
    private static final float FROST_SATURATION = 0.7f;
    /** How far what is behind is moved toward the tone's own lightness before the tone goes on. */
    private static final float FROST_LIFT = 0.08f;
    /** How much further, for each part of the cover given up: text has to read on what is left. */
    private static final float FROST_LIFT_FOR_COVER = 0.8f;

    /** Frosted glass in {@code theme}'s tone, kept until the tone it was made from changes. */
    private java.util.List<BackdropEffect> frostedIn(Theme theme) {
        limn.graphics.Color surface = theme.surface();
        if (frostedStack != null && surface.equals(frostedFrom)) {
            return frostedStack;
        }
        limn.graphics.Color tone = tint == null || tintAmount == 0 ? surface : surface.lerp(tint, tintAmount);
        // Toward white under a light tone and toward black under a dark one: bright content
        // behind dark glass, or dark content behind light glass, is what text on it loses to.
        // The less of the tone is laid on, the further what is behind is moved instead: glass that
        // covers little over content of the other lightness is glass nothing can be read on, and
        // moving the lightness keeps the colours of what is behind where covering hides them.
        float moved = FROST_LIFT + Math.max(0, FROST_COVER - cover) * FROST_LIFT_FOR_COVER;
        float lift = theme.isDark() ? -moved : moved;
        frostedFrom = surface;
        frostedStack = java.util.List.of(
                new BackdropEffect.Blur(limn.graphics.Color.TRANSPARENT, FROST_BLUR, BackdropEffect.Blur.Axis.X),
                new BackdropEffect.Blur(limn.graphics.Color.TRANSPARENT, FROST_BLUR, BackdropEffect.Blur.Axis.Y),
                new BackdropEffect.Wash(tone.withAlpha(cover), FROST_SATURATION, lift));
        return frostedStack;
    }

    /** The first effect painted behind the child; see {@link #effects()} for the whole stack. */
    public final BackdropEffect effect() {
        return effects().get(0);
    }

    /** Every effect painted behind the child, in the order they are drawn. */
    public final java.util.List<BackdropEffect> effects() {
        return frosted ? frostedIn(Theme.of(this)) : effects;
    }

    /**
     * Replaces the effect with a <b>stack</b>, drawn in order over the same shape.
     *
     * <p>This is how the effects compose, and it needs nothing from the renderer: each one reads
     * the framebuffer and writes it, so the second samples what the first left. It is what makes
     * a frosted pane out of the pieces rather than out of a variant with four parameters, and
     * what makes {@link BackdropEffect.Blur} affordable, since a separable blur IS two passes.
     *
     * <p>Each pass costs one batch flush and one copy of the panel's own bounds, so a stack of
     * three costs three of each. Over a small panel that is cheap and over a full-window one it
     * is not; the cost is proportional to the shape, not to the window.
     *
     * @throws IllegalArgumentException if the stack is empty
     */
    public BackdropPanel setEffects(BackdropEffect... stack) {
        Ui.checkUiThread();
        if (stack.length == 0) {
            throw new IllegalArgumentException("a backdrop panel needs at least one effect");
        }
        this.effects = java.util.List.of(stack);
        this.frosted = false;
        invalidate();
        return this;
    }

    /** Replaces the effect. UI thread only. */
    public BackdropPanel setEffect(BackdropEffect newEffect) {
        Ui.checkUiThread();
        this.effects = java.util.List.of(Objects.requireNonNull(newEffect, "newEffect"));
        this.frosted = false;
        invalidate();
        return this;
    }

    /**
     * Pins the corner radius in points, or {@link #RADIUS_FROM_TOKENS} to follow the resolved size
     * row (the default). UI thread only.
     */
    public BackdropPanel setCornerRadius(float radius) {
        Ui.checkUiThread();
        this.cornerRadius = radius;
        invalidate();
        return this;
    }

    /**
     * The glass is material and carries no information, so the tree removes this panel without
     * saying anything about it.
     *
     * <p>Every other member of the scaffolding family — the rows, the columns, the paddings —
     * paints nothing at all and is removed in silence for free. This one paints, and the guard
     * that catches a custom gauge nobody named cannot tell a gauge from a wash: it would name a
     * toolkit class in an application's log, for a picture that is background over content this
     * panel does not own, and recommend marking it ignored — which would take the whole child
     * subtree with it and delete the very controls the glass sits behind.
     *
     * @return {@code true}, always
     */
    @Override
    protected boolean paintsDecoration() {
        return true;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        float radius = cornerRadius >= 0
                ? cornerRadius
                : Theme.of(this).tokensFor(this).radiusLarge();
        // In order: each pass reads what the one before it wrote.
        for (BackdropEffect each : effects()) {
            canvas.fillBackdropRoundRect(0, 0, width(), height(), radius, each);
        }
    }
}
