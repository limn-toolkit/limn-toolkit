package limn.backend.lwjgl;

import limn.render3d.shader.Expr;
import limn.render3d.shader.SurfaceOutputs;
import limn.render3d.shader.TargetProfile;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates GLSL for a {@link TargetProfile} from the neutral shader IR. The
 * engine owns the framework: the Frame/Lights/Material std140 blocks, the sampler
 * and varyings, the Cook-Torrance BRDF; the fragment writes linear scene-referred
 * light, the display transform being the composite's job. The material's
 * {@link SurfaceOutputs} DAG supplies only the four surface expressions
 * ({@code baseColor}/{@code metallic}/{@code roughness}/{@code emissive}) spliced
 * into {@code main()}. This is the portability core: the same IR compiles to
 * GLSL 330 and GLSL ES 3.00 unchanged (they differ only in the header), and it
 * reproduces the hand-written reference shader (verified by golden + pixel tests).
 *
 * <p>Pure string generation (no GL), so it is unit-testable headless.
 */
final class GlslCodegen {

    record Generated(String vertexSource, String fragmentSource) {
    }

    static Generated generate(SurfaceOutputs surface, TargetProfile profile) {
        return generate(surface, "", profile);
    }

    /**
     * As above, with {@code declarations} spliced in after the framework's own: the
     * samplers and {@code vec4} uniforms an application-supplied surface named for
     * itself ({@link limn.render3d.Material.Surface}). They are appended rather than
     * merged so a name that collides with a framework one fails to compile, which is
     * the only moment anybody would want to hear about it.
     */
    static Generated generate(SurfaceOutputs surface, String declarations, TargetProfile profile) {
        Emitter emitter = new Emitter(profile, surface);
        Scope top = new Scope(null, null, null, "    ");
        // A surface without a normal shades by the interpolated geometric one; the
        // helper the standard surface calls derives its own frame, so there is no
        // shared Ng to leave lying around unused.
        StringBuilder main = new StringBuilder();
        main.append(surface.normal() == null
                ? "    vec3 N = normalize(v_normal);\n"
                : emitter.statement(top, "    vec3 N = ", surface.normal(), ";\n"));
        main.append("    vec3 V = normalize(u_cameraPos.xyz - v_worldPos);\n"
                + "    float NdotV = max(dot(N, V), 1e-4);\n\n");
        main.append(emitter.statement(top, "    vec4 baseColor = ", surface.baseColor(), ";\n"));
        main.append("    vec3 albedo = baseColor.rgb;\n");
        main.append(emitter.statement(top, "    float metallic = clamp(", surface.metallic(), ", 0.0, 1.0);\n"));
        main.append(emitter.statement(top, "    float roughness = clamp(", surface.roughness(), ", 0.04, 1.0);\n"));
        main.append(emitter.statement(top, "    vec3 emissive = ", surface.emissive(), ";\n"));
        String fragment = profile.header + FRAG_BANNER + FRAG_FRAMEWORK + declarations + FRAG_MAIN_OPEN
                + main + FRAG_MAIN_LIGHTING + accumulate(surface, emitter, top) + FRAG_MAIN_TAIL;
        String vertex = profile.header + VERT_BANNER + VERT_BODY;
        return new Generated(vertex, fragment);
    }

    /**
     * The one line of the light loop a surface can take over, and the reason it is a
     * generated variant rather than a uniform.
     *
     * <p>With no {@link SurfaceOutputs#diffuseResponse()} the standard line is emitted
     * unchanged. With one, the {@code NdotL} has to be distributed across the two lobes
     * so the diffuse half can be scaled by something else, and the diffuse half must be
     * free to be <b>non-zero where {@code NdotL} is zero</b>, which is the whole point:
     * a surface describing light scattered through itself is lit by a lamp behind it, and
     * any formulation that keeps the shared factor outside multiplies that answer by zero.
     *
     * <p>The two forms are equal in real arithmetic and differ in the last bits, so the
     * choice is made here, once, at generation time, not by handing the standard surface
     * a response of {@code NdotL} and hoping the optimiser puts the factor back.
     *
     * <p>The response is written inside the light loop, and so are the locals it alone needs: it
     * may read the loop's {@code L} and {@code NdotL}. What it shares with the outputs above is
     * read from their locals, which the loop sees.
     */
    private static String accumulate(SurfaceOutputs surface, Emitter emitter, Scope top) {
        if (surface.diffuseResponse() == null) {
            return FRAG_ACCUMULATE_STANDARD;
        }
        Scope lights = new Scope(top, null, null, "        ");
        lights.floor = true;
        return emitter.statement(lights, "        float diffuseResponse = ", surface.diffuseResponse(), ";\n")
                + "        Lo += (kd * albedo / PI * diffuseResponse + specular * NdotL)"
                + " * radiance * shadow;\n";
    }

