---
title: "Images and media"
description: "Pictures, icons, audio, video and a 3D viewport: all of them ordinary widgets."
---

## Pictures

`Images` loads them and `ImageView` shows them:

```java
Image logo = Images.fromResource("app/logo.png");
ImageView view = new ImageView(logo);
```

An image whose header claims more than 2^28 pixels is refused before it is decoded, rather than
asking for gigabytes.

`Images.load(Path)`, `Images.fromResource(String)` and `Images.decode(byte[])` all read on
the thread that calls them, which is fine during startup and not fine in a click handler.
Anywhere a frame is already running, load on the worker pool and take the result on the UI
thread instead. A file or a resource has a name, so `Images.loadShared` and
`Images.fromResourceShared` cache it: every caller of the same path gets the same `Image` and
the GPU gets one texture. Bytes you already hold have no name, so `Images.decodeAsync` is a
job you start, and may cancel:

{% snippet guide:loading %}

Each window keeps the texture of every image it draws, and the `Image` behind it, until 300
frames go by without drawing it, or sooner, least recently drawn first, while it holds more than
`Images.textureBudget()`: 256 MiB by default, mipmaps included, about twenty-four photographs of
1920 × 1080. What a frame draws is never dropped, so a single picture larger than the budget
still shows. An application short of memory lowers it, and one that keeps coming back to a set
of pictures bigger than that raises it, with `Images.setTextureBudget`.

The shared loads have a budget of their own, on the heap: what `loadShared` and
`fromResourceShared` loaded is kept, so that asking again costs nothing, while it fits a quarter
of the most heap the process may use. Past that the picture asked for longest ago is let go, and
every file before any resource, since an application's own resources are few and it comes back
to them. Letting go is not losing: a picture still on screen, or held anywhere, is the same
object when it is asked for again, and only one that nobody holds is read a second time.
`Images.setSharedBudget` moves the budget. For the pictures that must never be read twice,
whatever else is loaded, `Images.loadPinned` and `Images.fromResourcePinned` load the same way
and keep the result outside the budget until `Images.unpin`. `Sounds` has the same calls, with
an eighth of the heap.

### Pictures that move

An animated GIF, PNG or WebP decodes to an `Image` that says `isAnimated()`. It is an image
like any other, and its own pixels are its first frame's, so an icon, a cursor or anything else
that draws it without playing it shows a still picture. Give it to an `ImageView` and it plays,
by itself and for as many turns as the file asks for. A sheet of sprites is the same thing made
by hand, and `Images.animation(frames, millis, turns)` takes frames from anywhere:

{% snippet guide:animated-images %}

A picture playing costs your window one frame for each of its own: an image view asks for none
between two frames of its picture, and none at all while it is hidden. A widget of your own that draws frames itself uses
`ImageAnimation`, the clockwork an image view is built on: hand it the time from a scene's
ticker and draw `frame()`.

A small animation is decoded whole when it is loaded; one whose frames together pass
`Images.setAnimationBudget`, 32 MiB unless you say, keeps its file's bytes instead and decodes
a few frames ahead of the one shown as it plays, so that a long clip costs its file and a
handful of frames. Loaded with `loadShared`, each frame is kept or let go by the shared budget
like any picture, and `loadPinned` keeps them all.

A moving picture is not stopped by the system's setting for less motion, which is for the
motion an interface adds to itself. One that moves for more than a few seconds should be
something your user can stop, and `setPlaying` is what that control calls.

Going the other way, `Images.encode(…)` and `Images.saveAsync(…)` write PNG and GIF, still or
animated: an animated PNG keeps every frame without loss, and a GIF holds 256 colours.

