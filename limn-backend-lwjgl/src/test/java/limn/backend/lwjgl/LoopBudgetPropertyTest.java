package limn.backend.lwjgl;

import limn.render3d.shader.Expr;
import limn.render3d.shader.ShaderType;
import limn.render3d.shader.SurfaceOutputs;
import limn.render3d.shader.TargetProfile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The turn budget counts what the generated shader runs, exactly: for a few hundred random
 * surfaces, nested loops of one value and several, early ends, values read by index, subtrees
 * shared across turns, loops built apart and set in turns, loops built on worker threads, and a
 * looping diffuse response, the count {@code SurfaceOutputs} holds a surface to equals the turns
 * of the innermost loops the generated {@code main()} runs with every early end ignored, the light
 * loop taken at its eight.
 */
class LoopBudgetPropertyTest {

    private static final Pattern FOR = Pattern.compile("for \\(int ir_i\\d+ = 0; ir_i\\d+ < (\\d+); \\+\\+ir_i\\d+\\) \\{");
    private static final String LIGHT_LOOP = "for (int i = 0; i < MAX_LIGHTS; i++) {";

    /** The turns the innermost generated loops of {@code main()} run, early ends ignored. */
    static long turnsRun(String fragment) {
        String main = fragment.substring(fragment.indexOf("void main()"));
        // A block on the stack: a loop's count (0 for any other block), and whether a loop is in it.
        Deque<long[]> blocks = new ArrayDeque<>();
        long total = 0;
        for (String line : main.split("\n")) {
            Matcher loop = FOR.matcher(line);
            long count = loop.find() ? Long.parseLong(loop.group(1)) : line.contains(LIGHT_LOOP) ? -8 : 0;
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    if (count != 0) {
                        for (long[] around : blocks) {
                            if (around[0] != 0) {
                                around[1] = 1; // the nearest loop around holds a loop
                                break;
                            }
                        }
                    }
                    blocks.push(new long[]{count, 0});
                    count = 0;
                } else if (c == '}') {
                    long[] closed = blocks.peek();
                    if (closed[0] > 0 && closed[1] == 0) {
                        long runs = 1;
                        for (long[] around : blocks) {
                            runs *= Math.abs(around[0] == 0 ? 1 : around[0]);
                        }
                        total += runs;
                    }
                    blocks.pop();
                }
            }
        }
        return total;
    }

    /** Random surfaces in the IR, scalar, with loops of every shape the factory builds. */
    static final class Builder {
        private final Random random;
        private final List<Expr> pool = new ArrayList<>();
        private final List<List<List<Expr>>> poolScopes = new ArrayList<>();
        private final List<Expr.Loop> apart = new ArrayList<>();
        private int loopsLeft = 6;

        Builder(long seed) {
            random = new Random(seed);
        }

        /** A node over the states in {@code open}, the innermost last. */
        Expr node(int depth, List<List<Expr>> open) {
            double c = random.nextDouble();
            if (depth <= 0 || c < 0.25) {
                double d = random.nextDouble();
                if (!open.isEmpty() && d < 0.55) {
                    List<Expr> state = open.get(random.nextInt(open.size()));
                    return state.get(random.nextInt(state.size()));
                }
                if (d < 0.8 && !pool.isEmpty()) {
                    int i = random.nextInt(pool.size());
                    List<List<Expr>> made = poolScopes.get(i);
                    if (open.size() >= made.size() && open.subList(0, made.size()).equals(made)) {
                        return pool.get(i);
                    }
                }
                return Expr.Lit.of((random.nextInt(8) + 1) / 4f);
            }
            Expr made;
            if (c < 0.55) {
                Expr.Op op = Expr.Op.values()[random.nextInt(3)];
                made = new Expr.Binary(op, node(depth - 1, open), node(depth - 1, open));
            } else if (c < 0.7) {
                made = random.nextBoolean()
                        ? new Expr.Call("fract", ShaderType.FLOAT, List.of(node(depth - 1, open)))
                        : new Expr.Call("min", ShaderType.FLOAT, List.of(node(depth - 1, open), node(depth - 1, open)));
            } else if (c < 0.8 && !apart.isEmpty()) {
                made = apart.get(random.nextInt(apart.size()));
            } else if (loopsLeft > 0) {
                made = loop(depth, open);
            } else {
                made = Expr.Lit.of(0.5f);
            }
            if (random.nextDouble() < 0.3) {
                pool.add(made);
                poolScopes.add(open);
            }
            return made;
        }

        Expr loop(int depth, List<List<Expr>> open) {
            loopsLeft--;
            int values = 1 + random.nextInt(3);
            int turns = 1 + random.nextInt(4);
            boolean until = random.nextBoolean();
            List<Expr> initial = new ArrayList<>();
            for (int i = 0; i < values; i++) {
                initial.add(node(depth - 1, open));
            }
            java.util.function.Function<List<Expr>, List<Expr>> next = state -> {
                List<List<Expr>> inside = new ArrayList<>(open);
                inside.add(state);
                List<Expr> stepped = new ArrayList<>();
                for (int i = 0; i < values; i++) {
                    stepped.add(new Expr.Call("fract", ShaderType.FLOAT, List.of(node(depth - 1, inside))));
                }
                return stepped;
            };
            java.util.function.Function<List<Expr>, Expr> stop = state -> {
                List<List<Expr>> inside = new ArrayList<>(open);
                inside.add(state);
                return new Expr.Binary(Expr.Op.SUB, node(depth - 1, inside), Expr.Lit.of(0.5f));
            };
            java.util.function.Supplier<Expr.Loop> build = until
                    ? () -> Expr.Loop.of(initial, turns, next, stop)
                    : () -> Expr.Loop.of(initial, turns, next);
            Expr.Loop loop = random.nextBoolean() ? CompletableFuture.supplyAsync(build).join() : build.get();
            Expr.Loop read = loop.value(random.nextInt(values));
            if (open.isEmpty()) {
                apart.add(read);
            }
            return read;
        }

        SurfaceOutputs surface() {
            for (int i = 0; i < 2; i++) {
                apart.add((Expr.Loop) loop(3, List.of()));
            }
            Expr emissive = new Expr.Construct(ShaderType.VEC3, List.of(node(4, List.of()), node(4, List.of()),
                    node(4, List.of())));
            SurfaceOutputs surface = IrSurfaces.emitting(emissive);
            return random.nextInt(3) == 0 ? surface.withDiffuseResponse(node(4, List.of())) : surface;
        }
    }

    private static long budget(SurfaceOutputs surface) throws Exception {
        Class<?> loops = Class.forName("limn.render3d.shader.Loops");
        Method count = loops.getDeclaredMethod("surfaceTurns", List.class, Expr.class, int.class);
        count.setAccessible(true);
        List<Expr> outputs = new ArrayList<>(List.of(surface.baseColor(), surface.metallic(), surface.roughness(),
                surface.emissive()));
        return (long) count.invoke(null, outputs, surface.diffuseResponse(), Gl3DContext.MAX_LIGHTS);
    }

    @Test
    void theBudgetIsTheTurnsTheGeneratedShaderRuns() throws Exception {
        int checked = 0;
        int withLoopsInLoops = 0;
        for (long seed = 1; seed <= 300; seed++) {
            SurfaceOutputs surface;
            try {
                surface = new Builder(seed).surface();
            } catch (IllegalArgumentException overBudget) {
                continue; // a random surface may ask for more than the cap: refused, nothing to compare
            }
            String fragment = GlslCodegen.generate(surface, TargetProfile.GLSL_330).fragmentSource();
            long run = turnsRun(fragment);
            assertEquals(run, budget(surface), "seed " + seed + "\n" + fragment.substring(fragment.indexOf("void main()")));
            checked++;
            if (fragment.contains("\n        for (int ir_i")) {
                withLoopsInLoops++; // a generated loop indented under another block of main
            }
        }
        assertTrue(checked > 200, checked + " surfaces checked");
        assertTrue(withLoopsInLoops > 20, withLoopsInLoops + " with a loop in a loop");
    }

    @Test
    void theCountOfTheGeneratedShaderReadsLeavesAndTheLightLoop() {
        // The counter itself, on text: two leaves of 3 under one of 4, and one of 5 in the light loop.
        String text = "void main() {\n    for (int ir_i0 = 0; ir_i0 < 4; ++ir_i0) {\n"
                + "        for (int ir_i1 = 0; ir_i1 < 3; ++ir_i1) {\n        }\n"
                + "        for (int ir_i2 = 0; ir_i2 < 3; ++ir_i2) {\n            if (x) {\n                break;\n"
                + "            }\n        }\n    }\n    for (int i = 0; i < MAX_LIGHTS; i++) {\n"
                + "        if (i >= count) {\n            break;\n        }\n"
                + "        for (int ir_i3 = 0; ir_i3 < 5; ++ir_i3) {\n        }\n    }\n}\n";
        assertEquals(4 * 3 + 4 * 3 + 8 * 5, turnsRun(text));
        assertEquals(0, turnsRun("void main() {\n    for (int i = 0; i < MAX_LIGHTS; i++) {\n    }\n}\n"));
    }
}
