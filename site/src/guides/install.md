---
title: "Install and first window"
description: "The JDK you need, the one dependency, the optional fonts, the one macOS flag, and a program that puts a window on screen."
---

## What you need

- **JDK 17 or newer.** Nothing in the toolkit needs a preview feature.
- **A GPU with OpenGL 3.3.** Anything from the last decade qualifies, and macOS runs it through
  Apple's 4.1 compatibility layer. Where the system's OpenGL gives no context, the backend runs on
  OpenGL ES 3.0 from ANGLE instead, which it carries for Windows and macOS and loads only when it
  is needed. On Windows that is a machine with no OpenGL driver, or one older than 3.3 (a virtual
  machine, a remote-desktop session), and ANGLE runs over Direct3D 11 there, which needs a
  Direct3D 11 GPU of feature level 10_0 or the system's own software adapter. On macOS it is a
  Mac with no accelerated renderer, as in a virtual machine, or with no OpenGL at all, and ANGLE
  runs over Metal; only if that fails too does such a Mac take the system's software renderer. A
  Linux machine with no GPU at all can fall back to Mesa's software rasteriser, which is how the
  screenshots on this site are taken. On Windows, where even ANGLE gives no context, the error
  Limn stops with names the way out: the graphics driver, or Microsoft's free
  [OpenCL, OpenGL, and Vulkan Compatibility Pack](https://apps.microsoft.com/detail/9NQPSL29BFFF)
  (`winget install Microsoft.OpenCLGLVulkanCompatibilityPack`). An application opened from its
  icon has no console to show that error, so it first shows Windows' own error dialog, with
  buttons that open the pack in the Store or install it with winget;
  `-Dlimn.backend.errorDialog=false` turns the dialog off, and `-Dlimn.backend.angle=off` refuses
  the ANGLE fallback. [Troubleshooting](/docs/troubleshooting/#the-window-never-opens) has the
  rest.
- **On Linux, an X11 or a Wayland session.** The same binaries serve both.

### Supported systems

The oldest system each platform runs on is set by the native libraries Limn ships, or by the JDK
where that is higher. The numbers are read from the libraries themselves, and the build checks
every one of them, on all six platforms, against
[`limn-backend-lwjgl/src/test/java/limn/backend/lwjgl/floors/OsFloors.java`](https://github.com/limn-toolkit/limn-toolkit/blob/main/limn-backend-lwjgl/src/test/java/limn/backend/lwjgl/floors/OsFloors.java),
where they are kept: a dependency that would raise one fails the build first.

| Platform | Oldest supported | What sets it | Run in the lab |
|---|---|---|---|
| Windows x64 | Windows 10 | JDK 17, which supports nothing older; the libraries themselves ask only for Windows Vista | an x64 JVM under emulation on Windows 11 ARM64, not Intel or AMD hardware |
| Windows ARM64 | Windows 10 on ARM, or Windows 11 | the JDK: ARM64 builds exist only for those; the libraries ask for Windows 8 | Windows 11 |
| macOS, Apple silicon | macOS 11 Big Sur | LWJGL's libraries and the SVG renderer, built for 11.0 | macOS 26 and 27 |
| macOS, Intel | macOS 11 Big Sur | the SVG renderer, built for 11.0 (LWJGL's own ask for 10.11 to 10.15) | an x86_64 JVM under translation on Apple silicon |
| Linux x86_64 | glibc 2.28: Debian 10, Ubuntu 20.04, RHEL 8 or newer; Ubuntu 18.04, at 2.27, is just below it | LWJGL's core and OpenAL, the SVG renderer and FFmpeg, all at glibc 2.28 | the build's own tests on an Ubuntu runner, under a virtual X server and software rendering; no desktop |
| Linux ARM64 | glibc 2.35: Ubuntu 22.04, Debian 12 or newer; RHEL 9, at 2.34, is just below it | LWJGL's text shaping library (its core and GLFW need 2.34) | Fedora 44 (KDE) and Ubuntu 24.04 (GNOME) |

MP4 and Matroska, through `limn-video-ffmpeg`, keep the same floors: `limn-ffmpeg-natives`
9.0.2.1 is built for macOS 11 Big Sur. Every payload before it was built for macOS 26 Tahoe by
mistake, so an application that names an older one itself plays video only on macOS 26 or newer.

A few Windows features need a later build of Windows 10 than the first, and below it each gives
way to something plainer rather than failing:

- **Dark system menus** (the tray's menu among them) from version 1903, build 18362. Before it
  they are light whatever the user chose.
- **Headings a screen reader announces as headings, with their level** from version 1809, the
  first to carry UI Automation's heading level. Before it a heading is read as plain text.
- **A tray icon sized for the task bar's own monitor** from version 1607, build 14393. Before it
  the icon is sized for the scale the application started at, which on a second monitor with a
  different scale is the wrong one.

## Try it before you install anything

The demo application and the theme editor are on Maven Central as runnable artifacts, and
[jbang](https://www.jbang.dev/download/) starts either from its name — fetching a JDK first
if you have none:

{% tabs os "Windows / Linux" macOS %}
```bash
# the showcase: an application, and every widget beside it
jbang demo@limn-toolkit/limn-toolkit
# the theme editor, which writes a theme file
jbang theme-editor@limn-toolkit/limn-toolkit
```
```bash
# the showcase: an application, and every widget beside it
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

The backend brings its native binaries, LWJGL's, the SVG renderer's and, for Windows and macOS,
ANGLE's, with it for every desktop platform: Windows, macOS and Linux, x64 and ARM alike. There is
nothing per-platform to add: the JVM loads the slice that matches the machine it is running on, and
the rest sit inert on the classpath, costing download size and nothing else. A build that ships one
platform at a time, or runs on the module path, keeps only that platform's:
[Packaging](/docs/packaging/#natives-for-one-platform) shows how.

It also brings the fonts a UI cannot do without: Roboto (the default family) and the four
faces that make Arabic, Hebrew, Devanagari and Thai render, each versioned with the font
rather than with the toolkit. Two faces are yours to opt into, because together they are
23.6 MB an app that never draws them should not carry — the pan-CJK face (Chinese, Japanese,
Korean) and colour emoji:

{% tabs build Gradle Maven %}
```kotlin title="build.gradle.kts"
dependencies {
    // Every fallback face at the versions this release was tested with…
    runtimeOnly("io.github.limn-toolkit:limn-fonts-all:{{version}}")
    // …or the two heavyweights by name, pinned to the FONT's own version:
    // runtimeOnly("io.github.limn-toolkit:limn-fonts-noto-cjk:2.004.2")
    // runtimeOnly("io.github.limn-toolkit:limn-fonts-noto-emoji:2.057.0")
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
  <version>2.057.0</version>
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
4. **`runEventLoop()` blocks** until the last window closes, and says why it returned.
   [Closing a window, and ending the application](/docs/menus-and-dialogs/#closing-a-window-and-ending-the-application)
   covers "unsaved changes" and Quit.

## Versions and compatibility

From 1.0 on, nothing an application uses breaks before 2.0. Every 1.x release builds and runs
the code written for any earlier 1.x, so moving to the newest 1.x is always safe, and fixes go
into the next 1.x rather than into older lines. A method marked `@Deprecated` keeps working
until 2.0, and JDK 17 stays the minimum until 2.0.

The one exception is the package `limn.backend.spi`, which an application never touches: it is
for someone writing a backend of their own, it is incubating, and it may change in a 1.x minor,
always with the migration in the
[changelog](https://github.com/limn-toolkit/limn-toolkit/blob/main/CHANGELOG.md).

Any of Limn's enums may gain constants in a 1.x minor, so a `switch` over one needs a `default`
branch.

What each release changed, and what to change in your code when you move to it, is in the
[changelog](https://github.com/limn-toolkit/limn-toolkit/blob/main/CHANGELOG.md). A
vulnerability is reported privately, as
[SECURITY.md](https://github.com/limn-toolkit/limn-toolkit/blob/main/SECURITY.md) describes, and
fixes are made on the latest release.

## Where to go next

[Widgets and scenes](/docs/widgets/) explains the model this program uses without saying so.
If you would rather look around first, the toolkit ships a demo application: a screen of
orders, and beside it a gallery with every component.
