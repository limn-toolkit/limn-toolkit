package limn.transfer;

import limn.graphics.Image;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One representation of what is copied or dragged, and the Java type that holds it.
 *
 * <p>Four formats are the ones every desktop program exchanges: {@link #TEXT}, {@link #HTML},
 * {@link #IMAGE} and {@link #FILES}. An application adds its own in two ways. {@link #of} names a
 * format and says how its value becomes bytes, so it can cross to another program, or to another
 * run of this one. {@link #inProcess} carries the object itself and never leaves this process:
 * what a list hands another list of the same window.
 *
 * <p>Two formats are equal when their names are. Declare each once, in a constant.
 *
 * @param <T> the type of the value this format carries
 */
public final class DataFormat<T> {

    private static final Map<String, DataFormat<?>> NAMED = new ConcurrentHashMap<>();

    /** Plain text. */
    public static final DataFormat<String> TEXT = new DataFormat<>("text/plain", false, null, null);

    /** A fragment of HTML, as a word processor or a spreadsheet pastes it with its formatting. */
    public static final DataFormat<String> HTML = new DataFormat<>("text/html", false, null, null);

    /** A picture. */
    public static final DataFormat<Image> IMAGE = new DataFormat<>("image/png", false, null, null);

    /** Files and directories, as a file manager copies and drags them. */
    public static final DataFormat<List<Path>> FILES =
            new DataFormat<>("text/uri-list", false, null, null);

    private static final List<DataFormat<?>> STANDARD = List.of(TEXT, HTML, IMAGE, FILES);

    private final String name;
    private final boolean inProcess;
    private final @Nullable Function<T, byte[]> encoder;
    private final @Nullable Function<byte[], T> decoder;

    private DataFormat(String name, boolean inProcess, @Nullable Function<T, byte[]> encoder,
            @Nullable Function<byte[], T> decoder) {
        this.name = name;
        this.inProcess = inProcess;
        this.encoder = encoder;
        this.decoder = decoder;
    }

    /**
     * Declares a format of the application's own, carried as bytes under a name another program
     * can ask for too. From this call on, something offered under that name by anyone is listed
     * by {@link Offer#formats()} as this format.
     *
     * <p>The name is written as a media type, {@code application/x-acme-order}: lower case, one
     * slash, no spaces. It is the type the Linux desktops see as written, a registered clipboard
     * format on Windows and a pasteboard type on macOS. Declaring a name again replaces the
     * earlier declaration.
     *
     * @param name   the format's name
     * @param encode the value as bytes
     * @param decode the bytes as a value; what it throws fails the read
     * @param <T>    the type of the value
     * @return the format
     * @throws IllegalArgumentException if the name is not a media type, or is one of the four
     *                                  standard formats'
     */
    public static <T> DataFormat<T> of(String name, Function<T, byte[]> encode,
            Function<byte[], T> decode) {
        Objects.requireNonNull(encode, "encode");
        Objects.requireNonNull(decode, "decode");
        DataFormat<T> format = new DataFormat<>(checked(name), false, encode, decode);
        NAMED.put(format.name, format);
        return format;
    }

    /**
     * Declares a format that carries the object itself. It costs no encoding, it is offered to
     * this process alone, and it is gone when the process ends: another program, and another run
     * of this one, never see it.
     *
     * @param name the format's name, written as {@link #of} asks
     * @param type the class of the value, which a read is checked against
     * @param <T>  the type of the value
     * @return the format
     * @throws IllegalArgumentException if the name is not a media type, or is one of the four
     *                                  standard formats'
     */
    public static <T> DataFormat<T> inProcess(String name, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return new DataFormat<>(checked(name), true, null, null);
    }

    private static String checked(String name) {
        Objects.requireNonNull(name, "name");
        int slash = name.indexOf('/');
        boolean shaped = slash > 0 && slash < name.length() - 1 && slash == name.lastIndexOf('/')
                && name.equals(name.toLowerCase(Locale.ROOT))
                && name.chars().noneMatch(c -> c <= ' ' || c > '~' || c == ';' || c == ',');
        if (!shaped) {
            throw new IllegalArgumentException("a format's name is a media type such as "
                    + "application/x-acme-order, got \"" + name + "\"");
        }
        for (DataFormat<?> standard : STANDARD) {
            if (standard.name.equals(name)) {
                throw new IllegalArgumentException(name + " is a standard format's name");
            }
        }
        return name;
    }

    /**
     * The format an application declared under a name with {@link #of}, for a backend that finds
     * the name among what a platform offers.
     *
     * @param name a native type's name
     * @return the declared format, or null when the application declared none by that name
     */
    public static @Nullable DataFormat<?> declared(String name) {
        return NAMED.get(name);
    }

    /** @return the format's name: a media type */
    public String name() {
        return name;
    }

    /** @return whether this is one of {@link #TEXT}, {@link #HTML}, {@link #IMAGE}, {@link #FILES} */
    public boolean isStandard() {
        return STANDARD.contains(this);
    }

    /** @return whether the value is carried as the object itself, within this process only */
    public boolean isInProcess() {
        return inProcess;
    }

    /**
     * The value as the bytes another program is handed; for a backend.
     *
     * @param value the value
     * @return its bytes
     * @throws UnsupportedOperationException for a standard or an in-process format, whose bytes
     *                                       are the platform's business or do not exist
     */
    public byte[] encode(T value) {
        if (encoder == null) {
            throw new UnsupportedOperationException(name + " has no encoding of its own");
        }
        return encoder.apply(value);
    }

    /**
     * The bytes another program handed over as a value; for a backend.
     *
     * @param bytes the bytes
     * @return the value
     * @throws UnsupportedOperationException for a standard or an in-process format
     */
    public T decode(byte[] bytes) {
        if (decoder == null) {
            throw new UnsupportedOperationException(name + " has no encoding of its own");
        }
        return decoder.apply(bytes);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DataFormat<?> format && format.name.equals(name)
                && format.inProcess == inProcess;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
