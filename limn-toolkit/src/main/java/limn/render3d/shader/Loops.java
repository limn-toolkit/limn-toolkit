package limn.render3d.shader;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What {@link Expr.Loop} checks of the expressions it holds: their nesting and what they call. */
final class Loops {

    private Loops() {
    }

    /** The children of {@code node}, in the order they are written. */
    static List<Expr> children(Expr node) {
        if (node instanceof Expr.Sample sample) {
            return List.of(sample.sampler(), sample.uv());
        }
        if (node instanceof Expr.Swizzle swizzle) {
            return List.of(swizzle.source());
        }
        if (node instanceof Expr.Binary binary) {
            return List.of(binary.left(), binary.right());
        }
        if (node instanceof Expr.Call call) {
            return call.args();
        }
        if (node instanceof Expr.Construct construct) {
            return construct.args();
        }
        if (node instanceof Expr.Loop loop) {
            return loop.until() == null ? List.of(loop.initial(), loop.next())
                    : List.of(loop.initial(), loop.next(), loop.until());
        }
        return List.of();
    }

    /**
     * The most turns a pixel takes in the loops under {@code node}, 1 where there are none, and
     * refuses a loop that reuses the name of a loop holding it: {@code enclosing} is the name of
     * the loop holding {@code node}, and every loop met on the way down adds its own.
     */
    static long turns(Expr node, String enclosing) {
        return turns(node, List.of(enclosing), new IdentityHashMap<>());
    }

    /**
     * Memoized by node and by the names around it, so a node shared a thousand times is walked
     * once for each nesting it is met in, and a chain built by reusing its last step stays linear.
     */
    private static long turns(Expr node, List<String> enclosing, Map<Expr, Map<List<String>, Long>> seen) {
        Map<List<String>, Long> byNesting = seen.computeIfAbsent(node, n -> new java.util.HashMap<>());
        Long known = byNesting.get(enclosing);
        if (known != null) {
            return known;
        }
        long result = walk(node, enclosing, seen);
        byNesting.put(enclosing, result);
        return result;
    }

    private static long walk(Expr node, List<String> enclosing, Map<Expr, Map<List<String>, Long>> seen) {
        if (node instanceof Expr.Loop loop) {
            if (enclosing.contains(loop.state())) {
                throw new IllegalArgumentException("a loop inside the loop of \"" + loop.state()
                        + "\" names its state the same, so one reference would mean two values");
            }
            List<String> inside = new java.util.ArrayList<>(enclosing);
            inside.add(loop.state());
            // Its turns multiply what it nests; its initial value runs once, before it.
            long body = Math.max(turns(loop.next(), inside, seen),
                    loop.until() == null ? 1 : turns(loop.until(), inside, seen));
            return Math.max(loop.maxIterations() * body, turns(loop.initial(), enclosing, seen));
        }
        long most = 1;
        for (Expr child : children(node)) {
            most = Math.max(most, turns(child, enclosing, seen));
        }
        return most;
    }

    /**
     * The texture functions that need no neighbouring pixel: told their level of detail or its
     * gradients, or asking the size. Every other {@code texture…} function picks its level from the
     * derivatives of its coordinates. ({@code texelFetch} reads one texel and is no {@code texture…}.)
     */
    private static final Set<String> EXPLICIT_LEVEL = Set.of("textureSize", "textureLod",
            "textureLodOffset", "textureProjLod", "textureProjLodOffset", "textureGrad", "textureGradOffset",
            "textureProjGrad", "textureProjGradOffset");

    /** What under {@code node} is undefined in non-uniform control flow, or {@code null}. */
    static @Nullable String undefinedEarly(Expr node) {
        return undefinedEarly(node, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static @Nullable String undefinedEarly(Expr node, Set<Expr> seen) {
        if (!seen.add(node)) {
            return null;
        }
        if (node instanceof Expr.Sample) {
            return "a texture sample";
        }
        if (node instanceof Expr.Call call) {
            String function = call.function();
            if (function.startsWith("dFd") || function.startsWith("fwidth")) {
                return "a derivative (" + function + ")";
            }
            if (function.startsWith("texture") && !EXPLICIT_LEVEL.contains(function)) {
                return "a texture lookup that picks its own level of detail (" + function + ")";
            }
        }
        for (Expr child : children(node)) {
            String found = undefinedEarly(child, seen);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
