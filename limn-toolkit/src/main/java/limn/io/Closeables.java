package limn.io;

import java.lang.System.Logger.Level;

/**
 * Closing something on a path that is already a failure or already done, where a close that
 * throws must not become the error that is reported.
 */
public final class Closeables {

    private static final System.Logger LOG = System.getLogger(Closeables.class.getName());

    private Closeables() {
    }

    /**
     * Closes {@code closeable} and reports nothing worse than a debug line if that fails.
     *
     * <p>For a source nobody received, a channel on a parse that has already failed, a decoder a
     * voice is done with: the caller is on a worker thread where blocking is allowed, and is
     * either already reporting a failure or has already succeeded, and in neither case is the
     * close's own failure the news. Three classes wrote this with three exception filters, one of
     * them swallowing silently; every close now logs at {@code DEBUG}, which is where a failure
     * nobody can act on belongs.
     *
     * @param closeable what to close; {@code null} is nothing to close
     * @param what      the noun for the log line: "an undelivered video source", "the Y4M channel"
     */
    public static void closeQuietly(AutoCloseable closeable, String what) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable failure) {
            LOG.log(Level.DEBUG, "closing " + what + " failed", failure);
        }
    }
}
