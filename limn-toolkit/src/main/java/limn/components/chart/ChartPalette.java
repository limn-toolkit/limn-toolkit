package limn.components.chart;

import limn.components.Theme;
import limn.graphics.Color;

import java.util.List;
import java.util.Objects;

/**
 * The colors a chart hands to its series (or, in a donut, to its slices) when the
 * application names none. Two built-in sets, one for light surfaces and one for dark,
 * picked automatically from the active {@link Theme}.
 *
 * <p>The two sets are the same eight hues stepped for their surface, not two unrelated
 * palettes, and the <b>order is the mechanism</b>, not decoration: it was chosen so that
 * every <em>adjacent</em> pair stays apart under simulated protanopia and deuteranopia
 * (OKLab ΔE ≥ 8) as well as under normal vision (ΔE ≥ 15), because adjacent slots are the
 * pairs a stacked bar, a grouped bar and a legend actually put side by side. Reordering
 * the slots or editing a hex breaks that guarantee; {@code ChartPaletteTest} pins the
 * values so the break is a test failure rather than a silent regression.
 *
 * <p><b>Contrast against the surface is deliberately not a gate</b> for these marks: four
 * of the light slots and one of the dark sit below 3:1 on a typical chart surface, which
 * is legal only because identity is never carried by color alone here: a chart with two
 * or more series always draws a legend, and the tooltip names the series it reports. Keep
 * that relief in place if you swap in your own colors.
 *
 * <p>Past the last slot the palette repeats, shading each repeat alternately toward white
 * and black. That keeps a ninth series drawable, but two series then differ only by
 * lightness: at that point fold the tail into an "Other" series, or split the chart.
 *
 * <pre>{@code
 * chart.setPalette(ChartPalette.of(Color.rgb(0x4C8DFF), Color.rgb(0xF472B6)));
 * }</pre>
 */
public final class ChartPalette {

    /** Categorical slots for a light surface. */
    private static final ChartPalette LIGHT = new ChartPalette(List.of(
            Color.rgb(0x2A78D6),  // blue
            Color.rgb(0xEB6834),  // orange
            Color.rgb(0x1BAF7A),  // aqua
            Color.rgb(0xEDA100),  // yellow
            Color.rgb(0xE87BA4),  // magenta
            Color.rgb(0x008300),  // green
            Color.rgb(0x4A3AA7),  // violet
            Color.rgb(0xE34948))); // red

    /** The same eight hues, stepped for a dark surface. */
    private static final ChartPalette DARK = new ChartPalette(List.of(
            Color.rgb(0x3987E5),
            Color.rgb(0xD95926),
            Color.rgb(0x199E70),
            Color.rgb(0xC98500),
            Color.rgb(0xD55181),
            Color.rgb(0x008300),
            Color.rgb(0x9085E9),
            Color.rgb(0xE66767)));

    private final List<Color> colors;

    private ChartPalette(List<Color> colors) {
        this.colors = colors;
    }

    /**
     * A palette over the given colors, in the order they will be assigned.
     *
     * @throws IllegalArgumentException if no color is given
     */
    public static ChartPalette of(Color... colors) {
        return of(List.of(colors));
    }

    /** {@link #of(Color...)} over a list; the list is copied. */
    public static ChartPalette of(List<Color> colors) {
        Objects.requireNonNull(colors, "colors");
        if (colors.isEmpty()) {
            throw new IllegalArgumentException("a palette needs at least one color");
        }
        return new ChartPalette(List.copyOf(colors));
    }

    /** The built-in slots for a light surface. */
    public static ChartPalette forLightSurface() {
        return LIGHT;
    }

    /** The built-in slots for a dark surface. */
    public static ChartPalette forDarkSurface() {
        return DARK;
    }

    /** The built-in set matching {@code theme}'s mode, what a chart uses unless told otherwise. */
    public static ChartPalette defaultFor(Theme theme) {
        return theme.dark ? DARK : LIGHT;
    }

    /**
     * The color for slot {@code index}. Indexes past {@link #size()} repeat the slots,
     * shading each repeat alternately lighter and darker; see the class note on why that
     * is a fallback rather than an extension.
     *
     * @throws IllegalArgumentException if {@code index} is negative
     */
    public Color color(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("negative slot: " + index);
        }
        int n = colors.size();
        Color base = colors.get(index % n);
        int wrap = index / n;
        if (wrap == 0) {
            return base;
        }
        float amount = Math.min(0.45f, 0.22f * ((wrap + 1) / 2));
        return wrap % 2 == 1 ? base.lerp(Color.WHITE, amount) : base.lerp(Color.BLACK, amount);
    }

    /** How many distinct slots this palette declares. */
    public int size() {
        return colors.size();
    }

    /** The slots, in assignment order. */
    public List<Color> colors() {
        return colors;
    }
}
