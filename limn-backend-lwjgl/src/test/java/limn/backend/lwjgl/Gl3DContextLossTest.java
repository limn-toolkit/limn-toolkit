package limn.backend.lwjgl;

import limn.concurrent.UiRuntime;
import limn.graphics.ScenePixels;
import limn.render3d.ColorSpace;
import limn.render3d.GpuMesh;
import limn.render3d.GpuTexture;
import limn.render3d.MeshData;
import limn.render3d.MeshUsage;
import limn.render3d.Sampler;
import limn.render3d.TextureData;
import limn.render3d.VertexAttribute;
import limn.testing.HeadlessUi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application's 3D objects after their context is gone (ADR 082): each stays the handle the
 * application holds and is made again, from what it keeps, in the new context.
 */
class Gl3DContextLossTest {

    private static final int SIZE = 8;

    private GlCanvas canvas;
    private FontStore fonts;
    private HeadlessUi ui;
    private UiRuntime runtime;
    private long second;

    @BeforeEach
    void openContext() {
        HeadlessGl.assumeWindow();
        HeadlessGl.assumeDesktop("the second context is made with the desktop profile's hints, and "
                + "glGetTexImage and glGetBufferSubData are desktop calls");
        ui = new HeadlessUi();
        runtime = ui.runtime();
        fonts = new FontStore();
        canvas = new GlCanvas(fonts);
    }

    @AfterEach
    void closeContext() {
        if (canvas != null) {
            canvas.dispose();
        }
        if (second != 0) {
            GLFW.glfwMakeContextCurrent(0);
            GLFW.glfwDestroyWindow(second);
        }
        GLFW.glfwMakeContextCurrent(HeadlessGl.window());
        Gl.api().setCapabilities(HeadlessGl.capabilities());
        if (fonts != null) {
            fonts.close();
        }
        if (runtime != null) {
            ui.close();
        }
    }

    private void loseContext() {
        second = GlContextLossTest.makeSecondContextForTests();
        canvas.contextLost();
    }

    @Test
    void aRenderTargetIsMadeAgainAtItsSize() {
        GlRenderTarget target = (GlRenderTarget) canvas.gl3d().createTarget(SIZE, SIZE, 1);
        loseContext();

        Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, target.renderFramebuffer());
        Gl.glClearColor(0.5f, 0.25f, 0f, 1f);
        Gl.glClear(GL33C.GL_COLOR_BUFFER_BIT);
        Gl.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
        ScenePixels scene = target.readSceneReferred();

        assertEquals(GL33C.GL_NO_ERROR, Gl.glGetError());
        assertEquals(SIZE, scene.width());
        assertEquals(0.5f, scene.channel(3, 3, 0), 1e-2f, "the new target takes what is drawn into it");
        target.dispose();
    }

    @Test
    void aTextureIsUploadedAgainFromThePixelsItKept() {
        byte[] pixels = new byte[SIZE * SIZE * 4];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i * 7);
        }
        byte[] original = pixels.clone();
        GpuTexture handle = canvas.gl3d().upload(TextureData.rgba(SIZE, SIZE, pixels, ColorSpace.LINEAR),
                Sampler.pixelated());
        java.util.Arrays.fill(pixels, (byte) 0); // what the caller does with its array is not the texture's
        loseContext();

        int id = ((GlTexture) handle).id();
        assertTrue(Gl.glIsTexture(id), "a texture of the new context");
        ByteBuffer read = MemoryUtil.memAlloc(original.length);
        try {
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, id);
            Gl.glPixelStorei(GL33C.GL_PACK_ALIGNMENT, 1);
            GL33C.glGetTexImage(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, read);
            Gl.glBindTexture(GL33C.GL_TEXTURE_2D, 0);
            byte[] back = new byte[original.length];
            read.get(back);
            assertArrayEquals(original, back, "the pixels uploaded at first, not the caller's array now");
        } finally {
            MemoryUtil.memFree(read);
        }
        handle.dispose();
    }

    @Test
    void aMeshIsUploadedAgainWithItsIndices() {
        int[] indices = {0, 1, 2, 2, 1, 3};
        MeshData data = new MeshData()
                .put(VertexAttribute.POSITION, new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0})
                .indices(indices);
        GpuMesh handle = canvas.gl3d().upload(data, MeshUsage.STATIC);
        loseContext();

        int vao = ((GlMesh) handle).vertexArray();
        assertNotEquals(0, vao);
        Gl.glBindVertexArray(vao);
        IntBuffer read = MemoryUtil.memAllocInt(indices.length);
        try {
            GL33C.glGetBufferSubData(GL33C.GL_ELEMENT_ARRAY_BUFFER, 0, read);
            int[] back = new int[indices.length];
            read.get(back);
            assertArrayEquals(indices, back, "the index buffer of the new context holds the mesh's indices");
        } finally {
            Gl.glBindVertexArray(0);
            MemoryUtil.memFree(read);
        }
        assertEquals(GL33C.GL_NO_ERROR, Gl.glGetError());
        handle.dispose();
    }
}
