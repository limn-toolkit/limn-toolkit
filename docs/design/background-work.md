# Background work

Background for `limn.concurrent`: `Ui.post`, `Ui.async` and `Ui.work`, and the rule a facade that
blocks has to satisfy before it ships.

## Everything lands on the one thread, and why that is not negotiable

Limn has a single UI thread (the process main thread), and every widget mutation checks it. There
is no lock around the scene graph, no synchronised setter, no copy-on-write anywhere in the tree,
and no `invokeLater` bolted onto the side. Layout reads sizes that a paint is about to use; a paint
reads a tree that an event handler just changed; the backend hands input in on that same thread. The
thread confinement is what makes all of that correct for free, and the moment one background thread
writes one field of one widget, none of it is.

So the runtime's whole job is to be the one place that crossing happens. A background body produces
a value; the value is put on the UI queue; the frame loop drains the queue and runs the callback.
Every callback in this package (`thenAccept` on an `Ui.async` stage, `onSuccess`, `onFailure`,
`onProgress`, the `deliverIf` predicate) runs there. A handler may therefore touch widgets with no
ceremony at all, which is the point: an API that made you think about it would be an API people work
around.

Two things do **not** run there, on purpose. The body itself, obviously. And `onDiscarded`, for the
reason in "The discard trap" below.

## Which of the three to reach for

**`Ui.post(action)` / `Ui.postDelayed(action, ms)`**: you already have the answer and you are on
the wrong thread. A decoder thread that finished a picture, a native callback, a watchdog. No work
is being scheduled; a closure is being moved to the frame loop. `postDelayed` is also the honest way
to say "later this frame or the next", where a widget would otherwise recurse into layout.

Running a task buys **no frame**, and that applies to every callback in this package: a `Ui.post`
action, an `Ui.async` dependent, an `onSuccess`. A task that mutates widget or scene state has to
invalidate what it touched, and almost nobody has to think about it, because every widget setter and
every tree change already does. What does not is a task that writes a field behind a setter's back,
or that changes a value some custom `onPaint` reads from outside the tree; that one calls
`invalidate()` itself or its change is not painted. The upside is what makes `postDelayed` worth
reaching for at all: a poll that re-reads a value and finds it unchanged costs a wake-up and nothing
else, where a ticker asks for a frame every frame it stays registered.

**`Ui.async(supplier)`**: bounded work whose answer is always wanted, with nothing to withdraw and
nobody to report progress to. It returns a `CompletionStage` whose default async executor is the UI
thread, so it composes with other stages and with anything that already speaks
`CompletableFuture`. It is fewer lines than a job and it stays the right answer for the "click,
fetch, set a label" shape.

**`Ui.work(body)`**: anything with a lifecycle. Reach for it when any of these is true:

- the request can be **superseded** (search as you type, a preview that follows a selection, a
  thumbnail for a row that scrolls away): a job can be cancelled and a cancelled job delivers
  nothing, so the view ends up showing the answer to the question last *asked* rather than whichever
  answer finished last;
- it is slow enough to want a **progress bar**;
- the requester can **go away** while it is in flight, and `deliverIf` refuses the delivery instead of
  writing into a dismantled view;
- the body returns a **resource** (`onDiscarded` closes what nobody received);
- the body throws **checked exceptions**: `Body.run` declares `throws Exception`, where a
  `Supplier` forces a wrap-and-rethrow prologue at every loader.

A useful tell: if you are about to write a `boolean stale` field next to the call, or an `if
(!isShowing()) return;` at the top of a callback, the job already has the feature you are
reimplementing.

## What is ordered, and what is not

Within one job:

- every delivery runs on the UI thread, on a frame, in the order it was posted;
- progress values arrive in the order they were reported;
- **the last value reported before the body returns is always delivered, and always before
  `onSuccess`**, so a bar finishes at its true final value and does not stick one step short;
- at most one terminal callback runs: `onSuccess` or `onFailure`, never both and never twice;
- `onDiscarded` sees a value at most once, and only a value that was produced and then refused.

