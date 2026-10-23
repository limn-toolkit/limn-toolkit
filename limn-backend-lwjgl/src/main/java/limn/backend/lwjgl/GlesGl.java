package limn.backend.lwjgl;

import limn.render3d.shader.TargetProfile;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengles.EXTDisjointTimerQuery;
import org.lwjgl.opengles.GLES;
import org.lwjgl.opengles.GLES30;
import org.lwjgl.opengles.GLESCapabilities;
import org.lwjgl.system.FunctionProvider;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * {@link GlApi} on OpenGL ES 3.0: the profile a process runs on once a window's context comes from
 * ANGLE because the machine's own OpenGL gave none ({@link AngleFallback}). Each method is the LWJGL ES
 * call of the same name, with an answer of its own where the desktop call
 * has no ES twin: GPU timings through {@code EXT_disjoint_timer_query}, and the float colour buffer
 * and 16-bit normalized textures through their extensions, which the Phase 0b and 0c probes found
 * on Direct3D 11 and on Metal.
 *
 * <p>Made only by {@link AngleLibrary}'s caller, after the libraries are loaded and LWJGL's ES
 * binding pointed at them.
 */
final class GlesGl implements GlApi {

    static final GlesGl INSTANCE = new GlesGl();

    private GlesGl() {
    }

    @Override
    public Object createCapabilities() {
        created();
        return GLES.createCapabilities();
    }

    @Override
    public void setCapabilities(@Nullable Object capabilities) {
        GLES.setCapabilities((GLESCapabilities) capabilities);
    }

    @Override
    public FunctionProvider functionProvider() {
        return created();
    }

    /**
     * LWJGL's ES binding, created. LWJGL creates it when the class is first touched, unless
     * something in the process asked to create it by hand ({@code Configuration.OPENGLES_EXPLICIT_INIT});
     * then it is created here, from the library {@link AngleLibrary} named.
     */
    private static FunctionProvider created() {
        FunctionProvider functions = GLES.getFunctionProvider();
        if (functions == null) {
            GLES.create();
            functions = GLES.getFunctionProvider();
        }
        return functions;
    }

    @Override
    public TargetProfile shadingProfile() {
        return TargetProfile.GLSL_ES_300;
    }

    @Override
    public int defaultColorBuffer() {
        return GLES30.GL_BACK;
    }

    @Override
    public boolean timerQueries() {
        return GLES.getCapabilities().GL_EXT_disjoint_timer_query;
    }

    @Override
    public boolean timerDisjoint() {
        return timerQueries() && GLES30.glGetInteger(EXTDisjointTimerQuery.GL_GPU_DISJOINT_EXT) != 0;
    }

    @Override
    public boolean floatColorBuffer() {
        return GLES.getCapabilities().GL_EXT_color_buffer_float;
    }

    @Override
    public boolean norm16Textures() {
        return GLES.getCapabilities().GL_EXT_texture_norm16;
    }

    @Override
    public void glActiveTexture(int texture) {
        GLES30.glActiveTexture(texture);
    }

    @Override
    public void glAttachShader(int program, int shader) {
        GLES30.glAttachShader(program, shader);
    }

    @Override
    public void glBeginQuery(int target, int id) {
        GLES30.glBeginQuery(target, id);
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        GLES30.glBindBuffer(target, buffer);
    }

    @Override
    public void glBindBufferBase(int target, int index, int buffer) {
        GLES30.glBindBufferBase(target, index, buffer);
    }

    @Override
    public void glBindFramebuffer(int target, int framebuffer) {
        GLES30.glBindFramebuffer(target, framebuffer);
    }

    @Override
    public void glBindRenderbuffer(int target, int renderbuffer) {
        GLES30.glBindRenderbuffer(target, renderbuffer);
    }

    @Override
    public void glBindSampler(int unit, int sampler) {
        GLES30.glBindSampler(unit, sampler);
    }

    @Override
    public void glBindTexture(int target, int texture) {
        GLES30.glBindTexture(target, texture);
    }

    @Override
    public void glBindVertexArray(int array) {
        GLES30.glBindVertexArray(array);
    }

    @Override
    public void glBlendFuncSeparate(int sfactorRGB, int dfactorRGB, int sfactorAlpha, int dfactorAlpha) {
        GLES30.glBlendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
    }

    @Override
    public void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        GLES30.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    @Override
    public void glBufferData(int target, float[] data, int usage) {
        GLES30.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, int[] data, int usage) {
        GLES30.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, FloatBuffer data, int usage) {
        GLES30.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, IntBuffer data, int usage) {
        GLES30.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, long size, int usage) {
        GLES30.glBufferData(target, size, usage);
    }

    @Override
    public void glBufferSubData(int target, long offset, ByteBuffer data) {
        GLES30.glBufferSubData(target, offset, data);
    }

    @Override
    public int glCheckFramebufferStatus(int target) {
        return GLES30.glCheckFramebufferStatus(target);
    }

