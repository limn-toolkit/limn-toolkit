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
            List<Expr> all = new java.util.ArrayList<>(loop.initial());
            all.addAll(turnOf(loop));
            return all;
        }
        return List.of();
    }

    /** How the names {@link Expr.Loop#of} gives a state's values begin: two numbers follow. */
    static final String STATE_PREFIX = "ir_state";

    /** How the names a state has while {@link Expr.Loop#of} builds its turn begin. */
    static final String BUILDING_PREFIX = "ir_building";

    /** The name {@link Expr.Loop#of} gives value {@code place} of a loop at {@code level}. */
    static String stateName(int level, int place) {
        return STATE_PREFIX + level + "_" + place;
    }

    /**
     * The level a loop over {@code turn} takes: one more than the highest of the loops anywhere in
     * it, initial values and nested turns included, or 0 where there are none. A loop's name holds
     * its level, so no loop in a turn shares a name with the loop around it.
     */
    static int level(List<Expr> turn) {
        Set<Expr> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Expr> pending = new java.util.ArrayDeque<>(turn);
        int level = 0;
        while (!pending.isEmpty()) {
            Expr node = pending.pop();
            if (!seen.add(node)) {
                continue;
            }
            if (node instanceof Expr.Loop loop) {
                level = Math.max(level, levelOf(loop) + 1);
            }
            pending.addAll(children(node));
        }
        return level;
    }

    /** The level held in a loop's names, {@code ir_state<level>_<place>}. */
    private static int levelOf(Expr.Loop loop) {
        String name = loop.states().get(0);
        return Integer.parseInt(name.substring(STATE_PREFIX.length(), name.indexOf('_', STATE_PREFIX.length())));
    }

    /**
     * {@code node} with every reference named in {@code names} renamed, rebuilt where it changes
     * and kept where it does not; {@code done} remembers the nodes already seen.
     */
    static Expr rename(Expr node, Map<String, String> names, Map<Expr, Expr> done) {
        Expr known = done.get(node);
        if (known != null) {
            return known;
        }
        Expr result = node;
        if (node instanceof Expr.Ref ref) {
            String name = names.get(ref.name());
            if (name != null) {
                result = new Expr.Ref(name, ref.type());
            }
        } else if (node instanceof Expr.Sample sample) {
            Expr sampler = rename(sample.sampler(), names, done);
            Expr uv = rename(sample.uv(), names, done);
            if (sampler != sample.sampler() || uv != sample.uv()) {
                result = new Expr.Sample((Expr.Ref) sampler, uv);
            }
        } else if (node instanceof Expr.Swizzle swizzle) {
            Expr source = rename(swizzle.source(), names, done);
            if (source != swizzle.source()) {
                result = new Expr.Swizzle(source, swizzle.pattern());
            }
        } else if (node instanceof Expr.Binary binary) {
            Expr left = rename(binary.left(), names, done);
            Expr right = rename(binary.right(), names, done);
            if (left != binary.left() || right != binary.right()) {
                result = new Expr.Binary(binary.op(), left, right);
            }
        } else if (node instanceof Expr.Call call) {
            List<Expr> args = renameAll(call.args(), names, done);
            if (args != call.args()) {
                result = new Expr.Call(call.function(), call.type(), args);
            }
        } else if (node instanceof Expr.Construct construct) {
            List<Expr> args = renameAll(construct.args(), names, done);
            if (args != construct.args()) {
                result = new Expr.Construct(construct.type(), args);
            }
        } else if (node instanceof Expr.Loop loop) {
            List<Expr> initial = renameAll(loop.initial(), names, done);
            List<Expr> next = renameAll(loop.next(), names, done);
            Expr until = loop.until() == null ? null : rename(loop.until(), names, done);
            if (initial != loop.initial() || next != loop.next() || until != loop.until()) {
                result = new Expr.Loop(loop.states(), initial, loop.maxIterations(), next, until, loop.index());
            }
        }
        done.put(node, result);
        return result;
    }

    /** {@code nodes} renamed, the same list where none changes. */
    private static List<Expr> renameAll(List<Expr> nodes, Map<String, String> names, Map<Expr, Expr> done) {
        List<Expr> renamed = new java.util.ArrayList<>(nodes.size());
        boolean changed = false;
        for (Expr node : nodes) {
            Expr after = rename(node, names, done);
            changed |= after != node;
            renamed.add(after);
        }
        return changed ? renamed : nodes;
    }

    /** Whether {@code name} is one {@link Expr.Loop#of} gives a state's value. */
    static boolean isStateName(String name) {
        return name.matches("ir_state(0|[1-9][0-9]{0,8})_(0|[1-9][0-9]{0,8})");
    }

    /**
     * Why a reference under {@code turn} to a value of the state, named in {@code types}, is not of
     * that value's type, or null. A loop under it that binds a name hides it in its turn, not in
     * its initial values, which are computed in the turn around it.
     */
    static @Nullable String misread(List<Expr> turn, Map<String, ShaderType> types) {
        Map<Expr, Set<Map<String, ShaderType>>> seen = new IdentityHashMap<>();
        for (Expr part : turn) {
            String found = misread(part, types, seen);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static @Nullable String misread(Expr node, Map<String, ShaderType> types,
                                            Map<Expr, Set<Map<String, ShaderType>>> seen) {
        if (types.isEmpty() || !seen.computeIfAbsent(node, n -> new java.util.HashSet<>()).add(types)) {
            return null;
        }
        if (node instanceof Expr.Ref ref) {
            ShaderType type = types.get(ref.name());
            return type == null || ref.type() == type ? null
                    : "the state's " + ref.name() + " is a " + type + ", read here as a " + ref.type();
        }
        if (node instanceof Expr.Loop loop) {
            for (Expr start : loop.initial()) {
                String found = misread(start, types, seen);
                if (found != null) {
                    return found;
                }
            }
            Map<String, ShaderType> outer = new HashMap<>(types);
            outer.keySet().removeAll(loop.states());
            for (Expr part : turnOf(loop)) {
                String found = misread(part, outer, seen);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        for (Expr child : children(node)) {
            String found = misread(child, types, seen);
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
            boolean state = isStateName(ref.name()) || ref.name().startsWith(BUILDING_PREFIX);
            return state && !bound.contains(ref.name()) ? ref.name() : null;
        }
        if (node instanceof Expr.Loop loop) {
            for (Expr start : loop.initial()) {
                String found = freeState(start, bound, seen);
                if (found != null) {
                    return found;
                }
            }
            List<String> inside = new java.util.ArrayList<>(bound);
            inside.addAll(loop.states());
            String found;
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
     * (its next state and its exit test) after {@code initial}: the initial values' loops once,
     * then each turn's loops, at least one turn's worth, times the count.
     */
    static long turns(List<Expr> initial, int maxIterations, List<Expr> turn) {
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
        Map<LoopKey, Long> known = new HashMap<>();
        Map<LoopKey, Expr.Loop> once = frontier(outputs);
        long total = work(once, known);
        if (response != null) {
            Map<LoopKey, Expr.Loop> perLight = frontier(List.of(response));
            perLight.keySet().removeAll(once.keySet());
            total = saturatedSum(total, saturatedProduct(lights, work(perLight, known)));
        }
        return total;
    }

    private static long turns(List<Expr> initial, int maxIterations, List<Expr> turn, Map<LoopKey, Long> known) {
        long before = work(frontier(initial), known);
        long each = Math.max(1, work(frontier(turn), known));
        return saturatedSum(before, saturatedProduct(maxIterations, each));
    }

    /**
     * A loop as a computation, whichever of its values a node reads: the nodes for two values of
     * one loop are one loop, computed once.
     */
    private record LoopKey(List<String> states, List<Expr> initial, int maxIterations, List<Expr> next,
                           @Nullable Expr until) {
        LoopKey(Expr.Loop loop) {
            this(loop.states(), loop.initial(), loop.maxIterations(), loop.next(), loop.until());
        }
    }

    /** The loops reached from {@code roots} without passing through another loop, by value. */
    private static Map<LoopKey, Expr.Loop> frontier(List<Expr> roots) {
        Map<LoopKey, Expr.Loop> loops = new java.util.LinkedHashMap<>();
        Set<Expr> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Expr> pending = new java.util.ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            Expr node = pending.pop();
            if (!seen.add(node)) {
                continue;
            }
            if (node instanceof Expr.Loop loop) {
                loops.putIfAbsent(new LoopKey(loop), loop);
            } else {
                pending.addAll(children(node));
            }
        }
        return loops;
    }

    private static long work(Map<LoopKey, Expr.Loop> loops, Map<LoopKey, Long> known) {
        long sum = 0;
        for (Map.Entry<LoopKey, Expr.Loop> entry : loops.entrySet()) {
            Long turns = known.get(entry.getKey());
            if (turns == null) {
                Expr.Loop loop = entry.getValue();
                turns = turns(loop.initial(), loop.maxIterations(), turnOf(loop), known);
                known.put(entry.getKey(), turns);
            }
            sum = saturatedSum(sum, turns);
        }
        return sum;
    }

    /** A loop's turn: its next values and its exit test. */
    static List<Expr> turnOf(Expr.Loop loop) {
        if (loop.until() == null) {
            return loop.next();
        }
        List<Expr> turn = new java.util.ArrayList<>(loop.next());
        turn.add(loop.until());
        return turn;
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