    // ------------------------------------------------------------ IR → GLSL

    // Sharing and loops: ADR 077.
    /**
     * Whether a node used more than once is written once into a local. Package-private
     * and on by default; the device test turns it off to hold the shared form to the pixels the
     * form written in place drew.
     */
    static boolean shareSubexpressions = true;

    /** The generator's own helpers that take derivatives, refused where those are undefined. */
    private static final java.util.Set<String> DERIVATIVE_HELPERS = java.util.Set.of("normalMapToWorld",
            "worldToTangent");

    /**
     * A block of the generated {@code main()}: where a local can be declared and what it sees.
     * A loop's body is a scope that binds the loop's state; the light loop's is a floor, the
     * innermost a local that reads nothing bound can go, because the blocks above it are written.
     */
    private static final class Scope {
        final @org.jspecify.annotations.Nullable Scope parent;
        /** The IR name this block binds (a loop's state), or null. */
        final @org.jspecify.annotations.Nullable String binds;
        /** The GLSL variable it stands for. */
        final @org.jspecify.annotations.Nullable String variable;
        final String indent;
        final java.util.Map<Integer, String> locals = new java.util.HashMap<>();
        final StringBuilder prelude = new StringBuilder();
        boolean floor;

        Scope(@org.jspecify.annotations.Nullable Scope parent, @org.jspecify.annotations.Nullable String binds,
              @org.jspecify.annotations.Nullable String variable, String indent) {
            this.parent = parent;
            this.binds = binds;
            this.variable = variable;
            this.indent = indent;
            this.floor = parent == null;
        }

        @org.jspecify.annotations.Nullable String local(int id) {
            for (Scope at = this; at != null; at = at.parent) {
                String name = at.locals.get(id);
                if (name != null) {
                    return name;
                }
            }
            return null;
        }

        @org.jspecify.annotations.Nullable String variableOf(String name) {
            for (Scope at = this; at != null; at = at.parent) {
                if (name.equals(at.binds)) {
                    return at.variable;
                }
            }
            return null;
        }

        /**
         * Where a node reading {@code reads} is declared: the innermost block binding something
         * it reads, or the floor of this group when it reads nothing bound.
         */
        Scope home(java.util.Set<String> reads) {
            Scope floorScope = null;
            for (Scope at = this; at != null; at = at.parent) {
                if (at.binds != null && reads.contains(at.binds)) {
                    return at;
                }
                if (at.floor) {
                    floorScope = at;
                    break;
                }
            }
            return floorScope != null ? floorScope : this;
        }

        String takePrelude() {
            String text = prelude.toString();
            prelude.setLength(0);
            return text;
        }
    }

    /**
     * Writes the IR as GLSL, numbering it by value so that a node used twice is written once.
     * Two nodes are the same when their kind, their fields and their children's
     * numbers are; a node that is not a literal, a reference or a swizzle of one, used by more
     * than one parent, becomes a local, declared before the first statement that reads it in the
     * innermost block that sees everything it reads. A loop is written as statements and read by
     * its variable.
     */
    private static final class Emitter {
        private final TargetProfile profile;
        private final java.util.IdentityHashMap<Expr, Integer> ids = new java.util.IdentityHashMap<>();
        private final java.util.Map<String, Integer> byKey = new java.util.HashMap<>();
        private final List<Expr> nodes = new java.util.ArrayList<>();
        private final List<Integer> uses = new java.util.ArrayList<>();
        private final java.util.Map<Integer, java.util.Set<String>> reads = new java.util.HashMap<>();
        private int temps;
        private int loops;

