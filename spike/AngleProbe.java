import java.nio.file.Path;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;

/**
 * Asks ANGLE for an OpenGL ES 3.0 context on Direct3D 11 and prints what answered.
 *
 * <p>Limn's renderer asks for desktop OpenGL 3.3 core, which ANGLE does not offer, so the demo
 * cannot run on it; this proves only that ANGLE gives a working context on the machine. The
 * libraries come from {@code -Dangle.dir} and are loaded before GLFW asks for them by name:
 * {@code libGLESv2.dll} first, because {@code libEGL.dll} imports it.
 */
public class AngleProbe {

    private static final int GL_VENDOR = 0x1F00;
    private static final int GL_RENDERER = 0x1F01;
    private static final int GL_VERSION = 0x1F02;

    public static void main(String[] args) {
        String dir = System.getProperty("angle.dir");
        for (String name : new String[] {"libGLESv2.dll", "libEGL.dll"}) {
            System.load(Path.of(dir, name).toAbsolutePath().toString());
            System.out.println("PROBE loaded " + name);
        }
        GLFW.glfwSetErrorCallback((code, description) ->
                System.out.println("GLFW error 0x" + Integer.toHexString(code) + ": "
                        + MemoryUtil.memUTF8(description)));
        GLFW.glfwInitHint(GLFW.GLFW_ANGLE_PLATFORM_TYPE, GLFW.GLFW_ANGLE_PLATFORM_TYPE_D3D11);
        if (!GLFW.glfwInit()) {
            System.out.println("ANGLE: GLFW did not start");
            System.exit(1);
        }
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_ES_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_EGL_CONTEXT_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 0);
        long window = GLFW.glfwCreateWindow(64, 64, "ANGLE probe", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            System.out.println("ANGLE: no OpenGL ES 3.0 context");
            System.exit(1);
        }
        GLFW.glfwMakeContextCurrent(window);
        long getString = GLFW.glfwGetProcAddress("glGetString");
        System.out.println("ANGLE vendor   " + string(getString, GL_VENDOR));
        System.out.println("ANGLE renderer " + string(getString, GL_RENDERER));
        System.out.println("ANGLE version  " + string(getString, GL_VERSION));
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    private static String string(long getString, int name) {
        long value = JNI.invokeP(name, getString);
        return value == MemoryUtil.NULL ? "(null)" : MemoryUtil.memUTF8(value);
    }
}
