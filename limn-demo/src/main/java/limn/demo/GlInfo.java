package limn.demo;

import limn.backend.Backend;
import limn.backend.GraphicsInfo;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;

import java.util.function.Supplier;

/**
 * The {@code --gl-info} mode: what graphics stack this machine hands Limn,
 * printed so it can be pasted into a bug report whole.
 *
 * <p>It renders nothing. The window it opens is invisible and one point square,
 * and exists only because the strings belong to a GL context and there is no
 * context without a window. When even that fails (the interesting case, and the
 * common one on a machine with no OpenGL driver), the failure is the report.
 *
 * <p><b>There are two such failures and the report names which one happened.</b>
 * A machine with a driver too old, or none, gets that far and fails at the
 * window; a machine with no display server at all — a plain SSH shell with no
 * {@code DISPLAY} and no Wayland socket, which is how a CI runner and half the
 * verification lab are reached — fails one step earlier, when the window library
 * itself refuses to start. Both are "no graphics context" to the caller and the
 * exit status is the same for both, but they are different machines to whoever
 * reads the log, so the line under the heading says which.
 */
final class GlInfo {

    private static final int LABEL_WIDTH = 21;

    private GlInfo() {
    }

    /** @return whether a graphics context was obtained, for the process exit code */
    static boolean run() {
        return run(LwjglBackend::new);
    }

    /**
     * @param backends what to ask for a backend, so that the failure this mode exists to report
     *                 can be tested without a machine that has it
     * @return whether a graphics context was obtained, for the process exit code
     */
    static boolean run(Supplier<Backend> backends) {
        Backend backend;
        try {
            backend = backends.get();
        } catch (RuntimeException error) {
            // Nothing was constructed, so there is nothing to close and no backend to ask: the
            // report is built from what is knowable without one, which is the failure itself.
            System.out.print(report(startFailure(error), null));
            return false;
        }
        try (backend) {
            String creationFailure = null;
            try {
                backend.createWindow(new WindowConfig("Limn: graphics info", 1, 1, false, false));
            } catch (RuntimeException error) {
                creationFailure = error.getMessage();
            }
            GraphicsInfo info = backend.graphicsInfo();
            System.out.print(report(info, creationFailure));
            return info.available();
        }
    }

    /**
     * @param error what the backend's constructor threw
     * @return the report for a machine whose window library never started, which knows neither the
     *         platform nor the library version because choosing one is what failed
     */
    static GraphicsInfo startFailure(RuntimeException error) {
        String said = error.getMessage();
        return GraphicsInfo.unavailable("none", "none",
                "The window library did not start, so no window and no context were attempted: "
                        + (said == null || said.isBlank() ? error.getClass().getName() : said));
    }

    /**
     * @param info            what the backend reports, or {@link #startFailure} when there was no
     *                        backend to ask
     * @param creationFailure what the window's own creation threw, or {@code null}
     * @return the whole report, newline-terminated, ready to print
     */
    static String report(GraphicsInfo info, String creationFailure) {
        StringBuilder out = new StringBuilder();
        out.append(System.lineSeparator());
        line(out, "Limn graphics report");
        out.append(System.lineSeparator());
        row(out, "Window platform", info.windowPlatform());
        row(out, "Window library", info.windowLibrary());

        if (!info.available()) {
            out.append(System.lineSeparator());
            line(out, "  No graphics context.");
            line(out, "  " + (creationFailure != null ? creationFailure : info.failure()));
            out.append(System.lineSeparator());
            return out.toString();
        }

        row(out, "Graphics API", info.api());
        row(out, "Context created via", info.contextApi());
        out.append(System.lineSeparator());
        row(out, "Vendor", info.vendor());
        row(out, "Renderer", info.renderer());
        row(out, "Version", info.version());
        row(out, "Shading language", info.shadingLanguage());
        out.append(System.lineSeparator());
        row(out, "Max MSAA samples", String.valueOf(info.maxSamples()));
        gate(out, "GPU timer queries", info.timerQueries(), "GPU figures in the performance monitor");
        gate(out, "Float colour buffer", info.floatColorBuffer(), "the 3D subsystem's render target");
        gate(out, "16-bit textures", info.norm16Textures(), "10-bit video planes");
        out.append(System.lineSeparator());
        line(out, "  Extensions (" + info.extensions().size() + ")");
        for (String extension : info.extensions()) {
            line(out, "    " + extension);
        }
        out.append(System.lineSeparator());
        return out.toString();
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append(System.lineSeparator());
    }

    private static void row(StringBuilder out, String label, String value) {
        out.append(String.format("  %-" + LABEL_WIDTH + "s %s%n", label, value));
    }

    private static void gate(StringBuilder out, String label, boolean present, String what) {
        out.append(String.format("  %-" + LABEL_WIDTH + "s %-3s (%s)%n",
                label, present ? "yes" : "NO", what));
    }
}
