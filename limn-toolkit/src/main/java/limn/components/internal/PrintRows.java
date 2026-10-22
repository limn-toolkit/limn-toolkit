package limn.components.internal;

import limn.scene.Widget;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What a list, a table or a tree answers a printed document that cuts it between rows: how many
 * rows there are, how tall each is at the width it is laid out at, how much of the box is not rows
 * (a table's header), and the row to begin the box with. A page then lays the widget
 * out exactly as tall as its header and the rows that fit, from that row: every row of the widget
 * is printed, a page's worth realized at a time, and a table's header is on every page because it is
 * on every box.
 *
 * <p>Not API. Each of the three widgets registers how it answers from its own static initializer,
 * with a view that reaches its private row engine; {@code limn.print} asks {@link #of}.
 */
public interface PrintRows {

    /** @return how many rows there are */
    int rowCount();

    /**
     * Realizes a row, if it is not, and measures it at the width of the last layout.
     *
     * @param index a row
     * @return its height
     */
    float rowHeight(int index);

    /**
     * @return how much of the box the last layout gave to anything but rows: a table's header and
     *         footer, a reserved strip
     */
    float chromeHeight();

    /**
     * Begins the box at {@code index}, its top at the box's top; the next layout places rows from
     * there.
     *
     * @param index a row
     */
    void beginAt(int index);

    /**
     * @param widget a widget
     * @return how it answers, or null for a widget that is not cut between rows
     */
    static @Nullable PrintRows of(Widget<?> widget) {
        Function<Widget<?>, PrintRows> view = Registry.VIEWS.get(widget.getClass());
        return view == null ? null : view.apply(widget);
    }

    /**
     * Called once by each widget that is cut between rows, from its static initializer.
     *
     * @param type the widget's class
     * @param view how an instance of it answers
     */
    static void register(Class<?> type, Function<Widget<?>, PrintRows> view) {
        Registry.VIEWS.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(view, "view"));
    }

    /** The views, by the widget's class. */
    final class Registry {
        private static final Map<Class<?>, Function<Widget<?>, PrintRows>> VIEWS = new ConcurrentHashMap<>();

        private Registry() {
        }
    }
}
