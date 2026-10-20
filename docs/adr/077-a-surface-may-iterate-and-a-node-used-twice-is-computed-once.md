# ADR 077: A surface may iterate, and a node used twice is computed once

- **Status:** Accepted and implemented, 2026-10-20. The owner's decision 29 of the 2026-10-19 audit: loops,
  locals and helper functions in the public shader IR, and common subexpressions shared by the
  GLSL generator, before 1.0, "but verify everything stays compatible with the minimum shader
  profile Limn uses". Section 1 is that profile, read from the sources; section 3 is the smallest
  surface that does what the decision asks, and section 4 says which of the three named
  constructs it builds and why the other two are not public types.
- **Date:** 2026-10-20
- **New public API:** `Expr.Loop` in `limn.render3d.shader`, with `Expr.Loop.MAX_ITERATIONS`.
- **Compatibility:** additive. `Expr` is a sealed interface and gains a permitted record, so an
  exhaustive `switch` over it stops compiling; its Javadoc now says what `ShaderType` and
  `Expr.Op` already said, that a 1.x minor release may add node types and a switch needs a
  `default`. A surface without a loop generates the same statements as before except that a node
  it uses more than once is written once into a local; the standard surface's one such node is its
  base-colour texel (section 5).

## 0. What this rests on

Read at `569aea70` (`claude/a4-viewport3d`).

- `TargetProfile` has two constants, `GLSL_330` (`#version 330 core`) and `GLSL_ES_300`
  (`#version 300 es` with `precision highp float; precision highp int;`), and documents that they
  "share one body (the GLSL 330 ∩ ES 3.00 subset) and differ only in the version/precision
  header".
- ADR 001, rule 3: every shader is written in GLSL 330 using only the subset trivially portable to
  GLSL ES 3.00. ADR 003 §0(b): the 3D surface shaders already compile to `#version 300 es` from the
  IR, with a test that the two profiles differ only in the header.
- `Gl3DContext.ensureSurface` and `ensurePbr` generate for `GLSL_330` only: the ES profile has no
  context to run on today (ADR 003 is deferred), so what keeps it honest is the generator's tests.
- `GlslCodegen.emit` writes the IR as one expression per output, a tree: a node reachable twice is
  written twice. `Expr.Raw` is spliced as `(text)` inside an expression, so it can hold no
  statement. Neither can express a loop, and an unrolled iteration that reads its value more than
  once grows as a power of the number of steps: z ↦ z² + c reads z four times a step, so nine
  steps write it 262,144 times.

## 1. The minimum profile, exactly

The minimum is the intersection of GLSL 3.30 core and GLSL ES 3.00 (3.00.6). What this record
relies on, and the rule it takes from each:

| Construct | GLSL 3.30 | GLSL ES 3.00 | What the generator does |
|---|---|---|---|
| `for`, `while`, `do` loops; `break`, `continue` | yes | yes, in general form; ES 1.00's Appendix A limits (a constant-bounded `for` with one int index) are gone in 3.00 | only `for (int i = 0; i < N; ++i)` with a constant `N` and an optional `break`, the shape every profile and every driver accepts |
| Local variables anywhere in a block | yes | yes | locals named `ir_…`, declared at the point of first use, in the innermost block that sees everything they read |
| `int` | yes | yes, `highp` from the ES header | an `int` only as the loop counter |
| Implicit `int` → `float` conversion | yes | **no** | never relied on: every float literal is written with a point, the counter never meets a float |
| User functions | yes | yes, declared at global scope before use; no recursion, not even static, in either | none generated (section 4) |
| Derivatives (`dFdx`, `dFdy`, `fwidth`) and implicit-LOD `texture` | defined in uniform control flow | the same: undefined in non-uniform control flow | a loop that can end early may not sample, take a derivative, call a framework helper that does, or splice raw text (section 3.3) |
| Identifiers | `gl_` and any `__` reserved | the same | generated names start with `ir_`, which an application's names and a loop's state may not |
| Iteration limits | implementation-defined; a long draw is a driver timeout | the same; WebGL-class implementations lose the context | a cap of 1024 turns a fragment, nested loops counted together (section 3.2) |
| Precision | not needed | float precision has no default in a fragment shader; `highp` required in 3.00 | the ES header gives both, as before |

