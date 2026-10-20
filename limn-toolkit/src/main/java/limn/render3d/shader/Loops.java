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

    /** How the names {@link Expr.Loop#of} gives a state begin: a number follows. */
    static final String STATE_PREFIX = "ir_state";

    /** Whether {@code name} is one {@link Expr.Loop#of} gives a state. */
    static boolean isStateName(String name) {
        return name.matches("ir_state(0|[1-9][0-9]{0,8})");
    }

    /**
     * The type a reference to {@code state} under {@code node} has where it is not {@code type},
     * or null. A loop under it that binds the same name hides it in its turn, not in its initial
     * value, which is computed in the turn around it.
     */
    static @Nullable ShaderType misread(Expr node, String state, ShaderType type) {
        return misread(node, state, type, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static @Nullable ShaderType misread(Expr node, String state, ShaderType type, Set<Expr> seen) {
        if (!seen.add(node)) {
            return null;
        }
        if (node instanceof Expr.Ref ref && ref.name().equals(state) && ref.type() != type) {
            return ref.type();
        }
        List<Expr> inside = node instanceof Expr.Loop loop && loop.state().equals(state)
                ? List.of(loop.initial()) : children(node);
        for (Expr child : inside) {
            ShaderType found = misread(child, state, type, seen);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The name of a loop's state read under {@code roots} outside its loop, or null. */
    static @Nullable String freeState(List<Expr> roots) {
        Map<Expr, Set<List<String>>> seen = new IdentityHashMap<>();
        for (Expr root : roots) {
            String found = freeState(root, List.of(), seen);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Memoized by node and by the states bound around it, so a shared node is walked once a nesting. */
    private static @Nullable String freeState(Expr node, List<String> bound, Map<Expr, Set<List<String>>> seen) {
        if (!seen.computeIfAbsent(node, n -> new java.util.HashSet<>()).add(bound)) {
            return null;
        }
        if (node instanceof Expr.Ref ref) {
            return isStateName(ref.name()) && !bound.contains(ref.name()) ? ref.name() : null;
        }
        if (node instanceof Expr.Loop loop) {
            String found = freeState(loop.initial(), bound, seen);
            if (found != null) {
                return found;
            }
            List<String> inside = new java.util.ArrayList<>(bound);
            inside.add(loop.state());
            for (Expr part : turnOf(loop)) {
                found = freeState(part, inside, seen);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        for (Expr child : children(node)) {
            String found = freeState(child, bound, seen);
            if (found != null) {
                return found;
            }
        }
        return null;
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
