package limn.components.table;

import limn.i18n.NumberFormats;
import limn.concurrent.Ui;
import limn.i18n.I18nString;
import limn.scene.Widget;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Collator;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
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
 * dragged is held here, so it survives a {@link Table#refresh()}, and is held as dragged: the
 * column takes no share of the leftover until {@link #resetWidth()}, so the divider stays where
 * the pointer left it and the other weighted columns take up the difference.
 *
 * <p><b>Sorting.</b> A header click sorts through {@link #compare}: a comparator the application
 * named, else the values themselves &mdash; numbers numerically, strings through the language's
 * collator, anything else comparable by itself &mdash; else the formatted text. Numbers compare
 * exactly, so a {@code long} id past 2^53 or a {@code BigDecimal} amount keeps its place, and a
 * column whose values differ in kind sorts its numbers first, then its text, then the rest, each
 * among its own kind. A widget column has no value and is not sortable unless given a comparator.
 *
 * <p><b>The footer.</b> A column may put something in the table's summary row, which is pinned
 * under the rows the way the header is pinned over them and appears as soon as one column has
 * something for it: a fixed text ({@link #footer(I18nString)}), a value computed from all the
 * rows and formatted as the cells are ({@link #footer(Function)}), or one of the aggregates a
 * numeric column offers ({@link #footerSum()}, {@link #footerAverage()}, {@link #footerMin()},
 * {@link #footerMax()}) and the count any column does ({@link #footerCount()}). The table computes
 * it on {@link Table#setRows} and {@link Table#refresh()}, never per frame.
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
    private final Function<T, Widget<?>> widget;
    private Comparator<T> comparator;
    private boolean sortable = true;
    private Alignment alignment = Alignment.START;
    private float preferredWidth = DEFAULT_WIDTH;
    private float minWidth = DEFAULT_MIN_WIDTH;
    private float weight;
    private float draggedWidth = -1;
    private boolean visible = true;
    private DoubleFunction<String> numberFormat;
    private I18nString footerText;
    private Function<List<T>, Object> footer;
    private BiFunction<Object, Locale, String> footerFormat;

    private Column(I18nString title, Function<T, Object> value,
                   BiFunction<Object, Locale, String> format, Function<T, Widget<?>> widget) {
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
     * A column of numbers: aligned to the reading end, formatted and localized as the charts format
     * an axis, and sorted numerically.
     *
     * @param title what the header says
     * @param value a row's number
     * @param <T>   the row type
     * @return the column
     */
    public static <T> Column<T> numeric(I18nString title, ToDoubleFunction<T> value) {
        return numeric(title, value, NumberFormats.number());
    }

    /**
     * A column of numbers written by {@code format}, which is any of {@link NumberFormats} or
     * the application's own {@code DoubleFunction<String>}: {@code NumberFormats.prefix("R$ ")}
     * for a fixed prefix, {@code decimals(2)}, {@code unit(" kg")}, {@code percent(1)},
     * {@code compact()}. The footer's aggregates are written by the same format, so a sum of
     * prices is a price.
     *
     * @param title  what the header says
     * @param value  a row's number
     * @param format the number as text, reading the locale in effect when it formats
     * @param <T>    the row type
     * @return the column
     */
    public static <T> Column<T> numeric(I18nString title, ToDoubleFunction<T> value,
                                        DoubleFunction<String> format) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(format, "format");
        // Any Number, not only the Double a cell holds: the same format writes the footer, and a
        // footer function answers whatever number the application computed, an int sum included.
        Column<T> column = new Column<>(title, row -> value.applyAsDouble(row),
                (v, locale) -> format.apply(((Number) v).doubleValue()), null);
        column.alignment = Alignment.END;
        column.numberFormat = format;
        return column;
    }

    /**
     * {@link #numeric(I18nString, ToDoubleFunction, DoubleFunction)} with a literal title.
     *
     * @param title  what the header says
     * @param value  a row's number
     * @param format the number as text
     * @param <T>    the row type
     * @return the column
     */
    public static <T> Column<T> numeric(String title, ToDoubleFunction<T> value,
                                        DoubleFunction<String> format) {
        return numeric(I18nString.literal(title), value, format);
    }

    /**
     * A column of amounts in one currency, written as the language in effect writes money
     * ({@link NumberFormats#currency(Currency)}): the symbol, its side, the decimals and the
     * separators are the locale's, and a footer sum is money too.
     *
     * @param title    what the header says
     * @param value    a row's amount, in {@code currency}
     * @param currency the currency the amounts are in
     * @param <T>      the row type
     * @return the column
     */
    public static <T> Column<T> currency(I18nString title, ToDoubleFunction<T> value,
                                         Currency currency) {
        return numeric(title, value, NumberFormats.currency(currency));
    }

    /**
     * {@link #currency(I18nString, ToDoubleFunction, Currency)} with a literal title.
     *
     * @param title    what the header says
     * @param value    a row's amount
     * @param currency the currency the amounts are in
     * @param <T>      the row type
     * @return the column
     */
    public static <T> Column<T> currency(String title, ToDoubleFunction<T> value,
                                         Currency currency) {
        return currency(I18nString.literal(title), value, currency);
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
    public static <T> Column<T> widget(I18nString title, Function<T, Widget<?>> factory) {
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
    public static <T> Column<T> widget(String title, Function<T, Widget<?>> factory) {
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
     * it take all of the leftover; equal weights on two share it equally. A column whose width
     * the user dragged takes no share until {@link #resetWidth()}.
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
     * reader, builds no widget for its cells when it is a {@linkplain #widget widget column},
     * and keeps its widths for when it returns. The change is picked up by the table's next
     * layout, which releases a hidden widget column's widgets and builds a newly shown one's
     * and leaves every other cell's widget alone, a focused one included:
     * {@link limn.scene.Widget#markNeedsLayout() markNeedsLayout()} on the table asks for that
     * layout. {@link Table#refresh()} asks for one too, and as every refresh does rebuilds the
     * realized rows except the one whose widget cell holds the keyboard, which stays with its
     * record while the list still holds it (and hands the keyboard to the table when its own
     * column is the one hidden, or its record is gone). A focus cell,
     * or the header's cursor, on the hidden column moves to the nearest shown column.
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

    // ------------------------------------------------------------------------------- footer

    /**
     * Puts a fixed text in this column's footer cell: "Total", say, beside the sums.
     *
     * @param text what the footer cell says; {@code null} removes the footer cell
     * @return this column
     */
    public Column<T> footer(I18nString text) {
        this.footerText = text;
        this.footer = null;
        return this;
    }

    /**
     * {@link #footer(I18nString)} with a literal text.
     *
     * @param text what the footer cell says
     * @return this column
     */
    public Column<T> footer(String text) {
        return footer(I18nString.literal(text));
    }

    /**
     * Computes this column's footer cell from all the rows, and formats the result as the
     * column's cells are formatted: a {@link #text} column expects a string back, a
     * {@link #numeric} one any {@code Number}, an {@link #of} column a value of its own type.
     *
     * @param summary the footer value from the rows; {@code null} removes the footer cell
     * @return this column
     */
    public Column<T> footer(Function<List<T>, ?> summary) {
        this.footerText = null;
        this.footer = summary == null ? null : summary::apply;
        this.footerFormat = format;
        return this;
    }

    /**
     * Sums the column's numbers in the footer.
     *
     * @return this column
     * @throws IllegalStateException on a column without a numeric value, from the first
     *                               {@link Table#refresh()} that computes it
     */
    public Column<T> footerSum() {
        return aggregate(numbers -> {
            double sum = 0;
            for (double n : numbers) {
                sum += n;
            }
            return sum;
        });
    }

    /**
     * Averages the column's numbers in the footer; empty when there are no rows.
     *
     * @return this column
     */
    public Column<T> footerAverage() {
        return aggregate(numbers -> {
            if (numbers.length == 0) {
                return null;
            }
            double sum = 0;
            for (double n : numbers) {
                sum += n;
            }
            return sum / numbers.length;
        });
    }

    /**
     * The smallest of the column's numbers in the footer; empty when there are no rows.
     *
     * @return this column
     */
    public Column<T> footerMin() {
        return aggregate(numbers -> {
            if (numbers.length == 0) {
                return null;
            }
            double min = numbers[0];
            for (double n : numbers) {
                min = Math.min(min, n);
            }
            return min;
        });
    }

    /**
     * The largest of the column's numbers in the footer; empty when there are no rows.
     *
     * @return this column
     */
    public Column<T> footerMax() {
        return aggregate(numbers -> {
            if (numbers.length == 0) {
                return null;
            }
            double max = numbers[0];
            for (double n : numbers) {
                max = Math.max(max, n);
            }
            return max;
        });
    }

    /**
     * The row count in the footer, localized as a number; any column may carry it.
     *
     * @return this column
     */
    public Column<T> footerCount() {
        DoubleFunction<String> number = NumberFormats.number();
        this.footerText = null;
        this.footer = rows -> (double) rows.size();
        this.footerFormat = (v, locale) -> number.apply(((Number) v).doubleValue());
        return this;
    }

    private interface Aggregate {
        Double of(double[] numbers);
    }

    private Column<T> aggregate(Aggregate aggregate) {
        DoubleFunction<String> number = numberFormat != null ? numberFormat : NumberFormats.number();
        this.footerText = null;
        this.footer = rows -> {
            if (value == null) {
                throw new IllegalStateException("a widget column has no numbers to aggregate");
            }
            double[] numbers = new double[rows.size()];
            for (int i = 0; i < numbers.length; i++) {
                Object v = value.apply(rows.get(i));
                if (!(v instanceof Number n)) {
                    throw new IllegalStateException("the column's value is not a number: " + v);
                }
                numbers[i] = n.doubleValue();
            }
            return aggregate.of(numbers);
        };
        this.footerFormat = (v, locale) -> number.apply(((Number) v).doubleValue());
        return this;
    }

    /** @return whether this column puts something in the footer */
    public boolean hasFooter() {
        return footerText != null || footer != null;
    }

    /** The footer cell's text under {@code locale}, or {@code null} when the column has none. */
    String footerText(List<T> rows, Locale locale) {
        if (footerText != null) {
            return footerText.get();
        }
        if (footer == null) {
            return null;
        }
        Object v = footer.apply(rows);
        return v == null ? "" : footerFormat.apply(v, locale);
    }

    /**
     * Forgets a width the user dragged, so layout starts from the preferred width again and the
     * column's weight takes its share of the leftover again.
     */
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

    /**
     * The weight layout shares the leftover by: none while the width is one the user dragged.
     * A drag starts from the width on screen, share included, so a dragged column that took a
     * share again ran ahead of the pointer by it: between two weighted columns a 1 pt drag moved
     * the divider 35.5 pt, and a column alone in its weight could not be dragged at all.
     */
    float leftoverWeight() {
        return draggedWidth >= 0 ? 0 : weight;
    }

    /** The text of {@code row}'s cell under {@code locale}; empty for a widget column. */
    String text(T row, Locale locale) {
        if (value == null) {
            return "";
        }
        return format.apply(value.apply(row), locale);
    }

    /** The widget of {@code row}'s cell, for a widget column. */
    Widget<?> widgetFor(T row) {
        return Objects.requireNonNull(widget.apply(row), "the column's widget factory returned null");
    }

    /**
     * How {@code a} and {@code b} compare on this column: the named comparator, else the values
     * &mdash; numbers by value, text through the collator, anything else comparable by itself,
     * else the formatted text &mdash; with {@code null} first. Two values of different kinds
     * compare by kind, in that order, and two comparable values of different classes by the
     * class's name.
     *
     * <p>The kinds come first because the order has to be one order. Before, a number met a string
     * through both formatted texts, so in a column mixing 5, 20 and "3" the numbers sorted as
     * numbers and against the string as text, 5 &lt; 20 &lt; "3" &lt; 5, and the sort threw on
     * the cycle and kept throwing on every refresh while the column stayed sorted.
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
        int kind = kindOf(va);
        int other = kindOf(vb);
        if (kind != other) {
            return Integer.compare(kind, other);
        }
        if (kind == NUMBER) {
            return compareNumbers((Number) va, (Number) vb);
        }
        if (kind == TEXT) {
            return collator.compare(va.toString(), vb.toString());
        }
        if (kind == COMPARABLE) {
            Class<?> ca = comparableClass(va);
            Class<?> cb = comparableClass(vb);
            if (ca == cb) {
                return compareComparables(va, vb);
            }
            // Each class's values together, in the order of the classes' names; two classes share
            // a name only from two class loaders, and those meet by their text, as below.
            int byName = ca.getName().compareTo(cb.getName());
            if (byName != 0) {
                return byName;
            }
        }
        return collator.compare(format.apply(va, locale), format.apply(vb, locale));
    }

    /** The kinds a value sorts among, in the order they sort in when two values' kinds differ. */
    private static final int NUMBER = 0;
    private static final int TEXT = 1;
    private static final int COMPARABLE = 2;
    private static final int OTHER = 3;

    private static int kindOf(Object v) {
        if (v instanceof Number) {
            return NUMBER;
        }
        if (v instanceof CharSequence) {
            return TEXT;
        }
        return v instanceof Comparable<?> ? COMPARABLE : OTHER;
    }

    /**
     * The class whose {@code compareTo} orders {@code v}. An enum constant with a body of its own
     * is a subclass of its enum, so the enum is its declaring class, or two of its constants would
     * never meet through {@code compareTo}.
     */
    private static Class<?> comparableClass(Object v) {
        return v instanceof Enum<?> e ? e.getDeclaringClass() : v.getClass();
    }

    @SuppressWarnings("unchecked")
    private static int compareComparables(Object va, Object vb) {
        return ((Comparable<Object>) va).compareTo(vb);
    }

    /**
     * Two numbers by value, exactly: a {@code long}, a {@code BigInteger} or a {@code BigDecimal}
     * is not rounded to a double on the way, where two ids past 2^53 that one double holds sorted
     * as equal and an id between them could sort on either side. Wherever a double or a float
     * takes part the order is {@link Double#compare}'s, negative zero before zero and NaN after
     * positive infinity, and a number of any other class is compared by its double, as before.
     */
    private static int compareNumbers(Number a, Number b) {
        if (a instanceof Double x && b instanceof Double y) {
            return Double.compare(x, y);
        }
        boolean exactA = isExact(a);
        boolean exactB = isExact(b);
        if (!exactA && !exactB) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (isWhole(a) && isWhole(b)) {
            return Long.compare(a.longValue(), b.longValue());
        }
        double da = exactA ? 0 : a.doubleValue();
        double db = exactB ? 0 : b.doubleValue();
        // A double that is not finite has no exact value, and sorts where Double.compare puts it:
        // negative infinity below every number, positive infinity above, and NaN above that.
        int sideA = exactA ? 0 : outside(da);
        int sideB = exactB ? 0 : outside(db);
        if (sideA != 0 || sideB != 0) {
            return Integer.compare(sideA, sideB);
        }
        int byValue = exactValue(a, exactA, da).compareTo(exactValue(b, exactB, db));
        if (byValue != 0) {
            return byValue;
        }
        // Equal values differ only at zero, where a negative zero comes first, as it does
        // between two doubles.
        return Boolean.compare(!exactB && isNegativeZero(db), !exactA && isNegativeZero(da));
    }

    private static boolean isWhole(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte;
    }

    private static boolean isExact(Number n) {
        return isWhole(n) || n instanceof BigInteger || n instanceof BigDecimal;
    }

    /** Where a double that is not finite sorts against every finite number; 0 for a finite one. */
    private static int outside(double d) {
        if (Double.isNaN(d)) {
            return 2;
        }
        return Double.isInfinite(d) ? (d > 0 ? 1 : -1) : 0;
    }

    private static boolean isNegativeZero(double d) {
        return d == 0 && Math.copySign(1.0, d) < 0;
    }

    private static BigDecimal exactValue(Number n, boolean exact, double d) {
        if (!exact) {
            return new BigDecimal(d); // a finite double's exact binary value, not its shortest text
        }
        if (n instanceof BigDecimal big) {
            return big;
        }
        return n instanceof BigInteger big ? new BigDecimal(big) : BigDecimal.valueOf(n.longValue());
    }

    /** @return whether this column sorts by a comparator the application named */
    boolean sortsByComparator() {
        return comparator != null;
    }

    /**
     * {@code row}'s sort key, computed once per sort rather than once per comparison:
     * the value, its kind, its number, and its collation key when it is text. Compared by
     * {@link #compareKeys}, which orders exactly as {@link #compare} does.
     *
     * <p>Measured before (2026-09-22): the comparator extracted both values and ran
     * {@code Collator.compare} on every comparison, about 940 bytes each, so sorting 100,000 rows by a
     * text column took 1.3 s and allocated 1.6 GB on the user-interface thread, against 24 ms for a
     * numeric column. A {@code CollationKey} compares by its bytes, and {@code compareTo} on two keys
     * from one collator orders as that collator's {@code compare} does.
     */
    SortKey sortKey(T row, int index, Collator collator) {
        Object v = value == null ? null : value.apply(row);
        if (v instanceof Number n) {
            return new SortKey(index, v, NUMBER, n.doubleValue(), comparesByDouble(n), null);
        }
        return new SortKey(index, v, v == null ? -1 : kindOf(v), 0, false,
                v instanceof CharSequence cs ? collator.getCollationKey(cs.toString()) : null);
    }

    /**
     * Whether {@code n} compares with any other such number by its double alone, as
     * {@link #compareNumbers} would compare them: a double or a float, a whole number within
     * 2^53, every one of which a double holds, and a number of a class compared by its double
     * anyway. Two such keys compare by a field, as a numeric column's always did, rather than
     * through the boxed values.
     */
    private static boolean comparesByDouble(Number n) {
        if (isWhole(n)) {
            long v = n.longValue();
            return v >= -(1L << 53) && v <= 1L << 53;
        }
        return !(n instanceof BigInteger || n instanceof BigDecimal);
    }

    /**
     * {@link #compare}'s order over two keys: {@code null} first, then by kind &mdash; numbers by
     * value, text by the collator, comparable values by class and then by {@code compareTo}, and
     * anything else by its formatted text under the collator (computed on first need and kept on
     * the key).
     */
    int compareKeys(SortKey a, SortKey b, Collator collator, Locale locale) {
        Object va = a.value;
        Object vb = b.value;
        if (va == null || vb == null) {
            return va == null ? (vb == null ? 0 : -1) : 1;
        }
        if (a.kind != b.kind) {
            return Integer.compare(a.kind, b.kind);
        }
        if (a.kind == NUMBER) {
            return a.byDouble && b.byDouble ? Double.compare(a.number, b.number)
                    : compareNumbers((Number) va, (Number) vb);
        }
        if (a.kind == TEXT) {
            return a.text.compareTo(b.text);
        }
        if (a.kind == COMPARABLE) {
            Class<?> ca = comparableClass(va);
            Class<?> cb = comparableClass(vb);
            if (ca == cb) {
                return compareComparables(va, vb);
            }
            int byName = ca.getName().compareTo(cb.getName());
            if (byName != 0) {
                return byName;
            }
        }
        return formatted(a, collator, locale).compareTo(formatted(b, collator, locale));
    }

    private java.text.CollationKey formatted(SortKey key, Collator collator, Locale locale) {
        if (key.formatted == null) {
            key.formatted = collator.getCollationKey(format.apply(key.value, locale));
        }
        return key.formatted;
    }

    /** One row's sort key; see {@link #sortKey}. */
    static final class SortKey {
        final int index;
        final Object value;
        final int kind;
        final double number;
        final boolean byDouble;
        final java.text.CollationKey text;
        java.text.CollationKey formatted;

        SortKey(int index, Object value, int kind, double number, boolean byDouble,
                java.text.CollationKey text) {
            this.index = index;
            this.value = value;
            this.kind = kind;
            this.number = number;
            this.byDouble = byDouble;
            this.text = text;
        }
    }
}
