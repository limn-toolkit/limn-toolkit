package limn.render3d.scene;

import limn.math.Transform3D;
import limn.render3d.GpuMesh;
import limn.render3d.Material;

/** A {@link Node} that draws a {@link GpuMesh} with a {@link Material}. */
public final class MeshNode extends Node {

    private final GpuMesh mesh;
    private Material material;

    /** A node drawing one mesh with one material. */
    public MeshNode(GpuMesh mesh, Material material) {
        this.mesh = mesh;
        this.material = material;
    }

    /** Covariant override so {@code new MeshNode(...).transform(...)} stays a {@code MeshNode}. */
    @Override
    public MeshNode transform(Transform3D transform) {
        super.transform(transform);
        return this;
    }

    /** The geometry this node draws. */
    public GpuMesh mesh() {
        return mesh;
    }

    /** The material it draws with. */
    public Material material() {
        return material;
    }

    /** Swaps the material, keeping the geometry. */
    public MeshNode material(Material material) {
        this.material = material;
        return this;
    }
}
