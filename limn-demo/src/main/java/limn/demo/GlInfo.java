package limn.demo;

import limn.backend.Backend;
import limn.backend.GraphicsInfo;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;

import java.util.function.Function;
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
 * <p>Two different machines fail, at two different steps, and the report has to
 * say which. GLFW may never start — there is no display server at all, which is
 * what a plain SSH shell and most CI containers are — or it may start and then
 * refuse a window, which is a session whose OpenGL is unusable. Both print the
 * report below and exit non-zero, and only the line naming the step tells the
 * reader of a log whether the answer is {@code xvfb-run} or a driver. Neither
 * ends in an uncaught exception: a diagnostic that dies of the condition it
 * exists to describe has reported nothing.
 */
final class GlInfo {

    private static final int LABEL_WIDTH = 21;

    /** The two steps that can fail, as the failure line names them. See the class note. */
    static final String NO_GLFW = "Failed at GLFW startup: ";
    static final String NO_WINDOW = "GLFW started; failed at window creation: ";

    private GlInfo() {
    }

    /** @return whether a graphics context was obtained, for the process exit code */
    static boolean run() {
        // The real backend's own start-failure report, which carries the version string of the
        // GLFW that is loaded — the one thing knowable before it starts, and the contrast that
        // tells a headless machine from a broken library.
        return run(LwjglBackend::new, error -> LwjglBackend.startupFailure(NO_GLFW + describe(error)));
    }

    /**
     * @param backends       what to ask for a backend, so that the failure this mode exists to
     *                       report can be tested without a machine that has it
     * @param onStartFailure the report for a backend that could not be constructed, given what
     *                       its constructor threw
     * @return whether a graphics context was obtained, for the process exit code
     */
    static boolean run(Supplier<Backend> backends,
                       Function<RuntimeException, GraphicsInfo> onStartFailure) {
        Backend backend;
        try {
            backend = backends.get();
        } catch (RuntimeException error) {
            // Nothing was constructed, so there is nothing to close and no backend to ask: the
            // report is built from what is knowable without one, which is the failure itself.
            System.out.print(report(onStartFailure.apply(error), null));
            return false;
        }
        try (backend) {
            GraphicsInfo info;
            try {
                backend.createWindow(WindowConfig.of("Limn: graphics info", 1, 1).visible(false).resizable(false));
                info = backend.graphicsInfo();
            } catch (RuntimeException error) {
                // With no window, graphicsInfo() answers "no window has been created
                // yet", which is true and useless here. Keep the platform and library
                // it does know, and say why the window did not open in place of it.
                GraphicsInfo withoutContext = backend.graphicsInfo();
                info = GraphicsInfo.unavailable(withoutContext.windowPlatform(),
                        withoutContext.windowLibrary(), NO_WINDOW + describe(error));
            }
            System.out.print(report(info, null));
            return info.available();
        }
    }

    /**
     * @param error what the backend's constructor threw
     * @return the report for a machine whose window library never started, knowing neither the
     *         platform nor the library version because choosing one is what failed; the real
     *         mode asks the backend instead, which knows the version of the library it loaded
     */
    static GraphicsInfo startFailure(RuntimeException error) {
        return GraphicsInfo.unavailable("none selected", "none", NO_GLFW + describe(error));
    }

    /**
     * What a failure says about itself, or the type of it where it said nothing: a
     * report whose one interesting line reads {@code null} has failed at its only job.
     */
    private static String describe(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getName() : message;
    }

    /**
     * @param info            what the backend reports, or {@link #startFailure} when there was no
     *                        backend to ask
     * @param creationFailure what the window's own creation threw, or {@code null} where the
     *                        failure line is already the report's own
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