        Emitter(TargetProfile profile, SurfaceOutputs surface) {
            this.profile = profile;
            java.util.Set<Integer> counted = new java.util.HashSet<>();
            for (Expr root : new Expr[]{surface.normal(), surface.baseColor(), surface.metallic(),
                    surface.roughness(), surface.emissive(), surface.diffuseResponse()}) {
                if (root != null) {
                    int id = id(root);
                    uses.set(id, uses.get(id) + 1);
                    count(id, counted);
                }
            }
        }

        /** Counts each parent of a node once, however many times the parent itself is used. */
        private void count(int id, java.util.Set<Integer> counted) {
            if (!counted.add(id)) {
                return;
            }
            for (Expr child : children(nodes.get(id))) {
                int childId = id(child);
                uses.set(childId, uses.get(childId) + 1);
                count(childId, counted);
            }
        }

        int id(Expr node) {
            Integer known = ids.get(node);
            if (known != null) {
                return known;
            }
            StringBuilder key = new StringBuilder();
            if (node instanceof Expr.Lit lit) {
                key.append("L").append(java.util.Arrays.toString(lit.values()));
            } else if (node instanceof Expr.Ref ref) {
                key.append("R").append(ref.name()).append(':').append(ref.type());
            } else if (node instanceof Expr.Swizzle swizzle) {
                key.append("W").append(id(swizzle.source())).append('.').append(swizzle.pattern());
            } else if (node instanceof Expr.Binary binary) {
                key.append("B").append(binary.op()).append(id(binary.left())).append(',').append(id(binary.right()));
            } else if (node instanceof Expr.Call call) {
                key.append("C").append(call.function()).append(':').append(call.type());
            } else if (node instanceof Expr.Construct construct) {
                key.append("K").append(construct.type());
            } else if (node instanceof Expr.Raw raw) {
                key.append("X").append(raw.target()).append(':').append(raw.type()).append(':').append(raw.glsl());
            } else if (node instanceof Expr.Loop loop) {
                key.append("P").append(loop.state()).append(':').append(loop.maxIterations());
            } else if (node instanceof Expr.Sample) {
                key.append("S");
            } else {
                throw new IllegalStateException("unknown Expr: " + node);
            }
            if (!(node instanceof Expr.Swizzle) && !(node instanceof Expr.Binary)) {
                key.append('(');
                for (Expr child : children(node)) {
                    key.append(id(child)).append(',');
                }
                key.append(node instanceof Expr.Loop loop && loop.until() == null ? "-" : "").append(')');
            }
            Integer same = byKey.get(key.toString());
            int id;
            if (same != null) {
                id = same;
            } else {
                id = nodes.size();
                nodes.add(node);
                uses.add(0);
                byKey.put(key.toString(), id);
            }
            ids.put(node, id);
            return id;
        }

        /** The IR names a node reads, a loop's own state excepted. */
        java.util.Set<String> reads(int id) {
            java.util.Set<String> known = reads.get(id);
            if (known != null) {
                return known;
            }
            Expr node = nodes.get(id);
            java.util.Set<String> names = new java.util.HashSet<>();
            if (node instanceof Expr.Ref ref) {
                names.add(ref.name());
            }
            for (Expr child : children(node)) {
                names.addAll(reads(id(child)));
            }
            if (node instanceof Expr.Loop loop) {
                names.remove(loop.state());
                names.addAll(reads(id(loop.initial())));
            }
            reads.put(id, names);
            return names;
        }

        /** {@code prefix + node + suffix} as one statement of {@code scope}, after the locals it reads. */
        String statement(Scope scope, String prefix, Expr node, String suffix) {
            String text = emit(node, scope);
            return scope.takePrelude() + prefix + text + suffix;
        }

