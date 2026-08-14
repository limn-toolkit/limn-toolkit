package limn.backend.lwjgl;

import limn.render3d.Camera;
import limn.render3d.Graphics3D;
import limn.render3d.GpuMesh;
import limn.render3d.GpuTexture;
import limn.render3d.MeshData;
import limn.render3d.MeshUsage;
import limn.render3d.RenderPass;
import limn.render3d.RenderTarget;
import limn.render3d.Sampler;
import limn.render3d.TextureData;

import java.util.function.Consumer;

/**
 * Backend {@link Graphics3D.Provider}: a stateless router to the currently
 * rendering window's per-context {@link Gl3DContext}. 3D calls only happen inside
 * a widget's paint (a frame), where exactly one {@link GlCanvas} is
 * {@linkplain GlCanvas#current() current}, so there is no ambiguity.
 */
final class LwjglGraphics3D implements Graphics3D.Provider {

    @Override
    public RenderTarget createTarget(int widthPx, int heightPx, int samples) {
        return context().createTarget(widthPx, heightPx, samples);
    }

    @Override
    public GpuMesh upload(MeshData mesh) {
        return context().upload(mesh, MeshUsage.STATIC);
    }

    @Override
    public GpuMesh upload(MeshData mesh, MeshUsage usage) {
        return context().upload(mesh, usage);
    }

    @Override
    public GpuTexture uploadTexture(TextureData texture, Sampler sampler) {
        return context().upload(texture, sampler);
    }

    @Override
    public void render(RenderTarget target, Camera camera, Consumer<RenderPass> body) {
        // The pass needs the window's device scale: bloom's radius is authored
        // in points and converted to texels by the backend (ADR 005 §1).
        GlCanvas canvas = currentCanvas();
        canvas.gl3d().render(target, camera, body, canvas.contentScale());
    }

    @Override
    public void renderDemoScene(RenderTarget target, double timeSeconds) {
        context().renderDemoScene(target, timeSeconds);
    }

    private static Gl3DContext context() {
        return currentCanvas().gl3d();
    }

    private static GlCanvas currentCanvas() {
        GlCanvas canvas = GlCanvas.current();
        if (canvas == null) {
            throw new IllegalStateException("3D calls must happen during a frame (no current GL canvas)");
        }
        return canvas;
    }
}
