package limn.themeeditor;

import limn.components.SizeTokens;
import limn.components.Strokes;
import limn.components.Theme;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.RoundRect;
import limn.graphics.TextMetrics;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

import java.util.Objects;

/**
 * A miniature window, painted from a palette that is <b>not</b> the process-wide one.
 *
 * <p>That is the whole point of it, and the reason it draws itself instead of holding real
 * widgets. Every widget in this toolkit reads {@link Theme#current()} as it paints, so a
 * preview built out of a {@code Button} and a {@code Label} would show whichever palette
 * the application is wearing, which is either the one being edited (in which case the
 * preview says nothing the surrounding window does not) or the wrong one entirely.
 *
 * <p>It also shows what a running application does not: the hover and pressed states of the
 * accent, a disabled control, a popover above a card, and all four semantic tones, all at
 * once and without having to be interacted with. Those are the tones a palette author
 * cannot see while choosing them, and they are where a hand-built palette goes wrong.
 *
 * <p><b>Not a rendering of the toolkit's real chrome</b>, and it does not try to be: it is
 * a swatch board with the shapes of a window, held deliberately simple so that a change to
 * a component's paint cannot silently make the preview a lie.
 */
public final class ThemePreview extends Widget {

    private Theme theme;

    /** A preview of {@code theme}, which is never read from {@link Theme#current()}. */
    public ThemePreview(Theme theme) {
        this.theme = Objects.requireNonNull(theme, "theme");
    }

    /** The palette being previewed. */
    public Theme theme() {
        return theme;
    }

    /** Shows another palette. Repaints; never re-lays out, since the geometry is fixed. */
    public ThemePreview setTheme(Theme value) {
        Ui.checkUiThread();
        this.theme = Objects.requireNonNull(value, "value");
        invalidate();
        return this;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = tokens();
        // Eight rows, because the contents are what they are: three lines of type, the
        // accent's four states, and a focused field whose ring is drawn OUTSIDE its box.
        // At seven the field falls off the card and the one tone a keyboard user depends on
        // is the one the preview stops showing.
        float wide = 12 * t.controlHeight();
        float tall = 8 * t.controlHeight();
        return constraints.constrain(
                constraints.hasBoundedWidth() ? Math.min(wide, constraints.maxWidth()) : wide,
                tall);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        SizeTokens t = tokens();
        float w = width();
        float h = height();
        if (w < 4 || h < 4) {
            return;
        }
        float pad = t.spacingMedium();
        float gap = t.spacingSmall();
        float row = t.controlHeight();

        canvas.save();
        canvas.clipRoundRect(RoundRect.of(0, 0, w, h, t.radiusMedium()));
        canvas.fillRect(0, 0, w, h, theme.background);

        // A card on the canvas, holding the type ramp.
        float cardX = pad;
        float cardY = pad;
        float cardW = w - 2 * pad;
        float cardH = h - 2 * pad - row - gap;
        canvas.fillRoundRect(cardX, cardY, cardW, cardH, t.radiusMedium(), theme.surface);
        outline(canvas, cardX, cardY, cardW, cardH, t.radiusMedium());

        float textX = cardX + pad;
        float cursorY = cardY + pad;
        // Clipped to the card's own text column, not to the widget: a preview whose body
        // line runs out over the canvas would be showing text on the wrong surface, which
        // is the exact mistake the preview is here to catch.
        canvas.save();
        canvas.clipRect(textX, cardY, cardW - 2 * pad, cardH);
        cursorY = line(canvas, ThemeEditorStrings.PREVIEW_TITLE.get(), textX, cursorY,
                t.title(), theme.text);
        cursorY = line(canvas, ThemeEditorStrings.PREVIEW_BODY.get(), textX, cursorY,
                t.body(), theme.text);
        cursorY = line(canvas, ThemeEditorStrings.PREVIEW_BODY.get(), textX, cursorY,
                t.body(), theme.textMuted);
        canvas.restore();

        // One button in its four states, side by side: the row a running application shows
        // one cell of at a time, and the reason the pressed accent is where a palette breaks.
        String action = ThemeEditorStrings.PREVIEW_ACTION.get();
        float chipW = Math.max(row, (cardW - 2 * pad - 3 * gap) / 4);
        float chipY = cursorY + gap;
        float chipX = textX;
        chipX = accentChip(canvas, t, chipX, chipY, chipW, row, theme.primary,
                theme.onPrimary, action) + gap;
        chipX = accentChip(canvas, t, chipX, chipY, chipW, row, theme.primaryHover,
                theme.onPrimary, action) + gap;
        chipX = accentChip(canvas, t, chipX, chipY, chipW, row, theme.primaryPressed,
                theme.onPrimary, action) + gap;
        accentChip(canvas, t, chipX, chipY, chipW, row, theme.disabledFill,
                theme.disabledText, action);

        // A focused field: the ring is drawn outside its box, which is the only way to see
        // whether it survives on the surface it lands on.
        float fieldY = chipY + row + gap + Strokes.FOCUS_RING_OUTSET;
        float fieldW = Math.min(cardW - 2 * pad, 6 * row);
        if (fieldY + row <= cardY + cardH - pad) {
            canvas.fillRoundRect(textX, fieldY, fieldW, row, t.radiusSmall(), theme.surfaceRaised);
            outline(canvas, textX, fieldY, fieldW, row, t.radiusSmall());
            float ring = Strokes.FOCUS_GAP_BUTTON;
            canvas.drawRoundRect(textX - ring, fieldY - ring, fieldW + 2 * ring, row + 2 * ring,
                    t.radiusSmall() + ring, Strokes.FOCUS_RING, theme.focusRing);
        }

        // The semantic four, on the canvas, as a strip along the bottom.
        float stripY = h - pad - row;
        float stripW = (w - 2 * pad - 3 * gap) / 4;
        Color[] semantic = {theme.danger, theme.warning, theme.success, theme.info};
        for (int i = 0; i < semantic.length; i++) {
            float x = pad + i * (stripW + gap);
            canvas.fillRoundRect(x, stripY, stripW, row, t.radiusSmall(), theme.surfaceRaised);
            outline(canvas, x, stripY, stripW, row, t.radiusSmall());
            float dot = t.iconBox() / 2;
            canvas.fillCircle(x + gap + dot / 2, stripY + row / 2, dot / 2, semantic[i]);
        }
        canvas.restore();
    }

