package limn.components.table;

import org.junit.jupiter.api.Test;

import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Decision 114: a table sorts by keys computed once per row, and the order must be exactly the one
 * {@link Column#compare} gives — nulls first, numbers by value, text by the collator, one
 * {@code Comparable} class by {@code compareTo}, anything else by its formatted text. Held here on
 * random data against the old comparator, over every kind of value a column can hold, in two
 * languages whose collation differs.
 */
class ColumnSortKeyTest {

    private static final String[] WORDS = {"apple", "Äpfel", "zebra", "Zürich", "straße", "strasse",
            "Ørsted", "ångström", "", "résumé", "resume", "ç", "c", "10", "9", "É"};

    private static int[] oldOrder(Column<Object[]> column, List<Object[]> rows, Collator collator,
                                  Locale locale) {
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int c = column.compare(rows.get(a), rows.get(b), collator, locale);
            return c != 0 ? c : Integer.compare(a, b);
        });
        return Arrays.stream(order).mapToInt(Integer::intValue).toArray();
    }

    private static int[] newOrder(Column<Object[]> column, List<Object[]> rows, Collator collator,
                                  Locale locale) {
        Column.SortKey[] keys = new Column.SortKey[rows.size()];
        for (int i = 0; i < keys.length; i++) keys[i] = column.sortKey(rows.get(i), i, collator);
        Arrays.sort(keys, (a, b) -> {
            int c = column.compareKeys(a, b, collator, locale);
            return c != 0 ? c : Integer.compare(a.index, b.index);
        });
        return Arrays.stream(keys).mapToInt(k -> k.index).toArray();
    }

    private static Object randomValue(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> null;
            case 1 -> WORDS[random.nextInt(WORDS.length)];
            case 2 -> random.nextInt(50) - 25;
            case 3 -> random.nextDouble() * 100;
            case 4 -> LocalDate.of(2026, 1 + random.nextInt(12), 1 + random.nextInt(28));
            default -> new StringBuilder(WORDS[random.nextInt(WORDS.length)]);
        };
    }

    @Test
    void keysOrderRowsExactlyAsTheComparatorDidForEveryKindOfValueAndLanguage() {
        Column<Object[]> column = Column.of("mixed", (Object[] row) -> row[0],
                (Object v, Locale l) -> String.valueOf(v));
        for (Locale locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("sv-SE"),
                Locale.forLanguageTag("de-DE"))) {
            Collator collator = Collator.getInstance(locale);
            Random random = new Random(20260922L + locale.hashCode());
            for (int round = 0; round < 40; round++) {
                List<Object[]> rows = new ArrayList<>();
                int n = 1 + random.nextInt(300);
                for (int i = 0; i < n; i++) rows.add(new Object[] {randomValue(random)});
                assertArrayEquals(oldOrder(column, rows, collator, locale),
                        newOrder(column, rows, collator, locale),
                        "round " + round + " under " + locale);
            }
        }
    }

    @Test
    void aTextColumnAndANumericColumnSortAsBefore() {
        Column<String[]> text = Column.text("name", (String[] r) -> r[0]);
        Column<String[]> number = Column.numeric("n", (String[] r) -> Double.parseDouble(r[1]));
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        Random random = new Random(7);
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            rows.add(new String[] {WORDS[random.nextInt(WORDS.length)] + random.nextInt(10),
                    String.valueOf(random.nextInt(100))});
        }
        for (Column<String[]> column : List.of(text, number)) {
            Integer[] order = new Integer[rows.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> {
                int c = column.compare(rows.get(a), rows.get(b), collator, Locale.ENGLISH);
                return c != 0 ? c : Integer.compare(a, b);
            });
            Column.SortKey[] keys = new Column.SortKey[rows.size()];
            for (int i = 0; i < keys.length; i++) keys[i] = column.sortKey(rows.get(i), i, collator);
            Arrays.sort(keys, (a, b) -> {
                int c = column.compareKeys(a, b, collator, Locale.ENGLISH);
                return c != 0 ? c : Integer.compare(a.index, b.index);
            });
            assertArrayEquals(Arrays.stream(order).mapToInt(Integer::intValue).toArray(),
                    Arrays.stream(keys).mapToInt(k -> k.index).toArray(), column.toString());
        }
    }
}