    @Override
    public void glClear(int mask) {
        GLES30.glClear(mask);
    }

    @Override
    public void glClearColor(float red, float green, float blue, float alpha) {
        GLES30.glClearColor(red, green, blue, alpha);
    }

    @Override
    public int glClientWaitSync(long sync, int flags, long timeout) {
        return GLES30.glClientWaitSync(sync, flags, timeout);
    }

    @Override
    public void glCompileShader(int shader) {
        GLES30.glCompileShader(shader);
    }

    @Override
    public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        GLES30.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    @Override
    public int glCreateProgram() {
        return GLES30.glCreateProgram();
    }

    @Override
    public int glCreateShader(int type) {
        return GLES30.glCreateShader(type);
    }

    @Override
    public void glDeleteBuffers(int buffer) {
        GLES30.glDeleteBuffers(buffer);
    }

    @Override
    public void glDeleteFramebuffers(int framebuffer) {
        GLES30.glDeleteFramebuffers(framebuffer);
    }

    @Override
    public void glDeleteProgram(int program) {
        GLES30.glDeleteProgram(program);
    }

    @Override
    public void glDeleteQueries(int id) {
        GLES30.glDeleteQueries(id);
    }

    @Override
    public void glDeleteQueries(int[] ids) {
        GLES30.glDeleteQueries(ids);
    }

    @Override
    public void glDeleteRenderbuffers(int renderbuffer) {
        GLES30.glDeleteRenderbuffers(renderbuffer);
    }

    @Override
    public void glDeleteSamplers(int sampler) {
        GLES30.glDeleteSamplers(sampler);
    }

    @Override
    public void glDeleteShader(int shader) {
        GLES30.glDeleteShader(shader);
    }

    @Override
    public void glDeleteSync(long sync) {
        GLES30.glDeleteSync(sync);
    }

    @Override
    public void glDeleteTextures(int texture) {
        GLES30.glDeleteTextures(texture);
    }

    @Override
    public void glDeleteVertexArrays(int array) {
        GLES30.glDeleteVertexArrays(array);
    }

    @Override
    public void glDepthFunc(int func) {
        GLES30.glDepthFunc(func);
    }

    @Override
    public void glDepthMask(boolean flag) {
        GLES30.glDepthMask(flag);
    }

    @Override
    public void glDetachShader(int program, int shader) {
        GLES30.glDetachShader(program, shader);
    }

    @Override
    public void glDisable(int cap) {
        GLES30.glDisable(cap);
    }

    @Override
    public void glDrawArrays(int mode, int first, int count) {
        GLES30.glDrawArrays(mode, first, count);
    }

    @Override
    public void glDrawBuffers(int buf) {
        GLES30.glDrawBuffers(buf);
    }

    @Override
    public void glDrawElements(int mode, int count, int type, long indices) {
        GLES30.glDrawElements(mode, count, type, indices);
    }

    @Override
    public void glEnable(int cap) {
        GLES30.glEnable(cap);
    }

    @Override
    public void glEnableVertexAttribArray(int index) {
        GLES30.glEnableVertexAttribArray(index);
    }

    @Override
    public void glEndQuery(int target) {
        GLES30.glEndQuery(target);
    }

    @Override
    public long glFenceSync(int condition, int flags) {
        return GLES30.glFenceSync(condition, flags);
    }

    @Override
    public void glFinish() {
        GLES30.glFinish();
    }

