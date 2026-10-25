---
title: "Packaging"
description: "Turning your application into something you can send someone: natives, the macOS flag, and a self-contained bundle."
---

## What has to ship

Two things beyond your own code:

1. **The toolkit jars**: `limn-toolkit` and `limn-backend-lwjgl`. The
   dependency block you develop with is the one you ship: the backend declares its native
   binaries, LWJGL's, the SVG renderer's and, for Windows and macOS, ANGLE's, for every desktop
   platform, x64 and ARM alike, so there is no per-platform classifier to remember at packaging
   time. The JVM loads the slice for the machine it lands on; the rest sit inert. To ship one
   platform's alone, see [Natives for one platform](#natives-for-one-platform).
2. **A JDK 17 or newer**, unless you are confident your users already have one. Bundling it
   is usually the right answer for a desktop application.

If you use the optional FFmpeg video decoder, its native libraries are a separate artifact,
`limn-ffmpeg-natives`, one `natives-<os>-<arch>` classifier per desktop target, and again the
dependency block you develop with is the one you ship: name the classifier for the machine you
build for, or `limn-video-ffmpeg-natives-all` for a bundle that goes everywhere
([Images and media](/docs/images-and-media/) shows both). One slice is 2.1 to 2.9 MB, depending
on the platform, measured at this version. Where a slice is missing the decoder
reports itself unavailable and the rest of the application is unaffected. Those libraries are a
trimmed FFmpeg under the LGPL, version 2.1 or later, dynamically linked and replaceable,
which is what that licence asks. Each jar carries the licence text and the notice that
shipping them requires, so a distribution that includes it has already shipped both. If
you would rather distribute no FFmpeg at all, leave the module out: nothing depends on it, and
every other media format keeps working.

## Natives for one platform

