package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.components.chart.ChartPalette;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.Image;
import limn.graphics.RoundRect;
import limn.graphics.ShapedText;
import limn.graphics.TextMetrics;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.internal.LocaleServices;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A person, drawn as a circle: their picture when there is one, and otherwise their initials over a
 * colour of their own. An assignee in a table's column, the user on a tool bar, a contact's row.
 *
 * <p><b>The size</b> is the control height of the step (32 points at {@code MEDIUM}), so an avatar
 * sits in a row of controls at their height; give it a {@link limn.scene.layout.SizedBox} for a
 * larger one, and the circle fills the smaller side of the box it is given. A box squeezed to a
 * point or so across draws the tinted circle without its letters, and one of no size draws nothing.
 *
 * <p><b>The colour</b> is one of the chart palette's categorical colours
 * ({@link ChartPalette#defaultFor(Theme)}), chosen by the name's {@link String#hashCode()}, mixed, unless
 * {@link #setColorIndex} says which, so the same name has the same colour on every run and every
 * machine. Categorical, and never a state's tone: a person drawn in the danger colour reads as an
 * error. It is drawn as a tint, at 22 % (34 % in a dark palette), with the initials in the
 * palette's text colour, so their contrast is the palette's own text on its surface.
 *
 * <p><b>The initials</b>, unless {@link #setInitials} gives them, are the first grapheme of the
 * name's first word and of its last, upper-cased in the avatar's locale: "Ana Lima" is "AL", "Ana"
 * is "A", an accent or a joined emoji is kept whole, and a script without case stays as it is.
 * With neither initials nor a picture, a silhouette of a head and shoulders is drawn.
 *
 * <p><b>To a reader an avatar is an image named by the person</b>, so one standing alone (a user
 * button's picture, a column of owners) is read. Beside the name it repeats, as in a contact's
 * row, take it out with {@code setAccessibleIgnored(true)}. Inside an {@link AvatarGroup} it is
 * not published at all: the group is one node described by every name.
 *
 * <pre>{@code
 * new Avatar("Ana Lima");
 * new Avatar("Rui Costa").setImage(photo);
 * }</pre>
 */
public final class Avatar extends Widget<Avatar> {

    /** One extended grapheme cluster: an accent with its letter, a joined emoji whole. */
    private static final Pattern GRAPHEME = Pattern.compile("\\X");

    private I18nString name;
    private @Nullable String initials;
    private @Nullable Image image;
    private int colorIndex = -1;
    // Set by an AvatarGroup: a ring of the surface colour around the circle, so an avatar
    // overlapping the one before reads as stacked on it rather than cut into it.
    private boolean ringed;
    // The group this avatar is a member of, shown or hidden: a hidden member has no parent, and its
    // name is still in the group's description and the count's tooltip.
    @Nullable AvatarGroup group;

    // The initials as drawn, derived once per name, locale and override, and their shaping.
    private @Nullable String shownInitials;
    private @Nullable String shownFor;
    private @Nullable Locale shownIn;
    private @Nullable ShapedText line;

    /**
     * An avatar for a person with a fixed name.
     *
     * @param name the person's name; never null
     */
    public Avatar(String name) {
        this(I18nString.literal(Objects.requireNonNull(name, "name")));
    }

    /**
     * An avatar whose name follows the UI language, for a name that is a role ("Support").
     *
     * @param name the person's name; never null
     */
    public Avatar(I18nString name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /**
     * Replaces the name: the initials, the colour and what a reader hears follow it.
     *
     * @param newName the name; never null
     * @return this avatar
     */
    public Avatar setName(String newName) {
        return setName(I18nString.literal(Objects.requireNonNull(newName, "newName")));
    }

    /**
     * Replaces the name with one that follows the UI language.
     *
     * @param newName the name; never null
     * @return this avatar
     */
    public Avatar setName(I18nString newName) {
        Ui.checkUiThread();
        Objects.requireNonNull(newName, "newName");
        if (name.equals(newName)) {
            return this;
        }
        this.name = newName;
        invalidate(); // the same circle: initials and colour are paint
        notifyChange(Change.of(Change.Aspect.NAME, Change.Origin.CODE));
        if (group != null) {
            group.memberRenamed();
        }
        return this;
    }

    /** @return the person's name, in the language in effect */
    public String name() {
        return name.get();
    }

    /** The name as held, for a group that describes its members by reference. */
    I18nString nameSource() {
        return name;
    }

    /**
     * Gives the initials instead of deriving them from the name; {@code null} derives them again.
     *
     * @param value the initials, drawn as given; or {@code null}
     * @return this avatar
     */
    public Avatar setInitials(@Nullable String value) {
        Ui.checkUiThread();
        if (Objects.equals(initials, value)) {
            return this;
        }
        this.initials = value;
        invalidate();
        if (group != null) {
            group.memberDrawnAnew(); // the group's one size for initials may move
        }
        return this;
    }

    /**
     * Sets the person's picture, drawn covering the circle and clipped to it; {@code null} brings
     * the initials back. An animated image is drawn at its first frame.
     *
     * @param newImage the picture, or {@code null}
     * @return this avatar
     */
    public Avatar setImage(@Nullable Image newImage) {
        Ui.checkUiThread();
        if (image == newImage) {
            return this;
        }
        this.image = newImage;
        invalidate();
        if (group != null) {
            group.memberDrawnAnew();
        }
        return this;
    }

    /** @return the picture, or {@code null} */
    public @Nullable Image image() {
        return image;
    }

    /**
     * Chooses the palette colour by its index instead of by the name, for two people whose names
     * fall on the same colour side by side. Indexes past the palette's size repeat it.
     *
     * @param index the index, at least zero
     * @return this avatar
     * @throws IllegalArgumentException if {@code index} is negative
     */
    public Avatar setColorIndex(int index) {
        Ui.checkUiThread();
        if (index < 0) {
            throw new IllegalArgumentException("a palette index is at least zero: " + index);
        }
        if (colorIndex == index) {
            return this;
        }
        this.colorIndex = index;
        invalidate();
        return this;
    }

    /**
     * The palette index the avatar is drawn in: the one given, or the name's hash modulo the
     * palette's size.
     *
     * @return the index
     */
    public int colorIndex() {
        if (colorIndex >= 0) {
            return colorIndex;
        }
        return colorIndexOf(name.get(), ChartPalette.defaultFor(Theme.of(this)).size());
    }

    /**
     * The palette index a name falls on: its {@link String#hashCode()}, mixed, scaled to the
     * palette's size. Mixed, because the hash alone spreads names badly over eight colours: its multiplier,
     * 31, is minus one modulo eight, so a name's index was little more than an alternating sum of
     * its letters, and five of seven names in the Showcase's approvers fell on the same green. The
     * same name falls on the same colour on every run and every machine all the same.
     *
     * @param name a person's name
     * @param size the palette's size
     * @return the index, in {@code [0, size)}
     */
    static int colorIndexOf(String name, int size) {
        int h = name.hashCode();
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        // Scaled from the top bits, which the mixing spreads best, rather than taken modulo.
        return (int) (((h & 0xFFFFFFFFL) * size) >>> 32);
    }

    void setRinged(boolean value) {
        if (ringed != value) {
            this.ringed = value;
            invalidate();
        }
    }

    /**
     * The initials of {@code name}: the first grapheme of the first word and of the last, each
     * upper-cased in {@code locale}; one word gives one. Package-private for the tests that pin
     * each script.
     *
     * @param name   the person's name
     * @param locale the locale whose case rules apply
     * @return the initials, empty for a blank name
     */
    static String initialsOf(String name, Locale locale) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] words = trimmed.split("\\s+");
        String first = LocaleServices.upperCase(firstGrapheme(words[0]), locale);
        if (words.length == 1) {
            return first;
        }
        return first + LocaleServices.upperCase(firstGrapheme(words[words.length - 1]), locale);
    }

    private static String firstGrapheme(String word) {
        Matcher m = GRAPHEME.matcher(word);
        return m.lookingAt() ? m.group() : word;
    }

    /**
     * The initials drawn: the ones given, or derived once per name and locale. The name is read in
     * this avatar's own language whoever asks, so a group working out its one size from every
     * member's letters gets the same letters from each of its circles.
     */
    String shownInitials() {
        if (initials != null) {
            return initials;
        }
        Locale locale = locale();
        String value;
        Locale enclosing = I18n.pushScope(locale);
        try {
            value = name.get();
        } finally {
            I18n.popScope(enclosing);
        }
        if (shownInitials == null || !value.equals(shownFor) || !locale.equals(shownIn)) {
            shownInitials = initialsOf(value, locale);
            shownFor = value;
            shownIn = locale;
        }
        return shownInitials;
    }

    /** How wide the ring an avatar in a group draws around itself is, past its box. */
    static final float RING = 2;

    @Override
    protected float paintOutset() {
        return ringed ? RING : 0;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        float d = Theme.of(this).tokensFor(this).controlHeight();
        return constraints.constrain(d, d);
    }

    /**
     * The font a circle {@code d} across draws its initials in, alone: the label font, strong,
     * grown with the circle, so a SizedBox twice as large draws initials twice as large, where a
     * fixed size would sit lost in the middle of it; {@code null} for a circle a point or so across,
     * which would ask for letters of no size at all, which no font has (the tinted circle alone, a
     * dot at that size).
     *
     * @param circle the widget drawing the circle, whose theme and control size it takes
     * @param d      the circle's diameter
     * @return the font, or {@code null} for no letters
     */
    static @Nullable Font initialsFont(Widget<?> circle, float d) {
        SizeTokens t = Theme.of(circle).tokensFor(circle);
        Font font = t.label().bold();
        float scale = d / t.controlHeight();
        if (scale != 1) {
            int size = Math.round(font.size() * scale);
            if (size < 1) {
                return null;
            }
            font = font.withSize(size);
        }
        return font;
    }

    /** The circle's diameter: the smaller side of the box, which a SizedBox may make larger. */
    private float diameter() {
        return Math.min(width(), height());
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        float d = diameter();
        if (!(d > 0)) {
            return; // a box squeezed to nothing has no circle to draw
        }
        float x = (width() - d) / 2;
        float y = (height() - d) / 2;
        float r = d / 2;
        if (ringed) {
            canvas.fillCircle(x + r, y + r, r + RING, theme.surface());
        }
        if (image != null) {
            paintImage(canvas, image, x, y, d);
            return;
        }
        Color colour = ChartPalette.defaultFor(theme).color(colorIndex());
        canvas.fillCircle(x + r, y + r, r, colour.withAlpha(colour.a() * (theme.isDark() ? 0.34f : 0.22f)));
        String letters = shownInitials();
        if (letters.isEmpty()) {
            paintSilhouette(canvas, theme, x, y, d);
            return;
        }
        Font font = initialsFont(this, d);
        if (font == null) {
            return;
        }
        AvatarGroup g = parent() instanceof AvatarGroup in ? in : null;
        if (g != null) {
            // One size for the whole group, the size its widest pair needs to clear the overlap,
            // so the stack reads even; the last circle takes it too.
            font = g.initialsFont(font, d, textRuler());
            if (font == null) {
                return;
            }
        }
        ShapedText.Direction base = ShapedText.Direction.of(letters, neutralBase());
        ShapedText shaped = line;
        if (shaped == null || !shaped.matches(letters, font, base, textRuler())) {
            shaped = textRuler().shape(letters, font, base);
            line = shaped;
        }
        TextMetrics m = shaped.metrics();
        // Where the letters go: the middle of the circle, or, under the next circle of a group,
        // the middle of the part it leaves, three quarters less the ring, from the circle's edge at
        // the letters' height on the near side, the side away from the overlap in the group's
        // direction. "AL" lost the foot of its L when centred on the whole circle.
        float from = x;
        float to = x + d;
        float shown = g == null ? 0 : g.shownPart(this, d);
        if (shown > 0) {
            float edge = AvatarGroup.edgeAt(r, m.height());
            boolean rtl = g.isRightToLeft(); // the group's: it is the group that lays the circles out
            from = rtl ? x + d - shown : x + edge;
            to = rtl ? x + d - edge : x + shown;
        }
        canvas.drawText(shaped, (from + to - m.width()) / 2, y + textRuler().centredBaseline(font, d),
                theme.text());
    }

    /** The picture covering the circle: its middle square, scaled to the diameter, clipped round. */
    private static void paintImage(Canvas canvas, Image picture, float x, float y, float d) {
        int w = picture.width();
        int h = picture.height();
        float side = Math.min(w, h);
        canvas.save();
        canvas.clipRoundRect(RoundRect.of(x, y, d, d, d / 2));
        canvas.drawImage(picture, (w - side) / 2, (h - side) / 2, side, side, x, y, d, d);
        canvas.restore();
    }

    /**
     * A head and shoulders in the muted text colour, cut by the circle, for a person with neither
     * a picture nor letters to show: drawn by the toolkit as its own glyphs are, at any size.
     */
    private static void paintSilhouette(Canvas canvas, Theme theme, float x, float y, float d) {
        Color ink = theme.textMuted();
        float cx = x + d / 2;
        canvas.save();
        canvas.clipRoundRect(RoundRect.of(x, y, d, d, d / 2));
        canvas.fillCircle(cx, y + d * 0.38f, d * 0.19f, ink);
        canvas.fillEllipse(cx, y + d * 0.98f, d * 0.36f, d * 0.32f, ink);
        canvas.restore();
    }

    /**
     * One {@link Accessible.Role#IMAGE} named by the person. The name is the held string by
     * reference, so a frame that repaints the avatar compares it without allocating.
     *
     * <p>Its provenance is {@link Accessible.NameFrom#EXPLICIT}, not content: the circle paints
     * initials or a picture and never the name, so the name is text about the image, which macOS
     * publishes as {@code AXDescription}, the attribute VoiceOver reads for a picture.
     *
     * @param a the node being described
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        a.role(Accessible.Role.IMAGE);
        a.name(name, Accessible.NameFrom.EXPLICIT);
    }
}
