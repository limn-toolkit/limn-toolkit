package limn.backend.lwjgl;

import limn.graphics.Color;
import limn.graphics.Gradient;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.GL_FLOAT;
import static org.lwjgl.opengl.GL33C.GL_NEAREST;
import static org.lwjgl.opengl.GL33C.GL_RGBA;
import static org.lwjgl.opengl.GL33C.GL_RGBA32F;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE3;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL33C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL33C.GL_UNPACK_ALIGNMENT;

/**
 * The colours of the {@link Gradient}s a canvas draws, one row of a float texture each, which the
 * canvas program walks to find a fragment's colour: a stop's position and its premultiplied colour,
 * exact, so that two stops at one position make an edge as hard as it is asked, and a gradient of
 * any number of stops is one primitive of the frame's one draw like a two-colour one.
 *
 * <p>A row's first texel holds the stop count; texel {@code 1 + 2i} holds stop {@code i}'s position
 * and texel {@code 2 + 2i} its colour. A gradient keeps its row across frames while it is drawn; when
 * every row is taken the canvas draws what it queued and starts the rows again. One per context.
 */
final class GradientRamps implements AutoCloseable {

    /** How many gradients one round of rows holds. */
    static final int ROWS = 64;
    static final int WIDTH = 1 + 2 * Gradient.MAX_STOPS;

    private int texture;
    private final Map<Gradient, Integer> rows = new HashMap<>();
    private ByteBuffer upload;

    /** @return the texture, made on first use */
    int texture() {
        if (texture == 0) {
            texture = Gl.glGenTextures();
            Gl.glActiveTexture(GL_TEXTURE3);
            Gl.glBindTexture(GL_TEXTURE_2D, texture);
            Gl.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, WIDTH, ROWS, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
            Gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            Gl.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            Gl.glActiveTexture(GL_TEXTURE0);
        }
        return texture;
    }

    /**
     * The row holding {@code gradient}'s stops, written now if it has none.
     *
     * @return the row, or -1 when every row is taken: the caller draws what it queued, calls
     *         {@link #reset()}, and asks again
     */
    int rowFor(Gradient gradient) {
        Integer row = rows.get(gradient);
        if (row != null) {
            return row;
        }
        if (rows.size() == ROWS) {
            return -1;
        }
        int at = rows.size();
        float[] positions = gradient.positions();
        List<Color> colors = gradient.colors();
        int texels = 1 + 2 * positions.length;
        if (upload == null) {
            upload = MemoryUtil.memAlloc(WIDTH * 4 * Float.BYTES);
        }
        upload.clear();
        upload.putFloat(positions.length).putFloat(0).putFloat(0).putFloat(0);
        for (int i = 0; i < positions.length; i++) {
            Color c = colors.get(i);
            upload.putFloat(positions[i]).putFloat(0).putFloat(0).putFloat(0);
            upload.putFloat(c.r() * c.a()).putFloat(c.g() * c.a()).putFloat(c.b() * c.a()).putFloat(c.a());
        }
        upload.flip();
        Gl.glActiveTexture(GL_TEXTURE3);
        Gl.glBindTexture(GL_TEXTURE_2D, texture());
        Gl.glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        Gl.glTexSubImage2D(GL_TEXTURE_2D, 0, 0, at, texels, 1, GL_RGBA, GL_FLOAT, upload);
        Gl.glActiveTexture(GL_TEXTURE0);
        rows.put(gradient, at);
        return at;
    }

    /** Frees every row, for a canvas that has drawn everything that read them. */
    void reset() {
        rows.clear();
    }

    /** Forgets the texture without a GL call, the context it lived in being gone. */
    void contextLost() {
        texture = 0;
        rows.clear();
    }

    @Override
    public void close() {
        if (texture != 0) {
            Gl.glDeleteTextures(texture);
            texture = 0;
        }
        rows.clear();
        if (upload != null) {
            MemoryUtil.memFree(upload);
            upload = null;
        }
    }
}
