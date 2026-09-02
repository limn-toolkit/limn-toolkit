<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>Aplicaciones de escritorio en Java, dibujadas desde cero.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">Sitio web</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">Empezar</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">Componentes</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">Referencia de la API</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.pt-BR.md">Português (Brasil)</a> ·
  <b>Español</b> ·
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
    <img src="media/readme/showcase-kitchen-light.webp" alt="Una aplicación de Limn: barra de menús, pestañas, formularios, gráficos y un selector de tema" width="900">
  </picture>
</p>

Limn dibuja sus propios píxeles. Widgets, disposición, texto, gráficos, medios y una vista 3D, en
una dependencia, **sin Swing, sin JavaFX y sin ningún kit nativo por debajo**.

## Pruébalo ahora

El escaparate entero — cada widget, las gráficas, el reproductor, el visor 3D — en un comando.
Nada que clonar, y nada que instalar salvo
[jbang](https://www.jbang.dev/download/), que también descarga un JDK si no tienes ninguno:

```bash
jbang https://github.com/limn-toolkit/limn-toolkit/releases/latest/download/limn-demo-all.jar
```

En macOS añade `--java-options=-XstartOnFirstThread`. Esa opción es solo de macOS, y una JVM que
la reciba en cualquier otro sistema se niega a arrancar.

## Instalación

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:0.5.0")
}
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-backend-lwjgl</artifactId>
  <version>0.5.0</version>
</dependency>
```

</details>

Esa única línea es toda la instalación. `limn-backend-lwjgl` es la ventana y el renderizador, y
exporta `limn-toolkit` — los widgets, la disposición y el grafo de escena — a todo lo que dependa
de él. El backend trae los nativos de LWJGL para todas las plataformas de escritorio, así que no
hay ningún clasificador que elegir.

> [!IMPORTANT]
> En macOS la JVM necesita `-XstartOnFirstThread`. Es la única peculiaridad de plataforma con la
> que te toparás el primer día, y es exclusiva de macOS: una JVM en cualquier otro sistema que
> reciba esa opción no arrancará.

### Reproducir vídeo

`VideoView` está en la línea de arriba, y también los decodificadores en Java puro que tiene
detrás. Lo que eso reproduce es Y4M y una fuente sintética; MP4 y Matroska necesitan FFmpeg, que es
una dependencia aparte porque es la única pieza de Limn con carga nativa y licencia propia.

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:0.5.0")
    runtimeOnly("io.github.limn-toolkit:limn-ffmpeg-natives:7.1.5.0:natives-macos-aarch64")
}
```

La primera línea trae el Java y, con él, la capa JNI para todas las plataformas. La segunda trae
las bibliotecas de FFmpeg — de `limn-ffmpeg-natives`, un artefacto que cambia de versión con FFmpeg
y no con el kit, de modo que sigue en tu caché de una actualización de Limn a otra — con un
clasificador por destino, de modo que una máquina descarga unos dos megabytes en lugar de las seis:

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

Usa `limn-video-ffmpeg-natives-all` cuando una misma compilación se distribuya a todas las
plataformas y no pueda saber en qué máquina acabará: es un POM propio, con la versión del kit, y
nombra los seis en la versión de la carga con la que se probó esta publicación, para que tú no
tengas que hacerlo. Nada te impide nombrar varios clasificadores: un paquete para dos destinos
lleva dos.

```kotlin
runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg-natives-all:0.5.0")
```

Omite el clasificador y el kit sigue compilando y ejecutándose: el decodificador se declara no
disponible, nombrando la plataforma que buscó, y todo lo que no es FFmpeg sigue funcionando. La
compilación de FFmpeg es LGPL-2.1-o-posterior, está enlazada dinámicamente y es reemplazable, y
lleva el texto de su licencia dentro del jar que la contiene.

## Una ventana en pantalla

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

Sin lenguaje de marcado, sin procesador de anotaciones, sin complemento de compilación. Los
widgets son objetos que construyes.

## Lo que obtienes

**Un conjunto de componentes que no tienes que construir.** Botones, campos, listas, pestañas,
menús, diálogos, paneles divididos, un selector de color, gráficos de barras, de líneas y de
anillo, y una lista virtualizada donde un millón de filas cuesta lo mismo que veinte. Cada uno lee
su color, su forma y su densidad del tema.

**Una disposición que cabe en la cabeza.** Cuatro widgets y un marcador: una columna apila, una
fila reparte, una pila superpone, el relleno separa y `Expanded` dice quién se queda con el espacio
sobrante. No hay solucionador de restricciones que configurar ni gestor de disposición que
instalar.

**El aspecto de tu producto, no el del toolkit.** Un tema son datos puros — cada color, el radio de
las esquinas, el paso de tamaño que hereda cada control — y una llamada lo cambia en tiempo de
ejecución.

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="La misma interfaz renderizada con siete temas" width="900">
</p>

**Los idiomas de tus usuarios.** El texto se mide con los mismos avances con los que se dibuja, y
el respaldo de fuentes funciona carácter a carácter, así que latino, griego, cirílico y CJK se
mezclan en una misma cadena sin que elijas tipografía — las tipografías CJK y de emojis llegan en
una única dependencia opcional (`limn-fonts-all`), el resto viene con el backend. Los métodos de
entrada componen dentro del campo y la edición avanza por grupos de grafemas.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="La misma pantalla capturada en japonés, chino simplificado, coreano y ruso" width="900">
  </picture>
