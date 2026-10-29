---
title: "Native executable"
description: "Compiling an application ahead of time with GraalVM's native-image: one executable per system, no JDK to ship, and what your application still has to name."
---

GraalVM's `native-image` compiles an application and everything it uses into one executable
for one system. There is no JDK to install or bundle, and the window is up sooner: measured with
the Showcase on a macOS virtual machine, the executable drew its first frame in 0.5 s against
0.93 s on the JVM, and held 275 MB of memory against 349 MB. The executable came to 119 to
128 MB, depending on the system, with every language's formats included and the Showcase's own
video clip and optional fonts inside it.

The toolkit needs nothing from you to be compiled this way: its jars tell `native-image` what
they need. What your application owes is the same list for its own files, below.

An executable is built on the system and processor it will run on; there is no
cross-compiling. GraalVM builds for Linux on x64 and ARM, macOS on Apple silicon and Windows on
x64. This release was compiled and run that way on macOS with Apple silicon, Windows x64 and Linux
on ARM, and each release builds it again on all three systems before it is published.

## What you need

- **GraalVM for JDK 25.** The community edition for JDK 25.0.4 is the one tested. Its `bin` has
  `java` and `native-image`.
- **The system's C toolchain**, which `native-image` links with:
  - macOS: the command line tools, `xcode-select --install`.
  - Windows: the Visual Studio Build Tools with the x64 C++ compiler and a Windows SDK, and the
    build run from an *x64 Native Tools Command Prompt*, or after `vcvarsall.bat x64`.
  - Linux: `gcc` and the zlib headers (`zlib1g-dev`, `zlib-devel`).
- **Memory.** The builder peaked at about 3 GB of memory compiling the Showcase. On a machine
  with 4 GB, `-J-Xmx3g --parallelism=2` keeps it there, using some swap.

## Building

`native-image` takes the same jars the JVM runs. A runnable jar with every dependency inside it
is the shortest way to hand them over:

```bash
native-image -H:+IncludeAllLocales -jar build/libs/your-app-all.jar -o your-app
```

A class path works as well: `-cp` with every jar, and the main class at the end. Build from the
class path: the toolkit's native libraries are taken from there.

- `-H:+IncludeAllLocales` keeps every language's formats: without it an executable formats dates,
  numbers and money only as the machine that built it does, whatever language your translations
  are in. It costs about 33 MB; `-H:IncludeLocales=en,pt-BR,de` keeps the languages you ship and
  costs less.
- On macOS, `-XstartOnFirstThread` is not needed: an executable's `main` already runs on the
  first thread.
- On Windows, add `-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS
  -H:NativeLinkerOption=/ENTRY:mainCRTStartup`. An executable is otherwise a console program, and
  one opened from Explorer brings a console window up beside your own. Leave them out of a build
  whose log you want to read.
- On Windows, the executable's icon is a resource linked into it. Write a resource script with
  one line, `GLFW_ICON ICON "your-app.ico"`, compile it with the SDK's `rc /fo your-app.res
  your-app.rc` and add `-H:NativeLinkerOption=your-app.res`. Under that name the icon is also
  the one every window of the application shows in its title bar and the taskbar, with nothing
  to set in code.

The Showcase's own scripts, in `scripts/native-image/` of the repository, build it this way on
each system.

## What the toolkit carries for you

Each toolkit jar has a `META-INF/native-image/` that `native-image` reads on its own:

- **Its files**: shaders, translations, icons, and the faces of every font artifact, the
  optional ones included when they are on the class path.
- **The reflection and the JNI it uses**, including the lookups the FFmpeg decoder's native code
  makes.
- **The native libraries of the system being built for, and no other**: LWJGL's, the SVG
  renderer's, ANGLE's and, with `limn-video-ffmpeg`, FFmpeg's. The jars carry all six desktop
  platforms', about 130 MB, of which one system uses under 20, and the toolkit's build step picks
  the slice by the machine building.

So the video player, the SVG renderer, the ANGLE fallback and the screen-reader bridges work in
an executable as they do on the JVM.

## What your application owes

**Its own files, listed.** An executable holds only the resources something named, and a file
nobody named is missing at run time, on the screen that reads it and nowhere else. Name yours in
a `reachability-metadata.json` under your own `src/main/resources/META-INF/native-image/<group>/<artifact>/`,
by kind rather than one by one, so a file added later is in without a second thought:

```json title="reachability-metadata.json"
{
  "resources": [
    {"glob": "com/example/app/images/*"},
    {"glob": "com/example/app/i18n/*.properties"}
  ]
}
```

**The reflection its own code does.** Code that finds a class or a method by name, or reads a
field through `VarHandle` or a serialization, needs it named too. GraalVM's tracing agent writes
that list while the application runs on the JVM:

```bash
java -agentlib:native-image-agent=config-output-dir=agent-output -jar build/libs/your-app-all.jar
```

It is GraalVM's own `java` that has the agent, and on macOS it needs `-XstartOnFirstThread`
there as any JVM does.

The agent records only what that run touched. Open every screen and take every path once, then
keep what it found about your own classes; what it found about the toolkit's is already in the
toolkit's jars. Its list of resources is the run's, not your application's: a file read on
another machine, or on another path, is not in it. The toolkit learnt that from its own video
player, whose conversion shader for pictures decoded in hardware was missing from an executable
that every virtual machine played, and was found on a physical Mac. Name resources by kind, as
above.

**The licences of what it carries.** The executable contains the toolkit's native libraries, so
it carries their licences' obligations as a distribution of the jars does; the
[licence page](/licence/) lists them. FFmpeg stays replaceable: it is extracted at run time, and
`-Dlimn.video.ffmpeg.library=<directory>` loads another build of it instead.

## What is different from the JVM

- **The system's language on macOS.** The JVM reads the language the user chose in the system's
  settings; an executable reads `LANG`, which a terminal sets and a `.app` started by the system
  does not have. The backend reads the system's choice itself in that case, so an executable
  starts in the user's language either way. An application that sets its own with
  `I18n.setLocale` before the backend starts keeps it.
- **One warning at startup.** LWJGL prints four lines about `sun.misc.Unsafe`. The JVM flag that
  quiets them does not exist in an executable, and they change nothing.
- **One developer warning is not given.** On the JVM the toolkit warns about a widget that draws
  itself and has no accessible name, which it finds by asking the widget's class for an
  `onPaint` method. An executable answers that question only for classes named for reflection,
  so the warning comes from runs on the JVM, which is where it is useful.
- **The module path is not covered.** The toolkit's build step reads the class path, and that is
  how it was tested.

## Checking it before you send it

Everything [Packaging](/docs/packaging/#checking-it-before-you-send-it) says applies, and two
things more. Open every screen of the executable once, because a file left out shows only on the
screen that reads it. And run it on a physical machine of each system as well as on a virtual
one: a GPU and a hardware video decoder take paths that a machine without them never does.
