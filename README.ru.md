<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/lockup-dark.svg">
    <img src="media/readme/lockup-light.svg" alt="Limn" height="72">
  </picture>
</p>

<p align="center"><b>Настольные приложения на Java, нарисованные с нуля.</b></p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.limn-toolkit/limn-toolkit"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.limn-toolkit/limn-toolkit?label=Maven%20Central&color=6d4aff"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 17+" src="https://img.shields.io/badge/Java-17%2B-orange">
  <img alt="Windows, macOS, Linux" src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey">
  <a href="https://limn-toolkit.github.io/limn-toolkit"><img alt="Documentation" src="https://img.shields.io/badge/docs-limn--toolkit.github.io-6d4aff"></a>
</p>

<p align="center">
  <a href="https://limn-toolkit.github.io/limn-toolkit">Сайт</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/docs/install/">Начать</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/components/">Компоненты</a> ·
  <a href="https://limn-toolkit.github.io/limn-toolkit/api/">Справочник API</a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.pt-BR.md">Português (Brasil)</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <b>Русский</b> ·
  <a href="README.zh-Hans.md">简体中文</a> ·
  <a href="README.zh-Hant.md">繁體中文</a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/showcase-kitchen-dark.webp">
    <img src="media/readme/showcase-kitchen-light.webp" alt="Приложение на Limn: строка меню, вкладки, формы, диаграммы и выбор темы" width="900">
  </picture>
</p>

Limn рисует свои пиксели сам. Виджеты, компоновка, текст, диаграммы, медиа и 3D-вьюпорт умещаются
в одной зависимости, **без Swing, без JavaFX и без нативного тулкита под низом**.

## Установка

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-backend-lwjgl:0.2.0")
}
```

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>io.github.limn-toolkit</groupId>
  <artifactId>limn-backend-lwjgl</artifactId>
  <version>0.2.0</version>
</dependency>
```

</details>

Эта одна строка и есть вся установка. `limn-backend-lwjgl` — это окно и отрисовка, и он
экспортирует `limn-toolkit` — виджеты, компоновку и граф сцены — всему, что от него зависит.
Бэкенд приносит нативные библиотеки LWJGL для всех настольных платформ, поэтому классификатор
выбирать не нужно.

> [!IMPORTANT]
> На macOS JVM требуется `-XstartOnFirstThread`. Это единственная особенность платформы, с которой
> вы столкнётесь в первый же день, и она только для macOS — JVM на другой платформе с этим флагом
> не запустится.

### Воспроизведение видео

`VideoView` входит в строку выше, как и стоящие за ним декодеры на чистой Java. Этого хватает на
Y4M и синтетический источник; для MP4 и Matroska нужен FFmpeg, а он вынесен в отдельную
зависимость, потому что это единственная часть Limn с нативной нагрузкой и собственной лицензией.

```kotlin
dependencies {
    implementation("io.github.limn-toolkit:limn-video-ffmpeg:0.2.0")
    runtimeOnly("io.github.limn-toolkit:limn-video-ffmpeg:0.2.0:natives-macos-aarch64")
}
```

Первая строка приносит Java-код и JNI-прослойку для всех платформ. Вторая приносит библиотеки
FFmpeg, которые публикуются по одному классификатору на цель, поэтому машина скачивает около двух
мегабайт, а не все шесть:

```
natives-linux-x86_64     natives-macos-x86_64     natives-windows-x86_64
natives-linux-aarch64    natives-macos-aarch64    natives-windows-aarch64
```

Берите вместо этого `natives-all`, когда одна сборка отправляется на все платформы и не может
знать, на какую машину попадёт. Указать несколько тоже ничто не мешает: дистрибутиву на две цели
нужны две.

Не указывайте классификатор вовсе — тулкит всё равно соберётся и запустится: декодер сообщит, что
недоступен, и назовёт платформу, которую искал, а всё, что не FFmpeg, продолжит работать. Сборка
FFmpeg идёт под LGPL-2.1-или-позднее, связана динамически и заменяема, а текст своей лицензии
несёт внутри того jar, в котором лежит.

## Окно на экране

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

Никакого языка разметки, никакого обработчика аннотаций, никакого плагина сборки. Виджеты — это
объекты, которые вы создаёте.

## Что вы получаете

**Набор компонентов, который не нужно писать самому.** Кнопки, поля, списки, вкладки, меню,
диалоги, разделённые панели, выбор цвета, столбчатые, линейные и кольцевые диаграммы и
виртуализированный список, в котором миллион строк стоит столько же, сколько двадцать. Каждый из
них берёт цвет, форму и плотность из темы.

