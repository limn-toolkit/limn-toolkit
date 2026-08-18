package limn.components;

import limn.animation.Easing;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Path2D;
import limn.i18n.I18nString;
import limn.graphics.TextMetrics;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Toggle with two visual variants: the classic {@code BOX} with a check
 * mark, and the mobile-style {@code SWITCH} whose thumb slides. The check/slide
 * is driven by a shared {@link Transition} (the toolkit's reusable animator),
 * so it only renders while moving. Toggled by click, or Space/Enter when
 * focused. Colours come from the {@link Theme}, extents from the
 * {@link SizeTokens} row resolved on this widget, and every pen from
 * {@link Strokes}.
 *
 * <h2>This row is under the 24 pt pointer target</h2>
 * The row measures {@code max(indicator, lineHeight)} and the indicator wins that max at
 * every step (18 pt at MEDIUM for {@code BOX}, 22 for {@code SWITCH}), which is below the
 * 24 pt target of WCAG 2.2 SC 2.5.8 (AA) on the axis that decides it: a label widens a
 * target, it never heightens it. A toggle's pointer target is exactly the box it paints;
 * there is no hit outset.
 *
 * <p>The standard's own <em>Spacing</em> exception is what an application relies on: an
 * undersized target conforms while a 24 pt circle centred on it clears every neighbour. A
 * lone toggle already satisfies that; a column of them does not until the pitch reaches 24,
 * which is what {@link Tokens#toggleColumnGap(limn.scene.Widget)} gives. Stack toggles on
 * that gap, not a tighter one.
 */
public class Checkbox extends Widget {

    public enum Variant { BOX, SWITCH }

    /**
     * The box the check-mark path below was authored against. The path is scaled by
     * {@code indicator / this}, which is exactly {@code 1.0f} wherever the indicator ramp
     * sits on 18, so those steps reproduce the hand-tuned mark bit for bit.
     */
    private static final float CHECK_PATH_BOX = 18;

    private final Variant variant;
    private I18nString text;
    private boolean checked;
    private Consumer<Boolean> onChange = value -> {
    };
    /** 0 = unchecked visual, 1 = checked visual; eased toward the state. */
    private final Transition progress =
            new Transition(this, 0).duration(Theme.current().animFade).easing(Easing.LINEAR);
    private final Transition hover =
            new Transition(this).duration(Theme.current().animHover).easing(Theme.current().animEasing);
    private final Transition focusFade =
            new Transition(this).duration(Theme.current().animFocus).easing(Theme.current().animEasing);
    /** Reused each paint; the 3 points depend only on {@code top} and the resolved box. */
    private final Path2D checkPath = new Path2D();

    /** A toggle with a fixed label; see the {@link I18nString} constructor for localized text. */
    public Checkbox(Variant variant, String text) {
        this(variant, I18nString.literal(Objects.requireNonNull(text, "text")));
    }

    /** A checkbox whose label follows the UI language; see {@link I18nString}. */
    public Checkbox(Variant variant, I18nString text) {
        this.variant = variant;
        this.text = Objects.requireNonNull(text, "text");
        setFocusable(true);
        setCursor(Cursor.POINTER);
    }

    /** Called with the new state on user toggles only, not on {@link #setChecked}. */
    public Checkbox onChange(Consumer<Boolean> listener) {
        Ui.checkUiThread();
        this.onChange = Objects.requireNonNull(listener, "listener");
        return this;
    }

    /** The current state. */
    public boolean isChecked() {
        return checked;
    }

    /** Sets the state, animating the visual transition. */
    public Checkbox setChecked(boolean newChecked) {
        Ui.checkUiThread();
        if (checked == newChecked) {
            return this;
        }
        checked = newChecked;
        progress.to(checked ? 1 : 0); // eases (or snaps, when detached/headless)
        invalidate();
        return this;
    }

    /** Flips the state and fires {@link #onChange}, as a click does. UI thread only. */
    public void toggle() {
        setChecked(!checked);
        onChange.accept(checked);
    }

    float animationProgress() {
        return progress.value();
    }

    /** Test seam, like {@link #animationProgress()}: {@code baselineOffset()} is protected. */
    float textBaseline() {
        return baselineOffset();
    }

    /**
     * The painted indicator's width: the box, or the switch track. Deliberately <em>not</em>
     * {@code t.controlHeight()}: a checkbox row is the indicator, not a 32pt control box, so
     * it keeps its sub-24 row at every step.
     */
    private float indicatorWidth(SizeTokens t) {
        return variant == Variant.BOX ? t.indicator() : t.switchTrackW();
    }

    private float indicatorHeight(SizeTokens t) {
        return variant == Variant.BOX ? t.indicator() : t.switchTrackH();
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        TextMetrics metrics = textRuler().measure(text.get(), t.body());
        float width = indicatorWidth(t)
                + (text.get().isEmpty() ? 0 : t.gapLabel() + metrics.width());
        float height = Math.max(indicatorHeight(t), metrics.lineHeight());
        return constraints.constrain(width, height);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        float cy = (height() - indicatorHeight(t)) / 2;
        if (variant == Variant.BOX) {
            paintBox(canvas, theme, t, cy);
        } else {
            paintSwitch(canvas, theme, t, cy);
        }
        if (!text.get().isEmpty()) {
            TextMetrics metrics = textRuler().measure(text.get(), t.body());
            Color ink = isEnabled() ? theme.text : theme.disabledText;
            canvas.drawText(text.get(), indicatorWidth(t) + t.gapLabel(),
                    (height() - metrics.height()) / 2 + metrics.ascent(), t.body(), ink);
        }
        float focus = focusFade.value();
        if (focus > 0.001f) {
            // The gap is 1.5. The box border is a
            // 1.5pt pen centred on the 0.5 inset, so its outer ink edge is 0.25pt OUTSIDE the
            // nominal box; a ring 1pt out landed its inner ink edge on exactly that line and the
            // two strokes fused into one thick seam. At 1.5 there is 0.5pt of clear ground.
            float gap = Strokes.FOCUS_GAP_INDICATOR;
            canvas.drawRoundRect(-gap, cy - gap, indicatorWidth(t) + 2 * gap,
                    indicatorHeight(t) + 2 * gap, t.indicatorFocusRadius(),
                    Strokes.FOCUS_RING_THIN, theme.focusRing.withAlpha(focus));
        }
    }

    /**
     * The focus ring is the only thing that paints outside the box, and it paints well outside:
     * centred {@link Strokes#FOCUS_GAP_INDICATOR} out with a {@link Strokes#FOCUS_RING_THIN} pen,
     * its outer ink reaches 2.25pt past the indicator. The indicator is flush with the widget's
     * left edge, with its top and bottom at every step (the row <em>is</em> the indicator), and
     * with the right edge when there is no label, so that reach is outside {@code bounds} on all
     * four sides. {@link limn.scene.Scene} assumes only 1pt of AA feather, so the fading ring left
     * stale pixels under partial rendering even at a 1pt gap (reach 1.75); the 1.5pt gap widens the
     * gap to 1.5 and the reach with it. Locked, like both quantities it is composed of.
     */
    @Override
    protected float paintOutset() {
        return Strokes.FOCUS_GAP_INDICATOR + Strokes.FOCUS_RING_THIN / 2;
    }

    @Override
    protected float baselineOffset() {
        if (text.get().isEmpty()) {
            return super.baselineOffset();
        }
        TextMetrics metrics = textRuler().measure(text.get(), Theme.current().tokensFor(this).body());
        return (height() - metrics.height()) / 2 + metrics.ascent();
    }

    private void paintBox(Canvas canvas, Theme theme, SizeTokens t, float top) {
        float p = progress.value();
        float box = t.indicator();
        Color border = !isEnabled() ? theme.disabledFill
                : p > 0 ? theme.primary
                : theme.outline.lerp(theme.primaryHover, hover.value());
        // Fill fades in with the animation.
        Color fill = (isEnabled() ? theme.primary : theme.disabledFill).withAlpha(p);
        canvas.fillRoundRect(0, top, box, box, t.indicatorRadius(), fill);
        canvas.drawRoundRect(Strokes.HALF_PIXEL_INSET, top + Strokes.HALF_PIXEL_INSET,
                box - Strokes.BORDER, box - Strokes.BORDER,
                t.indicatorRadius(), Strokes.INDICATOR_BORDER, border);
        if (p > 0.05f) {
            Color ink = (isEnabled() ? theme.onPrimary : theme.disabledText).withAlpha(p);
            // The mark's extent follows the box; its pen does not (Strokes.CHECK_MARK).
            float s = box / CHECK_PATH_BOX;
            checkPath.reset();
            checkPath.moveTo(4 * s, top + 9.5f * s)
                    .lineTo(7.5f * s, top + 13 * s)
                    .lineTo(14 * s, top + 5.5f * s);
            canvas.drawPath(checkPath, Strokes.CHECK_MARK, ink);
        }
    }

    private void paintSwitch(Canvas canvas, Theme theme, SizeTokens t, float top) {
        float p = progress.value();
        float trackW = t.switchTrackW();
        float trackH = t.switchTrackH();
        Color off = isEnabled() ? theme.surfaceRaised : theme.disabledFill;
        Color on = isEnabled() ? theme.primary : theme.disabledFill;
        Color track = off.lerp(on, p);
        canvas.fillRoundRect(0, top, trackW, trackH, trackH / 2, track);
        canvas.drawRoundRect(Strokes.HALF_PIXEL_INSET, top + Strokes.HALF_PIXEL_INSET,
                trackW - Strokes.BORDER, trackH - Strokes.BORDER,
                trackH / 2, Strokes.BORDER, theme.outline.withAlpha(1 - p));
        float inset = t.switchThumbInset();
        float thumbRadius = trackH / 2 - inset;
        float minX = inset + thumbRadius;
        float maxX = trackW - inset - thumbRadius;
        float thumbX = minX + (maxX - minX) * p;
        // The OFF thumb must read against the OFF track: surfaceRaised (track) and
        // surface (the old thumb) are near-identical in dark palettes, so the thumb
        // rides on textMuted (a light neutral guaranteed to contrast with the track)
        // and brightens to onPrimary as it slides onto the accent track.
        Color thumb = isEnabled() ? theme.textMuted.lerp(theme.onPrimary, p) : theme.disabledText;
        canvas.fillCircle(thumbX, top + trackH / 2, thumbRadius, thumb);
    }

    @Override
    protected void onMouseEvent(MouseEvent event) {
        switch (event.type()) {
            case ENTER -> hover.to(1);
            case EXIT -> hover.to(0);
            case PRESS -> event.consume();
            case CLICK -> {
                if (event.button() == Keys.MOUSE_LEFT) {
                    event.consume();
                    toggle();
                }
            }
            default -> {
            }
        }
    }

    @Override
    protected void onKeyEvent(KeyEvent event) {
        if ((event.key() == Keys.SPACE || event.key() == Keys.ENTER)
                && event.isPressed() && !event.isRepeat()) {
            event.consume();
            toggle();
        }
    }

    @Override
    protected void onFocusGained() {
        focusFade.to(1);
    }

    @Override
    protected void onFocusLost() {
        focusFade.to(0);
    }
}
