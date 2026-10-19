package limn.components;

import limn.graphics.Color;

/**
 * Which of the palette's colours a piece of text or a mark is drawn in, said by what it means rather
 * than by the colour: a line that reports a failure is {@link #DANGER}, whatever red the palette in
 * force calls danger.
 *
 * <p><b>A tone is resolved when it is drawn, not when it is set.</b> A {@link Color} read off
 * {@link Theme#current()} while a page is built is the palette of that moment, and a change of
 * palette afterwards (the system turning dark, the user picking another) leaves it behind; a tone
 * is asked of the theme on every paint, so it follows. That is what {@link Label#setTone} gives a
 * line of text, and what a {@link Field} draws its line under the control with.
 *
 * <p>Each tone is one of the palette's tokens, so its contrast is the palette's: the built-in
 * palettes are tested for each as text on the canvas, on a card and on a popover.
 */
public enum Tone {
    /** Nothing to call attention to: the palette's {@link Theme#textMuted() muted text}. */
    NEUTRAL,
    /**
     * The application's own colour: the palette's accent {@linkplain Theme#primaryText() as text},
     * which is {@link Theme#primary()} where that already reads as text on every surface.
     */
    ACCENT,
    /** A note: the palette's {@link Theme#info() info}. */
    INFO,
    /** Something done or accepted: the palette's {@link Theme#success() success}. */
    SUCCESS,
    /** Something to check: the palette's {@link Theme#warning() warning}. */
    WARNING,
    /** Something refused or failed: the palette's {@link Theme#danger() danger}. */
    DANGER;

    /**
     * The colour this tone is in {@code theme}: what a widget drawing in a tone asks, on every paint.
     *
     * @param theme the palette to read
     * @return the colour
     */
    public Color colorIn(Theme theme) {
        return switch (this) {
            case NEUTRAL -> theme.textMuted();
            case ACCENT -> theme.primaryText();
            case INFO -> theme.info();
            case SUCCESS -> theme.success();
            case WARNING -> theme.warning();
            case DANGER -> theme.danger();
        };
    }
}