What is **not** ordered, and has bitten people writing this kind of code elsewhere:

- **`isDone()` does not mean the callbacks have run.** It flips on the worker thread the instant the
  body returns; the delivery is posted and runs on a later frame. Polling `isDone()` and then
  reading the field your `onSuccess` sets is a race with a comfortable-looking spelling. If you need
  to know that the result landed, do the work in `onSuccess`.
- **Progress is coalesced, so most reported values are never delivered.** Do not accumulate on the
  receiving side and do not treat a delivery as an event; treat it as "the newest number I have".
  And by the time one arrives, the body has already moved past it.
- **There is no ordering between jobs.** Two started in sequence complete in whatever order the pool
  and the work give them. If a later request must win, hold the earlier job and cancel it.
- **`cancel()` from off the UI thread guarantees less** than from on it. The "nothing is delivered
  after `cancel()` returns" guarantee is about a cancel made on the UI thread; from another thread a
  delivery may already be executing when the flag is set. Cancel from the UI thread, which is where
  the reason to cancel comes from anyway.
- **`cancel()` does not free the worker.** The body runs until it returns or notices
  `Progress.isCancelled()`. Cancelling ten jobs does not give ten threads back.
- **`deliverIf` answering `true` says nothing about the next frame.** It is asked immediately before
  one delivery. If your handler posts follow-up work, that follow-up is unguarded.

## The discard trap

A body that returns an open resource and a cancel that arrives half a second later are, together, a
leak that no test written by hand will find, because nobody cancels by hand:

```java
Ui.work(progress -> Files.newInputStream(path))   // returns an OPEN stream
  .onSuccess(this::readAndClose)                  // never called if the job was cancelled
  .start();
```

The stream was opened, was never delivered, and nothing closes it. `onDiscarded` is the guard:

```java
  .onDiscarded(stream -> { try { stream.close(); } catch (IOException ignored) { } })
```

It runs **on a worker thread, not the UI thread**, because closing a file, unmapping a buffer or
releasing a native picture can block, and the UI thread is the one place in the process where
nothing may.

That produces a flow that looks like a detour and is not. Whether a value is discarded depends on
`deliverIf`, and `deliverIf` may only be asked on the UI thread, so the result crosses to the UI
thread, is refused there, and crosses back to the pool to be closed. Asking the predicate off the UI
thread instead would put widget reads on a worker, which is the bug the whole package exists to
prevent. The one shortcut is the case that needs no question: a job already cancelled when its body
returns is known undeliverable on the spot and disposes on the thread that produced the value.

The rule of thumb for a body's return type: **if it would need a `close()`, it needs an
`onDiscarded`.** If it is a `String`, a record or a decoded array, it does not.

## The rule for a loader

The reason this package exists is that the toolkit's own facades are where blocking work is most
likely to end up on the UI thread by accident: a `load` that reads a file, a `decode` that walks a
few megabytes, an `open` that dlopens a library and probes a device. Every one of those is fast on
the machine it was written on and is not fast on a cold network volume, a spinning disk, or a laptop
under thermal pressure. The symptom is a frozen window, and by then the API shape is public.

So, the policy, and it is a requirement rather than advice:

> **A toolkit facade that reads a file, decodes, or touches a native library on the calling thread
> must offer an asynchronous form. One that does not must say in its own Javadoc why it does not
> need one.**

The two halves matter equally. The first stops a blocking call from being the only call, so an
application is never forced to choose between a stall and writing its own thread. The second stops
the exemption from being invisible: "this reads from a map already in memory", "this only validates
its arguments", "this hands the work to the decoder thread and returns" are all fine answers, and
each is one sentence at the place a reader is standing when the question occurs to them. A facade
with neither the asynchronous form nor the sentence is the case this rule is aimed at, because from
the outside it is indistinguishable from a fast one.

### The two suffixes, and why a facade may not choose freely

A caller reads a facade's method name before it reads anything else, so the name carries the
lifecycle:

