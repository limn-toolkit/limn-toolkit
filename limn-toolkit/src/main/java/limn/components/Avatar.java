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
 * larger one, and the circle fills the smaller side of the box it is given.
 *
 * <p><b>The colour</b> is one of the chart palette's categorical colours
 * ({@link ChartPalette#defaultFor(Theme)}), chosen by the name's {@link String#hashCode()} unless
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
        return Math.floorMod(name.get().hashCode(), ChartPalette.defaultFor(Theme.of(this)).size());
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

    /** The initials drawn: the ones given, or derived once per name and locale. */
    String shownInitials() {
        if (initials != null) {
            return initials;
        }
        String value = name.get();
        Locale locale = locale();
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

    /** The circle's diameter: the smaller side of the box, which a SizedBox may make larger. */
    private float diameter() {
        return Math.min(width(), height());
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        float d = diameter();
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
        SizeTokens t = theme.tokensFor(this);
        // The label font, strong, grown with the circle: a SizedBox twice as large draws initials
        // twice as large, where a fixed size would sit lost in the middle of it.
        Font font = t.label().bold();
        float scale = d / t.controlHeight();
        if (scale != 1) {
            font = font.withSize(Math.round(font.size() * scale));
        }
        ShapedText shaped = line;
        ShapedText.Direction base = ShapedText.Direction.of(letters, neutralBase());
        if (shaped == null || !shaped.matches(letters, font, base, textRuler())) {
            shaped = textRuler().shape(letters, font, base);
            line = shaped;
        }
        TextMetrics m = shaped.metrics();
        canvas.drawText(shaped, x + (d - m.width()) / 2, y + (d - m.height()) / 2 + m.ascent(),
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
