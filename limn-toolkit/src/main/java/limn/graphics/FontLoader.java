package limn.graphics;

import java.nio.file.Path;

/**
 * Registers a font file the application ships, so its family becomes usable by name.
 *
 * <p>Installed by the backend through {@link Fonts#installLoader}; {@link #UNAVAILABLE} is the
 * headless default, and it refuses rather than pretending a family exists.
 *
 * <p>This is the only way a face that is neither bundled in the backend nor installed in the
 * operating system can enter the font stack. Without it an application that ships its own
 * typeface has to ask the user to install it, and a capture or test that names such a family
 * silently gets the default face instead, a difference nothing downstream can see.
 */
@FunctionalInterface
public interface FontLoader {

    /**
     * Reads the faces in one font file and registers them under their own family name.
     *
     * <p><b>Synchronous, and on the calling thread.</b> What is read here is the file's name
     * table (the family and style strings, kilobytes), not its glyphs; those are parsed on
     * first use, the same way an operating-system face is. Registration has to complete before
     * it returns, because the caller's next act is to name the family in a widget or in
     * {@link Fonts#setDefaultFamily}, and a family that is not registered yet resolves to the
     * default face.
     *
     * @param  file font file (TrueType, OpenType, or a collection); a relative path resolves
     *              against the process working directory
     * @return the family name the faces registered under; pass this to
     *         {@link Fonts#setDefaultFamily} or to {@link Font}, rather than assuming the name
     *         from the file name, which need not match what the file declares
     * @throws java.io.UncheckedIOException  if the file cannot be read
     * @throws IllegalArgumentException      if it carries no usable face
     * @throws UnsupportedOperationException if no backend is installed
     */
    String load(Path file);

    /** No backend / headless: there is nothing that can parse a font file. */
    FontLoader UNAVAILABLE = file -> {
        throw new UnsupportedOperationException(
                "no backend installed: cannot load the font file " + file);
    };
}
