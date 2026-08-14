package limn.graphics;

import java.util.List;

/**
 * The set of font families the running backend can resolve: the bundled faces
 * plus any enumerated from the operating system. Installed via {@link Fonts};
 * {@link #EMPTY} is the headless default.
 */
@FunctionalInterface
public interface FontCatalog {

    /**
     * Font family names available for use, de-duplicated and sorted.
     *
     * <p><b>Must not block and must not read anything.</b> This is asked on the UI thread, from a
     * font picker being populated and from anything else building a list, and it must return
     * whatever is known right now. An implementation that has to enumerate the operating system
     * does that on a worker and publishes the fuller list by installing itself again through
     * {@link Fonts#installCatalog}, which notifies listeners; returning a short list first and a
     * complete one later is expected and correct.
     */
    List<String> families();

    /** No backend / headless: nothing to offer. */
    FontCatalog EMPTY = List::of;
}
