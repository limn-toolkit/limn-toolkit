package limn.backend.lwjgl;

import limn.render3d.shader.TargetProfile;

import limn.io.Resources;

import static org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL33C.GL_FALSE;
import static org.lwjgl.opengl.GL33C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL33C.GL_INVALID_INDEX;
import static org.lwjgl.opengl.GL33C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL33C.GL_VERTEX_SHADER;
import static limn.backend.lwjgl.Gl.glAttachShader;
import static limn.backend.lwjgl.Gl.glCompileShader;
import static limn.backend.lwjgl.Gl.glCreateProgram;
import static limn.backend.lwjgl.Gl.glCreateShader;
import static limn.backend.lwjgl.Gl.glDeleteProgram;
import static limn.backend.lwjgl.Gl.glDeleteShader;
import static limn.backend.lwjgl.Gl.glDetachShader;
import static limn.backend.lwjgl.Gl.glGetProgramInfoLog;
import static limn.backend.lwjgl.Gl.glGetProgrami;
import static limn.backend.lwjgl.Gl.glGetShaderInfoLog;
import static limn.backend.lwjgl.Gl.glGetShaderi;
import static limn.backend.lwjgl.Gl.glGetUniformBlockIndex;
import static limn.backend.lwjgl.Gl.glGetUniformLocation;
import static limn.backend.lwjgl.Gl.glLinkProgram;
import static limn.backend.lwjgl.Gl.glShaderSource;
import static limn.backend.lwjgl.Gl.glUniformBlockBinding;
import static limn.backend.lwjgl.Gl.glUseProgram;

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
        int fragment;
        try {
            fragment = compile(GL_FRAGMENT_SHADER, fragmentSource, "fragment");
        } catch (RuntimeException refused) {
            glDeleteShader(vertex); // compiled, and nobody's once the other half is refused
            throw refused;
        }
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
        glShaderSource(shader, inProfile(source, Gl.api().shadingProfile()));
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed for " + name + ":\n" + log);
        }
        return shader;
    }

    /**
     * {@code source} for the shading language {@code profile} compiles. Every shader here is written
     * once, in the subset GLSL 3.30 and GLSL ES 3.00 share (the rule is quoted at the top of each), and
     * says so with {@code #version 330 core} as its first line; for ES that line, and only that
     * line, becomes {@link TargetProfile#GLSL_ES_300}'s header, so the file keeps one version of
     * the truth. The line may end in CRLF: a checkout on Windows writes the shaders so, and the
     * swap missed every one of them there, which handed ANGLE the desktop line. A source that
     * starts any other way is the author's to answer for and is compiled as written.
     */
    static String inProfile(String source, TargetProfile profile) {
        if (profile == TargetProfile.GLSL_330) {
            return source;
        }
        String header = TargetProfile.GLSL_330.header;
        String desktop = header.substring(0, header.length() - 1); // the line, without its end
        int start = 0;
        while (start < source.length() && Character.isWhitespace(source.charAt(start))) {
            start++;
        }
        if (!source.startsWith(desktop, start)) {
            return source;
        }
        int end = start + desktop.length();
        if (source.startsWith("\r\n", end)) {
            end += 2;
        } else if (source.startsWith("\n", end)) {
            end += 1;
        } else {
            return source; // "#version 330 core" followed by more on its line is not this line
        }
        return profile.header + source.substring(end);
    }

    private static String readResource(String resource) {
        // Opened here and not by Resources: on the module path the shaders sit in a package of this
        // module that is open to nobody, and only this module's own code can read it.
        return Resources.text(ShaderProgram.class.getResourceAsStream(resource), resource, "shader");
    }
}
