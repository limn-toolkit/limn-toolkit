package limn.render3d.scene;

import limn.math.Mat4;
import limn.math.Transform3D;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the retained {@link Scene3D} graph: a local {@link Transform3D} plus
 * children. Concrete payloads are {@link MeshNode} and {@link LightNode}; a plain
 * {@code Node} is a transform group. World transforms are composed top-down during
 * traversal ({@code world = parentWorld · local}) and <b>cached</b>: only subtrees
 * whose transform changed since the last frame recompute, so a static scene does
 * zero matrix math per frame.
 */
public class Node {

    private Transform3D transform = Transform3D.IDENTITY;
    private String name = "";
    private final List<Node> children = new ArrayList<>();
    // World-matrix cache: transform()/add() set the dirty bit; Scene3D's
    // per-frame traversal recomposes only dirty branches.
    private Mat4 localMatrix; // lazy: transform.toMatrix()
    private Mat4 worldMatrix = Mat4.identity();
    private boolean dirty = true;

    /** This node's transform, relative to its parent. */
    public Transform3D transform() {
        return transform;
    }

    /** Sets the transform relative to the parent, moving the whole subtree with it. */
    public Node transform(Transform3D transform) {
        this.transform = transform;
        this.localMatrix = null;
        this.dirty = true;
        return this;
    }

    /** An optional label, for debugging and for finding a node in a loaded model. */
    public String name() {
        return name;
    }

    /** Labels this node; purely informational. */
    public Node name(String name) {
        this.name = name;
        return this;
    }

    /**
     * The live child list. Prefer {@link #add} for attaching: it marks the child
     * dirty so its cached world matrix is recomposed under the new parent;
     * moving an already-composed node in via this raw list keeps a stale world
     * until its transform next changes.
     */
    public List<Node> children() {
        return children;
    }

    /** Adds a child and returns it (for fluent building). */
    public <N extends Node> N add(N child) {
        children.add(child);
        ((Node) child).dirty = true; // its cached world derives from the previous parent
        return child;
    }

    Mat4 localMatrix() {
        if (localMatrix == null) {
            localMatrix = transform.toMatrix();
        }
        return localMatrix;
    }

    /** The world matrix composed by the last {@link #updateWorld} pass. */
    Mat4 worldMatrix() {
        return worldMatrix;
    }

    /** Recomposes this subtree's world matrices; clean branches are skipped. */
    void updateWorld(Mat4 parentWorld, boolean parentChanged) {
        boolean changed = parentChanged || dirty;
        if (changed) {
            worldMatrix = parentWorld.multiply(localMatrix());
            dirty = false;
        }
        for (int i = 0; i < children.size(); i++) {
            children.get(i).updateWorld(worldMatrix, changed);
        }
    }
}
