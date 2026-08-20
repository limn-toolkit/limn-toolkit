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

`Images.load(Path)`, `Images.fromResource(String)` and `Images.decode(byte[])` all read on
the thread that calls them, which is fine during startup and not fine in a click handler.
Each has an `…Async` twin that does the work on the worker pool and hands the result back on
the UI thread. Use those anywhere a frame is already running:

```java
Images.loadAsync(path).thenAccept(image -> view.setImage(image));
```

Going the other way, `Images.encode(…)` and `Images.saveAsync(…)` write PNG.

## Icons

The toolkit takes an `Icon` wherever a small vector mark belongs: a button, a text field's
leading slot, a menu item. Icons are drawn as paths rather than loaded as bitmaps, so they
stay sharp at every control size and pick up the theme's colour.

The `limn-icons-tabler` module packages the Tabler icon set if you would rather not draw
your own.

## Sound

```java
AudioClip click = Sounds.fromResource("app/click.wav");
Sounds.play(click);
```

WAV, Ogg Vorbis and MP3 decode out of the box. `Sounds.isAvailable()` reports whether an
audio device was found at all. On a machine with none, playback is a no-op rather than an
exception, so a game does not need a silent-mode branch.

Loading is I/O, so prefer `Sounds.loadAsync(…)` and `Sounds.fromResourceAsync(…)` once the
window is up.

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
- **`limn-video-ffmpeg`** decodes H.264 and AAC in MP4 through a trimmed FFmpeg. Its native
  libraries ride in one `natives-<os>-<arch>` classifier per desktop target, for the same
  platforms the backend covers (macOS, Windows and Linux, x64 and ARM64 alike); add the one
  your machine is, or the separate `limn-video-ffmpeg-natives-all` artifact, which names all six,
  for a bundle that ships everywhere. Leave it out and the
  decoder reports itself unavailable and everything else keeps working. The libraries are LGPL (version 2.1 or later) and the jar carries the licence
  text and notice alongside them; [Packaging](/docs/packaging/) has what shipping them means.

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
