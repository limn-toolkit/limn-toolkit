package limn.render3d.gltf;

import limn.concurrent.Progress;
import limn.concurrent.Ui;
import limn.concurrent.Work;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.math.Transform3D;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.ColorSpace;
import limn.render3d.Graphics3D;
import limn.render3d.GpuMesh;
import limn.render3d.GpuTexture;
import limn.render3d.Material;
import limn.render3d.MeshData;
import limn.render3d.Sampler;
import limn.render3d.TextureData;
import limn.render3d.scene.MeshNode;
import limn.render3d.scene.Node;
import limn.render3d.scene.Scene3D;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * A parsed glTF 2.0 asset as neutral CPU data: a node hierarchy, meshes (as
 * {@link MeshData}), metallic-roughness materials, and encoded texture images.
 * {@link GltfLoader} produces it without touching the GPU or decoding images, so
 * it is fully headless-testable. {@link #toScene3D()} then uploads everything
 * (via {@link Graphics3D}/{@link Images}) and builds a retained {@link Scene3D};
 * call it inside a render frame, with a backend running.
 *
 * <p>Getting to a scene is two costs, and only one of them needs the frame. Decoding the embedded
 * PNG/JPEG textures is plain CPU work on bytes this object already holds (tens of milliseconds for
 * a single 2048&sup2; base-colour map), while the GPU uploads need the render thread with the
 * context current. {@link #decodeTextures()} is the first half on its own, {@link
 * #decodeTexturesAsync()} runs it on the worker pool, and {@link #toScene3D(DecodedTextures)}
 * uploads what it produced. Use those three when the model is loaded while a window is live;
 * {@link #toScene3D()} does both halves in the frame and is for setup code that can afford it.
 */
public final class GltfModel {

    /** One drawable range of a mesh with its material index ({@code -1} = default). */
    public record Primitive(MeshData mesh, int material) {
    }

    public record MeshDef(List<Primitive> primitives, String name) {
    }

    /** Metallic-roughness material; texture fields index {@link #textures()} ({@code -1} = none). */
    public record MaterialDef(Vec4 baseColor, float metallic, float roughness, Vec3 emissive,
                              int baseColorTexture, String name) {
    }

    public record TextureDef(int image, int sampler) {
    }

    /** A glTF sampler, kept as the file's GL enums; {@code -1} = unspecified. */
    public record SamplerDef(int magFilter, int minFilter, int wrapS, int wrapT) {
    }

    /** An encoded image (PNG/JPEG bytes), left encoded until {@link #decodeTextures()}. */
    public record ImageDef(byte[] bytes, String mimeType) {
    }

    public record NodeDef(Transform3D transform, int mesh, int[] children, String name) {
    }

    private final List<MeshDef> meshes;
    private final List<MaterialDef> materials;
    private final List<TextureDef> textures;
    private final List<SamplerDef> samplers;
    private final List<ImageDef> images;
    private final List<NodeDef> nodes;
    private final int[] rootNodes;

    GltfModel(List<MeshDef> meshes, List<MaterialDef> materials, List<TextureDef> textures,
              List<SamplerDef> samplers, List<ImageDef> images, List<NodeDef> nodes, int[] rootNodes) {
        this.meshes = meshes;
        this.materials = materials;
        this.textures = textures;
        this.samplers = samplers;
        this.images = images;
        this.nodes = nodes;
        this.rootNodes = rootNodes;
    }

    /** Meshes, indexed as the glTF file numbers them. */
    public List<MeshDef> meshes() {
        return meshes;
    }

    /** Materials, indexed as the glTF file numbers them. */
    public List<MaterialDef> materials() {
        return materials;
    }

    /** Textures, each pairing an image with a sampler. */
    public List<TextureDef> textures() {
        return textures;
    }

    /** Samplers: filtering and wrap modes referenced by textures. */
    public List<SamplerDef> samplers() {
        return samplers;
    }

    /** The images the textures sample, still in their file encoding. */
    public List<ImageDef> images() {
        return images;
    }

    /** Every node in the file, flat; the hierarchy is in each node's child indices. */
    public List<NodeDef> nodes() {
        return nodes;
    }

    /** Indices into {@link #nodes()} of the scene's roots, where traversal starts. */
    public int[] rootNodes() {
        return rootNodes;
    }

    /** Total primitives across all meshes (a mesh may have several). */
    public int primitiveCount() {
        int n = 0;
        for (MeshDef m : meshes) {
            n += m.primitives().size();
        }
        return n;
    }

    /**
     * The model's texture images decoded to pixels, ready to upload: the CPU half of
     * {@link #toScene3D()}, separated so it can be done off the UI thread. Produced by
     * {@link #decodeTextures()} or {@link #decodeTexturesAsync()} and consumed by
     * {@link #toScene3D(DecodedTextures)}.
     *
     * <p>Belongs to the model that produced it: the images are indexed by that model's texture
     * numbering, and handing one to a different model is rejected rather than silently mismatched.
     * It holds no GPU or native resource, so an instance nobody uses needs no disposal.
     */
    public static final class DecodedTextures {

        private final GltfModel model;
        /** Indexed by texture, {@code null} where no material references that texture. */
        private final Image[] byTexture;

        private DecodedTextures(GltfModel model, Image[] byTexture) {
            this.model = model;
            this.byTexture = byTexture;
        }

        /** @return how many textures were decoded (the ones some material references) */
        public int count() {
            int n = 0;
            for (Image image : byTexture) {
                if (image != null) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * Decodes every texture image some material references, on the calling thread, and returns them
     * for {@link #toScene3D(DecodedTextures)} to upload. Pure CPU work on bytes this model already
     * holds (no GPU, no frame, no UI thread), so it is safe on a worker, which is where a model
     * loaded while a window is live should do it.
     *
     * <p>Textures no material references are skipped, exactly as {@link #toScene3D()} skips them:
     * glTF files commonly carry normal/ORM/emissive maps this renderer does not sample yet, and
     * decoding one costs the same as decoding a map that gets drawn. An image two textures share is
     * decoded once and shared between them.
     *
     * @return the decoded images, tied to this model
     * @throws IllegalStateException if no backend is running (there is no image decoder installed)
     */
    public DecodedTextures decodeTextures() {
        return decodeTextures(null);
    }

    /**
     * Decodes the referenced texture images on the {@code Ui} worker pool, delivering them to
     * {@code onSuccess} on the UI thread: the asynchronous form of {@link #decodeTextures()}.
     *
     * <pre>{@code
     * decoding = model.decodeTexturesAsync()
     *                 .onSuccess(decoded -> this.pending = decoded)  // upload in the next frame
     *                 .deliverIf(viewport::isShowing)
     *                 .start();
     * }</pre>
     *
     * <p>Returned <b>unstarted</b>: register handlers, then call {@code start()}. A job cancelled
     * while it runs stops between images. No disposer is registered and none is needed: the result
     * is decoded pixels and nothing else.
     *
     * <p>What it produces still has to be uploaded, and that half cannot leave the render thread:
     * pass it to {@link #toScene3D(DecodedTextures)} from inside a frame.
     *
     * @throws IllegalStateException if no backend is running (there is no worker pool to use)
     */
    public Work<DecodedTextures> decodeTexturesAsync() {
        return Ui.work(progress -> decodeTextures(progress));
    }

    /**
     * {@code progress} may be null for the synchronous path, which never cancels and reports
     * nothing. Package-private rather than private so a test can drive the cancellation and
     * progress contract with a {@link Progress} of its own.
     */
    DecodedTextures decodeTextures(Progress progress) {
        boolean[] referenced = referencedTextures();
        int total = 0;
        for (boolean used : referenced) {
            total += used ? 1 : 0;
        }
        Image[] byTexture = new Image[textures.size()];
        Image[] byImage = new Image[images.size()]; // one decode per image, however many textures use it
        int done = 0;
        for (int i = 0; i < textures.size(); i++) {
            if (!referenced[i]) {
                continue;
            }
            if (progress != null && progress.isCancelled()) {
                throw new CancellationException("glTF texture decode cancelled");
            }
            int source = textures.get(i).image();
            if (byImage[source] == null) {
                byImage[source] = Images.decode(images.get(source).bytes());
            }
            byTexture[i] = byImage[source];
            done++;
            if (progress != null) {
                progress.report((double) done / total);
            }
        }
        if (progress != null) {
            progress.report(1); // a model with no textures still finishes its bar
        }
        return new DecodedTextures(this, byTexture);
    }

    /**
     * Which textures some material actually uses. Uploading the rest would put them on the GPU
     * unreachable by any draw AND by {@link Scene3D#dispose()}'s tree traversal.
     */
    private boolean[] referencedTextures() {
        boolean[] referenced = new boolean[textures.size()];
        for (int i = 0; i < materials.size(); i++) {
            int texture = materials.get(i).baseColorTexture();
            if (texture >= 0 && texture < referenced.length) {
                referenced[texture] = true;
            }
        }
        return referenced;
    }

    /**
     * Decodes the textures and uploads everything, then instantiates the node hierarchy as a
     * retained {@link Scene3D}: {@link #decodeTextures()} followed by
     * {@link #toScene3D(DecodedTextures)}, for setup code that can afford both in one frame.
     * Lights and camera are the caller's to set on the scene.
     *
     * <p>Must run inside a render frame with a backend installed, and the texture decode it does
     * first runs there too: a model with large base-colour maps stalls that frame for tens of
     * milliseconds per map. Split the two when that matters.
     *
     * <p>The returned scene owns the uploaded GPU resources: release them with
     * {@link Scene3D#dispose()} once it is no longer drawn (each call to this method uploads a
     * fresh copy).
     */
    public Scene3D toScene3D() {
        return toScene3D(decodeTextures());
    }

    /**
     * Uploads {@code decoded} and the meshes, and instantiates the node hierarchy as a retained
     * {@link Scene3D}: the GPU half of {@link #toScene3D()}, for a caller that has already
     * decoded the textures elsewhere.
     *
     * <p>Must run inside a render frame with a backend installed; there is no asynchronous form,
     * because every call it makes needs the GL context current on the render thread. What can be
     * moved off the frame is the decode, and {@link #decodeTexturesAsync()} is where that lives.
     *
     * <p>The returned scene owns the uploaded GPU resources: release them with
     * {@link Scene3D#dispose()} once it is no longer drawn (each call uploads a fresh copy, so
     * one {@code decoded} may build several scenes and each disposes independently).
     *
     * @param decoded textures from {@link #decodeTextures()} or {@link #decodeTexturesAsync()} on
     *                <em>this</em> model
     * @throws IllegalArgumentException if {@code decoded} came from a different model, whose
     *                                  texture numbering would silently mismatch this one's
     */
    public Scene3D toScene3D(DecodedTextures decoded) {
        Objects.requireNonNull(decoded, "decoded");
        if (decoded.model != this) {
            throw new IllegalArgumentException(
                    "these DecodedTextures belong to a different GltfModel");
        }
        Scene3D scene = new Scene3D();
        GpuTexture[] gpuTextures = new GpuTexture[textures.size()];
        for (int i = 0; i < textures.size(); i++) {
            Image image = decoded.byTexture[i];
            if (image == null) {
                continue;
            }
            TextureData data = new TextureData(image.width(), image.height(), image.pixels(), ColorSpace.SRGB);
            gpuTextures[i] = Graphics3D.uploadTexture(data, samplerFor(textures.get(i).sampler()));
            scene.owns(gpuTextures[i]);
        }

        Material[] mats = new Material[materials.size()];
        for (int i = 0; i < materials.size(); i++) {
            MaterialDef md = materials.get(i);
            GpuTexture tex = md.baseColorTexture() >= 0 ? gpuTextures[md.baseColorTexture()] : null;
            // glTF's baseColorFactor is LINEAR per spec; Material.Pbr.baseColor is
            // authored in sRGB (the shader linearizes it). Encode exactly with the
            // inverse of the shader's piecewise srgbToLinear so factors round-trip.
            // Opaque: glTF's alphaMode is not read yet, so every loaded material
            // composites as OPAQUE regardless of what the file asked for.
            mats[i] = new Material.Pbr(srgbFromLinear(md.baseColor()),
                    md.metallic(), md.roughness(), md.emissive(), tex, null, null);
        }

        GpuMesh[][] gpuMeshes = new GpuMesh[meshes.size()][];
        for (int i = 0; i < meshes.size(); i++) {
            List<Primitive> prims = meshes.get(i).primitives();
            gpuMeshes[i] = new GpuMesh[prims.size()];
            for (int p = 0; p < prims.size(); p++) {
                gpuMeshes[i][p] = Graphics3D.upload(prims.get(p).mesh());
                scene.owns(gpuMeshes[i][p]);
            }
        }

        for (int root : rootNodes) {
            scene.root().add(buildNode(root, mats, gpuMeshes));
        }
        return scene;
    }

    private Node buildNode(int index, Material[] mats, GpuMesh[][] gpuMeshes) {
        NodeDef def = nodes.get(index);
        Node node = new Node().name(def.name()).transform(def.transform());
        if (def.mesh() >= 0) {
            List<Primitive> prims = meshes.get(def.mesh()).primitives();
            for (int p = 0; p < prims.size(); p++) {
                Material material = materialFor(prims.get(p).material(), mats);
                node.add(new MeshNode(gpuMeshes[def.mesh()][p], material));
            }
        }
        for (int child : def.children()) {
            node.add(buildNode(child, mats, gpuMeshes));
        }
        return node;
    }

    private static Material materialFor(int index, Material[] mats) {
        return index >= 0 && index < mats.length ? mats[index] : Material.Pbr.of(0.8f, 0.8f, 0.8f);
    }

    /** Maps the file's sampler (GL enums) onto {@link Sampler}; no sampler → glTF defaults. */
    private Sampler samplerFor(int index) {
        if (index < 0 || index >= samplers.size()) {
            return Sampler.smooth(); // glTF default: repeat wrap, auto filtering
        }
        SamplerDef def = samplers.get(index);
        // minFilter: 9728/9729 = unmipped NEAREST/LINEAR; 9984-9987 = mip variants
        // (9984/9986 have a NEAREST base); unspecified → trilinear.
        boolean mipmaps = def.minFilter() < 0 || def.minFilter() >= 9984;
        Sampler.Filter min = def.minFilter() == 9728 || def.minFilter() == 9984
                || def.minFilter() == 9986 ? Sampler.Filter.NEAREST : Sampler.Filter.LINEAR;
        Sampler.Filter mag = def.magFilter() == 9728 ? Sampler.Filter.NEAREST : Sampler.Filter.LINEAR;
        return new Sampler(min, mag, wrap(def.wrapS()), wrap(def.wrapT()), mipmaps);
    }

    private static Sampler.Wrap wrap(int glEnum) {
        return switch (glEnum) {
            case 33071 -> Sampler.Wrap.CLAMP_TO_EDGE;
            case 33648 -> Sampler.Wrap.MIRRORED_REPEAT;
            default -> Sampler.Wrap.REPEAT; // 10497
        };
    }

    private static Vec4 srgbFromLinear(Vec4 linear) {
        return new Vec4(linearToSrgb(linear.x()), linearToSrgb(linear.y()),
                linearToSrgb(linear.z()), linear.w());
    }

    /** Exact inverse of the shader's piecewise {@code srgbToLinear}. */
    private static float linearToSrgb(float linear) {
        float clamped = Math.max(0f, Math.min(1f, linear));
        return clamped <= 0.0031308f
                ? clamped * 12.92f
                : 1.055f * (float) Math.pow(clamped, 1.0 / 2.4) - 0.055f;
    }
}
