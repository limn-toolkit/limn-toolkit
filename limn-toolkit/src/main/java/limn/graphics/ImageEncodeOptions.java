package limn.graphics;

import java.util.Objects;

/**
 * What an encode should produce: the {@link ImageFormat}, plus the knobs a format needs. A record
 * rather than a hierarchy of parameter classes: a format that gains a knob gains a component here,
 * and every caller keeps compiling because the shorter constructors stay.
 *
 * <p>For PNG, what trades encoding time against file size is {@link #compression()} and
 * {@link #filtering()}. The defaults are the fastest that still compresses well: a server answering
 * pictures one request at a time spends less time encoding them than drawing them.
 *
 * @param format      the format to write; never null
 * @param quality     1 (smallest) to 100 (best) for a lossy format. <b>A lossless format ignores
 *                    it entirely</b>: PNG output is byte-identical at every quality, so this value
 *                    never explains a size difference there
 * @param compression for a format that deflates its pixels (PNG), the level: 0 stores them, 1 is the
 *                    fastest compression and 9 the smallest; {@link #DEFAULT_COMPRESSION} unless
 *                    set. A lossy format ignores it
 * @param filtering   for a format that filters its rows before deflating them (PNG), how the
 *                    filters are chosen; {@link Filtering#NONE} unless set. A lossy format ignores it
 */
public record ImageEncodeOptions(ImageFormat format, int quality, int compression, Filtering filtering) {

    /** The quality {@link #ImageEncodeOptions(ImageFormat)} uses: high, still lossy. */
    public static final int DEFAULT_QUALITY = 90;

    /**
     * The deflate level unless one is set: 4, where a picture is barely larger than at 9 and takes
     * a fraction of the time. Pinned rather than left to the runtime's own default, which a runtime
     * could change, and every byte written with it.
     */
    public static final int DEFAULT_COMPRESSION = 4;

    /**
     * How a format that filters its rows before deflating them (PNG) chooses each row's filter. A
     * filter rewrites a row as its differences from the pixels beside and above, which a
     * photograph or a screen of text and shading compresses better, and a drawing of flat colour
     * and smooth ramps (an avatar, an icon, a chart) compresses worse: such a row repeats the one
     * above it shifted, which the compressor finds in the row as it is. The file decodes to the
     * same pixels whichever is chosen.
     *
     * <p>A 1.x minor release may add constants to this enum: a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Filtering {
        /** Rows as they are, in one pass: the fastest, and the smallest file for a drawing. */
        NONE,
        /**
         * Each row with the filter that looks smallest, in one pass: some 1.5 times the time of
         * {@link #NONE}, and the smaller file for a photograph or a screen capture.
         */
        ADAPTIVE,
        /**
         * Both passes, keeping the smaller file: the smallest of the two for any picture, in
         * about the time of both.
         */
        SMALLER
    }

    public ImageEncodeOptions {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(filtering, "filtering");
        if (quality < 1 || quality > 100) {
            throw new IllegalArgumentException("quality must be 1..100, got " + quality);
        }
        if (compression < 0 || compression > 9) {
            throw new IllegalArgumentException("compression must be 0..9, got " + compression);
        }
    }

    /**
     * Encodes as {@code format} at {@code quality}, with the default compression and filtering.
     *
     * @param format  the format to write
     * @param quality 1 to 100, for a lossy format
     */
    public ImageEncodeOptions(ImageFormat format, int quality) {
        this(format, quality, DEFAULT_COMPRESSION, Filtering.NONE);
    }

    /**
     * Encodes as {@code format} at {@link #DEFAULT_QUALITY}, with the default compression and
     * filtering.
     *
     * @param format the format to write
     */
    public ImageEncodeOptions(ImageFormat format) {
        this(format, DEFAULT_QUALITY);
    }

    /**
     * These options at another deflate level.
     *
     * @param level 0 (stored) to 9 (smallest)
     * @return the options with that level
     * @throws IllegalArgumentException for a level outside 0 to 9
     */
    public ImageEncodeOptions withCompression(int level) {
        return new ImageEncodeOptions(format, quality, level, filtering);
    }

    /**
     * These options with another way of choosing the rows' filters.
     *
     * @param newFiltering how the filters are chosen
     * @return the options with it
     */
    public ImageEncodeOptions withFiltering(Filtering newFiltering) {
        return new ImageEncodeOptions(format, quality, compression, newFiltering);
    }
}
