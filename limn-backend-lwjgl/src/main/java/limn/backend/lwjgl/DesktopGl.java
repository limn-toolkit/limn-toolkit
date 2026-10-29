package limn.backend.lwjgl;

import limn.render3d.shader.TargetProfile;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.FunctionProvider;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * {@link GlApi} on OpenGL 3.3 core: the profile every machine with a usable OpenGL driver runs on,
 * and the only one this backend had before the OpenGL ES fallback. Each method is the LWJGL call of the
 * same name and nothing else.
 */
final class DesktopGl implements GlApi {

    static final DesktopGl INSTANCE = new DesktopGl();

    private DesktopGl() {
    }

    @Override
    public Object createCapabilities() {
        // LWJGL creates its OpenGL binding when the class is first touched, unless something in
        // the process asked to create it by hand (Configuration.OPENGL_EXPLICIT_INIT); then it is
        // created here. Only one of the GL and ES bindings can exist in a process.
        if (GL.getFunctionProvider() == null) {
            GL.create();
        }
        return GL.createCapabilities();
    }

    @Override
    public void setCapabilities(@Nullable Object capabilities) {
        GL.setCapabilities((GLCapabilities) capabilities);
    }

    @Override
    public FunctionProvider functionProvider() {
        return GL.getFunctionProvider();
    }

    @Override
    public void loadBinding() {
        if (GL.getFunctionProvider() == null) {
            GL.create();
        }
    }

    @Override
    public void destroyBinding() {
        GL.setCapabilities(null);
        if (GL.getFunctionProvider() != null) {
            GL.destroy();
        }
    }

    @Override
    public TargetProfile shadingProfile() {
        return TargetProfile.GLSL_330;
    }

    @Override
    public int defaultColorBuffer() {
        return GL33C.GL_BACK_LEFT;
    }

    @Override
    public boolean timerDisjoint() {
        return false;
    }

    @Override
    public boolean timerQueries() {
        GLCapabilities capabilities = GL.getCapabilities();
        return capabilities.OpenGL33 || capabilities.GL_ARB_timer_query;
    }

    @Override
    public boolean floatColorBuffer() {
        return GL.getCapabilities().OpenGL30;
    }

    @Override
    public boolean norm16Textures() {
        return GL.getCapabilities().OpenGL30;
    }

    @Override
    public void glActiveTexture(int texture) {
        GL33C.glActiveTexture(texture);
    }

    @Override
    public void glAttachShader(int program, int shader) {
        GL33C.glAttachShader(program, shader);
    }

    @Override
    public void glBeginQuery(int target, int id) {
        GL33C.glBeginQuery(target, id);
    }

    @Override
    public void glBindBuffer(int target, int buffer) {
        GL33C.glBindBuffer(target, buffer);
    }

    @Override
    public void glBindBufferBase(int target, int index, int buffer) {
        GL33C.glBindBufferBase(target, index, buffer);
    }

    @Override
    public void glBindFramebuffer(int target, int framebuffer) {
        GL33C.glBindFramebuffer(target, framebuffer);
    }

    @Override
    public void glBindRenderbuffer(int target, int renderbuffer) {
        GL33C.glBindRenderbuffer(target, renderbuffer);
    }

    @Override
    public void glBindSampler(int unit, int sampler) {
        GL33C.glBindSampler(unit, sampler);
    }

    @Override
    public void glBindTexture(int target, int texture) {
        GL33C.glBindTexture(target, texture);
    }

    @Override
    public void glBindVertexArray(int array) {
        GL33C.glBindVertexArray(array);
    }

    @Override
    public void glBlendFuncSeparate(int sfactorRGB, int dfactorRGB, int sfactorAlpha, int dfactorAlpha) {
        GL33C.glBlendFuncSeparate(sfactorRGB, dfactorRGB, sfactorAlpha, dfactorAlpha);
    }

    @Override
    public void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        GL33C.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    @Override
    public void glBufferData(int target, float[] data, int usage) {
        GL33C.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, int[] data, int usage) {
        GL33C.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, FloatBuffer data, int usage) {
        GL33C.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, IntBuffer data, int usage) {
        GL33C.glBufferData(target, data, usage);
    }

    @Override
    public void glBufferData(int target, long size, int usage) {
        GL33C.glBufferData(target, size, usage);
    }

    @Override
    public void glBufferSubData(int target, long offset, ByteBuffer data) {
        GL33C.glBufferSubData(target, offset, data);
    }

    @Override
    public int glCheckFramebufferStatus(int target) {
        return GL33C.glCheckFramebufferStatus(target);
    }

    @Override
    public void glClear(int mask) {
        GL33C.glClear(mask);
    }

    @Override
    public void glClearColor(float red, float green, float blue, float alpha) {
        GL33C.glClearColor(red, green, blue, alpha);
    }

