package limn.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads a classpath resource or a file whole, and says what was being read when it cannot.
 *
 * <p>Every loader in the toolkit opens a resource the same way: ask the class loader, throw if
 * it answers nothing, read all the bytes, wrap the {@code IOException}. The nine copies of that
 * block differed only in the noun in the message &mdash; image, audio, glTF, shader, font &mdash;
 * and in how many of the four steps each remembered. This is the block once, with the noun as a
 * parameter, so a failure still says "reading font /limn/fonts/x.ttf" and never "reading
 * resource".
 *
 * <p>Two lookups, because the callers use two. {@link #bytes(Class, String, String)} resolves
 * the name the way {@link Class#getResourceAsStream} does: an absolute name with a leading slash,
 * or one relative to the class's package. {@link #bytes(ClassLoader, String, String)} resolves it
 * the way {@link ClassLoader#getResourceAsStream} does: always from the root, never with a
 * leading slash. A caller keeps whichever it had; the two are not interchangeable.
 *
 * <p>A resource that is absent is an {@link IllegalStateException} (the jar was built without
 * it, which is a fact about the build and not a fault of the caller); a read that fails is an
 * {@link UncheckedIOException}. The {@code IfPresent} forms answer {@code null} for the first
 * case, for the loaders whose resource is optional &mdash; a heavy fallback font, a translation
 * a domain does not carry.
 */
public final class Resources {

    private Resources() {
    }

    /**
     * The whole of a classpath resource, resolved relative to {@code owner}.
     *
     * @param owner    the class whose loader, and whose package for a relative name, resolve it
     * @param resource the resource name
     * @param what     the noun for the message: "image", "shader", "font"
     * @return the bytes, never null
     * @throws IllegalStateException if there is no such resource
     * @throws UncheckedIOException  if reading it fails
     */
    public static byte[] bytes(Class<?> owner, String resource, String what) {
        byte[] bytes = bytesIfPresent(owner, resource, what);
        if (bytes == null) {
            throw missing(what, resource);
        }
        return bytes;
    }

    /**
     * The whole of a classpath resource, resolved from the root by {@code loader}.
     *
     * @param loader   the loader to ask
     * @param resource the resource name, with no leading slash
     * @param what     the noun for the message
     * @return the bytes, never null
     * @throws IllegalStateException if there is no such resource
     * @throws UncheckedIOException  if reading it fails
     */
    public static byte[] bytes(ClassLoader loader, String resource, String what) {
        byte[] bytes = bytesIfPresent(loader, resource, what);
        if (bytes == null) {
            throw missing(what, resource);
        }
        return bytes;
    }

    /**
     * {@link #bytes(Class, String, String)}, or {@code null} when there is no such resource.
     *
     * @param owner    the class whose loader, and whose package for a relative name, resolve it
     * @param resource the resource name
     * @param what     the noun for the message
     * @return the bytes, or null if the resource is absent
     * @throws UncheckedIOException if reading it fails
     */
    public static byte[] bytesIfPresent(Class<?> owner, String resource, String what) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resource, "resource");
        return readAll(owner.getResourceAsStream(resource), resource, what);
    }

    /**
     * {@link #bytes(ClassLoader, String, String)}, or {@code null} when there is no such resource.
     *
     * @param loader   the loader to ask
     * @param resource the resource name, with no leading slash
     * @param what     the noun for the message
     * @return the bytes, or null if the resource is absent
     * @throws UncheckedIOException if reading it fails
     */
    public static byte[] bytesIfPresent(ClassLoader loader, String resource, String what) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(resource, "resource");
        return readAll(loader.getResourceAsStream(resource), resource, what);
    }

    /**
     * The whole of a classpath resource as UTF-8 text, resolved relative to {@code owner}.
     *
     * @param owner    the class whose loader, and whose package for a relative name, resolve it
     * @param resource the resource name
     * @param what     the noun for the message
     * @return the text, never null
     * @throws IllegalStateException if there is no such resource
     * @throws UncheckedIOException  if reading it fails
     */
    public static String text(Class<?> owner, String resource, String what) {
        return new String(bytes(owner, resource, what), StandardCharsets.UTF_8);
    }

    /**
     * {@link #text(Class, String, String)}, or {@code null} when there is no such resource.
     *
     * @param owner    the class whose loader, and whose package for a relative name, resolve it
     * @param resource the resource name
     * @param what     the noun for the message
     * @return the text, or null if the resource is absent
     * @throws UncheckedIOException if reading it fails
     */
    public static String textIfPresent(Class<?> owner, String resource, String what) {
        byte[] bytes = bytesIfPresent(owner, resource, what);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * {@link #text(Class, String, String)} resolved from the root by {@code loader}, or
     * {@code null} when there is no such resource.
     *
     * @param loader   the loader to ask
     * @param resource the resource name, with no leading slash
     * @param what     the noun for the message
     * @return the text, or null if the resource is absent
     * @throws UncheckedIOException if reading it fails
     */
    public static String textIfPresent(ClassLoader loader, String resource, String what) {
        byte[] bytes = bytesIfPresent(loader, resource, what);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * The whole of a file.
     *
     * @param file the file
     * @param what the noun for the message: "image", "font", "Ogg stream"
     * @return the bytes, never null
     * @throws UncheckedIOException if reading it fails, the file being absent included
     */
    public static byte[] bytes(Path file, String what) {
        Objects.requireNonNull(file, "file");
        try {
            return Files.readAllBytes(file);
        } catch (IOException error) {
            throw new UncheckedIOException("reading " + what + " " + file, error);
        }
    }

    private static byte[] readAll(InputStream opened, String resource, String what) {
        if (opened == null) {
            return null;
        }
        try (InputStream in = opened) {
            return in.readAllBytes();
        } catch (IOException error) {
            throw new UncheckedIOException("reading " + what + " resource " + resource, error);
        }
    }

    private static IllegalStateException missing(String what, String resource) {
        return new IllegalStateException(what + " resource missing: " + resource);
    }
}
