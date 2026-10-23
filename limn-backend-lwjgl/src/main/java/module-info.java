/**
 * The LWJGL backend: GLFW, OpenGL and stb behind the toolkit's SPIs, and the platform
 * accessibility bridges.
 *
 * <p>Only {@code limn.backend.lwjgl} is exported; the bridges and the native helpers in
 * {@code limn.backend.lwjgl.internal} are this module's own. jlayer, the
 * MP3 decoder, has no module name and belongs on the class path, which this module reads for it
 * ({@code AudioFileDecoder}).
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
}
