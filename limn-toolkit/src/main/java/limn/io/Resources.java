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
 * <p><b>On the module path</b>, a class in a named module finds only its own module's resources,
 * so the class form asks the class's module first and then its class loader, which is where the
 * class path's answer comes from. What the loader cannot see is a resource inside a named module's
 * package that the module does not open: an application that is a named module keeps what the
 * toolkit loads by name in a package it opens, in a directory whose name is not a package name
 * ({@code static-assets/}, {@code app-images/}), or opens the stream itself, in its own code, and
 * hands it to {@link #bytes(InputStream, String, String)}, which is what the toolkit's own modules
 * do with theirs.
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
        InputStream opened = owner.getResourceAsStream(resource);
        if (opened == null && owner.getModule().isNamed()) {
            // On the module path a class's own lookup stays inside its module, so a toolkit class
            // asked for an application's resource finds nothing. The loader's lookup is the class
            // path's: it reaches every jar on the class path, every automatic module and every
            // package a named module opens.
            ClassLoader loader = owner.getClassLoader() != null
                    ? owner.getClassLoader() : ClassLoader.getSystemClassLoader();
            opened = loader.getResourceAsStream(absolute(owner, resource));
        }
        return readAll(opened, resource, what);
    }

    /** The name {@link Class#getResourceAsStream} would resolve, as a class loader spells it. */
    private static String absolute(Class<?> owner, String resource) {
        if (resource.startsWith("/")) {
            return resource.substring(1);
        }
        String pkg = owner.getPackageName();
        return pkg.isEmpty() ? resource : pkg.replace('.', '/') + '/' + resource;
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
     * The whole of a resource the caller opened, and closes it.
     *
     * <p>For a named module's own resource in a package it does not open, which only that module's
     * code can open: {@code Resources.bytes(Icons.class.getResourceAsStream(name), name, "icon")}.
     *
     * @param opened   the open stream, or {@code null} when the lookup found nothing
     * @param resource the resource name, for the message
     * @param what     the noun for the message
     * @return the bytes, never null
     * @throws IllegalStateException if {@code opened} is null
     * @throws UncheckedIOException  if reading it fails
     */
    public static byte[] bytes(InputStream opened, String resource, String what) {
        byte[] bytes = bytesIfPresent(opened, resource, what);
        if (bytes == null) {
            throw missing(what, resource);
        }
        return bytes;
    }

    /**
     * {@link #bytes(InputStream, String, String)}, or {@code null} when {@code opened} is null.
     *
     * @param opened   the open stream, or {@code null} when the lookup found nothing
     * @param resource the resource name, for the message
     * @param what     the noun for the message
     * @return the bytes, or null if there was nothing to read
     * @throws UncheckedIOException if reading it fails
     */
    public static byte[] bytesIfPresent(InputStream opened, String resource, String what) {
        Objects.requireNonNull(resource, "resource");
        return readAll(opened, resource, what);
    }

    /**
     * {@link #bytes(InputStream, String, String)} as UTF-8 text.
     *
     * @param opened   the open stream, or {@code null} when the lookup found nothing
     * @param resource the resource name, for the message
     * @param what     the noun for the message
     * @return the text, never null
     * @throws IllegalStateException if {@code opened} is null
     * @throws UncheckedIOException  if reading it fails
     */
    public static String text(InputStream opened, String resource, String what) {
        return new String(bytes(opened, resource, what), StandardCharsets.UTF_8);
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
