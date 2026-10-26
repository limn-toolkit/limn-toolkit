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

    /**
     * The count {@link #walked} keeps on this thread, or null while it keeps none. Every walk of
     * the IR in this package, the checks of a loop and of a surface, hashing and comparing
     * included, takes a node's children from {@link #children}, so its calls are the work those
     * walks do: a number the same on every machine, which a test holds to a bound in the size of
     * the IR where a time would fail on a loaded one.
     */
    private static final ThreadLocal<long @Nullable []> WALKED = new ThreadLocal<>();

    /** How many nodes the walks of this package visit on this thread while {@code work} runs. */
    static long walked(Runnable work) {
        long[] outer = WALKED.get();
        long[] count = {0};
        WALKED.set(count);
        try {
            work.run();
        } finally {
            WALKED.set(outer);
            if (outer != null) {
                outer[0] += count[0];
            }
        }
        return count[0];
    }

    /** The children of {@code node}, in the order they are written. */
    static List<Expr> children(Expr node) {
        long[] walked = WALKED.get();
        if (walked != null) {
            walked[0]++;
        }
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

    /**
     * Set while {@link #rename} rebuilds a loop it renamed a free name in: the loop passed every
     * check when it was built, and renaming does not change one, so the constructor need not
     * count and walk its nest again, which at every level of a deep nest cost its fourth power.
     */
    static final ThreadLocal<Boolean> RENAMING = ThreadLocal.withInitial(() -> false);

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
                // A free name renamed to another free one changes no check the loop passed: no
                // state under it shares the new name, which is a level above them all.
                boolean was = RENAMING.get();
                RENAMING.set(true);
                try {
                    result = new Expr.Loop(loop.states(), initial, loop.maxIterations(), next, until, loop.index());
                } finally {
                    RENAMING.set(was);
                }
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

    /**
     * The first {@code ir_} name read under {@code roots} where no loop binds it, or null: a
     * loop's state outside its loop, or one of the generator's own variables.
     */
    static @Nullable String freeState(List<Expr> roots) {
        return freeName(roots, name -> name.startsWith("ir_"));
    }

    /**
     * The first state name {@link Expr.Loop#of} gives a built loop that a turn under construction
     * reads where no loop in it binds it, or null. While a turn is built, the states of the loops
     * around it still have their provisional names, and a loop built in it binds its own: a built
     * name read free can only be another loop's state, its turn read back through the record or a
     * reference made by hand, and the turn would read its own loop's state under it if the levels
     * matched.
     */
    static @Nullable String foreignState(List<Expr> turn) {
        return freeName(turn, Loops::isStateName);
    }

    private static @Nullable String freeName(List<Expr> roots, java.util.function.Predicate<String> watched) {
        Map<Expr, Set<List<String>>> seen = new IdentityHashMap<>();
        for (Expr root : roots) {
            String found = freeState(root, List.of(), seen, watched);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Memoized by node and by the states bound around it, so a shared node is walked once a nesting. */
    private static @Nullable String freeState(Expr node, List<String> bound, Map<Expr, Set<List<String>>> seen,
                                               java.util.function.Predicate<String> watched) {
        if (!seen.computeIfAbsent(node, n -> new java.util.HashSet<>()).add(bound)) {
            return null;
        }
        if (node instanceof Expr.Ref ref) {
            // Every ir_ name is the generator's or a loop's: read where no loop binds it, it is either
            // a state out of its loop or a generated variable reached by accident.
            return watched.test(ref.name()) && !bound.contains(ref.name()) ? ref.name() : null;
        }
        if (node instanceof Expr.Loop loop) {
            for (Expr start : loop.initial()) {
                String found = freeState(start, bound, seen, watched);
                if (found != null) {
                    return found;
                }
            }
            List<String> inside = new java.util.ArrayList<>(bound);
            inside.addAll(loop.states());
            String found;
            for (Expr part : turnOf(loop)) {
                found = freeState(part, inside, seen, watched);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        for (Expr child : children(node)) {
            String found = freeState(child, bound, seen, watched);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ turns

    /**
     * The turns a pixel takes in a loop of {@code maxIterations} turns binding {@code states}, over
     * {@code turn} (its next state and its exit test) after {@code initial}, written as the
     * generator writes it on its own: see {@link Placement}.
     */
    static long turns(List<Expr> initial, int maxIterations, List<Expr> turn, List<String> states) {
        Placement placement = new Placement(new Counter());
        Placement.Block top = placement.top();
        for (Expr start : initial) {
            placement.visit(start, top);
        }
        placement.write(states, maxIterations, turn, top);
        return placement.total;
    }

    /**
     * A surface's turns a pixel: its outputs computed once, its diffuse response once for each of
     * {@code lights}, a loop the outputs already computed read by the response where it is.
     */
    static long surfaceTurns(List<Expr> outputs, @Nullable Expr response, int lights) {
        if (lights < 0) {
            throw new IllegalArgumentException("a pass shades with no fewer than 0 lights, not " + lights);
        }
        Placement placement = new Placement(new Counter());
        Placement.Block top = placement.top();
        for (Expr output : outputs) {
            placement.visit(output, top);
        }
        if (response != null) {
            placement.visit(response, placement.floor(top, lights));
        }
        return placement.total;
    }

    /**
     * The generator's placement of loops, followed for the loops alone, so the count is what the
     * generated shader runs. A loop is written in the innermost block binding a name it reads, or
     * in the block of its group that no loop binds (the top of {@code main()}, or the light loop);
     * a loop already written in a block around it, with nothing between binding a name it reads,
     * is read there and not written again. Its initial values are computed where it is written,
     * its turn in its own block. The count is the turns of the innermost loops: a loop whose block
     * holds no loop counts its own turns, times the turns of the blocks around it.
     */
    private static final class Placement {
        /** A block of {@code main()}: a loop's turn, the top, or the light loop. */
        static final class Block {
            final @Nullable Block parent;
            final Set<String> binds;
            /** How many times a pixel runs this block. */
            final long runs;
            final boolean floor;
            final Set<Integer> written = new java.util.HashSet<>();
            final Set<Expr> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            boolean holdsLoop;

            Block(@Nullable Block parent, Set<String> binds, long runs, boolean floor) {
                this.parent = parent;
                this.binds = binds;
                this.runs = runs;
                this.floor = floor;
            }

            boolean bindsAny(Set<String> names) {
                return !Collections.disjoint(binds, names);
            }
        }

        private final Counter counter;
        private final Map<Expr, Boolean> holdsLoop = new IdentityHashMap<>();
        long total;

        Placement(Counter counter) {
            this.counter = counter;
        }

        Block top() {
            return new Block(null, Set.of(), 1, true);
        }

        /** The light loop's block: a floor of its own, run once a light. */
        Block floor(Block top, int lights) {
            return new Block(top, Set.of(), lights, true);
        }

        /** Whether a loop is anywhere under {@code node}. */
        private boolean holdsLoop(Expr node) {
            Boolean known = holdsLoop.get(node);
            if (known == null) {
                known = node instanceof Expr.Loop;
                for (Expr child : children(node)) {
                    if (known) {
                        break;
                    }
                    known = holdsLoop(child);
                }
                holdsLoop.put(node, known);
            }
            return known;
        }

        void visit(Expr node, Block at) {
            if (!holdsLoop(node) || !at.visited.add(node)) {
                return;
            }
            if (!(node instanceof Expr.Loop loop)) {
                for (Expr child : children(node)) {
                    visit(child, at);
                }
                return;
            }
            int number = counter.loopNumber(loop);
            Set<String> reads = counter.reads(loop);
            for (Block around = at; around != null; around = around.parent) {
                if (around.written.contains(number)) {
                    return; // computed already where this block reads it
                }
                if (around.bindsAny(reads)) {
                    break;
                }
            }
            Block home = at;
            for (Block around = at; around != null; around = around.parent) {
                if (around.bindsAny(reads) || around.floor) {
                    home = around;
                    break;
                }
            }
            for (Expr start : loop.initial()) {
                visit(start, home);
            }
            home.written.add(number);
            write(loop.states(), loop.maxIterations(), turnOf(loop), home);
        }

        /** A loop's turn written in {@code home}: its own block, and its turns if it holds no loop. */
        void write(List<String> states, int maxIterations, List<Expr> turn, Block home) {
            home.holdsLoop = true;
            Block body = new Block(home, Set.copyOf(states), saturatedProduct(home.runs, maxIterations), false);
            for (Expr part : turn) {
                visit(part, body);
            }
            if (!body.holdsLoop) {
                total = saturatedSum(total, body.runs);
            }
        }
    }

    /**
     * The IR numbered by value as the generator numbers it, each node once, and the names each
     * node reads: a loop is one number however many of its values are read and however many times
     * it was built alike.
     */
    private static final class Counter {
        private final Map<Expr, Integer> ids = new IdentityHashMap<>();
        private final Map<List<Object>, Integer> byKey = new HashMap<>();
        private final Map<Expr, Integer> loopNumbers = new IdentityHashMap<>();
        private final Map<List<Object>, Integer> byLoopKey = new HashMap<>();
        private final Map<Expr, Set<String>> reads = new IdentityHashMap<>();

        /** The IR names a node reads, a loop's own states excepted, as the generator places it by. */
        Set<String> reads(Expr node) {
            Set<String> known = reads.get(node);
            if (known != null) {
                return known;
            }
            Set<String> names = new java.util.HashSet<>();
            if (node instanceof Expr.Ref ref) {
                names.add(ref.name());
            }
            for (Expr child : children(node)) {
                names.addAll(reads(child));
            }
            if (node instanceof Expr.Loop loop) {
                names.removeAll(loop.states());
                for (Expr start : loop.initial()) {
                    names.addAll(reads(start));
                }
            }
            reads.put(node, names);
            return names;
        }

        /** The node's number: equal for nodes of one kind, equal fields and equally numbered children. */
        int id(Expr node) {
            Integer known = ids.get(node);
            if (known != null) {
                return known;
            }
            List<Object> key = new java.util.ArrayList<>();
            if (node instanceof Expr.Lit || node instanceof Expr.Ref || node instanceof Expr.Raw) {
                key.add(node);
            } else if (node instanceof Expr.Swizzle swizzle) {
                key.add("W" + swizzle.pattern());
            } else if (node instanceof Expr.Binary binary) {
                key.add(binary.op());
            } else if (node instanceof Expr.Call call) {
                key.add("C" + call.function());
                key.add(call.type());
            } else if (node instanceof Expr.Construct construct) {
                key.add("K");
                key.add(construct.type());
            } else if (node instanceof Expr.Loop loop) {
                key.add("P" + loop.index());
                key.add(loopNumber(loop));
            } else {
                key.add("S");
            }
            if (!(node instanceof Expr.Loop)) {
                for (Expr child : children(node)) {
                    key.add(id(child));
                }
            }
            int id = byKey.computeIfAbsent(key, k -> byKey.size());
            ids.put(node, id);
            return id;
        }

        /** The loop's number, whichever of its values the node reads. */
        int loopNumber(Expr.Loop loop) {
            Integer known = loopNumbers.get(loop);
            if (known != null) {
                return known;
            }
            List<Object> key = new java.util.ArrayList<>();
            key.add(loop.states());
            key.add(loop.maxIterations());
            key.add(loop.until() == null);
            for (Expr child : children(loop)) {
                key.add(id(child));
            }
            int number = byLoopKey.computeIfAbsent(key, k -> byLoopKey.size());
            loopNumbers.put(loop, number);
            return number;
        }
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
