# ADR 065: An animated image is an image, and its frames are images

- **Status:** Accepted, 2026-10-13, and not implemented. The WebP library is adopted on the
  outcome of the spike of section 6.
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
its frames itself, and over it a window of them.

## 6. Decision: the decoders

All three formats in the first delivery, as the owner chose, and all three read in Java.

- **GIF, written here.** The bundled decoder reads a whole file at once into native memory
  and says nothing of the loop count, which rules out both decoding as it plays and
  `Loop.FILE`. The format is a header, a palette and a dictionary coder, a few hundred lines.
- **APNG, written here,** on the inflater the platform has: the chunks that say where each
  frame goes and how it is laid on the one before, and PNG's five filters.
- **WebP, by `org.glavo:webp`, a dependency of the library itself,** as the owner decided: an
  application gets WebP with the toolkit and adds nothing. It reads frame by frame, which is
  what section 5 needs. It is adopted after a spike that answers four things: what it adds
  to the size of what an application ships; that none of it is loaded until a WebP is
  decoded; how fast it decodes; and what it does with a damaged file, under the mutation fuzz
  of ADR 061. It is taken at the version published under Apache-2.0 or a later one whose
  licence is read first. If the spike refuses it, the way back is the format's own library
  built as natives in a repository of its own, as the fonts and the video decoder are.

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

- **Writing** an animation to a file.
- **Animated AVIF and JPEG XL**, for want of a decoder that is not megabytes.
- **Vector animation** (the JSON-described kind), which is drawing and not a picture.
- **Animated icons** in a button, a menu, the tray or a cursor: each shows the first frame.
- **Sound.** A picture that moves with sound is a video.

## 9. What the owner settled

1. **A moving picture starts moving,** and the system's wish for less motion is not applied
   to it (section 4).
2. **`fromSheet` cuts the sheet** into one image a frame (section 3).
3. **WebP by the Java library, as a dependency of the toolkit,** after the spike of section 6.
4. **32 MiB** is the animation budget unless set (section 5).
