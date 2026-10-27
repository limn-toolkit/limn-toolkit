/**
 * The LWJGL backend behind the toolkit's SPIs: windows and drawing through GLFW and OpenGL, with
 * OpenGL ES through ANGLE where a Windows or macOS machine has no usable OpenGL; text through
 * HarfBuzz and stb, SVG through lunasvg, sound through OpenAL; the system's dialogs, clipboard, drag
 * and drop, tray, menu bar and printing, and the platform accessibility bridges.
 *
 * <p>Only {@code limn.backend.lwjgl} is exported to applications; the bridges and the native
 * helpers in {@code limn.backend.lwjgl.internal} are this module's own. Three of them are exported
 * to LWJGL alone, because each declares native callbacks: on Java 25 LWJGL builds a callback as a
 * class in the package of the callback's interface and instantiates it from its own module, which
 * it may do only in a package exported to it. Unexported, the clipboard, drag and drop and the
 * accessibility bridges failed to open on the module path. jlayer, the
 * MP3 decoder, has no module name and belongs on the class path, which this module reads for it
 * when it is there ({@code AudioFileDecoder}). Nothing here requires it: an application may exclude
 * it and lose MP3 alone.
 */
module limn.backend.lwjgl {
    requires transitive limn.toolkit;
    // The nullness annotations on this module's signatures, as limn.toolkit requires them.
    requires static transitive org.jspecify;
    requires org.lwjgl;
    requires org.lwjgl.glfw;
    requires org.lwjgl.egl;
    requires org.lwjgl.opengl;
    requires org.lwjgl.opengles;
    requires org.lwjgl.openal;
    requires org.lwjgl.stb;
    requires org.lwjgl.harfbuzz;
    requires org.lwjgl.tinyfd;
    // Roboto, the face everything falls back to. Required so that on the module path its jar is
    // loaded at all, and loading it loads every other font jar on the path with it.
    requires limn.fonts.roboto;
    // The SVG renderer's payload (ADR 066): its six natives-<os>-<arch> jars are automatic
    // modules too, resolved with this one, and the loader reads them through the class loader.
    requires limn.lunasvg.natives;
    // ANGLE's libraries (ADR 080), the OpenGL ES fallback: four natives-<os>-<arch> jars, each an
    // automatic module of its own, read through the class loader like the SVG renderer's.
    requires limn.angle.natives;

    exports limn.backend.lwjgl;
    // Every package that declares an LWJGL callback (CallbacksExportedToLwjglTest holds the list to the classes).
    exports limn.backend.lwjgl.a11y.macos to org.lwjgl;
    exports limn.backend.lwjgl.a11y.windows to org.lwjgl;
    exports limn.backend.lwjgl.transfer to org.lwjgl;
}