    /**
     * The previewed palette's row, at the step this preview sits at.
     *
     * <p><b>The palette's, not {@link Theme#current()}'s.</b> The step is the editor's (a
     * preview is furniture in whatever pane holds it, and a palette carries no step), but the
     * row has to be the previewed palette's, because a palette carries its corner radii. Read
     * from the current palette instead and the preview shows the edited colours wearing
     * somebody else's shape, which is only invisible while the two happen to be the same
     * palette.
     */
    private SizeTokens tokens() {
        return theme.tokens(controlSize());
    }

    /** @return the baseline-advanced cursor, so the caller stacks lines without arithmetic */
    private float line(Canvas canvas, String text, float x, float y, Font font, Color ink) {
        TextMetrics metrics = textRuler().measure(text, font);
        canvas.drawText(text, x, y + metrics.ascent(), font, ink);
        return y + metrics.lineHeight();
    }

    /** @return the chip's right edge */
    private float accentChip(Canvas canvas, SizeTokens t, float x, float y, float w, float h,
                             Color fill, Color ink, String caption) {
        canvas.fillRoundRect(x, y, w, h, t.radiusMedium(), fill);
        TextMetrics metrics = textRuler().measure(caption, t.label());
        if (metrics.width() <= w - t.spacingSmall()) {
            canvas.drawText(caption, x + (w - metrics.width()) / 2,
                    y + (h - metrics.height()) / 2 + metrics.ascent(), t.label(), ink);
        }
        return x + w;
    }

    private void outline(Canvas canvas, float x, float y, float w, float h, float radius) {
        float inset = Strokes.HALF_PIXEL_INSET;
        canvas.drawRoundRect(x + inset, y + inset, w - 2 * inset, h - 2 * inset, radius,
                Strokes.BORDER, theme.outline);
    }
}
