package limn.backend.lwjgl;

import limn.render3d.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A raw material is a record of two strings, cheap to build, and an application that builds one
 * per frame is writing ordinary code. The program it compiles to is not cheap: keyed by the
 * record's identity, every frame linked a new one and the cache kept each of them until the window
 * closed.
 */
class RawProgramCacheTest {

    private static final String VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 a_position;
            uniform mat4 u_mvp;
            void main() {
                gl_Position = u_mvp * vec4(a_position, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 330 core
            out vec4 color;
            void main() {
                color = vec4(1.0);
            }
            """;

    @Test
    void aRawMaterialRebuiltEachFrameCompilesOnce() {
        HeadlessGl.assumeAvailable();
        Gl3DContext context = new Gl3DContext();
        try {
            Object first = context.ensureRaw(new Material.Raw(VERTEX, FRAGMENT));
            // Copies, so equal and not the same strings either: what a material assembled from a
            // template every frame hands over.
            Object next = context.ensureRaw(new Material.Raw(new String(VERTEX), new String(FRAGMENT)));
            assertSame(first, next, "an equal material is the same program, whichever instance asks");

            Object other = context.ensureRaw(new Material.Raw(VERTEX, FRAGMENT.replace("1.0", "0.5")));
            assertNotSame(first, other, "and different sources are a different one");
        } finally {
            context.dispose();
        }
    }
}
