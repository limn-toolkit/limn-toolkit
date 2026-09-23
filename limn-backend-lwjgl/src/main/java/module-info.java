/**
 * The LWJGL backend: GLFW, OpenGL and stb behind the toolkit's SPIs, and the platform
 * accessibility bridges.
 *
 * <p>Only {@code limn.backend.lwjgl} is exported; the bridges are this module's own. Two kinds of
 * jar it uses belong on the class path even when it is on the module path: the limn-fonts jars,
 * which it reads as resources, and jlayer, the MP3 decoder, which has no module name. This module
 * reads the class path for jlayer ({@code AudioFileDecoder}).
 */
module limn.backend.lwjgl {
    requires transitive limn.toolkit;
    requires org.lwjgl;
    requires org.lwjgl.glfw;
    requires org.lwjgl.opengl;
    requires org.lwjgl.openal;
    requires org.lwjgl.stb;
    requires org.lwjgl.harfbuzz;
    requires org.lwjgl.nanovg;
    requires org.lwjgl.tinyfd;

    exports limn.backend.lwjgl;
}