        String emit(Expr node, Scope scope) {
            if (node instanceof Expr.Lit lit) {
                return literal(lit.values());
            }
            if (node instanceof Expr.Ref ref) {
                String variable = scope.variableOf(ref.name());
                return variable != null ? variable : ref.name();
            }
            if (node instanceof Expr.Swizzle swizzle && trivial(swizzle.source())) {
                return emit(swizzle.source(), scope) + "." + swizzle.pattern();
            }
            int id = id(node);
            String local = scope.local(id);
            if (local != null) {
                return local;
            }
            if (node instanceof Expr.Loop loop) {
                return loop(loop, id, scope);
            }
            if (!shareSubexpressions) {
                return inline(node, scope);
            }
            Scope home = scope.home(reads(id));
            if (uses.get(id) < 2 && home == scope) {
                return inline(node, scope);
            }
            String name = "ir_t" + temps++;
            String text = inline(node, home);
            home.prelude.append(home.indent).append(node.type().glsl).append(' ').append(name)
                    .append(" = ").append(text).append(";\n");
            home.locals.put(id, name);
            return name;
        }

        private static boolean trivial(Expr node) {
            return node instanceof Expr.Lit || node instanceof Expr.Ref
                    || node instanceof Expr.Swizzle swizzle && trivial(swizzle.source());
        }

        /** {@code node} written in place, its children by {@link #emit}. */
        private String inline(Expr node, Scope scope) {
            if (node instanceof Expr.Sample sample) {
                return "texture(" + emit(sample.sampler(), scope) + ", " + emit(sample.uv(), scope) + ")";
            }
            if (node instanceof Expr.Swizzle swizzle) {
                return emit(swizzle.source(), scope) + "." + swizzle.pattern();
            }
            if (node instanceof Expr.Binary binary) {
                return "(" + emit(binary.left(), scope) + " " + binary.op().glsl + " "
                        + emit(binary.right(), scope) + ")";
            }
            if (node instanceof Expr.Call call) {
                return call.function() + "(" + args(call.args(), scope) + ")";
            }
            if (node instanceof Expr.Construct construct) {
                return construct.type().glsl + "(" + args(construct.args(), scope) + ")";
            }
            if (node instanceof Expr.Raw raw) {
                if (raw.target() != profile) {
                    throw new IllegalStateException("raw GLSL node targets " + raw.target()
                            + " but generating for " + profile + " (non-portable escape)");
                }
                return "(" + raw.glsl() + ")";
            }
            throw new IllegalStateException("unknown Expr: " + node);
        }

        private String args(List<Expr> args, Scope scope) {
            return args.stream().map(a -> emit(a, scope)).collect(Collectors.joining(", "));
        }

        /**
         * A loop as statements, in the innermost block that sees what it reads: its state declared
         * and set, then a {@code for} of a constant count that asks {@code until} before each turn
         * and steps the state. Returns the variable that holds its value.
         */
        private String loop(Expr.Loop loop, int id, Scope scope) {
            if (loop.until() != null) {
                String refused = refusedEarly(loop.next());
                if (refused == null) {
                    refused = refusedEarly(loop.until());
                }
                if (refused != null) {
                    throw new IllegalArgumentException("a loop that can end early cannot hold " + refused
                            + ": it is undefined where neighbouring pixels take different turns");
                }
            }
            Scope home = scope.home(reads(id));
            String variable = "ir_s" + loops;
            String counter = "ir_i" + loops++;
            String initial = emit(loop.initial(), home);
            home.prelude.append(home.indent).append(loop.type().glsl).append(' ').append(variable)
                    .append(" = ").append(initial).append(";\n");
            Scope body = new Scope(home, loop.state(), variable, home.indent + "    ");
            String until = loop.until() == null ? null : emit(loop.until(), body);
            String next = emit(loop.next(), body);
            StringBuilder text = new StringBuilder();
            text.append(home.indent).append("for (int ").append(counter).append(" = 0; ").append(counter)
                    .append(" < ").append(loop.maxIterations()).append("; ++").append(counter).append(") {\n");
            text.append(body.takePrelude());
            if (until != null) {
                text.append(body.indent).append("if (").append(until).append(" > 0.0) {\n")
                        .append(body.indent).append("    break;\n")
                        .append(body.indent).append("}\n");
            }
            text.append(body.indent).append(variable).append(" = ").append(next).append(";\n");
            text.append(home.indent).append("}\n");
            home.prelude.append(text);
            home.locals.put(id, variable);
            return variable;
        }

