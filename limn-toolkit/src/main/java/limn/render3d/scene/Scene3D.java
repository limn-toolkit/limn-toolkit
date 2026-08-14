package limn.render3d.scene;

import limn.math.Aabb;
import limn.math.Frustum;
import limn.math.Mat4;
import limn.math.Vec3;
import limn.math.Vec4;
import limn.render3d.Camera;
import limn.render3d.Environment;
import limn.render3d.IrradianceSh;
import limn.render3d.Light;
import limn.render3d.Material;
import limn.render3d.RenderPass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * A retained 3D scene: a {@link Node} tree plus scene-wide ambient/exposure and an
 * optional background. {@link #render} traverses the tree, composes world
 * transforms, adds every {@link LightNode}'s (transformed) light, frustum-culls
 * {@link MeshNode}s against the camera and issues their draws into the imperative
 * {@link RenderPass}, so it layers on top of the 2D composite pipeline unchanged.
 * A declarative scene renders identically to the equivalent imperative draws.
 */
public final class Scene3D {

    private final Node root = new Node().name("root");
    /** GPU resources this scene owns regardless of tree reachability (see {@link #owns}). */
    private final List<Object> owned = new ArrayList<>();
    private Vec3 ambient = new Vec3(0.03f, 0.03f, 0.03f);
    private float exposure = 1f;
    private Vec4 background; // null → caller clears (or leaves prior contents)
    private boolean castShadows;
    private Environment environment;
    private IrradianceSh irradiance; // baked from environment (cached)

    /** The root node; everything drawn hangs off it. */
    public Node root() {
        return root;
    }

    /** Uniform light applied everywhere, in linear scene-referred units. */
    public Scene3D ambient(Vec3 color) {
        this.ambient = color;
        return this;
    }

    /** Scales scene light before the display transform; {@code 1} leaves it as authored. */
    public Scene3D exposure(float exposure) {
        this.exposure = exposure;
        return this;
    }

    /** Colour the target is cleared to; a zero w leaves the viewport transparent. */
    public Scene3D background(Vec4 color) {
        this.background = color;
        return this;
    }

    /** Enables directional shadow mapping, fitted automatically to the visible bounds. */
    public Scene3D castShadows(boolean on) {
        this.castShadows = on;
        return this;
    }

    /** Sets the image-based lighting environment (baking its irradiance once); {@code null} disables. */
    public Scene3D environment(Environment environment) {
        this.environment = environment;
        this.irradiance = environment != null ? IrradianceSh.bake(environment) : null;
        return this;
    }

    /**
     * Registers a GPU resource as owned by this scene, so {@link #dispose()}
     * releases it even when no node references it (a texture whose material was
     * never assigned to a mesh, a mesh removed from the tree before disposal).
     * Builders that upload resources for a scene (e.g. {@code GltfModel.toScene3D})
     * register every upload here; tree-reachable duplicates are released once.
     */
    public Scene3D owns(limn.render3d.GpuMesh mesh) {
        owned.add(mesh);
        return this;
    }

    /** See {@link #owns(limn.render3d.GpuMesh)}. */
    public Scene3D owns(limn.render3d.GpuTexture texture) {
        owned.add(texture);
        return this;
    }

    /**
     * Releases every GPU resource this scene holds (each distinct
     * {@link limn.render3d.GpuMesh} and PBR base-color
     * {@link limn.render3d.GpuTexture} reachable from the node tree, plus every
     * resource registered via {@link #owns}) and empties the node tree. Call it
     * once the scene will no longer be drawn; like every GPU release it must run
     * on the UI thread with the owning GL context current: inside a render frame,
     * or deferred via {@code Scene.disposeLater(Runnable)} (which is what
     * {@code Viewport3D.onDispose} does). Calling it twice is a no-op. Do not
     * dispose a scene whose meshes/textures are shared with another scene that
     * is still drawn.
     */
    public void dispose() {
        Set<Object> disposed = Collections.newSetFromMap(new IdentityHashMap<>());
        disposeNode(root, disposed);
        for (int i = 0; i < owned.size(); i++) {
            Object resource = owned.get(i);
            if (!disposed.add(resource)) {
                continue;
            }
            if (resource instanceof limn.render3d.GpuMesh mesh) {
                mesh.dispose();
            } else if (resource instanceof limn.render3d.GpuTexture texture) {
                texture.dispose();
            }
        }
        owned.clear();
        root.children().clear();
    }

    private static void disposeNode(Node node, Set<Object> disposed) {
        if (node instanceof MeshNode mesh) {
            if (disposed.add(mesh.mesh())) {
                mesh.mesh().dispose();
            }
            if (mesh.material() instanceof Material.Pbr pbr) {
                if (pbr.baseColorTexture() != null && disposed.add(pbr.baseColorTexture())) {
                    pbr.baseColorTexture().dispose();
                }
                if (pbr.normalMap() != null && disposed.add(pbr.normalMap().texture())) {
                    pbr.normalMap().texture().dispose();
                }
            }
        }
        for (Node child : node.children()) {
            disposeNode(child, disposed);
        }
    }

    /** A mesh to draw, paired with its composed world transform. */
    public record MeshInstance(MeshNode node, Mat4 worldMatrix) {
    }

    // Per-frame scratch, reused across render() calls (single-threaded UI use).
    private final List<MeshInstance> meshScratch = new ArrayList<>();
    private final List<Light> lightScratch = new ArrayList<>();

    /** Mesh instances that survive frustum culling for {@code camera} at {@code aspect}. */
    public List<MeshInstance> visibleMeshes(Camera camera, float aspect) {
        root.updateWorld(Mat4.identity(), false);
        Frustum frustum = Frustum.fromViewProjection(camera.viewProjection(aspect));
        List<MeshInstance> all = new ArrayList<>();
        collectAll(root, all, new ArrayList<>());
        List<MeshInstance> out = new ArrayList<>();
        for (MeshInstance instance : all) {
            if (frustum.intersects(worldBounds(instance))) {
                out.add(instance);
            }
        }
        return out;
    }

    /** Every light in the graph, transformed into world space. */
    public List<Light> lights() {
        root.updateWorld(Mat4.identity(), false);
        List<Light> out = new ArrayList<>();
        collectLights(root, out);
        return out;
    }

    /**
     * World-space bounds of every mesh in the graph, or {@link Aabb#EMPTY} when it holds
     * none. Walks the whole graph and refreshes the cached world matrices, so it is a
     * setup-time call rather than a per-frame one: frame a camera with it once, or after
     * the content changes.
     */
    public Aabb bounds() {
        root.updateWorld(Mat4.identity(), false);
        List<MeshInstance> all = new ArrayList<>();
        collectAll(root, all, new ArrayList<>());
        Aabb box = Aabb.EMPTY;
        for (int i = 0; i < all.size(); i++) {
            box = box.union(worldBounds(all.get(i)));
        }
        return box;
    }

    /** Total mesh nodes in the graph (for culling diagnostics/tests). */
    public int meshCount() {
        return count(root);
    }

    /**
     * Emits every mesh node's world-space AABB (exactly the boxes frustum
     * culling tests) into {@code out}. Call from a render callback alongside
     * {@link #render}, before {@code out.flush(pass)}.
     */
    public void debugBounds(limn.render3d.DebugDraw out, Vec4 color) {
        root.updateWorld(Mat4.identity(), false);
        emitBounds(root, out, color);
    }

    private static void emitBounds(Node node, limn.render3d.DebugDraw out, Vec4 color) {
        if (node instanceof MeshNode mesh) {
            out.aabb(mesh.mesh().bounds().transformedBy(node.worldMatrix()), color);
        }
        List<Node> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            emitBounds(children.get(i), out, color);
        }
    }

    /**
     * Draws the whole graph into {@code pass}. {@code aspect} is the viewport's
     * width/height, which the camera needs and does not own.
     */
    public void render(RenderPass pass, Camera camera, float aspect) {
        // One traversal: recompose dirty world matrices, then collect every mesh
        // and light into the reused scratch lists; culling happens at draw time
        // so camera-culled meshes can still be routed to the shadow pass.
        root.updateWorld(Mat4.identity(), false);
        Frustum frustum = Frustum.fromViewProjection(camera.viewProjection(aspect));
        meshScratch.clear();
        lightScratch.clear();
        collectAll(root, meshScratch, lightScratch);
        List<Light> lights = lightScratch;
        if (background != null) {
            pass.clear(background.x(), background.y(), background.z(), background.w());
        }
        pass.ambient(ambient).exposure(exposure);
        if (environment != null) {
            pass.environment(environment, irradiance);
        }
        if (castShadows) {
            fitShadow(pass, lights, meshScratch);
        }
        for (int i = 0; i < lights.size(); i++) {
            pass.addLight(lights.get(i));
        }
        for (int i = 0; i < meshScratch.size(); i++) {
            MeshInstance instance = meshScratch.get(i);
            if (frustum.intersects(worldBounds(instance))) {
                pass.draw(instance.node().mesh(), instance.node().material(), instance.worldMatrix());
            } else if (castShadows) {
                // Off-screen but its shadow may fall across visible receivers.
                pass.drawShadowOnly(instance.node().mesh(), instance.worldMatrix());
            }
        }
    }

    /**
     * Aims the shadow map along the first directional light, fitted to the
     * <em>whole scene's</em> bounds, camera-independent on purpose: orbiting
     * doesn't re-fit the volume (so an unchanged shadow map can be reused), and
     * off-screen casters stay inside it instead of popping out of the shadow.
     */
    private static void fitShadow(RenderPass pass, List<Light> lights, List<MeshInstance> instances) {
        Light.Directional key = null;
        for (int i = 0; i < lights.size(); i++) {
            if (lights.get(i) instanceof Light.Directional directional) {
                key = directional;
                break;
            }
        }
        if (key == null || instances.isEmpty()) {
            return;
        }
        Aabb bounds = Aabb.EMPTY;
        for (int i = 0; i < instances.size(); i++) {
            bounds = bounds.union(worldBounds(instances.get(i)));
        }
        if (bounds.isEmpty()) {
            return;
        }
        float radius = bounds.extent().length() * 0.5f + 0.01f;
        pass.shadow(key.direction(), bounds.center(), radius);
    }

    private static Aabb worldBounds(MeshInstance instance) {
        return instance.node().mesh().bounds().transformedBy(instance.worldMatrix());
    }

    /** Reads the cached world matrices ({@code Node.updateWorld} must have run). */
    private static void collectAll(Node node, List<MeshInstance> meshes, List<Light> lights) {
        if (node instanceof MeshNode mesh) {
            meshes.add(new MeshInstance(mesh, node.worldMatrix()));
        } else if (node instanceof LightNode light) {
            lights.add(transformLight(light.light(), node.worldMatrix()));
        }
        List<Node> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            collectAll(children.get(i), meshes, lights);
        }
    }

    private static void collectLights(Node node, List<Light> out) {
        if (node instanceof LightNode light) {
            out.add(transformLight(light.light(), node.worldMatrix()));
        }
        List<Node> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            collectLights(children.get(i), out);
        }
    }

    private static int count(Node node) {
        int n = node instanceof MeshNode ? 1 : 0;
        for (Node child : node.children()) {
            n += count(child);
        }
        return n;
    }

    private static Light transformLight(Light light, Mat4 world) {
        if (light instanceof Light.Directional d) {
            return new Light.Directional(world.transformDirection(d.direction()).normalize(),
                    d.color(), d.intensity());
        }
        if (light instanceof Light.Point p) {
            return new Light.Point(world.transformPoint(p.position()), p.color(), p.intensity(), p.range());
        }
        if (light instanceof Light.Spot s) {
            return new Light.Spot(world.transformPoint(s.position()),
                    world.transformDirection(s.direction()).normalize(),
                    s.color(), s.intensity(), s.range(), s.innerAngleRadians(), s.outerAngleRadians());
        }
        return light;
    }
}
