package limn.render3d.shader;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The IR as a value, its type, equality and hash, each a walk that visits a node once however
 * many parents share it. The IR is a DAG, and a
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

    /** Whether {@code node} and {@code other} are the same computation: kind, fields and children. */
    static boolean equal(Expr node, @Nullable Object other) {
        return other instanceof Expr expr && equal(node, expr, new IdentityHashMap<>());
    }

    /** Pairs found equal are remembered; a pair found unequal ends the whole comparison. */
    private static boolean equal(Expr a, Expr b, Map<Expr, Set<Expr>> same) {
        if (a == b) {
            return true;
        }
        if (a.getClass() != b.getClass()) {
            return false;
        }
        Set<Expr> known = same.get(a);
        if (known != null && known.contains(b)) {
            return true;
        }
        if (!sameFields(a, b)) {
            return false;
        }
        List<Expr> left = Loops.children(a);
        List<Expr> right = Loops.children(b);
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!equal(left.get(i), right.get(i), same)) {
                return false;
            }
        }
        same.computeIfAbsent(a, n -> Collections.newSetFromMap(new IdentityHashMap<>())).add(b);
        return true;
    }

    /** What two nodes of one kind hold besides their children. */
    private static boolean sameFields(Expr a, Expr b) {
        if (a instanceof Expr.Lit || a instanceof Expr.Ref || a instanceof Expr.Raw) {
            return a.equals(b);
        }
        if (a instanceof Expr.Swizzle x && b instanceof Expr.Swizzle y) {
            return x.pattern().equals(y.pattern());
        }
        if (a instanceof Expr.Binary x && b instanceof Expr.Binary y) {
            return x.op() == y.op();
        }
        if (a instanceof Expr.Call x && b instanceof Expr.Call y) {
            return x.function().equals(y.function()) && x.type() == y.type();
        }
        if (a instanceof Expr.Construct x && b instanceof Expr.Construct y) {
            return x.type() == y.type();
        }
        if (a instanceof Expr.Loop x && b instanceof Expr.Loop y) {
            return x.state().equals(y.state()) && x.maxIterations() == y.maxIterations()
                    && (x.until() == null) == (y.until() == null);
        }
        return a instanceof Expr.Sample;
    }

    /** A hash of {@code node} that equal nodes share, each node under it hashed once. */
    static int hash(Expr node) {
        return hash(node, new IdentityHashMap<>());
    }

    private static int hash(Expr node, Map<Expr, Integer> known) {
        Integer found = known.get(node);
        if (found != null) {
            return found;
        }
        int result = fieldsHash(node);
        for (Expr child : Loops.children(node)) {
            result = 31 * result + hash(child, known);
        }
        known.put(node, result);
        return result;
    }

    private static int fieldsHash(Expr node) {
        if (node instanceof Expr.Lit || node instanceof Expr.Ref || node instanceof Expr.Raw) {
            return node.hashCode();
        }
        if (node instanceof Expr.Swizzle swizzle) {
            return Objects.hash("Swizzle", swizzle.pattern());
        }
        if (node instanceof Expr.Binary binary) {
            return Objects.hash("Binary", binary.op().name());
        }
        if (node instanceof Expr.Call call) {
            return Objects.hash("Call", call.function(), call.type().name());
        }
        if (node instanceof Expr.Construct construct) {
            return Objects.hash("Construct", construct.type().name());
        }
        if (node instanceof Expr.Loop loop) {
            return Objects.hash("Loop", loop.state(), loop.maxIterations(), loop.until() == null);
        }
        return "Sample".hashCode();
    }
}
