package limn.demo;

import limn.backend.Backend;
import limn.backend.GraphicsInfo;
import limn.backend.WindowConfig;
import limn.backend.lwjgl.LwjglBackend;

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
    private static final String NO_GLFW = "Failed at GLFW startup: ";
    private static final String NO_WINDOW = "GLFW started; failed at window creation: ";

    private GlInfo() {
    }

    /** @return whether a graphics context was obtained, for the process exit code */
    static boolean run() {
        Backend backend;
        try {
            backend = new LwjglBackend();
        } catch (RuntimeException error) {
            // Nothing was constructed, so there is no backend to ask and no window to
            // try: the report is what the backend knows without an initialized GLFW.
            print(LwjglBackend.startupFailure(NO_GLFW + describe(error)));
            return false;
        }
        try (backend) {
            GraphicsInfo info;
            try {
                backend.createWindow(new WindowConfig("Limn: graphics info", 1, 1, false, false));
                info = backend.graphicsInfo();
            } catch (RuntimeException error) {
                // With no window, graphicsInfo() answers "no window has been created
                // yet", which is true and useless here. Keep the platform and library
                // it does know, and say why the window did not open in place of it.
                GraphicsInfo withoutContext = backend.graphicsInfo();
                info = GraphicsInfo.unavailable(withoutContext.windowPlatform(),
                        withoutContext.windowLibrary(), NO_WINDOW + describe(error));
            }
            print(info);
            return info.available();
        }
    }

    /**
     * What a failure says about itself, or the type of it where it said nothing: a
     * report whose one interesting line reads {@code null} has failed at its only job.
     */
    private static String describe(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getName() : message;
    }

    private static void print(GraphicsInfo info) {
        System.out.println();
        System.out.println("Limn graphics report");
        System.out.println();
        row("Window platform", info.windowPlatform());
        row("Window library", info.windowLibrary());

        if (!info.available()) {
            System.out.println();
            System.out.println("  No graphics context.");
            System.out.println("  " + info.failure());
            System.out.println();
            return;
        }

        row("Graphics API", info.api());
        row("Context created via", info.contextApi());
        System.out.println();
        row("Vendor", info.vendor());
        row("Renderer", info.renderer());
        row("Version", info.version());
        row("Shading language", info.shadingLanguage());
        System.out.println();
        row("Max MSAA samples", String.valueOf(info.maxSamples()));
        gate("GPU timer queries", info.timerQueries(), "GPU figures in the performance monitor");
        gate("Float colour buffer", info.floatColorBuffer(), "the 3D subsystem's render target");
        gate("16-bit textures", info.norm16Textures(), "10-bit video planes");
        System.out.println();
        System.out.println("  Extensions (" + info.extensions().size() + ")");
        for (String extension : info.extensions()) {
            System.out.println("    " + extension);
        }
        System.out.println();
    }

    private static void row(String label, String value) {
        System.out.printf("  %-" + LABEL_WIDTH + "s %s%n", label, value);
    }

    private static void gate(String label, boolean present, String what) {
        System.out.printf("  %-" + LABEL_WIDTH + "s %-3s (%s)%n", label, present ? "yes" : "NO",
                what);
    }
}