> **`...Async` returns an unstarted `Work`.** Nothing runs until `start()`, the job can be
> cancelled, and a result nobody takes goes to `onDiscarded`.
>
> **`...Shared` returns a `CompletableFuture` that is already running**, de-duplicated by source.

There is no third shape and no facade-by-facade exception, because the failure mode of getting it
wrong is silent in one direction: a `Work` whose `start()` was forgotten does nothing and says
nothing until it is collected. A reader who has just written `Images.decodeAsync(bytes).start()`
must not find that `Images.loadShared(path)` needed the same call, or that `GltfLoader.loadAsync`
and a same-shaped `load...` next to it disagree.

Which of the two a loader gets is not a taste question either. **A de-duplicated result cannot be
a `Work`**: two callers waiting on one load must not be able to cancel each other, and a shared
value has no single owner to hand it to `onDiscarded`. So dedup forces `...Shared`, and everything
else (every operation with one owner) is `...Async`. `ImagesAsyncTest` and `SoundsAsyncTest`
pin the unstarted half from the caller's side.

The asynchronous form should be a `Job`, not a bare stage, wherever the request can be superseded or
the result holds a resource, which is most loaders. `Ui.work` is the shape:

```java
public static Job loadAsync(Path path, Consumer<Thing> onLoaded) {
    return Ui.work(progress -> decode(path, progress))   // blocking, off the UI thread
             .onSuccess(onLoaded)                        // on the UI thread
             .onDiscarded(Thing::release)               // only if a Thing holds something
             .start();
}
```

Which of this repository's facades satisfy the rule, and how each of them does, is the roster below.

## The roster

One row per entry point that reads, decodes, or touches a native library on the thread that calls
it. The last column is either the name of its asynchronous form or the reason it needs none, and it
is meant to be read against the member's own Javadoc: the table says which answer the member gives,
the Javadoc says it in full. A row that can offer neither is marked as a gap rather than dressed up,
because a gap that reads as compliant is the one nobody fixes.

### Pictures and icons

| Member | What the calling thread pays | Asynchronous form, or why none |
| --- | --- | --- |
| `Images.decode` | A decode that scales with the picture's pixel count | `Images.decodeAsync` |
| `Images.load`, `Images.fromResource` | A blocking read, then that decode | `Images.loadShared`, `Images.fromResourceShared`: de-duplicated by source, so one file stays one `Image` and therefore one texture, which is also why these are the `Shared` pair and not `Async` |
| `Images.encode(Image, ImageEncodeOptions)`, `Images.save` | A whole compression, and for `save` a write | `Images.encodeAsync`, `Images.saveAsync` |
| `Images.encode(Image, ImageEncodeOptions, OutputStream)` | The same compression, into the caller's stream | **Excused**: the sink belongs to the caller, and only the caller knows whether a worker may write to it. The two forms that own their sink have asynchronous forms |
| `ImageDecoder.decode` (SPI) | Whatever the decode costs | **Excused**: documented callable from any thread and from several at once, which is exactly what lets `Images.decodeAsync` exist. An implementation that is not pure CPU breaks that facade, not itself |
| `ImageEncoder.encode` (SPI) | The compression | **Excused**: same shape (any thread, no per-call state); `Images.encodeAsync` is the facade's form |
| `SvgIcon.fromResource` | A few kilobytes off the classpath; nothing is parsed | **Excused**: the expensive half is deferred to `SvgIcon.image` |
| `SvgIcon.image` | A parse and a rasterize on every miss: a new size step, a new content scale, each step of a zoom | `SvgIcon.imageAsync`, which folds the bitmap into the same cache, so a later `image` is a hit |
| `Icon.image` | Whatever the implementation's bitmap costs | **Excused**: `Icon.paint` calls it inside a frame, and a paint that asked for a bitmap has to be given one before it can finish. A costly implementation carries its own warm-up instead |
| `SvgRasterizer.rasterize` (SPI) | The parse and the rasterize | **Excused**: documented callable from any thread and from several at once, which is what makes `SvgIcon.imageAsync` possible |
| `Tabler.outline`, `Tabler.filled`, `TablerIcon.icon` | On the first call, the pack's index and about three megabytes of concatenated drawings, read off the classpath and inflated, and again after the soft reference holding them is cleared | `Tabler.warmUpAsync`, which does both reads on the pool. It cannot promise they stay read: the blob is held softly on purpose, so this is a cost moved rather than removed |
| `Tabler.has`, `Tabler.hasFilled`, `Tabler.names` | The index alone, on the same first call | The same `Tabler.warmUpAsync`; the index is read once and kept, so warming it is permanent |
| `ReadableSurface.readDisplayReferred`, `ReadableSurface.readSceneReferred` | A synchronous GPU read that stalls the pipeline until the queue drains | **Excused**: a read needs the context, and the context is current on one thread inside one frame. The encode that usually follows does leave (`Images.saveAsync`) |

