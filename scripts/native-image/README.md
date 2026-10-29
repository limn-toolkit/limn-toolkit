# The Showcase as a native executable

The demo compiled ahead of time with GraalVM's `native-image`, the way an application is: what
the toolkit needs in an image comes in its own jars, and `metadata/` names only the demo's own
files (its clip, images, model and the Showcase's translations). The site's
[Native executable](../../site/src/guides/native-image.md) guide is the reference; this is the
demo's instance of it, and what the release workflow builds on each system before it publishes.

```
./gradlew :limn-demo:fatJar
export GRAALVM_HOME=<GraalVM for JDK 25> DEMO_JAR=limn-demo/build/libs/limn-demo-all.jar
scripts/native-image/build.sh          # build/native-image/limn-demo
```

On Windows, `build.cmd` does what `build.sh` does and needs the Visual Studio Build Tools with
the x64 compiler and a Windows SDK; it links the executable as a window program, and `CONSOLE=1`
builds the console form, whose log can be read. On a machine with less than 8 GB,
`XMX=3g JOBS=2` keeps the builder within 4 GB and swap.

## What was run

| Machine | Build | Executable |
|---|---|---|
| macOS 27 guest, Apple silicon, 4 cores | 1.5 min | 124 MB |
| Windows 11 guest, x64 emulated on ARM, 4 cores | 7 to 13 min | 128 MB |
| Ubuntu 24.04 guest, ARM, 2 cores, 4 GB and swap | 3 to 4 min | 119 MB |

Each image was started on the video page and on a spread of scenes, and read by the system's
screen-reader interface while it ran. The macOS image was also run on a physical Mac, where the
video is decoded in hardware: an earlier image built from metadata a tracing agent recorded on
the virtual machines played the clip there and said "This video cannot be played" on the Mac,
because the shader for pictures decoded in hardware was never read on a machine without a
hardware decoder. That is why the toolkit's metadata names its resources by kind and a test
holds each module's list against the files it ships.

Started from the same machine, the macOS image reaches its first screenshot in 0.5 s against
0.93 s on GraalVM's own JVM, and holds 275 MB resident with the Showcase open against 349 MB.
