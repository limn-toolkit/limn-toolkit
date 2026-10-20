# ADR 077: A surface may iterate, and a node used twice is computed once

- **Status:** Accepted and implemented, 2026-10-20. Built: loops of one value or several, and
  common subexpressions shared by the GLSL generator. Not built: a public let and helper
  functions. The owner's decision 29 of the 2026-10-19 audit asked for loops, locals and helper
  functions in the public shader IR, and sharing in the generator, before 1.0, "but verify
  everything stays compatible with the minimum shader profile Limn uses"; section 4 gave the
  reasons for building only the loop, and the owner's decision 39b (2026-10-20, section 7)
  confirmed them: no let and no helper functions before 1.0, a 1.x minor release free to add
  either as new node types, and a loop that carries several values. Section 1 is the profile,
  read from the sources; section 3 is the loop.
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
| Identifiers | `gl_` and any `__` reserved | the same | generated names start with `ir_`, which an application's names may not; a loop's state is named by the factory (section 3.4) |
| Iteration limits | implementation-defined; a long draw is a driver timeout | the same; WebGL-class implementations lose the context | a cap of 1024 turns a fragment across the whole surface (section 3.2) |
| Precision | not needed | float precision has no default in a fragment shader; `highp` required in 3.00 | the ES header gives both, as before |

## 2. Decision

1. **Sharing.** The generator numbers the IR by value: two nodes are the same when their kind,
   their fields and their children's numbers are. A node that is not a literal, a reference or a
   swizzle of one, and is used by more than one parent, is written once into a local and read by
   name. The local goes in the innermost block that sees everything it reads, and before the first
   statement that uses it. This alone removes the exponential growth.
2. **Iteration.** One new node, `Expr.Loop`, a value computed by a bounded loop: a state of a
   float or vector type, its initial value, a maximum count, the next state, and an optional
   `until` that ends the loop early, built by `Expr.Loop.of`, which hands the turn its state as a
   node (amended 2026-10-20, section 7).
3. **Neither a let nor a function** becomes a public type (section 4). A 1.x minor release may
   add either as a new node type: `Expr` already tells a `switch` over it to keep a `default`.
4. **A loop carries several values** when a surface needs more than a vector holds, as a volume
   marched along a ray does with the colour it gathers and the distance it has gone (amended
   2026-10-20, section 7).

## 3. `Expr.Loop`

```java
Expr.Loop.of(initial, 256,                // initial value, at most 256 turns
        z -> next(z),                     // the state after one turn, given the state
        z -> until(z));                   // optional: ends the loop where it is above zero
```

### 3.1 What it means

Each turn first asks `until` of the state as it stands; above zero ends the loop, otherwise the
state becomes `next`. The loop's value is the state when it ends: after `maxIterations` turns, or
at the turn whose `until` was above zero. `next` and `until` are given the state as a node, an
`Expr.Ref` of the initial value's type under a name the factory chooses (section 3.4); outside its
loop it means nothing, and a surface that reads it there is refused. Generated:

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

`MAX_ITERATIONS` is **1024**, and it bounds every turn a pixel takes across a whole surface
(amended 2026-10-20, section 7; it bounded the product along one nesting, and loops side by side
were each counted alone). Loops side by side, and a loop in another's initial value, add their
turns; a loop in another's turn that reads the other's state multiplies by that loop's count, so
a loop of 32 turns may hold one of 32, not one of 64 nor two of 32 side by side; a loop in a turn
that reads nothing of that loop's state is computed once, before it, and counted once (amended
2026-10-20, review 9), as is a loop read twice, or two built alike. The constructor refuses a count below 1 or above the cap and a
loop that, with what it holds, passes it; `SurfaceOutputs` refuses a surface that passes it,
counting its diffuse response once for each of the eight lights a pass shades with at most
(`RenderPass.addLight`: "up to 8; extras are ignored"). 1024 is four times what the
Showcase's Mandelbrot uses and what a smooth-coloured escape-time fractal wants on screen at
single precision, where the picture runs out of precision long before it runs out of turns. The
cap bounds turns, not the cost of a turn: a surface with a large body still costs what it costs,
as a surface without a loop does, but no surface asks a pixel for more than 1024 turns.

### 3.3 What an early-ending loop may not hold

