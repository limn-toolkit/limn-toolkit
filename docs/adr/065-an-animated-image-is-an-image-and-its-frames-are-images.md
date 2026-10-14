# ADR 065: An animated image is an image, and its frames are images

- **Status:** Accepted, 2026-10-13, and implemented in part. Done: the image's frames, times and
  turns, `Images.animation` and `fromSheet`, the clockwork and `ImageView` playing, GIF read
  and written, WebP read, still and animated, behind the walk of its container, frames decoded
  as they play over the animation budget, and each frame an entry of the shared loads, pinned
  with its picture. Not yet: a frame asked for only when it is due (a view playing asks for
  every frame of the display and paints when its picture changes), and APNG.
- **Date:** 2026-10-13
- **Scope:** pictures that move: animated GIF, APNG and animated WebP read from a file, and a
  sprite sheet declared by hand; how they are held, cached, played and controlled.
- **New public API (proposed):** `Image.isAnimated`, `frameCount`, `frame`, `frameMillis`,
  `durationMillis`, `loopCount`; `Images.animation`, `Images.fromSheet`,
  `Images.setAnimationBudget`; `ImageAnimation`; on `ImageView`, `setPlaying`, `setSpeed`,
  `setLoop`, `seekFrame`, `onFinished`.
- **Compatibility:** an animated file decodes to an image whose own pixels are its first frame,
  which is what it decodes to today. Nothing that draws an `Image` changes.

---

## 1. Why

The toolkit shows a picture and plays a video, and has nothing between the two. An animated
GIF decodes to its first frame. The page of the Showcase called Sprites animates a sheet of
frames with a ticker and arithmetic of its own, because `Canvas.drawImage` with a source
rectangle is the whole of what the toolkit offers for it: no type holds frames, how long each
is shown, or how many times the whole repeats.

A business application meets these as a spinner, an illustration that loops, a sticker or a
clip somebody pasted into a conversation. None of them is worth a video decoder, and each is
the same thing: frames, a time for each, a number of turns.

## 2. What was read

| Format | Where it is met | What decodes it here |
|---|---|---|
| GIF | everywhere; 256 colours a frame, no partial transparency | the picture decoder the backend bundles reads every frame, but all at once and with no loop count |
| APNG | stickers and animated icons, with real transparency | nothing; it is PNG with three more chunks, and PNG is read already |
| Animated WebP | what the web serves where it served GIF | nothing in the windowing or picture libraries bundled |
| Animated AVIF | growing in browsers | nothing; it needs an AV1 decoder, megabytes a platform |
| JPEG XL | one major browser shows it | nothing, and no call for it |

Toolkits that have this expose it the same way: a codec that hands back frames, each with a
time, and a repeat count, with the caching of frames a separate choice. None makes the moving
picture a different kind of object to draw.

For WebP the owner asked whether somebody had already written it in Java. Four were found:

| Library | Licence | Reads | Depends on | State |
|---|---|---|---|---|
| `org.glavo:webp` | Apache-2.0 as published (0.2.0), MPL-2.0 in the repository since | lossy, lossless, animated, frame by frame with each frame's time | `java.base` alone, Java 17 | on Maven Central; one maintainer, 212 commits; ported from the Rust community's decoder with its tests and the reference library's |
| a multi-format codec library | Apache-2.0 | GIF and WebP animated, with disposal and blending | a logging library and two libraries of the author's own at snapshot versions; Java 21 | not on Maven Central; no release |
| an ImageIO plug-in, pure Java | not stated | lossy only | `javax.imageio`, so AWT | no animation |
| the long-standing ImageIO plug-in set | BSD | lossy and lossless | `javax.imageio`, so AWT | animation not found in it |

Only the first can be a dependency of a toolkit that does not load AWT, and it is young. The
decision below takes it behind a spike and a fuzz, with the format's own native library as the
way back.

## 3. Decision: an animated image is an `Image`

As the owner chose. `Image` gains what describes its motion:

```java
boolean isAnimated();          // more than one frame
int frameCount();              // 1 for a still picture
Image frame(int index);        // a still Image; frame(0) of a still picture is itself
int frameMillis(int index);    // how long that frame is shown
long durationMillis();         // one turn
int loopCount();               // turns the file asks for; 0 is for ever
```

**The image's own pixels are its first frame.** `width()`, `height()` and `pixels()` answer as
they do now, so everything that draws an `Image` and knows nothing of animation draws a still
picture, the first frame: an icon, a cursor, the tray, the picture carried by a drag, a
texture of a 3D material, `Canvas.drawImage`. That is the cost of making the moving picture
an `Image` (every such place would otherwise have had to decide), and this is how it is paid
once: nothing moves unless something plays it.

