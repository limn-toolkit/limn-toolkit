package limn.backend.lwjgl;

import limn.render3d.shader.TargetProfile;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.FunctionProvider;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Every OpenGL entry point this backend calls, as one interface with an implementation per profile
 * {@link DesktopGl}, OpenGL 3.3 core, which every machine with a usable driver
 * gets, and the OpenGL ES 3.0 one a process takes when the machine's own OpenGL is missing and the
 * context comes from ANGLE instead.
 *
 * <p>LWJGL binds the two profiles as unrelated classes with identical method names
 * ({@code GL33C} and {@code GLES30}), each calling through function pointers its own capabilities
 * resolved, so one cannot be called through the other. The call sites therefore call {@link Gl},
 * whose static methods forward here, and the process picks one implementation before its first
 * context's capabilities are created. Constants do not take part: their values are the same in
 * both profiles, and {@code GL33C}'s are compile-time constants that javac copies into the call
 * site, so they stay where they are and {@code GlConstantsEsTest} holds them to the ES values.
 *
 * <p>Only the overloads the backend actually calls are here, each with the same signature as
 * LWJGL's: adding a call means adding its overload, and the ES implementation has to compile
 * against {@code GLES30}, so a call with no ES equivalent fails the build rather than a machine.
 * The handful of desktop-only calls (a single draw buffer, timer queries, the default
 * framebuffer's left buffer) are answered differently per profile by the methods below.
 */
interface GlApi {

    /**
     * Resolves the entry points of the context current on this thread. What it returns is this
     * profile's own capabilities object, opaque to the caller, kept per window and per driver by
     * {@link GlCapabilitiesCache} and handed back to {@link #setCapabilities}.
     */
    Object createCapabilities();

    /** Makes {@code capabilities}, made by {@link #createCapabilities}, the ones calls on this thread use. */
    void setCapabilities(@Nullable Object capabilities);

    /** Where this profile looks its entry points up, for the queries made before there are capabilities. */
    FunctionProvider functionProvider();

    /** The shading language this profile compiles: what replaces a shader's {@code #version 330 core}. */
    TargetProfile shadingProfile();

    /**
     * How a framebuffer query names the default framebuffer's colour buffer: {@code GL_BACK_LEFT} on
     * the desktop, where {@code GL_BACK} is not an attachment that query accepts, and {@code GL_BACK}
     * on ES, which has no left and right.
     */
    int defaultColorBuffer();

    /** Whether the current context can measure GPU durations. */
    boolean timerQueries();

    /**
     * Whether the GPU timings in flight are meaningless because something disjoint happened while
     * they ran (a frequency change, a context loss): {@code EXT_disjoint_timer_query} reports it and
     * asks that they be thrown away. Desktop timer queries have no such state, and answer false.
     */
    boolean timerDisjoint();

    /** Whether the current context can render to {@code RGBA16F}, the 3D target's format. */
    boolean floatColorBuffer();

    /** Whether the current context can sample normalized 16-bit {@code R16}/{@code RG16}, for 10-bit video. */
    boolean norm16Textures();

    void glActiveTexture(int texture);

    void glAttachShader(int program, int shader);

    void glBeginQuery(int target, int id);

    void glBindBuffer(int target, int buffer);

    void glBindBufferBase(int target, int index, int buffer);

    void glBindFramebuffer(int target, int framebuffer);

    void glBindRenderbuffer(int target, int renderbuffer);

    void glBindSampler(int unit, int sampler);

    void glBindTexture(int target, int texture);

    void glBindVertexArray(int array);

    void glBlendFuncSeparate(int sfactorRGB, int dfactorRGB, int sfactorAlpha, int dfactorAlpha);

    void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter);

    void glBufferData(int target, float[] data, int usage);

    void glBufferData(int target, int[] data, int usage);

    void glBufferData(int target, FloatBuffer data, int usage);

    void glBufferData(int target, IntBuffer data, int usage);

    void glBufferData(int target, long size, int usage);

    void glBufferSubData(int target, long offset, ByteBuffer data);

    int glCheckFramebufferStatus(int target);

    void glClear(int mask);

    void glClearColor(float red, float green, float blue, float alpha);

    int glClientWaitSync(long sync, int flags, long timeout);

    void glCompileShader(int shader);

    void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height);

    int glCreateProgram();

    int glCreateShader(int type);

    void glDeleteBuffers(int buffer);

    void glDeleteFramebuffers(int framebuffer);

    void glDeleteProgram(int program);

    void glDeleteQueries(int id);

    void glDeleteQueries(int[] ids);

    void glDeleteRenderbuffers(int renderbuffer);

    void glDeleteSamplers(int sampler);

    void glDeleteShader(int shader);

    void glDeleteSync(long sync);

    void glDeleteTextures(int texture);

    void glDeleteVertexArrays(int array);

    void glDepthFunc(int func);

    void glDepthMask(boolean flag);

    void glDetachShader(int program, int shader);

    void glDisable(int cap);

    void glDrawArrays(int mode, int first, int count);

    void glDrawBuffers(int buf);

    void glDrawElements(int mode, int count, int type, long indices);

    void glEnable(int cap);

    void glEnableVertexAttribArray(int index);

    void glEndQuery(int target);

    long glFenceSync(int condition, int flags);

    void glFinish();

    void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer);

    void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level);

    int glGenBuffers();

    int glGenFramebuffers();

    int glGenQueries();

    void glGenQueries(int[] ids);

    int glGenRenderbuffers();

    int glGenSamplers();

    int glGenTextures();

    int glGenVertexArrays();

    void glGenerateMipmap(int target);

    int glGetError();

    int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname);

    int glGetInteger(int pname);

    void glGetIntegerv(int pname, int[] params);

    String glGetProgramInfoLog(int program);

    int glGetProgrami(int program, int pname);

    int glGetQueryObjectui(int id, int pname);

    long glGetQueryObjectui64(int id, int pname);

    String glGetShaderInfoLog(int shader);

    int glGetShaderi(int shader, int pname);

    String glGetString(int name);

    String glGetStringi(int name, int index);

    int glGetUniformBlockIndex(int program, CharSequence uniformBlockName);

    int glGetUniformLocation(int program, CharSequence name);

    boolean glIsFramebuffer(int framebuffer);

    boolean glIsTexture(int texture);

    void glLinkProgram(int program);

    void glPixelStorei(int pname, int param);

    void glPolygonOffset(float factor, float units);

    void glReadBuffer(int src);

    void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels);

    void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels);

    void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels);

    void glRenderbufferStorage(int target, int internalformat, int width, int height);

    void glRenderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height);

    void glSamplerParameteri(int sampler, int pname, int param);

    void glScissor(int x, int y, int width, int height);

    void glShaderSource(int shader, CharSequence string);

    void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels);

    void glTexParameteri(int target, int pname, int param);

    void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels);

    void glUniform1f(int location, float v0);

    void glUniform1i(int location, int v0);

    void glUniform2f(int location, float v0, float v1);

    void glUniform2i(int location, int v0, int v1);

    void glUniform3f(int location, float v0, float v1, float v2);

    void glUniform3fv(int location, float[] value);

    void glUniform4f(int location, float v0, float v1, float v2, float v3);

    void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding);

    void glUniformMatrix3fv(int location, boolean transpose, float[] value);

    void glUniformMatrix4fv(int location, boolean transpose, float[] value);

    void glUseProgram(int program);

    void glVertexAttrib2f(int index, float x, float y);

    void glVertexAttrib4f(int index, float x, float y, float z, float w);

    void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer);

    void glViewport(int x, int y, int width, int height);

    void nglTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels);
}
