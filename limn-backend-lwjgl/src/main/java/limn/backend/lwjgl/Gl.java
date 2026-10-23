package limn.backend.lwjgl;

import org.lwjgl.opengl.GL33C;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The OpenGL calls of this backend, through the profile the process runs on ({@link GlApi}), and
 * the two GL idioms every texture and framebuffer in it repeats: the four sampling parameters of a
 * 2D texture, and the completeness check after attaching to a framebuffer.
 *
 * <p>Every call site names {@code Gl.glFoo(…)}, or imports it statically, where it used to name
 * {@code GL33C}'s method of the same name; the methods forward to {@link #api}, which is
 * {@link DesktopGl} until a window's context comes from ANGLE ({@link AngleFallback}). One profile per
 * process: {@link #use} is called before the first context's capabilities are created and not
 * again with another.
 *
 * <p>Eight textures set MIN and MAG filter and WRAP_S and WRAP_T in the same four lines, and
 * three framebuffers read {@code glCheckFramebufferStatus}, tore down and threw the same
 * "FBO incomplete: 0x" line. What differs is a filter, a wrap mode, a name and what to tear
 * down, and those are the parameters here.
 */
final class Gl {

    /** The profile every call goes to; see the class comment. */
    private static GlApi api = DesktopGl.INSTANCE;

    private Gl() {
    }

    /** The profile the process runs on. */
    static GlApi api() {
        return api;
    }

    /** Sets the profile every call goes to, before the first context's capabilities exist. */
    static void use(GlApi profile) {
        api = profile;
    }

    /**
     * Sets how the 2D texture bound to the current unit is sampled: one filter for both
     * minification and magnification, one wrap mode for both axes.
     *
     * @param filter {@code GL_NEAREST} or {@code GL_LINEAR}
     * @param wrap   {@code GL_CLAMP_TO_EDGE} or {@code GL_REPEAT}
     */
    static void sample2D(int filter, int wrap) {
        sample2D(filter, filter, wrap);
    }

    /**
     * Sets how the 2D texture bound to the current unit is sampled.
     *
     * @param minFilter the minification filter, a mipmapped one included
     * @param magFilter the magnification filter
     * @param wrap      the wrap mode, for both axes
     */
    static void sample2D(int minFilter, int magFilter, int wrap) {
        glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MIN_FILTER, minFilter);
        glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_MAG_FILTER, magFilter);
        glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL33C.GL_TEXTURE_2D, GL33C.GL_TEXTURE_WRAP_T, wrap);
    }

    /**
     * Refuses an incomplete framebuffer: the one bound to {@code GL_FRAMEBUFFER} is checked, and
     * if it is not complete {@code cleanup} runs and the status is thrown.
     *
     * @param what    which framebuffer, for the message: "shadow", "3D resolve"
     * @param cleanup what to tear down before throwing, so nothing half-built is left behind
     * @throws IllegalStateException if the framebuffer is incomplete
     */
    static void requireFramebufferComplete(String what, Runnable cleanup) {
        requireFramebufferComplete(glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER), what, cleanup);
    }

    /**
     * {@link #requireFramebufferComplete(String, Runnable)} for a status the caller already read,
     * for the caller that has to restore a binding between reading it and reacting to it.
     *
     * @param status  what {@code glCheckFramebufferStatus} answered
     * @param what    which framebuffer, for the message
     * @param cleanup what to tear down before throwing
     * @throws IllegalStateException if the status is not complete
     */
    static void requireFramebufferComplete(int status, String what, Runnable cleanup) {
        if (status != GL33C.GL_FRAMEBUFFER_COMPLETE) {
            cleanup.run();
            throw new IllegalStateException(what + " FBO incomplete: 0x" + Integer.toHexString(status));
        }
    }

    // ------------------------------------------------------------------ the calls

    static void glActiveTexture(int texture) {
        api.glActiveTexture(texture);
    }

    static void glAttachShader(int program, int shader) {
        api.glAttachShader(program, shader);
    }

    static void glBeginQuery(int target, int id) {
        api.glBeginQuery(target, id);
    }

    static void glBindBuffer(int target, int buffer) {
        api.glBindBuffer(target, buffer);
    }

    static void glBindBufferBase(int target, int index, int buffer) {
        api.glBindBufferBase(target, index, buffer);
    }

    static void glBindFramebuffer(int target, int framebuffer) {
        api.glBindFramebuffer(target, framebuffer);
    }

    static void glBindRenderbuffer(int target, int renderbuffer) {
        api.glBindRenderbuffer(target, renderbuffer);
    }

    static void glBindSampler(int unit, int sampler) {
        api.glBindSampler(unit, sampler);
    }

    static void glBindTexture(int target, int texture) {
        api.glBindTexture(target, texture);
    }

    static void glBindVertexArray(int array) {
        api.glBindVertexArray(array);
    }

    static void glBlendFuncSeparate(int sfactorRGB, int dfactorRGB, int sfactorAlpha, int dfactorAlpha) {
        api.glBlendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
    }

    static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        api.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    static void glBufferData(int target, float[] data, int usage) {
        api.glBufferData(target, data, usage);
    }

    static void glBufferData(int target, int[] data, int usage) {
        api.glBufferData(target, data, usage);
    }

    static void glBufferData(int target, FloatBuffer data, int usage) {
        api.glBufferData(target, data, usage);
    }

    static void glBufferData(int target, IntBuffer data, int usage) {
        api.glBufferData(target, data, usage);
    }

    static void glBufferData(int target, long size, int usage) {
        api.glBufferData(target, size, usage);
    }

    static void glBufferSubData(int target, long offset, ByteBuffer data) {
        api.glBufferSubData(target, offset, data);
    }

    static int glCheckFramebufferStatus(int target) {
        return api.glCheckFramebufferStatus(target);
    }

    static void glClear(int mask) {
        api.glClear(mask);
    }

    static void glClearColor(float red, float green, float blue, float alpha) {
        api.glClearColor(red, green, blue, alpha);
    }

    static int glClientWaitSync(long sync, int flags, long timeout) {
        return api.glClientWaitSync(sync, flags, timeout);
    }

    static void glCompileShader(int shader) {
        api.glCompileShader(shader);
    }

    static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        api.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    static int glCreateProgram() {
        return api.glCreateProgram();
    }

    static int glCreateShader(int type) {
        return api.glCreateShader(type);
    }

    static void glDeleteBuffers(int buffer) {
        api.glDeleteBuffers(buffer);
    }

    static void glDeleteFramebuffers(int framebuffer) {
        api.glDeleteFramebuffers(framebuffer);
    }

    static void glDeleteProgram(int program) {
        api.glDeleteProgram(program);
    }

    static void glDeleteQueries(int id) {
        api.glDeleteQueries(id);
    }

    static void glDeleteQueries(int[] ids) {
        api.glDeleteQueries(ids);
    }

    static void glDeleteRenderbuffers(int renderbuffer) {
        api.glDeleteRenderbuffers(renderbuffer);
    }

    static void glDeleteSamplers(int sampler) {
        api.glDeleteSamplers(sampler);
    }

    static void glDeleteShader(int shader) {
        api.glDeleteShader(shader);
    }

    static void glDeleteSync(long sync) {
        api.glDeleteSync(sync);
    }

    static void glDeleteTextures(int texture) {
        api.glDeleteTextures(texture);
    }

    static void glDeleteVertexArrays(int array) {
        api.glDeleteVertexArrays(array);
    }

    static void glDepthFunc(int func) {
        api.glDepthFunc(func);
    }

    static void glDepthMask(boolean flag) {
        api.glDepthMask(flag);
    }

    static void glDetachShader(int program, int shader) {
        api.glDetachShader(program, shader);
    }

    static void glDisable(int cap) {
        api.glDisable(cap);
    }

    static void glDrawArrays(int mode, int first, int count) {
        api.glDrawArrays(mode, first, count);
    }

    static void glDrawBuffers(int buf) {
        api.glDrawBuffers(buf);
    }

    static void glDrawElements(int mode, int count, int type, long indices) {
        api.glDrawElements(mode, count, type, indices);
    }

    static void glEnable(int cap) {
        api.glEnable(cap);
    }

    static void glEnableVertexAttribArray(int index) {
        api.glEnableVertexAttribArray(index);
    }

    static void glEndQuery(int target) {
        api.glEndQuery(target);
    }

    static long glFenceSync(int condition, int flags) {
        return api.glFenceSync(condition, flags);
    }

    static void glFinish() {
        api.glFinish();
    }

    static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        api.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
    }

    static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        api.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    static int glGenBuffers() {
        return api.glGenBuffers();
    }

    static int glGenFramebuffers() {
        return api.glGenFramebuffers();
    }

    static int glGenQueries() {
        return api.glGenQueries();
    }

    static void glGenQueries(int[] ids) {
        api.glGenQueries(ids);
    }

    static int glGenRenderbuffers() {
        return api.glGenRenderbuffers();
    }

    static int glGenSamplers() {
        return api.glGenSamplers();
    }

    static int glGenTextures() {
        return api.glGenTextures();
    }

    static int glGenVertexArrays() {
        return api.glGenVertexArrays();
    }

    static void glGenerateMipmap(int target) {
        api.glGenerateMipmap(target);
    }

    static int glGetError() {
        return api.glGetError();
    }

    static int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        return api.glGetFramebufferAttachmentParameteri(target, attachment, pname);
    }

    static int glGetInteger(int pname) {
        return api.glGetInteger(pname);
    }

    static void glGetIntegerv(int pname, int[] params) {
        api.glGetIntegerv(pname, params);
    }

    static String glGetProgramInfoLog(int program) {
        return api.glGetProgramInfoLog(program);
    }

    static int glGetProgrami(int program, int pname) {
        return api.glGetProgrami(program, pname);
    }

    static int glGetQueryObjectui(int id, int pname) {
        return api.glGetQueryObjectui(id, pname);
    }

    static long glGetQueryObjectui64(int id, int pname) {
        return api.glGetQueryObjectui64(id, pname);
    }

    static String glGetShaderInfoLog(int shader) {
        return api.glGetShaderInfoLog(shader);
    }

    static int glGetShaderi(int shader, int pname) {
        return api.glGetShaderi(shader, pname);
    }

    static String glGetString(int name) {
        return api.glGetString(name);
    }

    static String glGetStringi(int name, int index) {
        return api.glGetStringi(name, index);
    }

    static int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        return api.glGetUniformBlockIndex(program, uniformBlockName);
    }

    static int glGetUniformLocation(int program, CharSequence name) {
        return api.glGetUniformLocation(program, name);
    }

    static boolean glIsFramebuffer(int framebuffer) {
        return api.glIsFramebuffer(framebuffer);
    }

    static boolean glIsTexture(int texture) {
        return api.glIsTexture(texture);
    }

    static void glLinkProgram(int program) {
        api.glLinkProgram(program);
    }

    static void glPixelStorei(int pname, int param) {
        api.glPixelStorei(pname, param);
    }

    static void glPolygonOffset(float factor, float units) {
        api.glPolygonOffset(factor, units);
    }

    static void glReadBuffer(int src) {
        api.glReadBuffer(src);
    }

    static void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        api.glReadPixels(x, y, width, height, format, type, pixels);
    }

    static void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        api.glReadPixels(x, y, width, height, format, type, pixels);
    }

    static void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        api.glReadPixels(x, y, width, height, format, type, pixels);
    }

    static void glRenderbufferStorage(int target, int internalformat, int width, int height) {
        api.glRenderbufferStorage(target, internalformat, width, height);
    }

    static void glRenderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {
        api.glRenderbufferStorageMultisample(target, samples, internalformat, width, height);
    }

    static void glSamplerParameteri(int sampler, int pname, int param) {
        api.glSamplerParameteri(sampler, pname, param);
    }

    static void glScissor(int x, int y, int width, int height) {
        api.glScissor(x, y, width, height);
    }

    static void glShaderSource(int shader, CharSequence string) {
        api.glShaderSource(shader, string);
    }

    static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        api.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    static void glTexParameteri(int target, int pname, int param) {
        api.glTexParameteri(target, pname, param);
    }

    static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        api.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    static void glUniform1f(int location, float v0) {
        api.glUniform1f(location, v0);
    }

    static void glUniform1i(int location, int v0) {
        api.glUniform1i(location, v0);
    }

    static void glUniform2f(int location, float v0, float v1) {
        api.glUniform2f(location, v0, v1);
    }

    static void glUniform2i(int location, int v0, int v1) {
        api.glUniform2i(location, v0, v1);
    }

    static void glUniform3f(int location, float v0, float v1, float v2) {
        api.glUniform3f(location, v0, v1, v2);
    }

    static void glUniform3fv(int location, float[] value) {
        api.glUniform3fv(location, value);
    }

    static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        api.glUniform4f(location, v0, v1, v2, v3);
    }

    static void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        api.glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
    }

    static void glUniformMatrix3fv(int location, boolean transpose, float[] value) {
        api.glUniformMatrix3fv(location, transpose, value);
    }

    static void glUniformMatrix4fv(int location, boolean transpose, float[] value) {
        api.glUniformMatrix4fv(location, transpose, value);
    }

    static void glUseProgram(int program) {
        api.glUseProgram(program);
    }

    static void glVertexAttrib2f(int index, float x, float y) {
        api.glVertexAttrib2f(index, x, y);
    }

    static void glVertexAttrib4f(int index, float x, float y, float z, float w) {
        api.glVertexAttrib4f(index, x, y, z, w);
    }

    static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        api.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    static void glViewport(int x, int y, int width, int height) {
        api.glViewport(x, y, width, height);
    }

    static void nglTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels) {
        api.nglTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }
}
