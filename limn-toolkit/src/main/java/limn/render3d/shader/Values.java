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

    /**
     * {@code node} written out as a record writes itself, except that a node with children and
     * more than one parent is written once, labelled {@code #n=}, and as {@code #n} after that:
     * a tree of every path through a shared chain grows as a power of its depth.
     */
    static String text(Expr node) {
        Map<Expr, Integer> parents = new IdentityHashMap<>();
        countParents(node, parents);
        StringBuilder out = new StringBuilder();
        write(node, parents, new IdentityHashMap<>(), out);
        return out.toString();
    }

    private static void countParents(Expr node, Map<Expr, Integer> parents) {
        for (Expr child : Loops.children(node)) {
            if (parents.merge(child, 1, Integer::sum) == 1) {
                countParents(child, parents);
            }
        }
    }

    private static void write(Expr node, Map<Expr, Integer> parents, Map<Expr, Integer> labels, StringBuilder out) {
        List<Expr> children = Loops.children(node);
        if (children.isEmpty()) {
            out.append(node);
            return;
        }
        Integer label = labels.get(node);
        if (label != null) {
            out.append('#').append(label);
            return;
        }
        if (parents.getOrDefault(node, 0) > 1) {
            label = labels.size() + 1;
            labels.put(node, label);
            out.append('#').append(label).append('=');
        }
        if (node instanceof Expr.Sample sample) {
            out.append("Sample[sampler=");
            write(sample.sampler(), parents, labels, out);
            out.append(", uv=");
            write(sample.uv(), parents, labels, out);
        } else if (node instanceof Expr.Swizzle swizzle) {
            out.append("Swizzle[source=");
            write(swizzle.source(), parents, labels, out);
            out.append(", pattern=").append(swizzle.pattern());
        } else if (node instanceof Expr.Binary binary) {
            out.append("Binary[op=").append(binary.op()).append(", left=");
            write(binary.left(), parents, labels, out);
            out.append(", right=");
            write(binary.right(), parents, labels, out);
        } else if (node instanceof Expr.Call call) {
            out.append("Call[function=").append(call.function()).append(", type=").append(call.type())
                    .append(", args=");
            writeAll(call.args(), parents, labels, out);
        } else if (node instanceof Expr.Construct construct) {
            out.append("Construct[type=").append(construct.type()).append(", args=");
            writeAll(construct.args(), parents, labels, out);
        } else if (node instanceof Expr.Loop loop) {
            out.append("Loop[state=").append(loop.state()).append(", initial=");
            write(loop.initial(), parents, labels, out);
            out.append(", maxIterations=").append(loop.maxIterations()).append(", next=");
            write(loop.next(), parents, labels, out);
            out.append(", until=");
            if (loop.until() == null) {
                out.append("null");
            } else {
                write(loop.until(), parents, labels, out);
            }
        }
        out.append(']');
    }

    private static void writeAll(List<Expr> nodes, Map<Expr, Integer> parents, Map<Expr, Integer> labels,
                                 StringBuilder out) {
        out.append('[');
        for (int i = 0; i < nodes.size(); i++) {
            out.append(i == 0 ? "" : ", ");
            write(nodes.get(i), parents, labels, out);
        }
        out.append(']');
    }
}
