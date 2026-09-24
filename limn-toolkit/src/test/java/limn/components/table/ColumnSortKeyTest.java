package limn.components.table;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    /**
     * FN-11 of the 2026-09-24 review: numbers were compared as doubles, and 2^53 + 1 is the double
     * 2^53, so a column of ids past it sorted neighbours as equal, in the list's order, and a
     * {@code BigDecimal} between them with them. They compare by value now, a double by its exact
     * value against them, and the doubles keep {@code Double.compare}'s own order at the edges.
     */
    @Test
    void longsBigIntegersAndBigDecimalsCompareExactly() {
        long big = 1L << 53;
        Column<Object[]> column = Column.of("n", (Object[] row) -> row[0],
                (Object v, Locale l) -> String.valueOf(v));
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        List<Object[]> rows = List.of(
                new Object[] {big + 1},                                      // 0
                new Object[] {big},                                          // 1
                new Object[] {BigInteger.valueOf(big).add(BigInteger.TWO)},  // 2
                new Object[] {new BigDecimal(big).add(new BigDecimal("0.5"))}, // 3
                new Object[] {(double) big},                                 // 4, equal to 1
                new Object[] {Long.MAX_VALUE},                               // 5
                new Object[] {0x1p63},                                       // 6, one past 5
                new Object[] {Double.NaN},                                   // 7
                new Object[] {Double.NEGATIVE_INFINITY},                     // 8
                new Object[] {0},                                            // 9
                new Object[] {-0.0});                                        // 10
        int[] expected = {8, 10, 9, 1, 4, 3, 0, 2, 5, 6, 7};
        assertArrayEquals(expected, oldOrder(column, rows, collator, Locale.ENGLISH), "compare");
        assertArrayEquals(expected, newOrder(column, rows, collator, Locale.ENGLISH), "the keys");
    }

    private enum Tier {
        LOW,
        HIGH {
            @Override
            public String toString() {
                return "high";
            }
        }
    }

    private record Tag(String name) {
    }

    /**
     * FN-3 and FN-11 of the 2026-09-24 review: the derived order has to be one order, or the sort
     * throws "Comparison method violates its general contract". Held over every pair and triple
     * of a pool that holds each kind a column can hold, the edges of the numbers included, through
     * both the comparator and the keys.
     */
    @Test
    void theDerivedOrderIsATotalOrderOverValuesOfEveryKind() {
        long big = 1L << 53;
        List<Object> pool = new ArrayList<>(Arrays.asList(null, 5, 20, "3", "20", "apple",
                new StringBuilder("Äpfel"), big, big + 1, (double) big, 0.5f, 2.5, -0.0, 0.0, 0,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Long.MIN_VALUE,
                BigInteger.valueOf(big).add(BigInteger.ONE), new BigDecimal("2.50"),
                new BigDecimal("2.5"), new BigDecimal("-0.00"), LocalDate.of(2026, 9, 24),
                LocalDate.of(2025, 1, 1), Tier.HIGH, Tier.LOW, true, 'x', new Tag("b"),
                new Tag("a")));
        Column<Object[]> column = Column.of("mixed", (Object[] row) -> row[0],
                (Object v, Locale l) -> String.valueOf(v));
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        int n = pool.size();
        List<Object[]> rows = new ArrayList<>();
        Column.SortKey[] keys = new Column.SortKey[n];
        for (int i = 0; i < n; i++) {
            rows.add(new Object[] {pool.get(i)});
            keys[i] = column.sortKey(rows.get(i), i, collator);
        }
        int[][] byCompare = new int[n][n];
        int[][] byKeys = new int[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                byCompare[i][j] = Integer.signum(
                        column.compare(rows.get(i), rows.get(j), collator, Locale.ENGLISH));
                byKeys[i][j] = Integer.signum(
                        column.compareKeys(keys[i], keys[j], collator, Locale.ENGLISH));
            }
        }
        assertArrayEquals(byCompare, byKeys, "the keys order as the comparator does");
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                String pair = pool.get(x) + " and " + pool.get(y);
                assertEquals(-byCompare[y][x], byCompare[x][y], "antisymmetric: " + pair);
                for (int z = 0; z < n; z++) {
                    String triple = pair + " and " + pool.get(z);
                    if (byCompare[x][y] > 0 && byCompare[y][z] > 0) {
                        assertEquals(1, byCompare[x][z], "transitive: " + triple);
                    }
                    if (byCompare[x][y] == 0) {
                        assertEquals(byCompare[x][z], byCompare[y][z], "equal alike: " + triple);
                    }
                }
            }
        }
        int low = pool.indexOf(Tier.LOW);
        int high = pool.indexOf(Tier.HIGH);
        assertEquals(-1, byCompare[low][high],
                "an enum constant with a body of its own still sorts in declaration order");
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
