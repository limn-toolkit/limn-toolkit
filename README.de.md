<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>Desktop-Anwendungen in Java, von Grund auf gezeichnet.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">Website</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">Loslegen</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">Komponenten</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">API-Referenz</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.pt-BR.md">Português (Brasil)</a> ·
  <a href="README.es.md">Español</a> ·
  <b>Deutsch</b> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.zh-Hans.md">简体中文</a> ·
  <a href="README.zh-Hant.md">繁體中文</a> ·
  <a href="README.ar.md">العربية</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="Eine Limn-Anwendung: Menüleiste, Reiter, Formulare, Diagramme und eine Designwahl" width="900">
  </picture>
</p>

Limn zeichnet seine Pixel selbst. Widgets, Layout, Text, Diagramme, Medien und ein 3D-Viewport, in
einer Abhängigkeit, **ohne Swing, ohne JavaFX und ohne natives Toolkit darunter**.

**Offen gebaut mit Claude Code:** Claude hat es geschrieben, Menschen haben es gesteuert und
geprüft. Was der Bau gekostet hat, ist schon bezahlt, und Sie müssen es nicht noch einmal bezahlen.
[Wie Limn entsteht](#wie-limn-entsteht)

## Jetzt ausprobieren

Die ganze Auslage — jedes Widget, die Diagramme, der Mediaplayer, der 3D-Viewport — in einem
Befehl, und der Theme-Editor in einem zweiten. Nichts zu klonen, und nichts zu installieren außer
[jbang](https://www.jbang.dev/download/), das auch ein JDK holt, wenn Sie keines haben. Beide
kommen als schlanke Artefakte von Maven Central: was ankommt, sind das Toolkit, die Schriften und
die nativen Bibliotheken der Maschine, an der Sie sitzen — nicht die der anderen fünf Plattformen.

Unter macOS:

```bash
jbang --java-options=-XstartOnFirstThread demo@limn-toolkit/limn-toolkit
jbang --java-options=-XstartOnFirstThread theme-editor@limn-toolkit/limn-toolkit
```

Unter Linux und Windows:

```bash
jbang demo@limn-toolkit/limn-toolkit
jbang theme-editor@limn-toolkit/limn-toolkit
```

Die Option gibt es nur unter macOS, und eine JVM, die sie anderswo bekommt, startet nicht. Die
beiden Befehle starten die neueste Version; um eine festzunageln, nennen Sie stattdessen die
Koordinate — `io.github.limn-toolkit:limn-demo:x.y.z` und
`io.github.limn-toolkit:limn-theme-editor:x.y.z`. Ohne Netz gibt es die Demo auch als eine Datei
an jedem Release, mit allen Plattformen darin:

```bash
jbang https://github.com/limn-toolkit/limn-toolkit/releases/latest/download/limn-demo-all.jar
```

JBang ermittelt die neueste Version einmal und behält sie: Fügen Sie `--fresh` hinzu, um eine neuere zu holen, etwa `jbang --fresh demo@limn-toolkit/limn-toolkit`.

## Installation

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

`x.y.z` ist das aktuelle Release — das Maven-Central-Badge oben auf dieser Seite zeigt es, und
jede Koordinate auf dieser Seite trägt dieselbe Nummer.

Diese eine Zeile ist die ganze Installation. `limn-backend-lwjgl` ist das Fenster und der Renderer,
und es exportiert `limn-toolkit` — die Widgets, das Layout und den Szenengraphen — an alles, was
davon abhängt. Das Backend bringt die nativen Bibliotheken von LWJGL für jede Desktop-Plattform
mit, es gibt also keinen Classifier zu wählen.

> [!IMPORTANT]
> Unter macOS braucht die JVM `-XstartOnFirstThread`. Das ist die eine Plattform-Eigenheit, die
> Ihnen am ersten Tag begegnet, und sie gilt nur für macOS — eine JVM anderswo startet mit diesem
> Flag nicht.

### Video abspielen

`VideoView` steckt in der Zeile oben, und die reinen Java-Decoder dahinter ebenso. Was sich damit
abspielen lässt, ist Y4M und eine synthetische Quelle; MP4 und Matroska brauchen FFmpeg, und das
ist eine eigene Abhängigkeit, weil es das eine Stück von Limn mit einer nativen Nutzlast und einer
eigenen Lizenz ist.

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:x.y.z")
    runtimeOnly("io.github.limn-toolkit:limn-ffmpeg-natives:7.1.5.1:natives-macos-aarch64")
}
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-video-ffmpeg</artifactId>
  <version>x.y.z</version>
</dependency>
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-ffmpeg-natives</artifactId>
  <version>7.1.5.1</version>
  <classifier>natives-macos-aarch64</classifier>
  <scope>runtime</scope>
</dependency>
```

</details>

Die erste Zeile bringt das Java und mit ihm den JNI-Shim für jede Plattform. Die zweite bringt die
FFmpeg-Bibliotheken — aus `limn-ffmpeg-natives`, einem Artefakt, dessen Version FFmpeg folgt und
nicht dem Toolkit, sodass es über Limn-Upgrades hinweg in Ihrem Cache bleibt — einen Classifier je
Ziel, sodass ein Rechner etwa zwei Megabyte lädt statt aller sechs:

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

Nehmen Sie stattdessen `limn-video-ffmpeg-natives-all`, wenn ein Build an jede Plattform
ausgeliefert wird und den Rechner nicht kennen kann, auf dem er landet: Es ist eine eigene POM,
deren Version dem Toolkit folgt, und sie nennt alle sechs in der Nutzlast-Version, mit der dieses
Release getestet wurde, damit Sie es nicht tun müssen. Es hindert Sie auch nichts daran, mehrere
Classifier zu nennen — ein Bündel für zwei Ziele nimmt zwei.

```kotlin
runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg-natives-all:x.y.z")
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-video-ffmpeg-natives-all</artifactId>
  <version>x.y.z</version>
  <type>pom</type>
  <scope>runtime</scope>
</dependency>
```

</details>

Lassen Sie den Classifier weg, und das Toolkit baut und läuft trotzdem: Der Decoder meldet sich als
nicht verfügbar und nennt die Plattform, nach der er gesucht hat, und alles, was nicht FFmpeg ist,
funktioniert weiter. Der FFmpeg-Build steht unter LGPL-2.1-oder-später, ist dynamisch gebunden und
austauschbar und führt seinen Lizenztext in dem Jar mit, das ihn enthält.

## Ein Fenster auf dem Bildschirm

```java
public static void main(String[] args) {
    try (Backend backend = new LwjglBackend()) {
        NativeWindow window = backend.createWindow(
                WindowConfig.of("Hello, Limn", 480, 320));

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

Keine Auszeichnungssprache, kein Annotation Processor, kein Build-Plugin. Widgets sind Objekte,
die Sie konstruieren.

## Was Sie bekommen

**Ein Komponentensatz, den Sie nicht selbst bauen müssen.** Schaltflächen, Felder, Listen, Reiter,
Menüs, Dialoge, geteilte Bereiche, ein Farbwähler, Balken-, Linien- und Ringdiagramme sowie eine
virtualisierte Liste, in der eine Million Zeilen so viel kostet wie zwanzig. Jede davon liest
Farbe, Form und Dichte aus dem Design.

**Tabellen, Bäume und Datumsangaben, schon gebaut.** Eine Tabelle sind typisierte Spalten über der
Liste, die Ihre Anwendung besitzt, sortiert vom Toolkit oder von Ihrem Server, mit einer Fußzeile,
die summiert; ein Baum ist eine Gliederung über Kindern, die Sie liefern, und eine Zeile darf
Kinder versprechen, bevor sie sie benennen kann; ein Datumsfeld tippt ein ISO-Datum im Kalender
und Format des Lesers selbst, und ein Datumswähler öffnet dahinter ein Monatsraster. Screenreader
auf den drei Plattformen lesen alle drei.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-data-dark.webp">
    <img src="media/readme/showcase-data-light.webp" alt="Eine Tabelle, ein Baum, ein Datumsfeld und ein Datumswähler in einem Limn-Fenster" width="900">
  </picture>
</p>

**Layout, das in den Kopf passt.** Vier Widgets und ein Marker: eine Spalte stapelt, eine Zeile
verteilt, ein Stapel überlagert, Padding rückt ein, und `Expanded` sagt, wer den übrigen Platz
bekommt. Es gibt keinen Constraint-Solver zu konfigurieren und keinen Layout-Manager zu
installieren.

**Das Aussehen Ihres Produkts, nicht das des Toolkits.** Ein Theme sind reine Daten — jede Farbe,
der Eckenradius, die Größenstufe, die jedes Steuerelement erbt — und ein Aufruf tauscht es zur
Laufzeit.

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="Dieselbe Oberfläche, unter sieben Themes gerendert" width="900">
</p>

**Die Sprachen Ihrer Nutzer.** Text wird mit denselben Vorschüben gemessen, mit denen er gezeichnet
wird, und der Schrift-Fallback wählt eine Schrift je Abschnitt eines Schriftsystems, so mischen sich Latein, Griechisch, Kyrillisch
und CJK in einer Zeichenkette, ohne dass Sie eine Schrift wählen — die CJK- und Emoji-Schriften
kommen über eine einzige Opt-in-Abhängigkeit (`limn-fonts-all`), der Rest kommt mit dem Backend.
Eingabemethoden komponieren im Feld selbst, und die Bearbeitung bewegt sich in Graphem-Clustern.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="Derselbe Bildschirm, aufgenommen auf Japanisch, vereinfachtem Chinesisch, Koreanisch und Russisch" width="900">
  </picture>
</p>

**Vorgelesen, in denselben Sprachen.** Ein Fenster veröffentlicht sich unter Windows an UI
Automation, unter macOS an NSAccessibility und unter Linux an AT-SPI2, sodass NVDA, VoiceOver und
Orca eine Limn-Oberfläche beschreiben, ohne dass Ihre Anwendung eine Zeile plattformspezifischen
Code schreibt. Die Namen sind dieselben `I18nString`s, die die Oberfläche zeichnet, und das Toolkit
bringt sein eigenes Wort für jede Rolle in jeder ausgelieferten Sprache mit — weil zwei der drei
Plattformen keines nennen.

**Video und 3D sind auch nur Widgets.** Ein physikalisch basierter 3D-Viewport und ein Videoplayer,
zusammengesetzt wie gewöhnliche Widgets: eine Scroll-Ansicht beschneidet sie, ein Stapel zeichnet
darüber, und sie nehmen am Layout teil wie eine Beschriftung.

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="Der 3D-Viewport, eingesetzt in ein gewöhnliches Fenster" width="900">
</p>

## Machen Sie es zu Ihrem

Aus einem Theme kommt jede Farbe, jeder Eckenradius und jede Größenstufe, und
`limn-theme-editor` ist das Programm, das eines schreibt. Starten Sie es:

```bash
jbang --main limn.themeeditor.ThemeEditorApp io.github.limn-toolkit:limn-theme-editor:x.y.z
```

Dieselbe macOS-Option wie oben. Was es speichert, ist eine `.limntheme`-Datei, schlichte Daten, die
Ihre Anwendung mit `ThemeFormat` aus `limn-toolkit` lädt. Der Editor selbst ist nie eine
Abhängigkeit Ihrer Anwendung.

## Die Module

<table>
  <tr>
    <td nowrap><samp>limn-toolkit</samp></td>
    <td>der Widget-Satz, Layout, der Szenengraph, die Backend-SPIs und die reinen Java-Video-Decoder; ohne jede Abhängigkeit</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-backend-lwjgl</samp></td>
    <td>GLFW, OpenGL und stb hinter diesen SPIs</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-video-ffmpeg</samp></td>
    <td>H.264/HEVC/VP9/VP8 und AAC/Opus/Vorbis über FFmpeg; die Nutzlast ist <code>limn-ffmpeg-natives</code>, dessen Version FFmpeg folgt, ein Classifier je Desktop-Ziel</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-icons-tabler</samp></td>
    <td>das Tabler-Icon-Paket, falls Sie es wollen — inzwischen ein eigenes Artefakt, dessen Version Tabler folgt (<code>3.46.0.x</code> ist Tabler 3.46.0)</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-test</samp></td>
    <td>Tests Ihrer Oberfläche ohne Bildschirm: ein Treiber, der in einer Szene klickt, tippt und Tasten drückt, eine Laufzeit und ein Backend ohne Anzeige, und die Barrierefreiheitsverträge, an denen die Widgets gemessen werden</td>
  </tr>
  <tr>
    <td nowrap><samp>limn-fonts-all</samp></td>
    <td>die Pan-CJK- und Farb-Emoji-Schriften (26 MB, die eine Anwendung, die sie nie zeichnet, nicht tragen sollte), dazu die übrigen Fallbacks, in den Versionen, mit denen dieses Release getestet wurde — jede Schrift ein eigenes Artefakt, dessen Version der Schrift folgt</td>
  </tr>
</table>

## Dokumentation

Die [Website](https://limn-toolkit.github.io/limn-toolkit) ist die Dokumentation: eine
[Installationsanleitung](https://limn-toolkit.github.io/limn-toolkit/docs/install/), die mit einem
laufenden Programm endet, eine
[Komponentengalerie](https://limn-toolkit.github.io/limn-toolkit/components/), in der jedes Bild
während dieses Builds vom Toolkit gerendert wurde, und die vollständige
[API-Referenz](https://limn-toolkit.github.io/limn-toolkit/api/).

Entwurfsentscheidungen stehen in [`docs/adr/`](docs/adr/), und wie ein Release entsteht, in
[`RELEASING.md`](RELEASING.md).

## Wie Limn entsteht

Der Code von Limn wird von Claude, dem Modell von Anthropic, in Claude Code geschrieben und von
Menschen gesteuert und geprüft. Wir sagen das gleich vorweg, weil Sie es wissen wollen würden.

Gesteuert heißt: Jede Entwurfsentscheidung ist ein schriftlicher Beschluss in
[`docs/adr`](docs/adr), begründet und angenommen, bevor der Code entsteht. Geprüft heißt: Jede
Änderung läuft gegen eine Testsuite, die umfangreicher ist als der Code selbst; die Barrierefreiheit
wurde angehört statt angenommen, mit NVDA, VoiceOver und Orca, die echte Fenster unter Windows,
macOS und Linux vorlesen; und der Code ist durch wiederholte Audits zu Korrektheit, Leistung und
API-Design gegangen.

Bis hierher waren viele tausend Modellaufrufe und Millionen erzeugter Tokens nötig. Nach dem
Schätzmodell COCOMO II entsprechen die über hunderttausend Codezeilen, Tests nicht mitgezählt, der
Arbeit eines Teams von etwa zwanzig Personen über mehr als zwei Jahre. Diese Arbeit ist getan. Sie
können darauf aufbauen, statt sie zu wiederholen.

## Aus dem Quelltext bauen

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

Die Artefakte zielen auf JDK 17; der Build selbst läuft auf 21 und braucht eines. Ein JDK 21, das
dort installiert ist, wo Gradle sucht, wird so verwendet; eines an einem unüblichen Ort wird mit
`org.gradle.java.installations.paths` in `~/.gradle/gradle.properties` benannt; und wird gar keines
gefunden, lädt der Toolchain-Resolver beim ersten Gebrauch eines herunter — der einzige
Netzwerkzugriff des Builds neben dem Auflösen von Abhängigkeiten. Auf einem Rechner ohne GPU werden
die GL-gestützten Tests übersprungen, statt zu scheitern.

Die MP4-Wiedergabe braucht eine native Nutzlast, die **nicht** in diesem Repository liegt: Es ist
das Artefakt [`limn-ffmpeg-natives`](https://github.com/limn-toolkit/limn-ffmpeg-natives), dessen
Version FFmpeg folgt, und der Build löst die Version, mit der er getestet wurde, wie jede andere
Abhängigkeit von Maven Central auf — die Tests und die Demo spielen Video ab, ohne dass lokal etwas
gebaut wird. Die Writer-Tests brauchen einen Encoder, den nichts Veröffentlichtes enthält; ein
`full`-Build in einem Geschwister-Klon jenes Repositorys wird automatisch aufgegriffen.

Wer am Toolkit selbst arbeitet, beginnt bei [`docs/design/README.md`](docs/design/README.md): Es
sagt, was in ein Javadoc, in ein ADR und in eine Design-Notiz gehört, und verzeichnet die Notizen
nach Subsystem. Die Website ist ein Konsument dieses Repositorys mit einem eigenen Build,
beschrieben in [`docs/design/website.md`](docs/design/website.md).

Wie man eine Änderung vorschlägt und was ein Pull Request mitbringt, steht in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Lizenz

[Apache-2.0](LICENSE), mit einer ausdrücklichen Patentgewährung. Die mitgelieferten Komponenten
sind mit ihren eigenen Lizenzen in [`NOTICE`](NOTICE) aufgeführt; der FFmpeg-Decoder steht unter
LGPL-2.1-oder-später und führt seinen Lizenztext im eigenen Jar mit.
