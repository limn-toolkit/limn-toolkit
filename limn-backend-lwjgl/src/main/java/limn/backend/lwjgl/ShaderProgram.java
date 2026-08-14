package limn.backend.lwjgl;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL33C.GL_FALSE;
import static org.lwjgl.opengl.GL33C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL33C.GL_INVALID_INDEX;
import static org.lwjgl.opengl.GL33C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL33C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL33C.glAttachShader;
import static org.lwjgl.opengl.GL33C.glCompileShader;
import static org.lwjgl.opengl.GL33C.glCreateProgram;
import static org.lwjgl.opengl.GL33C.glCreateShader;
import static org.lwjgl.opengl.GL33C.glDeleteProgram;
import static org.lwjgl.opengl.GL33C.glDeleteShader;
import static org.lwjgl.opengl.GL33C.glDetachShader;
import static org.lwjgl.opengl.GL33C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL33C.glGetProgrami;
import static org.lwjgl.opengl.GL33C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL33C.glGetShaderi;
import static org.lwjgl.opengl.GL33C.glGetUniformBlockIndex;
import static org.lwjgl.opengl.GL33C.glGetUniformLocation;
import static org.lwjgl.opengl.GL33C.glLinkProgram;
import static org.lwjgl.opengl.GL33C.glShaderSource;
import static org.lwjgl.opengl.GL33C.glUniformBlockBinding;
import static org.lwjgl.opengl.GL33C.glUseProgram;

/** Compiles and links a GLSL program from classpath resources. */
final class ShaderProgram implements AutoCloseable {

    private final int id;

    private ShaderProgram(int id) {
        this.id = id;
    }

    /**
     * Compiles + links from two classpath resources. Needs a GL context current
     * on the calling thread, and has no asynchronous form because it could not
     * use one: the two reads are a few kilobytes of text off the classpath, and
     * every step after them (compile, attach, link) is a GL call, which is
     * bound to the thread holding the context. Moving the reads to a worker
     * would buy a fraction of the cost and pay a thread hop for it.
     *
     * @throws IllegalStateException if a resource is missing, or the shader
     *                               fails to compile or link
     */
    static ShaderProgram fromResources(String vertexResource, String fragmentResource) {
        return fromSources(readResource(vertexResource), readResource(fragmentResource));
    }

    /** Compiles + links from GLSL source strings (used by raw-GLSL materials). */
    static ShaderProgram fromSources(String vertexSource, String fragmentSource) {
        int vertex = compile(GL_VERTEX_SHADER, vertexSource, "vertex");
        int fragment = compile(GL_FRAGMENT_SHADER, fragmentSource, "fragment");
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        glDetachShader(program, vertex);
        glDetachShader(program, fragment);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("shader link failed: " + log);
        }
        return new ShaderProgram(program);
    }

    void use() {
        glUseProgram(id);
    }

    int uniformLocation(String name) {
        int location = glGetUniformLocation(id, name);
        if (location < 0) {
            throw new IllegalStateException("uniform not found (optimized out?): " + name);
        }
        return location;
    }

    /** Like {@link #uniformLocation} but returns {@code -1} instead of throwing when the
     *  uniform is absent/dead-stripped; it exists for user (raw) shaders that may not use
     *  every standard uniform. Callers skip binding when the location is {@code -1}. */
    int optionalUniformLocation(String name) {
        return glGetUniformLocation(id, name);
    }

    /** Points a std140 uniform block at a binding point (no-op if the block is absent). */
    void bindUniformBlock(String blockName, int bindingPoint) {
        int index = glGetUniformBlockIndex(id, blockName);
        if (index != GL_INVALID_INDEX) {
            glUniformBlockBinding(id, index, bindingPoint);
        }
    }

    @Override
    public void close() {
        glDeleteProgram(id);
    }

    private static int compile(int type, String source, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed for " + name + ":\n" + log);
        }
        return shader;
    }

    private static String readResource(String resource) {
        try (InputStream in = ShaderProgram.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("shader resource missing: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException("reading shader " + resource, error);
        }
    }
}