    @Override
    public void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        GLES30.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
    }

    @Override
    public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        GLES30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    @Override
    public int glGenBuffers() {
        return GLES30.glGenBuffers();
    }

    @Override
    public int glGenFramebuffers() {
        return GLES30.glGenFramebuffers();
    }

    @Override
    public int glGenQueries() {
        return GLES30.glGenQueries();
    }

    @Override
    public void glGenQueries(int[] ids) {
        GLES30.glGenQueries(ids);
    }

    @Override
    public int glGenRenderbuffers() {
        return GLES30.glGenRenderbuffers();
    }

    @Override
    public int glGenSamplers() {
        return GLES30.glGenSamplers();
    }

    @Override
    public int glGenTextures() {
        return GLES30.glGenTextures();
    }

    @Override
    public int glGenVertexArrays() {
        return GLES30.glGenVertexArrays();
    }

    @Override
    public void glGenerateMipmap(int target) {
        GLES30.glGenerateMipmap(target);
    }

    @Override
    public int glGetError() {
        return GLES30.glGetError();
    }

    @Override
    public int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        return GLES30.glGetFramebufferAttachmentParameteri(target, attachment, pname);
    }

    @Override
    public int glGetInteger(int pname) {
        return GLES30.glGetInteger(pname);
    }

    @Override
    public void glGetIntegerv(int pname, int[] params) {
        GLES30.glGetIntegerv(pname, params);
    }

    @Override
    public String glGetProgramInfoLog(int program) {
        return GLES30.glGetProgramInfoLog(program);
    }

    @Override
    public int glGetProgrami(int program, int pname) {
        return GLES30.glGetProgrami(program, pname);
    }

    @Override
    public int glGetQueryObjectui(int id, int pname) {
        return GLES30.glGetQueryObjectui(id, pname);
    }

    @Override
    public long glGetQueryObjectui64(int id, int pname) {
        return EXTDisjointTimerQuery.glGetQueryObjectui64EXT(id, pname);
    }

    @Override
    public String glGetShaderInfoLog(int shader) {
        return GLES30.glGetShaderInfoLog(shader);
    }

    @Override
    public int glGetShaderi(int shader, int pname) {
        return GLES30.glGetShaderi(shader, pname);
    }

    @Override
    public String glGetString(int name) {
        return GLES30.glGetString(name);
    }

    @Override
    public String glGetStringi(int name, int index) {
        return GLES30.glGetStringi(name, index);
    }

    @Override
    public int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        return GLES30.glGetUniformBlockIndex(program, uniformBlockName);
    }

    @Override
    public int glGetUniformLocation(int program, CharSequence name) {
        return GLES30.glGetUniformLocation(program, name);
    }

    @Override
    public boolean glIsFramebuffer(int framebuffer) {
        return GLES30.glIsFramebuffer(framebuffer);
    }

    @Override
    public boolean glIsTexture(int texture) {
        return GLES30.glIsTexture(texture);
    }

    @Override
    public void glLinkProgram(int program) {
        GLES30.glLinkProgram(program);
    }

    @Override
    public void glPixelStorei(int pname, int param) {
        GLES30.glPixelStorei(pname, param);
    }

    @Override
    public void glPolygonOffset(float factor, float units) {
        GLES30.glPolygonOffset(factor, units);
    }

    @Override
    public void glReadBuffer(int src) {
        GLES30.glReadBuffer(src);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        GLES30.glReadPixels(x, y, width, height, format, type, pixels);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        GLES30.glReadPixels(x, y, width, height, format, type, pixels);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        GLES30.glReadPixels(x, y, width, height, format, type, pixels);
    }

    @Override
    public void glRenderbufferStorage(int target, int internalformat, int width, int height) {
        GLES30.glRenderbufferStorage(target, internalformat, width, height);
    }

    @Override
    public void glRenderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {
        GLES30.glRenderbufferStorageMultisample(target, samples, internalformat, width, height);
    }

    @Override
    public void glSamplerParameteri(int sampler, int pname, int param) {
        GLES30.glSamplerParameteri(sampler, pname, param);
    }

    @Override
    public void glScissor(int x, int y, int width, int height) {
        GLES30.glScissor(x, y, width, height);
    }

    @Override
    public void glShaderSource(int shader, CharSequence string) {
        GLES30.glShaderSource(shader, string);
    }

    @Override
    public void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        GLES30.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    @Override
    public void glTexParameteri(int target, int pname, int param) {
        GLES30.glTexParameteri(target, pname, param);
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        GLES30.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    @Override
    public void glUniform1f(int location, float v0) {
        GLES30.glUniform1f(location, v0);
    }

    @Override
    public void glUniform1i(int location, int v0) {
        GLES30.glUniform1i(location, v0);
    }

    @Override
    public void glUniform2f(int location, float v0, float v1) {
        GLES30.glUniform2f(location, v0, v1);
    }

    @Override
    public void glUniform2i(int location, int v0, int v1) {
        GLES30.glUniform2i(location, v0, v1);
    }

    @Override
    public void glUniform3f(int location, float v0, float v1, float v2) {
        GLES30.glUniform3f(location, v0, v1, v2);
    }

    @Override
    public void glUniform3fv(int location, float[] value) {
        GLES30.glUniform3fv(location, value);
    }

    @Override
    public void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        GLES30.glUniform4f(location, v0, v1, v2, v3);
    }

    @Override
    public void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        GLES30.glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
    }

    @Override
    public void glUniformMatrix3fv(int location, boolean transpose, float[] value) {
        GLES30.glUniformMatrix3fv(location, transpose, value);
    }

    @Override
    public void glUniformMatrix4fv(int location, boolean transpose, float[] value) {
        GLES30.glUniformMatrix4fv(location, transpose, value);
    }

    @Override
    public void glUseProgram(int program) {
        GLES30.glUseProgram(program);
    }

    @Override
    public void glVertexAttrib2f(int index, float x, float y) {
        GLES30.glVertexAttrib2f(index, x, y);
    }

    @Override
    public void glVertexAttrib4f(int index, float x, float y, float z, float w) {
        GLES30.glVertexAttrib4f(index, x, y, z, w);
    }

    @Override
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        GLES30.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        GLES30.glViewport(x, y, width, height);
    }

    @Override
    public void nglTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels) {
        GLES30.nglTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }
}
