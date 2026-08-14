package limn.render3d.scene;

import limn.render3d.Light;

/**
 * A {@link Node} carrying a {@link Light}. The node's world transform is applied
 * to the light (position as a point, direction as a direction) during traversal,
 * so lights can be parented and moved like any other node.
 */
public final class LightNode extends Node {

    private final Light light;

    /** A node contributing one light, positioned by its transform. */
    public LightNode(Light light) {
        this.light = light;
    }

    /** The light this node contributes. */
    public Light light() {
        return light;
    }
}