### Text and fonts

| Member | What the calling thread pays | Asynchronous form, or why none |
| --- | --- | --- |
| `TextRuler.measure` | May resolve a face on first use, which reads and parses a font file (tens of megabytes for a CJK face) | **Excused**: measurement cannot leave the UI thread, so the read it triggers cannot either. The only lever is *when* a family is first measured |
| `Fonts.setDefaultFamily` | Nothing itself; it stores a name and runs listeners | **Excused**, and the excuse names what it schedules: the next measure or paint resolves the new family, and an unresident face is read inside that frame. Hence the placement rule: a settings action, never an animation |
| `Fonts.available`, `FontCatalog.families` | Nothing; the catalog's current answer, which may be partial | **Excused**: contractually must not block and must not read. A fuller catalog arrives by installing again and notifying listeners |
| `FontStore.resolve` | The first resolve of a system family reads that family's font file, inside whatever measure or paint asked | `FontStore.preloadFamily`, which reads on a worker and calls back on the UI thread, but the backend routes to it internally and the class is not public, so this is **not** a lever an application can pull. Everything a caller has is the placement rule on the two rows above, which is a residual cost, not a solved one |
| `StbFont.loadResource` | A bundled face: a few hundred kilobytes plus an sfnt directory walk | **Excused**: less than a frame, and the first frame's first measure needs *some* face, so there is no earlier moment to move it to |
| `StbFont.loadResourceIfPresent` | The whole resource: small for a UI face, tens of megabytes for a broad-coverage fallback | **Excused** by naming the thread as the caller's choice: anything but a small bundled face belongs on a worker |
| `ColorEmojiFont.loadResourceIfPresent` | Tens of megabytes of bitmap strikes | **Excused** the same way: documented as a worker-thread call |
| `ColorEmojiFont.image` | Extracts one code point's strike and PNG-decodes it | **Excused**: deferring would draw `.notdef` for the first frame of every new emoji, and healing needs a repaint this path cannot ask for without relaying out every scene |
| `GlyphAtlas.glyph` | Rasterizes one glyph from a resident face and uploads it | **Excused**: reads no file, loads no library, and the upload is bound to the thread holding the context |

### Sound

