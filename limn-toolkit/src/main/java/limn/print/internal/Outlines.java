package limn.print.internal;

import limn.backend.spi.GlyphOutlines;
import limn.backend.spi.Installed;

/**
 * Where the running backend's {@link GlyphOutlines} is installed, read by the PDF writer. Not API:
 * a backend hands it over in {@link limn.backend.spi.BackendServices}, and nothing else installs
 * one but a test.
 */
public final class Outlines {

    private static final Installed<GlyphOutlines> INSTALLED = new Installed<>(GlyphOutlines.NONE,
            "no GlyphOutlines installed. Is the backend started?");

    private Outlines() {
    }

    /** @param outlines the backend's, or a test's */
    public static void install(GlyphOutlines outlines) {
        INSTALLED.install(outlines);
    }

    /** @param outlines the one installed */
    public static void uninstall(GlyphOutlines outlines) {
        INSTALLED.uninstall(outlines);
    }

    /** @return the installed source, {@link GlyphOutlines#NONE} without a backend */
    public static GlyphOutlines current() {
        GlyphOutlines installed = INSTALLED.current();
        return installed == null ? GlyphOutlines.NONE : installed;
    }
}