The one-line install brings the natives of all six desktop platforms, so that one build runs
anywhere. Measured at this version, that is 24.8 MB of jars, of which one machine uses 10.3 to
11.5 MB: the rest is the other five platforms' LWJGL and SVG natives. A build that packages one
platform at a time, an installer per target, need not carry them, and an application on the
[module path](#on-the-module-path) must not.

Three of the backend's dependencies come in one classifier per platform: LWJGL's natives
(`natives-macos-arm64`, `natives-windows`, …), the SVG renderer's, `limn-lunasvg-natives`
(`natives-macos-aarch64`, `natives-windows-x86_64`, …), and ANGLE's, `limn-angle-natives`, the
OpenGL ES fallback a machine without a usable OpenGL takes, which has classifiers for Windows and
macOS only (Linux has Mesa). Keeping one platform is keeping one classifier of each that has it:

{% tabs build Gradle Maven %}
```kotlin title="build.gradle.kts"
// The platform to keep: "macos-aarch64", "macos-x86_64", "linux-x86_64", "linux-aarch64",
// "windows-x86_64" or "windows-aarch64". The machine running Gradle unless -PlimnTarget says.
val limnTarget = findProperty("limnTarget")?.toString() ?: run {
    val os = System.getProperty("os.name").lowercase()
    val name = if ("mac" in os) "macos" else if ("win" in os) "windows" else "linux"
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
    "$name-$arch"
}

// Drops every natives-* dependency of the backend that is not for limnTarget.
abstract class OnePlatformNatives @Inject constructor(private val target: String) : ComponentMetadataRule {
    override fun execute(context: ComponentMetadataContext) {
        // LWJGL spells the same target without "-x86_64", and with "-arm64" for "-aarch64".
        val keep = setOf("natives-$target",
            "natives-" + target.removeSuffix("-x86_64").replace("-aarch64", "-arm64"))
        context.details.allVariants {
            withDependencies {
                removeIf { dependency ->
                    dependency.artifactSelectors.any {
                        it.classifier?.startsWith("natives-") == true && it.classifier !in keep
                    }
                }
            }
        }
    }
}

dependencies {
    components.withModule<OnePlatformNatives>("io.github.limn-toolkit:limn-backend-lwjgl") {
        params(limnTarget)
    }
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:{{version}}")
    // The FFmpeg payload, if you play video, spells its classifier the same way.
    // runtimeOnly("io.github.limn-toolkit:limn-ffmpeg-natives:9.0.2.1:natives-$limnTarget")
}
```
```xml title="pom.xml"
<properties>
  <lwjgl.version>3.4.3</lwjgl.version>
</properties>

<!-- This machine's natives. -Dlwjgl.natives=… -Dlimn.natives=… names another. -->
<profiles>
  <profile><id>macos-aarch64</id>
    <activation><os><family>mac</family><arch>aarch64</arch></os></activation>
    <properties><lwjgl.natives>natives-macos-arm64</lwjgl.natives><limn.natives>natives-macos-aarch64</limn.natives></properties>
    <dependencies><dependency><groupId>io.github.limn-toolkit</groupId><artifactId>limn-angle-natives</artifactId><version>2.1.8078.0</version><classifier>natives-macos-aarch64</classifier></dependency></dependencies>
  </profile>
  <profile><id>macos-x86_64</id>
    <activation><os><family>mac</family><arch>!aarch64</arch></os></activation>
    <properties><lwjgl.natives>natives-macos</lwjgl.natives><limn.natives>natives-macos-x86_64</limn.natives></properties>
    <dependencies><dependency><groupId>io.github.limn-toolkit</groupId><artifactId>limn-angle-natives</artifactId><version>2.1.8078.0</version><classifier>natives-macos-x86_64</classifier></dependency></dependencies>
  </profile>
  <profile><id>linux-aarch64</id>
    <activation><os><name>linux</name><arch>aarch64</arch></os></activation>
    <properties><lwjgl.natives>natives-linux-arm64</lwjgl.natives><limn.natives>natives-linux-aarch64</limn.natives></properties>
  </profile>
  <profile><id>linux-x86_64</id>
    <activation><os><name>linux</name><arch>!aarch64</arch></os></activation>
    <properties><lwjgl.natives>natives-linux</lwjgl.natives><limn.natives>natives-linux-x86_64</limn.natives></properties>
  </profile>
  <profile><id>windows-aarch64</id>
    <activation><os><family>windows</family><arch>aarch64</arch></os></activation>
    <properties><lwjgl.natives>natives-windows-arm64</lwjgl.natives><limn.natives>natives-windows-aarch64</limn.natives></properties>
    <dependencies><dependency><groupId>io.github.limn-toolkit</groupId><artifactId>limn-angle-natives</artifactId><version>2.1.8078.0</version><classifier>natives-windows-aarch64</classifier></dependency></dependencies>
  </profile>
  <profile><id>windows-x86_64</id>
    <activation><os><family>windows</family><arch>!aarch64</arch></os></activation>
    <properties><lwjgl.natives>natives-windows</lwjgl.natives><limn.natives>natives-windows-x86_64</limn.natives></properties>
    <dependencies><dependency><groupId>io.github.limn-toolkit</groupId><artifactId>limn-angle-natives</artifactId><version>2.1.8078.0</version><classifier>natives-windows-x86_64</classifier></dependency></dependencies>
  </profile>
</profiles>

<dependencies>
  <dependency>
    <groupId>io.github.limn-toolkit</groupId>
    <artifactId>limn-backend-lwjgl</artifactId>
    <version>{{version}}</version>
    <!-- Every platform's natives, declared again below with one classifier. -->
    <exclusions>
      <exclusion><groupId>org.lwjgl</groupId><artifactId>*</artifactId></exclusion>
      <exclusion><groupId>io.github.limn-toolkit</groupId><artifactId>limn-lunasvg-natives</artifactId></exclusion>
      <exclusion><groupId>io.github.limn-toolkit</groupId><artifactId>limn-angle-natives</artifactId></exclusion>
    </exclusions>
  </dependency>
  <dependency><groupId>io.github.limn-toolkit</groupId><artifactId>limn-lunasvg-natives</artifactId><version>3.5.0.0</version></dependency>
  <dependency><groupId>io.github.limn-toolkit</groupId><artifactId>limn-lunasvg-natives</artifactId><version>3.5.0.0</version><classifier>${limn.natives}</classifier></dependency>
  <!-- ANGLE's classifier is in the Windows and macOS profiles above: there is none for Linux. -->
  <dependency><groupId>io.github.limn-toolkit</groupId><artifactId>limn-angle-natives</artifactId><version>2.1.8078.0</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-egl</artifactId><version>${lwjgl.version}</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId><version>${lwjgl.version}</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl</artifactId><version>${lwjgl.version}</version><classifier>${lwjgl.natives}</classifier></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-glfw</artifactId><version>${lwjgl.version}</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-glfw</artifactId><version>${lwjgl.version}</version><classifier>${lwjgl.natives}</classifier></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-harfbuzz</artifactId><version>${lwjgl.version}</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-harfbuzz</artifactId><version>${lwjgl.version}</version><classifier>${lwjgl.natives}</classifier></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-openal</artifactId><version>${lwjgl.version}</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-openal</artifactId><version>${lwjgl.version}</version><classifier>${lwjgl.natives}</classifier></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-opengl</artifactId><version>${lwjgl.version}</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-opengl</artifactId><version>${lwjgl.version}</version><classifier>${lwjgl.natives}</classifier></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-opengles</artifactId><version>${lwjgl.version}</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-opengles</artifactId><version>${lwjgl.version}</version><classifier>${lwjgl.natives}</classifier></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-stb</artifactId><version>${lwjgl.version}</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-stb</artifactId><version>${lwjgl.version}</version><classifier>${lwjgl.natives}</classifier></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-tinyfd</artifactId><version>${lwjgl.version}</version></dependency>
  <dependency><groupId>org.lwjgl</groupId><artifactId>lwjgl-tinyfd</artifactId><version>${lwjgl.version}</version><classifier>${lwjgl.natives}</classifier></dependency>
  <!-- The FFmpeg payload, if you play video, spells its classifier like the SVG renderer's:
  <dependency><groupId>io.github.limn-toolkit</groupId><artifactId>limn-ffmpeg-natives</artifactId><version>9.0.2.1</version><classifier>${limn.natives}</classifier><scope>runtime</scope></dependency>
  -->
</dependencies>
```
{% endtabs %}

In Gradle, the rule takes the other platforms' natives out of the backend's dependencies before
anything is downloaded, and `-PlimnTarget=windows-x86_64` on the command line packages for
another target. Maven cannot drop one classifier of a dependency, only the whole module, so the
backend's LWJGL, SVG renderer and ANGLE are excluded and declared again, each with one classifier
(ANGLE's inside the Windows and macOS profiles, the only ones it has one for): a profile per system
picks the machine's, as `limn-demo`'s own POM does, and
`-Dlwjgl.natives=natives-windows -Dlimn.natives=natives-windows-x86_64` names another. The
versions are the ones this release was tested with; `lwjgl-egl` has no natives, because it binds
the system's own library.

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
| `limn.icons.tabler` | the Tabler icon pack, if you use it |

Packages with `internal` in their name are shared between these modules and are not
exported to yours. Only the packages a module exports are yours, and only they are in the API
reference: the shared `internal` packages and the backend's screen-reader bridges are not
exported.

The font jars and the FFmpeg native jars need nothing: each names itself as a module, the
backend and the video decoder require them, and the JVM loads them from the module path. Two
kinds of jar still need a word:

- **LWJGL's native jars.** Each LWJGL module's natives jar declares the same module name for all
  six platforms (`org.lwjgl.glfw.natives`, whichever platform it is for), so on the module path the
  first jar of a name wins, and with the one-line install that can be another platform's. They are
  also modules that nothing requires, so they are never loaded unless named. Resolve only your
  target's natives, as [Natives for one platform](#natives-for-one-platform) shows, and name them:

  ```kotlin title="build.gradle.kts"
  // LWJGL's natives modules, by name: for ./gradlew run, and for a distribution's start scripts.
  val lwjglNatives = listOf("--add-modules", "org.lwjgl.natives,org.lwjgl.glfw.natives," +
      "org.lwjgl.harfbuzz.natives,org.lwjgl.openal.natives,org.lwjgl.opengl.natives," +
      "org.lwjgl.opengles.natives,org.lwjgl.stb.natives,org.lwjgl.tinyfd.natives")
  tasks.named<JavaExec>("run") { jvmArgs(lwjglNatives) }
  application { applicationDefaultJvmArgs += lwjglNatives }
  ```

  Or put that platform's natives jars on the class path instead. When the backend stops for want
  of them, it says which of the two went wrong, and prints this line if it is the missing one.
  The SVG renderer's natives and ANGLE's need neither: each platform's jar has a module name of
  its own.
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
- **A machine without a usable OpenGL**, if your users might be on a virtual machine or a remote
  desktop. On Windows and macOS the backend runs there on OpenGL ES from ANGLE, over Direct3D 11
  or Metal, which it carries; on Linux it runs against Mesa's software rasteriser. Either is
  slower than a GPU's own driver, and it works. `-Dlimn.backend.angle=force` takes the ANGLE path
  on a machine whose OpenGL works, so it can be tried before a user meets it.

When a machine you cannot reach will not start it, ask the machine: `Backend.graphicsInfo()`
reports the windowing platform, the driver and the graphics context the backend actually
obtained, or the reason none was. Print it from a diagnostic flag or put it in your error
dialog, and a bug report carries the difference between a vendor driver, a translation
layer and no OpenGL at all, which no stack trace says. [Troubleshooting](/docs/troubleshooting/)
lists the messages a machine that will not start prints, and the JVM flags JDK 24 and later
ask for.