    @Override
    public int glClientWaitSync(long sync, int flags, long timeout) {
        return GL33C.glClientWaitSync(sync, flags, timeout);
    }

    @Override
    public void glCompileShader(int shader) {
        GL33C.glCompileShader(shader);
    }

    @Override
    public void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        GL33C.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
    }

    @Override
    public int glCreateProgram() {
        return GL33C.glCreateProgram();
    }

    @Override
    public int glCreateShader(int type) {
        return GL33C.glCreateShader(type);
    }

    @Override
    public void glDeleteBuffers(int buffer) {
        GL33C.glDeleteBuffers(buffer);
    }

    @Override
    public void glDeleteFramebuffers(int framebuffer) {
        GL33C.glDeleteFramebuffers(framebuffer);
    }

    @Override
    public void glDeleteProgram(int program) {
        GL33C.glDeleteProgram(program);
    }

    @Override
    public void glDeleteQueries(int id) {
        GL33C.glDeleteQueries(id);
    }

    @Override
    public void glDeleteQueries(int[] ids) {
        GL33C.glDeleteQueries(ids);
    }

    @Override
    public void glDeleteRenderbuffers(int renderbuffer) {
        GL33C.glDeleteRenderbuffers(renderbuffer);
    }

    @Override
    public void glDeleteSamplers(int sampler) {
        GL33C.glDeleteSamplers(sampler);
    }

    @Override
    public void glDeleteShader(int shader) {
        GL33C.glDeleteShader(shader);
    }

    @Override
    public void glDeleteSync(long sync) {
        GL33C.glDeleteSync(sync);
    }

    @Override
    public void glDeleteTextures(int texture) {
        GL33C.glDeleteTextures(texture);
    }

    @Override
    public void glDeleteVertexArrays(int array) {
        GL33C.glDeleteVertexArrays(array);
    }

    @Override
    public void glDepthFunc(int func) {
        GL33C.glDepthFunc(func);
    }

    @Override
    public void glDepthMask(boolean flag) {
        GL33C.glDepthMask(flag);
    }

    @Override
    public void glDetachShader(int program, int shader) {
        GL33C.glDetachShader(program, shader);
    }

    @Override
    public void glDisable(int cap) {
        GL33C.glDisable(cap);
    }

    @Override
    public void glDrawArrays(int mode, int first, int count) {
        GL33C.glDrawArrays(mode, first, count);
    }

    @Override
    public void glDrawBuffers(int buf) {
        GL33C.glDrawBuffers(buf);
    }

    @Override
    public void glDrawElements(int mode, int count, int type, long indices) {
        GL33C.glDrawElements(mode, count, type, indices);
    }

    @Override
    public void glEnable(int cap) {
        GL33C.glEnable(cap);
    }

    @Override
    public void glEnableVertexAttribArray(int index) {
        GL33C.glEnableVertexAttribArray(index);
    }

    @Override
    public void glEndQuery(int target) {
        GL33C.glEndQuery(target);
    }

    @Override
    public long glFenceSync(int condition, int flags) {
        return GL33C.glFenceSync(condition, flags);
    }

    @Override
    public void glFinish() {
        GL33C.glFinish();
    }

    @Override
    public void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
        GL33C.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer);
    }

    @Override
    public void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        GL33C.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    @Override
    public int glGenBuffers() {
        return GL33C.glGenBuffers();
    }

    @Override
    public int glGenFramebuffers() {
        return GL33C.glGenFramebuffers();
    }

    @Override
    public int glGenQueries() {
        return GL33C.glGenQueries();
    }

    @Override
    public void glGenQueries(int[] ids) {
        GL33C.glGenQueries(ids);
    }

    @Override
    public int glGenRenderbuffers() {
        return GL33C.glGenRenderbuffers();
    }

    @Override
    public int glGenSamplers() {
        return GL33C.glGenSamplers();
    }

    @Override
    public int glGenTextures() {
        return GL33C.glGenTextures();
    }

    @Override
    public int glGenVertexArrays() {
        return GL33C.glGenVertexArrays();
    }

    @Override
    public void glGenerateMipmap(int target) {
        GL33C.glGenerateMipmap(target);
    }

    @Override
    public int glGetError() {
        return GL33C.glGetError();
    }

    @Override
    public int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        return GL33C.glGetFramebufferAttachmentParameteri(target, attachment, pname);
    }

    @Override
    public int glGetInteger(int pname) {
        return GL33C.glGetInteger(pname);
    }

    @Override
    public void glGetIntegerv(int pname, int[] params) {
        GL33C.glGetIntegerv(pname, params);
    }

    @Override
    public String glGetProgramInfoLog(int program) {
        return GL33C.glGetProgramInfoLog(program);
    }

    @Override
    public int glGetProgrami(int program, int pname) {
        return GL33C.glGetProgrami(program, pname);
    }

    @Override
    public int glGetQueryObjectui(int id, int pname) {
        return GL33C.glGetQueryObjectui(id, pname);
    }

    @Override
    public long glGetQueryObjectui64(int id, int pname) {
        return GL33C.glGetQueryObjectui64(id, pname);
    }

    @Override
    public String glGetShaderInfoLog(int shader) {
        return GL33C.glGetShaderInfoLog(shader);
    }

    @Override
    public int glGetShaderi(int shader, int pname) {
        return GL33C.glGetShaderi(shader, pname);
    }

    @Override
    public String glGetString(int name) {
        return GL33C.glGetString(name);
    }

    @Override
    public String glGetStringi(int name, int index) {
        return GL33C.glGetStringi(name, index);
    }

    @Override
    public int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        return GL33C.glGetUniformBlockIndex(program, uniformBlockName);
    }

    @Override
    public int glGetUniformLocation(int program, CharSequence name) {
        return GL33C.glGetUniformLocation(program, name);
    }

    @Override
    public boolean glIsFramebuffer(int framebuffer) {
        return GL33C.glIsFramebuffer(framebuffer);
    }

    @Override
    public boolean glIsTexture(int texture) {
        return GL33C.glIsTexture(texture);
    }

    @Override
    public void glLinkProgram(int program) {
        GL33C.glLinkProgram(program);
    }

    @Override
    public void glPixelStorei(int pname, int param) {
        GL33C.glPixelStorei(pname, param);
    }

    @Override
    public void glPolygonOffset(float factor, float units) {
        GL33C.glPolygonOffset(factor, units);
    }

    @Override
    public void glReadBuffer(int src) {
        GL33C.glReadBuffer(src);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        GL33C.glReadPixels(x, y, width, height, format, type, pixels);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        GL33C.glReadPixels(x, y, width, height, format, type, pixels);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        GL33C.glReadPixels(x, y, width, height, format, type, pixels);
    }

    @Override
    public void glRenderbufferStorage(int target, int internalformat, int width, int height) {
        GL33C.glRenderbufferStorage(target, internalformat, width, height);
    }

    @Override
    public void glRenderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) {
        GL33C.glRenderbufferStorageMultisample(target, samples, internalformat, width, height);
    }

    @Override
    public void glSamplerParameteri(int sampler, int pname, int param) {
        GL33C.glSamplerParameteri(sampler, pname, param);
    }

    @Override
    public void glScissor(int x, int y, int width, int height) {
        GL33C.glScissor(x, y, width, height);
    }

    @Override
    public void glShaderSource(int shader, CharSequence string) {
        GL33C.glShaderSource(shader, string);
    }

    @Override
    public void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        GL33C.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
    }

    @Override
    public void glTexParameteri(int target, int pname, int param) {
        GL33C.glTexParameteri(target, pname, param);
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        GL33C.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }

    @Override
    public void glUniform1f(int location, float v0) {
        GL33C.glUniform1f(location, v0);
    }

    @Override
    public void glUniform1i(int location, int v0) {
        GL33C.glUniform1i(location, v0);
    }

    @Override
    public void glUniform2f(int location, float v0, float v1) {
        GL33C.glUniform2f(location, v0, v1);
    }

    @Override
    public void glUniform2i(int location, int v0, int v1) {
        GL33C.glUniform2i(location, v0, v1);
    }

    @Override
    public void glUniform3f(int location, float v0, float v1, float v2) {
        GL33C.glUniform3f(location, v0, v1, v2);
    }

    @Override
    public void glUniform3fv(int location, float[] value) {
        GL33C.glUniform3fv(location, value);
    }

    @Override
    public void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        GL33C.glUniform4f(location, v0, v1, v2, v3);
    }

    @Override
    public void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        GL33C.glUniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
    }

    @Override
    public void glUniformMatrix3fv(int location, boolean transpose, float[] value) {
        GL33C.glUniformMatrix3fv(location, transpose, value);
    }

    @Override
    public void glUniformMatrix4fv(int location, boolean transpose, float[] value) {
        GL33C.glUniformMatrix4fv(location, transpose, value);
    }

    @Override
    public void glUseProgram(int program) {
        GL33C.glUseProgram(program);
    }

    @Override
    public void glVertexAttrib2f(int index, float x, float y) {
        GL33C.glVertexAttrib2f(index, x, y);
    }

    @Override
    public void glVertexAttrib4f(int index, float x, float y, float z, float w) {
        GL33C.glVertexAttrib4f(index, x, y, z, w);
    }

    @Override
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        GL33C.glVertexAttribPointer(index, size, type, normalized, stride, pointer);
    }

    @Override
    public void glViewport(int x, int y, int width, int height) {
        GL33C.glViewport(x, y, width, height);
    }

    @Override
    public void nglTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels) {
        GL33C.nglTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
    }
}
