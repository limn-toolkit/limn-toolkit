package limn.print.internal;

import limn.graphics.Path2D;
import limn.graphics.RoundRect;

import java.util.function.Consumer;

/**
 * The shapes a canvas draws, spelled as PDF path operators: curves as cubics, since PDF has no
 * quadratic, and rounds and ellipses as four cubic arcs each, the usual quarter-circle constant
 * keeping the error under three parts in ten thousand of the radius.
 */
final class PdfPaths {

    /** How far along the tangent a quarter circle's control points lie, in radii. */
    private static final double KAPPA = 0.5522847498307936;

    private PdfPaths() {
    }

    /** A glyph's outline, in font units. */
    static void outline(GlyphBank.Outline outline, StringBuilder ops) {
        byte[] verbs = outline.verbs();
        float[] p = outline.points();
        int i = 0;
        float cx = 0;
        float cy = 0;
        for (byte verb : verbs) {
            switch (verb) {
                case GlyphBank.Outline.MOVE -> {
                    cx = p[i++];
                    cy = p[i++];
                    move(ops, cx, cy);
                }
                case GlyphBank.Outline.LINE -> {
                    cx = p[i++];
                    cy = p[i++];
                    line(ops, cx, cy);
                }
                case GlyphBank.Outline.QUAD -> {
                    float qx = p[i++];
                    float qy = p[i++];
                    float x = p[i++];
                    float y = p[i++];
                    quadAsCubic(ops, cx, cy, qx, qy, x, y);
                    cx = x;
                    cy = y;
                }
                case GlyphBank.Outline.CUBIC -> {
                    cubic(ops, p[i], p[i + 1], p[i + 2], p[i + 3], p[i + 4], p[i + 5]);
                    cx = p[i + 4];
                    cy = p[i + 5];
                    i += 6;
                }
                case GlyphBank.Outline.CLOSE -> ops.append("h\n");
                default -> throw new AssertionError(verb);
            }
        }
    }

    /**
     * A canvas path, one subpath at a time: each subpath goes to {@code each} whole, since the
     * canvas fills each closed subpath on its own and a PDF fill of all of them at once would cut
     * holes where they overlap the other way round.
     */
    static void subpaths(Path2D path, Consumer<String> each) {
        StringBuilder[] current = {null};
        float[] point = new float[4]; // current x, y; subpath start x, y
        path.forEach(new Path2D.Visitor() {
            @Override
            public void moveTo(float x, float y) {
                flush();
                current[0] = new StringBuilder(64);
                move(current[0], x, y);
                point[0] = x;
                point[1] = y;
                point[2] = x;
                point[3] = y;
            }

            @Override
            public void lineTo(float x, float y) {
                line(current[0], x, y);
                point[0] = x;
                point[1] = y;
            }

            @Override
            public void quadTo(float cx, float cy, float x, float y) {
                quadAsCubic(current[0], point[0], point[1], cx, cy, x, y);
                point[0] = x;
                point[1] = y;
            }

            @Override
            public void cubicTo(float c1x, float c1y, float c2x, float c2y, float x, float y) {
                cubic(current[0], c1x, c1y, c2x, c2y, x, y);
                point[0] = x;
                point[1] = y;
            }

            @Override
            public void close() {
                current[0].append("h\n");
                point[0] = point[2];
                point[1] = point[3];
            }

            private void flush() {
                if (current[0] != null) {
                    each.accept(current[0].toString());
                    current[0] = null;
                }
            }
        });
        // The last subpath has no move after it to hand it over.
        if (current[0] != null) {
            each.accept(current[0].toString());
        }
    }

    /** A whole canvas path, every subpath together, for a stroke. */
    static String whole(Path2D path) {
        StringBuilder ops = new StringBuilder(128);
        subpaths(path, ops::append);
        return ops.toString();
    }

    /** A rounded rectangle, its radii normalized and capped at half the shorter side as renderers do. */
    static String roundRect(RoundRect shape) {
        RoundRect r = shape.normalized();
        float x = r.x();
        float y = r.y();
        float w = r.width();
        float h = r.height();
        float cap = Math.min(w, h) / 2;
        float tl = Math.min(r.topLeft(), cap);
        float tr = Math.min(r.topRight(), cap);
        float br = Math.min(r.bottomRight(), cap);
        float bl = Math.min(r.bottomLeft(), cap);
        StringBuilder ops = new StringBuilder(160);
        if (tl == 0 && tr == 0 && br == 0 && bl == 0) {
            return rect(x, y, w, h);
        }
        double k = KAPPA;
        move(ops, x + tl, y);
        line(ops, x + w - tr, y);
        if (tr > 0) {
            cubic(ops, (float) (x + w - tr + tr * k), y, x + w, (float) (y + tr - tr * k), x + w, y + tr);
        }
        line(ops, x + w, y + h - br);
        if (br > 0) {
            cubic(ops, x + w, (float) (y + h - br + br * k), (float) (x + w - br + br * k), y + h, x + w - br, y + h);
        }
        line(ops, x + bl, y + h);
        if (bl > 0) {
            cubic(ops, (float) (x + bl - bl * k), y + h, x, (float) (y + h - bl + bl * k), x, y + h - bl);
        }
        line(ops, x, y + tl);
        if (tl > 0) {
            cubic(ops, x, (float) (y + tl - tl * k), (float) (x + tl - tl * k), y, x + tl, y);
        }
        ops.append("h\n");
        return ops.toString();
    }

    static String rect(float x, float y, float w, float h) {
        return Pdf.num(x) + " " + Pdf.num(y) + " " + Pdf.num(w) + " " + Pdf.num(h) + " re\n";
    }

    /** An ellipse about {@code (cx, cy)}. */
    static String ellipse(float cx, float cy, float rx, float ry) {
        double kx = rx * KAPPA;
        double ky = ry * KAPPA;
        StringBuilder ops = new StringBuilder(160);
        move(ops, cx + rx, cy);
        cubic(ops, cx + rx, (float) (cy + ky), (float) (cx + kx), cy + ry, cx, cy + ry);
        cubic(ops, (float) (cx - kx), cy + ry, cx - rx, (float) (cy + ky), cx - rx, cy);
        cubic(ops, cx - rx, (float) (cy - ky), (float) (cx - kx), cy - ry, cx, cy - ry);
        cubic(ops, (float) (cx + kx), cy - ry, cx + rx, (float) (cy - ky), cx + rx, cy);
        ops.append("h\n");
        return ops.toString();
    }

    private static void move(StringBuilder ops, float x, float y) {
        ops.append(Pdf.num(x)).append(' ').append(Pdf.num(y)).append(" m\n");
    }

    private static void line(StringBuilder ops, float x, float y) {
        ops.append(Pdf.num(x)).append(' ').append(Pdf.num(y)).append(" l\n");
    }

    private static void cubic(StringBuilder ops, float c1x, float c1y, float c2x, float c2y, float x, float y) {
        ops.append(Pdf.num(c1x)).append(' ').append(Pdf.num(c1y)).append(' ').append(Pdf.num(c2x)).append(' ')
                .append(Pdf.num(c2y)).append(' ').append(Pdf.num(x)).append(' ').append(Pdf.num(y)).append(" c\n");
    }

    /** A quadratic from {@code (x0, y0)} as the cubic that is the same curve. */
    private static void quadAsCubic(StringBuilder ops, float x0, float y0, float qx, float qy, float x, float y) {
        cubic(ops, x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0),
                x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y), x, y);
    }
}
