package limn.graphics;

import java.util.Objects;

/**
 * An {@link Icon} backed by pre-rendered {@link Image}s (typically PNGs). Covers
 * the raster cases that don't come from vectors:
 *
 * <ul>
 *   <li>{@link #mask(Image...)}: a monochrome or grayscale <em>mask</em> that is
 *       recolored with the theme ({@link #tintable()} is {@code true}). Pass more
 *       than one image to supply per-resolution variants (e.g. {@code @1x},
 *       {@code @2x}); {@link #image} picks the smallest one that still covers the
 *       requested device size.</li>
 *   <li>{@link #themed(Image, Image)}: pre-colored <em>light</em> and <em>dark</em>
 *       variants, drawn as-is ({@code tintable()} is {@code false}); {@link #image}
 *       chooses by theme brightness.</li>
 *   <li>{@link #picture(Image)}: a single pre-colored image, drawn as-is.</li>
 * </ul>
 *
 * <p>Bitmaps do not resample as cleanly as vectors, so for crisp results supply a
 * variant at (or above) the size you draw at, or prefer {@link SvgIcon}.
 *
 * <p><b>An animated image makes an animated icon</b> ({@link Image#isAnimated()}), which a
 * component plays by itself (see {@link Icon}). Every image of one icon is then animated, with
 * the same number of frames shown for the same times and turning as many times, so that a theme
 * or a resolution changes what is drawn and never when; an icon of images that differ in that is
 * refused.
 */
public final class BitmapIcon implements Icon {

    private final Image[] variants;   // for a mask: ascending-resolution; else [light, dark]
    private final boolean tintable;

    private BitmapIcon(Image[] variants, boolean tintable) {
        sameTimes(variants);
        this.variants = variants;
        this.tintable = tintable;
    }

    /** Refuses images of one icon that would play differently, still and animated among them. */
    private static void sameTimes(Image[] variants) {
        Image first = variants[0];
        for (int v = 1; v < variants.length; v++) {
            Image other = variants[v];
            boolean same = other.isAnimated() == first.isAnimated()
                    && other.frameCount() == first.frameCount()
                    && other.loopCount() == first.loopCount();
            for (int f = 0; same && f < first.frameCount(); f++) {
                same = other.frameMillis(f) == first.frameMillis(f);
            }
            if (!same) {
                throw new IllegalArgumentException("the images of one icon play alike: image " + v
                        + " has " + other.frameCount() + " frame(s) over " + other.durationMillis()
                        + " ms and " + other.loopCount() + " turn(s), image 0 has "
                        + first.frameCount() + " over " + first.durationMillis() + " ms and "
                        + first.loopCount());
            }
        }
    }

    /**
     * A monochrome/grayscale mask recolored with the theme. Supply one image, or
     * several at different resolutions (any order) to be chosen by size.
     *
     * @throws IllegalArgumentException when there is no image, or the images do not play alike
     */
    public static BitmapIcon mask(Image... resolutions) {
        if (resolutions.length == 0) {
            throw new IllegalArgumentException("mask needs at least one image");
        }
        Image[] copy = resolutions.clone();
        for (Image image : copy) {
            Objects.requireNonNull(image, "image");
        }
        return new BitmapIcon(copy, true);
    }

    /** A single, pre-colored image drawn without tinting; an animated one makes an animated icon. */
    public static BitmapIcon picture(Image image) {
        return new BitmapIcon(new Image[]{Objects.requireNonNull(image, "image")}, false);
    }

    /**
     * Pre-colored light/dark variants, chosen by the active theme's brightness.
     *
     * @throws IllegalArgumentException when the two do not play alike
     */
    public static BitmapIcon themed(Image light, Image dark) {
        return new BitmapIcon(new Image[]{
                Objects.requireNonNull(light, "light"),
                Objects.requireNonNull(dark, "dark")}, false);
    }

    @Override
    public boolean tintable() {
        return tintable;
    }

    @Override
    public boolean isAnimated() {
        return variants[0].isAnimated();
    }

    @Override
    public int frameCount() {
        return variants[0].frameCount();
    }

    @Override
    public int frameMillis(int frame) {
        return variants[0].frameMillis(frame);
    }

    @Override
    public int loopCount() {
        return variants[0].loopCount();
    }

    @Override
    public Image image(int pixelSize, boolean dark, int frame) {
        return image(pixelSize, dark).frame(frame);
    }

    /** {@inheritDoc} For an animated icon, the image itself, whose own pixels are its first frame. */
    @Override
    public Image image(int pixelSize, boolean dark) {
        if (!tintable) {
            // [light, dark] (or [picture] alone): pick by brightness.
            return variants.length > 1 && dark ? variants[1] : variants[0];
        }
        // A tintable mask: pick the smallest variant that still covers the target,
        // falling back to the largest available (upscaling a mask is the last resort).
        Image best = null;
        for (Image candidate : variants) {
            int extent = Math.max(candidate.width(), candidate.height());
            if (extent >= pixelSize && (best == null
                    || extent < Math.max(best.width(), best.height()))) {
                best = candidate;
            }
        }
        if (best != null) {
            return best;
        }
        Image largest = variants[0];
        for (Image candidate : variants) {
            if (Math.max(candidate.width(), candidate.height())
                    > Math.max(largest.width(), largest.height())) {
                largest = candidate;
            }
        }
        return largest;
    }
}
