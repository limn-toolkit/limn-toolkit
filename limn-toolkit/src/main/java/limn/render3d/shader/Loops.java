package limn.render3d.shader;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
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
     * Refuses a loop under {@code node} that reuses the name of a loop holding it: {@code enclosing}
     * is the name of the loop holding {@code node}, and every loop met on the way down adds its own.
     */
    static void refuseReusedNames(Expr node, String enclosing) {
        refuseReusedNames(node, List.of(enclosing), new IdentityHashMap<>());
    }

    /** Memoized by node and by the names around it, so a shared node is walked once a nesting. */
    private static void refuseReusedNames(Expr node, List<String> enclosing, Map<Expr, Set<List<String>>> seen) {
        if (!seen.computeIfAbsent(node, n -> new java.util.HashSet<>()).add(enclosing)) {
            return;
        }
        if (node instanceof Expr.Loop loop) {
            if (enclosing.contains(loop.state())) {
                throw new IllegalArgumentException("a loop inside the loop of \"" + loop.state()
                        + "\" names its state the same, so one reference would mean two values");
            }
            List<String> inside = new java.util.ArrayList<>(enclosing);
            inside.add(loop.state());
            refuseReusedNames(loop.next(), inside, seen);
            if (loop.until() != null) {
                refuseReusedNames(loop.until(), inside, seen);
            }
            refuseReusedNames(loop.initial(), enclosing, seen);
            return;
        }
        for (Expr child : children(node)) {
            refuseReusedNames(child, enclosing, seen);
        }
    }

    // ------------------------------------------------------------------ turns

    /**
     * The most turns a pixel takes in a loop of {@code maxIterations} turns over {@code turn}
     * (its next state and its exit test) after {@code initial}: the initial value's loops once,
     * then each turn's loops, at least one turn's worth, times the count.
     */
    static long turns(Expr initial, int maxIterations, List<Expr> turn) {
        return turns(initial, maxIterations, turn, new HashMap<>());
    }

    /**
     * The most turns a pixel takes computing each of {@code roots} once: the loops reached without
     * passing through another loop add, a loop reached twice, or two built alike, counted once.
     */
    static long turns(List<Expr> roots) {
        return work(frontier(roots), new HashMap<>());
    }

    /**
     * A surface's turns a pixel when its diffuse response runs once for each of {@code lights}:
     * the response's loops that an output also computes are computed once, before the lights.
     */
    static long surfaceTurns(List<Expr> outputs, @Nullable Expr response, int lights) {
        if (lights < 0) {
            throw new IllegalArgumentException("a pass shades with no fewer than 0 lights, not " + lights);
        }
        Map<Expr.Loop, Long> known = new HashMap<>();
        Set<Expr.Loop> once = frontier(outputs);
        long total = work(once, known);
        if (response != null) {
            Set<Expr.Loop> perLight = frontier(List.of(response));
            perLight.removeAll(once);
            total = saturatedSum(total, saturatedProduct(lights, work(perLight, known)));
        }
        return total;
    }

    private static long turns(Expr initial, int maxIterations, List<Expr> turn, Map<Expr.Loop, Long> known) {
        long before = work(frontier(List.of(initial)), known);
        long each = Math.max(1, work(frontier(turn), known));
        return saturatedSum(before, saturatedProduct(maxIterations, each));
    }

    /** The loops reached from {@code roots} without passing through another loop, by value. */
    private static Set<Expr.Loop> frontier(List<Expr> roots) {
        Set<Expr.Loop> loops = new java.util.LinkedHashSet<>();
        Set<Expr> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Expr> pending = new java.util.ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            Expr node = pending.pop();
            if (!seen.add(node)) {
                continue;
            }
            if (node instanceof Expr.Loop loop) {
                loops.add(loop);
            } else {
                pending.addAll(children(node));
            }
        }
        return loops;
    }

    private static long work(Set<Expr.Loop> loops, Map<Expr.Loop, Long> known) {
        long sum = 0;
        for (Expr.Loop loop : loops) {
            Long turns = known.get(loop);
            if (turns == null) {
                turns = turns(loop.initial(), loop.maxIterations(), turnOf(loop), known);
                known.put(loop, turns);
            }
            sum = saturatedSum(sum, turns);
        }
        return sum;
    }

    private static List<Expr> turnOf(Expr.Loop loop) {
        return loop.until() == null ? List.of(loop.next()) : List.of(loop.next(), loop.until());
    }

    private static long saturatedSum(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private static long saturatedProduct(long a, long b) {
        return a != 0 && b > Long.MAX_VALUE / a ? Long.MAX_VALUE : a * b;
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
