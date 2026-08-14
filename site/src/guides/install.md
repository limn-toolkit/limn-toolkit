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
    implementation("dev.limn:limn-components:0.1.0-SNAPSHOT")
    implementation("dev.limn:limn-backend-lwjgl:0.1.0-SNAPSHOT")
}
```

`limn-components` is the widget set and `limn-backend-lwjgl` is the window and the renderer.
Everything else (video decoding, the icon set) is optional and separate.

The backend brings LWJGL's native binaries with it for every desktop platform: Windows,
macOS and Linux, x64 and ARM alike. There is nothing per-platform to add: the JVM loads the
slice that matches the machine it is running on, and the rest sit inert on the classpath,
costing download size and nothing else.

There is no public repository yet. From a clone of the toolkit,
`./gradlew publishToMavenLocal -PlimnFfmpegProfile=player` publishes every library module to
your local Maven repository, and `mavenLocal()` in your `repositories` block resolves the
coordinates above.

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
