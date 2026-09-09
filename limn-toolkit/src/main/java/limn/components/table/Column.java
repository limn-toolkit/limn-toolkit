package limn.components.table;

import limn.components.chart.ChartFormats;
import limn.concurrent.Ui;
import limn.i18n.I18nString;
import limn.scene.Widget;

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * One column of a {@link Table}: a title, how a row becomes a cell, how rows compare on it, and
 * how wide it is.
 *
 * <p>A column is built once by the application and handed to the table. Three factories cover
 * the everyday cases &mdash; {@link #text}, {@link #numeric} and {@link #of} with a formatter
 * &mdash; and {@link #widget} is the seam for a cell that is a control rather than a value: a
 * switch, a button, an icon. Everything after the factory is a fluent setter.
 *
 * <p><b>Widths.</b> A column has a preferred width, a minimum, and a weight. Layout gives every
 * column its preferred width and then shares what is left of the viewport among the weighted
 * ones, as {@code Expanded} shares a row's leftover; a table whose preferred widths exceed the
 * viewport scrolls sideways rather than crushing anything below its minimum. A width the user
 * dragged is held here, so it survives a {@link Table#refresh()}.
 *
 * <p><b>Sorting.</b> A header click sorts through {@link #compare}: a comparator the application
 * named, else the values themselves &mdash; numbers numerically, strings through the language's
 * collator (ADR 034), anything else comparable by itself &mdash; else the formatted text. A widget
 * column has no value and is not sortable unless given a comparator.
 *
 * @param <T> the row type
 */
public final class Column<T> {

    /** Where a cell's text sits across its column. */
    public enum Alignment {
        /** The reading start: the left edge left to right, the right edge right to left. */
        START,
        /** Centred. */
        CENTER,
        /** The reading end, where a number belongs. */
        END
    }

    private static final float DEFAULT_WIDTH = 120;
    private static final float DEFAULT_MIN_WIDTH = 40;

    private final I18nString title;
    private final Function<T, Object> value;
    private final BiFunction<Object, Locale, String> format;
    private final Function<T, Widget> widget;
    private Comparator<T> comparator;
    private boolean sortable = true;
    private Alignment alignment = Alignment.START;
    private float preferredWidth = DEFAULT_WIDTH;
    private float minWidth = DEFAULT_MIN_WIDTH;
    private float weight;
    private float draggedWidth = -1;
    private boolean visible = true;

    private Column(I18nString title, Function<T, Object> value,
                   BiFunction<Object, Locale, String> format, Function<T, Widget> widget) {
        this.title = Objects.requireNonNull(title, "title");
        this.value = value;
        this.format = format;
        this.widget = widget;
    }

    /**
     * A column of text.
     *
     * @param title what the header says
     * @param value the text of a row's cell; {@code null} draws an empty cell
     * @param <T>   the row type
     * @return the column
     */
    public static <T> Column<T> text(I18nString title, Function<T, String> value) {
        Objects.requireNonNull(value, "value");
        return new Column<>(title, value::apply, (v, locale) -> v == null ? "" : (String) v, null);
    }

    /**
     * {@link #text(I18nString, Function)} with a literal title, for a header no translation
     * covers.
     *
     * @param title what the header says
     * @param value the text of a row's cell
     * @param <T>   the row type
     * @return the column
     */
    public static <T> Column<T> text(String title, Function<T, String> value) {
        return text(I18nString.literal(title), value);
    }

    /**
     * A column of numbers: aligned to the reading end, formatted and localized as the charts
     * format an axis (ADR 033), and sorted numerically.
     *
     * @param title what the header says
     * @param value a row's number
     * @param <T>   the row type
     * @return the column
     */
    public static <T> Column<T> numeric(I18nString title, ToDoubleFunction<T> value) {
        Objects.requireNonNull(value, "value");
        DoubleFunction<String> number = ChartFormats.number();
        Column<T> column = new Column<>(title, row -> value.applyAsDouble(row),
                (v, locale) -> number.apply((Double) v), null);
        column.alignment = Alignment.END;
        return column;
    }

    /**
     * {@link #numeric(I18nString, ToDoubleFunction)} with a literal title.
     *
     * @param title what the header says
     * @param value a row's number
     * @param <T>   the row type
     * @return the column
     */
    public static <T> Column<T> numeric(String title, ToDoubleFunction<T> value) {
        return numeric(I18nString.literal(title), value);
    }

    /**
     * A column over any value, with the formatter that turns it into text under the table's
     * locale: a date through a {@code DateTimeFormatter}, an enum through its own words.
     *
     * @param title  what the header says
     * @param value  a row's value; {@code null} draws an empty cell and sorts first
     * @param format the value as text under a locale; never asked for {@code null}
     * @param <T>    the row type
     * @param <V>    the value type
     * @return the column
     */
    @SuppressWarnings("unchecked")
    public static <T, V> Column<T> of(I18nString title, Function<T, V> value,
                                      BiFunction<V, Locale, String> format) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(format, "format");
        return new Column<>(title, value::apply,
                (v, locale) -> v == null ? "" : format.apply((V) v, locale), null);
    }

    /**
     * {@link #of(I18nString, Function, BiFunction)} with a literal title.
     *
     * @param title  what the header says
     * @param value  a row's value
     * @param format the value as text under a locale
     * @param <T>    the row type
     * @param <V>    the value type
     * @return the column
     */
    public static <T, V> Column<T> of(String title, Function<T, V> value,
                                      BiFunction<V, Locale, String> format) {
        return of(I18nString.literal(title), value, format);
    }

    /**
     * A column whose cells are widgets: one is built per realized row and released with it, so
     * the factory is called again for a row that scrolled out and back. Not sortable unless
     * {@link #comparator} names how.
     *
     * @param title   what the header says
     * @param factory the widget for a row's cell
     * @param <T>     the row type
     * @return the column
     */
    public static <T> Column<T> widget(I18nString title, Function<T, Widget> factory) {
        Objects.requireNonNull(factory, "factory");
        return new Column<>(title, null, null, factory);
    }

    /**
     * {@link #widget(I18nString, Function)} with a literal title.
     *
     * @param title   what the header says
     * @param factory the widget for a row's cell
     * @param <T>     the row type
     * @return the column
     */
    public static <T> Column<T> widget(String title, Function<T, Widget> factory) {
        return widget(I18nString.literal(title), factory);
    }

    /** @return what the header says */
    public I18nString title() {
        return title;
    }

    /** @return whether this column's cells are widgets rather than values */
    public boolean isWidgetColumn() {
        return widget != null;
    }

    /**
     * Names how rows compare on this column, replacing the comparison derived from the value.
     *
     * @param comparator the order; {@code null} restores the derived one
     * @return this column
     */
    public Column<T> comparator(Comparator<T> comparator) {
        this.comparator = comparator;
        return this;
    }

    /**
     * Sets whether a header click sorts on this column (default on). A column that cannot
     * compare &mdash; a widget column with no comparator &mdash; is not sortable whatever this says.
     *
     * @param sortable whether a click sorts
     * @return this column
     */
    public Column<T> sortable(boolean sortable) {
        this.sortable = sortable;
        return this;
    }

    /** @return whether a header click sorts on this column */
    public boolean isSortable() {
        return sortable && (comparator != null || value != null);
    }

    /**
     * Sets where a cell's text sits across the column (default {@link Alignment#START}, or
     * {@link Alignment#END} for a {@link #numeric} column).
     *
     * @param alignment where the text sits
     * @return this column
     */
    public Column<T> align(Alignment alignment) {
        this.alignment = Objects.requireNonNull(alignment, "alignment");
        return this;
    }

    /** @return where a cell's text sits across the column */
    public Alignment alignment() {
        return alignment;
    }

    /**
     * Sets the width layout starts from, in points (default 120). A width the user dragged
     * replaces it until {@link #resetWidth()}.
     *
     * @param points the preferred width; at least the minimum
     * @return this column
     */
    public Column<T> width(float points) {
        this.preferredWidth = Math.max(minWidth, points);
        return this;
    }

    /**
     * Sets the width below which neither layout nor a drag takes this column (default 40).
     *
     * @param points the floor
     * @return this column
     */
    public Column<T> minWidth(float points) {
        this.minWidth = Math.max(0, points);
        this.preferredWidth = Math.max(minWidth, preferredWidth);
        return this;
    }

    /**
     * Sets this column's share of the viewport width left over once every column has its
     * preferred width (default 0: keep the preferred width). A weight of 1 on one column makes
     * it take all of the leftover; equal weights on two share it equally.
     *
     * @param weight the share; zero or more
     * @return this column
     */
    public Column<T> weight(float weight) {
        this.weight = Math.max(0, weight);
        return this;
    }

    /**
     * Shows or hides the column. A hidden column takes no space, is not published to a screen
     * reader, and keeps its widths for when it returns.
     *
     * @param visible whether the column is shown
     * @return this column
     */
    public Column<T> visible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** @return whether the column is shown */
    public boolean isVisible() {
        return visible;
    }

    /** Forgets a width the user dragged, so layout starts from the preferred width again. */
    public void resetWidth() {
        Ui.checkUiThread();
        draggedWidth = -1;
    }

    /**
     * @return the width layout starts from: the one the user dragged, else the preferred one
     */
    public float width() {
        return draggedWidth >= 0 ? draggedWidth : preferredWidth;
    }

    /** @return the floor under this column's width */
    public float minWidth() {
        return minWidth;
    }

    /** @return this column's share of the leftover width */
    public float weight() {
        return weight;
    }

    // ---------------------------------------------------------------- package-private seam

    /** Records a width the user dragged the header to, floored at the minimum. */
    void dragTo(float points) {
        draggedWidth = Math.max(minWidth, points);
    }

    /** The text of {@code row}'s cell under {@code locale}; empty for a widget column. */
    String text(T row, Locale locale) {
        if (value == null) {
            return "";
        }
        return format.apply(value.apply(row), locale);
    }

    /** The widget of {@code row}'s cell, for a widget column. */
    Widget widgetFor(T row) {
        return Objects.requireNonNull(widget.apply(row), "the column's widget factory returned null");
    }

    /**
     * How {@code a} and {@code b} compare on this column: the named comparator, else the values
     * as ADR 041 §4 orders them, {@code null} first.
     */
    int compare(T a, T b, Collator collator, Locale locale) {
        if (comparator != null) {
            return comparator.compare(a, b);
        }
        Object va = value == null ? null : value.apply(a);
        Object vb = value == null ? null : value.apply(b);
        if (va == null || vb == null) {
            return va == null ? (vb == null ? 0 : -1) : 1;
        }
        if (va instanceof Number na && vb instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (va instanceof CharSequence sa && vb instanceof CharSequence sb) {
            return collator.compare(sa.toString(), sb.toString());
        }
        if (va instanceof Comparable<?> ca && va.getClass() == vb.getClass()) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) ca;
            return comparable.compareTo(vb);
        }
        return collator.compare(format.apply(va, locale), format.apply(vb, locale));
    }
}
