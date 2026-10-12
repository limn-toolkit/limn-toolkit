package limn.io;

import java.util.Objects;

/**
 * What a file, a resource or a block of bytes is refused with when what it holds is not what its
 * format says: a picture, a sound, a video, a font, a model, a theme, cut short or wrong in any
 * way. It is the one exception an application that opens a user's file catches to tell the user
 * the file is broken.
 *
 * <p>It is <em>not</em> what a file that cannot be read arrives as, which is an I/O failure
 * ({@link java.io.IOException} or {@link java.io.UncheckedIOException}); nor a format nothing
 * here decodes, which is an {@link UnsupportedOperationException}; nor a wrong argument of the
 * caller's own, which stays an {@link IllegalArgumentException}. Its message says what is wrong,
 * in words for a log.
 */
public final class MalformedContentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String format;
    private final long position;

    /**
     * @param format  the format's name as a person writes it, {@code "glTF"}, {@code "PNG"}
     * @param message what is wrong with the content
     */
    public MalformedContentException(String format, String message) {
        this(format, message, -1, null);
    }

    /**
     * @param format  the format's name
     * @param message what is wrong with the content
     * @param cause   what a decoder underneath said, or {@code null}
     */
    public MalformedContentException(String format, String message, Throwable cause) {
        this(format, message, -1, cause);
    }

    /**
     * @param format   the format's name
     * @param message  what is wrong with the content
     * @param position where: a line of a text, counted from 1, or an offset into the bytes,
     *                 counted from 0, as the format's reader documents; {@code -1} for unknown
     * @param cause    what a decoder underneath said, or {@code null}
     */
    public MalformedContentException(String format, String message, long position, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.format = Objects.requireNonNull(format, "format");
        this.position = position < 0 ? -1 : position;
    }

    /** @return the name of the format the content was read as */
    public String format() {
        return format;
    }

    /** @return the line or the offset the reader stopped at, or {@code -1} where it knows none */
    public long position() {
        return position;
    }
}
