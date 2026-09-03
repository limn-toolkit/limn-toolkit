<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>Desktop apps in Java, drawn from scratch.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">Website</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">Get started</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">Components</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">API reference</a>
</p>

<p align="center">
  <b>English</b> ·
  <a href="README.pt-BR.md">Português (Brasil)</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh-Hans.md">简体中文</a> ·
  <a href="README.zh-Hant.md">繁體中文</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="A Limn application: menu bar, tabs, forms, charts and a theme picker" width="900">
  </picture>
</p>

Limn draws its own pixels. Widgets, layout, text, charts, media and a 3D viewport, in one
dependency, with **no Swing, no JavaFX and no native toolkit underneath**.

## Try it now

The kitchen sink — every widget, the charts, the media player, the 3D viewport — in one command.
Nothing to clone, and nothing to install but
[jbang](https://www.jbang.dev/download/), which fetches a JDK too if you have none:

```bash
jbang https://github.com/limn-toolkit/limn-toolkit/releases/latest/download/limn-demo-all.jar
```

On macOS add `--java-options=-XstartOnFirstThread`. That flag is macOS-only, and a JVM given it
anywhere else refuses to start.

## Install

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:x.y.z")
}
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-backend-lwjgl</artifactId>
  <version>x.y.z</version>
</dependency>
```

</details>

`x.y.z` is the current release — the Maven Central badge at the top of this page shows it, and
every coordinate on this page takes the same number.

That one line is the whole install. `limn-backend-lwjgl` is the window and the renderer, and it
exports `limn-toolkit` — the widgets, the layout and the scene graph — to whatever depends on it.
The backend brings LWJGL's natives for every desktop platform, so there is no classifier to
choose.

> [!IMPORTANT]
> On macOS the JVM needs `-XstartOnFirstThread`. It is the one platform quirk you meet on day
> one, and it is macOS-only — a JVM elsewhere given that flag will not start.

### Playing video

`VideoView` is in the line above, and so are the pure-Java decoders behind it. What that plays is
Y4M and a synthetic source; MP4 and Matroska need FFmpeg, which is a separate dependency because
it is the one piece of Limn with a native payload and a licence of its own.

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:x.y.z")
    runtimeOnly("io.github.limn-toolkit:limn-ffmpeg-natives:7.1.5.0:natives-macos-aarch64")
}
```

The first line brings the Java and, with it, the JNI shim for every platform. The second brings
the FFmpeg libraries — from `limn-ffmpeg-natives`, an artifact that versions with FFmpeg rather
than with the toolkit, so it stays in your cache across Limn upgrades — one classifier per target,
so a machine downloads about two megabytes rather than all six:

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

Use `limn-video-ffmpeg-natives-all` instead when one build is shipped to every platform and cannot
know the machine it will land on: it is a POM of its own, versioned with the toolkit, and it names
all six at the payload version this release was tested with, so you do not have to. Nothing stops
you naming several classifiers, either — a bundle for two targets takes two.

```kotlin
runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg-natives-all:x.y.z")
```

Leave the classifier out and the toolkit still builds and runs: the decoder reports itself
unavailable, naming the platform it looked for, and everything that is not FFmpeg keeps working.
The FFmpeg build is LGPL-2.1-or-later, dynamically linked and replaceable, and carries its licence
text in the jar that holds it.

## A window on screen

```java
public static void main(String[] args) {
    try (Backend backend = new LwjglBackend()) {
        NativeWindow window = backend.createWindow(
                new WindowConfig("Hello, Limn", 480, 320, true, true));

        Column column = new Column();
        column.gap(12);
        column.add(new Label("A window, drawn by Limn."));
        column.add(new Button("Close").onAction(window::requestClose));

        Scene scene = new Scene(new Padding(Insets.all(24), column));
        scene.bind(window);

        backend.runEventLoop();
    }
}
```

No markup, no annotation processor, no build plugin. Widgets are objects you construct.

## What you get

**A component set you do not have to build.** Buttons, fields, lists, tabs, menus, dialogs, split
panes, a colour picker, bar, line and donut charts, and a virtualized list where a million rows
cost what twenty do. Every one reads its colour, shape and density from the theme.

**Layout that fits in your head.** Four widgets and one marker: a column stacks, a row spreads, a
stack overlays, padding insets, and `Expanded` says who takes the space that is left. No
constraint solver to configure, no layout manager to install.

**Your product's look, not the toolkit's.** A theme is plain data — every colour, the corner
radius, the size step every control inherits — and one call swaps it at runtime.

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="The same interface rendered under seven themes" width="900">
</p>

