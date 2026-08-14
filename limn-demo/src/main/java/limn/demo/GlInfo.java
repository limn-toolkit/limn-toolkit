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
 */
final class GlInfo {

    private static final int LABEL_WIDTH = 21;

    private GlInfo() {
    }

    /** @return whether a graphics context was obtained, for the process exit code */
    static boolean run() {
        try (Backend backend = new LwjglBackend()) {
            String creationFailure = null;
            try {
                backend.createWindow(new WindowConfig("Limn: graphics info", 1, 1, false, false));
            } catch (RuntimeException error) {
                creationFailure = error.getMessage();
            }
            GraphicsInfo info = backend.graphicsInfo();
            print(info, creationFailure);
            return info.available();
        }
    }

    private static void print(GraphicsInfo info, String creationFailure) {
        System.out.println();
        System.out.println("Limn graphics report");
        System.out.println();
        row("Window platform", info.windowPlatform());
        row("Window library", info.windowLibrary());

        if (!info.available()) {
            System.out.println();
            System.out.println("  No graphics context.");
            System.out.println("  " + (creationFailure != null ? creationFailure : info.failure()));
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
