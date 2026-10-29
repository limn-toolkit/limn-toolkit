# Native image spike

Whether a Limn application compiles ahead of time into one executable with GraalVM's
`native-image`, measured on the Showcase (`limn-demo-all.jar`) with GraalVM CE 25.0.4
(25.4.4.1.1). It does, unchanged: nothing in the toolkit or the demo was modified for it.

## What was run

| Machine | Build | Executable | Checked |
|---|---|---|---|
| macOS 27 guest, arm64, 4 cores | 1 min | 119 MB | every scene as a screenshot; then by hand: system menu bar with its check marks and a menu action, the accessibility tree read over AX, search and typing, the system file dialog round trip, tray icon and its menu, a clip played with its sound, Cmd-Q with the tray on |
| Windows 11 guest, x64 emulated on arm64, 4 cores | 7 to 10 min | 123 MB | every scene as a screenshot; the UIA tree (109 elements), a page chosen through UIA, the window closed through UIA; the video page playing the clip, linked as a window program |
| Ubuntu 24.04 guest, arm64, 2 cores, 4 GB and 4 GB of swap | 3 min | 114 MB | every scene under the agent; the video page playing the clip, the dates page in German |

Linux was built with `XMX=3g JOBS=2`: the builder peaked at 3 GB resident and dipped into swap,
and the desktop session stayed up. The macOS image was also run on a physical Mac, which is how
the hardware video path below was found.

Everything is inside the one file: the platform's native libraries, the clip, fonts, icons and
translations are resources of the image, and nothing has to sit beside it.

Started from the same machine, the macOS image reaches its first screenshot in 0.5 s against
0.93 s on GraalVM's own JVM, and holds 275 MB resident with the Showcase open against 349 MB
(three runs each, the guest otherwise idle).

LWJGL needs nothing from here: its jar carries its own native-image metadata. Its callbacks, the
ones the menu bar, the tray, the accessibility bridges and the window procedure ride on, all ran.

## How to reproduce

```
./gradlew :limn-demo:fatJar
export GRAALVM_HOME=<GraalVM for JDK 25> DEMO_JAR=limn-demo/build/libs/limn-demo-all.jar
scripts/native-image/agent-run.sh app table video ...   # optional: refresh metadata/<platform>
scripts/native-image/build.sh                           # build/native-image/limn-demo
```

On a machine with less than 8 GB, `XMX=3g JOBS=2` keeps the builder within 4 GB and swap.

On Windows, `build.cmd` does what `build.sh` does and needs the Visual Studio Build Tools with
the x64 compiler and a Windows SDK. An image is built on the system and processor it runs on;
there is no cross-compiling.

`metadata/<platform>/reachability-metadata.json` is what the tracing agent recorded while every
scene ran once. `metadata/<platform>/resources/` is written by hand, for what the agent cannot
see because no scene reads it: the platform's native libraries, the demo's media and the
toolkit's fonts, icons and catalogs.

## What an application has to know

- **Resources must be listed, by kind and not by run.** The agent records only what a run
  touched, and a run touches only the paths the machine it runs on takes. The first image had
  no video clip and no FFmpeg libraries, because no screenshot opened the clip. The second played
  the clip on the virtual machines and said "This video cannot be played" on a physical Mac: there
  VideoToolbox decodes in hardware, the picture arrives as an IOSurface, and its conversion
  shader, `video_convert_rect.frag`, was never read on a machine without a hardware decoder. The
  same gap left out ten of the Showcase's translations, the unlit 3D shader and the MP3
  library's tables. `metadata/<platform>/resources/` therefore names every kind of file the
  jar carries (`*.frag`, `*.vert`, `i18n/*.properties`, ...) rather than the files one run read;
  an application owes the same list for its own files.
- **Locales are chosen at build time.** By default an image carries only the build machine's
  locale data: the Showcase showed its own Portuguese strings with dates and money in English
  formats. `-H:+IncludeAllLocales` fixes it and costs about 33 MB; `-H:IncludeLocales=` with the
  application's languages costs less. GraalVM 25 calls the first option experimental.
- **On macOS the default locale comes from `LANG` only.** The JVM reads the system's language
  preference; the image does not, so one started from the Finder, where `LANG` is unset, comes
  up in English on a Portuguese system. Started from a terminal, or with `-Duser.language`, it
  is right. Windows read the system language without help.
- **One reflective probe misjudges unknown classes.** `Widget.paintsItself()` asks a widget class
  whether it declares `onPaint` through `getDeclaredMethod`. For a class the metadata does not
  list the answer is "no", and all that is lost is the developer warning about a painting widget
  with no accessible name. Nothing a user sees changes.
- **On Windows the image is linked as a window program.** native-image links a console program
  by default, and one opened from Explorer brings a console window up beside its own.
  `build.cmd` passes `/SUBSYSTEM:WINDOWS` with `/ENTRY:mainCRTStartup`; `CONSOLE=1` builds the
  console form, whose log can be read.
- **LWJGL prints the `sun.misc.Unsafe` deprecation warning** at start, four lines, because the
  JVM option that silences it does not exist in an image.
