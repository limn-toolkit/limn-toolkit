package limn.graphics;

import java.util.Objects;

/**
 * What an encode should produce: the {@link ImageFormat}, plus the knobs a lossy
 * format needs. A record rather than a hierarchy of parameter classes: a format
 * that gains a knob gains a component here, and every caller keeps compiling
 * because the format-only constructor stays.
 *
 * @param format  the format to write; never null
 * @param quality 1 (smallest) to 100 (best) for a lossy format. <b>A lossless
 *                format ignores it entirely</b>: PNG output is byte-identical at
 *                every quality, so this value never explains a size difference
 *                there. It is not a compression <em>level</em>; nothing in this
 *                toolkit lets you trade PNG encode time against file size.
 */
public record ImageEncodeOptions(ImageFormat format, int quality) {

    /** The quality {@link #ImageEncodeOptions(ImageFormat)} uses: high, still lossy. */
    public static final int DEFAULT_QUALITY = 90;

    public ImageEncodeOptions {
        Objects.requireNonNull(format, "format");
        if (quality < 1 || quality > 100) {
            throw new IllegalArgumentException("quality must be 1..100, got " + quality);
        }
    }

    /** Encodes as {@code format} at {@link #DEFAULT_QUALITY}. */
    public ImageEncodeOptions(ImageFormat format) {
        this(format, DEFAULT_QUALITY);
    }
}
