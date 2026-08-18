package limn.video.decode;

import limn.video.PixelFormat;
import limn.video.VideoColor;
import limn.video.VideoStreamSource;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * The whole description of a {@linkplain SyntheticVideoDecoder synthetic} stream: what it draws, how
 * big, in which layout, under which colour interpretation, how fast, for how long, and how many
 * pictures it keeps in flight.
 *
 * <p>It has a text form, and the text form is a file name: {@code pattern=counter,size=640x360,
 * format=I420,color=BT709_LIMITED,rate=30-1,frames=0,slots=3.synth}. That is what lets a stream that
 * has no file behind it be opened through the same facade a real file is, with the same
 * {@code canOpen}/{@code open} pair and the same install order, instead of through a side door only
 * this decoder has. Every field appears, so the text round-trips exactly and two specs that print
 * the same are the same.
 *
 * <p>Immutable. The {@code with…} methods return a new spec, so a caller starts from
 * {@link #of(int, int)} and changes the axis it cares about.
 *
 * @param pattern      what the pictures draw
 * @param width        picture width in pixels, in {@code [1..PixelFormat.MAX_DIMENSION]}
 * @param height       picture height in pixels, in the same range
 * @param format       the plane layout every picture uses
 * @param color        how every picture's samples are to be interpreted
 * @param frameRateNum numerator of the nominal frame rate, at least 1 (kept rational so that
 *                     30000/1001 is exact rather than a drifting decimal)
 * @param frameRateDen denominator of the nominal frame rate, at least 1
 * @param frameCount   pictures before the stream ends, or 0 for a stream that never ends; the
 *                     endless one is the default because a demo surface wants something to show for
 *                     as long as it is on screen, and a finite one is what a test asserts an end on
 * @param slots        pictures in flight at once, in {@code [1..FramePool.MAX_SLOTS]}
 */
public record SyntheticSpec(SyntheticPattern pattern, int width, int height, PixelFormat format,
                            VideoColor color, int frameRateNum, int frameRateDen, int frameCount,
                            int slots) {

    /** What a synthetic stream's file name ends with; nothing else claims it. */
    public static final String EXTENSION = ".synth";

    /** How an unsignalled colour is spelled in the text form. */
    private static final String UNSPECIFIED = "unspecified";

    public SyntheticSpec {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(color, "color");
        checkDimension(width, "width");
        checkDimension(height, "height");
        if (frameRateNum < 1) {
            throw new IllegalArgumentException("frameRateNum must be at least 1, got " + frameRateNum);
        }
        if (frameRateDen < 1) {
            throw new IllegalArgumentException("frameRateDen must be at least 1, got " + frameRateDen);
        }
        if (frameCount < 0) {
            throw new IllegalArgumentException("frameCount must be at least 0, got " + frameCount);
        }
        if (slots < 1 || slots > FramePool.MAX_SLOTS) {
            throw new IllegalArgumentException(
                    "slots must be in [1.." + FramePool.MAX_SLOTS + "], got " + slots);
        }
    }

    /**
     * Colour bars at {@code width × height}, I420, BT.709 studio range, 30 per second, endless,
     * three pictures in flight.
     *
     * @throws IllegalArgumentException if a dimension is outside {@code [1..MAX_DIMENSION]}
     */
    public static SyntheticSpec of(int width, int height) {
        return new SyntheticSpec(SyntheticPattern.BARS, width, height, PixelFormat.I420,
                VideoColor.BT709_LIMITED, 30, 1, 0, 3);
    }

    /** @return the same spec drawing {@code newPattern} */
    public SyntheticSpec withPattern(SyntheticPattern newPattern) {
        return new SyntheticSpec(newPattern, width, height, format, color, frameRateNum,
                frameRateDen, frameCount, slots);
    }

    /** @return the same spec at {@code newWidth × newHeight} */
    public SyntheticSpec withSize(int newWidth, int newHeight) {
        return new SyntheticSpec(pattern, newWidth, newHeight, format, color, frameRateNum,
                frameRateDen, frameCount, slots);
    }

    /** @return the same spec in {@code newFormat} */
    public SyntheticSpec withFormat(PixelFormat newFormat) {
        return new SyntheticSpec(pattern, width, height, newFormat, color, frameRateNum,
                frameRateDen, frameCount, slots);
    }

    /** @return the same spec interpreted as {@code newColor} */
    public SyntheticSpec withColor(VideoColor newColor) {
        return new SyntheticSpec(pattern, width, height, format, newColor, frameRateNum,
                frameRateDen, frameCount, slots);
    }

    /** @return the same spec at {@code num/den} pictures a second */
    public SyntheticSpec withRate(int num, int den) {
        return new SyntheticSpec(pattern, width, height, format, color, num, den, frameCount, slots);
    }

    /** @return the same spec ending after {@code count} pictures, or endless when 0 */
    public SyntheticSpec withFrameCount(int count) {
        return new SyntheticSpec(pattern, width, height, format, color, frameRateNum, frameRateDen,
                count, slots);
    }

    /** @return the same spec keeping {@code count} pictures in flight */
    public SyntheticSpec withSlots(int count) {
        return new SyntheticSpec(pattern, width, height, format, color, frameRateNum, frameRateDen,
                frameCount, count);
    }

    /**
     * @return total length in microseconds, or {@link VideoStreamSource#DURATION_UNKNOWN} for an
     *         endless stream
     */
    public long durationMicros() {
        return frameCount == 0
                ? VideoStreamSource.DURATION_UNKNOWN
                : (long) frameCount * 1_000_000L * frameRateDen / frameRateNum;
    }

    /**
     * The presentation time of picture {@code frameIndex}, in microseconds from the start of the
     * stream. Computed from the rational rate rather than accumulated from an interval, so picture
     * 100000 of a 30000/1001 stream is exact rather than a hundred milliseconds adrift.
     *
     * @throws IllegalArgumentException if {@code frameIndex} is negative
     */
    public long ptsMicrosOf(int frameIndex) {
        if (frameIndex < 0) {
            throw new IllegalArgumentException("frameIndex must be at least 0, got " + frameIndex);
        }
        return (long) frameIndex * 1_000_000L * frameRateDen / frameRateNum;
    }

    /**
     * @return the file name this spec is opened under: {@link #toString()} plus
     *         {@link #EXTENSION}. The file need not exist and is never read; it is the description
     *         that is being passed, not a location.
     */
    public String fileName() {
        return this + EXTENSION;
    }

    /**
     * @return {@link #fileName()} as a path, ready for {@link limn.video.Videos#open}. Relative and
     *         bare, so nothing resolves it against a directory that would have to exist.
     */
    public Path path() {
        return Path.of(fileName());
    }

    /**
     * Reads back what {@link #toString()} wrote. Keys may appear in any order and any of them may be
     * left out, in which case {@link #of} supplies it; the value of an unrecognised key is a failure
     * rather than something skipped, because a misspelled key that was silently ignored would leave
     * a stream quietly running at the wrong size.
     *
     * @param text a comma-separated list of {@code key=value}, with or without the
     *             {@link #EXTENSION} suffix
     * @throws IllegalArgumentException if a key is unknown, a value cannot be read, or a value is
     *                                  outside the range its component allows; the message names the
     *                                  offending key
     * @throws NullPointerException     if {@code text} is null
     */
    public static SyntheticSpec parse(String text) {
        Objects.requireNonNull(text, "text");
        String body = text.endsWith(EXTENSION)
                ? text.substring(0, text.length() - EXTENSION.length())
                : text;
        SyntheticSpec spec = of(320, 180);
        if (body.isEmpty()) {
            return spec;
        }
        for (String entry : body.split(",", -1)) {
            int equals = entry.indexOf('=');
            if (equals < 0) {
                throw new IllegalArgumentException(
                        "not a key=value pair: '" + entry + "' in '" + text + "'");
            }
            String key = entry.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = entry.substring(equals + 1).trim();
            spec = switch (key) {
                case "pattern" -> spec.withPattern(parsePattern(value));
                case "size" -> parseSize(spec, value);
                case "format" -> spec.withFormat(parseFormat(value));
                case "color" -> spec.withColor(parseColor(value));
                case "rate" -> parseRate(spec, value);
                case "frames" -> spec.withFrameCount(parseInt(key, value));
                case "slots" -> spec.withSlots(parseInt(key, value));
                default -> throw new IllegalArgumentException(
                        "unknown key '" + key + "' in '" + text + "' (expected one of pattern, "
                                + "size, format, color, rate, frames, slots)");
            };
        }
        return spec;
    }

    /** The text form: every field, in a fixed order, readable back by {@link #parse}. */
    @Override
    public String toString() {
        return "pattern=" + pattern.name().toLowerCase(Locale.ROOT)
                + ",size=" + width + "x" + height
                + ",format=" + format
                + ",color=" + colorText(color)
                + ",rate=" + frameRateNum + "-" + frameRateDen
                + ",frames=" + frameCount
                + ",slots=" + slots;
    }

    private static String colorText(VideoColor color) {
        return color.isSpecified() ? color.matrix() + "_" + color.range() : UNSPECIFIED;
    }

    private static SyntheticPattern parsePattern(String value) {
        try {
            return SyntheticPattern.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown pattern '" + value + "' (expected one of "
                    + java.util.Arrays.toString(SyntheticPattern.values()) + ")");
        }
    }

    private static PixelFormat parseFormat(String value) {
        try {
            return PixelFormat.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown format '" + value + "' (expected one of "
                    + java.util.Arrays.toString(PixelFormat.values()) + ")");
        }
    }

    private static VideoColor parseColor(String value) {
        if (value.equalsIgnoreCase(UNSPECIFIED)) {
            return VideoColor.unspecified();
        }
        int underscore = value.lastIndexOf('_');
        if (underscore > 0) {
            try {
                return VideoColor.of(
                        VideoColor.Matrix.valueOf(
                                value.substring(0, underscore).toUpperCase(Locale.ROOT)),
                        VideoColor.Range.valueOf(
                                value.substring(underscore + 1).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Falls through to the one message that names every accepted spelling.
            }
        }
        throw new IllegalArgumentException("unknown color '" + value + "' (expected " + UNSPECIFIED
                + " or a matrix and range such as BT709_LIMITED)");
    }

    private static SyntheticSpec parseSize(SyntheticSpec spec, String value) {
        int cross = value.toLowerCase(Locale.ROOT).indexOf('x');
        if (cross < 1) {
            throw new IllegalArgumentException("size must be <width>x<height>, got '" + value + "'");
        }
        return spec.withSize(parseInt("size", value.substring(0, cross)),
                parseInt("size", value.substring(cross + 1)));
    }

    private static SyntheticSpec parseRate(SyntheticSpec spec, String value) {
        // A dash and not a slash: this text is a file name, and a slash in it would be a directory
        // separator, which is a bug that hides, because the path still resolves and merely names
        // somewhere that is not there.
        int dash = value.indexOf('-');
        if (dash < 1) {
            throw new IllegalArgumentException("rate must be <num>-<den>, got '" + value + "'");
        }
        return spec.withRate(parseInt("rate", value.substring(0, dash)),
                parseInt("rate", value.substring(dash + 1)));
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "'" + key + "' needs a whole number, got '" + value + "'");
        }
    }

    private static void checkDimension(int value, String name) {
        if (value < 1 || value > PixelFormat.MAX_DIMENSION) {
            throw new IllegalArgumentException(
                    name + " must be in [1.." + PixelFormat.MAX_DIMENSION + "], got " + value);
        }
    }
}