| Member | What the calling thread pays | Asynchronous form, or why none |
| --- | --- | --- |
| `Sounds.decode` | A whole-clip decode (cost scales with decoded length, not file length) | `Sounds.decodeAsync` |
| `Sounds.load`, `Sounds.fromResource` | A whole file read, then that decode | `Sounds.loadShared`, `Sounds.fromResourceShared`: de-duplicated by source |
| `Sounds.isAvailable` | The first call may open the audio device: tens to hundreds of milliseconds, longer over Bluetooth | `Sounds.warmUpAsync`, which asks the same question on the pool |
| `Sounds.play` | A handful of device calls on an already decoded clip, unless it is the call that opens the device | **Excused**: it has to return the handle the caller stops. The device-open case is `warmUpAsync`'s to remove |
| `Sounds.stream(Path, PlayOptions)` | Opens the file, and a decoder may read it whole and decode a frame to learn the format; then the engine primes buffers | `Sounds.streamAsync`, returned unstarted and already carrying a disposer that stops the stream and closes the file |
| `Sounds.stream(AudioStreamSource, PlayOptions)` | Priming several device buffers, i.e. decoding the first fraction of a second | **Excused**: whoever holds an open source opened it somewhere, and that somewhere is where the background work belongs |
| `Sounds.setMasterGain`, `Sounds.setBusGain`, `Sounds.setListener` | Bounded by the voice count; reads nothing | **Excused**: a volume slider or a listener pose that took effect a frame later would read as broken |
| `AudioDecoder.decode`, `AudioDecoder.openStream` (SPI) | Allowed to read a whole file and decode all of it | **Excused**: the freedom is deliberate and paid for one level up; `Sounds` owns the asynchronous forms. The implementation's only duty is to assume nothing about its thread |
| `AudioEngine.isAvailable`, `AudioEngine.playStream` (SPI) | The device open, and the first buffers | **Excused**: `Sounds.warmUpAsync` is the one call the facade can make on a worker to get the open over with |
| `AudioFileDecoder.decode`, `AudioFileDecoder.openStream` | A full decode; for Ogg and MP3 a full file read as well | **Excused**: this is the backend's decoder SPI, and the asynchronous form belongs on the facade that hands the whole call to a worker |

### 3D

| Member | What the calling thread pays | Asynchronous form, or why none |
| --- | --- | --- |
| `GltfLoader.load(Path)`, `GltfLoader.fromResource`, `GltfLoader.load(byte[])` | The file, a JSON parse of the document, a base64 decode of every embedded buffer, and a de-interleaving copy per accessor | `GltfLoader.loadAsync(Path)`, `GltfLoader.fromResourceAsync`, `GltfLoader.loadAsync(byte[])`, reporting progress across those spans and stopping between meshes when cancelled |
| `GltfModel.decodeTextures` | An image decode per referenced texture | `GltfModel.decodeTexturesAsync` |
| `GltfModel.toScene3D()` | That decode *and* the GPU upload, inside one frame | **Excused** by naming the split: the upload half cannot leave the frame, so the form that scales is `decodeTexturesAsync` followed by `toScene3D(DecodedTextures)` |
| `Graphics3D.createTarget`, `Graphics3D.upload`, `Graphics3D.uploadTexture`, `Graphics3D.render` | The driver's time, and nothing else | **Excused** at the class: the context is current on one thread inside one frame, so a worker would have nothing to upload into. What can leave is whatever produced the `MeshData` and `TextureData` |
| `ImageTextureCache.textureFor` | A premultiply into a scratch buffer and an upload | **Excused**: the pixels are already decoded, and the upload is context-bound. The decode that produced them has `Images.loadShared` |
| `ShaderProgram.fromResources` | Two classpath reads of a few kilobytes, then compile, attach, link | **Excused**: everything after the reads is a GL call bound to the context thread, so a worker would buy a fraction of the cost and pay a thread hop for it |
| `IoSurfaces.isAvailable` | A `static final` read | **Excused**: decided once in the class initializer, and that initialization is a reference-count bump on a framework the backend already loaded |

### Video