**A frame is an `Image`.** Each is whole, composited and of the animation's size: what a GIF
calls disposal and APNG and WebP call blending is done by the decoder, and no caller meets a
partial frame. Its identity is its texture's key like any other image's, so the window's
texture cache and its budget apply to frames with no new rule.

**A sprite sheet is the same thing, made by hand.**

```java
Image run = Images.fromSheet(sheet, 8, 1, 90);          // 8 columns, 1 row, 90 ms a frame
Image any = Images.animation(frames, millis, loopCount); // frames from anywhere
```

`fromSheet` cuts the sheet into frames, each its own `Image`. That gives up drawing every
frame from one texture in one batch, which a user interface does not miss; a game that wants
it keeps `Canvas.drawImage` with a source rectangle, as now.

## 4. Decision: who plays it

**`ImageView` plays an animated image it is given, and starts by itself,** as a picture that
moves is expected to. A video in this toolkit starts still, because a video has sound and a
page nobody opened must not start one; a moving picture has none.

**The system's wish for less motion does not stop it,** as the owner decided: that wish is
for the motion an interface adds to itself, a fade or a slide, which loses nothing by ending
at once, where an animation held still is no longer the animation. It controls:

| Call | What it does |
|---|---|
| `setPlaying(boolean)` | plays or holds the frame shown |
| `setSpeed(float)` | 1 is the file's own times; 2 is twice as fast |
| `setLoop(Loop)` | `Loop.FILE`, the turns the file asks for (the default); `Loop.FOREVER`; `Loop.ONCE`; `Loop.times(n)`; `Loop.BOUNCE`, forward then back |
| `seekFrame(int)`, `frameIndex()` | goes to a frame, says which is shown |
| `onFinished(Runnable)` | told when the last turn ends |

**A widget of the application's plays one with `ImageAnimation`,** the same clockwork without
the view: given an image and a scene, it answers `frame()` to draw now and asks for the next
frame at the moment the frame changes. `ImageView` is built on it.

Three rules hold for both:

- **Time is the scene's.** Frames advance on the scene's clock, so a paused scene holds them,
  a slowed one slows them and a test moves them by moving its clock.
- **A frame is asked for when it is due, not every frame.** A picture of ten frames a second
  costs ten frames a second of its own box, and nothing while it is hidden, out of the
  window, or in a window that is not shown. The budget of ADR 062 counts it.
- **A time too short to be meant is lengthened.** Files carry frame times of zero or of a
  hundredth of a second that every viewer has always shown at a tenth; so does this.

## 5. Decision: what stays in memory

As the owner chose: a small animation is decoded whole, a large one as it plays, and the
frames live in the cache the shared loads already have.

- **Under the animation budget, every frame is decoded when the image is.** A spinner or a
  sticker then costs nothing to play. The budget is in bytes as the frames sit on the heap,
  `Images.setAnimationBudget`, 32 MiB unless set: some hundred frames of 320 by 240.
- **Over it, frames are decoded as they are needed,** on the worker pool, a few ahead of the
  one shown. The image keeps the file's bytes and the decoder's place in them. Frames in these
  formats are built on the one before, so playing forward costs one frame's work a frame, and
  a seek backward starts from the nearest frame that stands alone.
- **Each frame is an entry of the shared loads** (ADR 064) when the image came from
  `loadShared` or `fromResourceShared`: it counts toward the same budget, is let go by the
  same rule, the longest unasked first and files before resources, and a frame let go and
  still shown is the same object when asked for again. A frame let go of an animation decoded
  whole is decoded again when its turn comes round.
- **Pinning the image pins every frame.** `loadPinned` and `fromResourcePinned` of an animated
  file keep all of it, whatever both budgets say, and `unpin` puts all of it back.
- A frame that is not ready when it is due is not waited for: the one before stays until it
  is. The user interface thread never decodes.

An image decoded from bytes, `Images.decode`, is nobody's to share: under the budget it holds
its frames itself, and over it a window of them, so that each turn decodes the file again.

As built: the window is the frame asked for and up to eight after it, fewer when the budget
is smaller than that many frames and never fewer than two. `Image.frameIfReady` is what a
player asks, and the clockwork holds the frame before for one that is late, owing none of the
wait to the frames after: paid back, a late frame cost the one after it, which a test caught.
A frame behind the reader's place costs a start from the first, so playing backward through a
long animation decodes it over and over; nothing is done about that.

## 6. Decision: the decoders

GIF first, then WebP, as the owner chose, both read in Java; and APNG after them only if it
comes cheap, which is his condition: no decoder and no encoder of PNG's own is written for it.

- **GIF, written here.** The bundled decoder reads a whole file at once into native memory
  and says nothing of the loop count, which rules out both decoding as it plays and
  `Loop.FILE`. The format is a header, a palette and a dictionary coder, a few hundred lines.
