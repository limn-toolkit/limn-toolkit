package limn.components;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.animation.Transition;
import limn.backend.Cursor;
import limn.components.internal.a11y.LeafActionAccessibility;
import limn.concurrent.Ui;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.RoundRect;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.internal.lang.Checks;
import limn.scene.Change;
import limn.scene.Constraints;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import limn.scene.internal.SceneAccess;
import limn.scene.layout.Flex;
import limn.scene.layout.Wrap;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A card: a surface that holds one record, made of parts that are each optional. From the top,
 * a picture (the <em>media</em>), a header of a title over a subtitle with a part before them and a
 * part after them, the content, and a footer of actions at the trailing edge. A part that is not
 * set, or is hidden, takes no room and leaves no gap.
 *
 * <pre>{@code
 * Card invoice = new Card(new Label("Three items, due on Friday.").setWrap(true))
 *         .setTitle("Harbor Lane Supplies")
 *         .setSubtitle("Invoice 1042")
 *         .setHeaderSuffix(new Label("$1,240.00").setStrong(true))
 *         .setFooter(new Button("Approve"));
 * }</pre>
 *
 * <p><b>Looks.</b> {@link Style#ELEVATED}, the default, lifts the card by the theme's
 * {@link Elevation#RAISED} shadow, with a hairline round it on a dark palette, where a shadow on a
 * dark ground does not show; {@link Style#OUTLINED} draws a line round it and no shadow;
 * {@link Style#FILLED} sets it apart by tone alone. {@link Orientation#HORIZONTAL} puts the media
 * in a column at the leading side, beside the header and the content, with the footer across the
 * whole card under both. The media is laid out as {@link MediaFit} says, and may be an
 * {@link Icon}, which the card draws itself, tinted with the accent when it is tintable.
 *
 * <p><b>Height.</b> Given more height than it measures, as cards side by side in a row are, the
 * card gives the extra to its content and keeps its footer at the bottom, so footers line up.
 *
 * <p><b>A card that is clicked.</b> With {@link #onActivate(Runnable)} the card is activated by a
 * click anywhere outside its footer, and by Enter or Space while it holds the keyboard; the
 * footer's actions are clicked as the buttons they are, and a press in the footer between them
 * does nothing. A card with no footer is one click as a whole. The part of the card that is
 * clicked takes the keyboard as one stop, shows the pointer's hover and press over itself alone,
 * and is drawn apart from the footer by a line. It holds no control of its own: a widget that takes
 * the keyboard (a button, a field, a check box) is refused in the media, the header and the
 * content of a card that is activated, with an {@link IllegalArgumentException} from the setter or
 * from {@code onActivate}; one that arrives later, inside a content the application changes, is
 * logged once and answers nothing, and every press and key there stays the card's. Actions go in
 * the footer. A card that is not activated takes any widget in any part.
 *
 * <p><b>To a reader.</b> A card with a title is a group named by its title, whose title is a
 * heading; a card with none is transparent, as a {@link Surface} is, so a chart in a card is the
 * chart. A card that is activated is a button named by its title and described by its subtitle,
 * and what else is inside the part that is clicked is not read: a fact a reader needs from the
 * content (an amount, a state) is said with {@link #setAccessibleDescription(String)} on the card,
 * which lands on that button, as {@link #setAccessibleName(String)} does. With a footer, the
 * button and the footer's actions are held in a group named by the title. An activated card with
 * no title has no name unless the application gives it one.
 *
 * <p><b>Reading right to left</b> the header's leading part, the horizontal media column and the
 * rounded corners of a covering picture are on the right, and the footer's actions sit at the left.
 */
public final class Card extends Widget<Card> {

    /** How the card is set apart from what is around it. */
    public enum Style {
        /** Lifted by the theme's raised shadow: the default. */
        ELEVATED,
        /** A line round it and no shadow. */
        OUTLINED,
        /** A raised tone and no line, no shadow. */
        FILLED
    }

    /** Where the media goes. */
    public enum Orientation {
        /** Media, header, content and footer from the top: the default. */
        VERTICAL,
        /** The media in a column at the leading side, the header and the content beside it. */
        HORIZONTAL
    }

    /** How the media is laid out. */
    public enum MediaFit {
        /**
         * At its own measured size, inside the padding, at the leading edge (vertical) or the top of
         * its column (horizontal): the default. An icon is drawn as a square of twice the step's
         * icon size.
         */
        NATURAL,
        /**
         * As wide as the card inside its padding, its height from the {@linkplain
         * #setMediaAspectRatio aspect ratio} (vertical), or as tall as its column, at most half the
         * card's width (horizontal). An icon is drawn centred on a tint of the accent.
         */
        STRETCH,
        /**
         * As {@link #STRETCH}, out to the card's edges on three sides, its outer corners rounded
         * with the card's own.
         */
        COVER
    }

    private static final System.Logger LOG = System.getLogger(Card.class.getName());
    /** The classes of control already said to have arrived in a card's area, each said once. */
    private static final Set<String> SAID_INERT = ConcurrentHashMap.newKeySet();
    private static volatile boolean saidNameless;

    /** The text colour laid over the area while the pointer is over it, and while it is pressed. */
    private static final float HOVER_VEIL = 0.06f;
    private static final float PRESS_VEIL = 0.11f;
    /** The accent's share in the tint behind an icon that stretches, light and dark. */
    private static final float ICON_TINT_LIGHT = 0.16f;
    private static final float ICON_TINT_DARK = 0.26f;
    /** An icon on its tint takes this share of the tint's shorter side. */
    private static final float ICON_SHARE = 0.4f;

    private final Area area = new Area();
    private final Footer footer = new Footer();

    private @Nullable Widget<?> media;
    private @Nullable Icon mediaIcon;
    private Icon.Mirroring mediaMirroring = Icon.Mirroring.NEVER;
    private @Nullable Widget<?> prefix;
    private @Nullable Widget<?> suffix;
    private @Nullable Widget<?> content;
    private @Nullable I18nString title;
    private @Nullable I18nString subtitle;
    private @Nullable Label titleLabel;
    private @Nullable Label subtitleLabel;

    private Style style = Style.ELEVATED;
    private Orientation orientation = Orientation.VERTICAL;
    private MediaFit mediaFit = MediaFit.NATURAL;
    private float mediaAspect = 16f / 9f;
    private @Nullable Runnable action;
    /** The controls this card put out of reach because they arrived in its area; given back after. */
    private final List<Widget<?>> madeInert = new ArrayList<>();

    // Where the last layout put what the card draws itself: the icon media's box and the top of
    // the footer's band, which is where the area ends and the divider is drawn.
    private float iconX;
    private float iconY;
    private float iconW;
    private float iconH;
    private float bandTop;

    /** An empty card; every part is set afterwards. */
    public Card() {
        add(area);
        add(footer);
    }

    /**
     * A card holding {@code content}, every other part unset.
     *
     * @param content what the card holds
     */
    public Card(Widget<?> content) {
        this();
        setContent(Objects.requireNonNull(content, "content"));
    }

    // ------------------------------------------------------------------------------ parts

    /**
     * Sets the media: a picture, a video, an avatar, any widget. It is laid out in the box
     * {@link MediaFit} gives it and told nothing else, so an {@link ImageView} that should crop is
     * given {@link ImageView.Fit#COVER} by the caller. {@code null} takes it away, and so does an
     * icon set in its place. UI thread only.
     *
     * @param newMedia the media, or {@code null}
     * @return this card
     * @throws IllegalArgumentException when the card is activated and {@code newMedia} takes the
     *                                  keyboard or holds a widget that does
     */
    public Card setMedia(@Nullable Widget<?> newMedia) {
        Ui.checkUiThread();
        refuseControl(newMedia);
        mediaIcon = null;
        media = replace(media, newMedia, 0);
        markNeedsLayout();
        return this;
    }

    /**
     * Sets an icon as the media, drawn by the card as authored. A tintable icon is drawn in the
     * accent. The icon is decoration to a reader, as a button's is. UI thread only.
     *
     * @param icon the icon, or {@code null} to take the media away
     * @return this card
     */
    public Card setMedia(@Nullable Icon icon) {
        return setMedia(icon, Icon.Mirroring.NEVER);
    }

    /**
     * Sets an icon as the media, saying whether its drawing turns around when the card reads right
     * to left. UI thread only.
     *
     * @param icon      the icon, or {@code null} to take the media away
     * @param mirroring {@link Icon.Mirroring#NEVER} unless the glyph means a direction
     * @return this card
     */
    public Card setMedia(@Nullable Icon icon, Icon.Mirroring mirroring) {
        Ui.checkUiThread();
        Objects.requireNonNull(mirroring, "mirroring");
        media = replace(media, null, 0);
        mediaIcon = icon;
        mediaMirroring = mirroring;
        markNeedsLayout();
        return this;
    }

    /** @return the media widget, or {@code null} when there is none or the media is an icon */
    public @Nullable Widget<?> media() {
        return media;
    }

    /** @return the icon drawn as the media, or {@code null} */
    public @Nullable Icon mediaIcon() {
        return mediaIcon;
    }

    /**
     * Sets the part before the title, spanning its two lines: an avatar, an icon. UI thread only.
     *
     * @param newPrefix the part, or {@code null} to take it away
     * @return this card
     * @throws IllegalArgumentException when the card is activated and the part takes the keyboard
     *                                  or holds a widget that does
     */
    public Card setHeaderPrefix(@Nullable Widget<?> newPrefix) {
        Ui.checkUiThread();
        refuseControl(newPrefix);
        prefix = replace(prefix, newPrefix, 1);
        markNeedsLayout();
        return this;
    }

    /** @return the part before the title, or {@code null} */
    public @Nullable Widget<?> headerPrefix() {
        return prefix;
    }

    /**
     * Sets a fixed title: one line, strong, ending with an ellipsis where it does not fit.
     *
     * @param newTitle the title, or {@code null} to take it away
     * @return this card
     */
    public Card setTitle(@Nullable String newTitle) {
        return setTitle(newTitle == null ? null : I18nString.literal(newTitle));
    }

    /**
     * Sets a title that follows the UI language. It names the card to a reader. UI thread only.
     *
     * @param newTitle the title, or {@code null} to take it away
     * @return this card
     */
    public Card setTitle(@Nullable I18nString newTitle) {
        Ui.checkUiThread();
        if (Objects.equals(title, newTitle)) {
            return this;
        }
        title = newTitle;
        if (newTitle == null) {
            titleLabel = (Label) replace(titleLabel, null, 2);
        } else if (titleLabel == null) {
            titleLabel = (Label) replace(null, line(newTitle).setStrong(true), 2);
        } else {
            titleLabel.setText(newTitle);
        }
        markNeedsLayout();
        notifyChange(Change.of(Change.Aspect.NAME, Change.Origin.CODE));
        return this;
    }

    /** @return the title, resolved in this card's own language, or {@code null} */
    public @Nullable String title() {
        return title == null ? null : title.get();
    }

    /**
     * Sets a fixed subtitle: one line, muted, under the title.
     *
     * @param newSubtitle the subtitle, or {@code null} to take it away
     * @return this card
     */
    public Card setSubtitle(@Nullable String newSubtitle) {
        return setSubtitle(newSubtitle == null ? null : I18nString.literal(newSubtitle));
    }

    /**
     * Sets a subtitle that follows the UI language. Without a title it is drawn where the title
     * would be, muted, and names nothing. An activated card's subtitle is its description to a
     * reader. UI thread only.
     *
     * @param newSubtitle the subtitle, or {@code null} to take it away
     * @return this card
     */
    public Card setSubtitle(@Nullable I18nString newSubtitle) {
        Ui.checkUiThread();
        if (Objects.equals(subtitle, newSubtitle)) {
            return this;
        }
        subtitle = newSubtitle;
        if (newSubtitle == null) {
            subtitleLabel = (Label) replace(subtitleLabel, null, 3);
        } else if (subtitleLabel == null) {
            subtitleLabel = (Label) replace(null, line(newSubtitle).setMuted(true), 3);
        } else {
            subtitleLabel.setText(newSubtitle);
        }
        markNeedsLayout();
        notifyChange(Change.of(Change.Aspect.DESCRIPTION, Change.Origin.CODE));
        return this;
    }

    /** @return the subtitle, resolved in this card's own language, or {@code null} */
    public @Nullable String subtitle() {
        return subtitle == null ? null : subtitle.get();
    }

    /**
     * Sets the part after the title, spanning its two lines: a badge, an amount. UI thread only.
     *
     * @param newSuffix the part, or {@code null} to take it away
     * @return this card
     * @throws IllegalArgumentException when the card is activated and the part takes the keyboard
     *                                  or holds a widget that does
     */
    public Card setHeaderSuffix(@Nullable Widget<?> newSuffix) {
        Ui.checkUiThread();
        refuseControl(newSuffix);
        suffix = replace(suffix, newSuffix, 4);
        markNeedsLayout();
        return this;
    }

    /** @return the part after the title, or {@code null} */
    public @Nullable Widget<?> headerSuffix() {
        return suffix;
    }

    /**
     * Sets what the card holds under its header: one widget, a column for several. It takes the
     * height the card is given beyond what it measures. UI thread only.
     *
     * @param newContent the content, or {@code null} to take it away
     * @return this card
     * @throws IllegalArgumentException when the card is activated and the content takes the
     *                                  keyboard or holds a widget that does
     */
    public Card setContent(@Nullable Widget<?> newContent) {
        Ui.checkUiThread();
        refuseControl(newContent);
        content = replace(content, newContent, 5);
        markNeedsLayout();
        return this;
    }

    /** @return the content, or {@code null} */
    public @Nullable Widget<?> content() {
        return content;
    }

    /**
     * Sets the footer's actions, which sit at the card's trailing edge and wrap to another line on
     * a narrow card. Nothing takes the footer away. Of an activated card, the footer is the part
     * a click does not activate. UI thread only.
     *
     * @param actions the actions, in reading order
     * @return this card
     */
    public Card setFooter(Widget<?>... actions) {
        Ui.checkUiThread();
        Objects.requireNonNull(actions, "actions");
        for (Widget<?> each : actions) {
            Objects.requireNonNull(each, "an action");
        }
        boolean had = !footer.children().isEmpty();
        for (Widget<?> old : new ArrayList<>(footer.children())) {
            footer.remove(old);
        }
        for (Widget<?> each : actions) {
            footer.add(each);
        }
        markNeedsLayout();
        if (had != (actions.length > 0)) {
            // The published tree changes shape: an activated card turns from a button into a
            // group holding it, or back.
            notifyChange(Change.of(Change.Aspect.CHILDREN, Change.Origin.CODE));
        }
        return this;
    }

    /** @return the footer's actions, in reading order; read only */
    public List<Widget<?>> footer() {
        return Collections.unmodifiableList(new ArrayList<>(footer.children()));
    }

    /** Whether the footer shows an action: one hidden takes no room, and neither does a footer of them. */
    private boolean hasFooter() {
        List<Widget<?>> actions = footer.children();
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).isVisible()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a part is there to lay out: set, and not hidden, since a hidden part takes no room. */
    private static boolean shown(@Nullable Widget<?> part) {
        return part != null && part.isVisible();
    }

    /** A one-line label of the header. */
    private static Label line(I18nString text) {
        return new Label(text).setWrap(false).setOverflow(Label.Overflow.ELLIPSIS);
    }

    /**
     * Puts {@code next} in the area in place of {@code old}, at the place its part takes in the
     * area's order (media, prefix, title, subtitle, suffix, content), which is the order Tab and a
     * reader take them in. A control the card put out of reach goes back to the application as it
     * was.
     */
    private @Nullable Widget<?> replace(@Nullable Widget<?> old, @Nullable Widget<?> next, int slot) {
        if (old == next) {
            return next;
        }
        if (old != null) {
            area.drop(old);
            giveBack(old);
        }
        if (next != null) {
            if (next.parent() != null) {
                throw new IllegalArgumentException("the widget is already in another container");
            }
            int at = 0;
            Widget<?>[] order = {media, prefix, titleLabel, subtitleLabel, suffix, content};
            for (int i = 0; i < slot; i++) {
                if (order[i] != null && order[i] != old) {
                    at++;
                }
            }
            area.hold(at, next);
        }
        return next;
    }

    // ------------------------------------------------------------------------------ looks

    /**
     * Sets how the card is set apart (default {@link Style#ELEVATED}). UI thread only.
     *
     * @param newStyle the style
     * @return this card
     */
    public Card setStyle(Style newStyle) {
        Ui.checkUiThread();
        Objects.requireNonNull(newStyle, "style");
        if (style != newStyle) {
            invalidate(); // with the reach it had, which may be the larger
            style = newStyle;
            invalidate();
        }
        return this;
    }

    /** @return how the card is set apart */
    public Style style() {
        return style;
    }

    /**
     * Sets where the media goes (default {@link Orientation#VERTICAL}). UI thread only.
     *
     * @param newOrientation the orientation
     * @return this card
     */
    public Card setOrientation(Orientation newOrientation) {
        Ui.checkUiThread();
        orientation = Objects.requireNonNull(newOrientation, "orientation");
        markNeedsLayout();
        return this;
    }

    /** @return where the media goes */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * Sets how the media is laid out (default {@link MediaFit#NATURAL}). UI thread only.
     *
     * @param fit the fit
     * @return this card
     */
    public Card setMediaFit(MediaFit fit) {
        Ui.checkUiThread();
        mediaFit = Objects.requireNonNull(fit, "fit");
        markNeedsLayout();
        return this;
    }

    /** @return how the media is laid out */
    public MediaFit mediaFit() {
        return mediaFit;
    }

    /**
     * Sets the media's width over its height where it stretches across a vertical card
     * ({@link MediaFit#STRETCH} and {@link MediaFit#COVER}); 16 to 9 by default. UI thread only.
     *
     * @param widthOverHeight the ratio, above zero
     * @return this card
     * @throws IllegalArgumentException when not above zero or not finite
     */
    public Card setMediaAspectRatio(float widthOverHeight) {
        Ui.checkUiThread();
        if (!(Checks.finite(widthOverHeight, "aspect ratio") > 0)) {
            throw new IllegalArgumentException("aspect ratio must be above zero, got " + widthOverHeight);
        }
        mediaAspect = widthOverHeight;
        markNeedsLayout();
        return this;
    }

    /** @return the media's width over its height where it stretches across a vertical card */
    public float mediaAspectRatio() {
        return mediaAspect;
    }

    // ------------------------------------------------------------------------------ activation

    /**
     * Makes the card activated by a click outside its footer, by Enter or Space while it holds the
     * keyboard, and by a reader's press; {@code null} makes it a plain card again. A press is
     * announced to the {@linkplain #observeChanges watchers} as {@code INVOKED} first. Not called
     * while the card is disabled. UI thread only.
     *
     * @param handler the handler, or {@code null} to clear the slot
     * @return this card
     * @throws IllegalStateException    if a handler is already registered
     * @throws IllegalArgumentException when the media, the header or the content holds a widget
     *                                  that takes the keyboard
     */
    public Card onActivate(@Nullable Runnable handler) {
        Ui.checkUiThread();
        if (handler != null && action == null) {
            for (Widget<?> part : new Widget<?>[] {media, prefix, suffix, content}) {
                Widget<?> control = part == null ? null : firstControl(part);
                if (control != null) {
                    throw refused(control);
                }
            }
        }
        Runnable before = action;
        action = Checks.handlerSlot(action, handler, "Card.onActivate");
        if ((before == null) != (action == null)) {
            area.activatable(action != null);
            if (action == null) {
                for (Widget<?> each : new ArrayList<>(madeInert)) {
                    giveBack(each);
                }
            }
            invalidate();
            markNeedsLayout();
        }
        return this;
    }

    /** @return whether the card is activated by a click: whether {@link #onActivate} holds a handler */
    public boolean isActivatable() {
        return action != null;
    }

    @Override
    protected void handleUserChange(Change.Aspect aspect) {
        if (aspect == Change.Aspect.INVOKED) {
            if (action != null) {
                action.run();
            }
            return;
        }
        super.handleUserChange(aspect);
    }

    /** The user activated the card: the watchers hear {@code INVOKED} and then the handler runs. */
    private void invoke() {
        notifyChange(Change.of(Change.Aspect.INVOKED, Change.Origin.USER));
    }

    private void refuseControl(@Nullable Widget<?> part) {
        if (action != null && part != null) {
            Widget<?> control = firstControl(part);
            if (control != null) {
                throw refused(control);
            }
        }
    }

    private static IllegalArgumentException refused(Widget<?> control) {
        return new IllegalArgumentException("a Card activated by a click holds no control outside its "
                + "footer, and a " + control.getClass().getName() + " takes the keyboard: put it in the "
                + "footer, or leave the card without onActivate");
    }

    /** The first widget at or under {@code at} that takes the keyboard, in tree order, or {@code null}. */
    private static @Nullable Widget<?> firstControl(Widget<?> at) {
        if (at.isFocusable()) {
            return at;
        }
        List<Widget<?>> children = at.children();
        for (int i = 0; i < children.size(); i++) {
            Widget<?> found = firstControl(children.get(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Puts out of reach every control that arrived in the area of an activated card since the last
     * layout: drawn as it is, answering nothing, so every press, key and reader's verb there stays
     * the card's. Said once per class, for the developer who put it there.
     */
    private void quietControlsInArea() {
        for (Widget<?> part : new Widget<?>[] {media, prefix, suffix, content}) {
            if (part != null) {
                quietControls(part);
            }
        }
    }

    private void quietControls(Widget<?> at) {
        if (at.isFocusable() && !madeInert.contains(at)) {
            madeInert.add(at);
            SceneAccess.setInert(at, true);
            if (SAID_INERT.add(at.getClass().getName())) {
                LOG.log(System.Logger.Level.WARNING, "a " + at.getClass().getName() + " arrived in the "
                        + "area of a Card activated by a click, and answers nothing there: put it in "
                        + "the footer, or leave the card without onActivate");
            }
            return;
        }
        List<Widget<?>> children = at.children();
        for (int i = 0; i < children.size(); i++) {
            quietControls(children.get(i));
        }
    }

    /** Gives back every control this card put out of reach at or under {@code at}. */
    private void giveBack(Widget<?> at) {
        for (int i = madeInert.size() - 1; i >= 0; i--) {
            Widget<?> each = madeInert.get(i);
            for (Widget<?> w = each; w != null; w = w.parent()) {
                if (w == at) {
                    madeInert.remove(i);
                    SceneAccess.setInert(each, false);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ layout

    /** Where a pass puts the parts: one routine, so the measure and the layout cannot disagree. */
    private final class Arrangement {
        float width;
        float height;
        float areaHeight;
        float footerY;
        float footerHeight;
        // Each part's box, in the card's own space, reading left to right; mirrored on placing.
        final float[] mediaBox = new float[4];
        final float[] prefixBox = new float[4];
        final float[] titleBox = new float[4];
        final float[] subtitleBox = new float[4];
        final float[] suffixBox = new float[4];
        final float[] contentBox = new float[4];
        boolean iconMedia;
    }

    /**
     * Two, so that a measure asked between a layout and the area's own layout, as a parent asking
     * again does, cannot move the parts the layout placed: the measure works in its own.
     */
    private final Arrangement measured = new Arrangement();
    private final Arrangement arrangement = new Arrangement();

    private boolean hasMedia() {
        return shown(media) || mediaIcon != null;
    }

    private boolean hasHeader() {
        return shown(prefix) || titleLabel != null || subtitleLabel != null || shown(suffix);
    }

    /** The widest the parts are asked to be, inside the padding, for a card {@code width} wide. */
    private static Constraints loose(float width) {
        return Constraints.loose(Math.max(0, width), Constraints.UNBOUNDED_LIMIT);
    }

    /**
     * The width the card asks for under a bound of {@code limit}: its widest part plus its
     * padding, each part asked at most the room the bound leaves, as a column's children are.
     */
    private float naturalWidth(SizeTokens t, float limit) {
        float pad = t.cardPad();
        float gap = t.spacingMedium();
        float inner = limit - 2 * pad;
        float widest = 0;
        float mediaWidth = 0;
        if (shown(media)) {
            mediaWidth = media.measure(loose(inner)).width();
        } else if (mediaIcon != null) {
            mediaWidth = mediaFit == MediaFit.NATURAL ? 2 * t.iconBox() : 4 * t.iconBox();
        }
        float header = 0;
        if (hasHeader()) {
            float prefixWidth = prefix == null ? 0 : prefix.measure(loose(inner)).width() + gap;
            float suffixWidth = suffix == null ? 0 : suffix.measure(loose(inner)).width() + gap;
            float text = Math.max(
                    titleLabel == null ? 0 : titleLabel.measure(loose(inner)).width(),
                    subtitleLabel == null ? 0 : subtitleLabel.measure(loose(inner)).width());
            header = prefixWidth + text + suffixWidth;
        }
        float body = Math.max(header, content == null ? 0 : content.measure(loose(inner)).width());
        if (orientation == Orientation.HORIZONTAL && hasMedia()) {
            widest = mediaWidth + gap + body;
        } else {
            widest = Math.max(mediaWidth, body);
        }
        if (hasFooter()) {
            widest = Math.max(widest, footer.measure(loose(inner)).width());
        }
        return widest + 2 * pad;
    }

    @Override
    protected Size onMeasure(Constraints constraints) {
        SizeTokens t = Theme.of(this).tokensFor(this);
        float limit = constraints.hasBoundedWidth() ? constraints.maxWidth() : Constraints.UNBOUNDED_LIMIT;
        float width = constraints.constrainWidth(Math.min(limit, naturalWidth(t, limit)));
        arrange(t, width, Float.NaN, measured);
        return constraints.constrain(width, measured.height);
    }

    /**
     * Works out where every part goes in a card {@code width} wide, and {@code height} tall, or
     * as tall as it measures when {@code height} is {@code NaN}: the extra height goes to the
     * content, and the footer stays at the bottom.
     */
    private void arrange(SizeTokens t, float width, float height, Arrangement out) {
        float pad = t.cardPad();
        float gap = t.spacingMedium();
        float inner = Math.max(0, width - 2 * pad);
        out.width = width;
        out.iconMedia = !shown(media) && mediaIcon != null;
        clear(out.mediaBox, out.prefixBox, out.titleBox, out.subtitleBox, out.suffixBox, out.contentBox);
        boolean horizontal = orientation == Orientation.HORIZONTAL && hasMedia();
        boolean cover = hasMedia() && mediaFit == MediaFit.COVER;

        // The text column: the header and the content, under the media or beside it.
        float columnX = pad;
        float columnWidth = inner;
        float mediaColumn = 0;
        if (horizontal) {
            mediaColumn = mediaColumnWidth(t, width, inner);
            columnX = cover ? mediaColumn + pad : pad + mediaColumn + gap;
            columnWidth = Math.max(0, width - columnX - pad);
        }

        float y = 0;
        boolean coverLast = false;
        if (hasMedia() && !horizontal) {
            float[] box = out.mediaBox;
            if (mediaFit == MediaFit.NATURAL) {
                Size size = shown(media) ? media.measure(loose(inner)) : new Size(2 * t.iconBox(), 2 * t.iconBox());
                set(box, pad, pad, Math.min(inner, size.width()), size.height());
            } else if (mediaFit == MediaFit.STRETCH) {
                set(box, pad, pad, inner, inner / mediaAspect);
            } else {
                set(box, 0, 0, width, width / mediaAspect);
            }
            y = box[1] + box[3];
            coverLast = cover;
        }
        if (horizontal) {
            y = 0; // the text column starts at the top, beside the media
        }
        if (hasHeader()) {
            float top = y == 0 ? pad : y + (coverLast ? pad : gap);
            float prefixWidth = 0;
            float prefixHeight = 0;
            if (shown(prefix)) {
                Size size = prefix.measure(loose(columnWidth));
                prefixWidth = size.width();
                prefixHeight = size.height();
            }
            float suffixWidth = 0;
            float suffixHeight = 0;
            if (shown(suffix)) {
                Size size = suffix.measure(loose(columnWidth));
                suffixWidth = size.width();
                suffixHeight = size.height();
            }
            float textX = columnX + (shown(prefix) ? prefixWidth + gap : 0);
            float textWidth = Math.max(0, columnX + columnWidth - (shown(suffix) ? suffixWidth + gap : 0) - textX);
            float titleHeight = titleLabel == null ? 0 : titleLabel.measure(loose(textWidth)).height();
            float subtitleHeight = subtitleLabel == null ? 0 : subtitleLabel.measure(loose(textWidth)).height();
            float text = titleHeight + subtitleHeight;
            float band = Math.max(text, Math.max(prefixHeight, suffixHeight));
            if (shown(prefix)) {
                set(out.prefixBox, columnX, top + (band - prefixHeight) / 2, prefixWidth, prefixHeight);
            }
            if (shown(suffix)) {
                set(out.suffixBox, columnX + columnWidth - suffixWidth, top + (band - suffixHeight) / 2,
                        suffixWidth, suffixHeight);
            }
            float textTop = top + (band - text) / 2;
            if (titleLabel != null) {
                set(out.titleBox, textX, textTop, textWidth, titleHeight);
            }
            if (subtitleLabel != null) {
                set(out.subtitleBox, textX, textTop + titleHeight, textWidth, subtitleHeight);
            }
            y = top + band;
            coverLast = false;
        }
        float contentTop = 0;
        float contentHeight = 0;
        if (shown(content)) {
            contentTop = y == 0 ? pad : y + (coverLast ? pad : gap);
            contentHeight = content.measure(loose(columnWidth)).height();
            y = contentTop + contentHeight;
            coverLast = false;
        }
        // The media column beside the text, as tall as what it stands beside.
        float mediaMinimum = 0;
        if (horizontal) {
            if (mediaFit == MediaFit.NATURAL) {
                Size size = shown(media) ? media.measure(loose(mediaColumn)) : new Size(2 * t.iconBox(), 2 * t.iconBox());
                mediaMinimum = size.height() + 2 * pad;
            } else if (shown(media)) {
                // A picture keeps its own proportions at the column's width, at the least; it
                // grows with the text beside it, cropped as its own fit says.
                Size natural = media.measure(loose(Constraints.UNBOUNDED_LIMIT));
                float ratio = natural.width() > 0 ? natural.height() / natural.width() : 0;
                mediaMinimum = mediaColumn * ratio + (cover ? 0 : 2 * pad);
            } else {
                mediaMinimum = mediaColumn + (cover ? 0 : 2 * pad);
            }
        }
        // Where the area ends as measured: the last part and the padding below it, unless the last
        // part is a picture that covers the card's bottom edge itself.
        float areaBottom;
        if (horizontal) {
            areaBottom = Math.max(y == 0 ? 0 : y + pad, mediaMinimum);
        } else {
            areaBottom = y == 0 ? 0 : (coverLast && !hasFooter() ? y : y + pad);
        }
        float natural;
        float footerHeight = 0;
        if (hasFooter()) {
            footerHeight = footer.measure(loose(inner)).height();
            float footerTop = areaBottom == 0 ? pad : areaBottom - pad + gap;
            natural = footerTop + footerHeight + pad;
        } else {
            natural = Math.max(areaBottom, 2 * pad);
        }
        float total = Float.isNaN(height) ? natural : Math.max(height, 0);
        float extra = Math.max(0, total - natural);
        if (shown(content)) {
            set(out.contentBox, columnX, contentTop, columnWidth, contentHeight + extra);
        }
        out.height = total;
        out.footerHeight = footerHeight;
        if (hasFooter()) {
            out.footerY = total - pad - footerHeight;
            out.areaHeight = Math.max(0, out.footerY - gap / 2);
        } else {
            out.footerY = total;
            out.areaHeight = total;
        }
        if (horizontal) {
            float[] box = out.mediaBox;
            if (mediaFit == MediaFit.NATURAL) {
                Size size = shown(media) ? media.measure(loose(mediaColumn)) : new Size(2 * t.iconBox(), 2 * t.iconBox());
                set(box, pad, pad, Math.min(mediaColumn, size.width()), size.height());
            } else if (mediaFit == MediaFit.STRETCH) {
                set(box, pad, pad, mediaColumn, Math.max(0, out.areaHeight - 2 * pad));
            } else {
                set(box, 0, 0, mediaColumn, out.areaHeight);
            }
        }
    }

    /**
     * The width of a horizontal card's media column: the media's own at its natural fit, and a
     * third of the card at most when it stretches or covers, which leaves the text the larger
     * share; an icon that stretches takes four of its own size.
     */
    private float mediaColumnWidth(SizeTokens t, float width, float inner) {
        float third = width / 3;
        if (shown(media)) {
            if (mediaFit == MediaFit.NATURAL) {
                return Math.min(inner / 2, media.measure(loose(inner / 2)).width());
            }
            return Math.min(third, media.measure(loose(Constraints.UNBOUNDED_LIMIT)).width());
        }
        float icon = mediaFit == MediaFit.NATURAL ? 2 * t.iconBox() : 4 * t.iconBox();
        return Math.min(icon, third);
    }

    private static void clear(float[]... boxes) {
        for (float[] box : boxes) {
            java.util.Arrays.fill(box, 0);
        }
    }

    private static void set(float[] box, float x, float y, float w, float h) {
        box[0] = x;
        box[1] = y;
        box[2] = Math.max(0, w);
        box[3] = Math.max(0, h);
    }

    @Override
    protected void onLayout() {
        SizeTokens t = Theme.of(this).tokensFor(this);
        arrange(t, width(), height(), arrangement);
        Arrangement a = arrangement;
        bandTop = a.areaHeight;
        // Asked at the card's width and any height, the area answers the height its parts need:
        // a part that changes its size inside it then changes the area's, and a layout in place
        // climbs past it to the card, which is what decides where everything goes.
        area.measure(new Constraints(width(), width(), 0, Constraints.UNBOUNDED_LIMIT));
        area.layoutBox(0, 0, width(), a.areaHeight);
        float pad = t.cardPad();
        float inner = Math.max(0, width() - 2 * pad);
        footer.measure(Constraints.loose(inner, Constraints.UNBOUNDED_LIMIT));
        footer.layoutBox(pad, a.footerY, inner, a.footerHeight);
        if (action != null) {
            quietControlsInArea();
        }
    }

    /**
     * Lays the area's parts out for the card as it is now; called by the area's own layout, which
     * may run without the card's when nothing about the card's box changed.
     */
    private void placeParts() {
        arrange(Theme.of(this).tokensFor(this), width(), height(), arrangement);
        Arrangement a = arrangement;
        boolean rtl = isRightToLeft();
        place(media, a.mediaBox, rtl);
        place(prefix, a.prefixBox, rtl);
        place(titleLabel, a.titleBox, rtl);
        place(subtitleLabel, a.subtitleBox, rtl);
        place(suffix, a.suffixBox, rtl);
        place(content, a.contentBox, rtl);
        if (a.iconMedia) {
            float[] box = a.mediaBox;
            iconX = rtl ? a.width - box[0] - box[2] : box[0];
            iconY = box[1];
            iconW = box[2];
            iconH = box[3];
        } else {
            iconW = 0;
            iconH = 0;
        }
    }

    private void place(@Nullable Widget<?> part, float[] box, boolean rtl) {
        if (part == null) {
            return;
        }
        float x = rtl ? arrangement.width - box[0] - box[2] : box[0];
        part.measure(Constraints.tight(box[2], box[3]));
        part.layoutBox(x, box[1], box[2], box[3]);
    }

    // ------------------------------------------------------------------------------ paint

    /** The card's own rounded outline. */
    private RoundRect shape(SizeTokens t) {
        return RoundRect.of(0, 0, width(), height(), t.radiusMedium());
    }

    @Override
    protected float paintOutset() {
        float reach = style == Style.ELEVATED ? Theme.of(this).elevation(Elevation.RAISED).reach() : 0;
        if (action != null && !hasFooter()) {
            reach = Math.max(reach, Strokes.FOCUS_RING_OUTSET); // the ring round the whole card
        }
        return reach;
    }

    /**
     * The fill and the shadow are background to the parts, and say nothing of themselves: a card
     * with no title and no handler is not in the accessible tree.
     *
     * @return {@code true}, always
     */
    @Override
    protected boolean paintsDecoration() {
        return true;
    }

    @Override
    protected void onPaint(Canvas canvas) {
        Theme theme = Theme.of(this);
        SizeTokens t = theme.tokensFor(this);
        RoundRect shape = shape(t);
        switch (style) {
            case ELEVATED -> {
                theme.drawElevation(canvas, shape, Elevation.RAISED);
                canvas.fillRoundRect(shape, theme.surface());
            }
            case OUTLINED -> canvas.fillRoundRectInsideBorder(0, 0, width(), height(), t.radiusMedium(),
                    Strokes.BORDER, theme.surface());
            case FILLED -> canvas.fillRoundRect(shape, theme.surfaceRaised());
        }
    }

    /** The edge, over a picture that covers the card, and the line above an activated card's footer. */
    @Override
    protected void onPaintOverlay(Canvas canvas) {
        Theme theme = Theme.of(this);
        SizeTokens t = theme.tokensFor(this);
        if (style == Style.OUTLINED || (style == Style.ELEVATED && theme.isDark())) {
            // A dark palette's shadow is black on near black and does not show: a hairline does.
            canvas.drawRoundRectBorder(0, 0, width(), height(), t.radiusMedium(), Strokes.HAIRLINE,
                    theme.outline());
        }
        if (action != null && hasFooter()) {
            canvas.fillRect(0, bandTop, width(), Strokes.HAIRLINE, theme.outline());
        }
    }

    // ------------------------------------------------------------------------ accessibility

    /**
     * A group named by the title, when there is a title and the card is not activated, or when it
     * is activated and has a footer, whose actions the group holds beside the area's button; and
     * nothing otherwise, the card's node being its area's button or none.
     */
    @Override
    protected void onAccessibility(Accessibility a) {
        if (action == null) {
            if (title != null) {
                a.role(Accessible.Role.GROUP);
                a.name(title, Accessible.NameFrom.LABEL);
                a.relation(Accessible.Relation.LABELLED_BY, titleLabel);
            }
        } else if (hasFooter()) {
            a.role(Accessible.Role.GROUP);
            if (title != null) {
                a.name(title, Accessible.NameFrom.LABEL);
            }
            a.relation(Accessible.Relation.LABELLED_BY, area);
        }
    }

    /** The area carries a caption bound to an activated card, and its name and description. */
    @Override
    protected Widget<?> accessibleLabelTarget() {
        return action != null ? area : this;
    }

    // ------------------------------------------------------------------------ for tests

    /** @return the part of the card a click activates; for tests */
    Widget<?> area() {
        return area;
    }

    /** @return the widget that holds the footer's actions; for tests */
    Widget<?> footerBar() {
        return footer;
    }

    /** @return whether the pointer is over the area, as the area was told; for tests */
    boolean isAreaHovered() {
        return area.hovered;
    }

    // ------------------------------------------------------------------------------ the area

    /**
     * The media, the header and the content, and nothing of the footer: one rectangle that is the
     * hit area, the keyboard's stop, the ring, the hover and the reader's button of an activated
     * card, and transparent scaffolding of one that is not.
     */
    private final class Area extends Widget<Area> {

        private final Transition hover =
                new Transition(this).duration(Theme.of(this).animHover).easing(Theme.of(this).animEasing);
        private final Transition focusFade =
                new Transition(this).duration(Theme.of(this).animFocus).easing(Theme.of(this).animEasing);
        private final SceneAccess.HoverWithin hoverObserver = this::hoverChanged;
        boolean hovered;
        private boolean armed;
        private boolean keyArmed;

        void hold(int index, Widget<?> child) {
            add(index, child);
        }

        void drop(Widget<?> child) {
            remove(child);
        }

        /** Takes the keyboard and the pointer's shape, or gives them up, as the card is activated or not. */
        void activatable(boolean on) {
            setFocusable(on);
            setCursor(on ? Cursor.POINTER : null);
            observeHover();
            if (!on) {
                armed = false;
                keyArmed = false;
                hovered = false;
                hover.to(0);
                if (isFocused()) {
                    // Where a removed focus goes: the area is the same widget, but it no longer
                    // takes the keyboard, and the scene takes it out as it does from a subtree
                    // that leaves.
                    SceneAccess.setInert(this, true);
                    SceneAccess.setInert(this, false);
                }
            }
        }

        private void observeHover() {
            SceneAccess.observeHoverWithin(this, scene() != null && action != null ? hoverObserver : null);
        }

        private void hoverChanged(boolean inside) {
            hovered = inside;
            hover.to(inside ? 1 : 0);
            if (!inside && armed) {
                invalidate(); // the press is drawn only while the pointer is over the area
            }
        }

        @Override
        protected void onAttached() {
            super.onAttached();
            observeHover();
        }

        @Override
        protected void onDetached() {
            super.onDetached();
            SceneAccess.observeHoverWithin(this, null);
            hovered = false;
            armed = false;
            hover.to(0);
        }

        /** The height the parts need at the width it is given; the card lays it out taller when it is. */
        @Override
        protected Size onMeasure(Constraints constraints) {
            float width = constraints.maxWidth();
            if (Float.isInfinite(width)) {
                return constraints.constrain(constraints.minWidth(), constraints.minHeight());
            }
            arrange(Theme.of(Card.this).tokensFor(Card.this), width, Float.NaN, measured);
            return constraints.constrain(width, measured.areaHeight);
        }

        /** The fill, the icon and the veil are the card's material; the parts and the button speak. */
        @Override
        protected boolean paintsDecoration() {
            return true;
        }

        @Override
        protected void onLayout() {
            placeParts();
        }

        @Override
        protected float paintOutset() {
            return action != null && !hasFooter() ? Strokes.FOCUS_RING_OUTSET : 0;
        }

        /** The area's outline: the card's corners above, and below too when no footer is under it. */
        private RoundRect outline(SizeTokens t, float inset) {
            float r = Math.max(0, t.radiusMedium() - inset);
            float bottom = hasFooter() ? 0 : r;
            return new RoundRect(inset, inset, Math.max(0, width() - 2 * inset),
                    Math.max(0, height() - 2 * inset), r, r, bottom, bottom);
        }

        @Override
        protected void onPaint(Canvas canvas) {
            if (iconW <= 0 || mediaIcon == null) {
                return;
            }
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(this);
            boolean rtl = isRightToLeft();
            boolean flip = rtl && mediaMirroring == Icon.Mirroring.IN_RTL;
            Color tint = mediaIcon.tintable()
                    ? (isEnabled() ? theme.primary() : theme.disabledText())
                    : null;
            if (mediaFit == MediaFit.NATURAL) {
                mediaIcon.paint(canvas, iconX, iconY, iconW, tint, theme.isDark(), flip);
                return;
            }
            canvas.save();
            try {
                if (mediaFit == MediaFit.COVER) {
                    canvas.clipRoundRect(RoundRect.of(0, 0, Card.this.width(), Card.this.height(),
                            t.radiusMedium()));
                    canvas.fillRect(iconX, iconY, iconW, iconH, backdrop(theme));
                } else {
                    canvas.fillRoundRect(iconX, iconY, iconW, iconH, t.radiusSmall(), backdrop(theme));
                }
            } finally {
                canvas.restore();
            }
            // Rounded to a whole point, so the icon cache sees one key per size and not one per box.
            float side = Math.round(Math.min(iconW, iconH) * ICON_SHARE);
            mediaIcon.paint(canvas, iconX + (iconW - side) / 2, iconY + (iconH - side) / 2, side, tint,
                    theme.isDark(), flip);
        }

        private Color backdrop(Theme theme) {
            return theme.primary().withAlpha(theme.isDark() ? ICON_TINT_DARK : ICON_TINT_LIGHT);
        }

        @Override
        protected boolean clipsChildren() {
            return true;
        }

        /** A covering picture rounds with the card's own corners; nothing else reaches them. */
        @Override
        protected void paintChildren(Canvas canvas) {
            canvas.save();
            try {
                canvas.clipRoundRect(RoundRect.of(0, 0, Card.this.width(), Card.this.height(),
                        Theme.of(this).tokensFor(this).radiusMedium()));
                super.paintChildren(canvas);
            } finally {
                canvas.restore();
            }
        }

        /** The hover and the press over the whole area, the parts included, and the ring round it. */
        @Override
        protected void onPaintOverlay(Canvas canvas) {
            if (action == null) {
                return;
            }
            Theme theme = Theme.of(this);
            SizeTokens t = theme.tokensFor(this);
            float veil = isEnabled()
                    ? ((armed && hovered) || keyArmed ? PRESS_VEIL : HOVER_VEIL * hover.value())
                    : 0;
            if (veil > 0.001f) {
                canvas.fillRoundRect(outline(t, 0), theme.text().withAlpha(veil));
            }
            float focus = focusFade.value();
            if (focus > 0.001f) {
                Color ring = theme.focusRing().withAlpha(focus);
                if (hasFooter()) {
                    // Inside the card's edge and above the line, so it never crosses an action
                    // of the footer: what Enter presses is the area alone.
                    float inset = Strokes.BORDER + Strokes.FOCUS_RING / 2;
                    canvas.drawRoundRect(outline(t, inset), Strokes.FOCUS_RING, ring);
                } else {
                    // Outside the card, concentric with it, as a button's.
                    float gap = Strokes.FOCUS_GAP_BUTTON;
                    canvas.drawRoundRect(-gap, -gap, width() + 2 * gap, height() + 2 * gap,
                            t.radiusMedium() + gap, Strokes.FOCUS_RING, ring);
                }
            }
        }

        /**
         * An activated card's button: named by the title, described by the subtitle, offering the
         * press a click performs, and with no children, since a leaf is what a reader presses.
         */
        @Override
        protected void onAccessibility(Accessibility a) {
            if (action == null) {
                return;
            }
            a.role(Accessible.Role.BUTTON);
            if (title != null) {
                a.name(title, Accessible.NameFrom.CONTENT);
            } else if (!saidNameless && !SceneAccess.namedByApplication(Card.this)) {
                saidNameless = true;
                LOG.log(System.Logger.Level.WARNING, "a Card activated by a click has no title and no "
                        + "name, so a reader is told nothing of it: give it a title, or setAccessibleName");
            }
            if (subtitle != null) {
                a.description(subtitle);
            }
            LeafActionAccessibility.describe(a, isEnabled());
        }

        /**
         * The parts of an activated card are not read, the card's button being a leaf; of a card
         * that is not, the title is a heading.
         */
        @Override
        protected void onAccessibilityChild(Widget<?> child, Accessibility a) {
            if (action != null) {
                a.ignore();
            } else if (child == titleLabel) {
                a.role(Accessible.Role.HEADING);
            }
        }

        @Override
        protected boolean onAccessibilityAction(Accessible.Action verb, Accessible.Argument arg) {
            if (action == null) {
                return false;
            }
            return LeafActionAccessibility.perform(leafHost, verb);
        }

        private final LeafActionAccessibility.Host leafHost = new LeafActionAccessibility.Host() {
            @Override
            public boolean acceptsPress() {
                return isEnabled() && action != null;
            }

            @Override
            public void press() {
                invoke();
            }
        };

        /**
         * A press that nothing inside the area took arms the card, and its release over the area
         * activates it, wherever in the area it was pressed: a press on the title released on the
         * picture is one click on the card. Released outside, it does nothing.
         */
        @Override
        protected void onMouseEvent(MouseEvent event) {
            if (action == null || event.button() != Keys.MOUSE_LEFT) {
                return;
            }
            switch (event.type()) {
                case PRESS -> {
                    armed = true;
                    invalidate();
                    event.consume();
                }
                case RELEASE -> {
                    if (!armed) {
                        return;
                    }
                    armed = false;
                    invalidate();
                    event.consume();
                    limn.math.Vec2 at = sceneToLocal(event.x(), event.y());
                    if (at.x() >= 0 && at.y() >= 0 && at.x() < width() && at.y() < height()) {
                        invoke();
                    }
                }
                default -> {
                }
            }
        }

        /** Enter and Space on the area itself, on their release, as a button's; never a key from inside. */
        @Override
        protected void onKeyEvent(KeyEvent event) {
            if (action == null || !isFocused() || (event.key() != Keys.ENTER && event.key() != Keys.SPACE)) {
                return;
            }
            if (event.isPressed() && !event.isRepeat()) {
                keyArmed = true;
                invalidate();
                event.consume();
            } else if (!event.isPressed()) {
                boolean fire = keyArmed;
                keyArmed = false;
                invalidate();
                event.consume();
                if (fire) {
                    invoke();
                }
            }
        }

        @Override
        protected void onFocusGained() {
            focusFade.to(1);
        }

        @Override
        protected void onFocusLost() {
            focusFade.to(0);
            armed = false;
            keyArmed = false;
            invalidate();
        }
    }

    // ------------------------------------------------------------------------------ the footer

    /**
     * The footer's actions, at the trailing edge, wrapping to another line on a narrow card; its
     * gutters are the dialog's between buttons and the card's between lines, at the card's step.
     */
    private static final class Footer extends Wrap {
        Footer() {
            mainAlignment(Flex.MainAlignment.END);
            crossAlignment(Flex.CrossAlignment.CENTER);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            SizeTokens t = Theme.of(this).tokensFor(this);
            gapsSilently(t.gapButtonRow(), t.spacingMedium());
            return super.onMeasure(constraints);
        }
    }
}
