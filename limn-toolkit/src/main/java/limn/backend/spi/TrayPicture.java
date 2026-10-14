package limn.backend.spi;

import java.util.Objects;
import limn.graphics.Color;
import limn.graphics.Icon;
import limn.graphics.Image;
import org.jspecify.annotations.Nullable;

/**
 * A tray icon's picture at whatever size a system asks for: the application's icon, and the dot
 * that says attention when it has one.
 *
 * <p>Two pictures are equal when they would draw the same thing, so a backend can tell a state
 * that changed its picture from one that did not. An animated icon is played by the tray, which
 * hands the peer a state for each frame as it falls due: a picture of the frame that changed is
 * not equal to the one before, and is shown as any new picture is.
 *
 * @param icon        the application's icon
 * @param badge       the dot's colour, or {@code null} for no dot
 * @param badgeAtLeft whether the dot is at the top left corner and not the top right
 * @param frame       the frame of an animated icon to draw, from 0; 0 for a still one
 */
public record TrayPicture(Icon icon, @Nullable Color badge, boolean badgeAtLeft, int frame) {

    /** The part of the box's side the dot's radius is. */
    private static final float BADGE_RADIUS = 0.22f;

    /**
     * Checks the icon and the frame.
     *
     * @throws IndexOutOfBoundsException when the icon has no such frame
     */
    public TrayPicture {
        Objects.requireNonNull(icon, "icon");
        Objects.checkIndex(frame, icon.frameCount());
    }

    /**
     * The first frame of an icon: the whole of a still one, and what a menu shows of an
     * animated one.
     *
     * @param icon        the application's icon
     * @param badge       the dot's colour, or {@code null} for no dot
     * @param badgeAtLeft whether the dot is at the top left corner and not the top right
     */
    public TrayPicture(Icon icon, @Nullable Color badge, boolean badgeAtLeft) {
        this(icon, badge, badgeAtLeft, 0);
    }

    /**
     * Draws the picture into a square of {@code pixelSize}: the icon's {@link #frame} fitted and
     * centred, in {@code tint} when it is one that takes a colour and a tint is given, and the dot
     * over it, which every frame carries.
     *
     * @param pixelSize the square's side in device pixels, at least 1
     * @param tint      the colour of a tintable icon, or {@code null} to leave it as it is
     * @param dark      whether the surface behind is dark, for an icon with a light and a dark form
     * @return the picture, straight alpha
     */
    public Image render(int pixelSize, @Nullable Color tint, boolean dark) {
        if (pixelSize < 1) {
            throw new IllegalArgumentException("a picture's size is at least 1: " + pixelSize);
        }
        Image source = icon.image(pixelSize, dark, frame);
        byte[] out = fit(source, pixelSize);
        if (tint != null && icon.tintable()) {
            int r = Color.toByte(tint.r());
            int g = Color.toByte(tint.g());
            int b = Color.toByte(tint.b());
            for (int i = 0; i < out.length; i += 4) {
                out[i] = (byte) r;
                out[i + 1] = (byte) g;
                out[i + 2] = (byte) b;
                out[i + 3] = (byte) Math.round((out[i + 3] & 0xFF) * tint.a());
            }
        }
        if (badge != null) {
            dot(out, pixelSize, badge, badgeAtLeft);
        }
        return new Image(pixelSize, pixelSize, out);
    }

    /** The source scaled to fit a square of {@code size}, centred, by averaging what each pixel covers. */
    private static byte[] fit(Image source, int size) {
        int sw = source.width();
        int sh = source.height();
        byte[] in = source.pixels();
        byte[] out = new byte[size * size * 4];
        double scale = Math.min((double) size / sw, (double) size / sh);
        int w = Math.max(1, (int) Math.round(sw * scale));
        int h = Math.max(1, (int) Math.round(sh * scale));
        int left = (size - w) / 2;
        int top = (size - h) / 2;
        for (int y = 0; y < h; y++) {
            int y0 = (int) ((long) y * sh / h);
            int y1 = Math.max(y0 + 1, (int) ((long) (y + 1) * sh / h));
            for (int x = 0; x < w; x++) {
                int x0 = (int) ((long) x * sw / w);
                int x1 = Math.max(x0 + 1, (int) ((long) (x + 1) * sw / w));
                long r = 0;
                long g = 0;
                long b = 0;
                long a = 0;
                for (int sy = y0; sy < y1; sy++) {
                    for (int sx = x0; sx < x1; sx++) {
                        int at = (sy * sw + sx) * 4;
                        int alpha = in[at + 3] & 0xFF;
                        r += (long) (in[at] & 0xFF) * alpha;
                        g += (long) (in[at + 1] & 0xFF) * alpha;
                        b += (long) (in[at + 2] & 0xFF) * alpha;
                        a += alpha;
                    }
                }
                int count = (y1 - y0) * (x1 - x0);
                int to = ((top + y) * size + left + x) * 4;
                if (a > 0) {
                    out[to] = (byte) (r / a);
                    out[to + 1] = (byte) (g / a);
                    out[to + 2] = (byte) (b / a);
                    out[to + 3] = (byte) (a / count);
                }
            }
        }
        return out;
    }

    /** A filled circle at a top corner, its edge softened over one pixel, laid over what is there. */
    private static void dot(byte[] out, int size, Color color, boolean atLeft) {
        float radius = Math.max(1.5f, size * BADGE_RADIUS);
        float cx = atLeft ? radius : size - radius;
        float cy = radius;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double distance = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
                float cover = (float) Math.max(0, Math.min(1, radius - distance + 0.5)) * color.a();
                if (cover <= 0) {
                    continue;
                }
                int at = (y * size + x) * 4;
                float under = (out[at + 3] & 0xFF) / 255f * (1 - cover);
                float alpha = cover + under;
                out[at] = mix(color.r(), cover, out[at], under, alpha);
                out[at + 1] = mix(color.g(), cover, out[at + 1], under, alpha);
                out[at + 2] = mix(color.b(), cover, out[at + 2], under, alpha);
                out[at + 3] = (byte) Math.round(alpha * 255);
            }
        }
    }

    private static byte mix(float over, float cover, byte under, float underCover, float alpha) {
        return (byte) Math.round((over * 255 * cover + (under & 0xFF) * underCover) / alpha);
    }
}