- **APNG, if cheap.** It is cheap this way: each frame's data is put back between the header
  and the end of an ordinary PNG and handed to the decoder already installed, and what is
  written here is the walk of the chunks and the laying of each frame on the one before. If
  that turns out not to hold, APNG is left out and said so.
- **WebP, by `org.glavo:webp`, a dependency of the library itself,** as the owner decided: an
  application gets WebP with the toolkit and adds nothing. It reads frame by frame, which is
  what section 5 needs. It is adopted after a spike that answers four things: what it adds
  to the size of what an application ships; that none of it is loaded until a WebP is
  decoded; how fast it decodes; and what it does with a damaged file, under the mutation fuzz
  of ADR 061. It is taken at the version published under Apache-2.0 or a later one whose
  licence is read first. If the spike refuses it, the way back is the format's own library
  built as natives in a repository of its own, as the fonts and the video decoder are.

**The spike, 2026-10-13,** of version 0.2.0 against the format's reference encoder and decoder,
on files made for it: a photograph of 3.7 megapixels with and without loss, an icon with
transparency both ways, and animations of six frames both ways.

| Question | What was measured |
|---|---|
| Size | one jar of 127 kB, 70 classes; its one declared dependency is a set of annotations nothing loads. The Showcase's jar is 66.6 MB |
| Loaded when | no class of it before the first decode; 40 after a lossy picture, 52 after a lossless animation too; none of its helpers for other toolkits |
| Right | lossless, and transparency both ways: every pixel as the reference decoder's. Lossy, still and animated: every pixel as the reference decoder's with its simple chroma upsampling, which is not the one the reference uses unless told (16% of a photograph's pixels differ from that, by 25 levels at most, at edges of colour) |
| Animation | frames whole and composited, each with its time, the loop count and the frame count, read one at a time |
| Speed, warm | lossy 11 ms a megapixel against 2 for the native decoder; lossless 16 against 10. The first decode of a process took 223 ms for the photograph |
| A damaged file | 40,000 damaged copies under a heap of 256 MB: none hung, the slowest took a quarter of a second, 71% were refused with the library's own exception, and 4% ended in an out-of-memory error, with 41 other errors of an array's size or index. It allocates what a file's sizes say |
| The same, behind a check of ours | the container walked first, every chunk held inside the file and the canvas and frames under a ceiling: 4 of 40,000 out of memory, no other error |

So it is adopted on two conditions the toolkit meets itself: the container is checked before
the library sees a byte of it, and whatever the library throws leaves as the toolkit's one
exception. Its lossy pictures are a little coarser at edges of colour than the reference's,
and five times slower to decode, on a worker thread, which a user interface's pictures bear.

**Writing them.** The owner asked for encoders too, not one for each format but those that
come cheap:

| Format | Written | Why |
|---|---|---|
| APNG | yes, with its reader | the PNG encoder is here already; an animation is its chunks with three more kinds |
| GIF | yes | a palette of 256 colours chosen for each animation and the dictionary coder run the other way |
| WebP | no | the library reads and does not write, and a writer is two encoders |

`Images.encode` and `save` take an animated image where the format can hold one, and write
its first frame where it cannot.

Every reader is held to ADR 061: one exception for a damaged file, ceilings on a picture's
size and on the frames of one file, and the mutation fuzz in `check`, the WebP library
included since it is Java.

`ImageDecoder` gains the animated form beside the one it has, and a decoder that has none
answers the still picture as now.

## 7. Accessibility

An image's node is as it is, with the name the application gave the view. A picture that
moves for more than five seconds has to be something a user can stop, and stopping it is the
application's to offer: `setPlaying` is what its control calls, and
`SystemPreferences.reduceMotion()` is there for one that chooses to start such a picture
still. The toolkit neither holds a picture still by itself nor draws a control over one.

## 8. Not done

- **Writing animated WebP**, for want of an encoder.
- **Animated AVIF and JPEG XL**, for want of a decoder that is not megabytes.
- **Vector animation** (the JSON-described kind), which is drawing and not a picture.
- **Animated icons** in a button, a menu, the tray or a cursor: each shows the first frame.
- **Sound.** A picture that moves with sound is a video.

## 9. What the owner settled

1. **A moving picture starts moving,** and the system's wish for less motion is not applied
   to it (section 4).
2. **`fromSheet` cuts the sheet** into one image a frame (section 3).
3. **WebP by the Java library, as a dependency of the toolkit,** confirmed on the spike of
   section 6.
4. **32 MiB** is the animation budget unless set (section 5).
5. **GIF first; APNG only if it is cheap; and encoders** for the formats where one comes cheap
   (section 6).