## 2. Decision

1. **Sharing.** The generator numbers the IR by value: two nodes are the same when their kind,
   their fields and their children's numbers are. A node that is not a literal, a reference or a
   swizzle of one, and is used by more than one parent, is written once into a local and read by
   name. The local goes in the innermost block that sees everything it reads, and before the first
   statement that uses it. This alone removes the exponential growth.
2. **Iteration.** One new node, `Expr.Loop`, a value computed by a bounded loop: a named state of
   a float or vector type, its initial value, a maximum count, the next state, and an optional
   `until` that ends the loop early.
3. **Neither a let nor a function** becomes a public type (section 4).

## 3. `Expr.Loop`

```java
new Expr.Loop("z", initial, 256,          // state name, initial value, at most 256 turns
        next,                             // the state after one turn; reads Ref("z")
        until);                           // optional: ends the loop where it is above zero
```

### 3.1 What it means

Each turn first asks `until` of the state as it stands; above zero ends the loop, otherwise the
state becomes `next`. The loop's value is the state when it ends: after `maxIterations` turns, or
at the turn whose `until` was above zero. Inside `next` and `until` the state is
`new Expr.Ref(state, initial.type())`; outside them the name means nothing. Generated:

```glsl
vec4 ir_s0 = <initial>;
for (int ir_i0 = 0; ir_i0 < 256; ++ir_i0) {
    <locals the turn reads>
    if (<until> > 0.0) {
        break;
    }
    ir_s0 = <next>;
}
```

`until` is a float rather than a boolean because the IR has no boolean type and no comparison,
and this needs neither: "above zero" is a subtraction away from every threshold
(`dot(z, z) - 256.0`). An iteration count is part of the state when a surface wants it, the
fractal's smooth colouring does: one more component in a `vec4`.

A part of `next` or `until` that does not read the state is computed once, before the loop.

### 3.2 The cap

`MAX_ITERATIONS` is **1024**, and it bounds the product along a nesting: a loop of 32 turns may
hold one of 32, not one of 64. The constructor refuses a count below 1 or above the cap, and a
nesting whose product passes it. 1024 is four times what the Showcase's Mandelbrot uses and what
a smooth-coloured escape-time fractal wants on screen at single precision, where the picture
runs out of precision long before it runs out of turns. The cap bounds turns, not the cost of a
turn: a surface with a large body still costs what it costs, as a surface without a loop does,
but no surface can ask a fragment for an unbounded amount of work.

### 3.3 What an early-ending loop may not hold

A loop with an `until` ends at different turns in neighbouring fragments, which makes its body
non-uniform control flow, where both profiles leave derivatives and implicit-LOD texture lookups
undefined (section 1). So its `next` and `until` may not hold an `Expr.Sample`, a call to `dFdx`,
`dFdy` or `fwidth` (the constructor refuses them), a call to a framework helper that takes
derivatives (`normalMapToWorld`, `worldToTangent`; the generator refuses them, since the IR
does not know the framework's helpers), or an `Expr.Raw`, whose text nobody reads (the
generator refuses it). A loop without an `until` runs every turn in every fragment and may hold
all of them.

### 3.4 Names

The state is named by the application, so the constructor holds it to an identifier both
profiles accept and keeps it out of the generator's: letters, digits and `_`, a letter first, no
`__`, not `gl_…`, not `ir_…`. A loop inside another may not reuse an enclosing loop's name, which
would make one reference mean two values. The generator names the variable itself (`ir_s…`), so
two sibling loops may both call their state `z`.

## 4. Alternatives

- **A public `Let` node** (bind a value to a name, read it below). Rejected: with sharing in the
  generator, every value used twice is already computed once, so a `Let` would be a second way to
  say what a reused Java variable already says, and one that a surface could get wrong (a name
  read outside its binding). What a `Let` gives beyond that, a value that changes, is what the
  loop's state is for.
