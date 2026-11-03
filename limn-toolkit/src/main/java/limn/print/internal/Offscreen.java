package limn.print.internal;

import limn.backend.spi.Installed;
import limn.backend.spi.OffscreenPainter;

/**
 * Where the running backend's {@link OffscreenPainter} is installed, read by the recording of a page.
 * Not API: a backend hands it over in {@link limn.backend.spi.BackendServices}, and nothing else
 * installs one but a test.
 */
public final class Offscreen {

    private static final Installed<OffscreenPainter> INSTALLED = new Installed<>(OffscreenPainter.NONE,
            "no OffscreenPainter installed. Is the backend started?");

    private Offscreen() {
    }

    /** @param painter the backend's, or a test's */
    public static void install(OffscreenPainter painter) {
        INSTALLED.install(painter);
    }

    /** @param painter the one installed */
    public static void uninstall(OffscreenPainter painter) {
        INSTALLED.uninstall(painter);
    }

    /** @return the installed painter, {@link OffscreenPainter#NONE} without a backend */
    public static OffscreenPainter current() {
        OffscreenPainter installed = INSTALLED.current();
        return installed == null ? OffscreenPainter.NONE : installed;
    }
}
