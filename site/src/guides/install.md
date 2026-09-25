---
title: "Install and first window"
description: "The JDK you need, the two dependencies, the one macOS flag, and a program that puts a window on screen."
---

## What you need

- **JDK 17 or newer.** Nothing in the toolkit needs a preview feature.
- **A GPU with OpenGL 3.3.** Anything from the last decade qualifies. macOS runs it through
  Apple's 4.1 compatibility layer; a machine with no GPU at all can fall back to Mesa's
  software rasteriser, which is how the screenshots on this site are taken.

## Try it before you install anything

The demo application and the theme editor are on Maven Central as runnable artifacts, and
[jbang](https://www.jbang.dev/download/) starts either from its name — fetching a JDK first
if you have none:

{% tabs os "Windows / Linux" macOS %}
```bash
# the kitchen sink: every widget in one window
jbang demo@limn-toolkit/limn-toolkit
# the theme editor, which writes a theme file
jbang theme-editor@limn-toolkit/limn-toolkit
```
```bash
# the kitchen sink: every widget in one window
jbang --java-options=-XstartOnFirstThread demo@limn-toolkit/limn-toolkit
# the theme editor, which writes a theme file
jbang --java-options=-XstartOnFirstThread theme-editor@limn-toolkit/limn-toolkit
```
{% endtabs %}

The macOS form carries `--java-options=-XstartOnFirstThread`, which is macOS-only: a JVM elsewhere
given it will not start, and the caution below says why macOS needs it. What downloads is the toolkit, the fonts and the native libraries
of the machine you are on: each artifact's POM selects them by operating system, so the other
five platforms' never arrive. The names run the newest release, resolved the first time and then
kept by JBang: add `--fresh` (`jbang --fresh demo@limn-toolkit/limn-toolkit`) to take a newer
one. To pin one, use the coordinate
instead — `jbang io.github.limn-toolkit:limn-demo:{{version}}`, or `limn-theme-editor` for the
editor. With no network, the demo is also one file attached to every release, every platform
inside: `jbang limn-demo-all.jar`.

## Add the dependencies

{% tabs build Gradle Maven %}
```kotlin title="build.gradle.kts"
dependencies {
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:{{version}}")
}
```
```xml title="pom.xml"
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-backend-lwjgl</artifactId>
  <version>{{version}}</version>
</dependency>
```
{% endtabs %}

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

{% tabs build Gradle Maven %}
```kotlin title="build.gradle.kts"
dependencies {
    // Every fallback face at the versions this release was tested with…
    runtimeOnly("io.github.limn-toolkit:limn-fonts-all:{{version}}")
    // …or the two heavyweights by name, pinned to the FONT's own version:
    // runtimeOnly("io.github.limn-toolkit:limn-fonts-noto-cjk:2.004.2")
    // runtimeOnly("io.github.limn-toolkit:limn-fonts-noto-emoji:2.051.2")
}
```
```xml title="pom.xml"
<!-- Every fallback face at the versions this release was tested with… -->
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-fonts-all</artifactId>
  <version>{{version}}</version>
  <type>pom</type>
  <scope>runtime</scope>
</dependency>
<!-- …or the two heavyweights by name, pinned to the FONT's own version:
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-fonts-noto-cjk</artifactId>
  <version>2.004.2</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-fonts-noto-emoji</artifactId>
  <version>2.051.2</version>
  <scope>runtime</scope>
</dependency>
-->
```
{% endtabs %}

Without them the toolkit runs unchanged and those scripts draw as empty boxes; the log names
the artifact that fixes it.

Everything comes from Maven Central, so `mavenCentral()` in your `repositories` block is the
whole of a Gradle build's setup, and a Maven build, which reads Central already, needs none. If you want what is on `main` rather than what was released, development builds
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
