package limn.components.internal;

import limn.scene.Widget;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Where a widget that holds lines of text may be cut by a page without cutting a line in half
 * (ADR 078 §6.2): the boundaries between its lines, as offsets from its top, at the layout it last
 * had. A widget that does not answer is cut only above or below it.
 *
 * <p>Not API. A widget registers how it answers from its own static initializer; {@code limn.print}
 * asks {@link #of}.
 */
public final class LineCuts {

    private static final Map<Class<?>, Function<Widget<?>, float[]>> CUTS = new ConcurrentHashMap<>();
    private static final float[] NONE = new float[0];

    private LineCuts() {
    }

    /**
     * Called once by a widget class, from its static initializer.
     *
     * @param type the widget's class
     * @param cuts how an instance answers: ascending offsets strictly between 0 and its height
     */
    public static void register(Class<?> type, Function<Widget<?>, float[]> cuts) {
        CUTS.put(Objects.requireNonNull(type, "type"), Objects.requireNonNull(cuts, "cuts"));
    }

    /**
     * @param widget a widget, laid out
     * @return where it may be cut inside, ascending; empty for a widget that answers nothing
     */
    public static float[] of(Widget<?> widget) {
        Function<Widget<?>, float[]> cuts = CUTS.get(widget.getClass());
        return cuts == null ? NONE : cuts.apply(widget);
    }

    /** @return whether {@code widget} answers at all, so that it is a widget with lines inside it */
    public static boolean answers(Widget<?> widget) {
        return CUTS.containsKey(widget.getClass());
    }
}