- **A per-surface block of helper functions.** Rejected in both of its forms. As text it is
  `Expr.Raw` at file scope: one profile's GLSL, a name space shared with the framework's helpers,
  and nothing the generator can check, which is what `Material.Raw` already is. As IR it is a
  function type with parameters, calls and a recursion rule, a second language inside the first;
  and a Java method that builds the IR is already a function, which sharing makes as cheap to
  call twice as once. The decision's purpose, iterative surfaces, does not need either.
- **A loop with a bound read at run time** (a uniform). Rejected: the cap could then be passed at
  run time and refused nowhere. A surface that wants fewer turns ends early with `until`, reading
  its uniform there.
- **`while` with no bound.** Rejected: it is the one construct that can hang a GPU, the reason the
  decision asks for a cap.
- **Sharing only nodes the application shares by identity** (the same Java object). Rejected: the
  IR is a value, and two nodes built apart from equal parts, as the noise in the Showcase builds
  its corners, are the same computation. (A literal holds an array, so the records themselves
  are not equal by their parts; the generator compares the values.)
- **Leaving the standard surface's text alone** by sharing only in application surfaces.
  Rejected: one generator, one rule. The one change it makes there (section 5) is a texel sampled
  once instead of twice, measured to draw the same pixels.

## 5. What changes for an existing surface

- The standard surface reads its base-colour texel twice (colour and alpha); it is now written
  once, `vec4 ir_t0 = texture(u_baseColorTex, v_uv);`, and the golden fragment moves by that line
  and its two uses. Nothing else in it is used twice.
- Every other line of a surface without a loop is what it was. A response to the lights
  (`diffuseResponse`) is still written inside the light loop, with its own locals there.
- `GlslCodegen` keeps a package-private switch that writes every node in place, as before, for the
  test that holds the two forms to the same pixels.

## 6. Tests

- Generator (no GL): a node used twice is written once; nine unrolled steps of z² + c, which wrote
  z 262,144 times, write it once a step; a loop is a bounded `for` with its exit; a loop-invariant
  part is computed before the loop; locals land in the light loop when they read the light; a
  surface with loops is the same text in both profiles after the header; a framework helper that
  takes derivatives is refused in an early-ending loop.
- IR (no GL): the cap at 1 and 1024, the nesting product, the names, the types, and what an
  early-ending loop refuses.
- Device (`HeadlessGl`, on the GPU and on Apple's software renderer): every generated surface
  compiles and links; a loop's value reaches the pixel it should, with and without an early end;
  the standard surface, a surface of the dissolve's shape and one of the molten rock's draw
  the same pixels with sharing as without. No GLSL ES 3.00 compiler exists in the tests (no ES
  context on these machines, and no offline validator in the build); the ES profile is held by the
  generator test that its body is the 330 body.

## 7. Notes

- **2026-10-20, built.** Every test of section 6 passes on this Mac's GPU (Apple M5 Max, GL 4.1
  over Metal) and the device tests also on Apple's software renderer (`-Dlimn.test.softwareGl=true`,
  and through `HeadlessGl.onSoftwareRenderer` in the same run). The shared and in-place forms of
  the three surfaces drew the same pixels to the last bit on the GPU, in a half-float target.
  The nesting check is memoized by node and by the names around it, so a chain built by reusing
  its last step, as the unrolled test builds nine, is walked once per nesting rather than once per
  path. Sharing is a generator rule and no IR type: the sealed `Expr` gained one record, `Loop`.
- **2026-10-20, measured** (GPU time per frame, median of 300 after 20 to warm, three rounds that
  agree within 0.01 ms; 4x MSAA). On this Mac's GPU sharing changes nothing: the Showcase's molten
  surface costs 0.445 ms shared and 0.447 ms in place at 1826x1026, the driver's compiler already
  sharing what the text repeated. On Apple's software renderer it is the difference that matters:
  1.32 s a frame shared against 5.43 s in place at 913x513 (0.38 s against 1.56 s at 456x256),
  four times less, because that compiler computes what it is given. The Showcase's Mandelbrot, up
  to 256 turns a pixel, costs 0.32 ms at its widest and 0.39 ms at its deepest at 1826x1026 on the
  GPU against 0.26 ms for the same panel in the built-in material, and 1.5 s to 3.0 s a frame at
  913x513 on the software renderer. The program count stayed at one over every run.

