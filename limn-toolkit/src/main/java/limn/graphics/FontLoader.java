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
 *
 * <p>It is also where {@link Fonts#preload} reaches the backend: {@link #preload} reads a
 * family's faces ahead of their first use, and its default, for a loader that keeps nothing
 * resident, reads nothing. {@link #withdraw} takes back a request a later choice overtook.
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
     * @throws limn.io.MalformedContentException if it carries no usable face
     * @throws UnsupportedOperationException if no backend is installed
     */
    String load(Path file);

    /**
     * Makes {@code family}'s regular and bold faces resident without reading a font file on the
     * UI thread, so that the first measure or paint in that family finds them rather than reading
     * them inside its frame. What {@link Fonts#preload} and
     * {@link Fonts#setDefaultFamilyWhenResident} call; an application calls those.
     *
     * <p>Called on the UI thread. An implementation reads on a worker and runs
     * {@code whenResident} on the UI thread once the faces are resident, and also when there is
     * nothing to read: a family it carries itself, one already resident, one it does not know.
     * It runs it exactly once and <b>never inside this call</b>, so a caller may change its own
     * state there without re-entering this; it need not run it at all once the backend has shut
     * down, when there is no UI thread left to run it on, or once the request has been
     * {@linkplain #withdraw withdrawn}. Two requests for one family may share one read, and a
     * read once started need not stop: what {@code withdraw} takes back is the request.
     *
     * <p>The default reads nothing and runs {@code whenResident} on the UI thread's next turn,
     * which is right for a loader that keeps no face resident, {@link #UNAVAILABLE} among them.
     *
     * @param family       the family name, as a {@link Font} names it; never blank and never
     *                     {@link Font#DEFAULT_FAMILY}, which {@code Fonts} answers itself
     * @param whenResident what to run on the UI thread once the faces are resident
     */
    default void preload(String family, Runnable whenResident) {
        limn.concurrent.Ui.post(whenResident);
    }

    /**
     * Takes back a {@link #preload} request: whoever passed {@code whenResident} for
     * {@code family} no longer wants the faces. What {@link Fonts} calls on the request of a
     * {@link Fonts#setDefaultFamilyWhenResident} that a later choice overtook; an application
     * calls neither.
     *
     * <p>Called on the UI thread. An implementation need not run {@code whenResident} any more,
     * and need not stop a read already under way, which may serve another request for the family
     * too. A read that lands with no request left on it, for a family nothing draws in, is one
     * nobody wants: an implementation that keeps a bounded number of faces resident keeps its
     * faces only in room it can make without letting go of a face in use, and has nothing laid out
     * again for them. That is what keeps a font picker stepped through a list from re-laying out
     * the UI once per entry it passed, and from evicting the family the UI is drawn in.
     *
     * <p>The default does nothing, which is right for a loader that keeps no face resident: the
     * callback still runs, and {@code Fonts} ignores it.
     *
     * @param family       the family named in the request, as it was passed to {@link #preload}
     * @param whenResident the callback passed with it, the same instance
     */
    default void withdraw(String family, Runnable whenResident) {
    }

    /** No backend / headless: there is nothing that can parse a font file. */
    FontLoader UNAVAILABLE = file -> {
        throw new UnsupportedOperationException(
                "no backend installed: cannot load the font file " + file);
    };
}
