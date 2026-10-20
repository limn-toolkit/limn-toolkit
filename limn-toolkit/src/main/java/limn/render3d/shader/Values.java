package limn.render3d.shader;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Walks over the IR that visit a node once however many parents share it. The IR is a DAG, and a
 * chain that reuses its last step (z ↦ z·z, forty times) has 2^40 paths from the top: a walk that
 * follows each child as a tree never ends on it.
 */
final class Values {

    private Values() {
    }

    /** A binary node's type: the wider of its operands', a scalar promoted to the vector. */
    static ShaderType type(Expr.Binary node) {
        return type(node, new IdentityHashMap<>());
    }

    private static ShaderType type(Expr node, Map<Expr, ShaderType> known) {
        if (node instanceof Expr.Binary binary) {
            ShaderType found = known.get(binary);
            if (found == null) {
                ShaderType left = type(binary.left(), known);
                ShaderType right = type(binary.right(), known);
                found = left.components >= right.components ? left : right;
                known.put(binary, found);
            }
            return found;
        }
        if (node instanceof Expr.Loop loop) {
            return type(loop.initial(), known);
        }
        return node.type();
    }
}