**Your users' languages.** Text is measured with the same advances it is drawn with, and font
fallback runs per character, so Latin, Greek, Cyrillic and CJK mix in one string without you
choosing a face — the CJK and emoji faces ride one opt-in dependency (`limn-fonts-all`), the
rest come with the backend. Input methods compose inside the field, and editing moves by
grapheme cluster.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="The same screen captured in Japanese, Simplified Chinese, Korean and Russian" width="900">
  </picture>
</p>

**Video and 3D are widgets too.** A physically based 3D viewport and a video player composite like
any other widget: a scroll view clips them, a stack draws over them, and they take part in layout
the way a label does.

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="A 3D viewport composited into an ordinary window" width="900">
</p>

## Make it yours

A theme is where every colour, every corner radius and every size step comes from, and
`limn-theme-editor` is the screen that authors one. Embed it in your own settings screen, or just
run it:

```bash
jbang --main limn.themeeditor.ThemeEditorApp io.github.limn-toolkit:limn-theme-editor:x.y.z
```

Same macOS flag as above. What it saves is plain data, which your application loads with
`ThemeFormat`.

## The modules

| | |
| --- | --- |
| `limn-toolkit` | the widget set, layout, the scene graph, the backend SPIs and the pure-Java video decoders; depends on nothing |
| `limn-backend-lwjgl` | GLFW, OpenGL and stb behind those SPIs |
| `limn-video-ffmpeg` | H.264/HEVC/VP9/VP8 and AAC/Opus/Vorbis via FFmpeg; the payload is `limn-ffmpeg-natives`, versioned with FFmpeg, one classifier per desktop target |
| `limn-icons-tabler` | the Tabler icon pack, if you want it — an artifact of its own now, versioned with Tabler (`3.46.0.x` is Tabler 3.46.0) |
| `limn-theme-editor` | the screen that authors a theme, embeddable in your application |
| `limn-fonts-all` | the pan-CJK and colour-emoji faces (26 MB an app that never draws them should not carry), plus the rest of the fallbacks, at the versions this release was tested with — each face an artifact of its own that versions with the font |

## Before you commit to it

Every toolkit trades something. These are the trades, said early, because finding them in week
three is worse than reading them now.

- **Right to left is a line you write, not something the language implies.** Arabic, Hebrew,
  Devanagari and Thai shape wherever text is drawn, and `scene.setLayoutDirection(LayoutDirection.RTL)`
  mirrors the whole interface — rows, insets, alignment, scrollbars, popups, arrow keys — as an
  axis each subtree can override. The toolkit never infers that direction from the locale,
  because a Hebrew window usually holds a left-to-right island, so the two are declared
  separately. Vertical writing (Mongolian, the vertical form of CJK) is not supported.
- **No screen reader bridge.** Keyboard navigation and focus rings are complete, but nothing is
  exposed to the platform's accessibility APIs.
- **Before 1.0.** The API still moves between releases, and OpenGL is the only rendering path.
  Pin your version and read the release notes.

## Documentation

The [website](https://limn-toolkit.github.io/limn-toolkit) is the documentation: an
[install guide](https://limn-toolkit.github.io/limn-toolkit/docs/install/) that ends with a
running program, a [component gallery](https://limn-toolkit.github.io/limn-toolkit/components/)
where every image was rendered by the toolkit during that build, and the full
[API reference](https://limn-toolkit.github.io/limn-toolkit/api/).

Design decisions live in [`docs/adr/`](docs/adr/), and how a release is made in
[`RELEASING.md`](RELEASING.md).

## Building from source

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

JDK 17 is what the artifacts target; the build itself runs on 21, and needs one. A JDK 21 that is
installed where Gradle looks is used as it is; one in a non-standard place is named with
`org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`; and when none is found at
all, the toolchain resolver downloads one on first use, which is the one network access the build
makes beyond fetching dependencies. On a machine with no GPU the GL-backed tests skip rather than
fail.

MP4 playback needs a native payload that is **not** in this repository: it is the
[`limn-ffmpeg-natives`](https://github.com/limn-toolkit/limn-ffmpeg-natives) artifact, versioned
with FFmpeg, and the build resolves the version it was tested with from Maven Central like any
other dependency — the tests and the demo play video with nothing built locally. The writer tests
need an encoder nothing published carries; a `full` build in a sibling clone of that repository is
picked up automatically.

Working on the toolkit itself starts at [`docs/design/README.md`](docs/design/README.md): it says
what belongs in a Javadoc, in an ADR and in a design note, and indexes the notes by subsystem. The
website is a consumer of this repository with a build of its own, described in
[`docs/design/website.md`](docs/design/website.md).

## License

[Apache-2.0](LICENSE), with an explicit patent grant. The bundled components and their own
licences are listed in [`NOTICE`](NOTICE); the FFmpeg decoder is LGPL-2.1-or-later and carries its
licence text inside its jar.
