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
 * <p><b>What a chart draws by default is held to 3:1 on the palette's surfaces</b>, as WCAG
 * 2.1 1.4.11 asks of a mark that is needed to read the chart: {@link #defaultFor}
 * moves each slot in OKLab lightness alone, keeping its hue and its chroma where the gamut
 * allows, darker in a light palette and lighter in a dark one, only until it clears 3:1 on the
 * canvas, a card and a popover; then it moves an adjacent pair further apart wherever the move
 * brought it under the separation above. Four of the light slots and one of the dark sit below
 * 3:1 as written, on most palettes' surfaces. {@link #forLightSurface()} and
 * {@link #forDarkSurface()} keep the slots as written. A palette of your own is used as given:
 * hold it to 3:1 yourself, or let a legend and the tooltip name every series.
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

    /**
     * The built-in set for {@code theme}'s mode, solved for its surfaces: what a chart uses unless
     * told otherwise. Each slot clears 3:1 on the theme's background, surface and raised surface,
     * and adjacent slots stay apart under normal vision, protanopia and deuteranopia; see the
     * class comment. Solved once per palette.
     */
    public static ChartPalette defaultFor(Theme theme) {
        synchronized (SOLVED) {
            return SOLVED.computeIfAbsent(theme, ChartPalette::solve);
        }
    }

    /** {@link #defaultFor}'s answers, per palette, as long as the palette lives. */
    private static final java.util.Map<Theme, ChartPalette> SOLVED = new java.util.WeakHashMap<>();

    /** WCAG 2.1 1.4.11's bar for a graphical object. */
    private static final double NON_TEXT_CONTRAST = 3.0;
    /** The separation the slots were chosen for, OKLab ΔE × 100: normal vision, then simulated CVD. */
    private static final double SEPARATION_NORMAL = 15;
    private static final double SEPARATION_CVD = 8;
    /** Machado, Oliveira and Fernandes (2009), severity 1, on linear sRGB. */
    private static final double[][] PROTANOPIA = {
            {0.152286, 1.052583, -0.204868}, {0.114503, 0.786281, 0.099216}, {-0.003882, -0.048116, 1.051998}};
    private static final double[][] DEUTERANOPIA = {
            {0.367322, 0.860646, -0.227968}, {0.280085, 0.672501, 0.047413}, {-0.011820, 0.042940, 0.968881}};

    private static ChartPalette solve(Theme theme) {
        boolean dark = theme.isDark();
        Color[] grounds = {theme.background(), theme.surface(), theme.surfaceRaised()};
        List<Color> base = (dark ? DARK : LIGHT).colors;
        Color[] slots = new Color[base.size()];
        for (int i = 0; i < slots.length; i++) {
            Color slot = base.get(i);
            for (int step = 1; step <= 100 && !clears(slot, grounds); step++) {
                slot = moved(base.get(i), dark, step);
            }
            slots[i] = slot;
        }
        // Each move is away from the grounds, so a pair pushed apart keeps its contrast.
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i + 1 < slots.length; i++) {
                for (int step = 0; step < 40 && !apart(slots[i], slots[i + 1]); step++) {
                    double li = oklab(slots[i])[0];
                    double lj = oklab(slots[i + 1])[0];
                    int further = (li < lj) != dark ? i : i + 1;
                    slots[further] = moved(slots[further], dark, 1);
                }
            }
        }
        return new ChartPalette(List.of(slots));
    }

    private static boolean clears(Color colour, Color[] grounds) {
        for (Color ground : grounds) {
            if (Color.contrastRatio(colour, ground) < NON_TEXT_CONTRAST) {
                return false;
            }
        }
        return true;
    }

    private static boolean apart(Color a, Color b) {
        return distance(a, b, null) >= SEPARATION_NORMAL && distance(a, b, PROTANOPIA) >= SEPARATION_CVD
                && distance(a, b, DEUTERANOPIA) >= SEPARATION_CVD;
    }

    /**
     * {@code colour} with its OKLab lightness moved {@code percent} of the way to white (dark) or
     * black (light), hue and chroma kept, the chroma eased only as far as sRGB needs.
     */
    private static Color moved(Color colour, boolean dark, int percent) {
        double[] lab = oklab(colour);
        double lightness = dark ? lab[0] + (1 - lab[0]) * percent / 100.0 : lab[0] * (1 - percent / 100.0);
        for (double chroma = 1; chroma >= 0; chroma -= 0.02) {
            double[] rgb = linearFromOklab(lightness, lab[1] * chroma, lab[2] * chroma);
            if (rgb[0] >= -1e-4 && rgb[0] <= 1 + 1e-4 && rgb[1] >= -1e-4 && rgb[1] <= 1 + 1e-4
                    && rgb[2] >= -1e-4 && rgb[2] <= 1 + 1e-4) {
                return new Color(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]), colour.a());
            }
        }
        float grey = encode(lightness * lightness * lightness);
        return new Color(grey, grey, grey, colour.a());
    }

    /** OKLab ΔE × 100 between two colours, seen through {@code vision} (null: normal vision). */
    private static double distance(Color a, Color b, double[][] vision) {
        double[] la = oklab(a, vision);
        double[] lb = oklab(b, vision);
        return 100 * Math.sqrt(sq(la[0] - lb[0]) + sq(la[1] - lb[1]) + sq(la[2] - lb[2]));
    }

    private static double[] oklab(Color colour) {
        return oklab(colour, null);
    }

    private static double[] oklab(Color colour, double[][] vision) {
        double r = linear(colour.r());
        double g = linear(colour.g());
        double b = linear(colour.b());
        if (vision != null) {
            double[] v = {r, g, b};
            r = clamp(vision[0][0] * v[0] + vision[0][1] * v[1] + vision[0][2] * v[2]);
            g = clamp(vision[1][0] * v[0] + vision[1][1] * v[1] + vision[1][2] * v[2]);
            b = clamp(vision[2][0] * v[0] + vision[2][1] * v[1] + vision[2][2] * v[2]);
        }
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[] {
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s};
    }

    private static double[] linearFromOklab(double lightness, double a, double b) {
        double l = cube(lightness + 0.3963377774 * a + 0.2158037573 * b);
        double m = cube(lightness - 0.1055613458 * a - 0.0638541728 * b);
        double s = cube(lightness - 0.0894841775 * a - 1.2914855480 * b);
        return new double[] {
                4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }

    private static double linear(float channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static float encode(double linear) {
        double c = clamp(linear);
        return (float) (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double cube(double v) {
        return v * v * v;
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
