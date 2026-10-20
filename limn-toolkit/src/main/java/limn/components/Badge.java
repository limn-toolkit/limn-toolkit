package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Icon;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.NumberFormats;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A short status beside or inside something else: "Overdue" in a table's cell, "3" beside an
 * inbox, a dot beside a row's name that says it is unread.
 *
 * <p><b>One look, whatever the tone.</b> A pill tinted in the {@link Tone}, and at its leading end
 * either the {@linkplain #setIcon icon} drawn in the tone or, with no icon, a dot of it; the word is
 * in the palette's text colour. The colour says the state at a glance and the word says it to
 * everyone, a person who does not tell red from green and a screen reader included, and the
 * word's contrast is the palette's own text on its surface, which every built-in palette is tested
 * for, at every tone.
 *
 * <p>Three kinds, by how the badge is made:
 * <ul>
 *   <li>a <b>text</b> badge, {@link #Badge(I18nString)}, with a dot or an icon before the word;</li>
 *   <li>a <b>count</b>, {@link #ofCount(int)}: the number alone in the pill, in the digits the
 *       language is shown in, and "99+" past 99 so a badge does not grow with its count;</li>
 *   <li>a <b>dot</b>, {@link #dot(I18nString)}: the dot alone, twice as wide as a text badge's, and
 *       a name a reader hears in its place.</li>
 * </ul>
 *
 * <p>To an assistive technology a badge is a static text, a {@link Accessible.Role#LABEL}, named by
 * the word it shows, the number as shown, or a dot's name. It takes no focus and has no verb. An
 * application that wants a count read as "3 unread" names it with {@link #setAccessibleName}; a
 * badge inside a table's row or an activatable {@link Card} is read through the row's or the card's
 * own name, as anything inside them is.
 *
 * <pre>{@code
 * new Badge("Overdue").setTone(Tone.DANGER);
 * Badge.ofCount(unread).setTone(Tone.ACCENT).setAccessibleName(unread + " unread");
 * }</pre>
 */
public final class Badge extends Widget<Badge> {

    /** The largest count shown as itself; past it the badge reads "99+". */
    private static final int MOST_SHOWN = 99;

    private enum Kind { TEXT, COUNT, DOT }

    private final Kind kind;
    private I18nString text;
    private @Nullable Icon icon;
    private Tone tone = Tone.NEUTRAL;
    private int count;

    // The count as shown, formatted once per change of count or of language rather than on every
    // measure and paint, with the counter the accessible name is diffed by.
    private @Nullable String countShown;
    private int countShownFor;
    private long countShownEpoch;
    private long countWitness;

    // ONE shaping of the shown text, held so the measure pass and the paint that follows cannot
    // disagree about its width; refreshed through ShapedText.matches, as Button's caption is.
    private @Nullable ShapedText line;

    /**
     * A badge with a fixed word.
     *
     * @param text the word; never null
     */
    public Badge(String text) {
        this(I18nString.literal(Objects.requireNonNull(text, "text")));
    }

    /**
     * A badge whose word follows the UI language.
     *
     * @param text the word; never null
     */
    public Badge(I18nString text) {
        this(Kind.TEXT, Objects.requireNonNull(text, "text"));
    }

    /**
     * A badge whose icon leads its word, drawn in the badge's tone in place of the dot.
     *
     * @param icon the icon; never null
     * @param text the word, which also names the badge; never null
     */
    public Badge(Icon icon, I18nString text) {
        this(text);
        this.icon = Objects.requireNonNull(icon, "icon");
    }

    private Badge(Kind kind, I18nString text) {
        this.kind = kind;
        this.text = text;
    }

    /**
     * A badge that shows a number: in the digits the language is shown in, and "99+" past 99.
     *
     * @param n the count; at least zero
     * @return the badge
     * @throws IllegalArgumentException if {@code n} is negative
     */
    public static Badge ofCount(int n) {
        Badge badge = new Badge(Kind.COUNT, I18nString.literal(""));
        badge.count = requireCount(n);
        return badge;
    }

    /**
     * A dot alone, for an "unread" or "changed" mark beside a row's name: twice as wide as a text
     * badge's dot, with no pill and no word. The name is what a reader hears in its place.
     *
     * @param name what the dot means; never null
     * @return the badge
     */
    public static Badge dot(I18nString name) {
        return new Badge(Kind.DOT, Objects.requireNonNull(name, "name"));
    }

    private static int requireCount(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("a badge counts from zero: " + n);
        }
        return n;
    }

    /**
     * Replaces the word, or a dot's name, with a fixed string.
     *
     * @param newText the word; never null
     * @return this badge
     * @throws IllegalStateException on a count badge, which shows its {@link #setCount count}
     */
    public Badge setText(String newText) {
        return setText(I18nString.literal(Objects.requireNonNull(newText, "newText")));
    }

    /**
     * Replaces the word, or a dot's name, with one that follows the UI language. A layout runs
     * only when the badge's width changes.
     *
     * @param newText the word; never null
     * @return this badge
     * @throws IllegalStateException on a count badge, which shows its {@link #setCount count}
     */
    public Badge setText(I18nString newText) {
        Ui.checkUiThread();
        Objects.requireNonNull(newText, "newText");
        if (kind == Kind.COUNT) {
            throw new IllegalStateException("a count badge shows its count; call setCount");
        }
        if (text.equals(newText)) {
            return this;
        }
        float was = shownWidth();
        this.text = newText;
        textChanged(was);
        return this;
    }

    /** @return the word, a dot's name, or for a count the number as shown */
    public String text() {
        return shown();
    }

    /**
     * Sets the icon drawn before the word in place of the dot, in the badge's tone; {@code null}
     * brings the dot back. Not drawn by a count or a dot badge.
     *
     * @param newIcon the icon, or {@code null}
     * @return this badge
     */
    public Badge setIcon(@Nullable Icon newIcon) {
        Ui.checkUiThread();
        if (icon == newIcon) {
            return this;
        }
        this.icon = newIcon;
        // The icon's square and the dot are not the same width, so the box moves.
        markNeedsLayout();
        return this;
    }

    /** @return the icon drawn before the word, or {@code null} for the dot */
    public @Nullable Icon icon() {
        return icon;
    }

    /**
     * Sets the tone the pill, the dot and the icon are drawn in; {@link Tone#NEUTRAL} by default.
     * Resolved against the palette on every paint, so a change of palette re-colours the badge.
     *
     * @param newTone the tone; never null
     * @return this badge
     */
    public Badge setTone(Tone newTone) {
        Ui.checkUiThread();
        Objects.requireNonNull(newTone, "tone");
        if (tone == newTone) {
            return this;
        }
        this.tone = newTone;
        invalidate(); // colour only: the same box
        return this;
    }

    /** @return the tone the badge is drawn in */
    public Tone tone() {
        return tone;
    }

    /**
     * Changes a count badge's number. A layout runs only when the shown width changes, which is
     * when the number of digits does.
     *
     * @param n the count; at least zero
     * @return this badge
     * @throws IllegalArgumentException if {@code n} is negative
     * @throws IllegalStateException    on a badge that is not a {@link #ofCount(int) count}
     */
    public Badge setCount(int n) {
        Ui.checkUiThread();
        if (kind != Kind.COUNT) {
            throw new IllegalStateException("only a badge made by Badge.ofCount shows a count");
        }
        requireCount(n);
        if (count == n) {
            return this;
        }
        float was = shownWidth();
        this.count = n;
        textChanged(was);
        return this;
    }

    /** @return a count badge's number, or zero for any other */
    public int count() {
        return count;
    }

    /**
     * The shown text's shaped width, which is the number {@link #onMeasure} grows with; negative
     * while there is no box to keep, so a change then always lays out.
     */
    private float shownWidth() {
        if (kind == Kind.DOT || scene() == null || width() <= 0) {
            return -1;
        }
        return line(textRuler(), Theme.of(this).tokensFor(this).label()).metrics().width();
    }

    /**
     * The new text either keeps the box, and a repaint is the whole change, or it does not, and the
     * layout that follows is the in-place one, which repaints what the new width moved and not the
     * window: a count that ticks in a tool bar must not repaint every widget each time.
     *
     * @param was {@link #shownWidth()} before the change
     */
    private void textChanged(float was) {
        if (kind == Kind.DOT) {
            // A dot's name is heard and never drawn.
            invalidateAccessible();
        } else if (was >= 0 && shownWidth() == was) {
            invalidate();
        } else {
            markNeedsLayoutInPlace();
        }
        notifyChange(Change.of(Change.Aspect.NAME, Change.Origin.CODE));
    }

    /** What the badge shows, or for a dot what it is named. */
    private String shown() {
        if (kind != Kind.COUNT) {
            return text.get();
        }
        long epoch = I18n.epoch();
        if (countShown == null || countShownFor != count || countShownEpoch != epoch) {
            countShown = count > MOST_SHOWN
                    ? ComponentStrings.BADGE_AT_LEAST.format(MOST_SHOWN)
                    : NumberFormats.number().apply(count);
            countShownFor = count;
            countShownEpoch = epoch;
            countWitness++;
        }
        return countShown;
    }

    private ShapedText line(TextRuler ruler, Font font) {
        String value = shown();
        ShapedText.Direction base = ShapedText.Direction.of(value, neutralBase());
        if (line == null || !line.matches(value, font, base, ruler)) {
            line = ruler.shape(value, font, base);
        }
        return line;
    }

    // ------------------------------------------------------------------ geometry
    //
    // At MEDIUM spacingSmall is 6, the label font 13 pt: a pill 9 pt from either end to its
    // content, a 6 pt dot and 4 pt to the word, which is the pill the Showcase drew by hand within
    // a point, with no token of its own.

    /** From either end of the pill to what is inside it: a small spacing and a half. */
    private static float pad(SizeTokens t) {
        return t.spacingSmall() * 1.5f;
    }

    /** A text badge's dot: one small spacing across. */
    private static float dotSize(SizeTokens t) {
        return t.spacingSmall();
    }

    /** From the dot or the icon to the word. */
    private static float markGap(SizeTokens t) {
        return Math.round(t.spacingSmall() * 2 / 3f);
    }

    /** The icon's square: the label font's size, integral so its bitmap cache stays bounded. */
    private static float iconSize(SizeTokens t) {
        return Math.round(t.label().size());
    }

    /** The mark before the word: the icon's square or the dot. */
    private float markWidth(SizeTokens t) {
        return icon != null ? iconSize(t) : dotSize(t);
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        if (kind == Kind.DOT) {
            float d = 2 * dotSize(t);
            return constraints.constrain(d, d);
        }
        TextMetrics metrics = line(textRuler(), t.label()).metrics();
        float h = metrics.lineHeight() + t.spacingSmall();
        float w = kind == Kind.COUNT
                // A count is centred, and at least a circle: one digit is as wide as it is tall.
                ? Math.max(h, metrics.width() + 2 * t.spacingSmall())
                : pad(t) + markWidth(t) + markGap(t) + metrics.width() + pad(t);
        return constraints.constrain(w, h);
    }

    /** The baseline a BASELINE row aligns on: the very expression the paint draws with. */
    @Override
    protected float baselineOffset() {
        if (kind == Kind.DOT) {
            return super.baselineOffset();
        }
        return textRuler().centredBaseline(Theme.of(this).tokensFor(this).label(), height());
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        SizeTokens t = theme.tokensFor(this);
        Color ink = tone.colorIn(theme);
        if (kind == Kind.DOT) {
            float d = Math.min(width(), height());
            canvas.fillRoundRect((width() - d) / 2, (height() - d) / 2, d, d, d / 2, ink);
            return;
        }
        // The tint the Showcase's status pills were drawn with: strong enough to read as the
        // tone, light enough that the palette's text keeps its own contrast on it.
        canvas.fillRoundRect(0, 0, width(), height(), height() / 2,
                ink.withAlpha(ink.a() * (theme.isDark() ? 0.26f : 0.16f)));
        ShapedText shaped = line(textRuler(), t.label());
        TextMetrics metrics = shaped.metrics();
        float baseline = textRuler().centredBaseline(t.label(), height());
        if (kind == Kind.COUNT) {
            canvas.drawText(shaped, (width() - metrics.width()) / 2, baseline, theme.text());
            return;
        }
        boolean rtl = isRightToLeft();
        float mark = markWidth(t);
        // Leading, written as a coordinate: the pill's left end reading left to right and its
        // right end reading right to left, the word beside it on the inside.
        float markX = rtl ? width() - pad(t) - mark : pad(t);
        if (icon != null) {
            icon.paint(canvas, markX, (height() - mark) / 2, mark, ink, theme.isDark());
        } else {
            canvas.fillRoundRect(markX, (height() - mark) / 2, mark, mark, mark / 2, ink);
        }
        float textX = rtl ? markX - markGap(t) - metrics.width() : markX + mark + markGap(t);
        canvas.drawText(shaped, textX, baseline, theme.text());
    }

    /**
     * A static text named by what it shows: the word, the number as drawn, or a dot's name. The
     * count's name is the held string with the counter that moves when it does, so a quiet frame
     * compares a number and allocates nothing.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.LABEL);
        if (kind == Kind.COUNT) {
            String value = shown();
            a.name(value, countWitness, Accessible.NameFrom.CONTENT);
        } else {
            // A dot's name too is published as the static text's own content, so every platform
            // reads it as it reads a text badge's word: it is what the dot says.
            a.name(text, Accessible.NameFrom.CONTENT);
        }
    }
}