| Member | What the calling thread pays | Asynchronous form, or why none |
| --- | --- | --- |
| `Videos.open` | Whatever the claiming decoder takes: a header read, a probe of every stream, an index, a decoder, and on the first call a native library | `Videos.openAsync`, whose disposer closes a source nobody received |
| `Videos.canOpen` | An extension comparison and at most a few bytes, except on the call that first prepares a decoder | **Excused**: it has to stay synchronous, because a control deciding whether to enable itself cannot wait for a frame. `Videos.warmUpAsync` is where that one-off cost is paid instead |
| `VideoDecoder.supports` (SPI) | Contractually cheap: an extension, a few bytes, no container parse and no network | **Excused**: same reason, one level down |
| `VideoDecoder.openStream(Path)`, `openStream(Path, Progress)`, `warmUp` (SPI) | Allowed to be slow, and normally is | **Excused**: `openStream(Path, Progress)` is the overload the asynchronous facade calls and the one to override where there is something worth abandoning; `warmUp` is called on a worker by contract |
| `FfmpegLibrary.isAvailable` | The first call extracts tens of megabytes out of the jar, links them in dependency order and probes them, under a global lock | **Excused** by naming the lever: `FfmpegVideoDecoder.warmUp`, which `Videos.warmUpAsync` runs on a worker |
| `FfmpegMedia.open` | A container probe that decodes real packets, then a decoder, and on the hardware path a platform decode device | **Excused** on ownership: it returns one container holding several tracks, and closing it closes all of them, so the disposer a job needs depends on which tracks the caller took and in what order it lets them go. The member says to write that job (a body returning the caller's own record, one disposer over all of it) and names `Videos.openAsync` as the ready-made form for the single case with one obvious owner |
| `FfmpegMedia.writeClip` | A whole encode of every picture and every sample, plus the mux, through the native library | **Excused** the same way and for a weaker reason: no asynchronous form, and the member says to wrap it in `Ui.work`. Unlike the open it produces no resource, so the job is the wrap and nothing more |
| `FfmpegMedia.canWriteClip` | A field read, except on the call that links the native library | **Excused** by naming that first call and where to pay for it |
| `FfmpegMedia.identity`, `FfmpegMedia.components` | The same possible first-call link | **Excused** the same way: each names the first consultation as the one that may link the library, and points at `FfmpegVideoDecoder.warmUp` |

### Dialogs

| Member | What the calling thread pays | Asynchronous form, or why none |
| --- | --- | --- |
| `FileDialogs.openFile`, `openFiles`, `saveFile`, `chooseFolder` | Everything until the user answers: on macOS and Linux a helper process, on Windows an in-process chooser; no frames run meanwhile | **Excused**: a system-modal panel's contract is that the application is unusable while it is up, and the wait is the user's rather than the disk's |

## The trap: an asynchronous form the reader cannot find

A facade whose asynchronous form exists and whose Javadoc says only "in the background" is **not**
compliant, and it is the failure that looks most like success. "In the background" tells a reader
neither which thread the body runs on, nor which thread the callback lands on, nor whether the
result must be released if nobody takes it, so a caller either guesses or goes and reads the
implementation.

Which is the part worth being precise about: the reader this policy exists for **cannot** go and
read the implementation. A published Javadoc jar carries the sources' own documentation and nothing
else. A `//` comment above a private field, however carefully it explains the handoff, is stripped
out of it; so is the name of the executor, the shape of the queue, and the reason the disposer is
attached. The person holding that jar has no repository, no `git log`, no design note: only what
was written on the member. A fact that lives anywhere else has, for that reader, not been written
down at all.

So the third column of every row above has to be a promise a member makes about itself: the name of
the asynchronous form, or the reason there is none, spelled out where the question occurs.

## Adding a loader

1. Decide whether the entry point reads, decodes, or touches a native library on the calling
   thread. First-call costs count (a library link, a device open, a lazily resolved face), and
   they are the ones most often missed, because every call after the first is a field read.
2. Give it an asynchronous form, or a sentence saying why it needs none. Both halves go on the
   member, in prose a reader with only the jar can act on. Name it by the rule above (`...Async`
   if it has one owner, `...Shared` if the result is de-duplicated), and never by which of the two
   was easier to write.
3. Prefer a `Work` the caller starts (and therefore a `Job` it can cancel) over a bare stage,
   wherever the request can be superseded or the result holds something that must be closed. In
   the second case attach the `onDiscarded` yourself, so a caller cannot leak by forgetting one.
4. Say which thread the body runs on and which thread the callback lands on. Naming the pool is not
   the same as naming the thread the handler may touch widgets from.
5. Add the row here.
