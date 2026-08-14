/**
 * The shipped backend: GLFW windows and input, an OpenGL 3.3 core renderer that batches
 * the 2D canvas and hosts the 3D provider, OpenAL audio, and stb for font rasterization
 * and image decoding. {@link limn.backend.lwjgl.LwjglBackend} is the one type an
 * application names (constructed on the process main thread, which becomes the UI
 * thread), and everything else here is reached through the toolkit's facades once
 * installed.
 *
 * <p>This is the only module allowed to import {@code org.lwjgl}, a boundary the build
 * enforces, so nothing above the backend can acquire a dependency on the windowing
 * library by accident.
 */
package limn.backend.lwjgl;
