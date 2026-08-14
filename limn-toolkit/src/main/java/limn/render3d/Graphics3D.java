package limn.render3d;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Facade + install-at-startup SPI for the 3D backend, the same inversion pattern
 * as {@code Images}/{@code Sounds}: a neutral entry point here, the GL
 * implementation installed by the backend on startup. All calls must happen on
 * the UI thread <em>inside a frame</em> (the owning window's GL context must be
 * current), because they create/render GPU resources.
 *
 * <p>Nothing here has an asynchronous form, and nothing here can: the GL context is current on one
 * thread inside one frame, so an upload moved to a worker would have no context to upload into. The
 * work that <em>can</em> leave the frame is whatever produces the {@link MeshData} and
 * {@link TextureData} (reading the file, decoding the image, building the vertices), and that is
 * where an application should put a background job. By the time a call reaches this class, the
 * remaining cost is the driver's.
 *
 * <p>For this milestone the provider also ships one built-in demo scene (a
 * spinning, depth-tested cube) via {@link #renderDemoScene}. Later phases add the
 * real mesh/material/pass API on top of the same {@link RenderTarget} seam.
 */
public final class Graphics3D {

    /** Backend-supplied factory + renderer (installed at startup). */
    public interface Provider {
        RenderTarget createTarget(int widthPx, int heightPx, int samples);

        GpuMesh upload(MeshData mesh);

        /**
         * Uploads with an explicit usage. Default: ignores the hint and uploads
         * statically, so a provider written before dynamic meshes existed still
         * compiles and still returns a usable (if not rewritable) handle.
         */
        default GpuMesh upload(MeshData mesh, MeshUsage usage) {
            return upload(mesh);
        }

        GpuTexture uploadTexture(TextureData texture, Sampler sampler);

        void render(RenderTarget target, Camera camera, Consumer<RenderPass> body);

        void renderDemoScene(RenderTarget target, double timeSeconds);
    }

    private static volatile Provider provider;

    private Graphics3D() {
    }

    /** Installs the backend's 3D provider. Called once at startup, before any viewport renders. */
    public static void install(Provider newProvider) {
        provider = Objects.requireNonNull(newProvider, "provider");
    }

    /** Removes the provider if it is still {@code expected}, so a late teardown cannot clear a newer one. */
    public static void uninstall(Provider expected) {
        if (provider == expected) {
            provider = null;
        }
    }

    /** Whether a backend provider is installed (false when running headless). */
    public static boolean isAvailable() {
        return provider != null;
    }

    /** Creates a target with {@code samples}× MSAA (clamped to what the GPU supports). */
    public static RenderTarget createTarget(int widthPx, int heightPx, int samples) {
        return active().createTarget(Math.max(1, widthPx), Math.max(1, heightPx), Math.max(1, samples));
    }

    /** Uploads geometry to the GPU (call once; reuse the handle across frames). */
    public static GpuMesh upload(MeshData mesh) {
        return upload(mesh, MeshUsage.STATIC);
    }

    /**
     * Uploads geometry that will be rewritten through {@link GpuMesh#update}
     * ({@link MeshUsage#DYNAMIC}) or statically, as {@link #upload(MeshData)}.
     */
    public static GpuMesh upload(MeshData mesh, MeshUsage usage) {
        return active().upload(mesh, usage);
    }

    /** Uploads a texture with the given sampler state (call once; reuse the handle). */
    public static GpuTexture uploadTexture(TextureData texture, Sampler sampler) {
        return active().uploadTexture(texture, sampler);
    }

    /**
     * Renders into {@code target} with {@code camera}: binds the target (depth on),
     * then invokes {@code body} with a {@link RenderPass} to clear and draw. Resolves
     * MSAA and restores GL state afterwards. Must run inside a frame (context current).
     */
    public static void render(RenderTarget target, Camera camera, Consumer<RenderPass> body) {
        active().render(target, camera, body);
    }

    /** Draws a built-in scene, so a backend can be verified without any application content. */
    public static void renderDemoScene(RenderTarget target, double timeSeconds) {
        active().renderDemoScene(target, timeSeconds);
    }

    private static Provider active() {
        Provider p = provider;
        if (p == null) {
            throw new IllegalStateException("no Graphics3D provider. Is the backend started?");
        }
        return p;
    }
}