</p>

**El vídeo y el 3D también son widgets.** Una vista 3D con materiales físicamente realistas y un
reproductor de vídeo, compuestos como widgets corrientes: una vista con desplazamiento los recorta,
una pila dibuja encima y ambos participan en la disposición igual que una etiqueta.

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="Una vista 3D compuesta en una ventana corriente" width="900">
</p>

## Hazlo tuyo

De un tema sale cada color, cada radio de esquina y cada paso de tamaño, y `limn-theme-editor` es
la pantalla que escribe uno. Inclúyelo en tu propia pantalla de ajustes, o simplemente ejecútalo:

```bash
jbang --main limn.themeeditor.ThemeEditorApp io.github.limn-toolkit:limn-theme-editor:0.5.0
```

La misma opción de macOS de arriba. Lo que guarda son datos planos, que tu aplicación carga con
`ThemeFormat`.

## Los módulos

| | |
| --- | --- |
| `limn-toolkit` | el conjunto de widgets, la disposición, el grafo de escena, las SPI del backend y los decodificadores de vídeo en Java puro; no depende de nada |
| `limn-backend-lwjgl` | GLFW, OpenGL y stb detrás de esas SPI |
| `limn-video-ffmpeg` | H.264/HEVC/VP9/VP8 y AAC/Opus/Vorbis mediante FFmpeg; la carga es `limn-ffmpeg-natives`, que cambia de versión con FFmpeg, un clasificador por destino de escritorio |
| `limn-icons-tabler` | el paquete de iconos Tabler, si lo quieres |
| `limn-theme-editor` | la pantalla que crea un tema, incorporable en tu aplicación |
| `limn-fonts-all` | las tipografías pan-CJK y de emojis a color (26 MB que una aplicación que nunca las dibuja no debería cargar), más el resto de los respaldos, en las versiones con las que se probó esta publicación — cada tipografía es un artefacto propio que cambia de versión con la fuente |

## Antes de comprometerte

Todo kit renuncia a algo. Estas son las renuncias, dichas de entrada, porque descubrirlas en la
tercera semana es peor que leerlas ahora.

- **Las escrituras complejas se dibujan en todas partes, pero nada se refleja.** El árabe, el
  hebreo, el devanagari y el tailandés se unen, se reordenan y colocan sus marcas allí donde se
  dibuje texto: dentro de `Label`, `TextField` y `TextArea`, y en cada botón, pestaña, elemento de
  menú y texto de sugerencia a su alrededor; los paquetes `ar` y `he` sí se publican. Lo que un
  idioma de derecha a izquierda no obtiene es la disposición: los márgenes, la alineación, el lado
  en el que va una barra de desplazamiento, el lado por el que se abre un menú emergente y hacia
  dónde lleva una tecla de flecha cuando el foco no está en un campo de texto — todo va de
  izquierda a derecha, sea cual sea el idioma.
- **Sin puente para lectores de pantalla.** La navegación con teclado y los anillos de foco están
  completos, pero nada se expone a las API de accesibilidad de la plataforma.
- **Anterior a 1.0.** La API todavía se mueve entre versiones, y OpenGL es la única vía de
  renderizado. Fija tu versión y lee las notas de publicación.

## Documentación

El [sitio web](https://limn-toolkit.github.io/limn-toolkit) es la documentación: una
[guía de instalación](https://limn-toolkit.github.io/limn-toolkit/docs/install/) que termina con un
programa en marcha, una [galería de componentes](https://limn-toolkit.github.io/limn-toolkit/components/)
donde cada imagen la renderizó el kit durante esa compilación, y la
[referencia completa de la API](https://limn-toolkit.github.io/limn-toolkit/api/).

Las decisiones de diseño están en [`docs/adr/`](docs/adr/), y cómo se hace una publicación, en
[`RELEASING.md`](RELEASING.md).

## Compilar desde el código fuente

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

Los artefactos apuntan a JDK 17; la compilación en sí se ejecuta sobre 21. En una máquina sin GPU
las pruebas que dependen de GL se omiten en lugar de fallar.

La reproducción de MP4 necesita una carga nativa que **no** está en este repositorio: es el
artefacto [`limn-ffmpeg-natives`](https://github.com/limn-toolkit/limn-ffmpeg-natives), que cambia
de versión con FFmpeg, y la compilación resuelve desde Maven Central la versión con la que se
probó, como cualquier otra dependencia — las pruebas y la demo reproducen vídeo sin compilar nada
en local. Las pruebas de escritura necesitan un codificador que nada de lo publicado lleva; una
compilación `full` en un clon hermano de ese repositorio se detecta automáticamente.

## Licencia

[Apache-2.0](LICENSE), con concesión explícita de patentes. Los componentes incluidos y sus propias
licencias aparecen en [`NOTICE`](NOTICE); el decodificador de FFmpeg es LGPL-2.1-o-posterior y
lleva el texto de su licencia dentro de su jar.