        /** What under {@code node} this generator cannot place in non-uniform control flow, or null. */
        private @org.jspecify.annotations.Nullable String refusedEarly(Expr node) {
            if (node instanceof Expr.Raw) {
                return "raw GLSL, whose text nobody reads for a derivative";
            }
            if (node instanceof Expr.Call call && DERIVATIVE_HELPERS.contains(call.function())) {
                return "the helper " + call.function() + ", which takes derivatives";
            }
            for (Expr child : children(node)) {
                String found = refusedEarly(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }

    /** The children of {@code node}, in the order they are written. */
    private static List<Expr> children(Expr node) {
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

    private static String literal(float[] values) {
        if (values.length == 1) {
            return glslFloat(values[0]);
        }
        StringBuilder sb = new StringBuilder("vec").append(values.length).append('(');
        for (int i = 0; i < values.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(glslFloat(values[i]));
        }
        return sb.append(')').toString();
    }

    /** Formats a float as a GLSL literal, always with a decimal point (e.g. {@code 1.0}). */
    private static String glslFloat(float v) {
        if (v == Math.rint(v) && !Float.isInfinite(v)) {
            return (long) v + ".0";
        }
        return Float.toString(v);
    }

    private GlslCodegen() {
    }

    // ------------------------------------------------------- fixed framework

    /**
     * The sRGB decode as GLSL, the same piecewise curve as {@code Color.srgbToLinear} with the
     * same 0.04045 threshold. One text, spliced into every fragment shader that decodes an
     * authored colour, so the CPU reference and the shaders cannot drift: the 3D surface
     * shaders take it here and the debug-line shader in {@code Gl3DContext} takes the same.
     */
    static final String SRGB_TO_LINEAR = """
            vec3 srgbToLinear(vec3 c) {
                vec3 lo = c / 12.92;
                vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
                return mix(lo, hi, step(vec3(0.04045), c));
            }
            """;

    private static final String FRAG_BANNER = """
            // GENERATED from the neutral shader IR by GlslCodegen. Do not edit by hand.
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset. Metallic-roughness
            // PBR (Cook-Torrance GGX): sRGB→linear decode, light in linear, WRITE linear;
            // the target is scene-referred; the display transform runs in the 2D composite
            // (ADR 004).
            """;

    private static final String FRAG_FRAMEWORK = """
            #define MAX_LIGHTS 8
            #define PI 3.14159265359

            in vec3 v_worldPos;
            in vec3 v_normal;
            in vec2 v_uv;
            in vec4 v_color;
            // Read only by application-supplied surfaces (Material.Surface). Declared
            // unconditionally so there is ONE vertex program for every surface: the
            // vertex stage is identical whatever the fragment does, and a variant of it
            // would be a second thing to keep in step for no gain.
            in vec2 v_uv1;
            in vec4 v_params;
            in vec4 v_params1;

            layout(std140) uniform Frame {
                mat4 u_viewProj;
                vec4 u_cameraPos; // xyz camera position, w unused (exposure lives in the composite: ADR 004)
            };

            struct Light {
                vec4 posRange;
                vec4 dirType;
                vec4 colorIntensity;
                vec4 spot;
            };

            layout(std140) uniform Lights {
                vec4 u_ambient;
                ivec4 u_lightCount;
                Light u_lights[MAX_LIGHTS];
            };

            layout(std140) uniform Material {
                vec4 u_baseColor;
                vec4 u_emissiveHasTex;
                vec4 u_mr;
            };

            uniform sampler2D u_baseColorTex;
            uniform sampler2D u_normalTex;
            uniform mat4 u_shadowVp;
            uniform sampler2D u_shadowMap;
            uniform float u_shadowStrength;
            uniform vec3 u_sh[9];
            uniform vec3 u_skyColor;
            uniform vec3 u_horizonColor;
            uniform vec3 u_groundColor;
            uniform float u_iblIntensity;
            uniform float u_iblEnabled;

            out vec4 o_color;

            """ + SRGB_TO_LINEAR + """

            float distributionGGX(float NdotH, float roughness) {
                float a = roughness * roughness;
                float a2 = a * a;
                float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
                return a2 / max(PI * d * d, 1e-7);
            }

            float geometrySchlickGGX(float NdotX, float roughness) {
                float r = roughness + 1.0;
                float k = (r * r) / 8.0;
                return NdotX / (NdotX * (1.0 - k) + k);
            }

            float geometrySmith(float NdotV, float NdotL, float roughness) {
                return geometrySchlickGGX(NdotV, roughness) * geometrySchlickGGX(NdotL, roughness);
            }

            vec3 fresnelSchlick(float cosTheta, vec3 F0) {
                return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
            }

            // Fraction of the fragment that is lit (1 = fully lit), 3x3 PCF with a slope bias.
            float shadowFactor(vec3 worldPos, float NdotL) {
                vec4 lightClip = u_shadowVp * vec4(worldPos, 1.0);
                vec3 proj = lightClip.xyz / lightClip.w * 0.5 + 0.5;
                if (proj.z > 1.0 || proj.x < 0.0 || proj.x > 1.0 || proj.y < 0.0 || proj.y > 1.0) {
                    return 1.0;
                }
                float bias = max(0.0025 * (1.0 - NdotL), 0.0008);
                vec2 texel = 1.0 / vec2(textureSize(u_shadowMap, 0));
                float lit = 0.0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        float depth = texture(u_shadowMap, proj.xy + vec2(float(dx), float(dy)) * texel).r;
                        lit += (proj.z - bias) <= depth ? 1.0 : 0.0;
                    }
                }
                return lit / 9.0;
            }

            // Image-based lighting: procedural sky radiance + SH diffuse irradiance.
            vec3 skyRadiance(vec3 dir) {
                float t = dir.y;
                vec3 c = t >= 0.0 ? mix(u_horizonColor, u_skyColor, t) : mix(u_horizonColor, u_groundColor, -t);
                return c * u_iblIntensity;
            }

            vec3 shIrradiance(vec3 n) {
                return u_sh[0] * 0.282095
                    + u_sh[1] * (0.488603 * n.y)
                    + u_sh[2] * (0.488603 * n.z)
                    + u_sh[3] * (0.488603 * n.x)
                    + u_sh[4] * (1.092548 * n.x * n.y)
                    + u_sh[5] * (1.092548 * n.y * n.z)
                    + u_sh[6] * (0.315392 * (3.0 * n.z * n.z - 1.0))
                    + u_sh[7] * (1.092548 * n.x * n.z)
                    + u_sh[8] * (0.546274 * (n.x * n.x - n.y * n.y));
            }

            vec3 fresnelSchlickRoughness(float cosTheta, vec3 F0, float roughness) {
                return F0 + (max(vec3(1.0 - roughness), F0) - F0) * pow(1.0 - cosTheta, 5.0);
            }

            // Tangent-space normal to world, with the frame derived per pixel from the
            // screen-space derivatives of the world position and the UVs (Mikkelsen's
            // cotangent frame). No TANGENT vertex attribute: nothing in this toolkit
            // produces one, and a camera-facing billboard has no meaningful stored
            // tangent anyway. dFdx/dFdy are core in GLSL 330 and in GLSL ES 3.00, so
            // this stays inside the ADR 001 subset.
            vec3 normalMapToWorld(vec3 tangentNormal, float scale) {
                vec3 Ng = normalize(v_normal);
                vec3 n = vec3(tangentNormal.xy * scale, tangentNormal.z);
                vec3 dp1 = dFdx(v_worldPos);
                vec3 dp2 = dFdy(v_worldPos);
                vec2 duv1 = dFdx(v_uv);
                vec2 duv2 = dFdy(v_uv);
                vec3 dp2perp = cross(dp2, Ng);
                vec3 dp1perp = cross(Ng, dp1);
                vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
                vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
                // The floor is load-bearing, not defensive: inversesqrt(0) is inf and
                // 0 * inf is NaN, which would poison the whole fragment. With it, a
                // degenerate frame (no UV gradient) leaves T and B at zero and the
                // result collapses to Ng, which is also what scale = 0 produces.
                float invMax = inversesqrt(max(dot(T, T), max(dot(B, B), 1e-12)));
                // The bitangent enters NEGATED, and that sign is the whole convention.
                //
                // B as derived above runs along increasing v, and v runs DOWN an image.
                // Feeding it in unnegated makes the green channel mean "tilt down", which
                // is the DirectX convention, while the scale semantics this material
                // documents are glTF's, and glTF mandates green up. A surface would then
                // be lit from the wrong vertical direction by a toolkit that says it
                // follows the opposite standard.
                //
                // The failure has no visual signature of its own: it does not look like a
                // texture bug, it looks like the lights are in the wrong place. It was
                // found by rendering a hemisphere under a light pointing straight down and
                // measuring which half came out bright.
                return normalize(mat3(T * invMax, -B * invMax, Ng) * n);
            }

            // A world-space direction expressed in the same tangent frame the helper
            // above shades in: x toward the right of the image, y toward its TOP (the
            // same negation, for the same reason), z out of the surface. Read by
            // application-supplied diffuse responses that have to know where a light is
            // relative to the surface rather than merely how much of it lands.
            //
            // The axes are NORMALIZED here and deliberately are not above. That helper
            // divides both by the LARGER of the two, so a surface drawn out along one
            // axis has its normal map drawn out with it: the map is painted in uv space
            // and stretches with the geometry. A direction has no length to stretch:
            // "which way is right" of a quad pulled into a streak is still right, and
            // carrying the stretch into this frame would skew it, making a fixed light
            // appear to swing as the geometry lengthened.
            vec3 worldToTangent(vec3 v) {
                vec3 Ng = normalize(v_normal);
                vec3 dp1 = dFdx(v_worldPos);
                vec3 dp2 = dFdy(v_worldPos);
                vec2 duv1 = dFdx(v_uv);
                vec2 duv2 = dFdy(v_uv);
                vec3 dp2perp = cross(dp2, Ng);
                vec3 dp1perp = cross(Ng, dp1);
                vec3 T = dp2perp * duv1.x + dp1perp * duv2.x;
                vec3 B = dp2perp * duv1.y + dp1perp * duv2.y;
                // The floors are load-bearing for the same reason as above: a degenerate
                // frame must collapse to the geometric normal, not poison the fragment.
                float tl = max(length(T), 1e-6);
                float bl = max(length(B), 1e-6);
                return vec3(dot(v, T / tl), dot(v, -B / bl), dot(v, Ng));
            }
            """;

    private static final String FRAG_MAIN_OPEN = """

            void main() {
            """;

    private static final String FRAG_MAIN_LIGHTING = """

                vec3 F0 = mix(vec3(0.04), albedo, metallic);

                vec3 Lo = vec3(0.0);
                int count = u_lightCount.x;
                for (int i = 0; i < MAX_LIGHTS; i++) {
                    if (i >= count) {
                        break;
                    }
                    Light light = u_lights[i];
                    int type = int(light.dirType.w + 0.5);

                    vec3 L;
                    float attenuation = 1.0;
                    if (type == 0) {
                        L = normalize(light.dirType.xyz);
                    } else {
                        vec3 toLight = light.posRange.xyz - v_worldPos;
                        float dist = length(toLight);
                        L = toLight / max(dist, 1e-4);
                        attenuation = 1.0 / max(dist * dist, 1e-4);
                        float range = light.posRange.w;
                        if (range > 0.0) {
                            float win = clamp(1.0 - pow(dist / range, 4.0), 0.0, 1.0);
                            attenuation *= win * win;
                        }
                        if (type == 2) {
                            float cosAngle = dot(-L, normalize(light.dirType.xyz));
                            float t = clamp((cosAngle - light.spot.y) / max(light.spot.x - light.spot.y, 1e-4), 0.0, 1.0);
                            attenuation *= t * t;
                        }
                    }
                    vec3 radiance = light.colorIntensity.xyz * light.colorIntensity.w * attenuation;

                    vec3 H = normalize(V + L);
                    float NdotL = max(dot(N, L), 0.0);
                    float NdotH = max(dot(N, H), 0.0);
                    float VdotH = max(dot(V, H), 0.0);

                    float D = distributionGGX(NdotH, roughness);
                    float G = geometrySmith(NdotV, NdotL, roughness);
                    vec3 F = fresnelSchlick(VdotH, F0);

                    vec3 specular = (D * G * F) / max(4.0 * NdotV * NdotL, 1e-4);
                    vec3 kd = (vec3(1.0) - F) * (1.0 - metallic);
                    float shadow = 1.0;
                    if (type == 0 && u_shadowStrength > 0.0) {
                        shadow = mix(1.0, shadowFactor(v_worldPos, NdotL), u_shadowStrength);
                    }
            """;

    /**
     * The standard accumulate: the two lobes summed and the whole thing scaled by
     * {@code NdotL} once. Byte-for-byte what this generator has always emitted, and it
     * stays that way for every surface that supplies no diffuse response; the
     * distributed form below is equal to it in real arithmetic and not in IEEE.
     */
    private static final String FRAG_ACCUMULATE_STANDARD = """
                    Lo += (kd * albedo / PI + specular) * radiance * NdotL * shadow;
            """;

    private static final String FRAG_MAIN_TAIL = """
                }

                vec3 ambientLight;
                if (u_iblEnabled > 0.5) {
                    vec3 Fr = fresnelSchlickRoughness(NdotV, F0, roughness);
                    vec3 kdIbl = (vec3(1.0) - Fr) * (1.0 - metallic);
                    vec3 irradiance = max(shIrradiance(N), vec3(0.0));
                    vec3 diffuseIbl = kdIbl * albedo * irradiance;
                    vec3 reflection = mix(skyRadiance(reflect(-V, N)), irradiance, roughness);
                    ambientLight = diffuseIbl + reflection * Fr;
                } else {
                    ambientLight = u_ambient.xyz * albedo;
                }
                vec3 color = ambientLight + Lo + emissive;

                // Linear, premultiplied, scene-referred, with no tonemap and no encode:
                // the target's contract (ADR 004); the composite applies the
                // display transform when this target is drawn.
                float alpha = baseColor.a;
                o_color = vec4(color * alpha, alpha);
            }
            """;

    private static final String VERT_BANNER = """
            // GENERATED from the neutral shader IR by GlslCodegen. Do not edit by hand.
            // PORTABILITY RULE (ADR 001): GLSL 330 ∩ GLSL ES 3.00 subset.
            """;

    private static final String VERT_BODY = """
            layout(location = 0) in vec3 a_pos;
            layout(location = 1) in vec3 a_normal;
            layout(location = 2) in vec2 a_uv;
            // A mesh without COLOR leaves this attribute array disabled and reads the
            // context's current value, which the backend sets to opaque white, so the
            // per-vertex tint is an identity rather than a black multiply.
            layout(location = 3) in vec4 a_color;
            // Absent from most meshes; the backend sets their disabled-array defaults to
            // zero, which is why a surface should make zero its identity.
            layout(location = 4) in vec2 a_uv1;
            layout(location = 5) in vec4 a_params;
            layout(location = 6) in vec4 a_params1;

            layout(std140) uniform Frame {
                mat4 u_viewProj;
                vec4 u_cameraPos;
            };

            uniform mat4 u_model;
            uniform mat3 u_normalMatrix;

            out vec3 v_worldPos;
            out vec3 v_normal;
            out vec2 v_uv;
            out vec4 v_color;
            out vec2 v_uv1;
            out vec4 v_params;
            out vec4 v_params1;

            void main() {
                vec4 world = u_model * vec4(a_pos, 1.0);
                v_worldPos = world.xyz;
                v_normal = u_normalMatrix * a_normal;
                v_uv = a_uv;
                v_color = a_color;
                v_uv1 = a_uv1;
                v_params = a_params;
                v_params1 = a_params1;
                gl_Position = u_viewProj * world;
            }
            """;
}