A loop with an `until` ends at different turns in neighbouring fragments, which makes its body
non-uniform control flow, where both profiles leave derivatives and implicit-LOD texture lookups
undefined (section 1). So its `next` and `until` may not hold an `Expr.Sample`, a call to `dFdx`,
`dFdy` or `fwidth` (the constructor refuses them), a call to a framework helper that takes
derivatives (`normalMapToWorld`, `worldToTangent`; the generator refuses them, since the IR
does not know the framework's helpers), or an `Expr.Raw`, whose text nobody reads (the
generator refuses it). A loop without an `until` runs every turn in every fragment and may hold
all of them.

*Amended 2026-10-20 (section 7):* the constructor also refuses the `texture…` functions that pick
their own level of detail, whatever node reaches them, and the generator accepts there only the
calls it knows to be defined, refusing every other name.

### 3.4 Names

*Amended 2026-10-20 (section 7, the owner's decision 38 and review 9).* The state is not named
by the application. `Expr.Loop.of` hands `next` and `until` each value of the state as a reference
under a name no other loop has, then names the loop for good `ir_state<level>_<place>`: its level
is one more than the highest level of any loop in its turn (0 for a turn that holds none), and the
place is the value's in the state. A loop in another's turn, built there or apart, on any thread,
so always has names other than the outer loop's, and may read the outer state by the outer names;
names are a function of the loop alone, so two loops built alike are equal. A surface input may
not start `ir_`, so no input can be read where the state is meant, and the constructor accepts no
other names. The constructor checks every reference to a value of the state in `next` and `until`
has that value's type, and `SurfaceOutputs` refuses a state read outside its loop. A loop built
by hand may still reuse an enclosing loop's names; in its turn they are its own. The generator
names the variables itself (`ir_s…`).

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
- *Confirmed 2026-10-20 (decision 39b).* Asked what a let and helper functions would bring, with
  examples, the owner kept both out of 1.0: a let is a Java variable plus the generator's sharing,
  and helper functions only make the text, and a driver's compile, smaller, with no effect a
  surface cannot already have. Either can come in a 1.x as a new node type.
- **A loop with a bound read at run time** (a uniform). Rejected: the cap could then be passed at
  run time and refused nowhere. A surface that wants fewer turns ends early with `until`, reading
  its uniform there.
- **`while` with no bound.** Rejected: it is the one construct that can hang a GPU, the reason the
  decision asks for a cap.
- **Sharing only nodes the application shares by identity** (the same Java object). Rejected: the
  IR is a value, and two nodes built apart from equal parts, as the noise in the Showcase builds
  its corners, are the same computation. (Amended 2026-10-20, section 7: the records are equal
  by their parts too, a literal by its values.)
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

- **2026-10-20, review: every walk visits a node once.** The generator's own check of what an
  early-ending loop holds followed each child as a tree, and `Expr.Binary.type()` asked each
  operand's type twice: a turn of k unrolled steps of z ↦ z² + c, each reading the step before six
  times, took 1.2 s to generate at k = 10, 43 s at 12 and minutes beyond, inside the frame that
  first drew the surface, and forty squarings z ↦ z·z hung the loop's constructor on the type of
  its next state. Both now remember the nodes they have been through (by identity, as the nesting
  check and the constructor's own check already did); k = 12 to 20 generate in milliseconds
  together, and so does the forty-squaring chain.
- **2026-10-20, review: a local is read only where its names mean the same.** Sharing numbered a
  reference by its name, and a local was reused from any enclosing block. A loop's state named
  like an input of the surface (a `Value` called `z`) or a framework varying (`v_uv`), with a node
  over that name written outside the loop first, gave the loop's body the local computed from the
  uniform where the IR meant the state: it compiled and drew wrong pixels, while the form written
  in place was right. A local is now reused only when no block between it and the reader binds a
  name the node reads; otherwise the node is written afresh, in the block whose binding it reads.
  The device test holds such a surface, shared and in place, to the same pixels.
- **2026-10-20, review: what an early-ending loop may call is a list of what is allowed.** Section
  3.3 refused `Expr.Sample` and three derivatives by name, but `Expr.Call` reaches every built-in:
  `Call("texture", …)`, `textureProj`, `textureOffset`, `textureProjOffset` and the framework's
  `shadowFactor` (an implicit-level `texture` inside) were accepted in a loop that can end early,
  where GLSL ES 3.20 §8.9, like 3.00 and 3.30, leaves them undefined. The constructor now also
  refuses every `texture…` function but the ones told their level or gradients and `textureSize`,
  and every name beginning `dFd` or `fwidth`. The generator, which knows the whole name space a
  surface can call (no surface declares a function), accepts there only the built-ins of both
  profiles defined in non-uniform control flow and its own helpers that are arithmetic alone,
  and refuses any other name, so a function it has not read is no promise.
- **2026-10-20, review: a sampler is never a local.** Sharing and hoisting put any node into a
  typed local, a sampler-typed `Expr.Raw` or `Expr.Call` included, and both profiles declare an
  opaque type only as a uniform or a parameter (GLSL ES 3.20 §4.1.7, GLSL 3.30 §4.1.7): a surface
  that read a sampler through raw text twice, which linked before sharing, stopped linking, and
  the link was retried every frame. A node of an opaque type is now written where it is read,
  like a reference; the device test compiles that surface on the GPU and the software renderer.
- **2026-10-20, review: the IR is a value in Java too.** The class said two nodes are the same
  computation when their parts are, but `Expr.Lit` was a record over a `float[]`, equal only to
  itself and handing out its own array, so no record holding a literal equalled its twin. A
  literal now copies its values in and out and compares and hashes them; `Call` and `Construct`
  copy their argument lists; and every node with children compares and hashes through a walk
  that visits a shared node once, because a record's own `hashCode` follows each child as a tree:
  forty squarings z ↦ z·z, 2^40 paths, never finished. Two such chains built apart compare and
  hash in milliseconds. `toString` is still a record's, a tree as long as every path.
- **2026-10-20, the owner's decision 37: the cap is the whole surface's.** The review found the
  cap bounded the product along one nesting while the Javadoc promised no surface could hang the
  GPU: 64 loops of 1024 chained through their initial values were accepted (65,536 turns a
  pixel), and so was a loop of 8 holding eight loops of 128 (8,192). The owner chose a budget for
  the whole surface over rewording the promise. `Loops` counts it, sharing counted once by value,
  and three places hold it: the loop's constructor (the loop with what it holds, the second of
  those 64 is refused at 2,048), `SurfaceOutputs` at construction (every output, the diffuse
  response for one light), and the generator (the response once for each of its eight lights).
  `SurfaceOutputs.turnsPerPixel(lights)` is new and public, so a backend with its own light count
  can hold a surface to it. The device test's two loops, 50 and 1024, became 50 and 974.
- **2026-10-20, the owner's decision 38: a loop is built by a factory that hands it its state.**
  The review found the state reached by a free-name `Ref` whose type nothing checked: a `vec2`
  reference to a `vec4` state was accepted and failed to compile at draw time, a reference outside
  its loop was written as a bare name, and binding by string let a state take the name of an
  input. `Expr.Loop.of(initial, maxIterations, next)` and `of(initial, maxIterations, next, until)`
  take `UnaryOperator<Expr>` for the turn and hand it the state, so a surface can neither misname
  nor mistype it. The `Loop` record stays the IR node, and its `state` component stays public:
  a record cannot hide a component or its canonical constructor, and the IR has to say which
  references a loop binds. What changed is who chooses the value: the factory names it by
  nesting depth (`ir_state0`, `ir_state1`, …), deterministically, so a method that builds a loop
  twice at one depth builds one value the generator computes once, and the constructor accepts
  no other name. A loop may now hide an enclosing state of the same name, which a loop built
  apart and set in another's turn does; the review's fix that reads a local only where its names
  mean the same is what keeps that right, and the device test draws such a surface shared and in
  place to the same pixels. `Material.Surface` inputs may not start `ir_`.
- **2026-10-20, a node writes itself out once.** `toString` was still a record's, a tree as long
  as every path, so logging forty squarings z ↦ z·z never ended. A node with children and more
  than one parent is now written the first time as `#n=…` and as `#n` after that; a node nothing
  shares reads exactly as a record's `toString` did.
- **2026-10-20, the diffuse response is counted for eight lights when the surface is built.**
  Decision 37 first counted it for one light in `SurfaceOutputs` and again for eight in the
  generator, through a new public `SurfaceOutputs.turnsPerPixel(lights)`, so a looping response
  could pass construction and be refused at the first draw. `RenderPass.addLight` already promises
  "up to 8; extras are ignored", so `SurfaceOutputs` now counts eight at construction, the public
  method and the generator's second check are gone, and a backend test holds the generator's light
  loop to the same eight.
- **2026-10-20, the owner's decision 39b: a loop carries several values.** The owner confirmed no
  public let and no helper functions before 1.0 (a let is a Java variable plus sharing; functions
  only shrink the text and the compile, with no new effect; a 1.x may add either as a new node),
  and asked instead for what neither gives: a state of more than one vector. A volume marched
  along a ray gathers a colour and alpha (a `vec4`) and tracks the distance it has gone (a
  `float`), five floats that no single state held. `Expr.Loop.of(List<Expr> initial,
  maxIterations, next, until)` hands the turn the state as a list of nodes, one per value, each
  of its initial value's type, and takes the next values back as a list; the one-value form is
  unchanged. The record now holds `states`, `initial` and `next` as lists and an `index`: the
  node is one value of the loop, the first for the factory's, and `loop.value(1)` reads the
  second, the same loop computed once and counted once however many of its values are read
  (as the whole-surface budget counts and the generator writes it). Each value is checked for its
  type, in `next` and wherever it is read; the generator writes one local per value, and a turn
  of several values computes every next value before it assigns any, so each steps from the state
  before the turn (a loop that swaps two values is the test). The device tests march a ray
  through a sphere of constant density, gathering colour and alpha and ending once alpha passes
  0.99, on the GPU and on Apple's software renderer, and hold it to the same march in Java; the
  density is worked out, never sampled, so the early end leaves nothing undefined.
- **2026-10-20, review 9: a loop's names follow what it holds, not the thread that built it.**
  `Loop.of` named a state by a per-thread count of the loops being built around it. A loop built
  for another's turn on a worker thread (a future, a parallel stream) counted 0, took the outer
  loop's names, and the outer state it read became its own: `t + s` turned into `t + t`, the
  inner loop no longer read the outer state, was computed once before the outer loop, and a
  surface meant to draw 27 drew 1, with no error. The turn now sees the state under provisional
  names no other loop has; once it is built, the loop is named by its level, one more than any
  loop anywhere in its turn, and the provisional names are rewritten. A loop built apart and set
  in another's turn has names of its own too, so the hiding of section 3.4 now arises only in
  hand-built records. The device test builds the inner loop on a worker thread and draws 27.
- **2026-10-20, review 9: a count numbers the IR once.** Each loop's constructor counted the
  whole nest below it with a fresh memo and compared the loops in it by hashing each as a tree,
  so a chain of k loops built through their initial values cost k³: 2.7 s at 400 one-turn loops.
  The count now numbers the nodes by value once per count, as the generator does, and reads each
  loop's turns from its number; a thousand such loops, the most the budget allows, build and pass
  `SurfaceOutputs` in about a third of a second. A record cannot cache a count in a field it does
  not declare, so each constructor still counts the nest below it, once.
- **2026-10-20, review 9: the budget counts what the generator runs.** The count multiplied every
  loop in another's turn by that loop's count, while the generator computes a loop that reads
  nothing of the outer state once, before the outer loop, as section 3.1 promises: a loop of 4
  around an invariant loop of 512 runs 516 turns a pixel and was refused as 2,048, and an equal
  loop of 300 in the initial value and in `until`, written once, counted 1,200. The count now
  reads which names each loop reads, as the generator places it by, and counts a turn's loop that
  reads none of the state once, with the initial value's; a generator test holds the invariant
  loop before the outer one, where it is counted.
- **2026-10-20, review 9: no `ir_` name reaches the generator unbound.** `SurfaceOutputs` refused
  only a state name read outside its loop, so a reference to `ir_s0` beside a loop was written
  bare and read that loop's variable. It now refuses any name starting `ir_` that no loop binds:
  those are a loop's states or the generator's own variables, and inputs may not take them.
- **2026-10-20, review 9: the factory's generic shapes, decided before 1.0.** `Loop.of` took
  `List<Expr>` and `UnaryOperator<Expr>` / `UnaryOperator<List<Expr>>`, so a list a caller held as
  `List<Expr.Lit>`, or a function typed to return `Expr.Binary`, did not compile, and a function
  type is frozen with the method's descriptor. The forms are now `Expr initial` with
  `Function<? super Expr, ? extends Expr>` for `next` and `until`, and `List<? extends Expr>
  initial` with `Function<? super List<Expr>, ? extends List<? extends Expr>> next` and
  `Function<? super List<Expr>, ? extends Expr> until`: the widest that still hands the turn a
  `List<Expr>` of the state. A lambda reads as before.
