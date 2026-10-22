package limn.print.internal;

import limn.graphics.RoundRect;

/**
 * A soft shadow as a picture, since PDF cannot blur (ADR 078 §7.3): the rounded rectangle's
 * coverage, blurred by a Gaussian whose deviation is half the shadow's blur, as a style sheet's
 * {@code box-shadow} reads its blur.
 *
 * <p>A shadow is soft by nature, so its picture need not be drawn at the document's full raster
 * resolution: it is drawn at the resolution that keeps the blur's deviation under four pixels, and
 * never finer than the document's, and the viewer smooths it as it scales it up. A card's shadow
 * is then a few hundred pixels wide instead of a few thousand, and costs milliseconds.
 */
final class Shadows {

    /** The longest side of a shadow's picture, in pixels. */
    private static final int MAX_SIDE = 1024;

    private Shadows() {
    }

    /**
     * A shadow's picture: its coverage, a byte a pixel, and where it lies in user space.
     *
     * @param alpha   the coverage, rows top down
     * @param columns pixels across
     * @param rows    pixels down
     * @param x       the picture's left edge, in user space
     * @param y       its top
     * @param width   its width in user space
     * @param height  its height
     */
    record Picture(byte[] alpha, int columns, int rows, float x, float y, float width, float height) {
    }

    /**
     * @param shape       the shape, already moved by the shadow's offset
     * @param blur        the shadow's blur, in user units
     * @param rasterScale the document's pixels per user unit
     */
    static Picture rasterize(RoundRect shape, float blur, float rasterScale) {
        double sigma = blur / 2.0;
        double reach = 1.5 * blur;
        double scale = Math.min(rasterScale, 4.0 / sigma);
        double x0 = shape.x() - reach;
        double y0 = shape.y() - reach;
        double w = shape.width() + 2 * reach;
        double h = shape.height() + 2 * reach;
        scale = Math.min(scale, MAX_SIDE / Math.max(w, h));
        int columns = Math.max(1, (int) Math.ceil(w * scale));
        int rows = Math.max(1, (int) Math.ceil(h * scale));
        float[] coverage = new float[columns * rows];
        RoundRect r = shape.normalized();
        float cap = Math.min(r.width(), r.height()) / 2;
        for (int row = 0; row < rows; row++) {
            double py = y0 + (row + 0.5) / scale;
            for (int col = 0; col < columns; col++) {
                double px = x0 + (col + 0.5) / scale;
                double distance = signedDistance(r, cap, px, py) * scale;
                coverage[row * columns + col] = (float) Math.max(0, Math.min(1, 0.5 - distance));
            }
        }
        double sigmaPx = sigma * scale;
        float[] blurred = blur(coverage, columns, rows, sigmaPx);
        byte[] alpha = new byte[blurred.length];
        for (int i = 0; i < blurred.length; i++) {
            alpha[i] = (byte) Math.round(Math.max(0, Math.min(1, blurred[i])) * 255);
        }
        return new Picture(alpha, columns, rows, (float) x0, (float) y0, (float) (columns / scale),
                (float) (rows / scale));
    }

    /** Distance from a point to the rounded rectangle's edge, negative inside, in user units. */
    private static double signedDistance(RoundRect r, float cap, double px, double py) {
        double cx = r.x() + r.width() / 2.0;
        double cy = r.y() + r.height() / 2.0;
        double dx = px - cx;
        double dy = py - cy;
        double radius = dx < 0
                ? (dy < 0 ? r.topLeft() : r.bottomLeft())
                : (dy < 0 ? r.topRight() : r.bottomRight());
        radius = Math.min(radius, cap);
        double qx = Math.abs(dx) - (r.width() / 2.0 - radius);
        double qy = Math.abs(dy) - (r.height() / 2.0 - radius);
        double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
        double inside = Math.min(Math.max(qx, qy), 0);
        return outside + inside - radius;
    }

    /** A separable Gaussian blur with a kernel three deviations wide on each side. */
    private static float[] blur(float[] source, int columns, int rows, double sigma) {
        if (sigma < 0.3) {
            return source;
        }
        int radius = (int) Math.ceil(3 * sigma);
        float[] kernel = new float[2 * radius + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            double v = Math.exp(-(i * i) / (2 * sigma * sigma));
            kernel[i + radius] = (float) v;
            sum += v;
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= (float) sum;
        }
        float[] across = new float[source.length];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                float acc = 0;
                for (int k = -radius; k <= radius; k++) {
                    int c = col + k;
                    if (c >= 0 && c < columns) {
                        acc += source[row * columns + c] * kernel[k + radius];
                    }
                }
                across[row * columns + col] = acc;
            }
        }
        float[] down = new float[source.length];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                float acc = 0;
                for (int k = -radius; k <= radius; k++) {
                    int r = row + k;
                    if (r >= 0 && r < rows) {
                        acc += across[r * columns + col] * kernel[k + radius];
                    }
                }
                down[row * columns + col] = acc;
            }
        }
        return down;
    }
}
