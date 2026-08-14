package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.i18n.I18nString;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Single- or multi-line text. Without {@link #setWrap wrap}, text that does
 * not fit is cut with a real-measured ellipsis ({@code …}), never a blind
 * character count. With wrap, lines break greedily on spaces (words wider
 * than the box hard-break). Horizontal and vertical alignment supported.
 * Colors come from the {@link Theme}; typography comes from the resolved
 * {@link limn.scene.ControlSize} step, picked by {@link Role}.
 *
 * <p><b>Content-tight at every step</b>: the measured box is exactly the line box: no
 * minimum height, no padding, and no contribution from the icon. A Label's job beside a
 * Button or a TextField is to let the <em>parent's</em> alignment decide the row height; a
 * floor here would double-count against the container's control height.
 *
 * <p><b>The icon overhangs rather than growing the row.</b> Its square is
 * {@link Strokes#ICON_OPTICAL_BUMP} larger than the line box, so measuring
 * {@code max(lineHeight, iconBox)} would let it win unconditionally and leave an icon Label
 * taller than a bare one; a form column mixing the two goes visibly ragged. The row is the
 * line box either way and the bump hangs out of the top and bottom, which is what it is
 * for: icon glyphs carry no ascender/descender slack and must be drawn slightly larger than
 * the text box to read at the same weight. The overhang is declared to partial rendering by
 * {@link #paintOutset()} and drawn outside the text clip.
 */
public class Label extends Widget {

    public enum Overflow { CLIP, ELLIPSIS }

    public enum HAlign { START, CENTER, END }

    public enum VAlign { TOP, CENTER, BOTTOM }

    /**
     * Which typographic token the text is drawn with, orthogonal to the size step,
     * which decides how big that token is. The pair is a 3 × 5 grid, not a precedence
     * fight: {@code new Label(title).setRole(TITLE)} inside a LARGE dialog gets the
     * LARGE title, where {@code setFont(theme.title)} would pin a MEDIUM one forever.
     */
    public enum Role { BODY, LABEL, TITLE }

    private static final String ELLIPSIS = "…";

    private I18nString text;
    private limn.graphics.Icon icon;
    private Font font;   // null → the role's token at the resolved step
    private Role role = Role.BODY;
    private Color color; // null → theme (text, or textMuted when muted)
    private boolean muted;
    private boolean strong; // bold weight applied on top of the effective font
    private boolean emphasis; // italic style applied on top of the effective font
    private Overflow overflow = Overflow.ELLIPSIS;
    private HAlign hAlign = HAlign.START;
    private VAlign vAlign = VAlign.CENTER;
    private boolean wrap;

    // Derived-font memo. Font.bold()/italic() allocate a fresh record every call and
    // FontStore.resolved is an IdentityHashMap, so a strong/italic Label re-derived per
    // pass missed the glyph memo on every measure, layout and paint. Keyed on the base
    // instance, which is stable: the token rows are built once and setFont holds one ref.
    private Font memoBase;
    private Font memoDerived;

    // computed at layout
    private final List<String> lines = new ArrayList<>();
    // How far the icon square reaches past height(), per side. Cached here rather than
    // recomputed in paintOutset() because that runs on the damage path (every invalidate()
    // and every partial-repaint cull test), while this depends only on the icon box and
    // height(), and height() can only change through layoutBox, which always calls onLayout.
    private float iconOverhang;

    /** A label showing a fixed string; see the {@link I18nString} constructor for localized text. */
    public Label(String text) {
        this(I18nString.literal(Objects.requireNonNull(text, "text")));
    }

    /** A label whose text follows the UI language; see {@link I18nString}. */
    public Label(I18nString text) {
        this.text = Objects.requireNonNull(text, "text");
    }

    /** The text as it currently reads; see {@link #textSource()} for the key behind it. */
    public String text() {
        return text.get();
    }

    /** The localizable value this label holds, which a language change re-resolves. */
    public I18nString textSource() {
        return text;
    }

    /** Replaces the text with a fixed string. Repaints without a layout pass when the box is unchanged. */
    public Label setText(String newText) {
        Objects.requireNonNull(newText, "newText");
        // Compared before wrapping: a status label re-set every frame must not allocate
        // a literal per update just to discover nothing changed.
        if (text.isLiteral() && text.get().equals(newText)) {
            return this;
        }
        return setText(I18nString.literal(newText));
    }

    /** Replaces the text with a value that follows the UI language. UI thread only. */
    public Label setText(I18nString newText) {
        Ui.checkUiThread();
        Objects.requireNonNull(newText, "newText");
        if (text.equals(newText)) {
            return this; // unchanged: no layout, no damage (status labels re-set often)
        }
        // A layout pass repaints the WHOLE window (structural invariant), so a
        // ticking counter ("8.3 s") in a status label would full-flash every
        // update. When the new text occupies exactly the same box, and line
        // breaking cannot change (no wrap), a local repaint is enough.
        // width() > 0 because the fast path rebuilds the line against the CURRENT box:
        // before the first layout pass there is no box, and ellipsizing against zero
        // would cache a lone ellipsis for a label that is about to be measured properly.
        if (!wrap && scene() != null && width() > 0) {
            SizeTokens t = Theme.current().tokensFor(this);
            Font f = effectiveFont(t);
            TextRuler ruler = textRuler();
            boolean sameSize = ruler.measure(text.get(), f).width()
                    == ruler.measure(newText.get(), f).width();
            this.text = newText;
            if (sameSize) {
                // Skipping the layout pass means skipping onLayout, and onLayout is what
                // fills `lines`, which is the ONLY thing onPaint draws. Without this the
                // label repaints the string it no longer holds, forever, and the defect
                // hits exactly the case this fast path exists for: a counter whose digits
                // are the same width every tick.
                buildLine(ruler, f, textWidth(t, f));
                invalidate();
                return this;
            }
        } else {
            this.text = newText;
        }
        markNeedsLayout();
        return this;
    }

    /** Room the text itself gets: the box less the icon and its gap. */
    private float textWidth(SizeTokens t, Font f) {
        return Math.max(0, width() - (icon == null ? 0 : iconBox(t, f) + t.gapLabel()));
    }

    /**
     * The single line a non-wrapping label paints. Shared with {@link #onLayout()} rather
     * than repeated: the two have to agree on the ellipsis, or a text set through the
     * repaint-only path would break differently from the same text after a layout.
     */
    private void buildLine(TextRuler ruler, Font f, float textWidth) {
        lines.clear();
        String shown = text.get();
        if (overflow == Overflow.ELLIPSIS && ruler.measure(shown, f).width() > textWidth) {
            lines.add(ellipsize(shown, textWidth, ruler, f));
        } else {
            lines.add(shown);
        }
    }

    /**
     * Pins an explicit font, overriding both the {@link Role} and the size step:
     * the escape hatch, and the only thing that beats them. While it is set, the icon
     * box falls back to the font-derived formula, so an explicitly 14 pt label inside
     * an XLARGE subtree does not get a 24 pt icon. {@code null} restores the role.
     */
    public Label setFont(Font newFont) {
        Ui.checkUiThread();
        if (Objects.equals(font, newFont)) {
            return this;
        }
        this.font = newFont;
        memoBase = null;
        markNeedsLayout();
        return this;
    }

    /** The typographic role this label draws with. */
    public Role role() {
        return role;
    }

    /**
     * Selects the typographic token; the resolved step decides its size. Ignored while
     * an explicit {@link #setFont} is in force.
     */
    public Label setRole(Role newRole) {
        Ui.checkUiThread();
        Objects.requireNonNull(newRole, "newRole");
        if (role == newRole) {
            return this;
        }
        this.role = newRole;
        memoBase = null;
        markNeedsLayout();
        return this;
    }

    /** Pins the text colour; {@code null} restores the theme's, resolved at paint time. */
    public Label setColor(Color newColor) {
        Ui.checkUiThread();
        this.color = newColor;
        invalidate();
        return this;
    }

    /**
     * Uses the theme's muted text color, resolved at paint time; unlike an
     * explicit {@link #setColor}, it follows runtime theme switches.
     */
    public Label setMuted(boolean newMuted) {
        Ui.checkUiThread();
        this.muted = newMuted;
        invalidate();
        return this;
    }

    /** Bold weight on top of the effective font; follows runtime theme switches. */
    public Label setStrong(boolean newStrong) {
        Ui.checkUiThread();
        if (strong == newStrong) {
            return this;
        }
        this.strong = newStrong;
        memoBase = null;
        markNeedsLayout();
        return this;
    }

    /** Italic style on top of the effective font; follows runtime theme switches. */
    public Label setItalic(boolean newItalic) {
        Ui.checkUiThread();
        if (emphasis == newItalic) {
            return this;
        }
        this.emphasis = newItalic;
        memoBase = null;
        markNeedsLayout();
        return this;
    }

    /** What happens to text wider than the box when {@linkplain #setWrap wrap} is off. */
    public Label setOverflow(Overflow newOverflow) {
        Ui.checkUiThread();
        this.overflow = newOverflow;
        markNeedsLayout();
        return this;
    }

    /** Alignment of the text block inside the laid-out box. */
    public Label setAlign(HAlign horizontal, VAlign vertical) {
        Ui.checkUiThread();
        this.hAlign = horizontal;
        this.vAlign = vertical;
        invalidate();
        return this;
    }

    /**
     * Breaks text across lines at spaces, hard-breaking words wider than the box.
     * The measured height then depends on the width constraint. UI thread only.
     */
    public Label setWrap(boolean newWrap) {
        Ui.checkUiThread();
        this.wrap = newWrap;
        markNeedsLayout();
        return this;
    }

    /** Sets a leading icon, tinted to the text color. {@code null} clears it. */
    public Label setIcon(limn.graphics.Icon newIcon) {
        Ui.checkUiThread();
        this.icon = newIcon;
        markNeedsLayout();
        return this;
    }

    /**
     * The font actually drawn with, {@code ==}-stable across passes so the backend's
     * identity-keyed glyph cache hits. Takes the row rather than resolving it so a
     * measure and the paint that follows can never disagree about the step.
     */
    private Font effectiveFont(SizeTokens t) {
        Font base = font != null ? font : switch (role) {
            case BODY -> t.body();
            case LABEL -> t.label();
            case TITLE -> t.title();
        };
        if (base == memoBase) {
            return memoDerived;
        }
        Font derived = base;
        if (strong) {
            derived = derived.bold();
        }
        if (emphasis) {
            derived = derived.italic();
        }
        memoBase = base;
        memoDerived = derived;
        return derived;
    }

    /**
     * The icon square. Plain body text takes the tabled box, which is even at every step
     * so the icon centres on the pixel grid; an explicit {@link #setFont} (or a role with
     * no box of its own) falls back to the formula that box was derived from, so the icon
     * tracks the text it labels instead of the step. Icon glyphs carry no
     * ascender/descender slack, hence the optical bump.
     *
     * <p>Both branches can exceed the line box, and are allowed to: the difference is paid as
     * an overhang, never as a taller row (see the class note). Only the width still counts
     * the full square, because there it displaces text rather than the row above.
     */
    private float iconBox(SizeTokens t, Font f) {
        return font == null && role == Role.BODY
                ? t.iconBox()
                : textRuler().measure("Hg", f).height() + Strokes.ICON_OPTICAL_BUMP;
    }

    /** Top of the first line's box, the one anchor paint and {@link #baselineOffset} share. */
    private float textTop(float lineHeight) {
        // Before the first layout the list is empty; every later pass leaves at least one
        // line in it, so the clamp only guards a baseline read on an unlaid-out Label.
        float contentHeight = Math.max(1, lines.size()) * lineHeight;
        return switch (vAlign) {
            case TOP -> 0;
            case CENTER -> (height() - contentHeight) / 2;
            case BOTTOM -> height() - contentHeight;
        };
    }

    @Override
    protected float baselineOffset() {
        SizeTokens t = Theme.current().tokensFor(this);
        TextMetrics metrics = textRuler().measure("Hg", effectiveFont(t));
        return textTop(metrics.lineHeight()) + metrics.ascent();
    }

    /**
     * The icon's optical bump, the only ink a Label puts outside its bounds. Undeclared,
     * partial rendering would both under-damage the bump on {@link #invalidate()} and cull the
     * Label out of a repaint pass that covers only the overhanging sliver, either way leaving
     * a stale crescent of the old glyph on screen.
     */
    @Override
    protected float paintOutset() {
        return iconOverhang;
    }

    /** @return the lines as they will be painted (post-ellipsis/wrap) */
    List<String> displayedLines() {
        return lines;
    }

    /** Test seam: the font a pass would draw with, resolved exactly the way a pass does. */
    Font resolvedFont() {
        return effectiveFont(Theme.current().tokensFor(this));
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.current().tokensFor(this);
        TextRuler ruler = textRuler();
        Font f = effectiveFont(t);
        TextMetrics line = ruler.measure("Hg", f);
        float iconSpace = icon == null ? 0 : iconBox(t, f) + t.gapLabel();
        // The row is the line box whether or not there is an icon. The icon square is
        // deliberately larger and overhangs (class note); letting it drive the height here is
        // what made a mixed form column ragged.
        if (!wrap) {
            float width = ruler.measure(text.get(), f).width() + iconSpace;
            return constraints.constrain(width, line.lineHeight());
        }
        List<String> wrapped = new ArrayList<>();
        float widest = wrapText(text.get(), constraints.maxWidth() - iconSpace, ruler, f, wrapped);
        // wrapText always emits at least one line (empty text included), so the line count is
        // the whole height: no floor, which is what kept the icon out of the wrap branch too.
        return constraints.constrain(widest + iconSpace, wrapped.size() * line.lineHeight());
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.current().tokensFor(this);
        TextRuler ruler = textRuler();
        Font f = effectiveFont(t);
        float iconBox = icon == null ? 0 : iconBox(t, f);
        // Recorded for paintOutset: the square is centred on height(), so it reaches this far
        // past both edges. Negative when the box fits (a stretched Label, or a wrapped one);
        // clamped, since an outset is a distance and never shrinks the damage rect.
        iconOverhang = Math.max(0, (iconBox - height()) / 2);
        float textWidth = textWidth(t, f);
        if (wrap) {
            lines.clear();
            wrapText(text.get(), textWidth, ruler, f, lines);
            return;
        }
        buildLine(ruler, f, textWidth);
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.current();
        SizeTokens t = theme.tokensFor(this);
        Font f = effectiveFont(t);
        Color ink = !isEnabled() ? theme.disabledText
                : color != null ? color
                : muted ? theme.textMuted : theme.text;
        TextRuler ruler = textRuler();
        TextMetrics metrics = ruler.measure("Hg", f);
        float lineHeight = metrics.lineHeight();
        float top = textTop(lineHeight);
        float textLeft = 0;
        if (icon != null) {
            float iconBox = iconBox(t, f);
            float iconTop = (height() - iconBox) / 2; // negative: the bump overhangs
            // Painted BEFORE the text clip is installed, under a clip of its own: the text clip
            // is the box, and nesting inside it would shave exactly the overhang paintOutset()
            // promises to repaint. The icon's clip is its own square: vertically it lets the
            // bump through, horizontally it still stops at width(), so a Label squeezed
            // narrower than its icon cannot smear the glyph across its neighbour.
            // In a finally: Icon is an interface an application implements, so this is the one
            // place in a Label where foreign code paints.
            canvas.save();
            try {
                canvas.clipRect(0, iconTop, width(), iconBox);
                icon.paint(canvas, 0, iconTop, iconBox, ink, theme.dark);
            } finally {
                canvas.restore();
            }
            textLeft = iconBox + t.gapLabel();
        }
        // Always clip: even ELLIPSIS can exceed the box (a widget narrower
        // than the ellipsis itself), and text must never bleed on neighbors.
        canvas.save();
        canvas.clipRect(0, 0, width(), height());
        float textWidth = Math.max(0, width() - textLeft);
        float baseline = top + metrics.ascent();
        for (String line : lines) {
            // Only CENTER/END need the line width; for the default START, skip the
            // per-line measure (a native glyph scan otherwise thrown away each frame).
            float x = textLeft + switch (hAlign) {
                case START -> 0;
                case CENTER -> (textWidth - ruler.measure(line, f).width()) / 2;
                case END -> textWidth - ruler.measure(line, f).width();
            };
            canvas.drawText(line, x, baseline, f, ink);
            baseline += lineHeight;
        }
        canvas.restore();
    }

    /** Longest prefix (by code points) + "…" that fits {@code available}. */
    static String ellipsize(String text, float available, TextRuler ruler, Font font) {
        float ellipsisWidth = ruler.measure(ELLIPSIS, font).width();
        float budget = available - ellipsisWidth;
        if (budget <= 0) {
            return ELLIPSIS;
        }
        int codePoints = (int) text.codePoints().count();
        int lo = 0;
        int hi = codePoints;
        while (lo < hi) { // largest prefix with width <= budget
            int mid = (lo + hi + 1) / 2;
            String prefix = text.substring(0, text.offsetByCodePoints(0, mid));
            if (ruler.measure(prefix, font).width() <= budget) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return text.substring(0, text.offsetByCodePoints(0, lo)) + ELLIPSIS;
    }

    /** Greedy word wrap; over-wide words hard-break. @return the widest line */
    static float wrapText(String text, float maxWidth, TextRuler ruler, Font font, List<String> out) {
        float widest = 0;
        if (maxWidth == Constraints.UNBOUNDED_LIMIT || maxWidth <= 0) {
            out.add(text);
            return ruler.measure(text, font).width();
        }
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ", -1)) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (ruler.measure(candidate, font).width() <= maxWidth || line.isEmpty() && word.isEmpty()) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                widest = Math.max(widest, flushLine(line, ruler, font, out));
            }
            // Word alone: hard-break if it exceeds the width by itself.
            String rest = word;
            while (ruler.measure(rest, font).width() > maxWidth) {
                int fit = fitPrefix(rest, maxWidth, ruler, font);
                if (fit == 0) {
                    break; // narrower than one glyph: give up, emit as-is
                }
                String head = rest.substring(0, rest.offsetByCodePoints(0, fit));
                out.add(head);
                widest = Math.max(widest, ruler.measure(head, font).width());
                rest = rest.substring(head.length());
            }
            line.append(rest);
        }
        if (!line.isEmpty() || out.isEmpty()) {
            widest = Math.max(widest, flushLine(line, ruler, font, out));
        }
        return widest;
    }

    private static float flushLine(StringBuilder line, TextRuler ruler, Font font, List<String> out) {
        String value = line.toString();
        out.add(value);
        line.setLength(0);
        return ruler.measure(value, font).width();
    }

    /** Largest code-point prefix of {@code text} that fits {@code available}. */
    private static int fitPrefix(String text, float available, TextRuler ruler, Font font) {
        int codePoints = (int) text.codePoints().count();
        int lo = 0;
        int hi = codePoints;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            String prefix = text.substring(0, text.offsetByCodePoints(0, mid));
            if (ruler.measure(prefix, font).width() <= available) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
