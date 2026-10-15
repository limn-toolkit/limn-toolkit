package limn.graphics.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The values an animation moves between, and how it moves: numbers with their units, lists of
 * them and path data of the same shape, colours, transforms as matrices, and the easing of SMIL's
 * key splines and CSS's timing functions.
 *
 * <p>Two values interpolate when they have the same shape, the same text between the same count
 * of numbers, or are both colours, or both transforms; otherwise the first holds until halfway and
 * the second after, as CSS says of what does not interpolate.
 */
final class SvgValues {

    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private SvgValues() {
    }

    // ----------------------------------------------------------------------------- numbers

    /** A value split into its numbers and the text around them. */
    record Shape(List<String> text, double[] numbers) {
        boolean sameAs(Shape other) {
            return numbers.length == other.numbers.length && text.equals(other.text);
        }

        String with(double[] values) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < values.length; i++) {
                out.append(text.get(i)).append(format(values[i]));
            }
            return out.append(text.get(values.length)).toString();
        }
    }

    static Shape shape(String value) {
        List<String> text = new ArrayList<>();
        List<Double> numbers = new ArrayList<>();
        Matcher m = NUMBER.matcher(value);
        int at = 0;
        while (m.find()) {
            // A number glued to a letter before it is part of a name (a hex colour, an id): text.
            if (m.start() > 0 && (Character.isLetter(value.charAt(m.start() - 1)) || value.charAt(m.start() - 1) == '#'
                    || value.charAt(m.start() - 1) == '-' && m.start() > 1 && Character.isLetter(value.charAt(m.start() - 2)))
                    && !isPathCommand(value, m.start() - 1)) {
                continue;
            }
            text.add(value.substring(at, m.start()));
            numbers.add(Double.parseDouble(m.group()));
            at = m.end();
        }
        text.add(value.substring(at));
        double[] n = new double[numbers.size()];
        for (int i = 0; i < n.length; i++) {
            n[i] = numbers.get(i);
        }
        return new Shape(text, n);
    }

    /** Whether the letter before a number is a path command, after which a number starts. */
    private static boolean isPathCommand(String value, int at) {
        char c = value.charAt(at);
        return "MmLlHhVvCcSsQqTtAaZz".indexOf(c) >= 0 && (at == 0 || !Character.isLetter(value.charAt(at - 1)));
    }

    static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        String s = String.format(Locale.ROOT, "%.4f", value);
        int end = s.length();
        while (s.charAt(end - 1) == '0') {
            end--;
        }
        return s.charAt(end - 1) == '.' ? s.substring(0, end - 1) : s.substring(0, end);
    }

    /** The first number of a value, such as a length or an opacity, a percentage read as a fraction for {@code fraction}. */
    static double number(@Nullable String value, double otherwise, boolean fraction) {
        if (value == null) {
            return otherwise;
        }
        Matcher m = NUMBER.matcher(value.trim());
        if (!m.lookingAt()) {
            return otherwise;
        }
        double n = Double.parseDouble(m.group());
        return fraction && value.trim().endsWith("%") ? n / 100 : n;
    }

    // ----------------------------------------------------------------------------- interpolation

    /** The value {@code p} of the way from {@code a} to {@code b}. */
    static String interpolate(String a, String b, double p, String property) {
        if (p <= 0) {
            return a;
        }
        if (p >= 1) {
            return b;
        }
        if (property.equals("visibility") || property.equals("display")) {
            // A visibility shows while either end shows; display does not interpolate at all.
            return property.equals("visibility") && (a.equals("visible") || b.equals("visible")) ? "visible" : p < 0.5 ? a : b;
        }
        int[] ca = color(a);
        int[] cb = color(b);
        if (ca != null && cb != null) {
            int[] mix = new int[4];
            for (int i = 0; i < 4; i++) {
                mix[i] = (int) Math.round(ca[i] + (cb[i] - ca[i]) * p);
            }
            return hex(mix);
        }
        Shape sa = shape(unitless(a));
        Shape sb = shape(unitless(b));
        if (sa.numbers.length > 0 && sa.numbers.length == sb.numbers.length && !sa.sameAs(sb)) {
            Shape[] aligned = align(sa, sb);
            sa = aligned[0];
            sb = aligned[1];
        }
        if (sa.numbers.length > 0 && sa.sameAs(sb)) {
            double[] mix = new double[sa.numbers.length];
            for (int i = 0; i < mix.length; i++) {
                mix[i] = sa.numbers[i] + (sb.numbers[i] - sa.numbers[i]) * p;
            }
            return sa.with(mix);
        }
        return p < 0.5 ? a : b;
    }

    /** A value with {@code px} taken off its numbers: in SVG a pixel is a user unit, which a bare number is. */
    static String unitless(String value) {
        return value.indexOf("px") < 0 ? value : value.replaceAll("(?<=[0-9.])px\\b", "");
    }

    /**
     * Two shapes made one where they differ only by a zero written without its unit, as
     * {@code rotate(0)} beside {@code rotate(360deg)}: a zero is the same length or angle in any
     * unit, so it takes the other's. Unchanged when they differ otherwise.
     */
    private static Shape[] align(Shape a, Shape b) {
        List<String> ta = new ArrayList<>(a.text);
        List<String> tb = new ArrayList<>(b.text);
        for (int i = 1; i < ta.size(); i++) {
            String x = ta.get(i);
            String y = tb.get(i);
            if (x.equals(y)) {
                continue;
            }
            String ux = unit(x);
            String uy = unit(y);
            if (ux.isEmpty() && a.numbers[i - 1] == 0 && x.substring(ux.length()).equals(y.substring(uy.length()))) {
                ta.set(i, y);
            } else if (uy.isEmpty() && b.numbers[i - 1] == 0 && y.substring(uy.length()).equals(x.substring(ux.length()))) {
                tb.set(i, x);
            } else {
                return new Shape[] {a, b};
            }
        }
        return new Shape[] {new Shape(ta, a.numbers), new Shape(tb, b.numbers)};
    }

    /** The unit a text begins with: the letters or the percent sign right after a number. */
    private static String unit(String text) {
        int end = 0;
        while (end < text.length() && (Character.isLetter(text.charAt(end)) || text.charAt(end) == '%')) {
            end++;
        }
        return text.substring(0, end);
    }

    /** {@code a + b}, for SMIL's {@code by}: numbers of the same shape added, colours added. */
    static String add(String a, String b) {
        int[] ca = color(a);
        int[] cb = color(b);
        if (ca != null && cb != null) {
            int[] sum = new int[4];
            for (int i = 0; i < 3; i++) {
                sum[i] = Math.min(255, ca[i] + cb[i]);
            }
            sum[3] = ca[3];
            return hex(sum);
        }
        Shape sa = shape(a);
        Shape sb = shape(b);
        if (sa.numbers.length == sb.numbers.length && sa.numbers.length > 0) {
            double[] sum = new double[sa.numbers.length];
            for (int i = 0; i < sum.length; i++) {
                sum[i] = sa.numbers[i] + sb.numbers[i];
            }
            return sa.with(sum);
        }
        return b;
    }

    // ----------------------------------------------------------------------------- colours

    private static final Map<String, Integer> NAMES = Map.ofEntries(
            Map.entry("black", 0x000000), Map.entry("white", 0xFFFFFF), Map.entry("red", 0xFF0000),
            Map.entry("green", 0x008000), Map.entry("lime", 0x00FF00), Map.entry("blue", 0x0000FF),
            Map.entry("yellow", 0xFFFF00), Map.entry("cyan", 0x00FFFF), Map.entry("aqua", 0x00FFFF),
            Map.entry("magenta", 0xFF00FF), Map.entry("fuchsia", 0xFF00FF), Map.entry("gray", 0x808080),
            Map.entry("grey", 0x808080), Map.entry("silver", 0xC0C0C0), Map.entry("maroon", 0x800000),
            Map.entry("olive", 0x808000), Map.entry("navy", 0x000080), Map.entry("purple", 0x800080),
            Map.entry("teal", 0x008080), Map.entry("orange", 0xFFA500), Map.entry("pink", 0xFFC0CB),
            Map.entry("brown", 0xA52A2A), Map.entry("gold", 0xFFD700), Map.entry("indigo", 0x4B0082),
            Map.entry("violet", 0xEE82EE), Map.entry("coral", 0xFF7F50), Map.entry("crimson", 0xDC143C),
            Map.entry("tomato", 0xFF6347), Map.entry("salmon", 0xFA8072), Map.entry("khaki", 0xF0E68C),
            Map.entry("orchid", 0xDA70D6), Map.entry("plum", 0xDDA0DD), Map.entry("tan", 0xD2B48C),
            Map.entry("turquoise", 0x40E0D0), Map.entry("skyblue", 0x87CEEB), Map.entry("steelblue", 0x4682B4),
            Map.entry("royalblue", 0x4169E1), Map.entry("dodgerblue", 0x1E90FF), Map.entry("darkgray", 0xA9A9A9),
            Map.entry("darkgrey", 0xA9A9A9), Map.entry("lightgray", 0xD3D3D3), Map.entry("lightgrey", 0xD3D3D3),
            Map.entry("dimgray", 0x696969), Map.entry("dimgrey", 0x696969), Map.entry("whitesmoke", 0xF5F5F5),
            Map.entry("gainsboro", 0xDCDCDC), Map.entry("darkred", 0x8B0000), Map.entry("darkgreen", 0x006400),
            Map.entry("darkblue", 0x00008B), Map.entry("darkorange", 0xFF8C00), Map.entry("limegreen", 0x32CD32),
            Map.entry("forestgreen", 0x228B22), Map.entry("seagreen", 0x2E8B57), Map.entry("slategray", 0x708090),
            Map.entry("slategrey", 0x708090), Map.entry("lightblue", 0xADD8E6), Map.entry("deepskyblue", 0x00BFFF),
            Map.entry("hotpink", 0xFF69B4), Map.entry("deeppink", 0xFF1493), Map.entry("firebrick", 0xB22222),
            Map.entry("chocolate", 0xD2691E), Map.entry("goldenrod", 0xDAA520), Map.entry("beige", 0xF5F5DC),
            Map.entry("ivory", 0xFFFFF0), Map.entry("lavender", 0xE6E6FA), Map.entry("mintcream", 0xF5FFFA));

    /** RGBA of a colour value, 0 to 255 each, or null when it is no colour. */
    static int @Nullable [] color(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.equals("transparent")) {
            return new int[] {0, 0, 0, 0};
        }
        if (v.startsWith("#")) {
            String h = v.substring(1);
            try {
                switch (h.length()) {
                    case 3, 4 -> {
                        int[] c = new int[4];
                        for (int i = 0; i < 4; i++) {
                            c[i] = i < h.length() ? Integer.parseInt(h.substring(i, i + 1), 16) * 17 : 255;
                        }
                        return c;
                    }
                    case 6, 8 -> {
                        int[] c = new int[4];
                        for (int i = 0; i < 4; i++) {
                            c[i] = i * 2 < h.length() ? Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16) : 255;
                        }
                        return c;
                    }
                    default -> {
                        return null;
                    }
                }
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (v.startsWith("rgb(") || v.startsWith("rgba(")) {
            int open = v.indexOf('(');
            int close = v.lastIndexOf(')');
            if (close < open) {
                return null;
            }
            String[] parts = v.substring(open + 1, close).replace('/', ' ').replace(',', ' ').trim().split("\\s+");
            if (parts.length < 3) {
                return null;
            }
            int[] c = new int[4];
            for (int i = 0; i < 3; i++) {
                double n = number(parts[i], 0, false);
                c[i] = (int) Math.round(Math.max(0, Math.min(255, parts[i].endsWith("%") ? n * 2.55 : n)));
            }
            c[3] = parts.length > 3 ? (int) Math.round(Math.max(0, Math.min(1, number(parts[3], 1, true))) * 255) : 255;
            return c;
        }
        Integer named = NAMES.get(v);
        return named == null ? null : new int[] {named >> 16 & 0xFF, named >> 8 & 0xFF, named & 0xFF, 255};
    }

    private static String hex(int[] c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x%02x", clamp(c[0]), clamp(c[1]), clamp(c[2]), clamp(c[3]));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ----------------------------------------------------------------------------- transforms

    /** A transform function: its name and its arguments, each a number with its unit. */
    record Function(String name, List<String> arguments) {
    }

    static List<Function> functions(String list) {
        List<Function> out = new ArrayList<>();
        int at = 0;
        while (at < list.length()) {
            int open = list.indexOf('(', at);
            if (open < 0) {
                break;
            }
            int close = list.indexOf(')', open);
            if (close < 0) {
                break;
            }
            String name = list.substring(at, open).replace(",", " ").trim().toLowerCase(Locale.ROOT);
            List<String> arguments = new ArrayList<>();
            for (String a : list.substring(open + 1, close).replace(',', ' ').trim().split("\\s+")) {
                if (!a.isEmpty()) {
                    arguments.add(a);
                }
            }
            out.add(new Function(name, arguments));
            at = close + 1;
        }
        return out;
    }

    /** The reference box a CSS transform's percentages are of: x, y, width, height. */
    record Box(double x, double y, double width, double height) {
    }

    /**
     * The matrix of a transform list: {@code a b c d e f}, as SVG writes {@code matrix()}.
     *
     * @param css whether it is CSS's ({@code rotate(45deg)}, {@code translate(10px)}) or the
     *            attribute's ({@code rotate(45 12 12)})
     */
    static double[] matrix(String list, boolean css, Box box) {
        double[] m = {1, 0, 0, 1, 0, 0};
        if (list == null || list.isBlank() || list.trim().equals("none")) {
            return m;
        }
        for (Function f : functions(list)) {
            m = multiply(m, matrixOf(f, css, box));
        }
        return m;
    }

    static double[] matrixOf(Function f, boolean css, Box box) {
        List<String> a = f.arguments;
        double x0 = a.isEmpty() ? 0 : length(a.get(0), box.width);
        double y0 = a.size() < 2 ? 0 : length(a.get(1), box.height);
        return switch (f.name) {
            case "matrix" -> a.size() < 6 ? identity() : new double[] {
                    number(a.get(0), 1, false), number(a.get(1), 0, false), number(a.get(2), 0, false),
                    number(a.get(3), 1, false), number(a.get(4), 0, false), number(a.get(5), 0, false)};
            case "translate" -> new double[] {1, 0, 0, 1, x0, a.size() < 2 ? 0 : y0};
            case "translatex" -> new double[] {1, 0, 0, 1, x0, 0};
            case "translatey" -> new double[] {1, 0, 0, 1, 0, a.isEmpty() ? 0 : length(a.get(0), box.height)};
            case "scale" -> {
                double sx = a.isEmpty() ? 1 : number(a.get(0), 1, true);
                double sy = a.size() < 2 ? sx : number(a.get(1), 1, true);
                yield new double[] {sx, 0, 0, sy, 0, 0};
            }
            case "scalex" -> new double[] {a.isEmpty() ? 1 : number(a.get(0), 1, true), 0, 0, 1, 0, 0};
            case "scaley" -> new double[] {1, 0, 0, a.isEmpty() ? 1 : number(a.get(0), 1, true), 0, 0};
            case "rotate", "rotatez" -> {
                double angle = a.isEmpty() ? 0 : angle(a.get(0), css);
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double[] r = {cos, sin, -sin, cos, 0, 0};
                if (!css && a.size() >= 3) {
                    double cx = number(a.get(1), 0, false);
                    double cy = number(a.get(2), 0, false);
                    r = multiply(multiply(new double[] {1, 0, 0, 1, cx, cy}, r), new double[] {1, 0, 0, 1, -cx, -cy});
                }
                yield r;
            }
            case "skewx" -> new double[] {1, 0, Math.tan(a.isEmpty() ? 0 : angle(a.get(0), css)), 1, 0, 0};
            case "skewy" -> new double[] {1, Math.tan(a.isEmpty() ? 0 : angle(a.get(0), css)), 0, 1, 0, 0};
            case "skew" -> new double[] {1, Math.tan(a.size() < 2 ? 0 : angle(a.get(1), css)),
                    Math.tan(a.isEmpty() ? 0 : angle(a.get(0), css)), 1, 0, 0};
            default -> identity();
        };
    }

    private static double[] identity() {
        return new double[] {1, 0, 0, 1, 0, 0};
    }

    /** An angle in radians: degrees unless a unit says otherwise, and always degrees for the attribute. */
    static double angle(String value, boolean css) {
        double n = number(value, 0, false);
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (css) {
            if (v.endsWith("rad") && !v.endsWith("grad")) {
                return n;
            }
            if (v.endsWith("turn")) {
                return n * 2 * Math.PI;
            }
            if (v.endsWith("grad")) {
                return n * Math.PI / 200;
            }
        }
        return Math.toRadians(n);
    }

    /** A length: a percentage of {@code of}, or the number. */
    static double length(String value, double of) {
        double n = number(value, 0, false);
        return value.trim().endsWith("%") ? n / 100 * of : n;
    }

    static double[] multiply(double[] m, double[] n) {
        return new double[] {
                m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
                m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
                m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5]};
    }

    static String attribute(double[] m) {
        return "matrix(" + format(m[0]) + " " + format(m[1]) + " " + format(m[2]) + " " + format(m[3]) + " "
                + format(m[4]) + " " + format(m[5]) + ")";
    }

    /**
     * A CSS transform list {@code p} of the way from {@code a} to {@code b}: function by function
     * when the two name the same functions in the same order, and their matrices mixed otherwise.
     */
    static double[] interpolateTransform(String a, String b, double p, Box box) {
        List<Function> fa = functions(a.trim().equals("none") ? "" : a);
        List<Function> fb = functions(b.trim().equals("none") ? "" : b);
        if (fa.isEmpty() && !fb.isEmpty()) {
            fa = neutral(fb);
        } else if (fb.isEmpty() && !fa.isEmpty()) {
            fb = neutral(fa);
        }
        boolean same = fa.size() == fb.size();
        for (int i = 0; same && i < fa.size(); i++) {
            same = fa.get(i).name.equals(fb.get(i).name);
        }
        double[] m = identity();
        if (same) {
            for (int i = 0; i < fa.size(); i++) {
                Function x = fa.get(i);
                Function y = fb.get(i);
                List<String> mixed = new ArrayList<>();
                int n = Math.max(x.arguments.size(), y.arguments.size());
                for (int k = 0; k < n; k++) {
                    String ax = k < x.arguments.size() ? x.arguments.get(k) : defaultArgument(x.name, k, y.arguments.get(k));
                    String bx = k < y.arguments.size() ? y.arguments.get(k) : defaultArgument(y.name, k, x.arguments.get(k));
                    mixed.add(interpolate(normalizeAngle(ax), normalizeAngle(bx), p, "transform"));
                }
                m = multiply(m, matrixOf(new Function(x.name, mixed), true, box));
            }
            return m;
        }
        double[] ma = matrix(a, true, box);
        double[] mb = matrix(b, true, box);
        for (int i = 0; i < 6; i++) {
            m[i] = ma[i] + (mb[i] - ma[i]) * p;
        }
        return m;
    }

    /** An angle in degrees, whatever its unit, so two angles of different units mix. */
    private static String normalizeAngle(String argument) {
        String v = argument.trim().toLowerCase(Locale.ROOT);
        if (v.endsWith("turn") || v.endsWith("rad") || v.endsWith("grad")) {
            return format(Math.toDegrees(angle(v, true))) + "deg";
        }
        return argument;
    }

    private static List<Function> neutral(List<Function> like) {
        List<Function> out = new ArrayList<>();
        for (Function f : like) {
            List<String> args = new ArrayList<>();
            for (int k = 0; k < f.arguments.size(); k++) {
                args.add(defaultArgument(f.name, k, f.arguments.get(k)));
            }
            out.add(new Function(f.name, args));
        }
        return out;
    }

    /** The argument that makes a function do nothing, in the unit of {@code like}. */
    private static String defaultArgument(String name, int index, String like) {
        String unit = like.replaceAll("^[+-]?[0-9.eE+-]*", "");
        if (name.startsWith("scale")) {
            return "1";
        }
        if (name.equals("matrix")) {
            return index == 0 || index == 3 ? "1" : "0";
        }
        return "0" + unit;
    }

    // ----------------------------------------------------------------------------- easing

    /** How progress through a segment becomes progress through its values. */
    interface Easing {
        double at(double p);

        Easing LINEAR = p -> p;
    }

    /** CSS's {@code linear}, {@code ease}, {@code cubic-bezier()}, {@code steps()} and the rest; linear when unknown. */
    static Easing cssEasing(@Nullable String value) {
        if (value == null) {
            return bezier(0.25, 0.1, 0.25, 1);
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "linear":
                return Easing.LINEAR;
            case "ease":
                return bezier(0.25, 0.1, 0.25, 1);
            case "ease-in":
                return bezier(0.42, 0, 1, 1);
            case "ease-out":
                return bezier(0, 0, 0.58, 1);
            case "ease-in-out":
                return bezier(0.42, 0, 0.58, 1);
            case "step-start":
                return steps(1, "start");
            case "step-end":
                return steps(1, "end");
            default:
                break;
        }
        if (v.startsWith("cubic-bezier(") && v.endsWith(")")) {
            String[] p = v.substring(13, v.length() - 1).split(",");
            if (p.length == 4) {
                return bezier(number(p[0], 0, false), number(p[1], 0, false), number(p[2], 1, false), number(p[3], 1, false));
            }
        }
        if (v.startsWith("steps(") && v.endsWith(")")) {
            String[] p = v.substring(6, v.length() - 1).split(",");
            int n = Math.max(1, (int) number(p[0], 1, false));
            return steps(n, p.length > 1 ? p[1].trim() : "end");
        }
        return Easing.LINEAR;
    }

    static Easing steps(int n, String position) {
        return p -> {
            int jumps;
            double step;
            switch (position) {
                case "start", "jump-start" -> {
                    jumps = n;
                    step = Math.ceil(p * n) / jumps;
                    return Math.min(1, step);
                }
                case "jump-none" -> {
                    jumps = Math.max(1, n - 1);
                    return Math.min(1, Math.floor(p * n) / jumps);
                }
                case "jump-both" -> {
                    jumps = n + 1;
                    return Math.min(1, (Math.floor(p * n) + 1) / jumps);
                }
                default -> {
                    return p >= 1 ? 1 : Math.floor(p * n) / n;
                }
            }
        };
    }

    /** A cubic Bézier from (0, 0) to (1, 1) through (x1, y1) and (x2, y2), as CSS and SMIL's key splines define. */
    static Easing bezier(double x1, double y1, double x2, double y2) {
        double cx1 = Math.max(0, Math.min(1, x1));
        double cx2 = Math.max(0, Math.min(1, x2));
        return p -> {
            if (p <= 0) {
                return 0;
            }
            if (p >= 1) {
                return 1;
            }
            double lo = 0;
            double hi = 1;
            double t = p;
            for (int i = 0; i < 40; i++) {
                double x = curve(t, cx1, cx2);
                if (Math.abs(x - p) < 1e-7) {
                    break;
                }
                if (x < p) {
                    lo = t;
                } else {
                    hi = t;
                }
                t = (lo + hi) / 2;
            }
            return curve(t, y1, y2);
        };
    }

    private static double curve(double t, double a, double b) {
        double u = 1 - t;
        return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t;
    }
}
