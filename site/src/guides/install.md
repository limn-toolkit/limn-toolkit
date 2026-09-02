---
title: "Install and first window"
description: "The JDK you need, the two dependencies, the one macOS flag, and a program that puts a window on screen."
---

## What you need

- **JDK 17 or newer.** Nothing in the toolkit needs a preview feature.
- **A GPU with OpenGL 3.3.** Anything from the last decade qualifies. macOS runs it through
  Apple's 4.1 compatibility layer; a machine with no GPU at all can fall back to Mesa's
  software rasteriser, which is how the screenshots on this site are taken.

## Add the dependencies

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:{{version}}")
}
```

That one line is the whole install. `limn-backend-lwjgl` is the window and the renderer, and
it exports `limn-toolkit` — the widget set, the layout and the scene graph — to whatever
depends on it. Everything else (FFmpeg video decoding, the icon set) is optional and separate.

The backend brings LWJGL's native binaries with it for every desktop platform: Windows,
macOS and Linux, x64 and ARM alike. There is nothing per-platform to add: the JVM loads the
slice that matches the machine it is running on, and the rest sit inert on the classpath,
costing download size and nothing else.

It also brings the fonts a UI cannot do without: Roboto (the default family) and the four
faces that make Arabic, Hebrew, Devanagari and Thai render, each versioned with the font
rather than with the toolkit. Two faces are yours to opt into, because together they are
26 MB an app that never draws them should not carry — the pan-CJK face (Chinese, Japanese,
Korean) and colour emoji:

```kotlin
dependencies {
    // Every fallback face at the versions this release was tested with…
    runtimeOnly("io.github.limn-toolkit:limn-fonts-all:{{version}}")
    // …or the two heavyweights by name, pinned to the FONT's own version:
    // runtimeOnly("io.github.limn-toolkit:limn-fonts-noto-cjk:2.004.1")
    // runtimeOnly("io.github.limn-toolkit:limn-fonts-noto-emoji:2.051.1")
}
```

Without them the toolkit runs unchanged and those scripts draw as empty boxes; the log names
the artifact that fixes it.

Everything comes from Maven Central, so `mavenCentral()` in your `repositories` block is the
whole of the setup. If you want what is on `main` rather than what was released, development builds
are published as snapshots to `https://central.sonatype.com/repository/maven-snapshots/`.

:::caution[On macOS, read this first]
macOS requires the window event loop on the process's very first thread, so the JVM needs
`-XstartOnFirstThread`. Without it the program exits immediately with a native error, and
it looks like the toolkit is broken.

It is macOS-only. A JVM on Windows or Linux given that flag refuses to start, so pass it
from your run configuration or your Gradle `JavaExec` task, and never bake it into the
start scripts of a distribution you ship to everyone.
:::

## A window on screen

This is the whole program. A backend, a window, a widget tree, and the event loop.

{% snippet hello-window %}

Four things are happening:

1. **The backend owns the platform.** It opens windows and drives the event loop, and
   closing it shuts everything down, which is why it is in a try-with-resources.
2. **Widgets are objects you construct.** `Column`, `Label` and `Button` are ordinary
   classes; a screen is whatever tree you build out of them.
3. **A `Scene` is a widget tree bound to a window.** It handles layout, input and painting.
4. **`runEventLoop()` blocks** until the last window closes.

## Where to go next

[Widgets and scenes](/docs/widgets/) explains the model this program uses without saying so.
If you would rather look around first, the toolkit ships a demo application whose kitchen
sink screen has every component on it at once.
