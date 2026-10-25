package limn.render3d.gltf;

import limn.io.MalformedContentException;

import limn.concurrent.Progress;
import limn.concurrent.Ui;
import limn.concurrent.Work;
import limn.io.Resources;
import limn.math.Quat;
import limn.math.Transform3D;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.MeshData;
import limn.render3d.VertexAttribute;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Loads a glTF 2.0 asset into a neutral {@link GltfModel}: no AWT, no GPU. Handles
 * both {@code .gltf} (JSON, with base64 {@code data:} buffers) and {@code .glb}
 * (binary container). Reads the node hierarchy, mesh primitives
 * (POSITION/NORMAL/TEXCOORD_0 + indices), metallic-roughness materials and encoded
 * texture images. Buffers and images must be embedded, as {@code data:} URIs or the GLB
 * binary chunk: a reference to an external file is refused. Skinning and animation are
 * not read; nodes are placed by their static transforms. Images are kept encoded here;
 * decoding them is the separate, off-thread-able step {@link GltfModel#decodeTextures()}.
 *
 * <p>Every entry point reads and parses on the thread that calls it. The {@code ...Async} ones put
 * that on the {@code Ui} worker pool and deliver on the UI thread, which is what an application
 * loading a model behind a live window wants; the synchronous ones stay for setup code and tests.
 *
 * <p><b>One load creates at most 2<sup>26</sup> array elements</b> by default, a quarter of a
 * gigabyte of floats, and a model that needs more is refused with an
 * {@link MalformedContentException}; {@link #setElementBudget} moves that ceiling for the whole
 * process. What the default holds depends on whether the model carries its normals. A mesh that does costs six floats a
 * vertex and one index a triangle corner, so an indexed one of the usual shape, with about half as
 * many vertices as triangles, fits up to some eleven million triangles. A mesh without normals is
 * given flat ones, which takes a vertex of its own for every corner: three floats of position,
 * three of normal, two more with texture coordinates and a new index, on top of the arrays read
 * from the file. The same indexed mesh then fits up to about 2.6 million triangles, and one with
 * no index list, as a scan converted from STL often is, about two million. Exporting the normals
 * with the model is what lets a larger one load.
 */
public final class GltfLoader {

    private static final int GLB_MAGIC = 0x46546C67;    // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;   // "JSON"
    private static final int CHUNK_BIN = 0x004E4942;    // "BIN\0"

    /**
     * The most array elements one load creates unless {@link #setElementBudget} says otherwise,
     * over every accessor, index list, converted strip and generated normal together:
     * 2<sup>26</sup>, a quarter of a gigabyte of floats. Each accessor is bounded by the bytes
     * behind it, but primitives may name the same accessor again and again: a 168 KB file whose
     * two thousand strips all reused one 64 KB buffer asked for more than two gigabytes. What
     * that holds, in triangles, is in the class documentation.
     */
    public static final long DEFAULT_ELEMENT_BUDGET = 1L << 26;

    private static volatile long elementBudget = DEFAULT_ELEMENT_BUDGET;

    /**
     * Moves the ceiling on the array elements one load may create, for every load in the process
     * from the next one on; a load already running keeps the ceiling it started with. Raise it for
     * an application whose own models are large and trusted: 2<sup>27</sup> takes a mesh without
     * normals of about five million triangles, at twice the memory a hostile file can make a load
     * ask for before it is refused. Lower it where models come from strangers.
     *
     * @param elements the most elements one load creates, at least 1
     * @throws IllegalArgumentException if {@code elements} is less than 1
     */
    public static void setElementBudget(long elements) {
        if (elements < 1) {
            throw new IllegalArgumentException("an element budget is at least 1, got " + elements);
        }
        elementBudget = elements;
    }

    /**
     * The ceiling on the array elements one load may create: {@link #DEFAULT_ELEMENT_BUDGET} until
     * {@link #setElementBudget} moves it.
     *
     * @return the current budget, in elements
     */
    public static long elementBudget() {
        return elementBudget;
    }

    /** What the synchronous entry points hand the parser: never cancelled, nobody listening. */
    private static final Progress NO_PROGRESS = new Progress() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void report(double fraction) {
        }
    };

    // The parse is reported as four spans, sized by where the time goes on an ordinary model
    // rather than evenly: the accessor copies dominate, so the mesh loop owns most of the bar.
    private static final double READ_DONE = 0.10;
    private static final double JSON_DONE = 0.25;
    private static final double BUFFERS_DONE = 0.35;
    private static final double MESHES_DONE = 0.90;

    private GltfLoader() {
    }

    /**
     * Reads {@code file} whole and parses it ({@code .gltf} or {@code .glb}), on the calling
     * thread. This is the toolkit's largest blocking read: the file, a JSON parse of the document,
     * a base64 decode of every embedded buffer, and a de-interleaving copy per accessor add up to
     * hundreds of milliseconds for an ordinary model. That is a dropped second on the UI thread.
     * {@link #loadAsync(Path)} is the same work on the worker pool.
     */
    public static GltfModel load(Path file) {
        return load(Resources.bytes(file, "glTF"));
    }

    /**
     * Reads a classpath resource whole and parses it ({@code .gltf} or {@code .glb}), on the
     * calling thread: the shape for a model shipped inside the application jar. See
     * {@link #load(Path)} for the cost and {@link #fromResourceAsync} for the same work on the
     * worker pool.
     *
     * @param resource an absolute resource path, e.g. {@code "/app/models/robot.glb"}
     * @throws IllegalStateException if no such resource exists
     */
    public static GltfModel fromResource(String resource) {
        return load(Resources.bytes(GltfLoader.class, resource, "glTF"));
    }

    /**
     * Parses a model already in memory, on the calling thread. It is still a JSON parse, a base64
     * decode of every embedded buffer and a copy per accessor, so see {@link #load(Path)} for the
     * cost and {@link #loadAsync(byte[])} for the same work on the worker pool.
     *
     * @throws MalformedContentException if the document is malformed, whatever is wrong with it: text
     *         that is not JSON or ends early, a member of the wrong type or missing, an index past
     *         the end of the list it names, and one that declares more elements or bytes than the
     *         buffer behind them holds, which is refused before the array for them is allocated, so
     *         a small file cannot ask for an enormous heap. It is the only exception a malformed
     *         document is refused with
     */
    public static GltfModel load(byte[] data) {
        return load(data, NO_PROGRESS);
    }

    // -------------------------------------------------- background loading

    /**
     * Reads and parses {@code file} on the {@code Ui} worker pool, delivering the model to
     * {@code onSuccess} on the UI thread: the asynchronous form of {@link #load(Path)}.
     *
     * <pre>{@code
     * loading = GltfLoader.loadAsync(file)
     *                     .onProgress(fraction -> bar.setProgress((float) fraction))
     *                     .onSuccess(model -> show(model))
     *                     .deliverIf(view::isShowing)
     *                     .start();
     * }</pre>
     *
     * <p>Returned <b>unstarted</b>: register handlers, then call {@code start()}. Nothing is read
     * until then, and a job cancelled while it runs stops between meshes rather than parsing the
     * rest. No disposer is registered and none is needed: a {@link GltfModel} is plain arrays, so
     * an undelivered one is collected like any other object.
     *
     * <p>Progress runs 0 to 1 across the read, the JSON parse, the buffers and the meshes, weighted
     * by where the time actually goes rather than by member count, so it is monotonic but not
     * linear in time.
     *
     * <p>The model it produces is CPU data only. Turning it into something drawable still costs a
     * texture decode and a GPU upload; see {@link GltfModel#decodeTexturesAsync()} for the half of
     * that which also belongs here.
     *
     * @throws IllegalStateException if no backend is running (there is no worker pool to use)
     */
    public static Work<GltfModel> loadAsync(Path file) {
        Objects.requireNonNull(file, "file");
        return Ui.work(progress -> {
            byte[] data = Resources.bytes(file, "glTF");
            progress.report(READ_DONE);
            return load(data, progress);
        });
    }

    /**
     * Parses bytes already in memory on the {@code Ui} worker pool, delivering the model on the UI
     * thread: the asynchronous form of {@link #load(byte[])}. Returned unstarted, cancellable and
     * reporting progress exactly as {@link #loadAsync(Path)} does, minus the read.
     *
     * <p>The array is read by the worker thread and must not be modified until the job finishes.
     *
     * @throws IllegalStateException if no backend is running (there is no worker pool to use)
     */
    public static Work<GltfModel> loadAsync(byte[] data) {
        Objects.requireNonNull(data, "data");
        return Ui.work(progress -> load(data, progress));
    }

    /**
     * Reads and parses a classpath resource on the {@code Ui} worker pool, delivering the model on
     * the UI thread: the asynchronous form of {@link #fromResource(String)}, and what an
     * application shipping models in its jar should call. Returned unstarted, cancellable and
     * reporting progress exactly as {@link #loadAsync(Path)} does.
     *
     * @param resource an absolute resource path, e.g. {@code "/app/models/robot.glb"}
     * @throws IllegalStateException if no backend is running (there is no worker pool to use)
     */
    public static Work<GltfModel> fromResourceAsync(String resource) {
        Objects.requireNonNull(resource, "resource");
        return Ui.work(progress -> {
            byte[] data = Resources.bytes(GltfLoader.class, resource, "glTF");
            progress.report(READ_DONE);
            return load(data, progress);
        });
    }

    /**
     * Package-private rather than private so a test can drive the cancellation and progress
     * contract with a {@link Progress} of its own; the asynchronous entry points above are the only
     * production callers that pass a real one.
     */
    static GltfModel load(byte[] data, Progress progress) {
        return load(data, progress, elementBudget);
    }

    /**
     * With a budget of array elements other than {@link #elementBudget()}: package-private so a
     * test can prove the refusal without building half a gigabyte of arrays first.
     */
    static GltfModel load(byte[] data, Progress progress, long elementBudget) {
        String json;
        byte[] glbBin = null;
        if (isGlb(data)) {
            if (data.length < 12) {
                // The magic alone makes it a GLB, and the three header words are read before any
                // chunk is, so a file cut short inside them underflowed the buffer rather than
                // being refused.
                throw new MalformedContentException("glTF", "malformed GLB: " + data.length
                        + " bytes, shorter than the 12-byte header");
            }
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            bb.getInt(); // magic
            bb.getInt(); // version
            bb.getInt(); // total length
            String parsedJson = null;
            byte[] parsedBin = null;
            while (bb.remaining() >= 8) {
                int len = bb.getInt();
                int type = bb.getInt();
                if (len < 0 || len > bb.remaining()) {
                    throw new MalformedContentException("glTF", "malformed GLB: chunk declares "
                            + (len & 0xFFFFFFFFL) + " bytes but " + bb.remaining() + " remain");
                }
                byte[] chunk = new byte[len];
                bb.get(chunk);
                if (type == CHUNK_JSON) {
                    parsedJson = new String(chunk, StandardCharsets.UTF_8);
                } else if (type == CHUNK_BIN) {
                    parsedBin = chunk;
                }
            }
            if (parsedJson == null) {
                throw new MalformedContentException("glTF", "malformed GLB: no JSON chunk");
            }
            json = parsedJson;
            glbBin = parsedBin;
        } else {
            json = new String(data, StandardCharsets.UTF_8);
        }
        Map<String, Object> document = obj(Json.parse(json), "document");
        progress.report(JSON_DONE);
        return new Parser(document, glbBin, elementBudget).parse(progress);
    }

    private static boolean isGlb(byte[] d) {
        return d.length >= 4 && (d[0] & 0xFF | (d[1] & 0xFF) << 8 | (d[2] & 0xFF) << 16 | (d[3] & 0xFF) << 24) == GLB_MAGIC;
    }

    /** One parse pass over the JSON object graph, resolving binary data as it goes. */
    private static final class Parser {


        /** What this load may create: the process's budget when it started, or a test's own. */
        private final long budget;

        /** What this load has created so far, against {@link #budget}. */
        private long spent;

        private void spend(long elements, String what) {
            spent += elements;
            if (spent > budget) {
                throw new MalformedContentException("glTF", "glTF " + what + " takes the model past "
                        + budget + " elements, the most one load creates"
                        + " (GltfLoader.setElementBudget moves it)");
            }
        }

        private final Map<String, Object> root;
        private final List<byte[]> buffers = new ArrayList<>();
        private final List<Object> bufferViews;
        private final List<Object> accessors;

        Parser(Map<String, Object> root, byte[] glbBin, long budget) {
            this.budget = budget;
            this.root = root;
            this.bufferViews = arr(root, "bufferViews");
            this.accessors = arr(root, "accessors");
            List<Object> declared = arr(root, "buffers");
            for (int i = 0; i < declared.size(); i++) {
                Map<String, Object> bm = obj(declared.get(i), "buffer " + i);
                String uri = strOr(bm, "uri", null);
                if (uri == null) {
                    buffers.add(glbBin); // the GLB BIN chunk is buffer 0
                } else if (uri.startsWith("data:")) {
                    buffers.add(dataUriBytes(uri));
                } else {
                    throw new MalformedContentException("glTF", "external glTF buffer not supported: " + uri);
                }
            }
        }

        GltfModel parse(Progress progress) {
            progress.report(BUFFERS_DONE);
            List<Object> meshNodes = arr(root, "meshes");
            List<GltfModel.MeshDef> meshes = new ArrayList<>();
            for (int i = 0; i < meshNodes.size(); i++) {
                // Between meshes is the only place a cancel can be honoured cheaply: one mesh is a
                // handful of array copies, and a superseded model must not burn a worker to the end.
                if (progress.isCancelled()) {
                    throw new CancellationException("glTF parse cancelled");
                }
                meshes.add(parseMesh(obj(meshNodes.get(i), "mesh " + i)));
                progress.report(BUFFERS_DONE
                        + (MESHES_DONE - BUFFERS_DONE) * meshes.size() / meshNodes.size());
            }
            List<GltfModel.MaterialDef> materials = new ArrayList<>();
            List<Object> materialNodes = arr(root, "materials");
            for (int i = 0; i < materialNodes.size(); i++) {
                materials.add(parseMaterial(obj(materialNodes.get(i), "material " + i)));
            }
            List<GltfModel.ImageDef> images = new ArrayList<>();
            List<Object> imageNodes = arr(root, "images");
            for (int i = 0; i < imageNodes.size(); i++) {
                images.add(parseImage(obj(imageNodes.get(i), "image " + i)));
            }
            List<GltfModel.TextureDef> textures = new ArrayList<>();
            List<Object> textureNodes = arr(root, "textures");
            for (int i = 0; i < textureNodes.size(); i++) {
                Map<String, Object> tm = obj(textureNodes.get(i), "texture " + i);
                textures.add(new GltfModel.TextureDef(intOr(tm, "source", -1), intOr(tm, "sampler", -1)));
            }
            List<GltfModel.SamplerDef> samplers = new ArrayList<>();
            List<Object> samplerNodes = arr(root, "samplers");
            for (int i = 0; i < samplerNodes.size(); i++) {
                Map<String, Object> sm = obj(samplerNodes.get(i), "sampler " + i);
                samplers.add(new GltfModel.SamplerDef(intOr(sm, "magFilter", -1),
                        intOr(sm, "minFilter", -1), intOr(sm, "wrapS", 10497), intOr(sm, "wrapT", 10497)));
            }
            List<GltfModel.NodeDef> nodes = new ArrayList<>();
            List<Object> nodeList = arr(root, "nodes");
            for (int i = 0; i < nodeList.size(); i++) {
                nodes.add(parseNode(obj(nodeList.get(i), "node " + i)));
            }
            int[] roots = sceneRoots(nodes.size());
            checkForest(nodes, roots);
            checkReferences(textures, images.size(), materials, nodes, meshes.size());
            GltfModel model = new GltfModel(meshes, materials, textures, samplers, images, nodes,
                    roots);
            progress.report(1);
            return model;
        }

        /**
         * Adds an attribute's array to a primitive. The mesh refuses one that disagrees with the
         * arrays it already has, as a caller's mistake; here it is the file's.
         */
        private static void put(MeshData data, VertexAttribute attribute, float[] values) {
            try {
                data.put(attribute, values);
            } catch (IllegalArgumentException disagrees) {
                throw new MalformedContentException("glTF", "glTF primitive: " + disagrees.getMessage(), disagrees);
            }
        }

        private GltfModel.MeshDef parseMesh(Map<String, Object> mesh) {
            List<GltfModel.Primitive> prims = new ArrayList<>();
            List<Object> primitives = arr(mesh, "primitives");
            for (int i = 0; i < primitives.size(); i++) {
                Map<String, Object> prim = obj(primitives.get(i), "primitive " + i);
                // Required by the specification, and the one member a primitive cannot do without:
                // it names every array the primitive is drawn from.
                Map<String, Object> attrs = object(prim, "attributes");
                MeshData data = new MeshData();
                if (attrs.containsKey("POSITION")) {
                    put(data, VertexAttribute.POSITION,
                            readFloats(intOf(attrs, "POSITION"), 3, "POSITION"));
                }
                if (attrs.containsKey("NORMAL")) {
                    put(data, VertexAttribute.NORMAL,
                            readFloats(intOf(attrs, "NORMAL"), 3, "NORMAL"));
                }
                if (attrs.containsKey("TEXCOORD_0")) {
                    put(data, VertexAttribute.UV0,
                            readFloats(intOf(attrs, "TEXCOORD_0"), 2, "TEXCOORD_0"));
                }
                if (!prim.containsKey("indices")) {
                    spend(data.vertexCount(), "sequential indices");
                }
                int[] indices = prim.containsKey("indices")
                        ? readIndices(intOf(prim, "indices"), data.vertexCount())
                        : sequential(data.vertexCount());
                // glTF primitive modes: 4 = TRIANGLES (native); strips/fans are
                // converted to lists at load; points/lines have no pipeline here.
                int mode = intOr(prim, "mode", 4);
                if (mode == 5 || mode == 6) {
                    spend(3L * Math.max(0, indices.length - 2), "a strip or fan made a list");
                }
                data.indices(switch (mode) {
                    // A list that ends mid-triangle: a draw call skips the unfinished one, and so
                    // does the loader, since the flat normals below read three corners at a time.
                    case 4 -> indices.length % 3 == 0 ? indices
                            : java.util.Arrays.copyOf(indices, indices.length - indices.length % 3);
                    case 5 -> stripToTriangles(indices);
                    case 6 -> fanToTriangles(indices);
                    default -> throw new MalformedContentException("glTF", 
                            "unsupported glTF primitive mode " + mode + " (points/lines/loops)");
                });
                if (data.has(VertexAttribute.POSITION) && !data.has(VertexAttribute.NORMAL)) {
                    spend(flatNormalElements(data.indices().length, data.has(VertexAttribute.UV0)),
                            "flat normals");
                    data = withFlatNormals(data);
                }
                prims.add(new GltfModel.Primitive(data, intOr(prim, "material", -1)));
            }
            return new GltfModel.MeshDef(prims, strOr(mesh, "name", ""));
        }

        /**
         * The elements {@link #withFlatNormals} creates for a primitive of {@code indices} corners:
         * a position, a normal and, with texture coordinates, a UV for every corner, and the index
         * list that draws them in order. Charged against the budget before the arrays exist, so it
         * has to be exactly what is allocated there; a count that left out the index list let every
         * flat-shaded mesh past the budget by one element a corner.
         */
        static long flatNormalElements(int indices, boolean uv) {
            return (long) indices * (3 + 3 + (uv ? 2 : 0) + 1);
        }

        /**
         * glTF spec: a primitive without NORMAL uses flat shading; passing it
         * through as-is makes the PBR shader normalize a zero vector and render
         * the mesh black. Flat normals need per-face vertices, so the primitive
         * is de-indexed (each triangle gets its own three).
         */
        private static MeshData withFlatNormals(MeshData data) {
            float[] pos = data.get(VertexAttribute.POSITION);
            float[] uv = data.get(VertexAttribute.UV0);
            int[] idx = data.indices();
            float[] outPos = new float[idx.length * 3];
            float[] outNrm = new float[idx.length * 3];
            float[] outUv = uv != null ? new float[idx.length * 2] : null;
            for (int t = 0; t < idx.length; t += 3) {
                int a = idx[t] * 3;
                int b = idx[t + 1] * 3;
                int c = idx[t + 2] * 3;
                float e1x = pos[b] - pos[a];
                float e1y = pos[b + 1] - pos[a + 1];
                float e1z = pos[b + 2] - pos[a + 2];
                float e2x = pos[c] - pos[a];
                float e2y = pos[c + 1] - pos[a + 1];
                float e2z = pos[c + 2] - pos[a + 2];
                float nx = e1y * e2z - e1z * e2y;
                float ny = e1z * e2x - e1x * e2z;
                float nz = e1x * e2y - e1y * e2x;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len < 1e-12f) { // degenerate triangle: any unit vector shades
                    nx = 0;
                    ny = 0;
                    nz = 1;
                } else {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                }
                for (int corner = 0; corner < 3; corner++) {
                    int src = idx[t + corner];
                    int dst = t + corner;
                    outPos[dst * 3] = pos[src * 3];
                    outPos[dst * 3 + 1] = pos[src * 3 + 1];
                    outPos[dst * 3 + 2] = pos[src * 3 + 2];
                    outNrm[dst * 3] = nx;
                    outNrm[dst * 3 + 1] = ny;
                    outNrm[dst * 3 + 2] = nz;
                    if (outUv != null) {
                        outUv[dst * 2] = uv[src * 2];
                        outUv[dst * 2 + 1] = uv[src * 2 + 1];
                    }
                }
            }
            MeshData flat = new MeshData();
            flat.put(VertexAttribute.POSITION, outPos);
            flat.put(VertexAttribute.NORMAL, outNrm);
            if (outUv != null) {
                flat.put(VertexAttribute.UV0, outUv);
            }
            flat.indices(sequential(idx.length));
            return flat;
        }

        /** TRIANGLE_STRIP → list; odd triangles flip winding to keep them front-facing. */
        private static int[] stripToTriangles(int[] strip) {
            int triangles = Math.max(0, strip.length - 2);
            int[] out = new int[triangles * 3];
            for (int t = 0; t < triangles; t++) {
                boolean odd = (t & 1) == 1;
                out[t * 3] = strip[odd ? t + 1 : t];
                out[t * 3 + 1] = strip[odd ? t : t + 1];
                out[t * 3 + 2] = strip[t + 2];
            }
            return out;
        }

        /** TRIANGLE_FAN → list: (v0, v[i], v[i+1]). */
        private static int[] fanToTriangles(int[] fan) {
            int triangles = Math.max(0, fan.length - 2);
            int[] out = new int[triangles * 3];
            for (int t = 0; t < triangles; t++) {
                out[t * 3] = fan[0];
                out[t * 3 + 1] = fan[t + 1];
                out[t * 3 + 2] = fan[t + 2];
            }
            return out;
        }

        /**
         * Every index a texture or a material holds into another list names an entry the file has:
         * the model uses them as array indices when it decodes and uploads, where a bad one was an
         * ArrayIndexOutOfBoundsException long after the load had answered. A texture with no
         * source at all is allowed, as glTF allows it for an image an extension supplies: the model
         * treats it as no texture. A material's base-colour texture is optional too. A primitive's
         * material and a texture's sampler are already read defensively, falling back to a default.
         * A node's mesh is checked here as well: building the scene looks the mesh up by it, where
         * one the file does not have was an IndexOutOfBoundsException from a model that had loaded.
         */
        private static void checkReferences(List<GltfModel.TextureDef> textures, int imageCount,
                                            List<GltfModel.MaterialDef> materials,
                                            List<GltfModel.NodeDef> nodes, int meshCount) {
            for (int i = 0; i < nodes.size(); i++) {
                int mesh = nodes.get(i).mesh();
                if (mesh < -1 || mesh >= meshCount) {
                    throw new MalformedContentException("glTF", "glTF node " + i + " names mesh "
                            + mesh + ", but the file has " + meshCount);
                }
            }
            for (int i = 0; i < textures.size(); i++) {
                int source = textures.get(i).image();
                if (source < -1 || source >= imageCount) {
                    throw new MalformedContentException("glTF", "glTF texture " + i + " names image "
                            + source + ", but the file has " + imageCount);
                }
            }
            for (int i = 0; i < materials.size(); i++) {
                int texture = materials.get(i).baseColorTexture();
                if (texture < -1 || texture >= textures.size()) {
                    throw new MalformedContentException("glTF", "glTF material " + i + " names texture "
                            + texture + ", but the file has " + textures.size());
                }
            }
        }

        private GltfModel.MaterialDef parseMaterial(Map<String, Object> mat) {
            Map<String, Object> pbr = mat.containsKey("pbrMetallicRoughness")
                    ? object(mat, "pbrMetallicRoughness") : Map.of();
            Vec4 baseColor = vec4Or(pbr, "baseColorFactor", 1, 1, 1, 1);
            float metallic = floatOr(pbr, "metallicFactor", 1);
            float roughness = floatOr(pbr, "roughnessFactor", 1);
            Vec3 emissive = vec3Or(mat, "emissiveFactor", 0, 0, 0);
            int baseColorTexture = -1;
            if (pbr.containsKey("baseColorTexture")) {
                baseColorTexture = intOr(object(pbr, "baseColorTexture"), "index", -1);
            }
            return new GltfModel.MaterialDef(baseColor, metallic, roughness, emissive,
                    baseColorTexture, strOr(mat, "name", ""));
        }

        private GltfModel.ImageDef parseImage(Map<String, Object> image) {
            String uri = strOr(image, "uri", null);
            if (uri != null && uri.startsWith("data:")) {
                return new GltfModel.ImageDef(dataUriBytes(uri), dataUriMime(uri));
            }
            if (image.containsKey("bufferView")) {
                Map<String, Object> bv = bufferView(intOf(image, "bufferView"));
                byte[] buffer = buffer(intOr(bv, "buffer", 0));
                int offset = intOr(bv, "byteOffset", 0);
                int length = intOr(bv, "byteLength", 0);
                checkFits(length, offset, 1, 1, buffer.length, "image");
                byte[] bytes = new byte[length];
                System.arraycopy(buffer, offset, bytes, 0, length);
                return new GltfModel.ImageDef(bytes, strOr(image, "mimeType", "image/png"));
            }
            throw new MalformedContentException("glTF", "external glTF image not supported: " + uri);
        }

        private GltfModel.NodeDef parseNode(Map<String, Object> node) {
            Transform3D transform;
            float[] matrix = numbers(node, "matrix", 16);
            if (matrix != null) {
                transform = decompose(matrix);
            } else {
                transform = new Transform3D(
                        vec3Or(node, "translation", 0, 0, 0),
                        quatOr(node, "rotation"),
                        vec3Or(node, "scale", 1, 1, 1));
            }
            return new GltfModel.NodeDef(transform, intOr(node, "mesh", -1),
                    ints(node, "children"), strOr(node, "name", ""));
        }

        /** The deepest a node may sit below its scene's root; the scene is built and drawn by recursion. */
        static final int MAX_DEPTH = 1024;

        /**
         * The nodes form trees, as the spec requires: every child names a node, no node has two
         * parents, a scene's roots name nodes that have none, and no tree is deeper than
         * {@link #MAX_DEPTH}. The scene is built by walking children from the roots, so a node
         * named twice was built twice for each time its parent was: a 609-byte file of nodes each
         * naming the next one twice asked for 2<sup>28</sup> of them, and a node naming itself
         * recursed until the stack ran out.
         */
        private static void checkForest(List<GltfModel.NodeDef> nodes, int[] roots) {
            int count = nodes.size();
            int[] parent = new int[count];
            java.util.Arrays.fill(parent, -1);
            for (int i = 0; i < count; i++) {
                for (int child : nodes.get(i).children()) {
                    if (child < 0 || child >= count) {
                        throw new MalformedContentException("glTF", "glTF node " + i + " names child "
                                + child + ", and there are " + count + " nodes");
                    }
                    if (parent[child] >= 0 || child == i) {
                        throw new MalformedContentException("glTF", "glTF node " + child + " has more than"
                                + " one parent (" + (child == i ? "itself" : parent[child] + " and "
                                + i) + "): the nodes of a glTF form trees");
                    }
                    parent[child] = i;
                }
            }
            int[] depth = new int[count];
            int[] stack = new int[count];
            for (int root : roots) {
                if (root < 0 || root >= count || parent[root] >= 0) {
                    throw new MalformedContentException("glTF", "glTF scene root " + root
                            + " is not a node without a parent");
                }
                int top = 0;
                stack[top++] = root;
                depth[root] = 0;
                while (top > 0) {
                    int at = stack[--top];
                    for (int child : nodes.get(at).children()) {
                        depth[child] = depth[at] + 1;
                        if (depth[child] > MAX_DEPTH) {
                            throw new MalformedContentException("glTF", "glTF node " + child + " sits "
                                    + depth[child] + " levels deep, past the " + MAX_DEPTH
                                    + " a scene is built to");
                        }
                        stack[top++] = child;
                    }
                }
            }
        }

        private int[] sceneRoots(int nodeCount) {
            List<Object> scenes = arr(root, "scenes");
            if (scenes.isEmpty()) {
                // No scenes array: only nodes nobody references as a child are
                // roots; instantiating EVERY node would duplicate each child
                // subtree (once standalone, once under its parent).
                boolean[] isChild = new boolean[nodeCount];
                List<Object> nodes = arr(root, "nodes");
                for (int n = 0; n < nodes.size(); n++) {
                    for (int child : ints(obj(nodes.get(n), "node " + n), "children")) {
                        if (child >= 0 && child < nodeCount) {
                            isChild[child] = true;
                        }
                    }
                }
                int roots = 0;
                for (int i = 0; i < nodeCount; i++) {
                    if (!isChild[i]) {
                        roots++;
                    }
                }
                int[] all = new int[roots];
                for (int i = 0, out = 0; i < nodeCount; i++) {
                    if (!isChild[i]) {
                        all[out++] = i;
                    }
                }
                return all;
            }
            int index = intOr(root, "scene", 0);
            return ints(obj(at(scenes, index, "scene"), "scene " + index), "nodes");
        }

        // --------------------------------------------------------- accessors

        /**
         * Refuses a declared element count that the bytes behind it cannot hold, <b>before</b> the
         * array for it is allocated.
         *
         * <p>{@code load(byte[])} is a public entry point and the model may have come from
         * anywhere. A count is a handful of characters of JSON and the array it sizes is
         * {@code count} elements wide, so a two-hundred-byte file declaring a hundred million
         * elements asks the JVM for a gigabyte; the read that would have failed happens per
         * element, after the allocation, which is a heap the process may not survive rather than a
         * refusal. Bounding against the buffer that is already in memory bounds every declaration
         * by the size of the file instead of by what the file says about itself.
         *
         * <p>The arithmetic is in long deliberately: {@code count * stride} overflows int at
         * exactly the sizes this exists to refuse, and a wrapped negative would pass.
         *
         * @param element bytes touched at each step: the whole element, not one component
         * @param limit the first byte past what this data may touch, from {@link #limitOf}
         */
        private static void checkFits(int count, int base, int stride, int element,
                                      long limit, String what) {
            if (count < 0 || base < 0 || stride < 0) {
                throw new MalformedContentException("glTF", "glTF " + what + " declares a negative count, "
                        + "offset or stride (" + count + ", " + base + ", " + stride + ")");
            }
            if (count == 0) {
                return;
            }
            long needed = (long) base + (long) (count - 1) * stride + element;
            if (needed > limit) {
                throw new MalformedContentException("glTF", "glTF " + what + " declares " + count
                        + " elements, which need " + needed + " bytes of the " + limit
                        + " it has");
            }
        }

        /**
         * The first byte past what an accessor into this bufferView may touch: the view's own end,
         * or the buffer's where the view does not say. Bounding by the view as well as the buffer
         * is what stops an accessor whose count fits the file but not its own slice of it from
         * reading a neighbouring view's bytes as vertices.
         */
        private static long limitOf(Map<String, Object> bv, byte[] buffer) {
            int declared = intOr(bv, "byteLength", 0);
            if (declared <= 0) {
                return buffer.length;
            }
            return Math.min((long) intOr(bv, "byteOffset", 0) + declared, buffer.length);
        }

        /** The accessor's bufferView, rejecting the unsupported shapes descriptively. */
        private Map<String, Object> viewOf(Map<String, Object> acc, String what) {
            if (acc.containsKey("sparse")) {
                throw new MalformedContentException("glTF", 
                        "sparse accessors are not supported (" + what + ")");
            }
            if (acc.get("bufferView") == null) {
                throw new MalformedContentException("glTF", 
                        "accessor without a bufferView is not supported (" + what + ")");
            }
            return bufferView(intOf(acc, "bufferView"));
        }

        /** The accessor the file numbers {@code index}, refusing one it does not have. */
        private Map<String, Object> accessor(int index) {
            return obj(at(accessors, index, "accessor"), "accessor " + index);
        }

        /** The bufferView the file numbers {@code index}, refusing one it does not have. */
        private Map<String, Object> bufferView(int index) {
            return obj(at(bufferViews, index, "bufferView"), "bufferView " + index);
        }

        /**
         * The bytes of the buffer the file numbers {@code index}. A buffer without a uri stands for
         * a GLB's binary chunk, so one in a file that has no such chunk, a {@code .gltf} or a GLB
         * whose chunk is missing, is refused here, where it is first read, rather than being read
         * as a null array.
         */
        private byte[] buffer(int index) {
            byte[] bytes = at(buffers, index, "buffer");
            if (bytes == null) {
                throw new MalformedContentException("glTF", "glTF buffer " + index + " has no uri, and the "
                        + "file has no GLB binary chunk to stand in for it");
            }
            return bytes;
        }

        private float[] readFloats(int accessorIndex, int components, String what) {
            Map<String, Object> acc = accessor(accessorIndex);
            Map<String, Object> bv = viewOf(acc, what);
            byte[] buffer = buffer(intOr(bv, "buffer", 0));
            int base = intOr(bv, "byteOffset", 0) + intOr(acc, "byteOffset", 0);
            int componentType = intOr(acc, "componentType", 5126);
            boolean normalized = Boolean.TRUE.equals(acc.get("normalized"));
            int componentSize = switch (componentType) {
                case 5120, 5121 -> 1; // BYTE, UNSIGNED_BYTE
                case 5122, 5123 -> 2; // SHORT, UNSIGNED_SHORT
                case 5126 -> 4;       // FLOAT
                default -> throw new MalformedContentException("glTF", "unsupported componentType "
                        + componentType + " for " + what);
            };
            int stride = intOr(bv, "byteStride", 0);
            if (stride == 0) {
                stride = components * componentSize;
            } else if (stride < components * componentSize) {
                // The spec's own rule, and the one that bounds a count by the bytes: elements that
                // overlap let a short buffer declare as many as it has bytes.
                throw new MalformedContentException("glTF", "glTF " + what + " declares a byteStride of "
                        + stride + ", shorter than its " + components * componentSize + "-byte element");
            }
            int count = intOr(acc, "count", 0);
            checkFits(count, base, stride, components * componentSize, limitOf(bv, buffer), what);
            spend((long) count * components, what);
            ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
            float[] out = new float[count * components];
            for (int e = 0; e < count; e++) {
                int elem = base + e * stride;
                for (int c = 0; c < components; c++) {
                    int off = elem + c * componentSize;
                    out[e * components + c] = switch (componentType) {
                        case 5126 -> bb.getFloat(off);
                        case 5121 -> {
                            int raw = bb.get(off) & 0xFF;
                            yield normalized ? raw / 255f : raw;
                        }
                        case 5120 -> {
                            int raw = bb.get(off);
                            yield normalized ? Math.max(raw / 127f, -1f) : raw;
                        }
                        case 5123 -> {
                            int raw = bb.getShort(off) & 0xFFFF;
                            yield normalized ? raw / 65535f : raw;
                        }
                        case 5122 -> {
                            int raw = bb.getShort(off);
                            yield normalized ? Math.max(raw / 32767f, -1f) : raw;
                        }
                        default -> 0; // unreachable: componentSize already threw
                    };
                }
            }
            return out;
        }

        /**
         * The primitive's indices, each checked against {@code vertexCount}: the accessor is
         * bounded against its buffer, but the values in it are bounded by nothing else, and once
         * uploaded they drive {@code glDrawElements}, which reads the vertex buffer at whatever
         * index it is given. A primitive that carries normals goes to the device as-is.
         */
        private int[] readIndices(int accessorIndex, int vertexCount) {
            Map<String, Object> acc = accessor(accessorIndex);
            Map<String, Object> bv = viewOf(acc, "indices");
            byte[] buffer = buffer(intOr(bv, "buffer", 0));
            int base = intOr(bv, "byteOffset", 0) + intOr(acc, "byteOffset", 0);
            int componentType = intOr(acc, "componentType", 5123);
            int count = intOr(acc, "count", 0);
            int indexSize = switch (componentType) {
                case 5121 -> 1;
                case 5123 -> 2;
                case 5125 -> 4;
                default -> throw new MalformedContentException("glTF", 
                        "unsupported index componentType " + componentType);
            };
            checkFits(count, base, indexSize, indexSize, limitOf(bv, buffer), "indices");
            spend(count, "indices");
            ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
            int[] out = new int[count];
            for (int i = 0; i < count; i++) {
                int index = switch (componentType) {
                    case 5121 -> bb.get(base + i) & 0xFF;         // UNSIGNED_BYTE
                    case 5123 -> bb.getShort(base + i * 2) & 0xFFFF; // UNSIGNED_SHORT
                    case 5125 -> bb.getInt(base + i * 4);         // UNSIGNED_INT: above 2^31 reads negative
                    default -> 0; // unreachable: indexSize already threw
                };
                if (index < 0 || index >= vertexCount) {
                    throw new MalformedContentException("glTF", "index " + i + " of accessor " + accessorIndex
                            + " is " + (index < 0 ? index & 0xFFFFFFFFL : index)
                            + ", outside the primitive's " + vertexCount + " vertices");
                }
                out[i] = index;
            }
            return out;
        }

        private static int[] sequential(int n) {
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                out[i] = i;
            }
            return out;
        }
    }

    // ------------------------------------------------------------- data URIs

    private static byte[] dataUriBytes(String uri) {
        int comma = uri.indexOf(',');
        if (comma < 5) {
            throw new MalformedContentException("glTF", "malformed data URI (no comma separator): "
                    + uri.substring(0, Math.min(uri.length(), 40)));
        }
        String header = uri.substring(5, comma);
        String payload = uri.substring(comma + 1);
        if (!header.contains(";base64")) {
            throw new MalformedContentException("glTF", "only base64 data URIs supported: " + header);
        }
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException notBase64) {
            // Already the right exception, but saying only "Illegal base64 character 5f", with no
            // word that it was a data URI being read.
            throw new MalformedContentException("glTF", "malformed data URI (" + header + "): "
                    + notBase64.getMessage(), notBase64);
        }
    }

    private static String dataUriMime(String uri) {
        int end = uri.indexOf(';');
        int comma = uri.indexOf(',');
        int stop = end >= 0 && end < comma ? end : comma;
        return uri.substring(5, stop);
    }

    // --------------------------------------------------- matrix decomposition

    /** Decomposes a column-major TRS matrix (no shear) into a {@link Transform3D}. */
    private static Transform3D decompose(float[] m) {
        Vec3 translation = new Vec3(m[12], m[13], m[14]);
        Vec3 c0 = new Vec3(m[0], m[1], m[2]);
        Vec3 c1 = new Vec3(m[4], m[5], m[6]);
        Vec3 c2 = new Vec3(m[8], m[9], m[10]);
        float sx = c0.length();
        float sy = c1.length();
        float sz = c2.length();
        Vec3 n0 = sx > 1e-8f ? c0.div(sx) : Vec3.UNIT_X;
        Vec3 n1 = sy > 1e-8f ? c1.div(sy) : Vec3.UNIT_Y;
        Vec3 n2 = sz > 1e-8f ? c2.div(sz) : Vec3.UNIT_Z;
        return new Transform3D(translation, quatFromColumns(n0, n1, n2), new Vec3(sx, sy, sz));
    }

    private static Quat quatFromColumns(Vec3 c0, Vec3 c1, Vec3 c2) {
        // Rotation matrix element m(row, col) = column.component(row).
        float m00 = c0.x(), m10 = c0.y(), m20 = c0.z();
        float m01 = c1.x(), m11 = c1.y(), m21 = c1.z();
        float m02 = c2.x(), m12 = c2.y(), m22 = c2.z();
        float trace = m00 + m11 + m22;
        float x, y, z, w;
        if (trace > 0) {
            float s = 0.5f / (float) Math.sqrt(trace + 1f);
            w = 0.25f / s;
            x = (m21 - m12) * s;
            y = (m02 - m20) * s;
            z = (m10 - m01) * s;
        } else if (m00 > m11 && m00 > m22) {
            float s = 2f * (float) Math.sqrt(1f + m00 - m11 - m22);
            w = (m21 - m12) / s;
            x = 0.25f * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = 2f * (float) Math.sqrt(1f + m11 - m00 - m22);
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25f * s;
            z = (m12 + m21) / s;
        } else {
            float s = 2f * (float) Math.sqrt(1f + m22 - m00 - m11);
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25f * s;
        }
        return new Quat(x, y, z, w).normalize();
    }

    // ------------------------------------------------------------- JSON helpers
    //
    // Every value these read came out of a document that may have come from anywhere, so each one
    // checks the type it is about to take the value as, and refuses anything else with an
    // IllegalArgumentException naming the member and the type it had to be. They used to cast, and
    // a file with a string where a number belonged, an array where an object did, or a value
    // missing, escaped the loader as a ClassCastException or a NullPointerException, which an
    // application catching the documented refusal of a malformed file did not catch.

    /**
     * {@code value} as a JSON object, refusing anything else, a missing value included.
     *
     * @param what the part of the model it is, for the refusal: {@code "mesh 2"}, {@code "document"}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new MalformedContentException("glTF", "glTF " + what + " must be an object, not "
                + describe(value));
    }

    /** The object member {@code key} of {@code m}, which is required: absent is refused too. */
    private static Map<String, Object> object(Map<String, Object> m, String key) {
        return obj(m.get(key), "\"" + key + "\"");
    }

    /**
     * The array member {@code key} of {@code m}, empty where it is absent or null, which is what
     * every array the loader reads means when the file leaves it out.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> arr(Map<String, Object> m, String key) {
        Object value = m.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new MalformedContentException("glTF", "glTF \"" + key + "\" must be an array, not "
                + describe(value));
    }

    /**
     * Entry {@code index} of one of the file's top-level lists, for an index the file itself wrote
     * somewhere else, refusing one past either end of the list instead of letting the lookup throw.
     *
     * @param what what the list holds, for the refusal: {@code "accessor"}, {@code "bufferView"}
     */
    private static <T> T at(List<T> list, int index, String what) {
        if (index < 0 || index >= list.size()) {
            throw new MalformedContentException("glTF", "glTF names " + what + " " + index
                    + ", but the file has " + list.size());
        }
        return list.get(index);
    }

    /** The number member {@code key} of {@code m} as an int, which is required. */
    private static int intOf(Map<String, Object> m, String key) {
        return number(m.get(key), key).intValue();
    }

    private static int intOr(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        return v == null ? def : number(v, key).intValue();
    }

    private static float floatOr(Map<String, Object> m, String key, float def) {
        Object v = m.get(key);
        return v == null ? def : number(v, key).floatValue();
    }

    private static String strOr(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new MalformedContentException("glTF", "glTF \"" + key + "\" must be a string, not "
                + describe(v));
    }

    /** {@code value}, which the member {@code key} holds, as a number, refusing anything else. */
    private static Double number(Object value, String key) {
        if (value instanceof Double d) {
            return d;
        }
        throw new MalformedContentException("glTF", "glTF \"" + key + "\" must be a number, not "
                + describe(value));
    }

    private static Vec3 vec3Or(Map<String, Object> m, String key, float x, float y, float z) {
        float[] a = numbers(m, key, 3);
        return a == null ? new Vec3(x, y, z) : new Vec3(a[0], a[1], a[2]);
    }

    private static Vec4 vec4Or(Map<String, Object> m, String key, float x, float y, float z, float w) {
        float[] a = numbers(m, key, 4);
        return a == null ? new Vec4(x, y, z, w) : new Vec4(a[0], a[1], a[2], a[3]);
    }

    private static Quat quatOr(Map<String, Object> m, String key) {
        float[] a = numbers(m, key, 4);
        return a == null ? Quat.IDENTITY : new Quat(a[0], a[1], a[2], a[3]);
    }

    /**
     * The array member {@code key} of {@code m} as floats, or {@code null} where it is absent or
     * empty, which every caller reads as "use the default". An array of fewer than {@code needed}
     * numbers is refused, as is one holding anything but numbers; one of more keeps the first
     * {@code needed} and ignores the rest, as the loader always has.
     */
    private static float[] numbers(Map<String, Object> m, String key, int needed) {
        List<Object> a = arr(m, key);
        if (a.isEmpty()) {
            return null;
        }
        if (a.size() < needed) {
            throw new MalformedContentException("glTF", "glTF \"" + key + "\" must hold " + needed
                    + " numbers, not " + a.size());
        }
        float[] out = new float[needed];
        for (int i = 0; i < needed; i++) {
            out[i] = number(a.get(i), key).floatValue();
        }
        return out;
    }

    /** The array member {@code key} of {@code m} as ints, empty where it is absent. */
    private static int[] ints(Map<String, Object> m, String key) {
        List<Object> a = arr(m, key);
        int[] out = new int[a.size()];
        for (int i = 0; i < a.size(); i++) {
            out[i] = number(a.get(i), key).intValue();
        }
        return out;
    }

    /** What a JSON value is, for a refusal that says what was found where something else belonged. */
    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "a string";
        }
        if (value instanceof Double) {
            return "the number " + value;
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        return value instanceof List ? "an array" : "an object";
    }
}
