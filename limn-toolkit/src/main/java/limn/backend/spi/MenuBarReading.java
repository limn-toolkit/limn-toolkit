package limn.backend.spi;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A menu bar as a backend is handed it for the system's own bar: what the application declared,
 * already sorted into the places a system that keeps the bar outside the window has for it.
 *
 * <p>One reading numbers the rows of the whole bar, the two of the application's menu included,
 * and a number means nothing once the bar has been read again.
 *
 * @param about       the item that says what the application is, for the application's own
 *                    menu, or {@code null}; only its number, its enabled state and whether it is
 *                    there are used, the system having its own label for it
 * @param settings    the item that opens the application's settings, likewise
 * @param menus       the bar's menus in the application's order, the items above taken out and
 *                    the item that ends the application dropped, the system having its own
 * @param rightToLeft whether the application reads from the right
 */
public record MenuBarReading(@Nullable MenuRow about, @Nullable MenuRow settings, List<Top> menus,
        boolean rightToLeft) {

    /** A bar with nothing in it. */
    public static final MenuBarReading EMPTY = new MenuBarReading(null, null, List.of(), false);

    public MenuBarReading {
        menus = List.copyOf(menus);
    }

    /**
     * What a menu of the bar is to the system.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Kind {
        /** The application's alone. */
        PLAIN,
        /** The menu of the application's windows, to which the system adds its own. */
        WINDOW,
        /** The help menu, to which the system adds its search. */
        HELP
    }

    /**
     * One menu of the bar.
     *
     * @param title the word in the bar
     * @param kind  what it is to the system
     * @param rows  its items; a help menu may have none
     */
    public record Top(String title, Kind kind, List<MenuRow> rows) {
        public Top {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(kind, "kind");
            rows = List.copyOf(rows);
        }
    }
}