**Компоновка, которая помещается в голове.** Четыре виджета и один маркер: колонка складывает, ряд
распределяет, стопка накладывает, отступ отодвигает, а `Expanded` говорит, кому достанется
оставшееся место. Нет ни решателя ограничений, который надо настраивать, ни менеджера компоновки,
который надо ставить.

**Облик вашего продукта, а не облик тулкита.** Тема сводится к простым данным — каждый цвет, радиус
скругления, шаг размера, который наследует любой элемент управления, — и один вызов меняет её во
время работы.

<p align="center">
  <img src="media/readme/home-mosaic.webp" alt="Один и тот же интерфейс, отрисованный в семи темах" width="900">
</p>

**Языки ваших пользователей.** Текст измеряется теми же метриками, которыми он рисуется, а
подстановка шрифтов работает посимвольно, поэтому латиница, греческий, кириллица и CJK смешиваются
в одной строке, а вам не приходится выбирать начертание. Методы ввода составляют текст прямо в
поле, а редактирование движется по кластерам графем.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="media/readme/home-languages-dark.webp">
    <img src="media/readme/home-languages-light.webp" alt="Один и тот же экран, снятый на японском, упрощённом китайском, корейском и русском" width="900">
  </picture>
</p>

**Видео и 3D тоже виджеты.** 3D-вьюпорт с физически корректными материалами и видеоплеер,
скомпонованные как обычные виджеты: прокручиваемая область их обрезает, стопка рисует поверх, и оба
участвуют в компоновке так же, как подпись.

<p align="center">
  <img src="media/readme/showcase-viewport-3d-light.webp" alt="3D-вьюпорт, встроенный в обычное окно" width="900">
</p>

## Модули

| | |
| --- | --- |
| `limn-toolkit` | виджеты, компоновка, граф сцены, SPI бэкендов и видеодекодеры на чистой Java; не зависит ни от чего |
| `limn-backend-lwjgl` | GLFW, OpenGL и stb за этими SPI |
| `limn-video-ffmpeg` | H.264/HEVC/VP9/VP8 и AAC/Opus/Vorbis через FFmpeg; по одному классификатору на настольную цель |
| `limn-icons-tabler` | набор иконок Tabler, если он вам нужен |
| `limn-theme-editor` | экран, в котором создаётся тема, встраиваемый в ваше приложение |

## Прежде чем решиться

Любой тулкит чем-то жертвует. Вот эти компромиссы, названные сразу, потому что обнаружить их на
третьей неделе хуже, чем прочитать сейчас.

- **Нет шейпинга сложных письменностей.** Арабскому, ивриту и индийским письменностям нужны
  контекстные соединения и переупорядочивание, которых текстовый слой не реализует, и направления
  письма справа налево тоже нет. Переводы на эти языки намеренно не публикуются: это лучше, чем
  нарисовать их неправильно.
- **Нет моста к программам чтения с экрана.** Навигация с клавиатуры и кольца фокуса сделаны
  полностью, но ничего не передаётся в API доступности платформы.
- **До 1.0.** API ещё меняется между релизами, а единственным путём отрисовки остаётся OpenGL.
  Фиксируйте версию и читайте примечания к выпускам.

## Документация

[Сайт](https://limn-toolkit.github.io/limn-toolkit) и есть документация:
[руководство по установке](https://limn-toolkit.github.io/limn-toolkit/docs/install/), которое
заканчивается работающей программой,
[галерея компонентов](https://limn-toolkit.github.io/limn-toolkit/components/), где каждая картинка
отрисована тулкитом во время той сборки, и полный
[справочник API](https://limn-toolkit.github.io/limn-toolkit/api/).

Проектные решения лежат в [`docs/adr/`](docs/adr/), а порядок выпуска релиза — в
[`RELEASING.md`](RELEASING.md).

## Сборка из исходников

```bash
./gradlew check          # compiles, tests and builds the Javadoc every module publishes
./gradlew :limn-demo:run # the demo application, every component in one window
```

Артефакты нацелены на JDK 17; сама сборка выполняется на 21. На машине без GPU тесты, опирающиеся
на GL, пропускаются, а не падают.

Воспроизведение MP4 требует нативной нагрузки, которой в этом репозитории **нет**: релиз собирает
её для шести платформ и публикует по одному классификатору на каждую. Чтобы получить её локально,
`./scripts/build-ffmpeg.sh` собирает её примерно за минуту, а `./scripts/fetch-ffmpeg.sh`
распаковывает её из опубликованного jar.

## Лицензия

[Apache-2.0](LICENSE), включая явную патентную лицензию. Встроенные компоненты перечислены со
своими лицензиями в [`NOTICE`](NOTICE); декодер FFmpeg идёт под LGPL-2.1-или-позднее и несёт текст
своей лицензии внутри своего jar.
