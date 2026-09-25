---
title: "Packaging"
description: "Turning your application into something you can send someone: natives, the macOS flag, and a self-contained bundle."
---

## What has to ship

Two things beyond your own code:

1. **The toolkit jars**: `limn-toolkit` and `limn-backend-lwjgl`. The
   dependency block you develop with is the one you ship: the backend declares LWJGL's
   native binaries for every desktop platform, x64 and ARM alike, so there is no
   per-platform classifier to remember at packaging time. The JVM loads the slice for the
   machine it lands on; the rest sit inert.
2. **A JDK 17 or newer**, unless you are confident your users already have one. Bundling it
   is usually the right answer for a desktop application.

If you use the optional FFmpeg video decoder, its native libraries are a separate artifact,
`limn-ffmpeg-natives`, one `natives-<os>-<arch>` classifier per desktop target, and again the
dependency block you develop with is the one you ship: name the classifier for the machine you
build for, or `limn-video-ffmpeg-natives-all` for a bundle that goes everywhere
([Images and media](/docs/images-and-media/) shows both). Where a slice is missing the decoder
reports itself unavailable and the rest of the application is unaffected. Those libraries are a
trimmed FFmpeg under the LGPL, version 2.1 or later, dynamically linked and replaceable,
which is what that licence asks. Each jar carries the licence text and the notice that
shipping them requires, so a distribution that includes it has already shipped both. If
you would rather distribute no FFmpeg at all, leave the module out: nothing depends on it, and
every other media format keeps working.

## The macOS flag, one more time

macOS needs `-XstartOnFirstThread`, and Windows and Linux JVMs refuse to start when they are
given it. That rules out putting it in a shared `applicationDefaultJvmArgs`, because Gradle
bakes those into the start scripts of *every* platform's distribution.

Set it per platform instead: in the macOS launcher, in the app bundle's `Info.plist`
arguments, or from the environment:

```bash
JAVA_OPTS=-XstartOnFirstThread bin/your-app
```

If you build a real `.app` with `jpackage`, pass it as a Java option there and the launcher
carries it for you.

## A runnable distribution

Gradle's application plugin gets you most of the way:

```bash
./gradlew installDist   # a runnable tree under build/install
./gradlew distZip       # the same thing, zipped
```

For something users can double-click, `jlink` trims a runtime to the modules you actually
use and `jpackage` wraps that into an `.app`, an `.msi` or a `.deb`. Both ship with the JDK;
neither needs anything from the toolkit.

## On the module path

Most applications run from the class path, and nothing in this section applies to them.
An application that is a module of its own requires the backend, which brings the toolkit
with it:

```java
module com.example.app {
    requires limn.backend.lwjgl;
}
```

| Module | What it is |
| --- | --- |
| `limn.toolkit` | the widgets, layout, scene graph and SPIs |
| `limn.backend.lwjgl` | the LWJGL backend |
| `limn.video.ffmpeg` | the FFmpeg video decoder |
| `limn.test` | the test driver and headless doubles |

Packages with `internal` in their name are shared between these modules and are not
exported to yours. Only the packages a module exports are yours, and only they are in the API
reference: the shared `internal` packages and the backend's screen-reader bridges are not
exported.

The font jars and the FFmpeg native jars need nothing: each names itself as a module, the
backend and the video decoder require them, and the JVM loads them from the module path. Two
kinds of jar still need a word:

- **LWJGL's native jars.** They are modules that nothing requires, so on the module path they are
  never loaded. Put your platform's jars on the class path, or keep them on the module path and
  add the `--add-modules` line the backend prints when it stops for want of them.
- **jlayer**, the MP3 decoder. It has no module name, and the backend reads it from the class
  path, which is where Gradle puts it.

**Your own resources.** On the module path, a module's resources are private to it. The
toolkit can still load one by name, with `Images.fromResource` or `PropertyBundle.family`, in
two cases. Either the resource sits in a package your module `opens`, or it sits in a folder
whose name is not a valid package name, such as `app-images/`. Otherwise, open the resource
in your own code and hand the stream over:

```java
Image logo = Images.decode(Resources.bytes(
        App.class.getResourceAsStream("/com/example/app/logo.png"), "logo", "image"));

I18n.addBundle(PropertyBundle.family("/com/example/app/i18n/strings",
        name -> App.class.getResourceAsStream(name)));
```

## Checking it before you send it

Run the packaged build on a machine that has never seen your project. That is where a
missing native classifier or a forgotten flag shows up, and nowhere earlier. Three specific
things to check:

- **Startup on a machine with no JDK installed**, if you bundled one.
- **The window opens on macOS.** If it exits instantly, the flag did not reach the JVM.
- **A software-rendering fallback**, if your users might be on a virtual machine or a remote
  desktop. The toolkit runs against Mesa's software rasteriser; it is slower, and it works.

When a machine you cannot reach will not start it, ask the machine: `Backend.graphicsInfo()`
reports the windowing platform, the driver and the graphics context the backend actually
obtained, or the reason none was. Print it from a diagnostic flag or put it in your error
dialog, and a bug report carries the difference between a vendor driver, a translation
layer and no OpenGL at all, which no stack trace says. [Troubleshooting](/docs/troubleshooting/)
lists the messages a machine that will not start prints, and the JVM flags JDK 24 and later
ask for.
