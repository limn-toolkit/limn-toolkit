package limn.print.internal;

import limn.graphics.Color;
import limn.graphics.Gradient;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link Gradient} in PDF's own terms: a linear one as an axial shading and a radial one, from its
 * focus, as a radial shading of two circles, each with a function stitched from its stops, which
 * reads a hard edge as exactly as the screen; a repeated or reflected one as that function over as
 * many turns as the region it may paint spans; and a sweep, which PDF has no shading for, as a mesh
 * of triangles fanned round its centre, a degree each at most and split at every stop, out past the
 * region. Pure: what it answers is written by {@link PdfWriter}.
 */
final class PdfGradients {

    /** The most turns of a repeated gradient written out; past them the end colours hold. */
    static final int MAX_TURNS = 4096;

    private PdfGradients() {
    }

    /**
     * The shading dictionary of {@code g}'s colours, or of their alphas times {@code opacity} as grey
     * when {@code gray}, in the gradient's own space, covering {@code region}: the corners, x and y of
     * each, of what it may paint in that space.
     *
     * @return the dictionary, and for a sweep its mesh as {@link #meshData}
     */
    static Shading shading(Gradient g, boolean gray, float opacity, double[] region) {
        float[] q = g.geometry();
        String space = gray ? "/DeviceGray" : "/DeviceRGB";
        float[] positions = g.positions();
        double[][] colors = components(g.colors(), gray, opacity);
        switch (g.kind()) {
            case LINEAR -> {
                double dx = q[2] - q[0];
                double dy = q[3] - q[1];
                double l2 = dx * dx + dy * dy;
                double lo = Double.POSITIVE_INFINITY;
                double hi = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < region.length; i += 2) {
                    double t = ((region[i] - q[0]) * dx + (region[i + 1] - q[1]) * dy) / l2;
                    lo = Math.min(lo, t);
                    hi = Math.max(hi, t);
                }
                double[] range = range(g.spread(), lo, hi);
                String coords = num(q[0] + range[0] * dx) + " " + num(q[1] + range[0] * dy) + " "
                        + num(q[0] + range[1] * dx) + " " + num(q[1] + range[1] * dy);
                return new Shading("<</ShadingType 2/ColorSpace" + space + "/Coords[" + coords + "]/Domain["
                        + num(range[0]) + " " + num(range[1]) + "]/Function"
                        + function(positions, colors, g.spread(), range) + "/Extend[true true]>>", null);
            }
            case RADIAL -> {
                double cx = q[0];
                double cy = q[1];
                double r = q[2];
                double fx = q[3];
                double fy = q[4];
                double hi = 0;
                for (int i = 0; i < region.length; i += 2) {
                    hi = Math.max(hi, radialT(region[i], region[i + 1], cx, cy, r, fx, fy));
                }
                double[] range = range(g.spread(), 0, hi);
                double t0 = range[0];
                double t1 = range[1];
                String coords = num(fx + t0 * (cx - fx)) + " " + num(fy + t0 * (cy - fy)) + " " + num(t0 * r) + " "
                        + num(fx + t1 * (cx - fx)) + " " + num(fy + t1 * (cy - fy)) + " " + num(t1 * r);
                return new Shading("<</ShadingType 3/ColorSpace" + space + "/Coords[" + coords + "]/Domain["
                        + num(t0) + " " + num(t1) + "]/Function" + function(positions, colors, g.spread(), range)
                        + "/Extend[true true]>>", null);
            }
            default -> {
                double cx = q[0];
                double cy = q[1];
                double reach = 1;
                for (int i = 0; i < region.length; i += 2) {
                    reach = Math.max(reach, Math.hypot(region[i] - cx, region[i + 1] - cy));
                }
                reach = reach * 1.02 + 1;
                int n = colors[0].length;
                StringBuilder decode = new StringBuilder();
                decode.append(num(cx - reach)).append(' ').append(num(cx + reach)).append(' ')
                        .append(num(cy - reach)).append(' ').append(num(cy + reach));
                for (int i = 0; i < n; i++) {
                    decode.append(" 0 1");
                }
                return new Shading("<</ShadingType 4/ColorSpace" + space + "/BitsPerCoordinate 32"
                        + "/BitsPerComponent 16/BitsPerFlag 8/Decode[" + decode + "]",
                        meshData(cx, cy, q[2], reach, positions, colors));
            }
        }
    }

    /** A shading's dictionary (open, for a mesh, to take its stream's keys) and a mesh's data. */
    record Shading(String dictionary, byte[] mesh) {
    }

    /** The span of t the shading covers: [0, 1] padded, or whole turns over what the region spans. */
    private static double[] range(Gradient.Spread spread, double lo, double hi) {
        if (spread == Gradient.Spread.PAD) {
            return new double[] {0, 1};
        }
        double t0 = Math.floor(Math.min(lo, 0));
        double t1 = Math.ceil(Math.max(hi, 1));
        if (t1 - t0 > MAX_TURNS) {
            double mid = Math.floor((Math.max(lo, -1e9) + Math.min(hi, 1e9)) / 2);
            t0 = mid - MAX_TURNS / 2.0;
            t1 = mid + MAX_TURNS / 2.0;
        }
        return new double[] {t0, t1};
    }

    /** Where a point lies on a radial gradient from its focus to its circle: the circle's share. */
    static double radialT(double x, double y, double cx, double cy, double r, double fx, double fy) {
        double gx = (x - cx) / r;
        double gy = (y - cy) / r;
        double ffx = (fx - cx) / r;
        double ffy = (fy - cy) / r;
        double dx = gx - ffx;
        double dy = gy - ffy;
        double a = dx * dx + dy * dy;
        double b = ffx * dx + ffy * dy;
        double c = ffx * ffx + ffy * ffy - 1;
        return a / Math.max(-b + Math.sqrt(Math.max(b * b - a * c, 0)), 1e-12);
    }

    /** Each stop's colour components: RGB, or the alpha times the opacity as grey. */
    private static double[][] components(List<Color> colors, boolean gray, float opacity) {
        double[][] out = new double[colors.size()][];
        for (int i = 0; i < out.length; i++) {
            Color c = colors.get(i);
            out[i] = gray ? new double[] {c.a() * opacity} : new double[] {c.r(), c.g(), c.b()};
        }
        return out;
    }

    /** One stretch of the gradient over [0, 1] where the colour runs straight from one to another. */
    private record Piece(double from, double to, double[] c0, double[] c1) {
    }

    /** The stretches of one turn: the first colour before its stop, each pair between, the last after. */
    private static List<Piece> pieces(float[] positions, double[][] colors) {
        List<Piece> pieces = new ArrayList<>();
        int n = positions.length;
        if (positions[0] > 0) {
            pieces.add(new Piece(0, positions[0], colors[0], colors[0]));
        }
        for (int i = 0; i + 1 < n; i++) {
            if (positions[i + 1] > positions[i]) {
                pieces.add(new Piece(positions[i], positions[i + 1], colors[i], colors[i + 1]));
            }
        }
        if (positions[n - 1] < 1 || pieces.isEmpty()) {
            pieces.add(new Piece(positions[n - 1], Math.max(1, positions[n - 1] + 1e-6), colors[n - 1],
                    colors[n - 1]));
        }
        return pieces;
    }

    /**
     * A stitching function over {@code range} of every stretch of every turn in it, reflected turns
     * read backwards: a straight run between two colours each, joined at bounds that only grow.
     */
    private static String function(float[] positions, double[][] colors, Gradient.Spread spread, double[] range) {
        List<Piece> turn = pieces(positions, colors);
        List<Piece> all = new ArrayList<>();
        int first = (int) Math.round(range[0]);
        int last = (int) Math.round(range[1]);
        for (int k = first; k < last; k++) {
            boolean back = spread == Gradient.Spread.REFLECT && Math.floorMod(k, 2) == 1;
            if (back) {
                for (int i = turn.size() - 1; i >= 0; i--) {
                    Piece p = turn.get(i);
                    all.add(new Piece(k + 1 - p.to, k + 1 - p.from, p.c1, p.c0));
                }
            } else {
                for (Piece p : turn) {
                    all.add(new Piece(k + p.from, k + p.to, p.c0, p.c1));
                }
            }
        }
        StringBuilder functions = new StringBuilder();
        StringBuilder bounds = new StringBuilder();
        StringBuilder encode = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            Piece p = all.get(i);
            functions.append("<</FunctionType 2/Domain[0 1]/C0[").append(join(p.c0)).append("]/C1[")
                    .append(join(p.c1)).append("]/N 1>>");
            encode.append(i == 0 ? "" : " ").append("0 1");
            if (i > 0) {
                bounds.append(i == 1 ? "" : " ").append(num(p.from));
            }
        }
        return "<</FunctionType 3/Domain[" + num(range[0]) + " " + num(range[1]) + "]/Functions[" + functions
                + "]/Bounds[" + bounds + "]/Encode[" + encode + "]>>";
    }

    /**
     * A sweep's mesh: per stretch of the turn, triangles from the centre to an arc past the region, a
     * degree at most each, every vertex its own colour and every triangle its own (flag 0). The centre
     * takes the colour at the triangle's middle angle, so that a colour running straight with the
     * angle is what each triangle shows, to within half a degree near the centre.
     */
    static byte[] meshData(double cx, double cy, double start, double reach, float[] positions, double[][] colors) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Piece p : pieces(positions, colors)) {
            int steps = Math.max(1, (int) Math.ceil((p.to - p.from) * 360));
            for (int s = 0; s < steps; s++) {
                double ta = p.from + (p.to - p.from) * s / steps;
                double tb = p.from + (p.to - p.from) * (s + 1) / steps;
                double[] ca = lerp(p, ta);
                double[] cb = lerp(p, tb);
                double[] cm = lerp(p, (ta + tb) / 2);
                vertex(out, cx, cy, cx, cy, reach, cm);
                double aa = start + 2 * Math.PI * ta;
                double ab = start + 2 * Math.PI * tb;
                vertex(out, cx + reach * Math.cos(aa), cy + reach * Math.sin(aa), cx, cy, reach, ca);
                vertex(out, cx + reach * Math.cos(ab), cy + reach * Math.sin(ab), cx, cy, reach, cb);
            }
        }
        return out.toByteArray();
    }

    private static double[] lerp(Piece p, double t) {
        double w = p.to > p.from ? (t - p.from) / (p.to - p.from) : 0;
        double[] c = new double[p.c0.length];
        for (int i = 0; i < c.length; i++) {
            c[i] = p.c0[i] + (p.c1[i] - p.c0[i]) * w;
        }
        return c;
    }

    private static void vertex(ByteArrayOutputStream out, double x, double y, double cx, double cy, double reach,
                               double[] color) {
        out.write(0);
        int32(out, (x - (cx - reach)) / (2 * reach));
        int32(out, (y - (cy - reach)) / (2 * reach));
        for (double c : color) {
            long v = Math.round(Math.max(0, Math.min(1, c)) * 65535);
            out.write((int) (v >> 8));
            out.write((int) v);
        }
    }

    private static void int32(ByteArrayOutputStream out, double unit) {
        long v = Math.round(Math.max(0, Math.min(1, unit)) * 4294967295.0);
        out.write((int) (v >> 24));
        out.write((int) (v >> 16));
        out.write((int) (v >> 8));
        out.write((int) v);
    }

    private static String join(double[] values) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            s.append(i == 0 ? "" : " ").append(num(values[i]));
        }
        return s.toString();
    }

    private static String num(double v) {
        return Pdf.num((float) v);
    }
}