`view.setDragEnabled(true)` lets the user drag the picture out of its view, to another part of
your window or to another program; [Drag and drop](/docs/drag-and-drop/#a-picture-that-leaves-its-view)
has the details, the clipboard among them.

## Icons

The toolkit takes an `Icon` wherever a small vector mark belongs: a button, a text field's
leading slot, a menu item. Icons are drawn as paths rather than loaded as bitmaps, so they
stay sharp at every control size and pick up the theme's colour. An `SvgIcon` whose source the
rasterizer refuses draws nothing and says why in the log, once.

The `limn-icons-tabler` artifact packages the Tabler icon set if you would rather not draw your
own — one enum constant per icon, drawn through the same `SvgIcon`. It is versioned with Tabler
rather than with the toolkit (`3.46.0.x` is Tabler 3.46.0), so it stays in your cache across
Limn upgrades:

{% tabs build Gradle Maven %}
```kotlin title="build.gradle.kts"
implementation("io.github.limn-toolkit:limn-icons-tabler:3.48.0.0")
```
```xml title="pom.xml"
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-icons-tabler</artifactId>
  <version>3.48.0.0</version>
</dependency>
```
{% endtabs %}

## Sound

```java
AudioClip click = Sounds.fromResource("app/click.wav");
Sounds.play(click);
```

WAV, Ogg Vorbis and MP3 decode out of the box, at sample rates up to 384 kHz: a file above that
is refused with an `UnsupportedOperationException` rather than played slow, which is what the
audio device would do with it. `Sounds.isAvailable()` reports whether an
audio device was found at all. On a machine with none, playback is a no-op rather than an
exception, so a game does not need a silent-mode branch.

Loading is I/O, so once the window is up prefer `Sounds.loadShared(…)` and
`Sounds.fromResourceShared(…)`, which read and decode on the worker pool, complete on the UI
thread and share one clip between callers, as the image loaders above do; `Sounds.decodeAsync`
is the job for bytes in memory.

## Video

`VideoView` is a widget. Give it a source and it plays:

```java
VideoView view = new VideoView(source);
view.setFit(VideoView.Fit.CONTAIN);
view.setLooping(true);
```

It is a widget in every sense: a scroll view clips it, a stack draws over it, and it takes
part in layout like a label does. The decoder behind it is a separate concern:

- **The pure-Java decoders** ship in `limn-toolkit` itself, with no native code and no
  third-party dependency. They read Y4M and generate test patterns, which is enough to drive
  the widget and not enough to play a film.
- **`limn-video-ffmpeg`** decodes H.264, HEVC, VP9 and VP8 video, with AAC, Opus or Vorbis
  audio, out of MP4 and Matroska (WebM included), through a trimmed FFmpeg. The FFmpeg
  libraries are a separate artifact, `limn-ffmpeg-natives`, versioned with FFmpeg rather than
  with the toolkit (so it stays in your cache across Limn upgrades), one `natives-<os>-<arch>`
  classifier per desktop target, for the same platforms the backend covers (macOS, Windows and
  Linux, x64 and ARM64 alike); add the one your machine is, or `limn-video-ffmpeg-natives-all`,
  a POM versioned with the toolkit that names all six at the payload version this release was
  tested with, for a bundle that ships everywhere. The JNI shim comes with `limn-video-ffmpeg`
  itself. Leave the libraries out and the decoder reports itself unavailable and everything else
  keeps working. They are LGPL (version 2.1 or later) and each jar carries the licence text and
  notice alongside them; [Packaging](/docs/packaging/) has what shipping them means.

  {% tabs build Gradle Maven %}
  ```kotlin title="build.gradle.kts"
  implementation("io.github.limn-toolkit:limn-video-ffmpeg:{{version}}")
  // A build for one known machine takes the payload's classifier for it:
  runtimeOnly("io.github.limn-toolkit:limn-ffmpeg-natives:9.0.2.0:natives-macos-aarch64")
  // A bundle that ships everywhere takes the POM that names all six:
  runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg-natives-all:{{version}}")
  ```
  ```xml title="pom.xml"
  <dependency>
    <groupId>io.github.limn-toolkit</groupId>
    <artifactId>limn-video-ffmpeg</artifactId>
    <version>{{version}}</version>
  </dependency>
  <!-- A build for one known machine takes the payload's classifier for it: -->
  <dependency>
    <groupId>io.github.limn-toolkit</groupId>
    <artifactId>limn-ffmpeg-natives</artifactId>
    <version>9.0.2.0</version>
    <classifier>natives-macos-aarch64</classifier>
    <scope>runtime</scope>
  </dependency>
  <!-- A bundle that ships everywhere takes the POM that names all six: -->
  <dependency>
    <groupId>io.github.limn-toolkit</groupId>
    <artifactId>limn-video-ffmpeg-natives-all</artifactId>
    <version>{{version}}</version>
    <type>pom</type>
    <scope>runtime</scope>
  </dependency>
  ```
  {% endtabs %}

Subtitles ride along with the video's own container: select the track before you start
reading, and the player hands you timed text to draw however your design wants it.

## 3D

`Viewport3D` renders a scene to a texture and composites it as a 2D layer, so it is another
ordinary widget:

```java
Viewport3D viewport = new Viewport3D();
viewport.setRenderer(renderer);
viewport.setPreferredSize(480, 320);
viewport.frameContent(model.bounds());
```

It renders physically-based materials to a linear high-dynamic-range target and applies the
display transform once, when the result is composited, which is why a bright highlight in
the viewport does not clip differently from the rest of your window.

`setAnimated(true)` asks for a frame every tick; leave it off and the viewport only redraws
when something changes, which is what you want for a static model on a laptop battery.
`onClick` gives you a ray through the pointer for picking, and `captureNext` hands you the
next rendered frame as an `Image`.

{% shot viewport-3d "The 3D viewport, composited into an ordinary window." %}

## A file that is not what it claims to be

A file a user picks may be cut short, damaged, or made on purpose to break whatever reads it.
Every loader refuses such a file with one exception, `MalformedContentException`, whose
message says what is wrong:

```java
try {
    picture.setImage(Images.load(chosen));
} catch (MalformedContentException broken) {
    status.setText("That file is not a picture we can read: " + broken.getMessage());
} catch (UncheckedIOException unreadable) {
    status.setText("That file could not be opened.");
}
```

The other two things that can go wrong are kept apart from it. A file that cannot be read at
all is an I/O failure. A file that is whole but of a kind nothing here plays, a sound with
six channels, is an `UnsupportedOperationException`. `broken.format()` names the format, and
`broken.position()` the line or the offset where the reader knows one.

**What may come from anywhere:** a picture, a sound, a model and a theme. Their readers hold
every size a file claims to what the file can back, so a small file cannot ask for a large
heap, and a damaged one is refused. The one exception is video: what the video library
cannot open arrives as its own `FfmpegException`, since it does not tell a broken file from
one it does not play.

**What should come only from you, or from a source you trust:** a font. The library that
draws a font's outlines checks nothing a font says about itself, and a font made to break it
can end the process. Load the fonts you ship; do not offer "choose a font file" to a user
whose files you would not otherwise run. The same caution, more mildly, goes for an SVG: an
icon is your resource, and one a user supplies is drawn by a native library.

A sound decoded whole stops at about fifty minutes of stereo; `Sounds.stream` plays anything
longer without holding it in memory.
