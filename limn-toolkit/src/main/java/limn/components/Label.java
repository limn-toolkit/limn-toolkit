package limn.components;

import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.graphics.TextRuler;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Single- or multi-line text. Without {@link #setWrap wrap}, text that does
 * not fit is cut with a real-measured ellipsis ({@code …}), never a blind
 * character count. With wrap, lines break greedily at the opportunities
 * {@link BreakIterator} finds under the {@linkplain I18n#locale() UI language}, so
 * unspaced CJK and dictionary-segmented Thai wrap through the same walk as prose.
 * Horizontal and vertical alignment supported.
 * Colors come from the {@link Theme}; typography comes from the resolved
 * {@link limn.scene.ControlSize} step, picked by {@link Role}.
 *
 * <p><b>Geometry comes from one shaping of the paragraph, never from prefix widths.</b>
 * The width of the first N characters of a string is not the width of anything on screen
 * once a shaper joins, ligates or reorders, so every question this class asks about where
 * to cut is asked of a {@link ShapedText}: {@link ShapedText#fitEnd} for the budget and
 * {@link ShapedText#advanceTo} for what a candidate costs. The paragraph shaping does not
 * depend on the box, so a measure and the layout that follows share it; only the per-line
 * shapings are width-dependent, and each painted line is re-shaped because
 * {@code advanceTo} is a budget and not a promise about a substring &mdash; the forms on
 * both sides of a cut change when the cut is made.
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
 *
 * <p><b>Reading right to left, the icon and the text swap ends and nothing else moves.</b>
 * {@link HAlign#START} and {@link HAlign#END} name where reading starts and ends, so they turn
 * around with the paragraph; {@link HAlign#CENTER} is a centre in either direction and is the one
 * arm of that decision that is the same number. The icon square is
 * {@linkplain #setIcon(limn.graphics.Icon) leading}, which is the left edge in one direction and
 * the right edge in the other, and the glyph inside it turns around only when the call that
 * placed it said it should &mdash; see {@link limn.graphics.Icon.Mirroring}.
 */
public class Label extends Widget {

    public enum Overflow { CLIP, ELLIPSIS }

    /**
     * Where a line sits in the text region, on two vocabularies that coexist on purpose.
     *
     * <p>{@link #START} and {@link #END} are <b>logical</b>: they name where reading starts and
     * ends, so they turn around with {@link limn.scene.LayoutDirection}. That is what a label
     * almost always wants, because a label is text and text has a reading order.
     *
     * <p>{@link #LEFT} and {@link #RIGHT} are <b>physical</b>: they name a side of the region and
     * keep naming it whichever way the subtree reads. Reach for one when the alignment is about
     * the box rather than about the reading order &mdash; a column of figures that must stay
     * flush with the column beside it, a caption pinned under one corner of a picture.
     *
     * <p>{@link #CENTER} is the same number in both vocabularies and in both directions.
     */
    public enum HAlign {
        /** Against the edge reading starts from. */
        START,
        /** Centred in the text region. */
        CENTER,
        /** Against the edge reading ends on. */
        END,
        /** Against the region's left edge, whichever way the subtree reads. */
        LEFT,
        /** Against the region's right edge, whichever way the subtree reads. */
        RIGHT
    }

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
    // What the icon MEANS, not where it goes: its side is this widget's decision and turns around
    // with the paragraph, while the glyph inside the square turns around only when the call that
    // placed it said it should. NEVER by default, which is what keeps every existing setIcon call
    // drawing exactly what it drew.
    private limn.graphics.Icon.Mirroring iconMirroring = limn.graphics.Icon.Mirroring.NEVER;
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

    // ONE shaping of text.get(), width-independent, so a measure pass and the layout pass that
    // follows it shape the paragraph once between them. Refreshed through ShapedText.matches,
    // whose identity fast path on the string costs nothing here because I18nString memoizes it.
    private ShapedText paragraph;

    // computed at layout. Shaped, not String: alignment reads metrics().width() off the value it
    // is about to draw instead of re-measuring it per line per frame, and a wrapped line's width
    // is the width of the line AS SHAPED, which is not the width of that range of the paragraph.
    private final List<ShapedText> lines = new ArrayList<>();
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
            // Compared as SHAPED widths, because that is the number onMeasure would produce.
            // Asking measure() instead would be a second opinion on the same question, and the
            // one case where the two disagree — a face that kerns across a cluster seam — is a
            // box called unchanged that the paint then overflows.
            float was = paragraph(ruler, f).metrics().width();
            this.text = newText;
            if (paragraph(ruler, f).metrics().width() == was) {
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

    /**
     * The paragraph as one shaped line: the value every geometry question here is asked of.
     * Re-shaped only when {@link ShapedText#matches} says the held one is no longer the answer,
     * which is the whole invalidation test — the text, the font, the paragraph direction, and the
     * ruler epoch that moves when a face is evicted or the default family changes.
     *
     * <p>The base is resolved here, once, and handed to both halves of that test: asking whether
     * the held value is current and shaping a replacement have to be asking about the same
     * direction, or the check passes for a value the shape call would not have produced.
     */
    private ShapedText paragraph(TextRuler ruler, Font f) {
        String value = text.get();
        ShapedText.Direction base = ShapedText.Direction.of(value, neutralBase());
        if (paragraph == null || !paragraph.matches(value, f, base, ruler)) {
            paragraph = ruler.shape(value, f, base);
        }
        return paragraph;
    }

    /**
     * What a string with no strong character of its own falls back to: this label's own resolved
     * layout direction. A bare {@code "42"}, a phone number or a run of punctuation in an Arabic
     * form reads right to left however many Latin digits it starts with, and the first-strong rule
     * cannot know that; the surrounding interface can. A Latin string in that same form still
     * reads left to right, because the fallback is consulted only where no strong character has
     * an opinion.
     *
     * <p>Resolved here on every call rather than held, and never in a constructor: a label
     * captures no direction, because a direction captured before it has a parent is permanently
     * wrong with no path to recovery.
     */
    private ShapedText.Direction neutralBase() {
        return isRtl() ? ShapedText.Direction.RTL : ShapedText.Direction.LTR;
    }

    /** Whether this label reads right to left. Resolve it once per pass, into a local. */
    private boolean isRtl() {
        return layoutDirection() == LayoutDirection.RTL;
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
        ShapedText shown = paragraph(ruler, f);
        if (overflow == Overflow.ELLIPSIS && shown.metrics().width() > textWidth) {
            shown = ellipsize(shown, textWidth, ruler);
        }
        lines.add(shown);
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
     * Breaks text across lines at the opportunities {@link BreakIterator} reports for the
     * {@linkplain I18n#locale() UI language}, greedily; a run that offers no opportunity at all
     * — a word longer than the box, or a script this locale has no rule for — is cut at the last
     * cluster that fits. The measured height then depends on the width constraint. UI thread only.
     */
    public Label setWrap(boolean newWrap) {
        Ui.checkUiThread();
        this.wrap = newWrap;
        markNeedsLayout();
        return this;
    }

    /**
     * Sets a leading icon, tinted to the text color, drawn as authored whichever way this label
     * reads. {@code null} clears it. <em>Leading</em> is the side reading starts on: the left edge
     * left to right, the right edge right to left.
     */
    public Label setIcon(limn.graphics.Icon newIcon) {
        return setIcon(newIcon, limn.graphics.Icon.Mirroring.NEVER);
    }

    /**
     * Sets a leading icon and says whether its glyph turns around when the interface does. The
     * icon's <em>position</em> is leading either way and is this label's decision; whether the
     * drawing inside that square is a back arrow (which turns around) or a logo, a download arrow
     * or a photograph (which must not) is knowable only at the call that placed it, which is why
     * it is a parameter here and not a table anywhere.
     *
     * @param newIcon   the icon, or {@code null} to clear it
     * @param mirroring {@link limn.graphics.Icon.Mirroring#NEVER} unless this glyph is
     *                  directional; a wrong {@code NEVER} is one arrow pointing the wrong way,
     *                  and a wrong {@code IN_RTL} is a flipped brand mark
     */
    public Label setIcon(limn.graphics.Icon newIcon, limn.graphics.Icon.Mirroring mirroring) {
        Ui.checkUiThread();
        Objects.requireNonNull(mirroring, "mirroring");
        this.icon = newIcon;
        this.iconMirroring = mirroring;
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
        return lines.stream().map(ShapedText::text).toList();
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
            float width = paragraph(ruler, f).metrics().width() + iconSpace;
            return constraints.constrain(width, line.lineHeight());
        }
        List<ShapedText> wrapped = new ArrayList<>();
        float widest = wrapText(paragraph(ruler, f), constraints.maxWidth() - iconSpace,
                ruler, wrapped);
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
            wrapText(paragraph(ruler, f), textWidth, ruler, lines);
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
        // Resolved ONCE for the whole pass. The icon's side, the text region's left edge and the
        // alignment offset inside that region are three answers to one question, and two
        // resolutions that disagreed inside one paint would draw the text under the icon.
        boolean rtl = isRtl();
        float iconBox = icon == null ? 0 : iconBox(t, f);
        float iconSpace = icon == null ? 0 : iconBox + t.gapLabel();
        // Physical left edge of the text region. The icon gutter is on the side reading starts
        // on, so left to right the text starts past it, and right to left the gutter is at the
        // far end and the text region already begins at the box's own left edge. Only one of the
        // two moves, and it is the icon.
        float textLeft = rtl ? 0 : iconSpace;
        if (icon != null) {
            float iconTop = (height() - iconBox) / 2; // negative: the bump overhangs
            // The leading edge written as a coordinate: 0 reading left to right, and the far edge
            // less the square reading right to left.
            float iconLeft = rtl ? width() - iconBox : 0;
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
                // The flag says what the icon MEANS and the axis says which way this label reads;
                // neither alone is the answer, and the flip itself is Icon.paint's, about the
                // square it was just handed.
                icon.paint(canvas, iconLeft, iconTop, iconBox, ink, theme.dark,
                        iconMirroring == limn.graphics.Icon.Mirroring.IN_RTL && rtl);
            } finally {
                canvas.restore();
            }
        }
        // Always clip: even ELLIPSIS can exceed the box (a widget narrower
        // than the ellipsis itself), and text must never bleed on neighbors.
        canvas.save();
        canvas.clipRect(0, 0, width(), height());
        // A magnitude, not an x: the icon claims the same room from either end, so this is the
        // same number in both directions and only the edge it is measured from moved.
        float textWidth = Math.max(0, width() - iconSpace);
        float baseline = top + metrics.ascent();
        for (int i = 0; i < lines.size(); i++) {
            ShapedText line = lines.get(i);
            // The one place a paint may shape. A line held since the last layout can outlive the
            // ruler that produced it — a face evicted, the default family switched — and drawing
            // it then hands the backend glyph ids naming a face that is gone. Stale is not broken
            // (the backend falls back to the characters), but the check is one comparison and
            // removes the whole class, and no relayout is owed for a change that only re-resolved
            // a face to the same metrics.
            // The direction asked about is the line's own: the wrap pass decided it and a
            // change of direction re-wraps, so a line that survived to here was shaped for the
            // direction the paragraph still reads in.
            if (!line.matches(line.text(), f, line.baseDirection(), ruler)) {
                line = ruler.shape(line.text(), f, line.baseDirection());
                lines.set(i, line);
            }
            // The offset of this line inside the text region, added to the region's own left
            // edge. START and END are logical — where reading starts and where it ends — so they
            // trade places when the paragraph does; CENTER is a centre either way and is the one
            // arm that is the same number in both directions.
            // Each arm takes the width off the value about to be drawn, which is what deleted the
            // per-line, per-frame ruler.measure that used to sit here.
            float x = textLeft + switch (hAlign) {
                case START -> rtl ? textWidth - line.metrics().width() : 0;
                case CENTER -> (textWidth - line.metrics().width()) / 2;
                case END -> rtl ? 0 : textWidth - line.metrics().width();
                // The two physical arms, which is why they are arms and not a branch on rtl:
                // a side of the region is one expression, and the direction never reaches it.
                case LEFT -> 0;
                case RIGHT -> textWidth - line.metrics().width();
            };
            canvas.drawText(line, x, baseline, ink);
            baseline += lineHeight;
        }
        canvas.restore();
    }

    /**
     * {@code line} cut in <b>logical</b> order to fit {@code available}, with a real-measured
     * ellipsis appended and the whole thing re-shaped so it draws in <b>visual</b> order.
     *
     * <p>The cut and the draw are two different orders and the split is the point. The characters
     * dropped are the logically last ones, whatever end of the line they were drawn at; the
     * ellipsis is then logically last, so in a right-to-left paragraph it renders on the visual
     * <em>left</em>. There is no branch for that anywhere here, and there must not be one: it
     * falls out of shaping the concatenation instead of positioning two pieces.
     *
     * <p><b>The base direction travels with every re-shape here</b>, taken from {@code line}
     * itself because this is static and has no widget to ask. It has to: {@code …} is wholly
     * neutral and a cut prefix can be too, so re-shaping either without the base lets the
     * first-strong rule fall back to left to right and measures the budget in one direction while
     * the paragraph is read in the other.
     *
     * @param line      the shaped whole line, wider than {@code available}; its
     *                  {@linkplain ShapedText#baseDirection() base} is the one the cut is shaped
     *                  for
     * @param available room the text gets, in logical points
     * @param ruler     the ruler that shaped {@code line} and re-shapes the cut
     * @return the line as it will be drawn; the lone ellipsis when nothing fits beside it
     */
    static ShapedText ellipsize(ShapedText line, float available, TextRuler ruler) {
        Font font = line.font();
        ShapedText.Direction base = line.baseDirection();
        float ellipsisWidth = ruler.shape(ELLIPSIS, font, base).metrics().width();
        int cut = line.fitEnd(0, available - ellipsisWidth);
        ShapedText shown = ruler.shape(line.text().substring(0, cut) + ELLIPSIS, font, base);
        // fitEnd said WHERE to cut, against the budget of the UNCUT shaping. Re-shaping the kept
        // prefix beside an ellipsis can join, ligate or kern differently and come out a hair
        // wider than the budget promised, and a Label that overflows its box by a hair is a Label
        // whose ellipsis is clipped. Zero or one iteration for Latin; the loop exists for the
        // scripts where a cut changes the forms on both sides of it.
        while (shown.metrics().width() > available && cut > 0) {
            cut = line.caretIndex(line.caretOrdinal(cut) - 1);
            shown = ruler.shape(line.text().substring(0, cut) + ELLIPSIS, font, base);
        }
        return shown;
    }

    /**
     * Greedy line breaking over one shaping of the paragraph, at the opportunities
     * {@link BreakIterator} reports for the {@linkplain I18n#locale() UI language}.
     *
     * <p><b>The locale is the UI language and not {@code ROOT}.</b> Line breaking is
     * locale-sensitive by definition, and the JDK's Thai dictionary is reachable only through a
     * Thai locale: twelve characters of Thai offer two break opportunities under {@code th} and
     * none at all under {@code en}. A Thai UI is exactly when Thai text is on screen, and the string being
     * broken came from an {@code I18nString} resolved under this same locale, so the text and the
     * rule that breaks it change together. CJK needs no locale at all — every ideograph is an
     * opportunity in every locale — which is what retires the old per-code-point hard break as
     * the only thing that could wrap unspaced text.
     *
     * <p><b>A segment that is nothing but whitespace emits no line.</b> Every break opportunity a
     * line has to end at is trimmed, so a segment whose trim empties it carried no ink at all;
     * emitting it would paint a blank row, push every real line down by one, and make
     * {@code onMeasure} report a height one row too tall. Leading whitespace is the case that
     * bites &mdash; {@code " abcdefgh ij"} has an opportunity at index 1 and the segment before it
     * is the lone space &mdash; and it is the case the pre-shaping algorithm handled by accident,
     * because {@code split(" ", -1)} dropped the empty tokens it produced.
     *
     * <p>Always emits at least one line, empty text included; {@code onMeasure} takes the height
     * from the count and has no floor of its own. That floor is why the skip above needs a partner
     * at the end of the walk: a paragraph of pure whitespace skips every segment it has and would
     * otherwise emit nothing.
     *
     * <p><b>Every emitted line is shaped at the PARAGRAPH's base direction</b>, taken from
     * {@code paragraph} because this is static and has no widget to ask. A line re-deriving its
     * own base by the first-strong rule would let a line of digits and punctuation inside a
     * right-to-left paragraph come out left to right and disagree with the paragraph that decided
     * where it broke &mdash; and the drawn width would then be a fraction of a point off the
     * width the break was measured against.
     *
     * @param paragraph the whole text as one shaped line; its
     *                  {@linkplain ShapedText#baseDirection() base} is every emitted line's base
     * @param maxWidth  room per line; {@link Constraints#UNBOUNDED_LIMIT} or non-positive emits
     *                  the paragraph unbroken
     * @param ruler     the ruler that shaped {@code paragraph} and re-shapes each emitted line
     * @param out       destination for the shaped lines, appended in order
     * @return the widest emitted line's width, in logical points
     */
    static float wrapText(ShapedText paragraph, float maxWidth, TextRuler ruler,
            List<ShapedText> out) {
        String text = paragraph.text();
        ShapedText.Direction base = paragraph.baseDirection();
        int length = text.length();
        if (maxWidth == Constraints.UNBOUNDED_LIMIT || maxWidth <= 0) {
            out.add(paragraph);
            return paragraph.metrics().width();
        }
        BreakIterator breaks = BreakIterator.getLineInstance(I18n.locale());
        breaks.setText(text);
        float widest = 0;
        int start = 0;
        // The count this call added, not out.size(): out is appended to, and a caller that passes a
        // list with something already in it would otherwise lose the empty-line floor below.
        int emitted = 0;
        do {
            int fit = paragraph.fitEnd(start, maxWidth);   // the trailing space still counts here
            int end;
            if (fit >= length) {
                end = length;
            } else {
                // The last opportunity at or before the hard cut is always acceptable, because
                // trimming can only make a candidate narrower. Then walk FORWARD while the
                // TRIMMED candidate still fits: that is the trailing space hanging past the
                // margin, and it is what lets a word whose only overflow is the space after it
                // stay on this line. Without it "aaa bbb ccc" in a 75pt box breaks after "aaa",
                // because the space at index 7 — which is never drawn — spent the budget.
                int b = breaks.preceding(fit + 1);
                if (b <= start) {
                    b = start;
                }
                for (int next = breaks.following(b); next != BreakIterator.DONE && next <= length;
                        next = breaks.following(next)) {
                    if (paragraph.advanceTo(trimEnd(text, start, next))
                            - paragraph.advanceTo(start) > maxWidth) {
                        break;
                    }
                    b = next;
                }
                end = b;
                if (end <= start) {
                    // Not one break opportunity fits: a word longer than the line, or a script
                    // this locale has no rule for. Take as many CLUSTERS as fit — one character
                    // per line would be the other reading and it is not a line break, it is a
                    // column — and at least one, so the walk cannot fail to advance.
                    end = Math.max(paragraph.fitEnd(start, maxWidth),
                            paragraph.caretIndex(paragraph.caretOrdinal(start) + 1));
                }
            }
            int cut = trimEnd(text, start, end);
            if (cut == start && end > start) {
                // The whole segment trimmed away: it was whitespace and nothing else, so there is
                // no line here to draw. Emitting one would be a blank row with a real lineHeight in
                // it — every following line pushed down, and a measured height one row too tall.
                // Advance past it exactly as a drawn segment does and take the next.
                start = end;
                continue;
            }
            // Each emitted line is re-shaped from the trimmed range. advanceTo is a budget and
            // not a promise about a substring: joining forms change at the cut, a ligature that
            // spanned it is gone, and so is the kerning at the seam. The paragraph shaping
            // decides WHERE; the line shaping decides what is drawn, at the paragraph's base.
            ShapedText line = start == 0 && cut == length
                    ? paragraph                        // it all fits: nothing to re-shape
                    : ruler.shape(text.substring(start, cut), paragraph.font(), base);
            out.add(line);
            emitted++;
            widest = Math.max(widest, line.metrics().width());
            // end, NOT cut: advancing to the trimmed end would re-offer the whitespace that
            // caused the break as the next line's leading run, and the walk would not move.
            start = end;
        } while (start < length);
        if (emitted == 0) {
            // Every segment was whitespace, which is a paragraph of whitespace: one empty line, the
            // same answer this returned before the skip existed and the one onMeasure's height
            // depends on. Width 0, because an empty line has none — and still shaped at the
            // paragraph's base, because a held line's direction is asked about later even when
            // there is no ink in it to see.
            out.add(ruler.shape("", paragraph.font(), base));
        }
        return widest;
    }

    /**
     * {@code end} with the run of whitespace immediately before it dropped, never below
     * {@code start}. {@link Character#isWhitespace} is exactly the right predicate and was
     * checked: it is false for the non-breaking spaces U+00A0, U+2007 and U+202F, which must
     * never be dropped, and true for U+3000 IDEOGRAPHIC SPACE, which is a break opportunity.
     */
    private static int trimEnd(String text, int start, int end) {
        int cut = end;
        while (cut > start) {
            int cp = text.codePointBefore(cut);
            if (!Character.isWhitespace(cp)) {
                break;
            }
            cut -= Character.charCount(cp);
        }
        return cut;
    }
}
